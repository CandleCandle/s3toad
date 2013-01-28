package com.vaguehope.s3toad;

import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;

import com.amazonaws.services.s3.model.ProgressEvent;
import com.amazonaws.services.s3.model.ProgressListener;

class PrgTracker implements ProgressListener {

	private static final long PROGRESS_INTERVAL_MILLES = 5000L;

	private final Logger log;
	private final AtomicLong total = new AtomicLong(0);
	private final AtomicLong lastUpdate = new AtomicLong(0);

	public PrgTracker(Logger log) {
		this.log = log;
	}

	@Override
	public void progressChanged(ProgressEvent progressEvent) {
		this.total.addAndGet(progressEvent.getBytesTransfered());
		if (shouldPrint()) {
			synchronized (this.lastUpdate) {
				if (shouldPrint()) {
					print();
					this.lastUpdate.set(System.currentTimeMillis());
				}
			}
		}
	}

	private boolean shouldPrint() {
		return System.currentTimeMillis() - this.lastUpdate.get() > PROGRESS_INTERVAL_MILLES;
	}

	public void print() {
		this.log.info("transfered={}", this.total.get());
	}

}