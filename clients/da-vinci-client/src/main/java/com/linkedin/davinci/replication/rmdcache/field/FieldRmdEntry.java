package com.linkedin.davinci.replication.rmdcache.field;

import com.linkedin.venice.schema.rmd.v1.CollectionRmdTimestamp;
import javax.annotation.concurrent.NotThreadSafe;
import org.apache.avro.generic.GenericRecord;


/**
 * Per-field replication-metadata cache entry. Held by {@link FieldLevelRmdCache} for each
 * (keyHash, fieldOrdinal) pair in the per-field-ts mode (mode 1).
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code topLevelFieldTs} — the field's logical timestamp:
 *     <ul>
 *       <li>For a scalar field set by a PUT: the PUT's timestamp.</li>
 *       <li>For a scalar field at schema-default at the most recent PUT (Gotcha #2 from
 *           {@code autoresearch/vt-merge-cross-dc-fix/GOAL.md} §4.3): {@link Long#MIN_VALUE},
 *           so subsequent UPDATEs win.</li>
 *       <li>For a collection field with non-empty contents at PUT: the PUT's timestamp.</li>
 *       <li>For a collection field empty at PUT (Gotcha #1 from GOAL §4.2): {@link Long#MIN_VALUE},
 *           so subsequent UPDATEs win and establish per-element-ts naturally.</li>
 *     </ul>
 *   </li>
 *   <li>{@code rmdSubtree} — for collection fields only, the full V1 collection-RMD record
 *       (top-level-ts, per-element timestamps, deleted-element identities + timestamps).
 *       Mutated in place by the V2 algorithm during fold. Null for scalar fields.</li>
 *   <li>{@code populatedByPut} — whether the most recent PUT explicitly set this field
 *       (vs leaving it at schema default). When false, the entry has no DCR floor — subsequent
 *       UPDATEs apply regardless of timestamp comparison against this entry's
 *       {@code topLevelFieldTs}. This is the §4.3 gotcha lifted to a scalar bit at the cache-
 *       entry level for uniformity (the collection-side {@code putOnlyPartLength} already
 *       encodes this partially for collection fields).</li>
 * </ul>
 *
 * <p>Mutable to allow in-place updates by the merge-path and fold-path code, similar to how
 * {@link CollectionRmdTimestamp} is mutated in place by V2.
 */
@NotThreadSafe
public final class FieldRmdEntry {
  private long topLevelFieldTs;
  private CollectionRmdTimestamp<Object> rmdSubtree;
  private GenericRecord rmdSubtreeRecord;
  private boolean populatedByPut;

  /**
   * Construct a scalar-field entry.
   *
   * @param topLevelFieldTs the field's logical timestamp ({@link Long#MIN_VALUE} for default-at-PUT)
   * @param populatedByPut  whether the most recent PUT explicitly set this field
   */
  public static FieldRmdEntry forScalar(long topLevelFieldTs, boolean populatedByPut) {
    FieldRmdEntry e = new FieldRmdEntry();
    e.topLevelFieldTs = topLevelFieldTs;
    e.rmdSubtree = null;
    e.populatedByPut = populatedByPut;
    return e;
  }

  /**
   * Construct a collection-field entry.
   *
   * @param topLevelFieldTs the field's logical timestamp ({@link Long#MIN_VALUE} for empty-at-PUT)
   * @param populatedByPut  whether the most recent PUT populated this collection with elements
   * @param rmdSubtree      the full collection RMD subtree; ownership transfers to this entry
   */
  public static FieldRmdEntry forCollection(
      long topLevelFieldTs,
      boolean populatedByPut,
      CollectionRmdTimestamp<Object> rmdSubtree,
      GenericRecord rmdSubtreeRecord) {
    if (rmdSubtree == null) {
      throw new IllegalArgumentException("Collection field RMD subtree must be non-null");
    }
    FieldRmdEntry e = new FieldRmdEntry();
    e.topLevelFieldTs = topLevelFieldTs;
    e.rmdSubtree = rmdSubtree;
    e.rmdSubtreeRecord = rmdSubtreeRecord;
    e.populatedByPut = populatedByPut;
    return e;
  }

  public long getTopLevelFieldTs() {
    return topLevelFieldTs;
  }

  public void setTopLevelFieldTs(long topLevelFieldTs) {
    this.topLevelFieldTs = topLevelFieldTs;
  }

  public CollectionRmdTimestamp<Object> getRmdSubtree() {
    return rmdSubtree;
  }

  public void setRmdSubtree(CollectionRmdTimestamp<Object> rmdSubtree) {
    this.rmdSubtree = rmdSubtree;
  }

  public GenericRecord getRmdSubtreeRecord() {
    return rmdSubtreeRecord;
  }

  public void setRmdSubtreeRecord(GenericRecord rmdSubtreeRecord) {
    this.rmdSubtreeRecord = rmdSubtreeRecord;
  }

  public boolean isPopulatedByPut() {
    return populatedByPut;
  }

  public void setPopulatedByPut(boolean populatedByPut) {
    this.populatedByPut = populatedByPut;
  }

  public boolean isCollection() {
    return rmdSubtree != null;
  }

  /**
   * Whether an incoming UPDATE with the given operand timestamp wins DCR against this entry.
   *
   * <p>Honors the {@code populatedByPut} bit: if false, the entry has no DCR floor — any
   * incoming UPDATE wins. Otherwise standard {@code operandTs > entry.ts} comparison applies.
   *
   * <p>For collection fields, this checks the top-level field timestamp only. Element-level DCR
   * is handled by the V2 algorithm using the {@link #getRmdSubtree()} RMD.
   */
  public boolean newWins(long operandTs) {
    if (!populatedByPut) {
      return true;
    }
    return operandTs > topLevelFieldTs;
  }

  @Override
  public String toString() {
    return "FieldRmdEntry{topLevelFieldTs=" + topLevelFieldTs + ", populatedByPut=" + populatedByPut + ", isCollection="
        + isCollection() + '}';
  }
}
