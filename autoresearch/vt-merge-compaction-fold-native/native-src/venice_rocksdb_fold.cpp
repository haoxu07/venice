// Phase B native filter: bridges rocksdbjni's compaction filter framework into
// our existing Java fold logic.
//
// Architecture (per GOAL §4):
//   1. Java VeniceConcatFoldFilterFactory extends rocksdbjni's
//      AbstractCompactionFilterFactory; its createCompactionFilter(Context)
//      returns a VeniceConcatFoldFilter (subclass of AbstractCompactionFilter).
//   2. The factory's private bridge passes filter.nativeHandle_ (a long) back
//      to rocksdbjni's CompactionFilterFactoryJniCallback, which casts it to
//      `rocksdb::CompactionFilter*` and wraps in unique_ptr.
//   3. RocksDB calls FilterV2 on each compaction-output value at compaction
//      time. FilterV2 here is dispatched virtually through the vtable defined
//      by `compaction_filter.h v9.11.2` — same header rocksdbjni was built with
//      so the layout matches.
//   4. FilterV2 wraps the value as a DirectByteBuffer and calls back into
//      Java's VeniceConcatFoldNativeCallback.foldConcatBlob, which invokes the
//      well-tested ConcatBlobParser + MaterializingFoldContext.foldOperands.
//   5. If Java returns a byte[], C++ writes it into *new_value and returns
//      kChangeValue. If null, returns kKeep.
//
// ABI compatibility: rocksdbjni 9.11.2 statically links RocksDB into its own
// .so. Our C++ filter compiles against the SAME compaction_filter.h v9.11.2
// (downloaded out-of-band — the rocksdbjni jar does not ship .h files). As
// long as the C++ ABI matches (libstdc++ CXX11 ABI), virtual dispatch on
// FilterV2 works through the vtable. The std::unique_ptr<CompactionFilter>
// deletion path goes through CompactionFilter's virtual destructor (defined
// inline in the header), so cross-.so deletion is safe.

#include <jni.h>
#include "rocksdb/compaction_filter.h"
#include <atomic>
#include <cstring>
#include <memory>
#include <string>

namespace venice {

// Cached JNI handles populated by Java_..._nativeRegisterCallback. Set once
// during partition open; read by every compaction-thread FilterV2 call.
static JavaVM* g_jvm = nullptr;
static jobject g_callback_obj = nullptr;       // global ref to Java callback
static jmethodID g_fold_method = nullptr;      // foldConcatBlob(ByteBuffer)[B

// Counters for sanity / observability — read out via nativeReadCounters.
static std::atomic<uint64_t> g_calls{0};
static std::atomic<uint64_t> g_change{0};
static std::atomic<uint64_t> g_keep{0};
static std::atomic<uint64_t> g_exceptions{0};

class VeniceConcatFoldFilter : public rocksdb::CompactionFilter {
 public:
  VeniceConcatFoldFilter() = default;
  ~VeniceConcatFoldFilter() override = default;

  Decision FilterV2(int /*level*/, const rocksdb::Slice& /*key*/,
                    ValueType value_type,
                    const rocksdb::Slice& existing_value,
                    std::string* new_value,
                    std::string* /*skip_until*/) const override {
    g_calls.fetch_add(1, std::memory_order_relaxed);
    // Only fold KIND_BASE / KIND_OPERAND values; leave merge-operand records
    // alone so RocksDB's StringAppendOperator can still concatenate them.
    if (value_type != ValueType::kValue) {
      g_keep.fetch_add(1, std::memory_order_relaxed);
      return Decision::kKeep;
    }
    if (g_jvm == nullptr || g_callback_obj == nullptr || g_fold_method == nullptr) {
      g_keep.fetch_add(1, std::memory_order_relaxed);
      return Decision::kKeep;
    }

    JNIEnv* env = nullptr;
    bool attached = false;
    int gv = g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_8);
    if (gv == JNI_EDETACHED) {
      if (g_jvm->AttachCurrentThreadAsDaemon(reinterpret_cast<void**>(&env), nullptr) != JNI_OK) {
        g_keep.fetch_add(1, std::memory_order_relaxed);
        return Decision::kKeep;
      }
      attached = true;
    } else if (gv != JNI_OK) {
      g_keep.fetch_add(1, std::memory_order_relaxed);
      return Decision::kKeep;
    }

    // Mirror the production hot-path: copy the slice into a thread-local
    // staging buffer that we wrap as a DirectByteBuffer (the slice's data
    // pointer is RocksDB-owned and may be invalidated when we cross the
    // JNI boundary; the staging copy keeps it stable for the Java callback).
    thread_local std::string staging;
    staging.assign(existing_value.data(), existing_value.size());
    jobject buf = env->NewDirectByteBuffer(
        const_cast<char*>(staging.data()), static_cast<jlong>(staging.size()));

    jbyteArray result = static_cast<jbyteArray>(
        env->CallObjectMethod(g_callback_obj, g_fold_method, buf));
    env->DeleteLocalRef(buf);

    if (env->ExceptionCheck()) {
      env->ExceptionDescribe();
      env->ExceptionClear();
      g_exceptions.fetch_add(1, std::memory_order_relaxed);
      // Don't surface the exception to RocksDB — keep the value untouched so
      // a Java-side bug in the fold path doesn't take the engine down.
      g_keep.fetch_add(1, std::memory_order_relaxed);
      return Decision::kKeep;
    }

    if (result == nullptr) {
      g_keep.fetch_add(1, std::memory_order_relaxed);
      return Decision::kKeep;
    }

    jsize out_len = env->GetArrayLength(result);
    new_value->assign(static_cast<size_t>(out_len), 0);
    env->GetByteArrayRegion(result, 0, out_len,
                            reinterpret_cast<jbyte*>(&(*new_value)[0]));
    env->DeleteLocalRef(result);
    g_change.fetch_add(1, std::memory_order_relaxed);

    // Note: we don't detach attached threads on the hot path — RocksDB
    // compaction threads are long-lived; AttachCurrentThreadAsDaemon means
    // they don't block JVM shutdown. The first attach is the only cost.
    (void)attached;
    return Decision::kChangeValue;
  }

  const char* Name() const override { return "VeniceConcatFoldFilter"; }
};

}  // namespace venice

// JNI entrypoints. Symbol names must match the Java side
// com.linkedin.davinci.store.rocksdb.merge.jnibridge.VeniceConcatFoldNative.
extern "C" {

// Allocate a new filter. Returns its raw pointer as a jlong (matches the
// rocksdbjni AbstractCompactionFilter.nativeHandle_ contract).
JNIEXPORT jlong JNICALL
Java_com_linkedin_davinci_store_rocksdb_merge_jnibridge_VeniceConcatFoldNative_nativeCreateFilter(
    JNIEnv* /*env*/, jclass /*clazz*/) {
  auto* f = new venice::VeniceConcatFoldFilter();
  return reinterpret_cast<jlong>(static_cast<rocksdb::CompactionFilter*>(f));
}

// Register the global Java callback object + cached method id.
JNIEXPORT void JNICALL
Java_com_linkedin_davinci_store_rocksdb_merge_jnibridge_VeniceConcatFoldNative_nativeRegisterCallback(
    JNIEnv* env, jclass /*clazz*/, jobject callback) {
  env->GetJavaVM(&venice::g_jvm);
  if (venice::g_callback_obj != nullptr) {
    env->DeleteGlobalRef(venice::g_callback_obj);
  }
  venice::g_callback_obj = env->NewGlobalRef(callback);
  jclass cb_class = env->GetObjectClass(callback);
  venice::g_fold_method =
      env->GetMethodID(cb_class, "foldConcatBlob", "(Ljava/nio/ByteBuffer;)[B");
  env->DeleteLocalRef(cb_class);
}

// Read the call counters as a long[4]: {calls, change, keep, exceptions}.
JNIEXPORT jlongArray JNICALL
Java_com_linkedin_davinci_store_rocksdb_merge_jnibridge_VeniceConcatFoldNative_nativeReadCounters(
    JNIEnv* env, jclass /*clazz*/) {
  jlongArray out = env->NewLongArray(4);
  jlong vals[4] = {
      static_cast<jlong>(venice::g_calls.load(std::memory_order_relaxed)),
      static_cast<jlong>(venice::g_change.load(std::memory_order_relaxed)),
      static_cast<jlong>(venice::g_keep.load(std::memory_order_relaxed)),
      static_cast<jlong>(venice::g_exceptions.load(std::memory_order_relaxed))};
  env->SetLongArrayRegion(out, 0, 4, vals);
  return out;
}

// Reset counters (used by tests).
JNIEXPORT void JNICALL
Java_com_linkedin_davinci_store_rocksdb_merge_jnibridge_VeniceConcatFoldNative_nativeResetCounters(
    JNIEnv* /*env*/, jclass /*clazz*/) {
  venice::g_calls.store(0, std::memory_order_relaxed);
  venice::g_change.store(0, std::memory_order_relaxed);
  venice::g_keep.store(0, std::memory_order_relaxed);
  venice::g_exceptions.store(0, std::memory_order_relaxed);
}

// Direct call into FilterV2 — used by NativeFilterByteEquivalenceTest to
// exercise the filter without standing up a full RocksDB. Takes a value
// blob, returns:
//   - null  → kKeep
//   - byte[] → kChangeValue with that result
JNIEXPORT jbyteArray JNICALL
Java_com_linkedin_davinci_store_rocksdb_merge_jnibridge_VeniceConcatFoldNative_nativeInvokeFilter(
    JNIEnv* env, jclass /*clazz*/, jlong filter_handle, jbyteArray value) {
  auto* filter = reinterpret_cast<rocksdb::CompactionFilter*>(filter_handle);
  jsize len = env->GetArrayLength(value);
  std::string in;
  in.assign(len, 0);
  env->GetByteArrayRegion(value, 0, len, reinterpret_cast<jbyte*>(&in[0]));

  rocksdb::Slice key("k", 1);
  rocksdb::Slice existing(in);
  std::string new_value;
  std::string skip_until;
  auto decision = filter->FilterV2(0, key,
                                   rocksdb::CompactionFilter::ValueType::kValue,
                                   existing, &new_value, &skip_until);
  if (decision == rocksdb::CompactionFilter::Decision::kChangeValue) {
    jbyteArray out = env->NewByteArray(static_cast<jsize>(new_value.size()));
    env->SetByteArrayRegion(out, 0, static_cast<jsize>(new_value.size()),
                            reinterpret_cast<const jbyte*>(new_value.data()));
    return out;
  }
  return nullptr;  // kKeep / others
}

// Free the filter directly (used by tests that don't go through rocksdbjni's
// dispose path).
JNIEXPORT void JNICALL
Java_com_linkedin_davinci_store_rocksdb_merge_jnibridge_VeniceConcatFoldNative_nativeDeleteFilter(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong filter_handle) {
  auto* filter = reinterpret_cast<rocksdb::CompactionFilter*>(filter_handle);
  delete filter;
}

}  // extern "C"
