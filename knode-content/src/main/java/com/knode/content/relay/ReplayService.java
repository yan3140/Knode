package com.knode.content.relay;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class ReplayService {
    private final ReplayTransaction transaction;
    public ReplayService(ReplayTransaction transaction) { this.transaction=transaction; }
    public void apply(String eventId,String requestId,String operator) {
        try { transaction.apply(eventId,requestId,operator); }
        catch(DuplicateKeyException collision) { transaction.verifyExisting(eventId,requestId,operator); }
    }
}
