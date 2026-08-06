package com.paicli.eval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JudgeCacheTest {

    @TempDir
    Path tempDir;

    @Test
    void concurrentSameKeyComputesOnlyOnce() throws Exception {
        JudgeCache cache = new JudgeCache(tempDir);
        String key = RubricRepository.sha256("same prompt");
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        Callable<JudgeCache.Lookup> task = () -> {
            start.await();
            return cache.getOrCompute(key, () -> {
                loads.incrementAndGet();
                Thread.sleep(40);
                return new JudgeCache.Entry(4, "ok", "provider", "model", "rubric");
            });
        };

        var pool = Executors.newFixedThreadPool(4);
        try {
            List<java.util.concurrent.Future<JudgeCache.Lookup>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                futures.add(pool.submit(task));
            }
            start.countDown();
            int hits = 0;
            for (var future : futures) {
                if (future.get().hit()) {
                    hits++;
                }
            }
            assertEquals(1, loads.get());
            assertEquals(3, hits);
        } finally {
            pool.shutdownNow();
        }
    }
}
