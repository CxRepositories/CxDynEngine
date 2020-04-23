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
package com.checkmarx.engine.domain;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.checkmarx.engine.domain.DynamicEngine.EngineStats;
import com.checkmarx.engine.domain.DynamicEngine.State;

public class DynamicEngineTests {
	
	private static final Logger log = LoggerFactory.getLogger(DynamicEngineTests.class);
	
    private final int EXPIRE_DURATION = 3;
    
	@Test
    public void testStats() throws Exception {
        log.trace("testStats()");

        long ZERO = 0;
        
        final DynamicEngine engine = new DynamicEngine("name", "S", EXPIRE_DURATION);
        final EngineStats stats = engine.getStats();
        log.info("{}", stats);
        assertThat(stats, is(notNullValue()));
        
        assertThat(stats.getScanCount(), is(0L));
        assertThat("launchTime", stats.getLaunchTime(), is(nullValue()));
        assertThat("startTime", stats.getStartTime(), is(nullValue()));
        assertThat("provisionedTime", stats.getProvisionedTime(), is(Duration.ZERO));
        assertThat("currentRunTime", stats.getCurrentRunTime(), is(Duration.ZERO));
        assertThat("totalRunTime", stats.getTotalRunTime(), is(Duration.ZERO));
        assertThat("currentScanTime", stats.getCurrentScanTime(), is(Duration.ZERO));
        assertThat("cycleScanTime", stats.getCycleScanTime(), is(Duration.ZERO));
        assertThat("totalScanTime", stats.getTotalScanTime(), is(Duration.ZERO));
        assertThat("currentIdleTime", stats.getCurrentIdleTime(), is(Duration.ZERO));
        assertThat("cycleIdleTime", stats.getCycleIdleTime(), is(Duration.ZERO));
        assertThat("totalIdleTime", stats.getTotalIdleTime(), is(Duration.ZERO));
        assertThat("totalStoppedTime", stats.getTotalStoppedTime(), is(Duration.ZERO));
        log.info("new      : {}", stats);
        
        engine.onLaunch(null);
        Thread.sleep(10);
        assertThat("scanCount", stats.getScanCount(), is(0L));
        assertThat("launchTime", stats.getLaunchTime(), is(notNullValue()));
        assertThat("startTime", stats.getStartTime(), is(nullValue()));
        assertThat("provisionedTime", stats.getProvisionedTime().getMillis(), greaterThan(ZERO));
        assertThat("currentRunTime", stats.getCurrentRunTime(), is(Duration.ZERO));
        assertThat("totalRunTime", stats.getTotalRunTime(), is(Duration.ZERO));
        assertThat("currentScanTime", stats.getCurrentScanTime(), is(Duration.ZERO));
        assertThat("cycleScanTime", stats.getCycleScanTime(), is(Duration.ZERO));
        assertThat("totalScanTime", stats.getTotalScanTime(), is(Duration.ZERO));
        assertThat("currentIdleTime", stats.getCurrentIdleTime(), is(Duration.ZERO));
        assertThat("cycleIdleTime", stats.getCycleIdleTime(), is(Duration.ZERO));
        assertThat("totalIdleTime", stats.getTotalIdleTime(), is(Duration.ZERO));
        assertThat("totalStoppedTime", stats.getTotalStoppedTime(), is(Duration.ZERO));
        log.info("launched : {}", stats);
        
        engine.onStart(null);
        Thread.sleep(10);
        assertThat("scanCount", stats.getScanCount(), is(0L));
        assertThat("launchTime", stats.getLaunchTime(), is(notNullValue()));
        assertThat("startTime", stats.getStartTime(), is(notNullValue()));
        assertThat("provisionedTime", stats.getProvisionedTime().getMillis(), greaterThan(ZERO));
        assertThat("currentRunTime", stats.getCurrentRunTime().getMillis(), greaterThan(ZERO));
        assertThat("totalRunTime", stats.getTotalRunTime().getMillis(), greaterThan(ZERO));
        assertThat("currentScanTime", stats.getCurrentScanTime(), is(Duration.ZERO));
        assertThat("cycleScanTime", stats.getCycleScanTime(), is(Duration.ZERO));
        assertThat("totalScanTime", stats.getTotalScanTime(), is(Duration.ZERO));
        assertThat("currentIdleTime", stats.getCurrentIdleTime(), is(Duration.ZERO));
        assertThat("cycleIdleTime", stats.getCycleIdleTime(), is(Duration.ZERO));
        assertThat("totalIdleTime", stats.getTotalIdleTime(), is(Duration.ZERO));
        assertThat("totalStoppedTime", stats.getTotalStoppedTime(), is(Duration.ZERO));
        log.info("launched : {}", stats);
        
        engine.onScan();
        Thread.sleep(10);
        assertThat("scanCount", stats.getScanCount(), is(1L));
        assertThat("launchTime", stats.getLaunchTime(), is(notNullValue()));
        assertThat("startTime", stats.getStartTime(), is(notNullValue()));
        assertThat("provisionedTime", stats.getProvisionedTime().getMillis(), greaterThan(ZERO));
        assertThat("currentRunTime", stats.getCurrentRunTime().getMillis(), greaterThan(ZERO));
        assertThat("totalRunTime", stats.getTotalRunTime().getMillis(), greaterThan(ZERO));
        assertThat("currentScanTime", stats.getCurrentScanTime().getMillis(), greaterThan(ZERO));
        assertThat("cycleScanTime", stats.getCycleScanTime().getMillis(), greaterThan(ZERO));
        assertThat("totalScanTime", stats.getTotalScanTime().getMillis(), greaterThan(ZERO));
        assertThat("currentIdleTime", stats.getCurrentIdleTime(), is(Duration.ZERO));
        assertThat("cycleIdleTime", stats.getCycleIdleTime(), is(Duration.ZERO));
        assertThat("totalIdleTime", stats.getTotalIdleTime(), is(Duration.ZERO));
        assertThat("totalStoppedTime", stats.getTotalStoppedTime(), is(Duration.ZERO));
        log.info("scanning : {}", stats);
        
        engine.onIdle();
        Thread.sleep(10);
        assertThat("scanCount", stats.getScanCount(), is(1L));
        assertThat("launchTime", stats.getLaunchTime(), is(notNullValue()));
        assertThat("startTime", stats.getStartTime(), is(notNullValue()));
        assertThat("provisionedTime", stats.getProvisionedTime().getMillis(), greaterThan(ZERO));
        assertThat("currentRunTime", stats.getCurrentRunTime().getMillis(), greaterThan(ZERO));
        assertThat("totalRunTime", stats.getTotalRunTime().getMillis(), greaterThan(ZERO));
        assertThat("currentScanTime", stats.getCurrentScanTime(), is(Duration.ZERO));
        assertThat("cycleScanTime", stats.getCycleScanTime().getMillis(), greaterThan(ZERO));
        assertThat("totalScanTime", stats.getTotalScanTime().getMillis(), greaterThan(ZERO));
        assertThat("currentIdleTime", stats.getCurrentIdleTime().getMillis(), greaterThan(ZERO));
        assertThat("cycleIdleTime", stats.getCycleIdleTime().getMillis(), greaterThan(ZERO));
        assertThat("totalIdleTime", stats.getTotalIdleTime().getMillis(), greaterThan(ZERO));
        assertThat("totalStoppedTime", stats.getTotalStoppedTime(), is(Duration.ZERO));
        log.info("idle     : {}", stats);
        
        engine.onStop();
        Thread.sleep(10);
        assertThat("scanCount", stats.getScanCount(), is(1L));
        assertThat("launchTime", stats.getLaunchTime(), is(notNullValue()));
        assertThat("startTime", stats.getStartTime(), is(nullValue()));
        assertThat("provisionedTime", stats.getProvisionedTime().getMillis(), greaterThan(ZERO));
        assertThat("currentRunTime", stats.getCurrentRunTime().getMillis(), is(0L));
        assertThat("totalRunTime", stats.getTotalRunTime().getMillis(), greaterThan(ZERO));
        assertThat("currentScanTime", stats.getCurrentScanTime(), is(Duration.ZERO));
        assertThat("cycleScanTime", stats.getCycleScanTime(), is(Duration.ZERO));
        assertThat("totalScanTime", stats.getTotalScanTime().getMillis(), greaterThan(ZERO));
        assertThat("cycleIdleTime", stats.getCycleIdleTime(), is(Duration.ZERO));
        assertThat("totalIdleTime", stats.getTotalIdleTime().getMillis(), greaterThan(ZERO));
        assertThat("totalStoppedTime", stats.getTotalStoppedTime().getMillis(), greaterThan(ZERO));
        log.info("stopped  : {}", stats);
	}
	
	@Test
	public void testTimeToExpire() throws Exception {
		log.trace("testTimeToExpire()");
		
		final DynamicEngine engine = new DynamicEngine("name", "S", EXPIRE_DURATION);
		
		log.debug("unprovisioned: {}", engine);
		assertThat(engine.getState(), is(State.UNPROVISIONED));
		assertThat(engine.getRunTime(), is(Duration.ZERO));
		assertThat(engine.getTimeToExpire(), is(nullValue()));
		
		engine.onStart(DateTime.now());
        Thread.sleep(1000);
		engine.onIdle();
		log.info("idle: {}", engine);
		assertThat("engine is idle", engine.getState(), is(State.IDLE));
		assertThat("runtime is 1 sec", engine.getRunTime().getStandardSeconds(), is(1L));
		assertThat(engine.getTimeToExpire(), is(notNullValue()));
		assertThat(engine.getTimeToExpire().isAfterNow(), is(true));
		final DateTime firstTimeToExpire = engine.getTimeToExpire();
		final Duration firstExpireDuration = new Duration(engine.getStartTime(), firstTimeToExpire);
		assertThat(firstExpireDuration.getStandardSeconds(), is(new Long(EXPIRE_DURATION)));
		
		engine.onScan();
		log.info("active: {}", engine);
		assertThat(engine.getState(), is(State.SCANNING));
		assertThat(engine.getTimeToExpire(), is(nullValue()));
		assertThat(engine.getRunTime().getStandardSeconds(), is(1L));

		Thread.sleep(EXPIRE_DURATION * 1000);
        engine.onIdle();
		log.debug("idle: {}", engine);
		assertThat(engine.getState(), is(State.IDLE));
		final DateTime nextTimeToExpire = engine.getTimeToExpire();
		assertThat(firstTimeToExpire.isBeforeNow(), is(true));
		assertThat(nextTimeToExpire.isAfterNow(), is(true));
		assertThat(nextTimeToExpire.isAfter(firstTimeToExpire), is(true));

		engine.onStop();
		log.debug("unprovisioned: {}", engine);
		assertThat(engine.getState(), is(State.UNPROVISIONED));
		assertThat(engine.getRunTime(), is(Duration.ZERO));
		assertThat(engine.getTimeToExpire(), is(nullValue()));
	}
	
    @Test
    public void testPriorRuntime() throws Exception {
        log.trace("testPriorRuntime()");

        final DynamicEngine engine = new DynamicEngine("name", "S", EXPIRE_DURATION);
        final EngineStats stats = engine.getStats();
        log.debug("{}", stats);
        assertThat(stats, is(notNullValue()));
        
        final Duration HOUR = new Duration(1000L * 60 * 60);
        
        DateTime prior = DateTime.now().minusHours(1);
        engine.onLaunch(prior);
        engine.onStart(prior);
        Thread.sleep(1000);
        log.debug("{}", stats);
        assertThat(stats.getCurrentRunTime(), is(greaterThan(HOUR)));
        assertThat(stats.getTotalRunTime(), is(greaterThan(HOUR)));
        assertThat(stats.getProvisionedTime(), is(greaterThan(HOUR)));
    }

}
