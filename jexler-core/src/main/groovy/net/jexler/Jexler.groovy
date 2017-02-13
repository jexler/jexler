/*
   Copyright 2012-now $(whois jexler.net)

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/

package net.jexler

import net.jexler.service.Event
import net.jexler.service.Service
import net.jexler.service.ServiceGroup
import net.jexler.service.ServiceState
import net.jexler.service.ServiceUtil
import net.jexler.service.StopEvent

import groovy.grape.Grape
import groovy.grape.GrapeEngine
import groovy.transform.CompileStatic
import groovy.transform.PackageScope
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.customizers.ImportCustomizer
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.lang.reflect.Field
import java.util.concurrent.LinkedBlockingQueue

/**
 * Jexler, runs a Groovy script that handles events.
 *
 * @author $(whois jexler.net)
 */
@CompileStatic
class Jexler implements Service, IssueTracker {

    private static final Logger log = LoggerFactory.getLogger(Jexler.class)

    /**
     * Blocking queue for events sent to a Jexler
     * ('events' variable in jexler scripts).
     *
     * Typically events are taken with {@link Events#take()}
     * in an event loop in the jexler script.
     *
     * @author $(whois jexler.net)
     */
    @CompileStatic
    class Events extends LinkedBlockingQueue<Event> {
        /**
         * Take event from queue (blocks).
         */
        @Override
        Event take() {
            state = ServiceState.IDLE
            while (true) {
                try {
                    final Event event = (Event)super.take()
                    state = ServiceState.BUSY_EVENT
                    return event
                } catch (InterruptedException e) {
                    trackIssue(Jexler.this, 'Could not take event.', e)
                }
            }
        }
    }

    private final File file
    private final String id
    private final JexlerContainer container
    private volatile ServiceState state
    private volatile Script script

    private volatile Thread scriptThread

    /** Event queue. */
    protected final Events events

    /**
     * Group of services.
     * Scripts are free to add services to this list or not - if they do,
     * services are automatically stopped by jexler after the script exits
     * (regularly or throws).
     */
    private final ServiceGroup services

    private final IssueTracker issueTracker

    private Map<String,Object> metaInfoAtStart

    /**
     * Constructor.
     * @param file file with jexler script
     * @param container jexler container that contains this jexler
     */
    Jexler(File file, JexlerContainer container) {
        this.file = file
        this.container = container
        id = container.getJexlerId(file)
        state = ServiceState.OFF
        events = new Events()
        services = new ServiceGroup("${id}.services")
        issueTracker = new IssueTrackerBase()
    }

    /**
     * Initiate jexler start.
     * Immediately marks the jexler service as starting up, then tries to
     * start the script.
     * Typically returns before the jexler script has started or completed
     * to initialize all of its services.
     * The jexler remains in the running state until the script exits in
     * any way, after it has been tried to stop all registered services.
     */
    @Override
    void start() {
        log.info("*** Jexler start: $id")
        if (state.on) {
            return
        }
        state = ServiceState.BUSY_STARTING

        forgetIssues()

        metaInfoAtStart = readMetaInfo()
        if (!issues.empty) {
            state = ServiceState.OFF
            return
        }

        // prepare for compile
        WorkaroundGroovy7407.wrapGrapeEngineIfConfigured(this)
        final CompilerConfiguration config = new CompilerConfiguration()
        final ImportCustomizer importCustomizer = new ImportCustomizer()
        if (metaInfo.autoimport == null || metaInfo.autoimport) {
            importCustomizer.addStarImports(
                    'net.jexler', 'net.jexler.service', 'net.jexler.tool')
            config.addCompilationCustomizers(importCustomizer)
        }
        final GroovyClassLoader loader = new GroovyClassLoader(Thread.currentThread().contextClassLoader, config)
        loader.addClasspath(file.parent)

        // define script binding
        final Jexler jexler = this
        final Binding binding = new Binding([
                'jexler' : jexler,
                'container' : container,
                'events' : events,
                'services' : services,
                'log' : log,
        ])

        // provide access to binding variables in other classes as 'jexlerBinding'
        final Class jexlerBindingClazz = loader.parseClass("""\
          package net.jexler
          @groovy.transform.CompileStatic
          class JexlerBinding { 
            public static Map<String,Object> jexlerBinding
          }""".stripIndent())
        jexlerBindingClazz.getDeclaredField('jexlerBinding').set(null, binding.variables)
        if (metaInfo.autoimport == null || metaInfo.autoimport) {
            importCustomizer.addStaticImport('net.jexler.JexlerBinding', 'jexlerBinding')
        }

        // compile
        final Class clazz
        try {
            clazz = loader.parseClass(file)
        } catch (Throwable t) {
            // (may throw almost anything, checked or not)
            trackIssue(this, 'Script compile failed.', t)
            state = ServiceState.OFF
            return
        }

        // not a runnable script?
        if (!Script.class.isAssignableFrom(clazz)) {
            state = ServiceState.OFF
            return
        }

        // create script and run in a separate thread
        scriptThread = new Thread(
                new Runnable() {
                    void run() {
                        // create script instance
                        try {
                            script = (Script)clazz.newInstance()
                        } catch (Throwable t) {
                            // (may throw anything, checked or not)
                            trackIssue(jexler, 'Script create failed.', t)
                            state = ServiceState.OFF
                            return
                        }

                        // run script
                        script.binding = binding
                        try {
                            script.run()
                        } catch (Throwable t) {
                            // (script may throw anything, checked or not)
                            trackIssue(jexler, 'Script run failed.', t)
                        }

                        state = ServiceState.BUSY_STOPPING

                        try {
                            services.stop()
                        } catch (Throwable t) {
                            trackIssue(services, 'Could not stop services.', t)
                        }
                        events.clear()
                        services.services.clear()

                        script = null
                        state = ServiceState.OFF
                    }
                })
        scriptThread.daemon = true
        scriptThread.name = id
        scriptThread.start()
    }

    /**
     * Handle given event.
     */
    void handle(Event event) {
        events.add(event)
    }

    /**
     * Initiate jexler stop by sending it a stop event to handle.
     */
    @Override
    void stop() {
        log.info("*** Jexler stop: $id")
        if (state.off) {
            return
        }
        handle(new StopEvent(this))
    }

    @Override
    ServiceState getState() {
        return state
    }

    @Override
    void zap() {
        log.info("*** Jexler zap: $id")
        if (state.off) {
            return
        }
        state = ServiceState.OFF
        final ServiceGroup services = this.services
        final Thread scriptThread = this.scriptThread
        final Jexler jexler = this
        new Thread() {
            void run() {
                if (services != null) {
                    services.zap()
                }
                if (scriptThread != null) {
                    try {
                        scriptThread.stop()
                    } catch (Throwable t) {
                        trackIssue(jexler, 'Failed to stop jexler thread.', t)
                    }
                }
            }
        }.start()
    }

    @Override
    String getId() {
        return id
    }

    @Override
    void trackIssue(Issue issue) {
        issueTracker.trackIssue(issue)
    }

    @Override
    void trackIssue(Service service, String message, Throwable cause) {
        issueTracker.trackIssue(service, message, cause)
    }

    @Override
    List<Issue> getIssues() {
        return issueTracker.issues
    }

    @Override
    void forgetIssues() {
        issueTracker.forgetIssues()
    }

    /**
     * Get jexler script file.
     */
    File getFile() {
        return file
    }

    /**
     * Get directory that contains script file.
     */
    File getDir() {
        return file.parentFile
    }

    /**
     * Get container that contains this jexler.
     */
    JexlerContainer getContainer() {
        return container
    }

    /**
     * Get jexler script instance, null if script is not running.
     */
    Script getScript() {
        return script
    }

    /**
     * Get meta info.
     *
     * Read from the jexler file at each call except if the jexler
     * is already running, in that case returns meta info read at
     * the time the jexler was started.
     *
     * The meta info of a jexler is stored in the first line of
     * a jexler script file as a map in Groovy notation.
     *
     * Example:
     * <pre>
     *
     * [ "autostart" : true, "root" : new File('/') ]
     * </pre>
     *
     * Meta info is silently considered empty if for some reason
     * evaluating the line is not possible or fails or or evaluates
     * to an object which is not a map.
     */
    Map<String,Object> getMetaInfo() {
        if (state.on) {
            return metaInfoAtStart
        } else {
            return readMetaInfo()
        }
    }

    private Map<String,Object> readMetaInfo() {
        final Map<String,Object> info = new HashMap<>()

        if (!file.exists()) {
            return info
        }

        final List<String> lines
        try {
            lines = file.readLines()
        } catch (IOException e) {
            String msg = "Could not read meta info from jexler file '$file.absolutePath'."
            trackIssue(this, msg, e)
            return info
        }

        if (lines.empty) {
            return info
        }
        final String firstLine = lines.first()

        WorkaroundGroovy7407.wrapGrapeEngineIfConfigured(this)

        // evaluate first line as groovy script
        final Object obj
        try {
            obj = new GroovyShell().evaluate(firstLine)
        } catch (Throwable ignore) {
            // (script may throw anything, checked or not)
            return info
        }

        // evaluated to a map?
        if (obj == null || !(obj instanceof Map)) {
            return info
        }

        // set map
        final Map<String,Object> map = (Map<String,Object>)obj
        info.putAll(map)

        return info
    }

    // Workaround for bug GROOVY-7407:
    //   "Compilation not thread safe if Grape / Ivy is used in Groovy scripts"
    //   https://issues.apache.org/jira/browse/GROOVY-7407
    @CompileStatic
    @PackageScope
    static class WorkaroundGroovy7407 {
        // boolean whether to wrap the GrapeEngine in the Grape class with a synchronized version
        public static final String GRAPE_ENGINE_WRAP_PROPERTY_NAME =
                'net.jexler.workaround.groovy.7407.grape.engine.wrap'
        public static final String LOG_PREFIX = 'workaround GROOVY-7407:'
        private static volatile Boolean isWrapGrapeEngine
        static void wrapGrapeEngineIfConfigured(Jexler thisJexler) {
            if (isWrapGrapeEngine == null) {
                isWrapGrapeEngine = Boolean.valueOf(System.getProperty(GRAPE_ENGINE_WRAP_PROPERTY_NAME))
                if (isWrapGrapeEngine) {
                    log.trace("$LOG_PREFIX wrapping GrapeEngine...")
                    try {
                        WorkaroundGroovy7407WrappingGrapeEngine.createAndSet()
                        log.trace("$LOG_PREFIX successfully wrapped GrapeEngine")
                    } catch (Exception e) {
                        String msg = "failed to wrap GrapeEngine: $e"
                        log.trace("$LOG_PREFIX failed to wrap GrapeEngine: $e")
                        thisJexler.trackIssue(thisJexler, msg, e)
                    }

                }
            }
        }
        static void resetForUnitTests() {
            isWrapGrapeEngine = null
        }
    }

    /**
     * A GrapeEngine that wraps the current GrapeEngine with a wrapper where all calls
     * of the GrapeEngine API are synchronized with a configurable lock, and allows to
     * set this engine in the Grape class.
     *
     * Works at least in basic situations with Groovy 2.4.3 where the wrapped GrapeEngine
     * is always a GrapeIvy instance (not all public interface methods have been tested).
     *
     * But note that while a synchronized GrapeEngine call is in progress (which may take
     * a long time to complete, if e.g. downloading a JAR file from a maven repo),
     * all other threads that want to pull Grape dependencies must wait...
     *
     * Several things are not so nice about this approach:
     * - This is using a "trick" to set the static protected GrapeEngine instance in Grape;
     *   although nominally protected variables are part of the public API (and in this case
     *   is shown in the online JavaDoc of the Grape class).
     * - The "magic" with "calleeDepth" is based on exact knowledge of what GrapeIvy
     *   does (which, by the way, appears even inconsistent internally(?)), so this
     *   workaround is not guaranteed to be robust if GroovyIvy implementation changes.
     * - I refrained from referring to the GrapeIvy class in the source, because it is
     *   not publicly documented in the online JavaDoc of groovy-core.
     */
    @CompileStatic
    @PackageScope
    static class WorkaroundGroovy7407WrappingGrapeEngine implements GrapeEngine {

        private final Object lock
        private final GrapeEngine innerEngine

        // GrapeIvy.DEFAULT_DEPTH + 1, because is additionally wrapped by this class...
        private static final int DEFAULT_DEPTH = 4

        WorkaroundGroovy7407WrappingGrapeEngine(Object lock, GrapeEngine innerEngine) {
            this.lock = lock
            this.innerEngine = innerEngine
        }

        static void setEngine(GrapeEngine engine) throws Exception {
            final Field field = Grape.class.getDeclaredField('instance')
            field.accessible = true
            field.set(Grape.class, engine)
        }

        // call this somewhere during initialization to apply the workaround
        static void createAndSet() throws Exception {
            engine = new WorkaroundGroovy7407WrappingGrapeEngine(Grape.class, Grape.instance)
        }

        @Override
        Object grab(String endorsedModule) {
            synchronized(lock) {
                return innerEngine.grab(endorsedModule)
            }
        }

        @Override
        Object grab(Map args) {
            synchronized(lock) {
                if (args.get('calleeDepth') == null) {
                    args.put('calleeDepth', DEFAULT_DEPTH + 1)
                }
                return innerEngine.grab(args)
            }
        }

        @Override
        Object grab(Map args, Map... dependencies) {
            synchronized(lock) {
                if (args.get('calleeDepth') == null) {
                    args.put('calleeDepth', DEFAULT_DEPTH)
                }
                return innerEngine.grab(args, dependencies)
            }
        }

        @Override
        Map<String, Map<String, List<String>>> enumerateGrapes() {
            synchronized(lock) {
                return innerEngine.enumerateGrapes()
            }
        }

        @Override
        URI[] resolve(Map args, Map... dependencies) {
            synchronized(lock) {
                if (args.get('calleeDepth') == null) {
                    args.put('calleeDepth', DEFAULT_DEPTH)
                }
                return innerEngine.resolve(args, dependencies)
            }
        }

        @Override
        URI[] resolve(Map args, List depsInfo, Map... dependencies) {
            synchronized(lock) {
                return innerEngine.resolve(args, depsInfo, dependencies)
            }
        }

        @Override
        Map[] listDependencies(ClassLoader classLoader) {
            synchronized(lock) {
                return innerEngine.listDependencies(classLoader)
            }
        }

        @Override
        void addResolver(Map<String, Object> args) {
            synchronized(lock) {
                innerEngine.addResolver(args)
            }
        }
    }


}
