package com.knode.user.auth;

/** Transport adapters must map these statuses without invoking protected handlers. */
public class AuthenticationFailure extends RuntimeException {
    private final int status;
    public AuthenticationFailure(int status) { super(status == 503 ? "Authentication state unavailable" : status == 403 ? "Insufficient scope" : "Invalid session"); this.status = status; }
    public int status() { return status; }
}
