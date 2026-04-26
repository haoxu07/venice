#!/usr/bin/env bash
# Phase 8 balanced launcher: Run B with lower records-per-invocation (50)
# so the producer rate roughly matches the in-memory consumer's drain rate.
# This gives a clean E2E number from the in-memory path (without 250s drain).
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
  -p workloadType=PUT -wi 2 -w 20s -i 2 -r 20s -f 1 \
  -jvmArgs "-Xms32G -Xmx32G \
    -Dvenice.server.aa.bottleneck.instrumentation.enabled=true \
    -Dvenice.server.aa.dcr.merge.instrumentation.enabled=true \
    -Dvenice.server.aa.rmd.timestamp.cache.enabled=true \
    -Dvenice.server.aa.rmd.timestamp.cache.bloom.authoritative=false \
    -Dvenice.server.aa.leader.other.instrumentation.enabled=true \
    -Dvenice.server.aa.kafka.pipeline.instrumentation.enabled=true \
    -Dvenice.server.aa.kafka.broker.jmx.enabled=true \
    -Dphase3.producers.per.region=2 \
    -Dphase3.records.per.invocation=50 \
    $extra_flag" \
  com.linkedin.venice.benchmark.ActiveActiveIngestionBenchmark.benchmarkAAIngestion
