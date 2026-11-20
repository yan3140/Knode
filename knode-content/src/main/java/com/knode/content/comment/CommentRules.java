package com.knode.content.comment;

public final class CommentRules {
    private CommentRules() {}
    public record Valid(String type,Long articleId,long rootId,long targetId,Long suppliedTargetUser,String content) {}
    public static Valid validate(CommentInput input) {
        if (input==null) throw CommentFailure.invalid();
        String text=input.content();
        if (text==null || text.isBlank()) throw new CommentFailure(200,506);
        if (text.length()>4000 || !wellFormed(text)) throw CommentFailure.invalid();
        if (!"0".equals(input.type()) && !"1".equals(input.type())) throw CommentFailure.invalid();
        Long article=input.articleId();
        if ("0".equals(input.type())) {
            if (article==null || article<=0) throw CommentFailure.invalid();
        } else {
            if (article!=null && article!=0 && article!=1) throw CommentFailure.invalid();
            article=null;
        }
        Long root=input.rootId(),target=input.toCommentId(),supplied=input.toCommentUserId();
        if (root==null || (root!=-1 && root<=0)) throw CommentFailure.invalid();
        if (root==-1) {
            if ((target!=null && target!=-1) || (supplied!=null && supplied!=-1)) throw CommentFailure.invalid();
            return new Valid(input.type(),article,-1,-1,null,text);
        }
        if (target==null || target<=0 || (supplied!=null && supplied<=0)) throw CommentFailure.invalid();
        return new Valid(input.type(),article,root,target,supplied,text);
    }
    private static boolean wellFormed(String text) {
        for (int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            if (c==0) return false;
            if (Character.isHighSurrogate(c)) {
                if (++i>=text.length() || !Character.isLowSurrogate(text.charAt(i))) return false;
            } else if (Character.isLowSurrogate(c)) return false;
        }
        return true;
    }
    public static String preview(String text) {
        int count=text.codePointCount(0,text.length());
        return count<=500?text:text.substring(0,text.offsetByCodePoints(0,500));
    }
}
