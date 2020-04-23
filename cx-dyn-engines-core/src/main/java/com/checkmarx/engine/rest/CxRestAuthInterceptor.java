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

import com.checkmarx.engine.CxConfig;
import com.checkmarx.engine.rest.model.CxAuthResponse;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.TrustSelfSignedStrategy;
import org.apache.http.conn.ssl.TrustStrategy;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.ssl.SSLContextBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.ClientHttpRequestFactorySupplier;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.MediaType;
import org.springframework.http.client.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import java.io.IOException;
import java.time.LocalDateTime;

/**
 * {@link ClientHttpRequestInterceptor} to apply Cx authentication JWT.
 * 
 * @author ken.mcdonald@checkmarx.com
 *
 */
public class CxRestAuthInterceptor implements ClientHttpRequestInterceptor {

	private static final Logger log = LoggerFactory.getLogger(CxRestAuthInterceptor.class);
	private static final String LOGIN = "/cxrestapi/auth/identity/connect/token";
	private String token = null;
	private LocalDateTime tokenExpires = null;
	private CxConfig config;
	private RestTemplate restTemplate;

	public CxRestAuthInterceptor(CxConfig config) {
		this.config = config;
		this.restTemplate = getRestTemplate();
		log.info("ctor(): {}", this);
	}

	@Override
	public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
			throws IOException {
		log.trace("intercept()");
		final HttpHeaders headers = createAuthHeaders();
		request.getHeaders().addAll(headers);
		return execution.execute(request, body);
	}

	/**
	 * Get Auth Token
	 */
	private String getAuthToken(String username, String password, String clientId, String clientSecret, String scope) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
		MultiValueMap<String, String> map = new LinkedMultiValueMap<>();
		map.add("username", username);
		map.add("password", password);
		map.add("grant_type", "password");
		map.add("scope", scope);
		map.add("client_id", clientId);
		map.add("client_secret", clientSecret);

		HttpEntity<MultiValueMap<String, String>> requestEntity = new HttpEntity<>(map, headers);
		//get the access token
		log.info("Logging into Checkmarx {}", config.getRestUrl().concat(LOGIN));
		CxAuthResponse response = restTemplate.postForObject(config.getRestUrl().concat(LOGIN), requestEntity, CxAuthResponse.class);
		assert response != null;
		token = response.getAccessToken();
		tokenExpires = LocalDateTime.now().plusSeconds(response.getExpiresIn()-500); //expire 500 seconds early

		return token;
	}

	public String getCurrentToken(){
		return this.token;
	}

	/**
	 * Get Auth Token
	 */
	private void getAuthToken() {
		getAuthToken(
				config.getUserName(),
				config.getPassword(),
				config.getClientId(),
				config.getClientSecret(),
				config.getScope()
		);
	}

	private boolean isTokenExpired() {
		if (tokenExpires == null) {
			return true;
		}
		return LocalDateTime.now().isAfter(tokenExpires);
	}

	public HttpHeaders createAuthHeaders() {
		//get a new access token if the current one is expired.
		if (token == null || isTokenExpired()) {
			getAuthToken();
		}
		HttpHeaders httpHeaders = new HttpHeaders();
		httpHeaders.set(HttpHeaders.AUTHORIZATION, "Bearer ".concat(token));
		//httpHeaders.setContentType(MediaType.APPLICATION_JSON);
		return httpHeaders;
	}

	private RestTemplate getRestTemplate() {
		RestTemplateBuilder builder = new RestTemplateBuilder();
		return builder.requestFactory(getClientHttpRequestFactory()).build();
	}

	/**
	 * Creates a custom HttpClient that:
	 *  - disables SSL host verification
	 *  - disables cookie management
	 *  - sets a custom user agent
	 */
	protected ClientHttpRequestFactorySupplier getClientHttpRequestFactory() {

		try {
			final TrustStrategy trustStrategy = TrustSelfSignedStrategy.INSTANCE;
			final SSLContextBuilder sslContextBuilder = SSLContextBuilder.create().loadTrustMaterial(trustStrategy);
			final CloseableHttpClient httpClient = HttpClients.custom()
					.setSSLContext(sslContextBuilder.build())
					.setSSLHostnameVerifier(NoopHostnameVerifier.INSTANCE)
					.setUserAgent(config.getUserAgent() + " : v" + config.getVersion())
					.disableCookieManagement()
					.useSystemProperties()
					.build();
			final HttpComponentsClientHttpRequestFactory clientHttpRequestFactory
					= new HttpComponentsClientHttpRequestFactory(httpClient);
			clientHttpRequestFactory.setConnectTimeout(config.getTimeoutSecs() * 1000);
			clientHttpRequestFactory.setReadTimeout(config.getTimeoutSecs() * 1000);
			return new ClientHttpRequestFactorySupplier() {
				@Override
				public ClientHttpRequestFactory get() {
					return clientHttpRequestFactory;
				}
			};
		} catch (Throwable t) {
			final String msg = "Unable to initialize ClientHttpRequestFactory";
			throw new RuntimeException(msg, t);
		}
	}

}
