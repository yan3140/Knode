package com.knode.content.relay;

public final class OpsFailure extends RuntimeException {
    private final int status;
    public OpsFailure(int status) { this.status=status; }
    public int status() { return status; }
}
