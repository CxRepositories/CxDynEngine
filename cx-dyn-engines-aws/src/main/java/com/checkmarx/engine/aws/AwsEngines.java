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
package com.checkmarx.engine.aws;

import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import javax.validation.constraints.NotNull;

import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;

import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.Tag;
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

/**
 * AWS {@code CxEngines} provider.
 * 
 * @author randy@checkmarx.com
 *
 */
@Component
@Profile("aws")
public class AwsEngines implements CxEngines {

	private static final Logger log = LoggerFactory.getLogger(AwsEngines.class);
	
    static final DateTimeFormatter ISO_PARSER = ISODateTimeFormat.dateTimeParser();
    static final DateTimeFormatter ISO_FORMATTER = ISODateTimeFormat.dateTimeNoMillis();

    private final CxConfig cxConfig;
	private final AwsEngineConfig awsConfig;
	private final EnginePoolConfig poolConfig;
	private final AwsComputeClient ec2Client;
	private final CxEngineClient engineClient;
	private final TaskManager taskManager;
	private final int pollingMillis;
    private final ExecutorService executor;

	/**
	 * Maps engine name to EC2 instance; key=engine name
	 */
	private final Map<String, Instance> provisionedEngines = Maps.newConcurrentMap();
	
	/**
	 * Maps EngineSize to EC2 instanceType;
	 * key=size (name), 
	 * value=ec2 instance type (e.g. m4.large)
	 */
	private final Map<String, String> engineTypeMap;
	
	public AwsEngines(
	        CxConfig cxConfig,
			EnginePoolConfig poolConfig,
			AwsComputeClient awsClient, 
			CxEngineClient engineClient,
			TaskManager taskManager) {
		
	    this.cxConfig = cxConfig;
	    this.poolConfig = poolConfig;
		this.ec2Client = awsClient;
		this.awsConfig = awsClient.getConfig(); 
		this.engineClient = engineClient;
		this.taskManager = taskManager;
		this.engineTypeMap = awsConfig.getEngineSizeMap();
		this.pollingMillis = awsConfig.getMonitorPollingIntervalSecs() * 1000;
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
		final Map<String, String> tags = createCxTags(CxServerRole.ENGINE, awsConfig.getCxVersion());
		tags.put(CX_SIZE_TAG, size);
		// set scanID to empty initially
		tags.put(CX_SCAN_ID_TAG, "");
		// set launch time
		tags.put(CX_LAUNCH_TIME_TAG, ISO_FORMATTER.print(DateTime.now()));
		// add custom tags from configuration
		awsConfig.getTagMap().forEach(tags::put);
		return tags;
	}

	private Map<String, String> getEngineTags() {
		final Map<String, String> tags = createCxTags(CxServerRole.ENGINE, awsConfig.getCxVersion());
		awsConfig.getTagMap().forEach(tags::put);
		return tags;
	}

	Map<String, Instance> findEngines() {
		log.trace("findEngines()");
		
		final Stopwatch timer = Stopwatch.createStarted(); 
		try {
			final List<Instance> engines = ec2Client.find(getEngineTags());
			engines.forEach((instance) -> {
				if (Ec2.isTerminated(instance)) {
					log.info("Terminated engine found: {}", Ec2.print(instance));
					return;
				}

				final String name = Ec2.getName(instance);
				if (!provisionedEngines.containsKey(name)) {
					provisionedEngines.put(name, instance);
					log.info("Provisioned engine found: {}", Ec2.print(instance));
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
		final Map<String, Instance> engines = findEngines();
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
		ec2Client.stop(instanceId);
	}

	DynamicEngine buildDynamicEngine(@NotNull String name, @NotNull Instance instance) {
		final String size = lookupEngineSize(instance);
        final DateTime startTime = new DateTime(instance.getLaunchTime());
        final DateTime launchTime = new DateTime(lookupLaunchTime(instance));
		final boolean isRunning = Ec2.isRunning(instance);
		final String scanId = lookupScanId(instance);
		final String engineId = lookupEngineId(instance);
		final long scanCount = lookupScanCount(instance);
		final DynamicEngine engine = DynamicEngine.fromProvisionedInstance(
				name, size, poolConfig.getEngineExpireIntervalSecs(),
				launchTime, startTime, isRunning, scanId, engineId, scanCount);
		if (isRunning) {
			engine.setHost(createHost(name, instance));
		}
		return engine;
	}
	
    private DateTime lookupLaunchTime(Instance instance) {
        final String date = Ec2.getTag(instance, CX_LAUNCH_TIME_TAG);
        if (Strings.isNullOrEmpty(date)) return null;
        return ISO_PARSER.parseDateTime(date);
    }
    
	private String lookupScanId(Instance instance) {
        return Ec2.getTag(instance, CX_SCAN_ID_TAG);
    }
	
    private String lookupEngineId(Instance instance) {
        return Ec2.getTag(instance, CX_ENGINE_ID_TAG);
    }
    
    private long lookupScanCount(Instance instance) {
        final String count = Ec2.getTag(instance, CX_SCAN_COUNT_TAG);
        return Strings.isNullOrEmpty(count) ? 0L : Long.valueOf(count);
    }
    
    private String lookupEngineSize(Instance instance) {
		final String sizeTag = Ec2.getTag(instance, CX_SIZE_TAG);
        //TODO: validate instance type to size
		if (!Strings.isNullOrEmpty(sizeTag)) return sizeTag;
		
		for (Entry<String,String> entry : engineTypeMap.entrySet()) {
			String instanceType = entry.getValue();
			String size = entry.getKey();
			if (instance.getInstanceType().equals(instanceType))
				return size;
		}
		// if not found, return first size in map
		final String size = Iterables.getFirst(engineTypeMap.keySet(), "S"); 
		log.warn("Engine size tag doesn't match current settings, assuming {} engine: tag={}; instance={}", 
		        size, sizeTag, instance.getInstanceId());
		return size; 
	}
    
	@Override
	@Retryable(
			value = { RuntimeException.class },
			maxAttempts = AwsConstants.RETRY_ATTEMPTS,
			backoff = @Backoff(delay = AwsConstants.RETRY_DELAY))
	public void launch(DynamicEngine engine, EngineSize size, boolean waitForSpinup) throws InterruptedException {
		log.debug("launch(): {}; size={}; wait={}", engine, size, waitForSpinup);
		
		final String name = engine.getName();
		final String type = engineTypeMap.get(size.getName());
		final Map<String, String> tags = createEngineTags(size.getName());
		
		Instance instance = provisionedEngines.get(name);
		String instanceId = null;
		
		log.info("action=LaunchingEngine; name={}; {}", name, engine); 

		boolean success = false;
		final Stopwatch timer = Stopwatch.createStarted();
		try {
			if (instance != null) {
	            log.debug("...EC2 instance is provisioned...");
			} else {
			    instance = launchEngine(engine, name, type, tags);
			    engine.onLaunch(new DateTime(instance.getLaunchTime()));
			}
	
			// refresh instance state
            instanceId = instance.getInstanceId();
            instance = ec2Client.describe(instanceId);
            provisionedEngines.put(name, instance);
			
			if (Ec2.isTerminated(instance)) {
			    log.debug("...EC2 instance is terminated, launching new instance...");
				instance = launchEngine(engine, name, type, tags);
				instanceId = instance.getInstanceId();
                engine.onLaunch(new DateTime(instance.getLaunchTime()));
            } else if (Ec2.isStopping(instance)) {
                final int sleep = awsConfig.getStopWaitTimeSecs();
                do {
                    log.debug("...EC2 instance is stopping, sleeping {}s...", sleep);
                    Thread.sleep(sleep * 1000);
                    instance = ec2Client.describe(instanceId);
                } while (Ec2.isStopping(instance));
                log.debug("...EC2 instance is stopped, starting instance...");
                instance = ec2Client.start(instanceId);
			} else if (!Ec2.isRunning(instance)) {
                log.debug("...EC2 instance is stopped, starting instance...");
				instance = ec2Client.start(instanceId);
			} else {
			    // this will happen if engine was launched
                log.debug("...EC2 instance is running...");
			}
			
            instance = ec2Client.describe(instanceId);
            engine.onStart(new DateTime(instance.getLaunchTime()));
			final Host host = createHost(name, instance);
			engine.setHost(host);
			
            provisionedEngines.put(name, instance);

            if (waitForSpinup) {
				pingEngine(host);
			}
			//move this logic into caller
			runScript(awsConfig.getScriptOnLaunch(), engine);
			
			success = true;
        } catch (CancellationException | InterruptedException | RejectedExecutionException e) {
            log.warn("Error occurred while launching AWS EC2 instance; name={}; {}", name, engine, e);
            handleLaunchException(instanceId, e);
            throw new InterruptedException(e.getMessage());
		} catch (Throwable e) {
			log.error("Error occurred while launching AWS EC2 instance; name={}; {}", name, engine, e);
            handleLaunchException(instanceId, e);
            throw new RuntimeException("Error launching engine", e);
		} finally {
			log.info("action=LaunchedEngine; success={}; name={}; id={}; elapsedTime={}s; {}", 
					success, name, instanceId, timer.elapsed(TimeUnit.SECONDS), Ec2.print(instance)); 
		}
	}
	
	private void handleLaunchException(String instanceId, Throwable e) {
        if (!Strings.isNullOrEmpty(instanceId)) {
            log.warn("Terminating instance due to error; instanceId={}", instanceId);
            ec2Client.terminate(instanceId);
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
        Instance instance = null;
        boolean success = false;
        final String name = engine.getName();

        final Stopwatch timer = Stopwatch.createStarted();
        try {
    		instance = lookupInstance(engine, "stop");
    		instanceId = instance.getInstanceId();
			
			if (cxConfig.isTerminateOnStop() || forceTerminate) {	
				action = "TerminatedEngine";
				ec2Client.terminate(instanceId);
				provisionedEngines.remove(name);
                engine.onTerminate();
				runScript(awsConfig.getScriptOnTerminate(), engine);
			} else {
				ec2Client.stop(instanceId);
				instance = ec2Client.describe(instanceId);
				// update the map with updated instance
				provisionedEngines.put(name, instance);
                engine.onStop();
                runScript(awsConfig.getScriptOnStop(), engine);
			}
			success = true;
			
		} finally {
			log.info("action={}; success={}; name={}; id={}; elapsedTime={}ms; {}", 
					action, success, name, instanceId, timer.elapsed(TimeUnit.MILLISECONDS), Ec2.print(instance)); 
		}
	}
	
	private String emptyIfNull(String tag) {
	    return tag == null ? "" : tag;
	}

    @Override
    public void onScanAssigned(DynamicEngine toEngine) {
        final String scanId = toEngine.getScanId();
        final String engineId = toEngine.getEngineId();
        final long scanCount = toEngine.getStats().getScanCount();
        log.debug("onScanAssigned(): scanId={}; engineId={}; {}", scanId, engineId, toEngine);
        final Tag tag = new Tag(CX_SCAN_ID_TAG, emptyIfNull(scanId));
        final Tag idTag = new Tag(CX_ENGINE_ID_TAG, emptyIfNull(engineId));
        final Tag count = new Tag(CX_SCAN_COUNT_TAG, String.valueOf(scanCount));
        tagEngine(toEngine, tag, idTag, count);
    }

    @Override
    public void onScanRemoved(DynamicEngine fromEngine) {
        final String scanId = fromEngine.getScanId();
        final String engineId = fromEngine.getEngineId();
        log.debug("onScanRemoved(): scanId={}; engineId={}; {}", scanId, engineId, fromEngine);
        //clear engine tags
        final Tag tag = new Tag(CX_SCAN_ID_TAG, "");
        final Tag idTag = new Tag(CX_ENGINE_ID_TAG, "");
        tagEngine(fromEngine, tag, idTag);
    }
    
    Instance tagEngine(DynamicEngine engine, Tag... tags) {
        Instance instance = lookupInstance(engine, "tag");
        final String name = engine.getName();
        final String instanceId = instance.getInstanceId();
        
        final String action = "TagEngine";
        boolean success = false;
        final Stopwatch timer = Stopwatch.createStarted();
        try {
            instance = ec2Client.updateTags(instance, tags);
            provisionedEngines.put(name, instance);
            success = true;
            return instance;
        } catch (Exception e) {
            log.warn("Failed to tag engine; {}; cause={}; message={}", 
                    engine, e.getCause(), e.getMessage());
            throw e;
        } finally {
            log.info("action={}; success={}; name={}; id={}; elapsedTime={}ms; {}", 
                    action, success, name, instanceId, timer.elapsed(TimeUnit.MILLISECONDS), Ec2.print(instance)); 
        }
    }
    
    private Instance lookupInstance(DynamicEngine engine, String operation) {
        final String name = engine.getName();
        final Instance instance = provisionedEngines.get(name);
        if (instance == null) {
            final String msg = String.format("Cannot %s engine, engine not found; engine=%s", 
                    operation, name); 
            log.warn("{}: {}", msg, engine);
            throw new RuntimeException(msg);
        }
        return instance;
    }

	private Instance launchEngine(final DynamicEngine engine, final String name, 
			final String type, final Map<String, String> tags) throws Exception {
		log.debug("launchEngine(): name={}; type={}", name, type);
		
		final Instance instance = ec2Client.launch(name, type, tags);
		provisionedEngines.put(name, instance);
		return instance;
	}

	private Host createHost(final String name, final Instance instance) {
		final String ip = instance.getPrivateIpAddress();
		final String publicIp = instance.getPublicIpAddress();
		final String cxIp = awsConfig.isUsePublicUrlForCx() ? publicIp : ip;
		final String monitorIp = awsConfig.isUsePublicUrlForMonitor() ? publicIp : ip;
		final DateTime launchTime = lookupLaunchTime(instance);
		final DateTime startTime = new DateTime(instance.getLaunchTime());
		return new Host(name, ip, publicIp, 
				engineClient.buildEngineServiceUrl(cxIp), 
				engineClient.buildEngineServiceUrl(monitorIp), launchTime, startTime);
	}

	private void pingEngine(Host host) throws Exception {
		log.trace("pingEngine(): host={}", host);
		
		final TimeoutTask<Boolean> pingTask = 
				new TimeoutTask<>("pingEngine-"+host.getIp(), awsConfig.getCxEngineTimeoutSec(), 
				        TimeUnit.SECONDS, taskManager);
		final String ip = awsConfig.isUsePublicUrlForMonitor() ? host.getPublicIp(): host.getIp();
		try {
			pingTask.execute(() -> {
			    final Stopwatch timer = Stopwatch.createStarted();
                TimeUnit.MILLISECONDS.sleep(pollingMillis);
			    while (!engineClient.pingEngine(ip)) {
                    log.debug("Engine ping failed, waiting to retry; sleep={}ms; elapsedTime={}s; {}", 
                            pollingMillis, timer.elapsed(TimeUnit.SECONDS), host);
                    TimeUnit.MILLISECONDS.sleep(pollingMillis);
			    }
                log.debug("Engine online; elapsed={}s; {}", timer.elapsed(TimeUnit.SECONDS), host);
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
				.add("awsConfig", awsConfig)
				.add("cxConfig", cxConfig)
				.toString();
	}
}
