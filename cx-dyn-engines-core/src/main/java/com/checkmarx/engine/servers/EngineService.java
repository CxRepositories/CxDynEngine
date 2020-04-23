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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.checkmarx.engine.CxConfig;
import com.checkmarx.engine.domain.EnginePool;
import com.checkmarx.engine.rest.CxEngineApi;
import com.checkmarx.engine.utils.ExecutorServiceUtils;
import com.checkmarx.engine.utils.TaskManager;

@Component
public class EngineService implements Runnable {

	private static final Logger log = LoggerFactory.getLogger(EngineService.class);

	private final CxConfig config;
	private final ScanQueueMonitor scanQueueMonitor;
	private final EngineManager engineManager;

	private final ExecutorService engineManagerExecutor;
	private final ScheduledExecutorService scanQueueExecutor;
	private final TaskManager taskManager;

	public EngineService(CxEngineApi cxClient, CxConfig config, ScanQueueMonitor scanQueueMonitor, 
			EngineManager engineManager, EnginePool enginePool, TaskManager taskManager) {
		this.config = config;
		this.scanQueueMonitor = scanQueueMonitor;
		this.engineManager = engineManager;
		this.engineManagerExecutor = ExecutorServiceUtils.buildSingleThreadExecutorService("eng-service-%d", true);
		this.scanQueueExecutor = ExecutorServiceUtils.buildScheduledExecutorService("queue-mon-%d", true);
		this.taskManager = taskManager;
		
		log.info("ctor(): {}", this.config);
	}
	
	@Override
	public void run() {
		log.info("run()");
	
		final int pollingInterval = config.getQueueIntervalSecs();
		try {
		
            taskManager.addExecutor("EngineManagerExecutor", engineManagerExecutor);
            taskManager.addExecutor("ScanQueueExecutor", scanQueueExecutor);

            engineManager.initialize();
		    
			log.info("Launching EngineManager...");
			taskManager.addTask("EngineManager", engineManagerExecutor.submit(engineManager));
			
			log.info("Launching ScanQueueMonitor; pollingInterval={}s", pollingInterval);
			taskManager.addTask("ScanQueueMonitor", 
			        scanQueueExecutor.scheduleAtFixedRate(scanQueueMonitor, 0L, pollingInterval, TimeUnit.SECONDS));

		} catch (Throwable t) {
			log.error("Error occurred while launching Engine services, shutting down; cause={}; message={}", 
					t, t.getMessage(), t); 
			shutdown();
		}
	}

	@PreDestroy
	public void stop() {
		log.info("stop()");
		shutdown();
	}
	
	private void shutdown() {
		log.info("shutdown()");

        // taskManager will shutdown executors and cancel all tasks
		taskManager.shutdown();
		//engineManagerExecutor.shutdown();
		//scanQueueExecutor.shutdown();
	}
	
}
