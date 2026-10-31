package com.knode.governance;

/** The dependency failed or Sentinel rejected the call; no business success is implied. */
public final class GovernanceUnavailableException extends RuntimeException {
    GovernanceUnavailableException(Throwable cause) { super("Dependency unavailable", cause); }
}
