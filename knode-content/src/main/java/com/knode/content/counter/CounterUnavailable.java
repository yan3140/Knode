package com.knode.content.counter;

public final class CounterUnavailable extends RuntimeException {
    public CounterUnavailable() { super("Counter unavailable"); }
}
