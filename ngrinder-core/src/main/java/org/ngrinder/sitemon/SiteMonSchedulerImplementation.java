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
package org.ngrinder.sitemon;

import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.apache.commons.lang.time.DateUtils;
import org.ngrinder.common.util.ThreadUtils;
import org.ngrinder.sitemon.messages.RegistScheduleMessage;
import org.ngrinder.sitemon.model.SiteMonResult;
import org.ngrinder.sitemon.model.SiteMonResultLog;
import org.ngrinder.util.AgentStateMonitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.grinder.engine.agent.SiteMonScriptRunner;

/**
 * Manage process for execute sitemon script.
 * 
 * @author Gisoo Gwon
 */
public class SiteMonSchedulerImplementation implements SiteMonScheduler {

	private static final Logger LOGGER = LoggerFactory.getLogger("monitor scheduler impl");
	private static final int THREAD_POOL_SIZE = 10;
	private static final long DEFAULT_REPEAT_TIME = DateUtils.MILLIS_PER_MINUTE;

	private final SiteMonScriptRunner scriptRunner;
	private final AgentStateMonitor agentStateMonitor;

	private ExecutorService executor;
	private long repeatTime = DEFAULT_REPEAT_TIME;
	private boolean shutdown = false;

	Map<String, RegistScheduleMessage> siteMonMap = new HashMap<String, RegistScheduleMessage>();
	Map<String, MonitoringIntervalChecker> intervalCheckerMap = new HashMap<String, MonitoringIntervalChecker>();

	/**
	 * The constructor.
	 * Default repeat time is {@code DEFAULT_REPEAT_TIME}
	 * @param scriptRunner 
	 * @param agentStateMonitor
	 */
	public SiteMonSchedulerImplementation(SiteMonScriptRunner scriptRunner,
		AgentStateMonitor agentStateMonitor) {
		this(scriptRunner, agentStateMonitor, DEFAULT_REPEAT_TIME);
	}
	
	/**
	 * The constructor.
	 * 
	 * @param scriptRunner
	 * @param agentStateMonitor
	 * @param repeatTime
	 */
	public SiteMonSchedulerImplementation(SiteMonScriptRunner scriptRunner,
		AgentStateMonitor agentStateMonitor, long repeatTime) {
		this.agentStateMonitor = agentStateMonitor;
		this.scriptRunner = scriptRunner;
		this.repeatTime = repeatTime;
		this.agentStateMonitor.setRepeatInterval(repeatTime);
		executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
		new ScriptRunnerDaemon().start();
	}

	/**
	 * @param message
	 */
	@Override
	public void regist(final RegistScheduleMessage message) {
		siteMonMap.put(message.getSiteMonId(), message);
		intervalCheckerMap.put(message.getSiteMonId(),
			new MonitoringIntervalChecker(message.getMonitoringInterval()));
		agentStateMonitor.setRegistScriptCount(siteMonMap.size());
	}

	/**
	 * @param groupName
	 */
	@Override
	public void unregist(String siteMonId) {
		siteMonMap.remove(siteMonId);
		agentStateMonitor.clear();
		agentStateMonitor.setRegistScriptCount(siteMonMap.size());
	}
	
	public List<SiteMonResult> pollAllSiteMonResult() {
		return scriptRunner.pollAllResult();
	}
	
	public List<SiteMonResultLog> pollAllSiteMonResultLog() {
		return scriptRunner.pollAllResultLog();
	}

	public void setRepeatTime(long repeatTime) {
		this.repeatTime = repeatTime;
	}

	/**
	 * destroy process.
	 */
	@Override
	public void shutdown() {
		shutdown = true;
		scriptRunner.shutdown();
	}

	class ScriptRunnerDaemon extends Thread {

		ScriptRunnerDaemon() {
			setDaemon(true);
		}

		@Override
		public void run() {
			while (!shutdown) {
				LOGGER.debug("Sitemon runner awake! regist sitemon cnt is {}",
					siteMonMap.size());

				long st = System.nanoTime();
				List<Future<Object>> futures = runScriptUsingThreadPool();
				waitScriptComplete(futures);
				long useTime = (System.nanoTime() - st) / 1000 / 1000 ;
				agentStateMonitor.recordUseTime(useTime);
				sleepForRepeatCycle(useTime);
			}
		}

		private void sleepForRepeatCycle(long usedTime) {
			if (usedTime < repeatTime) {
				ThreadUtils.sleep(repeatTime - usedTime);
			}
		}

		private void waitScriptComplete(List<Future<Object>> futures) {
			for (Future<Object> future : futures) {
				try {
					future.get();
				} catch (Exception e) {
					LOGGER.error("script run failed {}", e.getMessage());
				}
			}
		}

		private List<Future<Object>> runScriptUsingThreadPool() {
			Calendar cal = Calendar.getInstance();
			cal.set(Calendar.SECOND, 0);
			cal.set(Calendar.MILLISECOND, 0);
			final long current = cal.getTimeInMillis();
			List<Future<Object>> futures = new LinkedList<Future<Object>>();
			for (Entry<String, RegistScheduleMessage> entry : siteMonMap.entrySet()) {
				final String siteMonId = entry.getKey();
				final RegistScheduleMessage message = entry.getValue();
				
				if (intervalCheckerMap.get(siteMonId).skip()) {
					continue;
				}

				Callable<Object> task = new Callable<Object>() {
					@Override
					public Object call() throws Exception {
						scriptRunner.runWorker(siteMonId, message.getScriptname(),
							message.getPropHosts(), message.getPropParam(),
							message.getErrorCallback(), current);
						return null;
					}
				};
				futures.add(executor.submit(task));
				LOGGER.debug("submit task for {}", siteMonId);
			}
			return futures;
		}
	}
	
	class MonitoringIntervalChecker {
		
		private final int interval;
		private int remainSkipCount;

		MonitoringIntervalChecker(int interval) {
			this.interval = interval;
			remainSkipCount = interval;
		}
		
		public boolean skip() {
			if (--remainSkipCount > 0) {
				return true;
			}
			remainSkipCount = interval;
			return false;
		}
		
	}

}
