package com.knode.content.snapshot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class SnapshotCleanup {
    private static final Logger LOG = LoggerFactory.getLogger(SnapshotCleanup.class);
    private final SnapshotSettings settings;
    private final SnapshotExport exports;

    public SnapshotCleanup(SnapshotSettings settings, SnapshotExport exports) {
        this.settings = settings;
        this.exports = exports;
    }

    @Scheduled(fixedDelay = 5_000)
    public void run() {
        if (!settings.cleanupEnabled()) return;
        try {
            exports.cleanExpired();
        } catch (RuntimeException failure) {
            LOG.warn("Content snapshot cleanup failed");
        }
    }
}
