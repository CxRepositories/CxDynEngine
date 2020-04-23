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
package com.checkmarx.engine.azure;

import com.checkmarx.engine.CxConfig;
import com.checkmarx.engine.domain.DynamicEngine;
import com.checkmarx.engine.domain.EnginePoolConfig;
import com.checkmarx.engine.domain.EngineSize;
import com.checkmarx.engine.domain.Host;
import com.checkmarx.engine.rest.CxEngineClient;
import com.checkmarx.engine.servers.CxEngines;
import com.checkmarx.engine.utils.ScriptRunner;
import com.checkmarx.engine.utils.TaskManager;
import com.checkmarx.engine.utils.TimeoutTask;
import com.google.common.base.MoreObjects;
import com.google.common.base.Stopwatch;
import com.google.common.base.Strings;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.microsoft.azure.management.compute.VirtualMachine;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import javax.validation.constraints.NotNull;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * azure {@code CxEngines} provider.
 * 
 * @author randy@checkmarx.com
 *
 */
@Component
@Profile("azure")
public class AzureEngines implements CxEngines {

	private static final Logger log = LoggerFactory.getLogger(AzureEngines.class);

	private final CxConfig cxConfig;
	private final AzureEngineConfig azureConfig;
	private final EnginePoolConfig poolConfig;
	private final AzureComputeClient azureClient;
	private final CxEngineClient engineClient;
	private final TaskManager taskManager;
	private final int pollingMillis;
    private final ExecutorService executor;

	/**
	 * Maps engine name to Azure instance; key=engine name
	 */
	private final Map<String, VirtualMachine> provisionedEngines = Maps.newConcurrentMap();
	
	/**
	 * Maps EngineSize to Azure instanceType;
	 * key=size (name), 
	 * value=ec2 instance type (e.g. m4.large)
	 */
	private final Map<String, String> engineTypeMap;

	public AzureEngines(
			CxConfig cxConfig,
			EnginePoolConfig poolConfig,
			AzureComputeClient azureClient,
			CxEngineClient engineClient,
			TaskManager taskManager) {

		this.cxConfig = cxConfig;
		this.poolConfig = poolConfig;
		this.azureClient = azureClient;
		this.azureConfig = azureClient.getConfig(); 
		this.engineClient = engineClient;
		this.taskManager = taskManager;
		this.engineTypeMap = azureConfig.getEngineSizeMap();
		this.pollingMillis = azureConfig.getMonitorPollingIntervalSecs() * 1000;
		this.executor = taskManager.getExecutor("EngineScripts", "eng-scripts-%d", false);
		
		log.info("ctor(): {}", this);
	}
	
	public static Map<String, String> createCxTags(CxServerRole role, String version) {
		log.trace("createCxTags(): role={}; version={}", role, version);
		
		final Map<String, String> tags = Maps.newHashMap();
		tags.put(CX_ROLE_TAG, role.toString());
		tags.put(CX_VERSION_TAG, version);
		return tags;
	}
	
	private Map<String, String> createEngineTags(String size) {
		log.trace("createEngineTags(): size={}", size);
		final Map<String, String> tags = createCxTags(CxServerRole.ENGINE, azureConfig.getCxVersion());
		tags.put(CX_SIZE_TAG, size);
		// set scanID to empty initially
		tags.put(CX_SCAN_ID_TAG, "");
		//add time started tag
		tags.put(AzureConstants.START_TIME_TAG, DateTime.now().toString());
		// add custom tags from configuration
		azureConfig.getTagMap().forEach(tags::put);
		return tags;
	}

	private Map<String, String> getEngineTags() {
		final Map<String, String> tags = createCxTags(CxServerRole.ENGINE, azureConfig.getCxVersion());
		azureConfig.getTagMap().forEach(tags::put);
		return tags;
	}

	Map<String, VirtualMachine> findEngines() {
		log.trace("findEngines()");
		
		final Stopwatch timer = Stopwatch.createStarted(); 
		try {
			final List<VirtualMachine> engines = azureClient.find(getEngineTags());
			engines.forEach((instance) -> {
				if (VM.isTerminated(instance)) {
					log.info("Terminated engine found: {}", VM.print(instance));
					return;
				}

				final String name = VM.getName(instance);
				if (!provisionedEngines.containsKey(name)) {
					provisionedEngines.put(name, instance);
					log.info("Provisioned engine found: {}", VM.print(instance));
				}
			});
			
		} finally {
			log.debug("Find Engines: elapsedTime={}ms; count={}", 
					timer.elapsed(TimeUnit.MILLISECONDS), provisionedEngines.size()); 
		}
		return provisionedEngines;
	}

	@Override
	public List<DynamicEngine> listEngines() {
		final Map<String, VirtualMachine> engines = findEngines();
		final List<DynamicEngine> dynEngines = Lists.newArrayList();
		engines.forEach((name, instance) -> {
			final DynamicEngine engine = buildDynamicEngine(name, instance);
			dynEngines.add(engine);
		});
		return dynEngines;
	}

	/**
	 * Used for Integration test purposes only
	 * @param instanceId
	 */
	void stop(String instanceId){
		azureClient.stop(instanceId);
	}

	DynamicEngine buildDynamicEngine(@NotNull String name, @NotNull VirtualMachine instance) {
		final String size = lookupEngineSize(instance);
		String startTime = VM.getTag(instance, AzureConstants.START_TIME_TAG);
		final DateTime launchTime = startTime != null ? new DateTime(startTime) : DateTime.now();
		final boolean isRunning = VM.isRunning(instance);
		final String scanId = lookupScanId(instance);
		final String engineId = lookupEngineId(instance);
		final DynamicEngine engine = DynamicEngine.fromProvisionedInstance(
				name, size, poolConfig.getEngineExpireIntervalSecs(),
				launchTime, launchTime, isRunning, scanId, engineId);
		if (isRunning) {
			engine.setHost(createHost(name, instance));
		}
		return engine;
	}

	private String lookupScanId(VirtualMachine instance) {
        return VM.getTag(instance, CX_SCAN_ID_TAG);
    }
	
    private String lookupEngineId(VirtualMachine instance) {
        return VM.getTag(instance, CX_ENGINE_ID_TAG);
    }
    
    private String lookupEngineSize(VirtualMachine instance) {
		final String sizeTag = VM.getTag(instance, CX_SIZE_TAG);
        //TODO: validate instance type to size
		if (!Strings.isNullOrEmpty(sizeTag)) return sizeTag;
		
		for (Entry<String,String> entry : engineTypeMap.entrySet()) {
			String instanceType = entry.getValue();
			String size = entry.getKey();
			if (instance.size().toString().equalsIgnoreCase(instanceType))
				return size;
		}
		// if not found, return first size in map
		final String size = Iterables.getFirst(engineTypeMap.keySet(), "S"); 
		log.warn("Engine size tag doesn't match current settings, assuming {} engine: tag={}; instance={}", 
		        size, sizeTag, instance.id());
		return size; 
	}
    
	@Override
	@Retryable(
			value = { RuntimeException.class },
			maxAttempts = AzureConstants.RETRY_ATTEMPTS,
			backoff = @Backoff(delay = AzureConstants.RETRY_DELAY))
	public void launch(DynamicEngine engine, EngineSize size, boolean waitForSpinup) throws InterruptedException {
		log.debug("launch(): {}; size={}; wait={}", engine, size, waitForSpinup);

		final String name = engine.getName();
		final String type = engineTypeMap.get(size.getName());
		final Map<String, String> tags = createEngineTags(size.getName());
		
		VirtualMachine instance = provisionedEngines.get(name);
		String instanceId = null;
		
		log.info("action=LaunchingEngine; name={}; {}", name, engine); 

		boolean success = false;
		final Stopwatch timer = Stopwatch.createStarted();
		try {
			if (instance != null) {
				log.debug("...Azure VM is provisioned...");
			}
			else{
				instance = launchEngine(engine, name, type, tags);
				engine.onLaunch(DateTime.now());
			}
			instanceId = instance.id();
			//refresh instance state
			instance = azureClient.describe(instanceId);
			provisionedEngines.put(name, instance);
			
			if (VM.isTerminated(instance)) {
				log.debug("...Azure VM is terminated, launching new instance...");
				instance = launchEngine(engine, name, type, tags);
				instanceId = instance.id();
			}else if (VM.isStopping(instance)) {
				final int sleep = azureConfig.getCxEngineTimeoutSec();
				log.debug("...Azure VM is stopping, sleeping {}s", sleep);
				Thread.sleep(sleep * 1000);
				log.debug("...Azure VM is stopping, starting instance...");
				instance = azureClient.start(instanceId);
			} else if (!VM.isRunning(instance)) {
				log.debug("...Azure VM is stopped, starting instance...");
				instance = azureClient.start(instanceId);
			} else {
				// host is running
				log.debug("...Azure VM is running...");
			}
			
			final Host host = createHost(name, instance);
			engine.setHost(host);
			engine.onStart(host.getLaunchTime());
			
			if (waitForSpinup) {
				pingEngine(host);
			}
			//move this logic into caller
			runScript(azureConfig.getScriptOnLaunch(), engine);
			
			//engine.setState(State.IDLE);
			success = true;
        } catch (CancellationException | InterruptedException | RejectedExecutionException e) {
            log.warn("Error occurred while launching azure Azure instance; name={}; {}", name, engine, e);
            handleLaunchException(instanceId, e);
            throw new InterruptedException(e.getMessage());
		} catch (Throwable e) {
			log.error("Error occurred while launching azure Azure instance; name={}; {}", name, engine, e);
            handleLaunchException(instanceId, e);
            throw new RuntimeException("Error launching engine", e);
		} finally {
			log.info("action=LaunchedEngine; success={}; name={}; id={}; elapsedTime={}s; {}", 
					success, name, instanceId, timer.elapsed(TimeUnit.SECONDS), VM.print(instance));
		}
	}
	
	private void handleLaunchException(String instanceId, Throwable e) {
        if (!Strings.isNullOrEmpty(instanceId)) {
			if(cxConfig.isTerminateOnStop()) {
				log.warn("Terminating instance due to error; instanceId={}", instanceId);
				azureClient.terminate(instanceId);
			}
			else {
				log.warn("Shutting down instance due to error; instanceId={}", instanceId);
				azureClient.stop(instanceId);
			}
        }
	}
	
	@Override
	public void stop(DynamicEngine engine) {
		stop(engine, false);
	}
	
	@Override
	public void stop(DynamicEngine engine, boolean forceTerminate) {
		log.debug("stop() : {}", engine);

        String action = "StoppedEngine";
        String instanceId = null;
        VirtualMachine instance = null;
        boolean success = false;
        final String name = engine.getName();

        final Stopwatch timer = Stopwatch.createStarted();
        try {
    		instance = lookupVirtualMachine(engine, "stop");
    		instanceId = instance.id();
			
			if (cxConfig.isTerminateOnStop() || forceTerminate) {
				action = "TerminatedEngine";
				azureClient.terminate(instanceId);
				provisionedEngines.remove(name);
                engine.onTerminate();
				runScript(azureConfig.getScriptOnTerminate(), engine);
			} else {
				azureClient.stop(instanceId);
				instance = azureClient.describe(instanceId);
				provisionedEngines.put(name, instance);
                engine.onStop();
                runScript(azureConfig.getScriptOnStop(), engine);
			}
			success = true;
			
		} finally {
			log.info("action={}; success={}; name={}; id={}; elapsedTime={}ms; {}", 
					action, success, name, instanceId, timer.elapsed(TimeUnit.MILLISECONDS), VM.print(instance));
		}
	}

    @Override
    public void onScanAssigned(DynamicEngine toEngine) {
        final String scanId = toEngine.getScanId();
        final String engineId = toEngine.getEngineId();
        log.debug("onScanAssigned(): scanId={}; engineId={}; {}", scanId, engineId, toEngine);
        Map<String, String> tags = new HashMap<>();
        tags.put(CX_SCAN_ID_TAG, scanId);
        tags.put(CX_ENGINE_ID_TAG, engineId);
        tagEngine(toEngine, tags);
    }

    @Override
    public void onScanRemoved(DynamicEngine fromEngine) {
        final String scanId = fromEngine.getScanId();
        final String engineId = fromEngine.getEngineId();
        log.debug("onScanRemoved(): scanId={}; engineId={}; {}", scanId, engineId, fromEngine);
        //clear engine tags
		Map<String, String> tags = new HashMap<>();
		tags.put(CX_SCAN_ID_TAG, "");
		tags.put(CX_ENGINE_ID_TAG, "");
		tagEngine(fromEngine, tags);
    }
    
    VirtualMachine tagEngine(DynamicEngine engine, Map<String, String> tags) {
        VirtualMachine instance = lookupVirtualMachine(engine, "tag");
        final String name = engine.getName();
        final String instanceId = instance.id();
        
        final String action = "TagEngine";
        boolean success = false;
        final Stopwatch timer = Stopwatch.createStarted();
        try {
            instance = azureClient.updateTags(instance, tags);
            provisionedEngines.put(name, instance);
            success = true;
            return instance;
        } catch (Exception e) {
            log.warn("Failed to tag engine; {}; cause={}; message={}", 
                    engine, e.getCause(), e.getMessage());
            throw e;
        } finally {
            log.info("action={}; success={}; name={}; id={}; elapsedTime={}ms; {}", 
                    action, success, name, instanceId, timer.elapsed(TimeUnit.MILLISECONDS), VM.print(instance));
        }
    }
    
    private VirtualMachine lookupVirtualMachine(DynamicEngine engine, String operation) {
        final String name = engine.getName();
        final VirtualMachine instance = provisionedEngines.get(name);
        if (instance == null) {
            final String msg = String.format("Cannot %s engine, engine not found; engine=%s", 
                    operation, name); 
            log.warn("{}: {}", msg, engine);
            throw new RuntimeException(msg);
        }
        return instance;
    }

	private VirtualMachine launchEngine(final DynamicEngine engine, final String name,
			final String type, final Map<String, String> tags) throws Exception {
		log.debug("launchEngine(): name={}; type={}", name, type);

		final VirtualMachine instance = azureClient.launch(name, type, tags);
		provisionedEngines.put(name, instance);
		return instance;
	}

	private Host createHost(final String name, final VirtualMachine instance) {
		final String ip = instance.getPrimaryNetworkInterface().primaryPrivateIP();
		final String publicIp = instance.getPrimaryPublicIPAddress() != null ?
				instance.getPrimaryPublicIPAddress().ipAddress() : ip;
		final String cxIp = azureConfig.isUsePublicUrlForCx() ? publicIp : ip;
		final String monitorIp = azureConfig.isUsePublicUrlForMonitor() ? publicIp : ip;

		String startTime = VM.getTag(instance, AzureConstants.START_TIME_TAG);
		final DateTime launchTime = startTime != null ? new DateTime(startTime) : DateTime.now();

		return new Host(name, ip, publicIp, 
				engineClient.buildEngineServiceUrl(cxIp), 
				engineClient.buildEngineServiceUrl(monitorIp), launchTime);
	}

	private void pingEngine(Host host) throws Exception {
		log.trace("pingEngine(): host={}", host);
		
		final TimeoutTask<Boolean> pingTask = 
				new TimeoutTask<>("pingEngine-"+host.getIp(), azureConfig.getCxEngineTimeoutSec(), 
				        TimeUnit.SECONDS, taskManager);
		final String ip = azureConfig.isUsePublicUrlForMonitor() ? host.getPublicIp(): host.getIp();
		try {
			pingTask.execute(() -> {
			    do {
                    log.trace("Engine ping failed, waiting to retry; sleep={}ms; {}", pollingMillis, host); 
                    TimeUnit.MILLISECONDS.sleep(pollingMillis);
			    } while (!engineClient.pingEngine(ip));
				return true;
			});
        } catch (CancellationException | InterruptedException | RejectedExecutionException e) {
            log.warn("Failed to ping CxEngine service; {}; cause={}; message={}", 
                    host, e.getCause(), e.getMessage());
            // do not retry, so throw Interrupted exception
            throw new InterruptedException(e.getMessage());
		} catch (Exception e) {
			log.warn("Failed to ping CxEngine service; {}; cause={}; message={}", 
					host, e.getCause(), e.getMessage());
			throw e;
		}
	}
	
	void runScript(String scriptFile, DynamicEngine engine) {
		log.trace("runScript() : script={}", scriptFile);
		
		if (Strings.isNullOrEmpty(scriptFile)) return;
		
		//TODO: pass a readonly copy of DynamicEngine 
		final ScriptRunner<DynamicEngine> runner = new ScriptRunner<DynamicEngine>();
		if (runner.loadScript(scriptFile)) {
			runner.bindData("engine", engine);
			taskManager.addTask("script-"+ engine.getName(), executor.submit(runner));
		} else {
			log.debug("Script file not found: {}", scriptFile);
		}
	}

	@Override
	public String toString() {
		return MoreObjects.toStringHelper(this)
				.add("azureConfig", azureConfig)
				.add("cxConfig", cxConfig)
				.toString();
	}
}
