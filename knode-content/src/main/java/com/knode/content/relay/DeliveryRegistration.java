package com.knode.content.relay;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeliveryRegistration {
    private final JdbcTemplate db;
    public DeliveryRegistration(JdbcTemplate db) { this.db=db; }
    @Transactional(propagation=Propagation.MANDATORY)
    public void register(String eventId,String family,String type) {
        boolean local="COMMENT".equals(family) && type.startsWith("LinkComment");
        db.update("INSERT INTO content_event_delivery(event_id,family,event_type,state,attempts,total_attempts,due_at) VALUES(?,?,?,?,0,0,"+
                (local?"NULL":"CURRENT_TIMESTAMP(6)")+")",eventId,family,type,local?"LOCAL_ONLY":"PENDING");
    }
}
