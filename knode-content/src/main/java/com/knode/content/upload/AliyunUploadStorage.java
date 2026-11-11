package com.knode.content.upload;

import com.aliyun.oss.OSS;
import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.PutObjectRequest;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.*;

public final class AliyunUploadStorage implements UploadStorage {
    private final OSS client;
    private final String bucket;
    private final ThreadPoolExecutor writes;
    public AliyunUploadStorage(OSS client,String bucket) {
        this.client=client; this.bucket=bucket;
        this.writes=new ThreadPoolExecutor(10,10,30,TimeUnit.SECONDS,new SynchronousQueue<>(),task -> {
            Thread thread=new Thread(task,"content-oss-write"); thread.setDaemon(true); return thread;
        },new ThreadPoolExecutor.AbortPolicy());
        this.writes.allowCoreThreadTimeOut(true);
    }
    @Override public String put(String objectName,String contentType,byte[] bytes) {
        Future<String> pending=null;
        try {
            pending=writes.submit(() -> putOnce(objectName,contentType,bytes));
            return pending.get(5,TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new UploadFailure(503,500);
        } catch (ExecutionException | TimeoutException | RejectedExecutionException unavailable) {
            throw new UploadFailure(503,500);
        } finally {
            if (pending!=null && !pending.isDone()) pending.cancel(true);
        }
    }
    private String putOnce(String objectName,String contentType,byte[] bytes) throws Exception {
        try (var stream=new ByteArrayInputStream(bytes)) {
            ObjectMetadata metadata=new ObjectMetadata();
            metadata.setContentType(contentType);
            metadata.setContentLength(bytes.length);
            metadata.setContentDisposition("inline");
            PutObjectRequest put=new PutObjectRequest(bucket,objectName,stream,metadata);
            put.setLogEnabled(false);
            client.putObject(put);
            GeneratePresignedUrlRequest signed=new GeneratePresignedUrlRequest(bucket,objectName);
            signed.setMethod(HttpMethod.GET);
            signed.setExpiration(Date.from(Instant.now().plusSeconds(3600)));
            return client.generatePresignedUrl(signed).toExternalForm();
        }
    }
    @Override public void close() { writes.shutdownNow(); client.shutdown(); }
}
