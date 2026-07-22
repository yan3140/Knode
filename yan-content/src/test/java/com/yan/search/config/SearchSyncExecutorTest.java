package com.yan.search.config;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class SearchSyncExecutorTest {

    @Test
    void createsBoundedExecutorThatCanScaleWithQueuePressure() {
        SearchSyncProperties properties = new SearchSyncProperties();
        properties.setCorePoolSize(4);
        properties.setMaxPoolSize(8);
        properties.setQueueCapacity(20);
        properties.setKeepAliveSeconds(60);

        ThreadPoolTaskExecutor executor = new ElasticsearchConfig().searchSyncExecutor(properties);
        try {
            assertEquals(4, executor.getCorePoolSize());
            assertEquals(8, executor.getMaxPoolSize());
            assertEquals(20, executor.getThreadPoolExecutor().getQueue().remainingCapacity());
            assertInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class,
                    executor.getThreadPoolExecutor().getRejectedExecutionHandler());
        } finally {
            executor.shutdown();
        }
    }
}
