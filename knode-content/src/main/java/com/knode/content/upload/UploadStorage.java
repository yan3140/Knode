package com.knode.content.upload;

public interface UploadStorage extends AutoCloseable {
    String put(String objectName,String contentType,byte[] bytes);
    @Override default void close() {}
}
