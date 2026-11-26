package com.knode.search.consumer.persistence;

public class CutoverPausedException extends RuntimeException {
    private final String aliasName;
    private final IndexGenerationState.CutoverState cutoverState;

    public CutoverPausedException(String aliasName, IndexGenerationState.CutoverState cutoverState) {
        super("Search index cutover gate active for alias " + aliasName + " in state " + cutoverState);
        this.aliasName = aliasName;
        this.cutoverState = cutoverState;
    }

    public String aliasName() {
        return aliasName;
    }

    public IndexGenerationState.CutoverState cutoverState() {
        return cutoverState;
    }
}
