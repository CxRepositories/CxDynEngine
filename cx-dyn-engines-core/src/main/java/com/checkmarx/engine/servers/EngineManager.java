/*******************************************************************************
 * Copyright (c) 2017-2019 Checkmarx
 *  
 * This software is licensed for customer's internal use only.
 *  
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.  IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 ******************************************************************************/
package com.checkmarx.engine.servers;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.validation.constraints.NotNull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

import com.checkmarx.engine.CxConfig;
import com.checkmarx.engine.domain.DynamicEngine;
import com.checkmarx.engine.domain.DynamicEngine.State;
import com.checkmarx.engine.domain.EnginePool;
import com.checkmarx.engine.domain.EnginePool.IdleEngineMonitor;
import com.checkmarx.engine.domain.EngineSize;
import com.checkmarx.engine.rest.CxEngineApi;
import com.checkmarx.engine.rest.Notification;
import com.checkmarx.engine.rest.model.EngineServer;
import com.checkmarx.engine.rest.model.ScanRequest;
import com.checkmarx.engine.rest.model.ScanRequest.ScanStatus;
import com.checkmarx.engine.utils.ExecutorServiceUtils;
import com.checkmarx.engine.utils.TaskManager;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Queues;

public class EngineManager implements Runnable {
	
	private static final Logger log = LoggerFactory.getLogger(EngineManager.class);

	private final CxConfig config;
	private final CxEngineApi cxClient;
	private final EnginePool pool;
	private final CxEngines engineProvisioner;
	//FIXME: figure out a way to communicate with scanQueue without dependency
    private final ScanQueueMonitor scanQueueMonitor;
	private final BlockingQueue<ScanRequest> queuedScansQueue;
	private final BlockingQueue<ScanRequest> finshedScansQueue;
	private final BlockingQueue<DynamicEngine> expiredEnginesQueue;

	private final ExecutorService managerExecutor;
	private final ExecutorService scanQueuedExecutor;
	private final ExecutorService scanFinishedExecutor;
	private final ExecutorService engineExpiringExecutor;
	private final ScheduledExecutorService idleEngineExecutor;
	private final TaskManager taskManager;
	private final Notification notify;

	private final static int MANAGER_THREAD_POOL_SIZE = 3;
	//FIXME: move these to config
	private final static int SCANS_QUEUED_THREAD_POOL_SIZE = 10;
	private final static int SCANS_FINISHED_THREAD_POOL_SIZE = 5;
	private final static int ENGINE_EXPIRING_THREAD_POOL_SIZE = 5;

	/**
	 * map of blocked scans by engine size; key=EngineSize of scan
	 */
	private final Map<EngineSize, Queue<ScanRequest>> blockedScansQueueMap;
	
	/**
	 * map of scans assigned to engines; key=Scan.Id, value=cxEngineId
	 */
	private final Map<String, Long> engineScans = Maps.newConcurrentMap();
	
	/**
	 * map of registered cx engine servers, key=cxEngineId
	 */
	private Map<Long, DynamicEngine> cxEngines = Maps.newConcurrentMap();
	
	/**
	 * map of active (scanning) cx engine servers, key=cxEngineId
	 */
	private Map<Long, EngineServer> activeEngines = Maps.newConcurrentMap();
	
	/**
	 * map of active scans when DynEngines is started, key=ScanId
	 */
	private Map<String, DynamicEngine> preExistingScans = Maps.newConcurrentMap();

	public EngineManager(
			CxConfig config,
			EnginePool pool, 
			CxEngineApi cxClient,
			CxEngines engineProvisioner,
			TaskManager taskManager,
			ScanQueueMonitor scanQueueMonitor,
			BlockingQueue<ScanRequest> scansQueued,
			BlockingQueue<ScanRequest> scansFinished,
			Notification notify) {
		this.pool = pool;
		this.config = config;
		this.cxClient = cxClient;
		this.taskManager = taskManager;
		this.scanQueueMonitor = scanQueueMonitor;
		this.queuedScansQueue = scansQueued;
		this.finshedScansQueue = scansFinished;
		this.expiredEnginesQueue = new ArrayBlockingQueue<DynamicEngine>(pool.getEngineCount());
		this.blockedScansQueueMap = Maps.newConcurrentMap();
		this.engineProvisioner = engineProvisioner;
		this.managerExecutor = ExecutorServiceUtils.buildPooledExecutorService(MANAGER_THREAD_POOL_SIZE, "engine-mgr-%d", true);
		this.scanQueuedExecutor = ExecutorServiceUtils.buildPooledExecutorService(SCANS_QUEUED_THREAD_POOL_SIZE, "scan-queue-%d", true);
		this.scanFinishedExecutor = ExecutorServiceUtils.buildPooledExecutorService(SCANS_FINISHED_THREAD_POOL_SIZE, "scan-finish-%d", true);
		this.engineExpiringExecutor = ExecutorServiceUtils.buildPooledExecutorService(ENGINE_EXPIRING_THREAD_POOL_SIZE, "engine-kill-%d", true);
		this.idleEngineExecutor = ExecutorServiceUtils.buildScheduledExecutorService("idle-mon-%d", true);
		this.notify = notify;
	}

	@Override
	public void run() {
		log.info("run()");
		
		try {
		    taskManager.addExecutor("EngineManager", managerExecutor);
            taskManager.addExecutor("ScanQueuedExecutor", scanQueuedExecutor);
            taskManager.addExecutor("ScanFinishedExecutor", scanFinishedExecutor);
            taskManager.addExecutor("EngineExpiringExecutor", engineExpiringExecutor);
            taskManager.addExecutor("IdleEngineExecutor", idleEngineExecutor);

            final IdleEngineMonitor engineMonitor = 
                    pool.createIdleEngineMonitor(this.expiredEnginesQueue, config.getExpireEngineBufferMins());
            final int monitorInterval = config.getIdleMonitorSecs();

            taskManager.addTask("ScanLauncher", managerExecutor.submit(new ScanLauncher()));
			taskManager.addTask("ScanFinisher", managerExecutor.submit(new ScanFinisher()));
			taskManager.addTask("EngineTerminator", managerExecutor.submit(new EngineTerminator()));
			taskManager.addTask("IdleEngineMonitor", 
			        idleEngineExecutor.scheduleAtFixedRate(engineMonitor, 1, monitorInterval, TimeUnit.SECONDS));
		} catch (Throwable t) {
			log.error("Error occurred while launching Engine processes, shutting down; cause={}; message={}", 
					t, t.getMessage(), t);
			throw t;
		}
	}
	
    public void stop() {
        log.info("stop()");
        taskManager.shutdown();
    }

	private EngineSize calcEngineSize(ScanRequest scan) {
		final EngineSize size = pool.calcEngineSize(scan.getLoc());
		if (size == null) {
			final String msg = String.format("Invalid scan size; %s", scan);
			throw new RuntimeException(msg);
		}
		return size;
	}
	
    public void initialize() {
        log.debug("initialize()");
        
//        log.info("Logging into CxManager; url={}, user={}", config.getRestUrl(), config.getUserName());
//        if (!cxClient.login()) {
//            throw new RuntimeException("Unable to login to CxManager, shutting down...");
//        }

        final List<EngineServer> registeredEngines = findRegisteredDynEngines();
        final List<ScanRequest> activeScans = findActiveScans();
        final List<DynamicEngine> activeEngines = checkPreExistingEngines(activeScans, registeredEngines);
        trackPreExistingScans(activeEngines, activeScans);
        spinMinIdleEngines();
        
        //unregisterStaleEngines(activeEngines);
        registerQueuingEngine();
        log.info("initialize complete: {}", pool);
    }
    
    private void unregisterMissingEngines(List<DynamicEngine> provisionedEngines, List<EngineServer> registeredEngines) {
        log.debug("unregisterMissingEngines()");

        final List<EngineServer> staleEngines = Lists.newArrayList();
        
        registeredEngines.forEach(cxEngine -> {
            if (provisionedEngines.stream().noneMatch(dynEngine -> namesMatch(cxEngine, dynEngine))) {
                staleEngines.add(cxEngine);
            }
            else{
            	//Go through the provisionedEngines and map the associated engine Id from the registered engine from Cx
				for(DynamicEngine e: provisionedEngines){
					if(namesMatch(cxEngine, e)){
						e.setEngineId(cxEngine.getId().toString());
					}
				}
			}
        });
        
        staleEngines.forEach(engine -> unRegisterStaleEngine(engine));
    }

    private void unRegisterStaleEngine(EngineServer engine) {
        log.warn("Unregistering stale engine; engine={}; {}", engine.getName(), engine);
        try {
            // this will fail if engine is scanning
            cxClient.unregisterEngine(engine.getId());
        } catch (Exception e) {
            // log and swallow
            final String msg = String.format("Error while trying to unregister stale DynamicEngine: %s", engine.toString());
            log.warn(msg, e);
        }
    }

    private boolean namesMatch(EngineServer cxEngine, DynamicEngine dynEngine) {
        final String cxName =  cxEngine.getName();
        final String dynName = dynEngine.getName();
        log.debug("namesMatch(): cxEngine={}; dynEngine={}", cxName, dynName);
        return computeCxEngineName(dynName).equals(cxName);
    }

    private void trackPreExistingScans(List<DynamicEngine> activeEngines, List<ScanRequest> scans) {
        log.debug("trackPreExistingScans(): count={}", activeEngines.size());
        activeEngines.forEach(engine -> {
            final String scanId = engine.getScanId(); 
            final Long engineId = Long.valueOf(engine.getEngineId());
            log.warn("...tracking existing scan; scanId={}; {}", scanId, engine);

            final EngineServer cxEngine = cxClient.getEngine(engineId);
            cxEngines.put(engineId, engine);
            engineScans.put(String.valueOf(scanId), engineId);
            this.activeEngines.put(engineId, cxEngine);
            
            preExistingScans.put(scanId, engine);
            cxClient.blockEngine(engine.getEngineId());
            final Optional<ScanRequest> scan = scans.stream()
                    .filter(scanRequest -> String.valueOf(scanRequest.getId()).equals(engine.getScanId()))
                    .findFirst();
            scanQueueMonitor.onPreExistingScan(scan.get());
        });
    }

    public void registerQueuingEngine() {
        log.debug("registerQueueEngine()");
        
        final String engineName = config.getQueueingEngineName();
        final EngineServer engine = cxClient.blockEngine(engineName);

        log.info("Queueing engine registered: {}", engine);
    }
    
    private boolean isDynamicEngine(EngineServer engine) {
        return engine.getName().startsWith(config.getCxEnginePrefix());
    }

    /**
     * Check for existing dynamic engines using the following rules:
     *  1. Find all provisioned engines
     *  2. If engine is running a scan, add to pool as State.SCANNING
     *  3. If not, add to pool as State.IDLE and unregister (if registered)
     *  
     *  If engine name does not exist in pool, stop the engine.
     *  
     * @param registeredEngines 
     *  
     * @return list of active/scanning engines
     */
    private List<DynamicEngine> checkPreExistingEngines(List<ScanRequest> activeScans, List<EngineServer> registeredEngines) {
        log.debug("checkPreExistingEngines()");

        final List<DynamicEngine> activeEngines = Lists.newArrayList();
        final List<DynamicEngine> provisionedEngines = engineProvisioner.listEngines();
        
        // unregister any unprovisioned dynamic engines (missing from the provisionedEngines list) 
        unregisterMissingEngines(provisionedEngines, registeredEngines);

        if (provisionedEngines.isEmpty()) {
            log.info("...no pre-existing engines found.");
            return activeEngines;
        }
        
        final List<DynamicEngine> idleEngines = provisionedEngines.stream()
                .filter(engine -> Strings.isNullOrEmpty(engine.getScanId()))
				.filter(engine -> engine.getHost() != null) //a host object reference here is the only indication of a running server
                .collect(Collectors.toList());

		final List<DynamicEngine> scanningEngines = provisionedEngines.stream()
				.filter(engine -> !Strings.isNullOrEmpty(engine.getScanId()))
				.filter(engine -> engine.getHost() != null) //a host object reference here is the only indication of a running server
				.collect(Collectors.toList());

		// add idle engines to the pool
		idleEngines.forEach(engine -> {
		    addEngineToPool(engine);
		    engine.onIdle();
		});

        //Check the active scans,
        scanningEngines.forEach((engine) -> {
            if (!addEngineToPool(engine)) return;
            if (checkForActiveScan(engine, activeScans)) {
                activeEngines.add(engine);
                engine.onScan();
                //engine.setState(State.SCANNING);
            } else {
                idleEngines.add(engine);
                engineProvisioner.onScanRemoved(engine);  //remove reference to the invalid scan id
                engine.onIdle();
            }
        });

        // make sure idle engines are not registered with CxManager
        unRegisterIdleEngines(idleEngines, registeredEngines);

        return activeEngines;
    }

    private void spinMinIdleEngines() {
        log.debug("spinMinIdleEngines()");
        
        List<DynamicEngine> idleEngines = pool.allocateMinIdleEngines();
        log.info("Launching minimum idle engines; count={}", idleEngines.size());
        idleEngines.forEach((engine) -> {
            launchIdleEngine(engine);
        });
    }
    
    private void launchIdleEngine(DynamicEngine engine) {
        try {
            final EngineSize size = pool.getEngineSize(engine.getSize());
            engineProvisioner.launch(engine, size, false);
            engine.onIdle();
        } catch (InterruptedException e) {
            // if interrupted, continue
            log.warn("Spinning idle engine interrupted, continuing...");
        }
    }

    private boolean addEngineToPool(DynamicEngine engine) {
        final DynamicEngine oldEngine = pool.addExistingEngine(engine);
        if (oldEngine == null) {
            // engine not found in pool, skip
            // FIXME-rjg: what should we do here?  shut it down?
            log.warn("Existing engine not found in pool most likely due to configuration change, skipping existing engine...; {}", engine);
            //engineProvisioner.stop(engine);
            return false;
        }
        return true;
    }

    private void unRegisterIdleEngines(
            List<DynamicEngine> idleEngines, 
            List<EngineServer> registeredEngines) {
        
        idleEngines.forEach(engine -> {
            //engine.setState(State.IDLE);
            engine.onIdle();
            final String sEngineId = engine.getEngineId();
            if (Strings.isNullOrEmpty(sEngineId)) {
                return;
            }
            final Optional<EngineServer> engineServer = registeredEngines.stream()
                .filter(server -> sEngineId.equals(String.valueOf(server.getId())))
                .findFirst();
            if (engineServer.isPresent()) {
                unRegisterStaleEngine(engineServer.get());
            }
        });
    }

    private boolean checkForActiveScan(@NotNull DynamicEngine engine, @NotNull List<ScanRequest> activeScans) {
        final String scanId = engine.getScanId();
        log.debug("checkForActiveScan(): engine={}; scan={}", engine.getName(), scanId);

        final Optional<ScanRequest> foundScan = 
                activeScans.stream().filter(scan -> String.valueOf(scan.getId()).equals(scanId)).findFirst();
        
        if (!foundScan.isPresent()) {
            return false;
        }
        
        final ScanRequest scan = foundScan.get();
        log.debug("...found matching scanRequest: {}", scan);
        if (engine.getEngineId().equals(String.valueOf(scan.getEngineId()))) {
            log.info("Engine found running active scan: {}, {}", engine, scan);
            return true;
        }

        log.warn("Existing engine found with ScanId but scan is running on different engine: {}; {}",
                engine, scan);
        return false;
    }

    private List<EngineServer> findRegisteredDynEngines() {
        log.debug("findRegisteredDynEngines()");
        
        final List<EngineServer> allEngines = cxClient.getEngines();
        final List<EngineServer> dynEngines = allEngines.stream()
                .filter(engine -> isDynamicEngine(engine))
                .collect(Collectors.toList());
        log.info("...dynamic engines registered; count={}", dynEngines.size());
        dynEngines.forEach(engine -> log.info("registeredEngine={}", engine));
        return dynEngines;
    }

    private List<ScanRequest> findActiveScans() {
        log.debug("findActiveScans()");
        
        final List<ScanRequest> scansQueue = cxClient.getScansQueue();
        final List<ScanRequest> activeScans = scansQueue.stream()
                .filter(scan -> ScanStatus.Scanning.equals(scan.getStatus()))
                .collect(Collectors.toList());
        log.info("...active scans; count={}", activeScans.size());
        activeScans.forEach(scan -> log.info("activeScan={}", scan));
        return activeScans;
    }

    void trackEngineScan(ScanRequest scan, EngineServer cxEngine, DynamicEngine dynEngine) {
        log.debug("trackEngineScan(): {}; {}; {}", scan, cxEngine, dynEngine);
        
        final long engineId = cxEngine.getId();
        final Long scanId = scan.getId();
        dynEngine.setScanId(String.valueOf(scanId));
        dynEngine.setEngineId(String.valueOf(engineId));
        engineProvisioner.onScanAssigned(dynEngine);
        
        cxEngines.put(engineId, dynEngine);
        engineScans.put(String.valueOf(scanId), engineId);
        activeEngines.put(engineId, cxEngine);
    }

    private String computeCxEngineName(String name) {
        final String prefix = config.getCxEnginePrefix(); //"**";
        return String.format("%s%s", prefix, name);
    }

	public class ScanLauncher implements Runnable {
		
		private final Logger log = LoggerFactory.getLogger(EngineManager.ScanLauncher.class);

		@Override
		public void run() {
			log.info("run()");
			
			int scansQueuedCount = 0;
			try {
				while (true) {
					log.debug("ScanLauncher: waiting for scan");
					
					// blocks until scan available
					ScanRequest scan = queuedScansQueue.take();
					scansQueuedCount++;
					
					// process scan task using background thread pool
					scanQueuedExecutor.execute(()-> onScanQueued(scan));
				}
			} catch (InterruptedException e) {
				log.info("ScanLauncher interrupted");
			} catch (Throwable t) {
				log.error("Error occurred in ScanLauncher; cause={}; message={}", 
						t, t.getMessage(), t); 
			} finally {
				log.info("ScanLauncher exiting; scansQueuedCount={}", scansQueuedCount);
			}
		}
		
		public void onScanQueued(ScanRequest scan) {
			log.debug("onScanQueued() : {}", scan);

			final EngineSize size = calcEngineSize(scan);
			
			try {
			
				if (allocateIdleEngine(size, scan)) return;
				
				if (checkActiveEngines(size, scan)) return;
				
				if (allocateNewEngine(size, scan)) return;
				
				blockScan(size, scan);
				
            } catch (InterruptedException e) {
                log.info("onScanQueued interrupted, exiting...");
			} catch (Throwable t) {
				log.error("Error occurred launching scan; cause={}; message={}", 
						t, t.getMessage(), t);
				notify.sendNotification(config.getNotificationSubject(),"Error occurred launching scan", t);
				scanQueueMonitor.onLaunchFailed(scan);
				//blockScan(size, scan);
			}
		}

		private boolean allocateIdleEngine(EngineSize size, ScanRequest scan) {
			log.trace("allocateIdleEngine(): size={}; {}", size, scan);

			final State state = State.IDLE;
			final DynamicEngine engine = pool.allocateEngine(size, state);
			
			if (engine == null) return false;
			
			registerEngine(state, scan, engine);
			return true;
		}
		
		private boolean allocateNewEngine(EngineSize size, ScanRequest scan) throws InterruptedException {
			log.trace("allocateNewEngine(): size={}; {}", scan, size);

			if (atScanLimit()) return false;
			
			final State state = DynamicEngine.State.UNPROVISIONED;
			final DynamicEngine engine = pool.allocateEngine(size, state);
			
			if (engine == null) return false;

			try {
	            // blocks while engine is spinning up
                engineProvisioner.launch(engine, size, true);
    			registerEngine(state, scan, engine);
    			return true;
            } catch (InterruptedException e) {
                throw e;
            }
		}
		
		private boolean atScanLimit() {
            // TODO implement
            return false;
        }

        private void blockScan(EngineSize size, ScanRequest scan) {
			log.trace("blockScan(): size={}; {}", size, scan);
			
			Queue<ScanRequest> blockedQueue = blockedScansQueueMap.get(size);
			if (blockedQueue == null) {
				log.debug("Creating blocked queue: size={}; {}", size, scan);
				blockedQueue = Queues.newConcurrentLinkedQueue();
				blockedScansQueueMap.put(size, blockedQueue);
			}
			
			blockedQueue.add(scan);
			log.warn("No engine available, added scan to blocked queue: size={}; {}", size, scan);
		}

		private void registerEngine(State fromState, ScanRequest scan, DynamicEngine dynEngine) {
			log.trace("registerEngine(): fromState={}; {}; {}", fromState, scan, dynEngine);
			
			final Long scanId = scan.getId();
			final String url = dynEngine.getUrl();
			if (Strings.isNullOrEmpty(url)) {
				final String msg = String.format("Cannot register Engine, url is null: %s", dynEngine);
				throw new RuntimeException(msg);
			}
			EngineServer cxEngine = createEngine(dynEngine.getName(), scan, dynEngine.getUrl());
			cxEngine = registerCxEngine(scanId, cxEngine);
			
			dynEngine.onScan();
			trackEngineScan(scan, cxEngine, dynEngine);

			log.info("Engine allocated for scan: fromState={}; engine={}; scan={}", fromState, dynEngine, scan);
		}
		
		@Retryable(value = { RestClientException.class }, maxAttempts = 3, backoff = @Backoff(delay = 1000))
		private EngineServer registerCxEngine(final long scanId, final EngineServer cxServer) {
			log.trace("registerCxEngine()L scanId={}", scanId);
			
			final EngineServer cxEngine = cxClient.registerEngine(cxServer);
			log.info("Engine registered: scanId={}; engine={}", scanId, cxEngine);
			
			return cxEngine;
		}

		private EngineServer createEngine(String name, ScanRequest scan, String url) {
		    final String engineName = computeCxEngineName(name);

		    // rjg - Issue 14: CxMgr has a defect with 0 LOC scans, which will cause Engine 
		    // 		registration failure (400).  Workaround set maxLoc to 1 when LOC is 0. 
			final int minLoc = scan.getLoc();
			final int maxLoc = minLoc > 0 ? minLoc : 1;
			
			return new EngineServer(engineName, url, minLoc, maxLoc, 1, false);
		}

        private boolean checkActiveEngines(EngineSize size, ScanRequest scan) {
			log.trace("checkActiveEngines() : size={}; {}", size, scan);

			//final State state = State.SCANNING;
			//final DynamicEngine engine = pool.allocateEngine(size, state);
			
			//TODO: check active engines for scan finish time; if within finish window, allocate scan
			return false;
		}

	}

	public class ScanFinisher implements Runnable {
		
		private final Logger log = LoggerFactory.getLogger(EngineManager.ScanFinisher.class);

		@Override
		public void run() {
			log.info("run()");
			
			int scanCount = 0;
			try {
				while (true) {
					log.trace("ScanFinisher: waiting for scan...");
					
					// blocks until scan is finished
					ScanRequest scan = finshedScansQueue.take();
					scanCount++;
					
					// add task to thread pool
					scanFinishedExecutor.execute(()-> onScanFinished(scan));
				}
			} catch (InterruptedException e) {
				log.info("ScanFinisher interrupted");
			} catch (Throwable t) {
				log.error("Error occurred in ScanFinisher; cause={}; message={}", 
						t, t.getMessage(), t);
				throw t;
			} finally {
				log.info("ScanFinisher exiting; scanCount={}", scanCount);
			}

		}

		private void onScanFinished(ScanRequest scan) {
			log.debug("onScanFinished() : {}", scan);
			
			try {
			    
                final String scanId = String.valueOf(scan.getId()); 
				final EngineSize size = calcEngineSize(scan);
				final Long engineId = determineEngineId(scan);
				if (engineId == null) {
					if (removeBlockedScan(size, scan)) {
						log.info("Blocked scan was cancelled and removed: {}", scan);
						return;
					}
					log.warn("Untracked scan completed; scanId={}; {}", scanId, scan);
					return;
				}
				
				final DynamicEngine engine = cxEngines.get(engineId);
				unRegisterEngine(engineId);
				engineProvisioner.onScanRemoved(engine);
				engine.onIdle();
				
				engineScans.remove(scanId);
				cxEngines.remove(engineId);
				log.info("Scan finished, engine removed: engine={}; scan={}", engine, scan);
				
				// see if we have any scans blocked that can now run
				checkBlockedScans(size);
			} catch (Throwable t) {
				log.error("Error occurred finishing scan; cause={}; message={}", 
						t, t.getMessage(), t); 
			}

		}

        /**
		 * Queues the head scan in the blocked queue, if any 
		 * @param size Engine size to check
		 */
		private void checkBlockedScans(EngineSize size) throws InterruptedException {
			log.trace("checkBlockedScans(): size={}", size);
			
			if (!blockedScansQueueMap.containsKey(size)) return;
			
			final ScanRequest scan = blockedScansQueueMap.get(size).poll();
			if (scan == null) return;
			
			// add scan to the queue
			queuedScansQueue.put(scan);
		}

		private boolean removeBlockedScan(EngineSize size, ScanRequest scan) {
			log.trace("removeBlockedScan(): size={}; {}", size, scan);

			if (!blockedScansQueueMap.containsKey(size)) return false;
			
			return blockedScansQueueMap.get(size).remove(scan);
		}

		@Retryable(value = { HttpClientErrorException.class }, maxAttempts = 3, backoff = @Backoff(delay = 1000))
		private void unRegisterEngine(final Long engineId) {
			log.trace("unRegisterEngine(): engineId={}", engineId);
			cxClient.unregisterEngine(engineId);
		}

		private Long determineEngineId(ScanRequest scan) {
			final Long scanEngineId = scan.getEngineId();
            final String scanId = String.valueOf(scan.getId());
			log.debug("determineEngineId(): scanId={}; engineId={}", scanId, scanEngineId);

			return scanEngineId == null ? engineScans.get(scanId) : scanEngineId;
		}
		
	}
	
	public class EngineTerminator implements Runnable {
		
		private final Logger log = LoggerFactory.getLogger(EngineManager.EngineTerminator.class);

		@Override
		public void run() {
			log.info("run()");
			
			try {
				while (true) {
					log.trace("EngineTerminator: waiting for event...");

					//blocks until an engine expires
					final DynamicEngine engine = expiredEnginesQueue.take();
					engineExpiringExecutor.execute(()-> stopEngine(engine));
				}
			} catch (InterruptedException e) {
				log.info("EngineTerminator interrupted");
			} catch (Throwable t) {
				log.error("Error occurred in EngineTerminator; cause={}; message={}", 
						t, t.getMessage(), t);
				throw t;
			}
		}
		
		private void stopEngine(DynamicEngine engine) {
			log.debug("stopEngine(): {}", engine);
			
			engine.onStop();
			//pool.deallocateEngine(engine);
			engineProvisioner.stop(engine);

			log.info("Idle engine expired, engine deallocated: engine={}", engine);
		}
		
	}

}
