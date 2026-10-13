package com.paicli.eval;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvalMainConcurrencyTest {

    @Test
    void runUnitsCapsParallelismAndExecutesEveryUnit() throws Exception {
        int concurrency = 2;
        int unitCount = 6;
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        AtomicInteger completed = new AtomicInteger();
        CountDownLatch firstWaveStarted = new CountDownLatch(concurrency);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Runnable> units = new ArrayList<>();

        for (int i = 0; i < unitCount; i++) {
            units.add(() -> {
                int current = active.incrementAndGet();
                maxActive.accumulateAndGet(current, Math::max);
                firstWaveStarted.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                    completed.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    active.decrementAndGet();
                }
            });
        }

        Thread coordinator = new Thread(() -> {
            try {
                EvalMain.runUnits(units, concurrency);
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "eval-concurrency-test");
        coordinator.start();
        try {
            assertTrue(firstWaveStarted.await(5, TimeUnit.SECONDS), "首批并发任务未及时启动");
            assertEquals(concurrency, maxActive.get());
        } finally {
            release.countDown();
        }
        coordinator.join(Duration.ofSeconds(5).toMillis());

        assertFalse(coordinator.isAlive(), "并发调度未结束");
        assertNull(failure.get());
        assertEquals(unitCount, completed.get());
        assertEquals(0, active.get());
        assertTrue(maxActive.get() <= concurrency);
    }

    @Test
    void concurrencyOneUsesStableSubmissionOrder() throws Exception {
        List<Integer> order = new ArrayList<>();
        EvalMain.runUnits(List.of(
                () -> order.add(1),
                () -> order.add(2),
                () -> order.add(3)), 1);
        assertEquals(List.of(1, 2, 3), order);
    }

    @Test
    void runsOverrideAndPositiveOptionsAreValidated() {
        CaseSpec spec = EvalFixtures.caseSpec("runs-case");

        assertEquals(1, EvalMain.effectiveRuns(spec, null));
        assertEquals(3, EvalMain.effectiveRuns(spec, 3));
        assertEquals(4, EvalMain.positiveInt("concurrency", "4"));
        assertThrows(IllegalArgumentException.class, () -> EvalMain.positiveInt("runs", "0"));
        assertThrows(IllegalArgumentException.class, () -> EvalMain.positiveInt("runs", "not-a-number"));
    }
}
