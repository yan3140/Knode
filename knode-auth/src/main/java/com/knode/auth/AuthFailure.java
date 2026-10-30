package com.knode.auth;

public final class AuthFailure extends RuntimeException {
    private final int status;
    public AuthFailure(int status) {
        super(status == 503 ? "Authentication state unavailable" : status == 403 ? "Insufficient scope" : "Invalid session");
        if (status != 401 && status != 403 && status != 503) throw new IllegalArgumentException("status");
        this.status = status;
    }
    public int status() { return status; }
}
