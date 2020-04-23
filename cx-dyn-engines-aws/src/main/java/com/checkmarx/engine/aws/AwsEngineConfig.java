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

import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import com.checkmarx.engine.domain.EngineSize;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.google.common.base.MoreObjects;
import com.google.common.collect.Maps;

@Profile("aws")
@Configuration
@ConfigurationProperties(prefix="cx-aws-engine")
@JsonIgnoreProperties("$$beanFactory")
public class AwsEngineConfig {

	private boolean assignPublicIP;
	private int cxEngineTimeoutSec=300;
	private String cxVersion;
	private String iamProfile;
	private String imageId;
	private String keyName;
	private int launchTimeoutSec=60;
	private int monitorPollingIntervalSecs = 10;
	private String securityGroup;
	private int stopWaitTimeSecs = 30;
	private String subnetId;
	private boolean terminateOnStop;
	private boolean usePublicUrlForCx = false;
	private boolean usePublicUrlForMonitor = false;
	private String ssmAutomationDocument = "AWS-StopEC2Instance";

	private String scriptOnLaunch;
    private String scriptOnStop;
	private String scriptOnTerminate;

	/**
	 * Maps EngineSize to EC2 instanceType; 
	 * 	key=size (M), 
	 * 	value=ec2 instance type (m4.large)
	 */
	private final Map<String, String> engineSizeMap = Maps.newHashMap();
	
	/**
	 * Maps custom EC2 tags to values
	 */
	private final Map<String,String> tagMap = Maps.newHashMap();

	public boolean isAssignPublicIP() {
		return assignPublicIP;
	}

	public void setAssignPublicIP(boolean assignPublicIP) {
		this.assignPublicIP = assignPublicIP;
	}

	/**
	 * Timeout for CxEngine response after EC2 instance enters Running state
	 */
	public int getCxEngineTimeoutSec() {
		return cxEngineTimeoutSec;
	}

	public void setCxEngineTimeoutSec(int cxEngineTimeoutSec) {
		this.cxEngineTimeoutSec = cxEngineTimeoutSec;
	}

	public String getCxVersion() {
		return cxVersion;
	}

	public void setCxVersion(String cxVersion) {
		this.cxVersion = cxVersion;
	}

	public String getIamProfile() {
		return iamProfile;
	}

	public void setIamProfile(String iamProfile) {
		this.iamProfile = iamProfile;
	}

	public String getImageId() {
		return imageId;
	}

	public void setImageId(String imageId) {
		this.imageId = imageId;
	}

	public String getKeyName() {
		return keyName;
	}

	public void setKeyName(String keyName) {
		this.keyName = keyName;
	}

	/**
	 * Polling interval for monitoring instance/engine launch/startup 
	 */
	public int getMonitorPollingIntervalSecs() {
		return monitorPollingIntervalSecs;
	}

	public void setMonitorPollingIntervalSecs(int monitorPollingIntervalSecs) {
		this.monitorPollingIntervalSecs = monitorPollingIntervalSecs;
	}

	public String getSecurityGroup() {
		return securityGroup;
	}

	public void setSecurityGroup(String securityGroup) {
		this.securityGroup = securityGroup;
	}

    /**
	 * Timeout to wait for Running state after EC2 instance launch
	 */
	public int getLaunchTimeoutSec() {
		return launchTimeoutSec;
	}

	public void setLaunchTimeoutSec(int launchTimeoutSec) {
		this.launchTimeoutSec = launchTimeoutSec;
	}

	/**
	 * Time to wait for stopping engine before attempting to start  
	 */
    public int getStopWaitTimeSecs() {
        return stopWaitTimeSecs;
    }

    public void setStopWaitTimeSecs(int stopWaitTimeSecs) {
        this.stopWaitTimeSecs = stopWaitTimeSecs;
    }

	public String getSubnetId() {
		return subnetId;
	}

	public void setSubnetId(String subnetId) {
		this.subnetId = subnetId;
	}

    @Deprecated
	public boolean isTerminateOnStop() {
		return terminateOnStop;
	}

	@Deprecated
	public void setTerminateOnStop(boolean terminateOnStop) {
		this.terminateOnStop = terminateOnStop;
	}

	public boolean isUsePublicUrlForCx() {
		return usePublicUrlForCx;
	}

	public void setUsePublicUrlForCx(boolean usePublicUrlForCx) {
		this.usePublicUrlForCx = usePublicUrlForCx;
	}

	public boolean isUsePublicUrlForMonitor() {
		return usePublicUrlForMonitor;
	}

	public void setUsePublicUrlForMonitor(boolean usePublicUrlForMonitor) {
		this.usePublicUrlForMonitor = usePublicUrlForMonitor;
	}

	/**
	 * @return script file to execute on engine launch
	 */
	public String getScriptOnLaunch() {
		return scriptOnLaunch;
	}

	public void setScriptOnLaunch(String scriptOnLaunch) {
		this.scriptOnLaunch = scriptOnLaunch;
	}

    /**
     * @return script file to execute on engine stop
     */
	public String getScriptOnStop() {
        return scriptOnStop;
    }

    public void setScriptOnStop(String scriptOnStop) {
        this.scriptOnStop = scriptOnStop;
    }

    /**
	 * @return script file to execute on engine termination
	 */
	public String getScriptOnTerminate() {
		return scriptOnTerminate;
	}

	public void setScriptOnTerminate(String scriptOnTerminate) {
		this.scriptOnTerminate = scriptOnTerminate;
	}

	/**
	 * Map of EngineSize to EC2 instanceType; 
	 * key=size (name), 
	 * value=ec2 instance type (e.g. m4.large)
	 * @see EngineSize
	 */
	public Map<String, String> getEngineSizeMap() {
		return engineSizeMap;
	}

	/**
	 * Map of custom EC2 tags
	 *  	key=tag name
	 *  	value=tag value 
	 */
	public Map<String, String> getTagMap() {
		return tagMap;
	}

	public String printEngineSizeMap() {
		final StringBuilder sb = new StringBuilder();
		engineSizeMap.forEach((size,instanceType) ->
			sb.append(String.format("%s->%s, ", size,instanceType)) );
		return sb.toString().replaceAll(", $", ""); 
	}

	private String printEngineTagMap() {
		final StringBuilder sb = new StringBuilder();
		tagMap.forEach((name,value) ->
			sb.append(String.format("%s->%s, ", name,value)) );
		return sb.toString().replaceAll(", $", ""); 
	}

	public String getSsmAutomationDocument() {
		return ssmAutomationDocument;
	}

	public void setSsmAutomationDocument(String ssmAutomationDocument) {
		this.ssmAutomationDocument = ssmAutomationDocument;
	}

	public String toString() {
		return MoreObjects.toStringHelper(this)
				.add("assignPublicIP", assignPublicIP)
				.add("cxEngineTimeoutSec", cxEngineTimeoutSec)
				.add("cxVersion", cxVersion)
				.add("iamProfile", iamProfile)
				.add("imageId", imageId)
				.add("keyName", keyName)
				.add("monitorPollingIntervalSecs", monitorPollingIntervalSecs)
				.add("securityGroup", securityGroup)
				.add("launchTimeoutSec", launchTimeoutSec)
				.add("scriptOnLaunch", scriptOnLaunch)
                .add("scriptOnStop", scriptOnStop)
				.add("scriptOnTerminate", scriptOnTerminate)
				.add("stopWaitTimeSecs", stopWaitTimeSecs)
				.add("terminateOnStop", terminateOnStop)
				.add("usePublicUrlForCx", usePublicUrlForCx)
				.add("usePublicUrlForMonitor", usePublicUrlForMonitor)
				.add("engineSizeMap", "[" + printEngineSizeMap() +"]")
				.add("engineTagMap", "[" + printEngineTagMap() +"]")
				.add("ssmAutomationDocumetn", ssmAutomationDocument)
				.toString();
	}

}
