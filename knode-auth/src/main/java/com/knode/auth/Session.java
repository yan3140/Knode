package com.knode.auth;

/** No credentials or application entity may be stored in this record. */
public record Session(int schemaVersion, String sid, String userId, String scope,
                      String status, long expiresAt) {}
