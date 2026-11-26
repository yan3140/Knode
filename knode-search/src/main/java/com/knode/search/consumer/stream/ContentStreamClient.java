package com.knode.search.consumer.stream;

import java.util.List;

public interface ContentStreamClient {

    String streamKey();

    String groupName();

    String consumerName();

    List<StreamRecord> claimIdlePending(int batchSize, long minIdleMs);

    List<StreamRecord> readNew(int batchSize, long blockMs);

    long ack(List<String> recordIds);
}
