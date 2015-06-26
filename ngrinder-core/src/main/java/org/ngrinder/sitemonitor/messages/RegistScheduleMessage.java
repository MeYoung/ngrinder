/* 
 * Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License. 
 */
package org.ngrinder.sitemonitor.messages;

import net.grinder.communication.Message;

/**
 * @author Gisoo Gwon
 */
public class RegistScheduleMessage implements Message {

	private static final long serialVersionUID = 8032597041213935838L;

	private final String sitemonitorId;
	private final String scriptname;
	private final String propHosts;
	private final String propParam;
	
	/**
	 * The Constructor.
	 * 
	 * @param sitemonitorId
	 * @param scriptname
	 * @param propHosts
	 * @param propParam
	 */
	public RegistScheduleMessage(String sitemonitorId, String scriptname, String propHosts,
		String propParam) {
		this.sitemonitorId = sitemonitorId;
		this.scriptname = scriptname;
		this.propHosts = propHosts;
		this.propParam = propParam;
	}

	public String getSitemonitorId() {
		return sitemonitorId;
	}

	public String getScriptname() {
		return scriptname;
	}

	public String getPropHosts() {
		return propHosts;
	}

	public String getPropParam() {
		return propParam;
	}

}