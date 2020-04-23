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
package com.checkmarx.engine.utils;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ManifestUtilsTests {
	
	private static final Logger log = LoggerFactory.getLogger(ManifestUtilsTests.class);

	@Test
	public void testGetVersion() {

		final String stringVersion = ManifestUtils.getVersion(String.class);
		log.debug("version={}", stringVersion);
		assertThat(stringVersion, is(not(emptyOrNullString())));
		
		final String version1 = ManifestUtils.getVersion(ManifestUtils.class);
		log.debug("version={}", version1);
		assertThat(version1, is(nullValue()));
		
		final String DEFAULT_VERSION = "1.0";
	
		final String version2 = ManifestUtils.getVersion(ManifestUtils.class, DEFAULT_VERSION);
		log.debug("version={}", version2);
		assertThat(version2, is(DEFAULT_VERSION));

		final String version3 = ManifestUtils.getVersion(this, DEFAULT_VERSION);
		log.debug("version={}", version3);
		assertThat(version3, is(DEFAULT_VERSION));
	}
}
