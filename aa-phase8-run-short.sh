#!/usr/bin/env bash
# Phase 8 short-iteration launcher: Run B with much shorter JMH iterations
# so the per-iteration record count fits within what the in-memory broker
# can drain. Used for a clean E2E number on the in-memory path; canonical
# 20s iterations are used for Apache Kafka (where they DO drain).
set -e
mode="$1"
case "$mode" in
  apache)
    extra_flag="-Dvenice.benchmark.use.inmemory.pubsub=false"
    ;;
  inmemory)
    extra_flag="-Dvenice.benchmark.use.inmemory.pubsub=true"
    ;;
  *)
    echo "Usage: $0 apache|inmemory" >&2
    exit 2
    ;;
esac
export JAVA_HOME=/export/apps/jdk/JDK-17_0_5-msft
JAR=/home/coder/Projects/venice/internal/venice-test-common/build/libs/venice-test-common-jmh.jar
exec "$JAVA_HOME/bin/java" -jar "$JAR" \
  -p workloadType=PUT -wi 1 -w 5s -i 2 -r 5s -f 1 \
  -jvmArgs "-Xms32G -Xmx32G \
    -Dvenice.server.aa.bottleneck.instrumentation.enabled=true \
    -Dvenice.server.aa.dcr.merge.instrumentation.enabled=true \
    -Dvenice.server.aa.rmd.timestamp.cache.enabled=true \
    -Dvenice.server.aa.rmd.timestamp.cache.bloom.authoritative=false \
    -Dvenice.server.aa.leader.other.instrumentation.enabled=true \
    -Dvenice.server.aa.kafka.pipeline.instrumentation.enabled=true \
    -Dvenice.server.aa.kafka.broker.jmx.enabled=true \
    -Dphase3.producers.per.region=2 \
    $extra_flag" \
  com.linkedin.venice.benchmark.ActiveActiveIngestionBenchmark.benchmarkAAIngestion
