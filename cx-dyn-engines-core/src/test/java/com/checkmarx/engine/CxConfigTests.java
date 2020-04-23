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
package com.checkmarx.engine;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import org.jasypt.util.text.BasicTextEncryptor;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

public class CxConfigTests extends CoreSpringTest {
	
	private static final Logger log = LoggerFactory.getLogger(CxConfigTests.class);
	
	private static final String USERNAME = "admin@cx";
	
	@Autowired
	private CxConfig config;
	
	@Before
	public void setUp() throws Exception {
		assertThat(config, notNullValue());
	}

	@Test
	public void test() {
		log.trace("test()");
		
		log.info("{}", config);
		
		assertThat(config.getCxEngineUrlPath(), is(not(emptyOrNullString())));
		assertThat(config.getPassword(), is(not(emptyOrNullString())));
		assertThat(config.getQueueCapacity(), is(greaterThan(0)));
		assertThat(config.getQueueIntervalSecs(), is(greaterThan(0)));
		assertThat(config.getRestUrl(), is(not(emptyOrNullString())));
		assertThat(config.getUserName(), is(not(emptyOrNullString())));
		assertThat(config.getTimeoutSecs(), is(greaterThan(0)));
		
		//test encrypted property
		//  requires jasypt.encryptor.password=CxR0cks!! env var or system property
		//  requires cx.username in application.properties is USERNAME (const defined above)
		assertThat(config.getUserName(), is(USERNAME));
	}
	
	@Test
	public void testJasypt() {
		final String ENCRYPTION_KEY = "CxR0cks!!";
		
		BasicTextEncryptor encryptor = new BasicTextEncryptor();
		encryptor.setPassword(ENCRYPTION_KEY);
		
		final String encryptedUsername = encryptor.encrypt(USERNAME);
		log.debug("Encrypted user: {}", encryptedUsername);
		final String encryptedPass = encryptor.encrypt("Im@hom3y!!");
		log.debug("Encrypted pass: {}", encryptedPass);
		
		final String decryptedUsername = encryptor.decrypt(encryptedUsername);
		log.debug("Decrypted: {}", decryptedUsername);
		
		assertThat(decryptedUsername, is(USERNAME));
	}
	
}
