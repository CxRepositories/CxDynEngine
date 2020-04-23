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
package com.checkmarx.engine.servers;

import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import com.checkmarx.engine.domain.EnginePool;

import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.checkmarx.engine.CxConfig;
import com.checkmarx.engine.rest.CxEngineApi;
import com.checkmarx.engine.rest.model.ScanRequest;
import com.checkmarx.engine.utils.ScanUtils;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

public class ScanQueueMonitor implements Runnable {

	private static final Logger log = LoggerFactory.getLogger(ScanQueueMonitor.class);

	private final BlockingQueue<ScanRequest> scanQueued;
	//private final BlockingQueue<ScanRequest> scanWorking;
	private final BlockingQueue<ScanRequest> scanFinished;
	private final EnginePool enginePool;


	/**
	 * Map of active scan requests by Scan.Id
	 */
	private final Map<Long,ScanRequest> activeScanMap = Maps.newHashMap();
	private final List<Long> workingScans = Lists.newArrayList();
	private final CxEngineApi cxClient;
	private final int concurrentScanLimit;
	private final AtomicInteger concurrentScans = new AtomicInteger(0);

	public ScanQueueMonitor(
			BlockingQueue<ScanRequest> scanQueued,
			//BlockingQueue<ScanRequest> scanWorking,
			BlockingQueue<ScanRequest> scanFinished,
			EnginePool enginePool,
			CxEngineApi cxClient,
			CxConfig config) {
		log.info("ctor(): {}", config);

		this.scanQueued = scanQueued;
		//this.scanWorking = scanWorking;
		this.scanFinished = scanFinished;
		this.enginePool = enginePool;
		this.cxClient = cxClient;
		this.concurrentScanLimit = config.getConcurrentScanLimit();
	}

	@Override
	public void run() {
		log.trace("run()");

		try {
			final List<ScanRequest> queue = cxClient.getScansQueue();
			log.debug("action=getScansQueue; scanCount={}", queue.size());

			// order queue before processing to avoid ScansManager queue bug
			ScanUtils.sortQueue(queue);
			queue.forEach((scan) -> processScan(scan));

			//TODO: check for missing scans and treat as finished
		} catch (Throwable t) {
			log.error("Error occurred while polling scan queue, cause={}; message={}",
					t, t.getMessage(), t);
			//TODO: determine if unexpected error should terminate monitor; for now swallow
		}

	}
	
	public void onPreExistingScan(ScanRequest scan) {
        log.debug("onPreExistingScan(): {}", scan);
        concurrentScans.incrementAndGet();
        activeScanMap.put(scan.getId(), scan);
	}
	
	public void onLaunchFailed(ScanRequest scan) {
        log.debug("onPreExistingScan(): {}", scan);
        
        final long scanId = scan.getId();
        if (activeScanMap.containsKey(scanId)) {
            activeScanMap.remove(scanId);
        }
	}

	private void processScan(ScanRequest scan) {
		log.debug("processScan(): {}", scan.toString(true));

		final long scanId = scan.getId();
		//if the scan loc is zero, it is not ready to determine if applicable to Dynamic Engines
		//if the calcEngineSize ends up with null, there is no applicable engine, therefore Dynamic Engines ignores
		//TODO replace this block when static engines are managed by Dynamic Engines
		if(enginePool.calcEngineSize(scan.getLoc()) == null && scan.getLoc() >= 0){
			log.info("No engine pool for scan size, ignoring; scanId={}, loc={}", scan.getId(), scan.getLoc());
			return;
		}
		switch (scan.getStatus()) {
			case Queued :
				onQueued(scanId, scan);
				break;
			case Scanning :
				onScanning(scanId, scan);
				break;
			case Canceled :
			case Deleted :
			case Failed :
			case Finished :
				onCompleted(scanId, scan);
				break;
			default:
				onOther(scan);
				break;
		}
	}

	private void onQueued(final long scanId, ScanRequest scan) {
		log.trace("onQueued(): {}", scan);

		// skip if we've already processed scan
		if (activeScanMap.containsKey(scanId)) {
			return;
		}

		// skip if at concurrent scan limit
		if (concurrentScans.get() >= concurrentScanLimit) {
			log.debug("At concurrent scan limit, defering scan...");
			return;
		}

		log.debug("scan queued, adding to scanQueued queue; id={}", scanId);
		final int count = concurrentScans.get();
		scanQueued.add(scan);
		activeScanMap.put(scanId, scan);
		log.info("Scan queued: {}; concurrentCount={}; concurrentLimit={}",
				scan, count, concurrentScanLimit);
	}

	private void onScanning(final long scanId, ScanRequest scan) {
		log.trace("onScanning(): {}", scan);

		// only process working scans once, so we add to workingScans after processing
		if (activeScanMap.containsKey(scanId) && !workingScans.contains(scanId)) {
            //FIXME: move block engine to EngineManager by posting to a queue
            //scanWorking.add(scan);
            final long engineId = scan.getEngineId();
            final int count = concurrentScans.incrementAndGet();
            log.info("Scan is working, blocking engine; scanId={}; engineId={}; concurrentCount={}; concurrentLimit={}", 
                    scanId, engineId, count, concurrentScanLimit);
            cxClient.blockEngine(engineId);

            // update active scan
			activeScanMap.put(scanId, scan);
			workingScans.add(scanId);
		}
	}

	private void onCompleted(final long scanId, ScanRequest scan) {
		log.trace("onCompleted(): {}", scan);

        // FIXME-rjg: if EngineManager.ScanFinisher fails to unregister engine, 
		//   it will remain registered and blocked causing issues downstream
		if (activeScanMap.remove(scanId) == null ) {
			return;
		}

		final int count = concurrentScans.decrementAndGet();

		log.debug("Scan complete, adding to scanFinished queue; id={}", scanId);
		workingScans.remove(scanId);
		scanFinished.add(scan);
		log.info("Scan finished: scanTime={}s; {}; concurrentScans={}", 
		        calcScanTime(scan), scan, count);
	}

	private long calcScanTime(ScanRequest scan) {
	    final Duration duration = new Duration(scan.getEngineStartedOn(), DateTime.now());
        return duration.getStandardSeconds();
    }

    private void onOther(ScanRequest scan) {
		log.trace("onOther(): {}", scan);
		// do nothing
	}

}