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
 * Phase 7 reporter that periodically scrapes IN-PROCESS Kafka BROKER JMX MBeans
 * (as opposed to the client-side producer/consumer MBeans scraped by
 * {@link AaKafkaPipelineReporter}). Emits {@code [KAFKA-BROKER]} stderr lines
 * every 20 seconds for off-line analysis.
 *
 * <p>The MBean naming follows the standard Apache Kafka broker conventions:
 * <ul>
 *   <li>{@code kafka.server:type=KafkaRequestHandlerPool,name=RequestHandlerAvgIdlePercent} (OneMinuteRate)</li>
 *   <li>{@code kafka.network:type=SocketServer,name=NetworkProcessorAvgIdlePercent} (Value)</li>
 *   <li>{@code kafka.network:type=RequestMetrics,name=*,request=Produce/Fetch} (Mean)</li>
 *   <li>{@code kafka.server:type=BrokerTopicMetrics,name=BytesInPerSec/BytesOutPerSec/MessagesInPerSec} (OneMinuteRate)</li>
 *   <li>{@code kafka.log:type=LogFlushStats,name=LogFlushRateAndTimeMs} (Mean + OneMinuteRate)</li>
 * </ul>
 *
 * <p>Gated by the system property
 * {@code venice.server.aa.kafka.broker.jmx.enabled}. When the flag is unset or
 * {@code false}, the daemon scheduler is never started.</p>
 *
 * <p>Output schema (one summary line per tick):</p>
 * <pre>
 *   [KAFKA-BROKER] tick=&lt;n&gt; handler_idle=&lt;F&gt; net_idle=&lt;F&gt; \
 *       produce_local_ms=&lt;F&gt; produce_remote_ms=&lt;F&gt; produce_req_queue_ms=&lt;F&gt; \
 *       produce_resp_queue_ms=&lt;F&gt; produce_total_ms=&lt;F&gt; \
 *       fetch_local_ms=&lt;F&gt; fetch_remote_ms=&lt;F&gt; fetch_req_queue_ms=&lt;F&gt; \
 *       fetch_resp_queue_ms=&lt;F&gt; fetch_total_ms=&lt;F&gt; \
 *       bytes_in_per_sec=&lt;F&gt; bytes_out_per_sec=&lt;F&gt; messages_in_per_sec=&lt;F&gt; \
 *       log_flush_mean_ms=&lt;F&gt; log_flush_per_sec=&lt;F&gt; \
 *       broker_count=&lt;N&gt;
 * </pre>
 *
 * <p>For request metrics (Produce / Fetch), Kafka registers MBeans both with and
 * without a {@code request=} key — the no-key one is the global aggregate. We
 * average across the per-broker instances we find: in the in-process benchmark
 * there are typically 3 brokers (parent, dc-0, dc-1), so the line averages each
 * metric across them.</p>
 */
public final class AaKafkaBrokerReporter {
  private static final long REPORT_INTERVAL_SECONDS = 20L;

  /**
   * Master enable flag. Set at class-load time from the system property
   * {@code venice.server.aa.kafka.broker.jmx.enabled}.
   */
  public static final boolean ENABLED = Boolean.getBoolean("venice.server.aa.kafka.broker.jmx.enabled");

  private static final AtomicBoolean STARTED = new AtomicBoolean();
  private static long tickNumber = 0L;

  static {
    if (ENABLED) {
      startReporter();
    }
  }

  private AaKafkaBrokerReporter() {
  }

  /**
   * Idempotent. Safe to call multiple times. Used by sibling classes (e.g.
   * {@link AaKafkaPipelineReporter}) to ensure this reporter is loaded even if
   * the JVM hasn't otherwise referenced it yet.
   */
  public static void ensureStarted() {
    // Touching the class is enough to run the static initializer.
  }

  private static void startReporter() {
    if (STARTED.compareAndSet(false, true)) {
      ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "AaKafkaBrokerReporter");
        t.setDaemon(true);
        return t;
      });
      scheduler.scheduleAtFixedRate(
          AaKafkaBrokerReporter::reportOnce,
          REPORT_INTERVAL_SECONDS,
          REPORT_INTERVAL_SECONDS,
          TimeUnit.SECONDS);
      Runtime.getRuntime().addShutdownHook(new Thread(() -> {
        try {
          reportOnce();
        } catch (Throwable ignored) {
        }
      }, "AaKafkaBrokerReporterShutdown"));
    }
  }

  /**
   * Average a numeric attribute across all MBeans matching {@code pattern}.
   * Returns -1.0 if no matching MBean / no readable values.
   */
  private static double avgAttr(MBeanServer mbs, String pattern, String attr) {
    try {
      Set<ObjectName> names = mbs.queryNames(new ObjectName(pattern), null);
      double sum = 0.0;
      int n = 0;
      for (ObjectName on: names) {
        double v = readDouble(mbs, on, attr);
        if (v != -1.0) {
          sum += v;
          n++;
        }
      }
      if (n == 0) {
        return -1.0;
      }
      return sum / n;
    } catch (Throwable t) {
      return -1.0;
    }
  }

  private static int countBrokers(MBeanServer mbs) {
    try {
      Set<ObjectName> names = mbs.queryNames(
          new ObjectName("kafka.server:type=KafkaRequestHandlerPool,name=RequestHandlerAvgIdlePercent,*"),
          null);
      // Some Kafka versions register without a brokerId key; fall back to non-pattern lookup.
      if (names.isEmpty()) {
        names = mbs.queryNames(
            new ObjectName("kafka.server:type=KafkaRequestHandlerPool,name=RequestHandlerAvgIdlePercent"),
            null);
      }
      return names.size();
    } catch (Throwable t) {
      return 0;
    }
  }

  // Phase 8: when running with the in-memory pubsub broker (`venice.benchmark.use.inmemory.pubsub=true`)
  // no Apache Kafka broker MBeans exist in the JVM. Detect that situation once at the first
  // tick and skip emission for the remainder of the run, so the log stays clean and the
  // reporter is a true no-op rather than spamming -1 lines. Wrapped in try/catch (also
  // already at the per-call level below) so the reporter never throws.
  private static volatile Boolean MBEANS_AVAILABLE = null;

  private static synchronized void reportOnce() {
    tickNumber++;
    try {
      MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();

      // First-tick gate: if no Kafka broker MBeans exist, declare the reporter inactive
      // and stop emitting [KAFKA-BROKER] lines. Required so the in-memory pubsub run
      // (criterion 5) does not throw or spam -1 lines.
      if (MBEANS_AVAILABLE == null) {
        int brokers = countBrokers(mbs);
        MBEANS_AVAILABLE = brokers > 0;
        if (!MBEANS_AVAILABLE) {
          System.err.println(
              "[KAFKA-BROKER] no Kafka broker MBeans available; reporter inactive (in-memory broker mode).");
          return;
        }
      } else if (!MBEANS_AVAILABLE) {
        return;
      }

      double handlerIdle = avgAttr(
          mbs,
          "kafka.server:type=KafkaRequestHandlerPool,name=RequestHandlerAvgIdlePercent,*",
          "OneMinuteRate");
      if (handlerIdle == -1.0) {
        handlerIdle = avgAttr(
            mbs,
            "kafka.server:type=KafkaRequestHandlerPool,name=RequestHandlerAvgIdlePercent",
            "OneMinuteRate");
      }
      double netIdle = avgAttr(
          mbs,
          "kafka.network:type=SocketServer,name=NetworkProcessorAvgIdlePercent,*",
          "Value");
      if (netIdle == -1.0) {
        netIdle =
            avgAttr(mbs, "kafka.network:type=SocketServer,name=NetworkProcessorAvgIdlePercent", "Value");
      }

      // Produce request decomposition (Mean of each component time).
      double prodLocal =
          avgAttr(mbs, "kafka.network:type=RequestMetrics,name=LocalTimeMs,request=Produce,*", "Mean");
      double prodRemote =
          avgAttr(mbs, "kafka.network:type=RequestMetrics,name=RemoteTimeMs,request=Produce,*", "Mean");
      double prodReqQueue = avgAttr(
          mbs,
          "kafka.network:type=RequestMetrics,name=RequestQueueTimeMs,request=Produce,*",
          "Mean");
      double prodRespQueue = avgAttr(
          mbs,
          "kafka.network:type=RequestMetrics,name=ResponseQueueTimeMs,request=Produce,*",
          "Mean");
      double prodTotal =
          avgAttr(mbs, "kafka.network:type=RequestMetrics,name=TotalTimeMs,request=Produce,*", "Mean");

      // Fetch request decomposition.
      double fetchLocal =
          avgAttr(mbs, "kafka.network:type=RequestMetrics,name=LocalTimeMs,request=Fetch,*", "Mean");
      double fetchRemote =
          avgAttr(mbs, "kafka.network:type=RequestMetrics,name=RemoteTimeMs,request=Fetch,*", "Mean");
      double fetchReqQueue =
          avgAttr(mbs, "kafka.network:type=RequestMetrics,name=RequestQueueTimeMs,request=Fetch,*", "Mean");
      double fetchRespQueue = avgAttr(
          mbs,
          "kafka.network:type=RequestMetrics,name=ResponseQueueTimeMs,request=Fetch,*",
          "Mean");
      double fetchTotal =
          avgAttr(mbs, "kafka.network:type=RequestMetrics,name=TotalTimeMs,request=Fetch,*", "Mean");

      // Topic-level throughput (no topic= key = global aggregate).
      double bytesInPerSec = avgAttr(
          mbs,
          "kafka.server:type=BrokerTopicMetrics,name=BytesInPerSec",
          "OneMinuteRate");
      double bytesOutPerSec = avgAttr(
          mbs,
          "kafka.server:type=BrokerTopicMetrics,name=BytesOutPerSec",
          "OneMinuteRate");
      double messagesInPerSec = avgAttr(
          mbs,
          "kafka.server:type=BrokerTopicMetrics,name=MessagesInPerSec",
          "OneMinuteRate");

      double logFlushMeanMs =
          avgAttr(mbs, "kafka.log:type=LogFlushStats,name=LogFlushRateAndTimeMs", "Mean");
      double logFlushPerSec =
          avgAttr(mbs, "kafka.log:type=LogFlushStats,name=LogFlushRateAndTimeMs", "OneMinuteRate");

      int brokerCount = countBrokers(mbs);

      System.err.println(
          "[KAFKA-BROKER] tick=" + tickNumber + " handler_idle=" + fmt(handlerIdle) + " net_idle=" + fmt(netIdle)
              + " produce_local_ms=" + fmt(prodLocal) + " produce_remote_ms=" + fmt(prodRemote) + " produce_req_queue_ms="
              + fmt(prodReqQueue) + " produce_resp_queue_ms=" + fmt(prodRespQueue) + " produce_total_ms="
              + fmt(prodTotal) + " fetch_local_ms=" + fmt(fetchLocal) + " fetch_remote_ms=" + fmt(fetchRemote)
              + " fetch_req_queue_ms=" + fmt(fetchReqQueue) + " fetch_resp_queue_ms=" + fmt(fetchRespQueue)
              + " fetch_total_ms=" + fmt(fetchTotal) + " bytes_in_per_sec=" + fmt(bytesInPerSec) + " bytes_out_per_sec="
              + fmt(bytesOutPerSec) + " messages_in_per_sec=" + fmt(messagesInPerSec) + " log_flush_mean_ms="
              + fmt(logFlushMeanMs) + " log_flush_per_sec=" + fmt(logFlushPerSec) + " broker_count=" + brokerCount);
    } catch (Throwable t) {
      System.err.println(
          "[KAFKA-BROKER] tick=" + tickNumber + " jmx_scan_error=" + t.getClass().getSimpleName() + ":"
              + t.getMessage());
    }
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
