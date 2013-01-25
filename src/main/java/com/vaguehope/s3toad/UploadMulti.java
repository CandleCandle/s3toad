package com.vaguehope.s3toad;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.AbortMultipartUploadRequest;
import com.amazonaws.services.s3.model.CompleteMultipartUploadRequest;
import com.amazonaws.services.s3.model.InitiateMultipartUploadRequest;
import com.amazonaws.services.s3.model.InitiateMultipartUploadResult;
import com.amazonaws.services.s3.model.PartETag;
import com.amazonaws.services.s3.model.UploadPartRequest;
import com.amazonaws.services.s3.model.UploadPartResult;

public class UploadMulti {

	private static final long PART_SIZE = 64L * 1024L * 1024L;
	private static final int PART_UPLOAD_RETRY_COUNT = 5;

	private final AmazonS3 s3Client;
	private final File file;
	private final String bucket;
	private final String key;
	private final ExecutorService executor;

	public UploadMulti(AmazonS3 s3Client, File file, String bucket, String key, int threads) {
		this.s3Client = s3Client;
		this.file = file;
		this.bucket = bucket;
		this.key = key;
		this.executor = Executors.newFixedThreadPool(threads);
	}

	public void dispose() {
		this.executor.shutdown();
	}

	public void run() throws Exception {
		long contentLength = this.file.length();
		System.err.println("contentLength=" + contentLength);
		System.err.println("partsize=" + PART_SIZE);

		List<Future<UploadPartResult>> uploadFutures = new ArrayList<Future<UploadPartResult>>();
		PrgTracker tracker = new PrgTracker();

		final long startTime = System.currentTimeMillis();

		InitiateMultipartUploadResult initResponse = initiateMultipartUpload(new InitiateMultipartUploadRequest(this.bucket, this.key));
		try {
			long filePosition = 0;
			for (int i = 1; filePosition < contentLength; i++) {
				long partSize = Math.min(PART_SIZE, (contentLength - filePosition));
				UploadPartRequest uploadRequest = new UploadPartRequest()
						.withBucketName(this.bucket).withKey(this.key)
						.withUploadId(initResponse.getUploadId()).withPartNumber(i)
						.withFileOffset(filePosition)
						.withFile(this.file)
						.withPartSize(partSize)
						.withProgressListener(tracker);
				uploadFutures.add(this.executor.submit(new PartUploader(this.s3Client, uploadRequest)));
				filePosition += partSize;
			}
			System.err.println("parts=" + uploadFutures.size());

			List<PartETag> partETags = new ArrayList<PartETag>();
			for (Future<UploadPartResult> future : uploadFutures) {
				partETags.add(future.get().getPartETag());
			}
			completeMultipartUpload(new CompleteMultipartUploadRequest(this.bucket, this.key, initResponse.getUploadId(), partETags));

			tracker.print();
			System.err.println("duration=" + TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis() - startTime) + "s");
		}
		catch (Exception e) {
			this.s3Client.abortMultipartUpload(new AbortMultipartUploadRequest(this.bucket, this.key, initResponse.getUploadId()));
			throw e;
		}
	}

	private InitiateMultipartUploadResult initiateMultipartUpload(InitiateMultipartUploadRequest initRequest) {
		int attempt = 0;
		while (true) {
			attempt++;
			try {
				return this.s3Client.initiateMultipartUpload(initRequest);
			}
			catch (AmazonClientException e) {
				if (attempt >= PART_UPLOAD_RETRY_COUNT) throw e;
				System.err.println("initiateMultipartUpload attempt " + attempt + " failed: '" + e.getMessage() + "'.  It will be retried.");
				sleep(3000L);
			}
		}
	}

	private void completeMultipartUpload(CompleteMultipartUploadRequest compRequest) {
		int attempt = 0;
		while (true) {
			attempt++;
			try {
				this.s3Client.completeMultipartUpload(compRequest);
				return;
			}
			catch (AmazonClientException e) {
				if (attempt >= PART_UPLOAD_RETRY_COUNT) throw e;
				System.err.println("completeMultipartUpload attempt " + attempt + " failed: '" + e.getMessage() + "'.  It will be retried.");
				sleep(3000L);
			}
		}
	}

	static void sleep(long s) {
		try {
			Thread.sleep(s);
		}
		catch (InterruptedException e) { /* Do not care. */}
	}

	private static class PartUploader implements Callable<UploadPartResult> {

		private final AmazonS3 s3Client;
		private final UploadPartRequest uploadRequest;

		public PartUploader(AmazonS3 s3Client, UploadPartRequest uploadRequest) {
			this.s3Client = s3Client;
			this.uploadRequest = uploadRequest;
		}

		@Override
		public UploadPartResult call() throws Exception {
			int attempt = 0;
			while (true) {
				attempt++;
				try {
					return uploadPart();
				}
				catch (AmazonClientException e) {
					if (attempt >= PART_UPLOAD_RETRY_COUNT) throw e;
					System.err.println("Upload of part " + this.uploadRequest.getPartNumber() +
							" with length " + this.uploadRequest.getPartSize() +
							" attempt " + attempt + " failed: '" + e.getMessage() +
							"'.  It will be retried.");
					sleep(3000L);
				}
			}
		}

		private UploadPartResult uploadPart() {
			final long startTime = System.currentTimeMillis();
			UploadPartResult res = this.s3Client.uploadPart(this.uploadRequest);
			final long seconds = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis() - startTime);
			System.err.println("part=" + this.uploadRequest.getPartNumber()
					+ " size=" + this.uploadRequest.getPartSize()
					+ " duration=" + seconds + "s");
			return res;
		}

	}

}
