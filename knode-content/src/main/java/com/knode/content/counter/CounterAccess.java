package com.knode.content.counter;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CounterAccess {
    private final JdbcTemplate db;
    private final CounterStore store;
    public CounterAccess(JdbcTemplate db,CounterStore store) { this.db=db; this.store=store; }
    private record Article(long count,String status,boolean deleted) {}

    // No global fence is held here. Every publication writer takes its fence before this row lock.
    @Transactional(isolation=Isolation.READ_COMMITTED,timeout=3)
    public int hit(long id,CounterStore.Identity identity) {
        if(!store.enabled()) throw new CounterUnavailable();
        List<Article> rows=db.query("SELECT view_count,status,deleted FROM content_article WHERE id=? FOR UPDATE",
                (r,n) -> new Article(r.getLong(1),r.getString(2),r.getBoolean(3)),id);
        Article row=rows.isEmpty()?null:rows.get(0);
        boolean visible=row!=null && !row.deleted() && "0".equals(row.status());
        return store.hit(id,row==null?0:row.count(),visible,identity);
    }
}
