package com.knode.search.ops;

public class AdminDependencyUnavailableException extends RuntimeException {
    public AdminDependencyUnavailableException(String message) {
        super(message);
    }

    public AdminDependencyUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
