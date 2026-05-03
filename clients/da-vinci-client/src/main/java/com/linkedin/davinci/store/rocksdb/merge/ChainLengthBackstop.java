package com.linkedin.davinci.store.rocksdb.merge;

import com.linkedin.davinci.store.rocksdb.merge.MaterializingFoldContext.FoldOnlyResult;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


/**
 * Per-call chain-length backstop for the VT-merge experiment. Invoked from the materializing
 * storage partitions on each {@code merge(key, operand)} BEFORE the actual {@code rocksDB.merge}
 * call. If the current on-disk operand chain for {@code key} has reached
 * {@code maxChainLength}, the backstop reads the raw blob, folds it via
 * {@link MaterializingFoldContext}, and writes back a single base PUT — replacing the entire
 * chain. The new operand is then merged on top of the freshly-PUT base, restoring chain depth
 * to exactly 1.
 *
 * <p>Bounds operand-chain length under any sustained workload regardless of RocksDB compaction
 * cadence: chain depth at the time of the {@code N}-th merge is at most {@code maxChainLength}
 * before the merge, and at most {@code maxChainLength + 1} immediately after — assuming the
 * caller chains exactly one merge per call. RocksDB compaction may further consolidate the
 * chain in the background.
 *
 * <p><b>Threading:</b> The caller's {@code merge()} method is {@code synchronized} on the
 * partition. The backstop's read-fold-put-merge sequence inherits that lock; no inter-merge
 * race window exists. {@link #maybeBackstop} is intentionally a static stateless helper —
 * configuration lives in the partition's {@code volatile} fields, which the partition
 * dereferences on each call.
 *
 * <p><b>Failure semantics:</b> any exception during the read or fold is logged and swallowed,
 * and the merge proceeds without the backstop. The cost is one unbounded chain on this key
 * until the next merge; correctness is preserved (the merge still applies the operand and the
 * read path will fold the chain on next {@code get}).
 *
 * <p>Per the VT-merge experiment {@code GOAL.md} §3 Phase B.
 */
public final class ChainLengthBackstop {
  private static final Logger LOGGER = LogManager.getLogger(ChainLengthBackstop.class);

  private ChainLengthBackstop() {
    // utility class
  }

  /**
   * If the current chain depth at {@code key} has reached {@code maxChainLength}, fold the
   * chain and write a base PUT replacing it. Returns {@code true} iff the backstop fired
   * (chain was folded + PUT). Returns {@code false} if the backstop is disabled, the key has
   * no chain or a short-enough chain, the fold context is missing, or the fold failed.
   *
   * @param storeNameAndVersion store-version key for the {@link MaterializingFoldContextRegistry}
   *     lookup; usually the partition's {@code storeNameAndVersion}.
   * @param maxChainLength backstop threshold; {@code <= 0} disables the backstop.
   * @param rawReader supplies the current raw on-disk blob for {@code key} (i.e. the
   *     bypass-the-fold accessor). May return {@code null} if the key is absent.
   * @param framedPutWriter writes the framed base PUT (the bytes already include
   *     {@code [schemaId][0x00][len][avro]}). The caller is responsible for the
   *     re-entry guard if needed.
   * @return {@code true} iff the backstop fired.
   */
  public static boolean maybeBackstop(
      String storeNameAndVersion,
      int maxChainLength,
      Supplier<byte[]> rawReader,
      Consumer<byte[]> framedPutWriter) {
    if (maxChainLength <= 0) {
      return false;
    }
    byte[] raw;
    try {
      raw = rawReader.get();
    } catch (Throwable t) {
      LOGGER.warn("VT-merge chain-backstop: raw read failed; skipping backstop", t);
      return false;
    }
    if (raw == null || raw.length == 0) {
      return false;
    }
    int chainDepth;
    ConcatBlobParser.Parsed parsed;
    try {
      parsed = ConcatBlobParser.parse(raw);
      chainDepth = parsed.getOperands().size();
    } catch (Throwable t) {
      // Malformed blob (legacy data or partial state). Don't fight it; let the existing read
      // path return raw bytes and the merge proceed.
      LOGGER.debug("VT-merge chain-backstop: parse failed; skipping backstop", t);
      return false;
    }
    if (chainDepth < maxChainLength) {
      return false;
    }
    MaterializingFoldContext foldContext = MaterializingFoldContextRegistry.get(storeNameAndVersion);
    if (foldContext == null) {
      // No fold context registered (e.g. boot order, or the partition belongs to a store-version
      // not being ingested by this server). Best-effort: skip the backstop and let the chain
      // grow until either compaction fires or a fold context registers.
      LOGGER.debug(
          "VT-merge chain-backstop: no fold context registered for storeVersion={}; skipping",
          storeNameAndVersion);
      return false;
    }
    byte[] reframed;
    try {
      List<byte[]> operands = parsed.getOperands();
      if (parsed.hasBase()) {
        // base + N operands → fold operands onto base, reframe as a single base.
        byte[] foldedAvro = foldContext.foldOperands(parsed.getSchemaId(), parsed.getBase(), operands);
        if (foldedAvro == null) {
          // WC delete: the entire chain folds to a tombstone. Nothing to PUT — but we MUST
          // delete the existing on-disk blob so the next read sees a missing key (matching the
          // semantics of fold-on-read returning null). However, the materializing partition's
          // delete pathway is not directly in scope for the backstop, and a follower-side delete
          // may not be the right semantics either (deletes are leader-controlled in the AA
          // pipeline). For Phase B, we conservatively skip the backstop on a tombstone fold —
          // the next read still observes the tombstone via the read-path fold, and the new
          // operand we're about to merge will land on the existing concat blob. This preserves
          // correctness; it just means a tombstone-folding chain doesn't get its disk footprint
          // reclaimed by the backstop. The same chain-depth bound still holds because the next
          // backstop fire (after another maxChainLength operands) will re-evaluate.
          LOGGER.debug(
              "VT-merge chain-backstop: fold result is null (tombstone); skipping PUT for storeVersion={}",
              storeNameAndVersion);
          return false;
        }
        reframed = ConcatBlobParser.frameBase(parsed.getSchemaId(), foldedAvro);
      } else {
        // Operand-only chain (no base PUT yet for this key). Use foldOperandOnly.
        FoldOnlyResult result = foldContext.foldOperandOnly(operands);
        if (result.getBytes() == null) {
          LOGGER.debug(
              "VT-merge chain-backstop: foldOperandOnly result is null (tombstone); skipping PUT for storeVersion={}",
              storeNameAndVersion);
          return false;
        }
        reframed = ConcatBlobParser.frameBase(result.getSchemaId(), result.getBytes());
      }
    } catch (Throwable t) {
      // Fold failed (e.g. unknown schema id, deserialization error). Don't fail the merge —
      // log and continue without the backstop. The next read still folds correctly.
      LOGGER.warn("VT-merge chain-backstop: fold failed; skipping backstop and continuing merge", t);
      return false;
    }

    try {
      framedPutWriter.accept(reframed);
    } catch (Throwable t) {
      LOGGER.warn("VT-merge chain-backstop: framed PUT failed; chain depth not reset", t);
      return false;
    }
    LOGGER.debug(
        "VT-merge chain-backstop: fired for storeVersion={} chainDepth={} maxChainLength={}",
        storeNameAndVersion,
        chainDepth,
        maxChainLength);
    return true;
  }
}
