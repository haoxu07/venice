#!/usr/bin/env bash
# Phase 8 alloc profile launcher v2: canonical 20s iterations + alloc profiler.
# Even if the iteration teardown blocks on sentinel, async-profiler samples
# allocation during the actual produce/consume work and emits flamegraphs at
# JVM exit.
set -e
mode="$1"
case "$mode" in
  apache)
    extra_flag="-Dvenice.benchmark.use.inmemory.pubsub=false"
    out_dir="aa-profile-alloc-apache-fresh"
    ;;
  inmemory)
    extra_flag="-Dvenice.benchmark.use.inmemory.pubsub=true"
    out_dir="aa-profile-alloc-inmemory"
    ;;
  *)
    echo "Usage: $0 apache|inmemory" >&2
    exit 2
    ;;
esac
mkdir -p /home/coder/Projects/venice/${out_dir}
export JAVA_HOME=/export/apps/jdk/JDK-17_0_5-msft
JAR=/home/coder/Projects/venice/internal/venice-test-common/build/libs/venice-test-common-jmh.jar
PROF_LIB=/export/apps/async-profiler/build/libasyncProfiler.so
exec "$JAVA_HOME/bin/java" -jar "$JAR" \
  -p workloadType=PUT -wi 1 -w 20s -i 1 -r 20s -f 1 \
  -prof "async:libPath=${PROF_LIB};event=alloc;output=flamegraph;dir=/home/coder/Projects/venice/${out_dir}" \
  -jvmArgs "-Xms32G -Xmx32G \
    -Dvenice.server.aa.bottleneck.instrumentation.enabled=true \
    -Dvenice.server.aa.dcr.merge.instrumentation.enabled=true \
    -Dvenice.server.aa.rmd.timestamp.cache.enabled=true \
    -Dvenice.server.aa.rmd.timestamp.cache.bloom.authoritative=false \
    -Dphase3.producers.per.region=2 \
    $extra_flag" \
  com.linkedin.venice.benchmark.ActiveActiveIngestionBenchmark.benchmarkAAIngestion
