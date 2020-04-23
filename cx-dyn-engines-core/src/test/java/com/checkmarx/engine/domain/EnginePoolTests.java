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
/**
 * 
 */
package com.checkmarx.engine.domain;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import java.util.List;

import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.test.context.junit4.SpringRunner;

import com.checkmarx.engine.domain.DynamicEngine.EngineStats;
import com.checkmarx.engine.domain.DynamicEngine.State;
import com.checkmarx.engine.domain.EnginePool.EnginePoolEntry;
import com.google.common.collect.Iterables;

/**
 * @author rjgey
 *
 */
@RunWith(SpringRunner.class)
public class EnginePoolTests {

	private static final Logger log = LoggerFactory.getLogger(EnginePoolTests.class);

	private EnginePoolConfig config;
	private EnginePool pool;
	
	public static final EngineSize SMALL = new EngineSize("S", 0, 99999);
	public static final EngineSize MEDIUM = new EngineSize("M", 100000, 499999);
	public static final EngineSize LARGE = new EngineSize("L", 500000, 999999999);
	
	@Before
	public void setUp() throws Exception {
		log.trace("setup()");
		
		config = new EnginePoolConfig();

		pool = new DefaultEnginePoolBuilder(config)
			.addEntry(new EnginePoolEntry(SMALL, 3, 2))
			.addEntry(new EnginePoolEntry(MEDIUM, 3, 1))
			.addEntry(new EnginePoolEntry(LARGE, 3, 0))
			.build();
		
		config.validate();

		log.info("{}", pool);
	}
	
	@Test
	public void testInit() {
		log.trace("testInit()");
		
		pool.logEngines();

		assertEquals(9, pool.getAllEnginesByName().size());
		
		assertEquals(3, pool.getAllEnginesBySize().size());
		assertEquals(3, pool.getAllEnginesBySize().get(SMALL.getName()).size());
		assertEquals(3, pool.getAllEnginesBySize().get(MEDIUM.getName()).size());
		assertEquals(3, pool.getAllEnginesBySize().get(LARGE.getName()).size());

		assertEquals(3, pool.getUnprovisionedEngines().size());
		assertEquals(3, pool.getUnprovisionedEngines().get(SMALL.getName()).size());
		assertEquals(3, pool.getUnprovisionedEngines().get(MEDIUM.getName()).size());
		assertEquals(3, pool.getUnprovisionedEngines().get(LARGE.getName()).size());
		
		assertEquals(3, pool.getActiveEngines().size());
		assertEquals(0, pool.getActiveEngines().get(SMALL.getName()).size());
		assertEquals(0, pool.getActiveEngines().get(MEDIUM.getName()).size());
		assertEquals(0, pool.getActiveEngines().get(LARGE.getName()).size());

		assertEquals(3, pool.getExpiringEngines().size());
		assertEquals(0, pool.getExpiringEngines().get(SMALL.getName()).size());
		assertEquals(0, pool.getExpiringEngines().get(MEDIUM.getName()).size());
		assertEquals(0, pool.getExpiringEngines().get(LARGE.getName()).size());

		assertEquals(3, pool.getIdleEngines().size());
		assertEquals(0, pool.getIdleEngines().get(SMALL.getName()).size());
		assertEquals(0, pool.getIdleEngines().get(MEDIUM.getName()).size());
		assertEquals(0, pool.getIdleEngines().get(LARGE.getName()).size());
	}
	
	@Test
	public void testAllocate() {
		log.trace("testAllocate()");
		
		DynamicEngine engine;

		engine = pool.allocateEngine(SMALL, State.IDLE);
		assertThat(engine, is(nullValue()));
		engine = pool.allocateEngine(SMALL, State.EXPIRING);
		assertThat(engine, is(nullValue()));
		engine = pool.allocateEngine(SMALL, State.SCANNING);
		assertThat(engine, is(nullValue()));

		engine = pool.allocateEngine(SMALL, State.UNPROVISIONED);
		assertThat(engine, is(notNullValue()));
        assertThat(engine.getState(), is(State.ALLOCATED));
		engine.onStart(DateTime.now());
		engine.onIdle();
		engine = pool.allocateEngine(SMALL, State.UNPROVISIONED);
		assertThat(engine, is(notNullValue()));
        assertThat(engine.getState(), is(State.ALLOCATED));
        engine.onStart(DateTime.now());
        engine.onIdle();
		engine = pool.allocateEngine(SMALL, State.UNPROVISIONED);
		assertThat(engine, is(notNullValue()));
        assertThat(engine.getState(), is(State.ALLOCATED));
        engine.onStart(DateTime.now());
        engine.onIdle();
		engine = pool.allocateEngine(SMALL, State.UNPROVISIONED);
		assertThat(engine, is(nullValue()));
	}
	
	@Test
	public void testAllocateMinIdleEngines() {
        log.trace("testAllocateMinIdleEngines()");
        
        // pool starts with 3 min engines

        // set one engine to IDLE
        DynamicEngine engine = pool.allocateEngine(SMALL, State.UNPROVISIONED);
        assertThat(engine, is(notNullValue()));
        assertThat(engine.getState(), is(State.ALLOCATED));
        engine.onStart(DateTime.now());
        engine.onIdle();
        assertThat(engine.getState(), is(State.IDLE));

        engine = pool.allocateEngine(MEDIUM, State.UNPROVISIONED);
        assertThat(engine, is(notNullValue()));
        assertThat(engine.getState(), is(State.ALLOCATED));
        engine.onStart(DateTime.now());
        engine.onScan();
        assertThat(engine.getState(), is(State.SCANNING));

        // should allocate 1 remaining min SMALL engine
        final List<DynamicEngine> engines = pool.allocateMinIdleEngines();
        assertThat(engines.size(), is(1));
        engine = engines.get(0);
        assertThat(engine.getSize(), is(SMALL.getName()));
        assertThat(engine.getState(), is(State.ALLOCATED));

        // should have one remaining SMALL IDLE engine
        engine = pool.allocateEngine(SMALL, State.IDLE);
        assertThat(engine, is(notNullValue()));
        assertThat(engine.getState(), is(State.ALLOCATED));
        engine.onScan();
        assertThat(engine.getState(), is(State.SCANNING));

        // should NOT have a MEDIUM IDLE engine
        engine = pool.allocateEngine(MEDIUM, State.IDLE);
        assertThat(engine, is(nullValue()));

        // should have no LARGE IDLE engines
        engine = pool.allocateEngine(LARGE, State.IDLE);
        assertThat(engine, is(nullValue()));
	}
	
	
	@Test
	public void testChangeState() throws InterruptedException {
		log.trace("testChangeState()");
		
		final DynamicEngine engine = Iterables.getFirst(pool.getUnprovisionedEngines().get(SMALL.getName()), null);
        engine.onStart(DateTime.now());
        EngineStats stats = engine.getStats();
		String size = engine.getSize();
		
        Thread.sleep(100);

        engine.onScan();
		assertEquals(1, pool.getActiveEngines().get(size).size());
		assertEquals(2, pool.getUnprovisionedEngines().get(size).size());
		assertEquals(State.SCANNING, engine.getState());
		
		Thread.sleep(110);
		//assertTrue(engine.getElapsedTime().getMillis() >= 100);
        assertThat("scanTime", stats.getCurrentScanTime().getMillis(), greaterThanOrEqualTo(100L));
        assertThat("runTime", stats.getCurrentRunTime().getMillis(), greaterThanOrEqualTo(200L));
        assertThat("runTime > scanTime", stats.getCurrentRunTime().getMillis(), greaterThan(stats.getCurrentScanTime().getMillis()));

		pool.logEngines();
	}

	@Test
	public void testReplaceEngine() {
		log.trace("testReplaceEngine()");
		
		final DynamicEngine e = 
				Iterables.getFirst(pool.getUnprovisionedEngines().get(SMALL.getName()), null);
		final String name = e.getName();
		
		final DynamicEngine newEngine = new DynamicEngine(name, e.getSize(), 300);
		DateTime now = DateTime.now();
		newEngine.onStart(now);
		newEngine.onIdle();
		newEngine.setHost(new Host(name, "ip", "url", now));
		assertEquals(newEngine, e);
		
		final DynamicEngine oldEngine = pool.addExistingEngine(newEngine);
		log.debug("old: {}", oldEngine);
		log.debug("new: {}", newEngine);

		assertEquals(e, oldEngine);
		assertEquals(newEngine, pool.getEngineByName(name));
		assertNotEquals(newEngine.getState(), oldEngine.getState());
		assertNotEquals(newEngine.getHost(), oldEngine.getHost());
	}

	@Test
	public void testCalcSize() {
		log.trace("testCalcSize()");
		
		assertEquals(SMALL, pool.calcEngineSize(0));
		assertEquals(SMALL, pool.calcEngineSize(1));
		assertEquals(SMALL, pool.calcEngineSize(99999));
		assertEquals(MEDIUM, pool.calcEngineSize(100000));
		assertEquals(MEDIUM, pool.calcEngineSize(499999));
		assertEquals(LARGE, pool.calcEngineSize(500000));
		assertEquals(LARGE, pool.calcEngineSize(999999999));
		assertThat(pool.calcEngineSize(100000000000L), is(nullValue()));
	}
	
}
