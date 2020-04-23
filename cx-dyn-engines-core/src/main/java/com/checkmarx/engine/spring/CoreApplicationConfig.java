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
package com.checkmarx.engine.spring;

import java.util.List;

import com.checkmarx.engine.rest.Notification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.retry.annotation.EnableRetry;

import com.checkmarx.engine.CxConfig;
import com.checkmarx.engine.domain.DefaultEnginePoolBuilder;
import com.checkmarx.engine.domain.EnginePool;
import com.checkmarx.engine.domain.EnginePool.EnginePoolEntry;
import com.checkmarx.engine.domain.EnginePoolConfig;
import com.checkmarx.engine.domain.ScanQueue;
import com.checkmarx.engine.rest.CxEngineApi;
import com.checkmarx.engine.servers.CxEngines;
import com.checkmarx.engine.servers.EngineManager;
import com.checkmarx.engine.servers.ScanQueueMonitor;
import com.checkmarx.engine.utils.TaskManager;
import com.fasterxml.jackson.datatype.joda.JodaModule;

/**
 * Spring Boot configuration
 * 
 * @author randy@checkmarx.com
 */
@EnableRetry
public class CoreApplicationConfig {
	
	private static final Logger log = LoggerFactory.getLogger(CoreApplicationConfig.class);
	
	public CoreApplicationConfig() {
		log.info("ctor()");
	}

	@Bean
	public JodaModule jacksonJodaModule() {
		return new JodaModule();
	}
	
	@Bean
	public ScanQueue scansQueued(CxConfig config) {
		return new ScanQueue(config.getQueueCapacity());
	}
	
	@Bean
	public ScanQueue scansFinished(CxConfig config) {
		return new ScanQueue(config.getQueueCapacity());
	}
	
	@Bean
	public EnginePool enginePool(
			EnginePoolConfig poolConfig) {
	    poolConfig.validate();
	    log.info("EnginePoolConfig is valid; {}", poolConfig); 
		final DefaultEnginePoolBuilder builder = new DefaultEnginePoolBuilder(poolConfig); 
		final List<EnginePoolEntry> pool = poolConfig.getPool();
		pool.forEach((entry) -> {
			builder.addEntry(entry);
		});
		return builder.build();
	}
	
    @Bean
    public ScanQueueMonitor queueMonitor(
            CxConfig config,
            CxEngineApi cxClient,
            EnginePool enginePool,
            ScanQueue scansQueued, 
            ScanQueue scansFinished) {
        return new ScanQueueMonitor(scansQueued.getQueue(), scansFinished.getQueue(), enginePool, cxClient, config);
    }
    
	@Bean
	public EngineManager engineManager(
			CxConfig config,
			EnginePool enginePool,
			CxEngineApi cxClient,
			CxEngines engineProvisioner,
			TaskManager taskManager,
			ScanQueueMonitor scanQueueMonitor,
			ScanQueue scansQueued,
			ScanQueue scansFinished,
			Notification notify) {
		
		return new EngineManager(config, enginePool, cxClient, engineProvisioner, taskManager, 
		        scanQueueMonitor, scansQueued.getQueue(), scansFinished.getQueue(), notify);
	}
	
}
