package com.knode.content.upload;

public final class UploadFailure extends RuntimeException {
    private final int status;
    private final int code;
    public UploadFailure(int status,int code) { this.status=status; this.code=code; }
    public int status() { return status; }
    public int code() { return code; }
}
