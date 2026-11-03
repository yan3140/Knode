package com.knode.user.account;

/** Shared database time makes fixed windows consistent across User instances. */
@FunctionalInterface
public interface LoginLimitTime {
    long millis();
}
