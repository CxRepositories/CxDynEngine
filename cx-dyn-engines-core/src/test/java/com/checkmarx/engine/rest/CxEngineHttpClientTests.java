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
 package com.checkmarx.engine.rest;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.TestPropertySource;

import com.checkmarx.engine.CoreSpringTest;

@TestPropertySource("/application-test.properties")
public class CxEngineHttpClientTests extends CoreSpringTest {
	
	private static final Logger log = LoggerFactory.getLogger(CxEngineHttpClientTests.class);

	@Autowired
	private CxEngineHttpClient client;
	
	@Value("${cx.test-engine-ip}")
	private String engineIp;
	
	@Before
	public void setUp() throws Exception {
		
		Assume.assumeTrue(super.runCxIntegrationTests());
		
		assertThat(client, notNullValue());
		assertThat(engineIp, is(not(emptyOrNullString())));
		log.debug("engineIp={}", engineIp);

	}
	
	@Test
	public void testPingEngine() {
		log.trace("testPingEngine()");

		assertThat(client.pingEngine(engineIp), is(true));
	}
	
}
