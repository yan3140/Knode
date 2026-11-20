package com.knode.content.comment;

public final class CommentFailure extends RuntimeException {
    private final int status,code;
    public CommentFailure(int status,int code) { this.status=status; this.code=code; }
    public int status() { return status; }
    public int code() { return code; }
    static CommentFailure invalid() { return new CommentFailure(200,515); }
    static CommentFailure missingArticle() { return new CommentFailure(200,513); }
    static CommentFailure unavailable() { return new CommentFailure(503,500); }
}
