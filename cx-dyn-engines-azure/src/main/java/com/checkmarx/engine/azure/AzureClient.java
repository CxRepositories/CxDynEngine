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

import static com.microsoft.azure.management.resources.fluentcore.utils.SdkContext.randomResourceName;

import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;

import com.google.common.base.Stopwatch;
import com.microsoft.azure.management.Azure;
import com.microsoft.azure.management.compute.PowerState;
import com.microsoft.azure.management.compute.VirtualMachine;
import com.microsoft.azure.management.compute.VirtualMachineCustomImage;
import com.microsoft.azure.management.compute.VirtualMachineSizeTypes;
import com.microsoft.azure.management.network.Network;
import com.microsoft.azure.management.network.PublicIPAddress;
import com.microsoft.azure.management.resources.fluentcore.arm.Region;
import com.microsoft.azure.management.resources.fluentcore.model.Creatable;

/**
 * Launches and terminates Azure VM instances from a specified image.
 *  
 * @author ken.mcdonald@checkmarx.com
 *
 */
@Component
@Profile("azure")
public class AzureClient implements AzureComputeClient {
	private final Azure client;
	private final AzureEngineConfig config;

	private static final Logger log = LoggerFactory.getLogger(AzureClient.class);

	public AzureClient(Azure client, AzureEngineConfig config) {
		this.client = client;
		this.config = config;

		log.info("ctor(): {}", this);
	}

	@Override
	@NotNull
	public AzureEngineConfig getConfig() {
		return config;
	}

	@Override
	@Retryable(
			value = { RuntimeException.class },
			maxAttempts = AzureConstants.RETRY_ATTEMPTS,
			backoff = @Backoff(delay = AzureConstants.RETRY_DELAY))
	public @NotNull VirtualMachine launch(@NotBlank String name, @NotBlank String instanceType, @NotNull Map<String, String> tags) throws Exception {
		log.trace("launch(): name={}; instanceType={}", name, instanceType);

		VirtualMachine instance = null;
		String requestId = null;
		boolean success = false;
		final Stopwatch timer = Stopwatch.createStarted();
		try {
			VirtualMachineCustomImage image = client.virtualMachineCustomImages().getByResourceGroup(config.getResourceGroup(), config.getImageName());
			Network network = client.networks().getByResourceGroup(config.getResourceGroup(), config.getNetworkName());
			Creatable<VirtualMachine> vm;

			if (config.isAssignPublicIP()) {
				String randomPubIPResource = randomResourceName("de-", 10);
				Creatable<PublicIPAddress> publicIpAddressCreatable = client.publicIPAddresses()
						.define(randomPubIPResource)
						.withRegion(Region.fromName(config.getRegion()))
						.withExistingResourceGroup(config.getResourceGroup())
						.withLeafDomainLabel(randomPubIPResource);

				vm = client.virtualMachines()
						.define(name)
						.withRegion(Region.fromName(config.getRegion()))
						.withExistingResourceGroup(config.getResourceGroup())
						.withExistingPrimaryNetwork(network)
						.withSubnet(config.getSubnetName())
						.withPrimaryPrivateIPAddressDynamic()
						.withNewPrimaryPublicIPAddress(publicIpAddressCreatable)
						.withWindowsCustomImage(image.id())
						.withAdminUsername(config.getServerAdmin())
						.withAdminPassword(config.getServerPassword())
						.withComputerName(name)
						//.withOSDiskSizeInGB(config.getDiskSize().intValue()) DISK size will be determined by Image
						.withSize(VirtualMachineSizeTypes.fromString(instanceType))
						.withTags(tags);
			}
			else {
				vm = client.virtualMachines()
						.define(name)
						.withRegion(Region.fromName(config.getRegion()))
						.withExistingResourceGroup(config.getResourceGroup())
						.withExistingPrimaryNetwork(network)
						.withSubnet(config.getSubnetName())
						.withPrimaryPrivateIPAddressDynamic()
						.withoutPrimaryPublicIPAddress()
						.withWindowsCustomImage(image.id())
						.withAdminUsername(config.getServerAdmin())
						.withAdminPassword(config.getServerPassword())
						.withComputerName(name)
						//.withOSDiskSizeInGB(config.getDiskSize().intValue()) DISK size will be determined by Image
						.withSize(VirtualMachineSizeTypes.fromString(instanceType))
						.withTags(tags);
			}

			instance = vm.create();

			return instance;
		} catch (CancellationException | RejectedExecutionException e) {
			// do not retry, so throw original exception
			handleLaunchException(e, instance, name);
			throw new InterruptedException(e.getMessage());
		} catch (Exception t) {
			// retry, so throw RuntimeException
			handleLaunchException(t, instance, name);
			throw new RuntimeException("Failed to launch Azure VirtualMachine instance", t);
		} finally {
			log.info("action={}, success={}; elapsedTime={}s; {}; requestId={}",
					"launchInstance", success, timer.elapsed(TimeUnit.SECONDS), VM.print(instance), requestId);
		}
	}

	@Override
	@Retryable(
			value = { RuntimeException.class },
			maxAttempts = AzureConstants.RETRY_ATTEMPTS,
			backoff = @Backoff(delay = AzureConstants.RETRY_DELAY))
	@NotNull
	public @NotNull VirtualMachine start(@NotBlank String instanceId) throws InterruptedException, Exception {
		log.trace("start(): instanceId={}", instanceId);

		try {
			final VirtualMachine instance = client.virtualMachines().getById(instanceId);
			PowerState instancePowerState = instance.powerState();
			if (!instancePowerState.equals(PowerState.RUNNING)
					&& !instancePowerState.equals(PowerState.STARTING)) { //Check in case first pass successfully started despite exception
				instance.start();
				log.info("action=startInstance; instanceId={}; state={}; {}",
						instanceId, instance.powerState(), VM.print(instance));
			} else {
				log.info("VirtualMachine Instance is already starting/running; instanceId={}", instance.id());
			}

			return instance;

		} catch (Throwable e) {
			log.warn("Failed to start EC2 instance; instanceId={}; cause={}; message={}",
					instanceId, e, e.getMessage());
			throw new RuntimeException("Failed to start Azure VirtualMachine instance", e);
		}
	}

	@Override
	@Retryable(
			value = { RuntimeException.class },
			maxAttempts = AzureConstants.RETRY_ATTEMPTS,
			backoff = @Backoff(delay = AzureConstants.RETRY_DELAY))
	public void stop(@NotBlank String instanceId) {
		log.trace("stop(): instanceId={}", instanceId);

		try {
			final VirtualMachine instance = client.virtualMachines().getById(instanceId);
			instance.powerOff();
		} catch (Throwable e) {
			log.warn("Failed to stop Azure instance; instanceId={}; cause={}; message={}",
					instanceId, e, e.getMessage());
			throw new RuntimeException("Failed to stop Azure VirtualMachine instance", e);
		}
	}

	@Override
	@Retryable(
			value = { RuntimeException.class },
			maxAttempts = AzureConstants.RETRY_ATTEMPTS,
			backoff = @Backoff(delay = AzureConstants.RETRY_DELAY))
	public void terminate(@NotBlank String instanceId) {
		log.trace("terminate(): instanceId={}", instanceId);

		try {
			client.virtualMachines().deleteById(instanceId); // TODO Async?  Timeout?
			//TODO Clean up NIC/PublicIP/Disk as Azure does not handle this automatically
		} catch (Throwable e) {
			log.warn("Failed to stop Azure instance; instanceId={}; cause={}; message={}",
					instanceId, e, e.getMessage());
			throw new RuntimeException("Failed to stop Azure VirtualMachine instance", e);
		}
	}

	@Override
	public @NotNull List<VirtualMachine> find(@NotNull Map<String, String> tags) {
		ListIterator<VirtualMachine> vms = client.virtualMachines().list().listIterator();
		List<VirtualMachine> vmList = new ArrayList<>();
		while(vms.hasNext()){
			VirtualMachine vm = vms.next();
			if(vm.tags().entrySet().containsAll(tags.entrySet())){
				vmList.add(vm);
			}
		}
		return vmList;
	}

	@Override
	@Retryable(
			value = { RuntimeException.class },
			maxAttempts = AzureConstants.RETRY_ATTEMPTS,
			backoff = @Backoff(delay = AzureConstants.RETRY_DELAY))
	@NotNull
	public @NotNull VirtualMachine describe(@NotBlank String instanceId) {
		log.trace("describe(): instanceId={}", instanceId);
		try{
			return client.virtualMachines().getById(instanceId);
		} catch (Throwable e) {
			log.warn("Failed to stop Azure instance; instanceId={}; cause={}; message={}",
					instanceId, e, e.getMessage());
			throw new RuntimeException("Failed to describe Azure VirtualMachine instance", e);
		}
	}

	@Override
	@Retryable(
			value = { RuntimeException.class },
			maxAttempts = AzureConstants.RETRY_ATTEMPTS,
			backoff = @Backoff(delay = AzureConstants.RETRY_DELAY))
	public @NotNull VirtualMachine updateTags(@NotNull VirtualMachine instance, @NotNull Map<String, String> tags) {
		final String instanceId = instance.id();
		log.trace("updateTags(): instance={}", instanceId);
		instance.update().withTags(tags).apply();
		//refresh object state
		return client.virtualMachines().getById(instanceId);
	}

	@Override
	public boolean isProvisioned(@NotBlank String instanceId) throws Exception {
		final VirtualMachine instance = client.virtualMachines().getById(instanceId);
		return VM.isProvisioned(instance);
	}

	@Override
	public boolean isRunning(@NotBlank String instanceId) throws Exception {
		final VirtualMachine instance = client.virtualMachines().getById(instanceId);
		return VM.isRunning(instance);
	}

	private void handleLaunchException(Throwable t, VirtualMachine instance, String name) {
		log.warn("Failed to launch Azure VirtualMachine instance; name={}; cause={}; message={}",
				name, t, t.getMessage());
		if (instance != null) {
			final String instanceId = instance.id();
			log.warn("Terminating failed instance launch; instanceId={}", instanceId);
			safeTerminate(instanceId);
		}
	}

	private void safeTerminate(@NotBlank String instanceId) {
		log.trace("safeTerminate(): instanceId={}", instanceId);
		try {
			terminate(instanceId);
		} catch (Throwable t) {
			// log and swallow
			log.warn("Error during safeTerminate; instanceId={}; cause={}; message={}",
					instanceId, t, t.getMessage());
		}
	}

}
