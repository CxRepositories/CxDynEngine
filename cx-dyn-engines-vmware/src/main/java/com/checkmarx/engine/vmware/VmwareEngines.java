/**
 * Copyright (c) 2017 Checkmarx
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
 */
package com.checkmarx.engine.vmware;

import java.rmi.RemoteException;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;

import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.checkmarx.engine.domain.DynamicEngine;
import com.checkmarx.engine.domain.EnginePoolConfig;
import com.checkmarx.engine.domain.EngineSize;
import com.checkmarx.engine.domain.Host;
import com.checkmarx.engine.rest.CxEngineClient;
import com.checkmarx.engine.servers.CxEngines;
import com.google.common.base.MoreObjects;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.base.Stopwatch;
import com.google.common.base.Strings;
import com.vmware.vim25.mo.*;

/**
 * {@code CxEngines} implementation for VMware hosted engines.
 * 
 * @author sergio.pinto@checkmarx.com
 */
@Component
@Profile("vmware")
public class VmwareEngines implements CxEngines {
	
	private static final Logger log = LoggerFactory.getLogger(VmwareEngines.class);
	
	/**
	 * Maps engine name to Vmware instance; key=engine name
	 */
	private final Map<String, VirtualMachine> provisionedEngines = Maps.newConcurrentMap();
	
	private final VmwareEngineConfig vmwareConfig;
	private final EnginePoolConfig poolConfig;
	private final VmwareClient client;
	private final CxEngineClient engineClient;

	public VmwareEngines(EnginePoolConfig poolConfig,VmwareClient client,CxEngineClient engineClient) {
		
		this.poolConfig = poolConfig;
		this.engineClient = engineClient;
		this.client = client;
		this.vmwareConfig = client.getConfig();
		
		log.info("{}", this);
	}

	@Override
	public List<DynamicEngine> listEngines() {
		log.trace("listEngines()");
		
		final Map<String, VirtualMachine> engines = findEngines();
		final List<DynamicEngine> dynEngines = Lists.newArrayList();
		engines.forEach((name, vm) -> {
			final DynamicEngine engine = buildDynamicEngine(name, vm);
			dynEngines.add(engine);
		});
		return dynEngines;
	}

	@Override
	public void launch(DynamicEngine engine, EngineSize size, boolean waitForSpinup) {
		log.info("launch() : {}; size={}; waitForSpinup={}", engine, size, waitForSpinup);

		findEngines();
		
		final String name = engine.getName();
		
		VirtualMachine vm = provisionedEngines.get(name);
		String vmUUID = null;

		boolean success = false;
		final Stopwatch timer = Stopwatch.createStarted();
		try {
			if (vm == null) {
				vm = launchEngine(name, size);
				engine.onLaunch(DateTime.now());
			}

			vmUUID = VmwareVm.getUUID(vm);
			
			if (VmwareVm.isTerminated(vm)) {
				vm = launchEngine(name, size);
				vmUUID = VmwareVm.getUUID(vm);
			} else if (!VmwareVm.isRunning(vm)) {
				vm = client.powerOn(vm);
				vm = client.waitForIp(vm);
			}

			/** getting to this point... so host is running */
			
			final Host host = createHost(name, vm);
			engine.setHost(host);
			engine.onStart(host.getLaunchTime());
			
			success = true;
			
		} catch (Throwable e) {
			log.error("Error occurred while launching Vmware Vm; name={}; {}", name, engine, e);
			if (!Strings.isNullOrEmpty(vmUUID)) {
				log.warn("Terminating instance due to error; instanceId={}", vmUUID);
				throw new RuntimeException("Error launching engine", e);
			}
		} finally {
			log.info("action=LaunchedEngine; success={}; name={}; id={}; elapsedTime={}s; {}", 
					success, name, vmUUID, timer.elapsed(TimeUnit.SECONDS), VmwareVm.print(vm)); 
		}
	}
	
	private VirtualMachine launchEngine(final String name, final EngineSize size) {
		log.info("launchEngine(): name={}; size={}", name, size);
        VirtualMachine vm=null;
        try {
		    vm = client.launch(name, size);
		    provisionedEngines.put(name, vm);

        }catch (RemoteException re){
            log.error("Remove exception {}",re.getMessage());
        }

        return vm;
	}

	@Override
	public void stop(DynamicEngine engine) {
		log.info("stop() : {}", engine);

		stop(engine, false);
	}

	@Override
	public void stop(DynamicEngine engine, boolean forceTerminate) {
		log.info("stop() : {}; forceTerminate={}", engine, forceTerminate);
        try {
            client.stop(engine.getName(), forceTerminate);
            if (forceTerminate)
                engine.onTerminate();
            else
                engine.onStop();
        }catch (RemoteException re){
            log.error("Remove exception {}",re.getMessage());
        }
	}

    @Override
    public void onScanAssigned(DynamicEngine toEngine) {
        log.debug("onScanAssigned() : {}", toEngine);

        // TODO: implement
    }

    @Override
    public void onScanRemoved(DynamicEngine fromEngine) {
        log.debug("onScanRemoved() : {}", fromEngine);

        // TODO: implement
    }

	@Override
	public String toString() {
		return MoreObjects.toStringHelper(this)
				.add("config", vmwareConfig)
				.toString();
	}
	
	private Map<String, VirtualMachine> findEngines() {
		log.trace("findEngines()");
		
		final Stopwatch timer = Stopwatch.createStarted(); 
		try {

            final List<VirtualMachine> engines = client.findAll(VmwareVm.buildAnnotation(CX_ROLE_TAG, CxServerRole.ENGINE.toString()));
            engines.forEach((vm) -> {

                final String name = VmwareVm.getName(vm);
                if (!provisionedEngines.containsKey(name)) {
                    provisionedEngines.put(name, vm);
                    log.info("Provisioned engine found: {}", VmwareVm.print(vm));
                }
            });
        }catch(RemoteException re){
            log.error("Remove exception {}",re.getMessage());
		} finally {
			log.debug("Find Engines: elapsedTime={}ms; count={}", 
					timer.elapsed(TimeUnit.MILLISECONDS), provisionedEngines.size()); 
		}
		return provisionedEngines;
	}
	
	private DynamicEngine buildDynamicEngine(String name, VirtualMachine vm) {
		log.info("buildDynamicEngine(): name {} vm {}",name,vm.toString());
		
		final String size = lookupEngineSize(vm);
		final DateTime launchTime = new DateTime().minusSeconds(VmwareVm.getLaunchTime(vm));
		final Boolean isRunning = VmwareVm.isRunning(vm);
		
		final DynamicEngine engine = DynamicEngine.fromProvisionedInstance(
				name, size, poolConfig.getEngineExpireIntervalSecs(),
				launchTime, launchTime, isRunning, null, null);
		if (isRunning) {
			engine.setHost(createHost(name, vm));
		}
		log.info("buildDynamicEngine(): engine={}",engine); 
		return engine;
	}
	
	private Host createHost(final String name, final VirtualMachine vm) {
		log.info("createHost(): name {} vm {}",name,vm);
		final String ip = VmwareVm.getIpAddress(vm);
		final DateTime launchTime = DateTime.now().minusSeconds(VmwareVm.getLaunchTime(vm));
				
		Host host = new Host(name, ip, ip,
				engineClient.buildEngineServiceUrl(ip),
				engineClient.buildEngineServiceUrl(ip), launchTime);
		log.info("Creating HOST: host {}", host);
		return host;
	}
	
	private String lookupEngineSize(VirtualMachine vm) {
		log.info("lookupEngineSize(): vm {}",vm);
		final Map<String, String> sizeMap = vmwareConfig.getEngineSizeMap();
		final Map<String, String> memMap = vmwareConfig.getEngineMemSizeMap();
		final Map<String, String> cpuMap = vmwareConfig.getEngineCpuSizeMap();
		
		final String mem = String.valueOf(VmwareVm.getMemory(vm));
		final String cpu = String.valueOf(VmwareVm.getCpu(vm));
		
		for (Entry<String,String> entry : sizeMap.entrySet()) {
			String size = entry.getKey();
			if (mem.equals(memMap.get(size)) && cpu.equals(cpuMap.get(size)))
				return size;
		}
		// if not found, return first size in map
		return Iterables.getFirst(sizeMap.values(), "S"); 
	}
	
}
