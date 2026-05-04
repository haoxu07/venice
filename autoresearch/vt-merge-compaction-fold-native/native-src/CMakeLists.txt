# Phase A + Phase B native build.
#   Phase A target: venice_jni_bridge — measures JNI per-call cost (no rocksdb deps).
#   Phase B target: venice_rocksdb_fold — production CompactionFilter that bridges
#                   to Java via JNI. Compiles against rocksdb v9.11.2 headers
#                   downloaded from the GitHub tag (rocksdbjni-9.11.2.jar does NOT
#                   ship .h files; verified via `unzip -l`).

cmake_minimum_required(VERSION 3.13)
project(venice_native_fold LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

if(NOT DEFINED ENV{JAVA_HOME})
  message(FATAL_ERROR "JAVA_HOME must be set")
endif()
set(JNI_INCLUDE_DIR "$ENV{JAVA_HOME}/include")

# RocksDB v9.11.2 public headers — required by venice_rocksdb_fold.
# Downloaded at configure time from the GitHub tag, since rocksdbjni-9.11.2.jar
# does NOT ship the .h files (verified via `unzip -l`). The headers are NOT
# committed to the repo — they would bloat the diff with 1.8 MB of upstream
# code. Re-runnable / cacheable via the existence check.
set(ROCKSDB_INCLUDE_DIR "${CMAKE_BINARY_DIR}/rocksdb-include")
if(NOT EXISTS "${ROCKSDB_INCLUDE_DIR}/rocksdb/compaction_filter.h")
  message(STATUS "Fetching rocksdb v9.11.2 headers...")
  file(MAKE_DIRECTORY "${CMAKE_BINARY_DIR}/rocksdb-fetch")
  file(DOWNLOAD
    "https://github.com/facebook/rocksdb/archive/refs/tags/v9.11.2.tar.gz"
    "${CMAKE_BINARY_DIR}/rocksdb-fetch/rocksdb-v9.11.2.tgz"
    SHOW_PROGRESS
    TIMEOUT 60
    STATUS DOWNLOAD_STATUS)
  list(GET DOWNLOAD_STATUS 0 STATUS_CODE)
  if(NOT STATUS_CODE EQUAL 0)
    message(FATAL_ERROR "Failed to download rocksdb v9.11.2: ${DOWNLOAD_STATUS}")
  endif()
  execute_process(
    COMMAND ${CMAKE_COMMAND} -E tar xzf "${CMAKE_BINARY_DIR}/rocksdb-fetch/rocksdb-v9.11.2.tgz" rocksdb-9.11.2/include
    WORKING_DIRECTORY "${CMAKE_BINARY_DIR}/rocksdb-fetch"
    RESULT_VARIABLE TAR_RESULT)
  if(NOT TAR_RESULT EQUAL 0)
    message(FATAL_ERROR "Failed to extract rocksdb headers")
  endif()
  file(RENAME "${CMAKE_BINARY_DIR}/rocksdb-fetch/rocksdb-9.11.2/include" "${ROCKSDB_INCLUDE_DIR}")
endif()

# ----- Phase A: minimal JNI bridge (no rocksdb deps) -----
add_library(venice_jni_bridge SHARED venice_jni_bridge.cpp)
target_include_directories(venice_jni_bridge PRIVATE
  ${JNI_INCLUDE_DIR}
  ${JNI_INCLUDE_DIR}/linux
)
target_compile_options(venice_jni_bridge PRIVATE
  -O2 -fvisibility=hidden -fno-exceptions -fno-rtti -Wall -Wextra
)
set_target_properties(venice_jni_bridge PROPERTIES
  CXX_VISIBILITY_PRESET hidden
  VISIBILITY_INLINES_HIDDEN ON
)

# ----- Phase B: production CompactionFilter shim -----
add_library(venice_rocksdb_fold SHARED venice_rocksdb_fold.cpp)
target_include_directories(venice_rocksdb_fold PRIVATE
  ${JNI_INCLUDE_DIR}
  ${JNI_INCLUDE_DIR}/linux
  ${ROCKSDB_INCLUDE_DIR}
)
# Disable RTTI so the C++ compiler does not require typeinfo symbols for the
# rocksdb::Customizable / Configurable base classes — those are hidden inside
# librocksdbjni's .so (verified via nm: _ZTIN7rocksdb12CustomizableE is not
# exported). RocksDB's compaction_filter.h does not use dynamic_cast / typeid,
# so this is safe. The vtable layout is unaffected by -fno-rtti so virtual
# dispatch on FilterV2 still works through the vptr.
target_compile_options(venice_rocksdb_fold PRIVATE
  -O2 -fvisibility=hidden -Wall -Wextra
  -fno-rtti
  # rocksdbjni 9.11.2 was built against the OLD (pre-CXX11) libstdc++ ABI.
  # Verified via nm: exported Customizable::GetOption uses RKSs (legacy
  # std::string), not NSt7__cxx1112basic_string... So we MUST compile with
  # _GLIBCXX_USE_CXX11_ABI=0 to match rocksdbjni's vtable expectations.
  -D_GLIBCXX_USE_CXX11_ABI=0
)
set_target_properties(venice_rocksdb_fold PROPERTIES
  CXX_VISIBILITY_PRESET hidden
  VISIBILITY_INLINES_HIDDEN ON
)
