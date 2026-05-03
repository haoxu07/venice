package com.linkedin.venice.benchmark.lean;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import com.linkedin.davinci.storage.StorageService;
import com.linkedin.davinci.store.StorageEngine;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;


/**
 * Phase 3 smoke test for {@link MinimalAAIngestionHarness}: stands up the harness's per-region
 * RocksDB-backed {@link StorageService}s and verifies a {@code put} / {@code get} byte-equality
 * round-trip on the version topic's storage engine on every region.
 *
 * <p>Coverage:
 * <ul>
 *   <li>One {@link StorageService} per region comes up under {@link MinimalAAIngestionHarness#start()}
 *       and reports an open {@link StorageEngine} for the version topic with all configured partitions
 *       added.</li>
 *   <li>{@code put(partition, key, value)} followed by {@code get(partition, key)} returns the same
 *       bytes for every region and every partition.</li>
 *   <li>Region-scoped isolation: a {@code get} for region 0's key returns {@code null} on region 1's
 *       engine (each region has its own RocksDB rooted in its own temp dir).</li>
 *   <li>Combined StorageService startup time is logged so the {@literal <}5-second budget can be
 *       checked.</li>
 *   <li>{@link MinimalAAIngestionHarness#stop()} shuts the storage services down and deletes the
 *       per-region temp directories: each region's tmp dir must no longer exist on disk after stop.</li>
 * </ul>
 */
public class LeanHarnessStorageSmokeTest {
  private static final String STORE_NAME = "lean-harness-storage-smoke-store";
  private static final int REGION_COUNT = 2;
  private static final int PARTITION_COUNT = 2;
  private static final int VERSION_NUMBER = 1;

  private MinimalAAIngestionHarness harness;

  @BeforeMethod(alwaysRun = true)
  public void setUp() {
    MinimalAAIngestionHarness.Config config =
        new MinimalAAIngestionHarness.Config(REGION_COUNT, PARTITION_COUNT, STORE_NAME);
    harness = new MinimalAAIngestionHarness(config);
  }

  @AfterMethod(alwaysRun = true)
  public void tearDown() {
    if (harness != null) {
      harness.stop();
    }
  }

  @Test(timeOut = 60_000)
  public void testStorageEnginePutGetRoundTripOnEveryRegionAndPartition() {
    long startNanos = System.nanoTime();
    harness.start();
    long startMs = (System.nanoTime() - startNanos) / 1_000_000L;
    System.out.println("[LeanHarnessStorageSmokeTest] harness.start() took " + startMs + " ms");
    assertTrue(harness.isStarted(), "Harness must report started after start()");

    // Sanity: each region exposes a non-null StorageService and StorageEngine.
    List<StorageEngine> engines = new ArrayList<>();
    for (int region = 0; region < REGION_COUNT; region++) {
      StorageService storageService = harness.getStorageServiceForRegion(region);
      assertNotNull(storageService, "StorageService for region " + region + " must be non-null");
      assertTrue(storageService.isRunning(), "StorageService for region " + region + " must be running");

      StorageEngine engine = harness.getStorageEngineForRegion(region);
      assertNotNull(engine, "StorageEngine for region " + region + " must be non-null");
      assertEquals(
          engine.getStoreVersionName(),
          STORE_NAME + "_v" + VERSION_NUMBER,
          "StorageEngine for region " + region + " must be opened on the version topic");
      // Confirm every configured partition was added.
      for (int p = 0; p < PARTITION_COUNT; p++) {
        assertTrue(engine.containsPartition(p), "StorageEngine for region " + region + " missing partition " + p);
      }
      engines.add(engine);
    }

    // Put + get on every (region, partition) pair, asserting byte-equality on the way out.
    for (int region = 0; region < REGION_COUNT; region++) {
      StorageEngine engine = engines.get(region);
      for (int partition = 0; partition < PARTITION_COUNT; partition++) {
        byte[] key = ("smoke-key-region-" + region + "-partition-" + partition).getBytes(StandardCharsets.UTF_8);
        byte[] value = ("smoke-value-region-" + region + "-partition-" + partition).getBytes(StandardCharsets.UTF_8);
        engine.put(partition, key, value);
        byte[] got = engine.get(partition, key);
        assertNotNull(got, "Region " + region + " partition " + partition + " should have a value after put");
        assertEquals(
            got,
            value,
            "Round-tripped value bytes must match for region " + region + " partition " + partition);
      }
    }

    // Region isolation: region-0's key on region-1's engine must be absent (RocksDB dirs are disjoint).
    byte[] region0Key = ("smoke-key-region-0-partition-0").getBytes(StandardCharsets.UTF_8);
    assertNull(engines.get(1).get(0, region0Key), "Region 0's key must not be visible on region 1's engine");
  }

  @Test(timeOut = 60_000)
  public void testTempDirsAreDeletedAfterStop() {
    harness.start();

    // Capture the per-region temp dirs before stop().
    List<File> tempDirsBeforeStop = new ArrayList<>(REGION_COUNT);
    for (int region = 0; region < REGION_COUNT; region++) {
      File regionDir = harness.getRegionTempDir(region);
      assertNotNull(regionDir, "Region " + region + " temp dir must be non-null while harness is running");
      assertTrue(
          regionDir.exists() && regionDir.isDirectory(),
          "Region " + region + " temp dir must exist on disk while harness is running: " + regionDir);
      File rocksdbRoot = new File(regionDir, "rocksdb");
      assertTrue(
          rocksdbRoot.exists() && rocksdbRoot.isDirectory(),
          "Region " + region + " rocksdb root must exist on disk while harness is running: " + rocksdbRoot);
      tempDirsBeforeStop.add(regionDir);
    }

    harness.stop();
    assertFalse(harness.isStarted(), "Harness must report stopped after stop()");

    for (int region = 0; region < REGION_COUNT; region++) {
      File dir = tempDirsBeforeStop.get(region);
      assertFalse(dir.exists(), "Region " + region + " temp dir must be deleted after stop(): " + dir);
    }
  }
}
