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
package net.grinder.engine.process;

import java.net.UnknownHostException;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

import org.ngrinder.sitemon.messages.ShutdownSiteMonProcessMessage;
import org.ngrinder.sitemon.messages.SiteMonResultMessage;
import org.ngrinder.sitemon.model.SiteMonResult;
import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.grinder.common.GrinderException;
import net.grinder.common.GrinderProperties;
import net.grinder.common.SkeletonThreadLifeCycleListener;
import net.grinder.common.Test;
import net.grinder.common.processidentity.ProcessReport;
import net.grinder.common.processidentity.WorkerIdentity;
import net.grinder.communication.ClientSender;
import net.grinder.communication.CommunicationException;
import net.grinder.communication.ConnectionType;
import net.grinder.communication.Connector;
import net.grinder.communication.MessageDispatchRegistry.Handler;
import net.grinder.communication.MessageDispatchSender;
import net.grinder.communication.MessagePump;
import net.grinder.communication.QueuedSender;
import net.grinder.communication.QueuedSenderDecorator;
import net.grinder.communication.Receiver;
import net.grinder.engine.common.ConnectorFactory;
import net.grinder.engine.common.EngineException;
import net.grinder.engine.common.ScriptLocation;
import net.grinder.engine.messages.InitialiseGrinderMessage;
import net.grinder.engine.process.dcr.DCRContextImplementation;
import net.grinder.messages.console.WorkerAddress;
import net.grinder.messages.console.WorkerProcessReportMessage;
import net.grinder.script.Grinder;
import net.grinder.script.InternalScriptContext;
import net.grinder.script.InvalidContextException;
import net.grinder.script.Statistics;
import net.grinder.scriptengine.Instrumenter;
import net.grinder.scriptengine.ScriptEngineService.ScriptEngine;
import net.grinder.scriptengine.ScriptEngineService.WorkerRunnable;
import net.grinder.scriptengine.ScriptExecutionException;
import net.grinder.statistics.StatisticsIndexMap;
import net.grinder.statistics.StatisticsServices;
import net.grinder.statistics.StatisticsServicesImplementation;
import net.grinder.statistics.StatisticsSet;
import net.grinder.statistics.TestStatisticsMap;
import net.grinder.synchronisation.BarrierGroups;
import net.grinder.synchronisation.BarrierIdentityGenerator;
import net.grinder.synchronisation.ClientBarrierGroups;
import net.grinder.util.ListenerSupport;
import net.grinder.util.ListenerSupport.Informer;
import net.grinder.util.Sleeper;
import net.grinder.util.SleeperImplementation;
import net.grinder.util.StandardTimeAuthority;
import net.grinder.util.TimeAuthority;
import net.grinder.util.thread.BooleanCondition;
import net.grinder.util.thread.Condition;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.Context;
import ch.qos.logback.core.joran.spi.JoranException;

/**
 * @author Gisoo Gwon
 */
public class SiteMonProcess {
	// logger
	private final LoggerContext m_logbackLoggerContext;
	private final Logger m_terminalLogger;
	private Logger m_logger = null;

	// value from InitialiseGrinderMessage
	private final ScriptLocation script;
	private final GrinderProperties properties;
	private final boolean m_reportTimesToConsole;
	private final String siteMonId;
	private final String logDirectory;
	private final WorkerIdentity workerIdentity;

	// script engine
	private final ScriptEngineContainer scriptEngineContainer;
	private final ScriptEngine scriptEngine;

	private final Condition m_eventSynchronisation = new Condition();
	private final QueuedSender m_consoleSender;
	private final Sleeper m_sleeper;
	private final StatisticsServices m_statisticsServices;
	private final TestStatisticsHelperImplementation m_testStatisticsHelper;
	private final TestRegistryImplementation m_testRegistryImplementation;
	private final MessagePump m_messagePump;

	private final ThreadStarter m_invalidThreadStarter = new InvalidThreadStarter();

	private final Times m_times = new Times();

	private final ThreadContexts m_threadContexts = new ThreadContexts();

	private final ListenerSupport<ProcessLifeCycleListener> m_processLifeCycleListeners = new ListenerSupport<ProcessLifeCycleListener>();

	// Guarded by m_eventSynchronisation.
	private ThreadStarter m_threadStarter = m_invalidThreadStarter;

	private boolean m_recievedShutdown = false;
	private boolean m_communicationShutdown;

	/**
	 * Creates a new <code>GrinderProcess</code> instance.
	 *
	 * @param agentReceiver Receiver used to listen to the agent.
	 * @throws net.grinder.common.GrinderException
	 *          If the process could not be created.
	 */
	public SiteMonProcess(final Receiver agentReceiver) throws GrinderException {
		try {
			// init value
			InitialiseGrinderMessage initialisationMessage = (InitialiseGrinderMessage)agentReceiver.waitForMessage();

			if (initialisationMessage == null) {
				throw new EngineException("No control stream from agent");
			}

			script = initialisationMessage.getScript();
			properties = initialisationMessage.getProperties();
			workerIdentity = initialisationMessage.getWorkerIdentity();

			siteMonId = properties.getProperty("sitemon.id");
			logDirectory = properties.getProperty(GrinderProperties.LOG_DIRECTORY, ".");
			m_reportTimesToConsole = properties.getBoolean("grinder.reportTimesToConsole", true);

			// init logger
			m_logbackLoggerContext = configureLogging(workerIdentity.getName(), logDirectory);
			m_terminalLogger = LoggerFactory.getLogger(workerIdentity.getName());
			m_logger = LoggerFactory.getLogger("worker");

			// init console connection
			MessageDispatchSender messageDispatcher = new MessageDispatchSender();
			Connector connector = new ConnectorFactory(ConnectionType.WORKER).create(properties);
			m_consoleSender = new QueuedSenderDecorator(ClientSender.connect(connector, new WorkerAddress(workerIdentity)));
			BarrierGroups barrierGroups = new ClientBarrierGroups(m_consoleSender, messageDispatcher);
			BarrierIdentityGenerator barrierIdentityGenerator = new BarrierIdentityGenerator(workerIdentity);
			ThreadStarter delegatingThreadStarter = new ThreadStarter() {
				@Override
				public int startThread(final Object testRunner) throws EngineException, InvalidContextException {
					final ThreadStarter threadStarter;

					synchronized (m_eventSynchronisation) {
						threadStarter = m_threadStarter;
					}

					return threadStarter.startThread(testRunner);
				}
			};

			m_statisticsServices = StatisticsServicesImplementation.getInstance();

			m_testStatisticsHelper = new TestStatisticsHelperImplementation(m_statisticsServices.getStatisticsIndexMap());

			m_testRegistryImplementation = new TestRegistryImplementation(m_threadContexts, m_statisticsServices.getStatisticsSetFactory(),
				m_testStatisticsHelper, m_times.getTimeAuthority());

			final Logger externalLogger = new ExternalLogger(m_logger, m_threadContexts);

			m_sleeper = new SleeperImplementation(m_times.getTimeAuthority(), externalLogger, properties.getDouble("grinder.sleepTimeFactor", 1.0d),
				properties.getDouble("grinder.sleepTimeVariation", 0.2d));

			final Statistics scriptStatistics = new ScriptStatisticsImplementation(m_threadContexts, m_statisticsServices, m_consoleSender);

			final ThreadStopper threadStopper = new ThreadStopper() {
				@Override
				public boolean stopThread(final int threadNumber) {
					return m_threadContexts.shutdown(threadNumber);
				}
			};

			final InternalScriptContext scriptContext = new ScriptContextImplementation(workerIdentity,
				initialisationMessage.getFirstWorkerIdentity(), m_threadContexts, properties, externalLogger, m_sleeper,
				new SSLControlImplementation(m_threadContexts), scriptStatistics, m_testRegistryImplementation, delegatingThreadStarter,
				threadStopper, barrierGroups, barrierIdentityGenerator);

			Grinder.grinder = scriptContext;

			final PluginRegistryImplementation pluginRegistry = new PluginRegistryImplementation(externalLogger, scriptContext, m_threadContexts,
				m_statisticsServices, m_times.getTimeAuthority());

			m_processLifeCycleListeners.add(pluginRegistry);
			m_processLifeCycleListeners.add(m_threadContexts);

			// If we don't call getLocalHost() before spawning our
			// ConsoleListener thread, any attempt to call it afterwards will
			// silently crash the JVM. Reproduced with both J2SE 1.3.1-b02 and
			// J2SE 1.4.1_03-b02 on W2K. Do not ask me why, I've stopped
			// caring.
			try {
				//noinspection ResultOfMethodCallIgnored
				java.net.InetAddress.getLocalHost();
			} catch (final UnknownHostException e) { /* Ignore */
			}

			m_messagePump = new MessagePump(agentReceiver, messageDispatcher, 1);
			messageDispatcher.set(ShutdownSiteMonProcessMessage.class, new Handler<ShutdownSiteMonProcessMessage>() {
				@Override
				public void handle(ShutdownSiteMonProcessMessage message) throws CommunicationException {
					m_recievedShutdown = true;
				}

				@Override
				public void shutdown() {
				}
			});
			m_messagePump.start();

			// init script engine
			scriptEngineContainer = new ScriptEngineContainer(properties, m_logger, DCRContextImplementation.create(m_logger), script);

			Instrumenter instrumenter = scriptEngineContainer.createInstrumenter();
			m_testRegistryImplementation.setInstrumenter(instrumenter);
			m_terminalLogger.info("Instrumentation agents: {}", instrumenter.getDescription());
			
			scriptEngine = scriptEngineContainer.getScriptEngine(script);

			// init send process status schedule
			Timer timer = new Timer(true);
			int reportToConsoleInterval = properties.getInt("grinder.reportToConsole.interval", 500);
			timer.schedule(new ReportToConsoleTimerTask(), 0, reportToConsoleInterval);
		} catch (GrinderException e) {
			if (m_logger != null) {
				m_logger.error("Error running worker process", e);
			}
			throw e;
		}
	}

	private LoggerContext configureLogging(final String workerName, final String logDirectory) throws EngineException {
		// TODO : log file path 설정
		final ILoggerFactory iLoggerFactory = LoggerFactory.getILoggerFactory();

		if (iLoggerFactory instanceof Context) {
			final Context context = (Context)iLoggerFactory;
			final LoggerContext result = (LoggerContext)iLoggerFactory;

			final JoranConfigurator configurator = new JoranConfigurator();
			configurator.setContext(context);
			context.putProperty("WORKER_NAME", workerName);
			context.putProperty("LOG_DIRECTORY", logDirectory);

			try {
				configurator.doConfigure(GrinderProcess.class.getResource("/logback-worker.xml"));
			} catch (final JoranException e) {
				throw new EngineException("Could not initialise logger", e);
			}

			return result;
		} else {
			m_terminalLogger.warn("Logback not found; grinder log configuration will be ignored.\n"
				+ "Consider adding logback-classic to the start of the CLASSPATH.");

			return null;
		}
	}

	public void run() throws GrinderException {
		try {
			m_terminalLogger.info("Running \"{}\" using {}", script, scriptEngine.getDescription());

			final ThreadSynchronisation threadSynchronisation = new ThreadSynchronisation(m_eventSynchronisation);

			ThreadStarterImplementation threadStarter = null;
			synchronized (m_eventSynchronisation) {
				threadStarter = new ThreadStarterImplementation(threadSynchronisation, scriptEngine);
				threadStarter.startThread(null);
			}

			threadSynchronisation.startThreads();

			Times times = new Times();
			times.setExecutionStartTime();

			// Wait for a termination event.
			synchronized (m_eventSynchronisation) {
				while (!threadSynchronisation.isFinished()) {
					if (m_recievedShutdown) {
						m_terminalLogger.info("Script {} shutdown.", script.getFile());
						break;
					}
					m_eventSynchronisation.waitNoInterrruptException();
				}
			}

			final long elapsedTime = times.getElapsedTime();
			sendResult(threadSynchronisation);
			
			m_terminalLogger.info("elapsed time is {} ms", elapsedTime);
			m_terminalLogger.info("Finished");

		} catch (final ScriptExecutionException e) {
			m_logger.error("Aborting process - {}", e.getShortMessage(), e);
			m_terminalLogger.error("aborting process - {}", e.getShortMessage(), e);
		} catch (EngineException e) {
			m_logger.error("Script error - {}", e.getMessage(), e);
			throw e;
		}
	}

	public void shutdown(final boolean inputStreamIsStdin) {
		if (!inputStreamIsStdin) {
			// Sadly it appears its impossible to interrupt a read() on a
			// process
			// input stream (at least under W2K), so we can't shut down the
			// message
			// pump cleanly. It runs in a daemon thread, so this isn't a big
			// deal.
			m_messagePump.shutdown();
		}

		// Logback doesn't stop its loggers on exit (see LBCORE-202). We do
		// so explicitly to flush our BufferedEchoMessageEncoder.
		if (m_logbackLoggerContext != null) {
			m_logbackLoggerContext.stop();
		}

		if (m_consoleSender != null) {
			m_consoleSender.shutdown();
		}
		
		if (scriptEngine != null) {
			try {
				scriptEngine.shutdown();
			} catch (EngineException e) {
				m_terminalLogger.warn("Failed shutdown {} script engine.", script.getFile());
			}
		}
	}

	private void sendResult(ThreadSynchronisation threads) {
		if (!m_communicationShutdown) {
			TestStatisticsMap sample = null;
			synchronized (m_testRegistryImplementation) {
				sample = m_testRegistryImplementation.getTestStatisticsMap().reset();
			}

			if (sample.size() > 0) {
				if (!m_reportTimesToConsole) {
					m_testStatisticsHelper.removeTestTimeFromSample(sample);
				}

				try {
					SiteMonResultMessage message = new SiteMonResultMessage();
					message.addAll(extractResults(sample));
					m_consoleSender.send(message);
					m_consoleSender.flush();
				} catch (CommunicationException e) {
					m_terminalLogger.info("Report to console failed", e);
					
					m_communicationShutdown = true;
				}
			}
		}
	}
	
	private List<SiteMonResult> extractResults(TestStatisticsMap sample) {
		final List<SiteMonResult> results = new LinkedList<SiteMonResult>();
		final StatisticsIndexMap indexMap = m_statisticsServices.getStatisticsIndexMap();
		final Date timestamp = new Date();
		sample.new ForEach() {
			@Override
			protected void next(Test test, StatisticsSet statistics) {
				SiteMonResult result = new SiteMonResult(siteMonId, test.getNumber(),
					statistics.getCount(indexMap.getLongSampleIndex("timedTests")),
					statistics.getValue(indexMap.getLongIndex("errors")),
					statistics.getSum(indexMap.getLongSampleIndex("timedTests")),
					timestamp);
				results.add(result);
			}
		}.iterate();
		return results;
	}

	private class ReportToConsoleTimerTask extends TimerTask {
		@Override
		public void run() {
			if (!m_communicationShutdown) {
				try {
					m_consoleSender.send(new WorkerProcessReportMessage(ProcessReport.STATE_RUNNING, (short)1, (short)1));
					m_consoleSender.flush();
				} catch (final CommunicationException e) {
					m_terminalLogger.info("Report to console failed", e);

					m_communicationShutdown = true;
				}
			}
		}
	}

	/**
	 * Implement {@link net.grinder.engine.process.WorkerThreadSynchronisation}. I looked hard at JSR 166's
	 * <code>CountDownLatch</code> and <code>CyclicBarrier</code>, but neither of them allow for the
	 * waiting thread to be interrupted by other events.
	 * <p/>
	 * <p>
	 * Package scope for unit tests.
	 * </p>
	 */
	static class ThreadSynchronisation implements WorkerThreadSynchronisation {
		final BooleanCondition m_started = new BooleanCondition();
		final Condition m_threadEventCondition;

		short m_numberCreated = 0;
		short m_numberAwaitingStart = 0;
		short m_numberFinished = 0;
		short m_numberRunning = 0;

		ThreadSynchronisation(final Condition condition) {
			m_threadEventCondition = condition;
		}

		/**
		 * The number of worker threads that have been created but not run to completion.
		 */
		public short getNumberOfRunningThreads() {
			synchronized (m_threadEventCondition) {
				return (short)(m_numberCreated - m_numberFinished);
			}
		}

		public boolean isReadyToStart() {
			synchronized (m_threadEventCondition) {
				return m_numberAwaitingStart >= getNumberOfRunningThreads();
			}
		}

		public boolean isFinished() {
			return getNumberOfRunningThreads() <= 0;
		}

		/**
		 * The number of worker threads that have been created.
		 */
		public short getTotalNumberOfThreads() {
			synchronized (m_threadEventCondition) {
				return m_numberCreated;
			}
		}

		@Override
		public void threadCreated() {
			synchronized (m_threadEventCondition) {
				++m_numberCreated;
			}
		}

		public void startThreads() {
			synchronized (m_threadEventCondition) {
				while (!isReadyToStart()) {
					m_threadEventCondition.waitNoInterrruptException();
				}

				m_numberAwaitingStart = 0;
			}

			m_started.set(true);
		}

		@Override
		public void awaitStart() {
			synchronized (m_threadEventCondition) {
				++m_numberAwaitingStart;

				if (isReadyToStart()) {
					m_threadEventCondition.notifyAll();
				}
			}

			m_started.await(true);
		}

		@Override
		public void threadFinished() {
			synchronized (m_threadEventCondition) {
				++m_numberFinished;

				if (isReadyToStart() || isFinished()) {
					m_threadEventCondition.notifyAll();
				}
			}
		}
	}

	private final class ThreadStarterImplementation implements ThreadStarter {
		private final ThreadSynchronisation m_threadSynchronisation;
		private final ScriptEngine m_scriptEngine;
		private final WorkerRunnableFactory m_defaultWorkerRunnableFactory;

		private final ProcessLifeCycleListener m_threadLifeCycleCallbacks = new ProcessLifeCycleListener() {
			@Override
			public void threadCreated(final ThreadContext threadContext) {
				m_processLifeCycleListeners.apply(new Informer<ProcessLifeCycleListener>() {
					@Override
					public void inform(final ProcessLifeCycleListener listener) {
						listener.threadCreated(threadContext);
					}
				});
			}

			@Override
			public void threadStarted(final ThreadContext threadContext) {
				m_processLifeCycleListeners.apply(new Informer<ProcessLifeCycleListener>() {
					@Override
					public void inform(final ProcessLifeCycleListener listener) {
						listener.threadStarted(threadContext);
					}
				});
			}
		};

		private int m_i = -1;

		private ThreadStarterImplementation(final ThreadSynchronisation threadSynchronisation, final ScriptEngine scriptEngine) {
			m_threadSynchronisation = threadSynchronisation;
			m_scriptEngine = scriptEngine;

			m_defaultWorkerRunnableFactory = new WorkerRunnableFactory() {
				@Override
				public WorkerRunnable create() throws EngineException {
					return m_scriptEngine.createWorkerRunnable();
				}
			};
		}

		@Override
		public int startThread(final Object testRunner) throws EngineException {
			final int threadNumber;
			synchronized (this) {
				threadNumber = ++m_i;
			}

			final ThreadContext threadContext = new ThreadContextImplementation(properties, m_statisticsServices, threadNumber, m_terminalLogger);

			final WorkerRunnableFactory workerRunnableFactory;

			if (testRunner != null) {
				workerRunnableFactory = new WorkerRunnableFactory() {
					@Override
					public WorkerRunnable create() throws EngineException {
						return m_scriptEngine.createWorkerRunnable(testRunner);
					}
				};
			} else {
				workerRunnableFactory = m_defaultWorkerRunnableFactory;
			}

			final GrinderThread runnable = new GrinderThread(m_logger, threadContext, m_threadSynchronisation, m_threadLifeCycleCallbacks,
				properties, m_sleeper, workerRunnableFactory);

			final Thread t = new Thread(runnable, "thread " + threadNumber);
			t.setDaemon(true);
			t.start();

			return threadNumber;
		}
	}

	/**
	 * Package scope for unit tests.
	 */
	static final class InvalidThreadStarter implements ThreadStarter {
		@Override
		public int startThread(final Object testRunner) throws InvalidContextException {
			throw new InvalidContextException("You should not start worker threads until the main thread has "
				+ "initialised the script engine, or after all other threads have "
				+ "shut down. Typically, you should only call startWorkerThread() " + "from another worker thread.");
		}
	}

	/**
	 * Package scope for unit tests.
	 */
	static final class Times {
		private volatile long m_executionStartTime;

		private static final TimeAuthority m_timeAuthority = new StandardTimeAuthority();

		/**
		 * {@link net.grinder.engine.process.GrinderProcess} calls {@link #setExecutionStartTime} just before launching
		 * threads, after which it is never called again.
		 */
		public void setExecutionStartTime() {
			m_executionStartTime = m_timeAuthority.getTimeInMilliseconds();
		}

		/**
		 * {@link net.grinder.engine.process.GrinderProcess} calls {@link #setExecutionStartTime} just before launching
		 * threads, after which it is never called again.
		 *
		 * @return Start of execution, in milliseconds since the Epoch.
		 */
		public long getExecutionStartTime() {
			return m_executionStartTime;
		}

		/**
		 * Elapsed time since execution was started.
		 *
		 * @return The time in milliseconds.
		 * @see #getExecutionStartTime()
		 */
		public long getElapsedTime() {
			return m_timeAuthority.getTimeInMilliseconds() - getExecutionStartTime();
		}

		public TimeAuthority getTimeAuthority() {
			return m_timeAuthority;
		}
	}

	/**
	 * Package scope for unit tests.
	 */
	static final class ThreadContexts implements ProcessLifeCycleListener, ThreadContextLocator {

		private final ThreadLocal<ThreadContext> m_threadContextThreadLocal = new ThreadLocal<ThreadContext>();

		// Guarded by self.
		private final Map<Integer, ThreadContext> m_threadContextsMap = new HashMap<Integer, ThreadContext>();

		// Guarded by m_threadContextsMap.
		private boolean m_allShutdown;

		@Override
		public ThreadContext get() {
			return m_threadContextThreadLocal.get();
		}

		@Override
		public void threadCreated(final ThreadContext threadContext) {
			final Integer threadNumber = threadContext.getThreadNumber();

			final boolean shutdown;

			synchronized (m_threadContextsMap) {
				shutdown = m_allShutdown;

				if (!shutdown) {
					threadContext.registerThreadLifeCycleListener(new SkeletonThreadLifeCycleListener() {
						@Override
						public void endThread() {
							m_threadContextsMap.remove(threadNumber);
						}
					});

					// Very unlikely, harmless race here - we could store a
					// reference to
					// a thread context that is in the process of shutting down.
					m_threadContextsMap.put(threadNumber, threadContext);
				}
			}

			if (shutdown) {
				// Stop new threads in their tracks.
				threadContext.shutdown();
			}
		}

		@Override
		public void threadStarted(final ThreadContext threadContext) {
			m_threadContextThreadLocal.set(threadContext);
		}

		public boolean shutdown(final int threadNumber) {
			final ThreadContext threadContext;

			synchronized (m_threadContextsMap) {
				threadContext = m_threadContextsMap.get(threadNumber);
			}

			if (threadContext != null) {
				threadContext.shutdown();
				return true;
			}

			return false;
		}

		public void shutdownAll() {
			final ThreadContext[] threadContexts;

			synchronized (m_threadContextsMap) {
				m_allShutdown = true;

				threadContexts = m_threadContextsMap.values().toArray(new ThreadContext[m_threadContextsMap.size()]);
			}

			for (final ThreadContext threadContext : threadContexts) {
				threadContext.shutdown();
			}
		}
	}
}
