package com.knode.content.counter;

import java.util.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

public final class RedisCounterStore implements CounterStore {
    private static final RedisScript<String> HIT=RedisScript.of(new ClassPathResource("counter/increment.lua"),String.class);
    private static final RedisScript<String> CLAIM=RedisScript.of(new ClassPathResource("counter/claim.lua"),String.class);
    private static final RedisScript<String> FINISH=RedisScript.of(new ClassPathResource("counter/finish.lua"),String.class);
    private final StringRedisTemplate redis;
    private final LettuceConnectionFactory factory;
    private final String prefix,counts,dirty;
    RedisCounterStore(StringRedisTemplate redis,LettuceConnectionFactory factory,String prefix) {
        this.redis=redis; this.factory=factory; this.prefix=prefix;
        this.counts=prefix+"counts"; this.dirty=prefix+"dirty";
    }
    @Override public boolean enabled() { return true; }
    @Override public int hit(long id,long baseline,boolean visible,Identity identity) {
        if(id<=0 || baseline<0 || identity==null || !hash(identity.ip()) || !hash(identity.article()) || !hash(identity.visitor()))
            throw new CounterUnavailable();
        String result=redis.execute(HIT,List.of(counts,dirty,prefix+"rate:ip:"+identity.ip(),
                prefix+"rate:article:"+identity.article(),prefix+"visitor:"+identity.visitor()),
                Long.toString(id),Long.toString(baseline),visible?"1":"0");
        if ("OK".equals(result) || "DUP".equals(result) || "HIDDEN".equals(result)) return 0;
        if (result!=null && result.matches("L:[1-9][0-9]{0,2}")) return Integer.parseInt(result.substring(2));
        throw new CounterUnavailable();
    }
    @Override public Map<Long,Long> read(Collection<Long> articleIds) {
        if(articleIds==null || articleIds.size()>100) throw new CounterUnavailable();
        List<Long> ids=new ArrayList<>(new LinkedHashSet<>(articleIds));
        if(ids.isEmpty()) return Map.of();
        if(ids.stream().anyMatch(id -> id==null || id<=0)) throw new CounterUnavailable();
        List<String> fields=ids.stream().map(String::valueOf).toList();
        List<String> values=redis.<String,String>opsForHash().multiGet(counts,fields);
        if(values==null || values.size()!=ids.size()) throw new CounterUnavailable();
        Map<Long,Long> out=new HashMap<>();
        for(int i=0;i<ids.size();i++) {
            String value=values.get(i);
            if(value!=null && value.matches("0|[1-9][0-9]{0,18}")) {
                try { long count=Long.parseLong(value); if(count>=0) out.put(ids.get(i),count); }
                catch(NumberFormatException ignored) { }
            }
        }
        return out;
    }
    @Override public Claim claim() {
        String result=redis.execute(CLAIM,List.of(counts,dirty),"1");
        if(result==null || result.equals("ERR")) throw new CounterUnavailable();
        String[] parts=result.split(";",-1);
        if(parts.length<1 || !parts[0].matches("[0-1]")) throw new CounterUnavailable();
        int invalid=Integer.parseInt(parts[0]); List<Snapshot> rows=new ArrayList<>();
        for(int i=1;i<parts.length;i++) {
            if(parts[i].isEmpty()) continue;
            String[] fields=parts[i].split(",",-1);
            if(fields.length!=3 || !fields[0].matches("[1-9][0-9]{0,18}")
                    || !fields[1].matches("0|[1-9][0-9]{0,18}") || !fields[2].matches("[0-9]{1,15}"))
                throw new CounterUnavailable();
            try { rows.add(new Snapshot(Long.parseLong(fields[0]),Long.parseLong(fields[1]),fields[2])); }
            catch(NumberFormatException failure) { throw new CounterUnavailable(); }
        }
        if(rows.size()+invalid>1) throw new CounterUnavailable();
        return new Claim(List.copyOf(rows),invalid);
    }
    @Override public void finish(Snapshot snapshot) {
        String result=redis.execute(FINISH,List.of(counts,dirty),Long.toString(snapshot.articleId()),
                Long.toString(snapshot.value()),snapshot.lease());
        if(result==null || !Set.of("DONE","AGAIN","STALE").contains(result)) throw new CounterUnavailable();
    }
    @Override public long pending() {
        Long n=redis.opsForZSet().zCard(dirty);
        if(n==null || n<0) throw new CounterUnavailable();
        return n;
    }
    @Override public void close() { factory.destroy(); }
    private static boolean hash(String value) { return value!=null && value.matches("[0-9a-f]{64}"); }
}
