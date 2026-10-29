package com.yan.admin.agent;

public class AgentGatewayException extends RuntimeException {

    private final int statusCode;

    public AgentGatewayException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public AgentGatewayException(int statusCode, String message, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
