package com.knode.user.account;
public final class BusinessFailure extends RuntimeException {
    private final int code;
    public BusinessFailure(int code,String message) { super(message); this.code=code; }
    public int code() { return code; }
}
