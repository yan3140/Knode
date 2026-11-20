package com.knode.content.comment;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown=true)
public record CommentInput(String type,Long articleId,Long rootId,Long toCommentId,Long toCommentUserId,String content) {}
