package com.knode.content.article;

public final class ArticleFailure extends RuntimeException {
    private final int status;
    public ArticleFailure(int status, String message) { super(message); this.status = status; }
    public int status() { return status; }
}
