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
 * 
 */
package com.checkmarx.engine.domain;

import java.util.Objects;

import org.joda.time.DateTime;

import com.google.common.base.MoreObjects;

/**
 * @author randy@checkmarx.com
 *
 */
public class Host {
	
	private final String name;
	private final String privateIp;
	private final String publicIp;
	private final String cxManagerUrl;
	private final String monitorUrl;
	private final DateTime launchTime;
	private final DateTime startTime;
	
	public Host(String name, String privateIp, String managerUrl, DateTime launchTime) {
		this(name, privateIp, null, managerUrl, null, launchTime);
	}
	
	public Host(String name, String privateIp, String publicIp, 
			String managerUrl, String monitorUrl, DateTime launchTime) {
        this(name, privateIp, publicIp, managerUrl, monitorUrl, launchTime, launchTime);
	}

    public Host(String name, String privateIp, String publicIp, 
            String managerUrl, String monitorUrl, DateTime launchTime, DateTime startTime) {
        this.name = name;
        this.privateIp = privateIp;
        this.publicIp = publicIp;
        this.cxManagerUrl = managerUrl;
        this.monitorUrl = monitorUrl;
        this.launchTime = launchTime;
        this.startTime = startTime;
    }

	public String getName() {
		return name;
	}

	public String getIp() {
		return privateIp;
	}
	
	public String getPublicIp() {
		return publicIp;
	}

	/**
	 * Returns the url for the CxManager 
	 */
	public String getCxManagerUrl() {
		return cxManagerUrl;
	}
	
	/**
	 * Returns the url for monitoring the host during spinup 
	 */
	public String getMonitorUrl() {
		return monitorUrl;
	}

	public DateTime getLaunchTime() {
		return launchTime;
	}

	public DateTime getStartTime() {
        return startTime;
    }

    @Override
	public int hashCode() {
		return Objects.hash(name, privateIp, publicIp, cxManagerUrl);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) return true;
		if (obj == null) return false;
		if (getClass() != obj.getClass()) return false;
		final Host other = (Host) obj;
		return Objects.equals(this.name, other.name)
				&& Objects.equals(this.privateIp, other.privateIp)
				&& Objects.equals(this.publicIp, other.publicIp)
				&& Objects.equals(this.cxManagerUrl, other.cxManagerUrl);
	}

	@Override
	public String toString() {
		return MoreObjects.toStringHelper(this)
				.add("name", name)
				.add("privateIp", privateIp)
				.add("publicIp", publicIp)
				.add("cxManagerUrl", cxManagerUrl)
				.add("monitorUrl", monitorUrl)
				.add("launchTime", launchTime)
                .add("startTime", startTime)
				.toString();
	}

}
