// Phase A microbenchmark — minimum-viable JNI round-trip.
//
// Mirrors the production hot-path shape that the real CompactionFilter would use:
//   1. C++ receives a (ptr, len) value (just like rocksdb::Slice).
//   2. C++ wraps it as a DirectByteBuffer (zero-copy).
//   3. C++ calls the Java callback method via CallObjectMethod.
//   4. Java returns a byte[] (the would-be folded bytes) or null (KEEP).
//   5. C++ copies bytes into a std::string-equivalent output buffer
//      (mirrors `new_value->assign(...)`) so we measure the realistic copy cost.
//
// We do NOT link against rocksdb here — Phase A only measures JNI round-trip cost.
// Phase B will add the rocksdb::CompactionFilter base class wiring; the per-call
// cost measured here is a lower bound on Phase B's per-call cost.

#include <jni.h>
#include <string>
#include <cstring>
#include <vector>

// Cached globals so we measure the steady-state cost (per-call cost), not the
// first-call init cost.
static JavaVM* g_jvm = nullptr;
static jobject g_callback = nullptr;        // global ref to the Java callback object
static jmethodID g_fold_method = nullptr;   // cached method id

extern "C" JNIEXPORT void JNICALL
Java_com_linkedin_davinci_store_rocksdb_merge_jnibridge_VeniceJniBridge_nativeInit(
    JNIEnv* env, jclass /*clazz*/, jobject callback, jstring methodName, jstring methodSig) {
  env->GetJavaVM(&g_jvm);
  if (g_callback != nullptr) {
    env->DeleteGlobalRef(g_callback);
  }
  g_callback = env->NewGlobalRef(callback);

  jclass cbClass = env->GetObjectClass(callback);
  const char* nameC = env->GetStringUTFChars(methodName, nullptr);
  const char* sigC = env->GetStringUTFChars(methodSig, nullptr);
  g_fold_method = env->GetMethodID(cbClass, nameC, sigC);
  env->ReleaseStringUTFChars(methodName, nameC);
  env->ReleaseStringUTFChars(methodSig, sigC);
  env->DeleteLocalRef(cbClass);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_linkedin_davinci_store_rocksdb_merge_jnibridge_VeniceJniBridge_nativeRoundTrip(
    JNIEnv* env, jclass /*clazz*/, jbyteArray input) {
  // Read the input into a transient native buffer to mirror the rocksdb::Slice
  // (ptr, len) the real filter receives.
  jsize inLen = env->GetArrayLength(input);
  // Use GetPrimitiveArrayCritical — the rocksdb::Slice path inside the real
  // filter would have a (data, size) pair already; the closest analog here.
  void* inPtr = env->GetPrimitiveArrayCritical(input, nullptr);
  if (inPtr == nullptr) {
    return -1;
  }
  // Wrap as DirectByteBuffer for the zero-copy path back to Java.
  // We can't use the critical pointer for NewDirectByteBuffer (it must remain
  // pinned during the Java call, which violates the critical contract). Copy
  // into a per-call thread-local heap buffer to make the bytes JNI-safe.
  thread_local std::vector<char> staging;
  if (staging.size() < (size_t)inLen) staging.resize(inLen);
  std::memcpy(staging.data(), inPtr, inLen);
  env->ReleasePrimitiveArrayCritical(input, inPtr, JNI_ABORT);

  jobject buf = env->NewDirectByteBuffer(staging.data(), inLen);
  jbyteArray result = (jbyteArray) env->CallObjectMethod(g_callback, g_fold_method, buf);
  env->DeleteLocalRef(buf);

  if (env->ExceptionCheck()) {
    env->ExceptionClear();
    return -2;
  }

  if (result == nullptr) {
    // KEEP — return the input length to confirm the round trip happened.
    return inLen;
  }

  // Mirror the real filter's CHANGE_VALUE path: copy result bytes into a
  // std::string-equivalent output to measure the realistic byte copy.
  jsize outLen = env->GetArrayLength(result);
  std::string newValue;
  newValue.assign(outLen, 0);
  env->GetByteArrayRegion(result, 0, outLen, reinterpret_cast<jbyte*>(&newValue[0]));
  env->DeleteLocalRef(result);
  return outLen;
}

// Stress-test path: invoke N times in a tight loop entirely in C++ so the JMH
// overhead is amortized. Useful for measuring stable per-call cost when the
// JMH measurement infra itself dominates.
extern "C" JNIEXPORT jlong JNICALL
Java_com_linkedin_davinci_store_rocksdb_merge_jnibridge_VeniceJniBridge_nativeRoundTripLoop(
    JNIEnv* env, jclass /*clazz*/, jbyteArray input, jint iterations) {
  jsize inLen = env->GetArrayLength(input);
  // Allocate the staging buffer once.
  std::vector<char> staging(inLen);
  // Critical-copy once and reuse — measures the steady-state per-call cost
  // through the Java callback only.
  void* inPtr = env->GetPrimitiveArrayCritical(input, nullptr);
  if (inPtr == nullptr) return -1;
  std::memcpy(staging.data(), inPtr, inLen);
  env->ReleasePrimitiveArrayCritical(input, inPtr, JNI_ABORT);

  jlong totalBytes = 0;
  for (jint i = 0; i < iterations; i++) {
    jobject buf = env->NewDirectByteBuffer(staging.data(), inLen);
    jbyteArray result = (jbyteArray) env->CallObjectMethod(g_callback, g_fold_method, buf);
    env->DeleteLocalRef(buf);
    if (env->ExceptionCheck()) {
      env->ExceptionClear();
      return -2;
    }
    if (result == nullptr) {
      totalBytes += inLen;
    } else {
      jsize outLen = env->GetArrayLength(result);
      // Skip the byte copy into a std::string here; we are measuring the JNI
      // hot path only. The byte copy is benchmarked separately by
      // nativeRoundTrip above.
      totalBytes += outLen;
      env->DeleteLocalRef(result);
    }
  }
  return totalBytes;
}

// Exception-handling correctness probe — Java throws, C++ recovers cleanly.
extern "C" JNIEXPORT jint JNICALL
Java_com_linkedin_davinci_store_rocksdb_merge_jnibridge_VeniceJniBridge_nativeProbeException(
    JNIEnv* env, jclass /*clazz*/, jbyteArray input) {
  jsize inLen = env->GetArrayLength(input);
  thread_local std::vector<char> staging;
  if (staging.size() < (size_t)inLen) staging.resize(inLen);
  void* inPtr = env->GetPrimitiveArrayCritical(input, nullptr);
  if (inPtr == nullptr) return -1;
  std::memcpy(staging.data(), inPtr, inLen);
  env->ReleasePrimitiveArrayCritical(input, inPtr, JNI_ABORT);

  jobject buf = env->NewDirectByteBuffer(staging.data(), inLen);
  jbyteArray result = (jbyteArray) env->CallObjectMethod(g_callback, g_fold_method, buf);
  env->DeleteLocalRef(buf);

  if (env->ExceptionCheck()) {
    env->ExceptionClear();
    return -2;  // Reported a Java exception path.
  }
  if (result != nullptr) env->DeleteLocalRef(result);
  return 0;
}
