package com.linkedin.davinci.stats;

import java.lang.management.ManagementFactory;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.management.MBeanServer;
import javax.management.ObjectName;


/**
 * Phase 6 reporter that periodically scrapes Kafka producer/consumer client JMX
 * MBeans and prints {@code [KAFKA-PIPELINE]} stderr lines for off-line analysis.
 *
 * <p>The MBean naming follows the standard Apache Kafka client conventions:
 * <ul>
 *   <li>Producer: {@code kafka.producer:type=producer-metrics,client-id=*}</li>
 *   <li>Consumer fetch: {@code kafka.consumer:type=consumer-fetch-manager-metrics,client-id=*}</li>
 *   <li>Consumer fetch per-broker: {@code kafka.consumer:type=consumer-fetch-manager-metrics,client-id=*,topic=*}</li>
 * </ul>
 *
 * <p>Gated by the system property
 * {@code venice.server.aa.kafka.pipeline.instrumentation.enabled}. When the
 * flag is unset or {@code false}, the daemon scheduler is never started.</p>
 *
 * <p>Output schema (one line per MBean instance per tick):</p>
 * <pre>
 *   [KAFKA-PIPELINE] producer client_id=&lt;id&gt; record_send_rate=&lt;F&gt; request_latency_avg_ms=&lt;F&gt; \
 *       batch_size_avg_bytes=&lt;F&gt; records_per_request_avg=&lt;F&gt; record_queue_time_avg_ms=&lt;F&gt;
 *   [KAFKA-PIPELINE] consumer client_id=&lt;id&gt; records_consumed_rate=&lt;F&gt; \
 *       fetch_latency_avg_ms=&lt;F&gt; fetch_size_avg_bytes=&lt;F&gt; records_per_request_avg=&lt;F&gt;
 *   [KAFKA-PIPELINE-SUMMARY] tick=&lt;n&gt; producer_count=&lt;N&gt; consumer_count=&lt;N&gt;
 * </pre>
 *
 * <p>Reading {@code F} as Double; missing attributes print as {@code -1.0}.</p>
 */
public final class AaKafkaPipelineReporter {
  private static final long REPORT_INTERVAL_SECONDS = 20L;

  /**
   * Master enable flag. Set at class-load time from the system property
   * {@code venice.server.aa.kafka.pipeline.instrumentation.enabled}.
   */
  public static final boolean ENABLED =
      Boolean.getBoolean("venice.server.aa.kafka.pipeline.instrumentation.enabled");

  private static final AtomicBoolean STARTED = new AtomicBoolean();
  private static long tickNumber = 0L;

  static {
    if (ENABLED) {
      startReporter();
    }
    // Phase 7: also pull in the broker-side JMX scraper. It self-gates on
    // `venice.server.aa.kafka.broker.jmx.enabled` so this is a no-op unless
    // that property is explicitly set.
    AaKafkaBrokerReporter.ensureStarted();
  }

  private AaKafkaPipelineReporter() {
  }

  private static void startReporter() {
    if (STARTED.compareAndSet(false, true)) {
      ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "AaKafkaPipelineReporter");
        t.setDaemon(true);
        return t;
      });
      scheduler.scheduleAtFixedRate(
          AaKafkaPipelineReporter::reportOnce,
          REPORT_INTERVAL_SECONDS,
          REPORT_INTERVAL_SECONDS,
          TimeUnit.SECONDS);
      Runtime.getRuntime().addShutdownHook(new Thread(() -> {
        try {
          reportOnce();
        } catch (Throwable ignored) {
        }
      }, "AaKafkaPipelineReporterShutdown"));
    }
  }

  // Phase 8: when running with the in-memory pubsub broker no Apache Kafka client MBeans
  // exist. Latch this on the first tick and stop emitting [KAFKA-PIPELINE] lines for the
  // remainder of the run. Wrapped at the per-call level (try/catch around the whole body
  // already exists) so the reporter never throws.
  private static volatile Boolean MBEANS_AVAILABLE = null;

  private static synchronized void reportOnce() {
    tickNumber++;
    int producerCount = 0;
    int consumerCount = 0;
    try {
      MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();

      // First-tick gate: detect whether any Kafka producer or consumer client MBeans exist.
      // If none, declare the reporter inactive (in-memory broker mode) and return.
      if (MBEANS_AVAILABLE == null) {
        Set<ObjectName> probeProducers =
            mbs.queryNames(new ObjectName("kafka.producer:type=producer-metrics,*"), null);
        Set<ObjectName> probeConsumers = mbs.queryNames(
            new ObjectName("kafka.consumer:type=consumer-fetch-manager-metrics,*"),
            null);
        MBEANS_AVAILABLE = !probeProducers.isEmpty() || !probeConsumers.isEmpty();
        if (!MBEANS_AVAILABLE) {
          System.err.println(
              "[KAFKA-PIPELINE] no Kafka client MBeans available; reporter inactive (in-memory broker mode).");
          return;
        }
      } else if (!MBEANS_AVAILABLE) {
        return;
      }

      // Producers: kafka.producer:type=producer-metrics,client-id=*
      ObjectName producerPattern = new ObjectName("kafka.producer:type=producer-metrics,*");
      Set<ObjectName> producerNames = mbs.queryNames(producerPattern, null);
      for (ObjectName on: producerNames) {
        String clientId = on.getKeyProperty("client-id");
        if (clientId == null) {
          continue;
        }
        double recordSendRate = readDouble(mbs, on, "record-send-rate");
        double requestLatencyAvg = readDouble(mbs, on, "request-latency-avg");
        double batchSizeAvg = readDouble(mbs, on, "batch-size-avg");
        double recordsPerReqAvg = readDouble(mbs, on, "records-per-request-avg");
        double recordQueueTimeAvg = readDouble(mbs, on, "record-queue-time-avg");
        System.err.println(
            "[KAFKA-PIPELINE] producer client_id=" + clientId
                + " record_send_rate=" + fmt(recordSendRate)
                + " request_latency_avg_ms=" + fmt(requestLatencyAvg)
                + " batch_size_avg_bytes=" + fmt(batchSizeAvg)
                + " records_per_request_avg=" + fmt(recordsPerReqAvg)
                + " record_queue_time_avg_ms=" + fmt(recordQueueTimeAvg));
        producerCount++;
      }

      // Consumers: kafka.consumer:type=consumer-fetch-manager-metrics,client-id=*
      // We only scrape the *aggregate* (no topic= key) MBean to avoid one line per
      // (client,topic) pair which floods the log.
      ObjectName consumerPattern =
          new ObjectName("kafka.consumer:type=consumer-fetch-manager-metrics,client-id=*");
      Set<ObjectName> consumerNames = mbs.queryNames(consumerPattern, null);
      for (ObjectName on: consumerNames) {
        // Skip per-topic / per-partition variants which carry extra keys.
        String topic = on.getKeyProperty("topic");
        if (topic != null) {
          continue;
        }
        String clientId = on.getKeyProperty("client-id");
        if (clientId == null) {
          continue;
        }
        double recordsConsumedRate = readDouble(mbs, on, "records-consumed-rate");
        double fetchLatencyAvg = readDouble(mbs, on, "fetch-latency-avg");
        double fetchSizeAvg = readDouble(mbs, on, "fetch-size-avg");
        double recordsPerReqAvg = readDouble(mbs, on, "records-per-request-avg");
        System.err.println(
            "[KAFKA-PIPELINE] consumer client_id=" + clientId
                + " records_consumed_rate=" + fmt(recordsConsumedRate)
                + " fetch_latency_avg_ms=" + fmt(fetchLatencyAvg)
                + " fetch_size_avg_bytes=" + fmt(fetchSizeAvg)
                + " records_per_request_avg=" + fmt(recordsPerReqAvg));
        consumerCount++;
      }
    } catch (Throwable t) {
      System.err.println(
          "[KAFKA-PIPELINE] tick=" + tickNumber + " jmx_scan_error=" + t.getClass().getSimpleName() + ":"
              + t.getMessage());
    }
    System.err.println(
        "[KAFKA-PIPELINE-SUMMARY] tick=" + tickNumber + " producer_count=" + producerCount + " consumer_count="
            + consumerCount);
  }

  private static double readDouble(MBeanServer mbs, ObjectName on, String attr) {
    try {
      Object v = mbs.getAttribute(on, attr);
      if (v instanceof Number) {
        double d = ((Number) v).doubleValue();
        if (Double.isNaN(d) || Double.isInfinite(d)) {
          return -1.0;
        }
        return d;
      }
      return -1.0;
    } catch (Throwable t) {
      return -1.0;
    }
  }

  private static String fmt(double d) {
    if (d == -1.0) {
      return "-1";
    }
    if (Math.abs(d) >= 1e6 || (d != 0.0 && Math.abs(d) < 1e-3)) {
      return String.format("%.4e", d);
    }
    return String.format("%.4f", d);
  }
}
