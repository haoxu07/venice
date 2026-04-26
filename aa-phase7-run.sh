#!/usr/bin/env bash
# Phase 7 canonical runner. Pass extra `-D...` args via $EXTRA_JVM_ARGS env var.
# Usage:
#   EXP_NAME=baseline EXTRA_JVM_ARGS="" ./aa-phase7-run.sh
#   EXP_NAME=expA EXTRA_JVM_ARGS="-Dvenice.kafka.linger.ms=5" ./aa-phase7-run.sh

set -euo pipefail

cd /home/coder/Projects/venice

EXP_NAME="${EXP_NAME:?must set EXP_NAME}"
EXTRA_JVM_ARGS="${EXTRA_JVM_ARGS:-}"

JAR="/home/coder/Projects/venice/internal/venice-test-common/build/libs/venice-test-common-jmh.jar"
LOG="/home/coder/Projects/venice/aa-phase7-${EXP_NAME}.log"

JVM_ARGS_BASE=(
  "-Xms32G" "-Xmx32G"
  "-Dvenice.server.aa.bottleneck.instrumentation.enabled=true"
  "-Dvenice.server.aa.dcr.merge.instrumentation.enabled=true"
  "-Dvenice.server.aa.rmd.timestamp.cache.enabled=true"
  "-Dvenice.server.aa.rmd.timestamp.cache.bloom.authoritative=false"
  "-Dvenice.server.aa.leader.other.instrumentation.enabled=true"
  "-Dvenice.server.aa.kafka.pipeline.instrumentation.enabled=true"
  "-Dvenice.server.aa.kafka.broker.jmx.enabled=true"
  "-Dphase3.producers.per.region=2"
)
# Append per-experiment extras as additional space-separated tokens.
JVM_ARGS_STR="${JVM_ARGS_BASE[*]} ${EXTRA_JVM_ARGS}"

export JAVA_HOME=/export/apps/jdk/JDK-17_0_5-msft

echo "=== Phase 7 ${EXP_NAME} ==="
echo "JVM_ARGS: ${JVM_ARGS_STR}"
echo "JAR: ${JAR}"
echo "LOG: ${LOG}"

"${JAVA_HOME}/bin/java" -jar "${JAR}" \
  ActiveActiveIngestionBenchmark \
  -p workloadType=PUT -wi 2 -w 20s -i 2 -r 20s -f 1 \
  -jvmArgs "${JVM_ARGS_STR}" \
  -prof gc \
  > "${LOG}" 2>&1
