/*
   Copyright 2012-now by Alain Stalder. Made in Switzerland.

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

package ch.artecat.jexler.service

import groovy.transform.CompileStatic

/**
 * Event to stop a running jexler.
 *
 * @author Alain Stalder
 */
@CompileStatic
class StopEvent extends EventBase {

    /**
     * Constructor.
     * @param service the service that is initiating the stop
     */
    StopEvent(final Service service) {
        super(service)
    }

}
