package com.knode.content.metadata;

public class MetadataFailure extends RuntimeException {
    private final int code;
    public MetadataFailure(int code) { this.code=code; }
    public int code() { return code; }
}
