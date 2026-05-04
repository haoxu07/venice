package com.linkedin.davinci.store.rocksdb.merge.jnibridge;

import org.rocksdb.AbstractCompactionFilter;
import org.rocksdb.AbstractCompactionFilterFactory;


/**
 * Factory for {@link VeniceConcatFoldFilter}. RocksDB calls
 * {@link #createCompactionFilter} once per compaction; rocksdbjni's
 * {@code AbstractCompactionFilterFactory} private bridge then reads the
 * filter's {@code nativeHandle_} (a long) and reinterprets it as a
 * {@code rocksdb::CompactionFilter*} on the C++ side.
 *
 * <p>The {@link VeniceConcatFoldNativeCallback} singleton must be
 * registered via {@link VeniceConcatFoldNative#nativeRegisterCallback}
 * before the first compaction runs; otherwise the filter returns KEEP for
 * every value (safe but throughput-degrading degradation).
 */
public class VeniceConcatFoldFilterFactory extends AbstractCompactionFilterFactory<VeniceConcatFoldFilter> {
  @Override
  public VeniceConcatFoldFilter createCompactionFilter(AbstractCompactionFilter.Context context) {
    return new VeniceConcatFoldFilter();
  }

  @Override
  public String name() {
    return "VeniceConcatFoldFilterFactory";
  }
}
