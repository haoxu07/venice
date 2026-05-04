# Phase A native build — minimal JNI bridge for round-trip measurement.
#
# This deliberately does NOT depend on rocksdb headers; Phase A measures the
# JNI per-call cost only. Phase B will pull rocksdb's compaction_filter.h
# from the v9.11.2 GitHub tag (already verified reachable from this host) and
# add the rocksdb::CompactionFilter base class wiring.

cmake_minimum_required(VERSION 3.13)
project(venice_jni_bridge LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

if(NOT DEFINED ENV{JAVA_HOME})
  message(FATAL_ERROR "JAVA_HOME must be set")
endif()
set(JNI_INCLUDE_DIR "$ENV{JAVA_HOME}/include")

add_library(venice_jni_bridge SHARED venice_jni_bridge.cpp)
target_include_directories(venice_jni_bridge PRIVATE
  ${JNI_INCLUDE_DIR}
  ${JNI_INCLUDE_DIR}/linux
)
target_compile_options(venice_jni_bridge PRIVATE
  -O2 -fvisibility=hidden -fno-exceptions -Wall -Wextra
  -fno-rtti
)
# Export only the JNI symbols. -fvisibility=hidden hides the rest.
set_target_properties(venice_jni_bridge PROPERTIES
  CXX_VISIBILITY_PRESET hidden
  VISIBILITY_INLINES_HIDDEN ON
)

# Use JNICALL convention for symbol export — JNIEXPORT in source already does
# the right thing.
