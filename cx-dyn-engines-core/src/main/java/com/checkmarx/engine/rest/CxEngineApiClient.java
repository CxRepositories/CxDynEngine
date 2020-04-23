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

import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import com.checkmarx.engine.CxConfig;
import com.checkmarx.engine.rest.model.EngineServer;
import com.checkmarx.engine.rest.model.EngineServerResponse;
import com.checkmarx.engine.rest.model.EngineServerV86;
import com.checkmarx.engine.rest.model.Login;
import com.checkmarx.engine.rest.model.ScanRequest;
import com.checkmarx.engine.rest.model.ErrorResponse;
import com.google.common.base.MoreObjects;
import com.google.common.collect.Lists;

@Component
public class CxEngineApiClient extends BaseHttpClient implements CxEngineApi {

	private static final Logger log = LoggerFactory.getLogger(CxEngineApiClient.class);
	private static final String MESSAGE_TEMPLATE = "Error occurred during operation: %s\nResponse Code: %s";

	private static final String BASE_URL = "/cxrestapi";
	private static final String AUTH_API_URL = BASE_URL + "/auth/login";
	private static final String ENGINES_API_URL = BASE_URL + "/sast/engineServers";
	private static final String SCAN_REQUESTS_URL = BASE_URL + "/sast/scansQueue";
	private final RestTemplate sastClient;
	private final Notification notify;

	private boolean isLoggedIn;
	private String cxVersion = "Unknown";

	public CxEngineApiClient(RestTemplateBuilder builder, CxConfig config, Notification notify) {
		super(config);
		
		this.sastClient = getSastBuilder(builder);
		this.notify = notify;
		log.info("ctor(): {}", this);
	}
	
	private RestTemplate getSastBuilder(RestTemplateBuilder builder) {
		return super.getRestBuilder(builder)
				.additionalInterceptors(new CxRestAuthInterceptor(config))
				.build();
	}
	
	protected <T,R> R execute(String operation, Request<R> request, boolean retryOn401) {
		int attempt = 0;
		R result = null;
		while (attempt < 2) {
			attempt++;
			try {
				result = super.execute(operation, request);
				return result;
			} catch (HttpClientErrorException | HttpServerErrorException e) {
				if (retryOn401 && e.getRawStatusCode() == 401) {
					log.info("...unauthorized, logging in and retrying...");
					login();
				} else {
					String errorMsg = String.format(MESSAGE_TEMPLATE, operation, e.getRawStatusCode());
					notify.sendNotification(config.getNotificationSubject(), errorMsg, e);
					throw e;
				}
			}
		}
		// shouldn't reach this code
		throw new IllegalStateException("Cx rest call failed, too many API call attempts");
	}
	
	@Override
	public boolean login() {
		return login(new Login(config.getUserName(), config.getPassword()));
	}
	
	@Override
	public boolean login(Login login) {
		log.trace("login(): {}", login);

		final String url = buildUrl(AUTH_API_URL);

		final HttpEntity<Login> request = new HttpEntity<Login>(login);
		boolean success = false;
		success = execute("login", () -> {
			try {
				sastClient.postForObject(url, request, ErrorResponse.class);
				return true;
			} catch (HttpClientErrorException e) {
				if (e.getStatusCode().equals(HttpStatus.FORBIDDEN)) {
					return false;
				}
				throw e;
			}
		});
		if (success) {
			isLoggedIn = true;
			getCxVersion();
		}
		return success;
	}
	
	@Override
	public String getCxVersion() {
		if (!isLoggedIn) return "Unknown";
		
		if (!cxVersion.equals("Unknown"))
			return cxVersion;
		
		List<EngineServer> engines = getEngines();
		if (engines.size() == 0) {
			throw new RuntimeException("ERROR: unable to determine version, no engine servers registered.");
		}
		cxVersion = engines.get(0).getCxVersion();
		return cxVersion;
	}
	
	@Override
	public List<EngineServer> getEngines() {
		log.trace("getEngines()");
		
		final String url = buildEngineUrl();
		final EngineServer[] engines = execute("getEngines", () -> {
			if (CxVersion.isMinVersion86(cxVersion))
				return sastClient.getForObject(url, EngineServerV86[].class);
			else
				return sastClient.getForObject(url, EngineServer[].class);
		}, true);
		return Lists.newArrayList(engines);
	}
	
	@Override
	public EngineServer getEngine(final long id) {
		log.trace("getEngine(): id={}", id);
		
		final String url = buildEngineUrl(id);
		final EngineServer engine = execute("getEngine", () -> {
			if (CxVersion.isMinVersion86(cxVersion))
				return sastClient.getForObject(url, EngineServerV86.class);
			else
				return sastClient.getForObject(url, EngineServer.class);
		}, true);
		return engine;
	}

	@Override
	public EngineServer getEngine(final String name) {
		log.trace("getEngine(): name={}", name);

		List<EngineServer> engines =  getEngines();
		for(EngineServer e: engines){
			if(e.getName().equalsIgnoreCase(name)){
				return e;
			}
		}
		return null;
	}

	@Override
	public EngineServer registerEngine(final EngineServer engine) {
		log.trace("registerEngine() : {}", engine);
		
		final String url = buildEngineUrl();
		final EngineServerResponse response = execute("registerEngine", () -> {
			return sastClient.postForObject(url, engine.toDTO(), EngineServerResponse.class);
		}, true);
		return getEngine(response.getId());
	}
	
	@Override
	public void unregisterEngine(long id) {
		log.trace("unregisterEngine(): id={}", id);
		
		final String url = buildEngineUrl(id);
		execute("unregisterEngine", () -> {
			sastClient.delete(url);
			return true;
		}, true);
	}
	
	@Override
	public EngineServer updateEngine(EngineServer engine) {
		log.trace("updateEngine(): {}", engine);
		
		
		final long id = engine.getId();
		final String url = buildEngineUrl(id);
		execute("updateEngine", () -> {
			sastClient.put(url, engine.toDTO());
			return true;
		}, true);
		return getEngine(id);
	}
	
	@Override
	public EngineServer blockEngine(long engineId) {
		log.trace("blockEngine(): engineId={}", engineId);
		
		final EngineServer engine = getEngine(engineId);
		if (engine == null) return null;
		
		if (engine.isBlocked()) return engine;
		
		engine.setBlocked(true);
		return updateEngine(engine);
	}

	@Override
	public EngineServer blockEngine(String engineName) {
		log.trace("blockEngine(): engineName={}", engineName);

		final EngineServer engine = getEngine(engineName);
		if (engine == null) return null;

		if (engine.isBlocked()) return engine;

		engine.setBlocked(true);
		return updateEngine(engine);
	}
	@Override
	public EngineServer unblockEngine(long engineId) {
		log.trace("unblockEngine(): engineId={}", engineId);
		
		final EngineServer engine = getEngine(engineId);
		if (engine == null) return null;
		
		if (!engine.isBlocked()) return engine;
		
		engine.setBlocked(false);
		return updateEngine(engine);
	}

	@Override
	public List<ScanRequest> getScansQueue() {
		log.trace("getScansQueue()");
		
		final String url = buildUrl(SCAN_REQUESTS_URL);
		final ScanRequest[] scanRequests = execute("getScansQueue", () -> {
			return sastClient.getForObject(url, ScanRequest[].class);
		}, true);
		return Arrays.asList(scanRequests);
	}
	
	private String buildEngineUrl(long id) {
		return buildEngineUrl() + "/" + id;
	}
	
	private String buildEngineUrl() {
		return config.getRestUrl() + ENGINES_API_URL;
	}
	
	private String buildUrl(String url) {
		return config.getRestUrl() + url;
	}

	@Override
	public String toString() {
		return MoreObjects.toStringHelper(this)
				.add("config", config)
				.add("cxVersion", getCxVersion())
				.toString();
	}

}
