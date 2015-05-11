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

package net.jexler.internal;

import groovy.lang.GroovyShell;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import net.jexler.MetaInfo;

/**
 * Basic default implementation of meta info interface.
 *
 * @author $(whois jexler.net)
 */
public class BasicMetaInfo extends HashMap<String,Object> implements MetaInfo {

	private static final long serialVersionUID = 125418652799853484L;

	public static final MetaInfo EMPTY = new BasicMetaInfo();
	
	/**
	 * Constructor for empty meta info.
	 */
	private BasicMetaInfo() {
		// empty map
	}
	
	/**
	 * Constructor from file.
	 */
	public BasicMetaInfo(File file) throws IOException {
		if (!file.exists()) {
			return;
		}

		try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
			// read first line of jexler script
			String line = reader.readLine();
			if (line == null) {
				return;
			}

			BasicJexler.WorkaroundGroovy7407.wrapGrapeEngineIfConfigured();

			// evaluate first line as groovy script
			Object obj;
			try {
				obj = new GroovyShell().evaluate(line);
			} catch (Throwable t) {
            	// (script may throw anything, checked or not)
				return;
			}

			// evaluated to a map?
			if (obj == null || !(obj instanceof Map)) {
				return;
			}

			// set map
			@SuppressWarnings("unchecked")
			Map<String,Object> map = (Map<String,Object>)obj;
			this.putAll(map);
		}
	}
		
    @Override
    public boolean isOn(String name, boolean defaultValue) {
    	Object o = get(name);
    	if (o != null && o instanceof Boolean) {
    		return (Boolean)o;
    	} else {
    		return defaultValue;
    	}
    }

}

