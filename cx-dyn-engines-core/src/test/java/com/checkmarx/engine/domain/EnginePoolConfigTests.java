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

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.number.OrderingComparison.greaterThan;

import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import com.checkmarx.engine.CoreSpringTest;
import com.checkmarx.engine.domain.EnginePool.EnginePoolEntry;

public class EnginePoolConfigTests extends CoreSpringTest {
	
	private static final Logger log = LoggerFactory.getLogger(EnginePoolConfigTests.class);

	@Autowired
	private EnginePoolConfig config;
	
	@Before
	public void setUp() throws Exception {
		assertThat(config, notNullValue());
	}

	@Test
	public void test() {
		log.trace("test()");
		
		log.info("{}", config);
		
		config.validate();
		
		assertThat(config.getEnginePrefix(), is("cx-engine"));
		assertThat(config.getEngineExpireIntervalSecs(), is(greaterThan(1)));
		
		final List<EnginePoolEntry> pool = config.getPool();
		assertThat(pool, notNullValue());
		assertThat(pool.isEmpty(), is(false));
		
	}

}
