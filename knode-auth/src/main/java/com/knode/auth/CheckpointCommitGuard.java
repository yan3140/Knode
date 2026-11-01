package com.knode.auth;

/**
 * Receiver-owned transaction boundary. Implementations must atomically check current run/epoch/worker,
 * bind the capability jti and operation id to a SHA-256 result digest, and commit the safe checkpoint.
 * An identical retry may return ALREADY_APPLIED; a changed digest or stale epoch must be rejected.
 * A returned authorization alone never proves that this receiver transaction committed.
 */
public interface CheckpointCommitGuard {
    enum Outcome {APPLIED,ALREADY_APPLIED}
    Outcome commit(DelegationTokenVerifier.AuthorizedCheckpoint capability,String sha256ResultDigest);
}
