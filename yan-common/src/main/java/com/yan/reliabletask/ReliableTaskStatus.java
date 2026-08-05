package com.yan.reliabletask;

/** Common state names used by reliable asynchronous task adapters. */
public final class ReliableTaskStatus {
    public static final String PENDING = "PENDING";
    public static final String PROCESSING = "PROCESSING";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";

    private ReliableTaskStatus() {
    }
}
