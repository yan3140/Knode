package com.knode.content.facade;

public final class FacadeFailure extends RuntimeException {
    private final int status;
    private final int code;
    private final String publicMessage;

    public FacadeFailure(int status, int code, String publicMessage) {
        super(publicMessage);
        this.status = status;
        this.code = code;
        this.publicMessage = publicMessage;
    }

    public int status() { return status; }
    public int code() { return code; }
    public String publicMessage() { return publicMessage; }
}
