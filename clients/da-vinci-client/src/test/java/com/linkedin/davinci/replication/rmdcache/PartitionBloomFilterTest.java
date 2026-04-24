package com.linkedin.davinci.replication.rmdcache;

import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.testng.annotations.Test;


public class PartitionBloomFilterTest {
  @Test
  public void absenceIsDefinite_presenceIsProbabilistic() {
    PartitionBloomFilter bf = new PartitionBloomFilter(10_000, 0.01);
    for (long i = 0; i < 1_000; i++) {
      bf.put(i);
    }
    for (long i = 0; i < 1_000; i++) {
      assertTrue(bf.mightContain(i), "inserted element must read as present");
    }
    // Probe far outside the insertion range; at fpp=0.01 most of these should read absent.
    int falsePositives = 0;
    int probes = 10_000;
    for (long i = 100_000; i < 100_000 + probes; i++) {
      if (bf.mightContain(i)) {
        falsePositives++;
      }
    }
    // Give a generous upper bound: 3x the target fpp to avoid flakes.
    assertTrue(falsePositives < probes * 0.03, "observed fpp too high: " + falsePositives + "/" + probes);
  }

  @Test
  public void concurrentInsertionsAreSafe() throws InterruptedException {
    PartitionBloomFilter bf = new PartitionBloomFilter(100_000, 0.01);
    int threadCount = 8;
    int perThread = 10_000;
    ExecutorService exec = Executors.newFixedThreadPool(threadCount);
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(threadCount);
    AtomicInteger errors = new AtomicInteger();
    for (int t = 0; t < threadCount; t++) {
      int base = t * perThread;
      exec.submit(() -> {
        try {
          start.await();
          for (int i = 0; i < perThread; i++) {
            bf.put(base + i);
          }
        } catch (Throwable th) {
          errors.incrementAndGet();
        } finally {
          done.countDown();
        }
      });
    }
    start.countDown();
    assertTrue(done.await(30, TimeUnit.SECONDS));
    exec.shutdownNow();
    if (errors.get() != 0) {
      throw new AssertionError("threads saw errors: " + errors.get());
    }
    // After concurrent fill, every inserted element must read as present.
    for (int t = 0; t < threadCount; t++) {
      int base = t * perThread;
      for (int i = 0; i < perThread; i++) {
        assertTrue(bf.mightContain(base + i));
      }
    }
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void rejectsInvalidExpectedInsertions() {
    new PartitionBloomFilter(0, 0.01);
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void rejectsInvalidFpp() {
    new PartitionBloomFilter(100, 1.5);
  }

  @Test
  public void clearResetsAllBits() {
    PartitionBloomFilter bf = new PartitionBloomFilter(1_000, 0.01);
    bf.put(1L);
    bf.put(2L);
    bf.put(3L);
    assertTrue(bf.mightContain(1L));
    bf.clear();
    assertFalse(bf.mightContain(1L));
    assertFalse(bf.mightContain(2L));
    assertFalse(bf.mightContain(3L));
  }
}
