package com.linkedin.davinci.store.rocksdb.merge.jnibridge;

import org.rocksdb.AbstractCompactionFilter;
import org.rocksdb.Slice;


/**
 * Java thin wrapper around the native {@code VeniceConcatFoldFilter}.
 * Holds the native handle ({@code rocksdb::CompactionFilter*}) so
 * rocksdbjni's {@code AbstractCompactionFilterFactory.createCompactionFilter}
 * private bridge can return it via {@code nativeHandle_}.
 *
 * <p>The native filter compiles against rocksdb v9.11.2 headers — the same
 * ABI rocksdbjni 9.11.2 was built with. Virtual dispatch on
 * {@code FilterV2} is resolved through the vtable layout defined in
 * {@code compaction_filter.h v9.11.2}.
 */
public class VeniceConcatFoldFilter extends AbstractCompactionFilter<Slice> {
  public VeniceConcatFoldFilter() {
    super(VeniceConcatFoldNative.nativeCreateFilter());
  }
}
