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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import java.util.List;
import java.util.Map;

import org.joda.time.DateTime;
import org.junit.After;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.Tag;
import com.checkmarx.engine.domain.DynamicEngine;
import com.checkmarx.engine.domain.DynamicEngine.EngineStats;
import com.checkmarx.engine.domain.DynamicEngine.State;
import com.checkmarx.engine.domain.EngineSize;
import com.checkmarx.engine.domain.Host;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

public class AwsEnginesTest extends AwsSpringTest {
	
	private static final Logger log = LoggerFactory.getLogger(AwsEnginesTest.class);

	private final String NAME = "cx-engine-test-01";
	private final List<DynamicEngine> runningEngines = Lists.newArrayList();
	
	@Autowired
	private AwsEngines awsEngines;
	
	@Before
	public void setUp() throws Exception {
		log.trace("setUp()");

		Assume.assumeTrue(super.runAwsIntegrationTests());

		assertThat(awsEngines, is(notNullValue()));
	}
	
	@After
	public void tearDown() throws Exception {
		runningEngines.forEach((engine) -> {
			awsEngines.stop(engine, true);
			log.debug("Stopped: {}", engine);
		});
		Thread.sleep(2000);
	}
	
	@Test
	public void testDateTimeParsing() {
        log.trace("testDateTimeParsing()");
	    
        final DateTime now = DateTime.now();
        
        final String text1 = AwsEngines.ISO_FORMATTER.print(now);
        final DateTime date = AwsEngines.ISO_PARSER.parseDateTime(text1);
        final String text2 = AwsEngines.ISO_FORMATTER.print(date);
        
        log.debug("date1={}; date2={}", text1, text2);
        assertThat(text1, is(text2));
	}
	
	@Test
	public void testScript() {
		log.trace("testScript()");
		
		final EngineSize size = new EngineSize("S", 1, 50000);
		final Host host = new Host(NAME, "1.2.3.4", "http://1.2.3.4", DateTime.now());
		final DynamicEngine engine = new DynamicEngine(NAME, size.getName(), 300);
		engine.setHost(host);

		awsEngines.runScript("scripts/launch.groovy", engine);
		awsEngines.runScript("scripts/terminate.js", engine);
	}

	@Test
	public void testStop() throws Exception {
		awsEngines.stop("i-0f1d10be0c02ae113");
	}

	@Test
	public void testFindEngines() {
		log.trace("testFindEngines()");
		
		final Map<String, Instance> engines = awsEngines.findEngines();
		assertThat(engines, is(notNullValue()));

		engines.forEach((name,instance) -> log.debug("{}", Ec2.print(instance)));
	}

	@Test
	public void testListEngines() {
		log.trace("testListEngines()");
		
		final List<DynamicEngine> engines = awsEngines.listEngines();
		assertThat(engines, is(notNullValue()));
		
		engines.forEach((engine) -> log.debug("{}", engine));
	}
	
	@Test
	public void testTagEngine() {
        log.trace("testTagEngine()");
        
        final List<DynamicEngine> engines = awsEngines.listEngines();
        final DynamicEngine engine = Iterables.getFirst(engines, null);
        if (engine == null) {
            Assert.fail("No engines currently provisioned.");
        }
        
        final Tag tag = new Tag("test", "test");
        Instance instance = awsEngines.tagEngine(engine, tag);
        assertThat(Ec2.getTag(instance, "test"), is("test"));
        
        tag.setValue("");
        instance = awsEngines.tagEngine(engine, tag);
        assertThat(Ec2.getTag(instance, "test"), is(""));
	}

	@Test
	@Ignore
	// this test takes about 10 mins to run.  Comment out @Ignore to run
	public void testLaunchAndStop() throws Exception {
		log.trace("testLaunchAndStop()");
		
		final EngineSize size = new EngineSize("S", 1, 50000);
		final DynamicEngine engine = new DynamicEngine(NAME, size.getName(), 300);
		final EngineStats stats = engine.getStats();
		log.debug("Pre-launch: {}", engine);
		assertThat(engine.getState(), is(State.UNPROVISIONED));
		
		awsEngines.launch(engine, size, true);
		runningEngines.add(engine);
		
        log.debug("Launched: {}", engine);
        assertThat(engine.getName(), is(NAME));
        assertThat(engine.getHost(), is(notNullValue()));
        assertThat(engine.getHost().getName(), is(NAME));
        assertThat(engine.getHost().getIp(), is(notNullValue()));
        assertThat(engine.getHost().getCxManagerUrl(), is(notNullValue()));
        Thread.sleep(1000);
        assertThat(stats.getCurrentRunTime().getStandardSeconds(), is(greaterThanOrEqualTo(1L)));
    
        engine.setScanId("123");
        engine.setEngineId("456");
        engine.onScan();
        awsEngines.onScanAssigned(engine);
        Thread.sleep(1000);
        assertThat(stats.getCurrentScanTime().getStandardSeconds(), is(1L));
        
		engine.onIdle();
        awsEngines.onScanRemoved(engine);
        Thread.sleep(1000);
        assertThat(stats.getCurrentIdleTime().getStandardSeconds(), is(1L));
		
        awsEngines.stop(engine);
        assertThat(stats.getCurrentRunTime().getStandardSeconds(), is(greaterThanOrEqualTo(3L)));
        log.debug("Stopped: {}", engine);
	}

}
