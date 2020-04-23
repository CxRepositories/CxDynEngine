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

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.base.MoreObjects;

@Component
@ConfigurationProperties(prefix="cx")
public class CxConfig {
	
	private final String cxEngineUrlPath = "/CxSourceAnalyzerEngineWCF/CxEngineWebServices.svc";
	
	private String userName;
	@JsonIgnore
	private String password;
	private String clientId = "resource_owner_client";
	private String clientSecret;
	private String scope = "access_control_api sast_rest_api";
	private int concurrentScanLimit;
	private String cxEnginePrefix = "**";
	private boolean cxEngineUseSSL = false;
	private int expireEngineBufferMins = 1;
	private int idleMonitorSecs = 15;
	private int queueCapacity = 100;
	private int queueIntervalSecs = 20;
	private String queueingEngineName="DynamicEngine";
	private String restUrl;
    private boolean terminateOnStop;
	private int timeoutSecs = 20;
	private String userAgent = "CxDynamicEngineManager";
	private String notificationId; //Identifier for Notification implementation (ARN/URI/UID/etc)
	private String notificationSubject = "Dynamic Engines"; //Subject for notifications
	private int notificationTimer = 60; //time to wait before resending

	public String getUserName() {
		return userName;
	}

	public void setUserName(String userName) {
		this.userName = userName;
	}

	public String getPassword() {
		return password;
	}

	public void setPassword(String password) {
		this.password = password;
	}

	public String getClientId() {
		return clientId;
	}

	public void setClientId(String clientId) {
		this.clientId = clientId;
	}

	public String getClientSecret() {
		return clientSecret;
	}

	public void setClientSecret(String clientSecret) {
		this.clientSecret = clientSecret;
	}

	public String getScope() {
		return scope;
	}

	public void setScope(String scope) {
		this.scope = scope;
	}

	public int getConcurrentScanLimit() {
		return concurrentScanLimit;
	}

	public void setConcurrentScanLimit(int concurrentScanLimit) {
		this.concurrentScanLimit = concurrentScanLimit;
	}

	/**
	 * @return the prefix to prepend to the engine name registered with CxManager.
	 * 			Default value is {@code '**'}.  This can be used to distinguish
	 * 			dynamic engines from non-dynamic engines. 
	 */
	public String getCxEnginePrefix() {
		return cxEnginePrefix;
	}

	public void setCxEnginePrefix(String cxEnginePrefix) {
		this.cxEnginePrefix = cxEnginePrefix;
	}

	public boolean isCxEngineUseSSL() {
		return cxEngineUseSSL;
	}

	public void setCxEngineUseSSL(boolean cxEngineUseSSL) {
		this.cxEngineUseSSL = cxEngineUseSSL;
	}

	public int getExpireEngineBufferMins() {
		return expireEngineBufferMins;
	}

	public void setExpireEngineBufferMins(int expireEngineBufferMins) {
		this.expireEngineBufferMins = expireEngineBufferMins;
	}

	public int getIdleMonitorSecs() {
		return idleMonitorSecs;
	}

	public void setIdleMonitorSecs(int idleMonitorSecs) {
		this.idleMonitorSecs = idleMonitorSecs;
	}

	public int getQueueCapacity() {
		return queueCapacity;
	}

	public void setQueueCapacity(int queueCapacity) {
		this.queueCapacity = queueCapacity;
	}

	public int getQueueIntervalSecs() {
		return queueIntervalSecs;
	}
	
	public void setQueueIntervalSecs(int queueIntervalSecs) {
		this.queueIntervalSecs = queueIntervalSecs;
	}

	public String getQueueingEngineName() {
		return queueingEngineName;
	}

	public void setQueueingEngineName(String queueingEngineName) {
		this.queueingEngineName = queueingEngineName;
	}

	public String getRestUrl() {
		return restUrl;
	}

	public void setRestUrl(String url) {
		this.restUrl = url;
	}

    public boolean isTerminateOnStop() {
        return terminateOnStop;
    }

    public void setTerminateOnStop(boolean terminateOnStop) {
        this.terminateOnStop = terminateOnStop;
    }

	public int getTimeoutSecs() {
		return timeoutSecs;
	}

	public void setTimeoutSecs(int timeoutSecs) {
		this.timeoutSecs = timeoutSecs;
	}

    public String getUserAgent() {
		return userAgent;
	}

	public void setUserAgent(String userAgent) {
		this.userAgent = userAgent;
	}

	public String getCxEngineUrlPath() {
		return cxEngineUrlPath;
	}

	public String getVersion() {
		return getManifestVersion();
	}

	public String getNotificationId() {
		return notificationId;
	}

	public void setNotificationId(String notificationId) {
		this.notificationId = notificationId;
	}

	public String getNotificationSubject() {
		return notificationSubject;
	}

	public void setNotificationSubject(String notificationSubject) {
		this.notificationSubject = notificationSubject;
	}

	public int getNotificationTimer() {
		return notificationTimer;
	}

	public void setNotificationTimer(int notificationTimer) {
		this.notificationTimer = notificationTimer;
	}

	private String getManifestVersion() {
	    final Package objPackage = this.getClass().getPackage();
	    return objPackage.getImplementationVersion();
	}

	@Override
	public String toString() {
		return MoreObjects.toStringHelper(this)
				.add("userName", userName)
				.add("concurrentScanLimit", concurrentScanLimit)
				.add("cxEnginePrefix", cxEnginePrefix)
				.add("cxEngineUseSSL", cxEngineUseSSL)
				.add("cxEngineUrlPath", cxEngineUrlPath)
				.add("expireEngineBufferMins", expireEngineBufferMins)
				.add("idleMonitorSecs", idleMonitorSecs)
				.add("queueCapacity", queueCapacity)
				.add("queueIntervalSecs", queueIntervalSecs)
				.add("queueingEngineName", queueingEngineName)
				.add("restUrl", restUrl)
				.add("terminateOnStop", terminateOnStop)
                .add("timeoutSecs", timeoutSecs)
				.add("userAgent", userAgent)
				.add("version", getVersion())
				.add("notificationId", notificationId)
				.add("notificationSubject", notificationSubject)
				.toString();
	}

}
