package org.example.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlertCooldownStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void blocksRepeatWithinCooldown() {
        AlertCooldownStore store = new AlertCooldownStore();
        store.configureStateFile(tempDir.resolve("cooldown.json").toString());

        String key = "disk-root-critical:172.19.0.64";
        Duration cooldown = Duration.ofHours(24);

        assertTrue(store.tryAcquire(key, cooldown));
        store.markSent(key);
        assertFalse(store.tryAcquire(key, cooldown));
    }
}
