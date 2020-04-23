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
 * Copyright (c) 2017 Checkmarx
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
 */
package com.checkmarx.engine.rest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import com.checkmarx.engine.CxConfig;
import com.google.common.base.Strings;

/**
 * {@code CxEngineClient} based on Spring RestTemplate and HttpClient.
 * 
 * @author randy@checkmarx.com
 *
 */
@Component
public class CxEngineHttpClient extends BaseHttpClient implements CxEngineClient {
	
	private static final Logger log = LoggerFactory.getLogger(CxEngineHttpClient.class);

	private final RestTemplate engineClient;

	public CxEngineHttpClient(RestTemplateBuilder builder, CxConfig config) {
		super(config);
		
		this.engineClient = super.getRestBuilder(builder).build();

		log.info("ctor(): {}", this);
	}
	
	@Override
	public boolean pingEngine(String host) {
		log.trace("pingEngine(): host={}", host);
		
		final String url = buildEngineServiceUrl(host);
		
		try {
			final String payload = execute("pingEngine; host=" + host , () -> {
				final ResponseEntity<String> response = engineClient.getForEntity(url, String.class);
				return response.getBody();
			});
			log.trace("pingEngine: host={}; response={}", host, payload.substring(0, 120));
			
			return true;
			
		} catch (RestClientException ex) {
			log.trace("pingEngine failed: host={}; message={}", host, ex.getMessage());
			return false;
		}
	}
	
	private String getProtocol() {
		return config.isCxEngineUseSSL() ? "https://" : "http://";
	}
	
	@Override
	public String buildEngineServerUrl(String host) {
		return getProtocol() + host;
	}

	@Override
	public String buildEngineServiceUrl(String host) {
		if (Strings.isNullOrEmpty(host)) return null;
		final String url =  buildEngineServerUrl(host) + config.getCxEngineUrlPath();
		return url;
	}

}
