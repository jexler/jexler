/*
   Copyright 2013-now by Alain Stalder. Made in Switzerland.

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       https://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/

package ch.grengine.jexler

import spock.lang.Specification
import spock.lang.Tag
import spock.lang.TempDir

/**
 * Tests the respective class.
 *
 * @author Alain Stalder
 */
@Tag("slow")
class JexlerSlowSpec extends Specification {

    @TempDir
    File tempDir;

    private final static long MS_1_SEC = 1000
    private final static long MS_3_SEC = 3000
    private final static long MS_6_SEC = 6000
    
    def 'TEST SLOW (12 sec) jexler start or shutdown too slow'() {
        given:
        final File dir = tempDir
        final File file = new File(dir, 'Test.groovy')
        file.text = """\
            // Jexler {}
            log.info('before startup wait ' + jexler.id)
            JexlerUtil.waitAtLeast($MS_3_SEC)
            log.info('after startup wait ' + jexler.id)
            while (true) {
              event = events.take()
              if (event instanceof StopEvent) {
                JexlerUtil.waitAtLeast($MS_3_SEC)
                return
              }
            }
            """.stripIndent()
        when:
        final def jexler = new Jexler(file, new JexlerContainer(dir))
        jexler.start()
        JexlerUtil.waitForStartup(jexler, MS_1_SEC)

        then:
        jexler.issues.size() == 1
        jexler.issues.first().message == 'Timeout waiting for jexler startup.'

        when:
        jexler.forgetIssues()
        JexlerUtil.waitForStartup(jexler, MS_6_SEC)

        then:
        jexler.issues.empty

        when:
        jexler.stop()
        JexlerUtil.waitForShutdown(jexler, MS_1_SEC)

        then:
        jexler.issues.size() == 1
        jexler.issues.first().message == 'Timeout waiting for jexler shutdown.'

        when:
        jexler.forgetIssues()
        JexlerUtil.waitForShutdown(jexler, MS_6_SEC)

        then:
        jexler.issues.empty
    }

}
