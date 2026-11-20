package com.knode.content.comment;

import com.knode.content.profile.UserProfileClient;
import com.knode.content.profile.UserProfileClient.Profile;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class CommentQuery {
    private static final DateTimeFormatter DATE=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.ofHours(8));
    private final CommentQueryTransaction query;
    private final UserProfileClient users;
    public CommentQuery(CommentQueryTransaction query,UserProfileClient users) { this.query=query; this.users=users; }

    public Map<String,Object> list(String type,String articleText,String pageText,String sizeText) {
        Long article=article(type,articleText);
        int page=number(pageText,1),size=number(sizeText,10);
        if(page<1 || size<1 || size>100 || ((long)page-1)*size>10_000_000L) throw CommentFailure.invalid();
        CommentQueryTransaction.Snapshot snapshot=query.read(type,article,page,size);
        Map<String,Profile> profiles=users.lookup(snapshot.users());
        Map<Long,List<Map<String,Object>>> children=new HashMap<>();
        for(var row:snapshot.children()) children.computeIfAbsent(row.root(),ignored -> new ArrayList<>()).add(vo(row,profiles,false));
        List<Map<String,Object>> roots=new ArrayList<>();
        for(var row:snapshot.roots()) {
            Map<String,Object> item=vo(row,profiles,true);
            item.put("children",children.getOrDefault(row.id(),List.of())); roots.add(item);
        }
        return Map.of("rows",roots,"total",Long.toString(snapshot.total()),"profileLookupLimited",snapshot.profileLookupLimited());
    }
    private static Map<String,Object> vo(CommentQueryTransaction.Row row,Map<String,Profile> profiles,boolean root) {
        Profile actor=profiles.getOrDefault(row.actor(),new Profile("UNAVAILABLE",null,null));
        Profile target="-1".equals(row.toUser())?null:profiles.getOrDefault(row.toUser(),new Profile("UNAVAILABLE",null,null));
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("id",Long.toString(row.id())); out.put("articleId",row.article()==null?null:row.article().toString());
        out.put("rootId",Long.toString(row.root())); out.put("toCommentId",Long.toString(row.target()));
        out.put("toCommentUserId",row.toUser()); out.put("toCommentUserName",target==null?null:target.nickName());
        out.put("toCommentUserInfoStatus",target==null?null:target.status());
        out.put("createBy",row.actor()); out.put("createTime",DATE.format(row.created()));
        out.put("username",actor.nickName()); out.put("userInfoStatus",actor.status());
        out.put("content",row.content());
        if(!root) out.put("children",List.of());
        return out;
    }
    private static Long article(String type,String value) {
        if("1".equals(type)) {
            if(value==null || "null".equals(value) || "0".equals(value) || "1".equals(value)) return null;
            throw CommentFailure.invalid();
        }
        try {
            if(value==null || !value.matches("[1-9][0-9]{0,18}")) throw new NumberFormatException();
            long id=Long.parseLong(value); if(id<=0) throw new NumberFormatException(); return id;
        } catch(NumberFormatException failure) { throw CommentFailure.invalid(); }
    }
    private static int number(String value,int fallback) {
        if(value==null) return fallback;
        try {
            if(!value.matches("[0-9]{1,10}")) throw new NumberFormatException();
            return Integer.parseInt(value);
        } catch(NumberFormatException failure) { throw CommentFailure.invalid(); }
    }
}
