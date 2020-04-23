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

import java.util.Map;

import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

public class AwsEngineConfigTests extends AwsSpringTest {
	
	private static final Logger log = LoggerFactory.getLogger(AwsEngineConfigTests.class);

	@Autowired
	private AwsEngineConfig config;
	
	@Before
	public void setUp() throws Exception {
		assertThat(config, notNullValue());
	}

	@Test
	public void test() {
		log.trace("test()");
		
		log.info("{}", config);
		
		assertThat(config.getIamProfile(), is(not(emptyOrNullString())));
		assertThat(config.getImageId(), is(not(emptyOrNullString())));
		assertThat(config.getKeyName(), is(not(emptyOrNullString())));
		assertThat(config.getSecurityGroup(), is(not(emptyOrNullString())));
		assertThat(config.getSubnetId(), is(not(emptyOrNullString())));

		final Map<String, String> sizeMap = config.getEngineSizeMap();
		assertThat(sizeMap, notNullValue());
		assertThat(sizeMap.values(), hasSize(greaterThan(0)));
		
		final Map<String, String> tagMap = config.getTagMap();
		assertThat(tagMap, notNullValue());
		assertThat(tagMap.values(), hasSize(greaterThan(0)));
		
	}

}
