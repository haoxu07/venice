package com.linkedin.davinci.store.rocksdb.merge;

import com.linkedin.davinci.store.rocksdb.merge.MaterializingFoldContext.OperandContent;
import org.testng.Assert;
import org.testng.annotations.Test;


/**
 * Unit tests for {@link OperandContent} wire-format round-trip. Phase A (rmd2) extended the
 * format to carry the operand's logical timestamp between the schema-id pair and the payload:
 * {@code [valueSchemaId : 4B BE][updateSchemaId : 4B BE][updateOperationTimestamp : 8B BE][payload]}.
 */
public class TestOperandContent {
  @Test
  public void framePreservesAllFields() {
    int valueSchemaId = 7;
    int updateSchemaId = 13;
    long opTs = 12345678901L;
    byte[] payload = { 1, 2, 3, 4, 5 };

    byte[] framed = OperandContent.frame(valueSchemaId, updateSchemaId, opTs, payload);
    Assert.assertEquals(framed.length, OperandContent.HEADER_LENGTH + payload.length);

    OperandContent parsed = OperandContent.parse(framed);
    Assert.assertEquals(parsed.valueSchemaId, valueSchemaId);
    Assert.assertEquals(parsed.updateSchemaId, updateSchemaId);
    Assert.assertEquals(parsed.updateOperationTimestamp, opTs);
    Assert.assertEquals(parsed.payload, payload);
  }

  @Test
  public void frameBackwardCompatOverloadSetsSentinelTs() {
    byte[] payload = { 9, 8, 7 };
    byte[] framed = OperandContent.frame(1, 2, payload);
    OperandContent parsed = OperandContent.parse(framed);
    Assert.assertEquals(parsed.valueSchemaId, 1);
    Assert.assertEquals(parsed.updateSchemaId, 2);
    Assert.assertEquals(
        parsed.updateOperationTimestamp,
        OperandContent.NO_TIMESTAMP_AVAILABLE,
        "Backward-compat overload must set the sentinel");
    Assert.assertEquals(parsed.payload, payload);
  }

  @Test
  public void roundTripsNegativeTs() {
    long opTs = -123456789L;
    byte[] payload = { 42 };
    OperandContent parsed = OperandContent.parse(OperandContent.frame(0, 0, opTs, payload));
    Assert.assertEquals(parsed.updateOperationTimestamp, opTs, "Negative ts must survive round-trip");
  }

  @Test
  public void roundTripsMaxLongTs() {
    OperandContent parsed = OperandContent.parse(OperandContent.frame(0, 0, Long.MAX_VALUE, new byte[] {}));
    Assert.assertEquals(parsed.updateOperationTimestamp, Long.MAX_VALUE);
  }

  @Test
  public void roundTripsEmptyPayload() {
    OperandContent parsed = OperandContent.parse(OperandContent.frame(5, 6, 100L, new byte[] {}));
    Assert.assertEquals(parsed.payload.length, 0);
    Assert.assertEquals(parsed.valueSchemaId, 5);
    Assert.assertEquals(parsed.updateSchemaId, 6);
    Assert.assertEquals(parsed.updateOperationTimestamp, 100L);
  }

  @Test(expectedExceptions = com.linkedin.venice.exceptions.VeniceException.class)
  public void parseRejectsTooShortContent() {
    // 15 bytes is below the 16-byte header length.
    OperandContent.parse(new byte[15]);
  }

  @Test(expectedExceptions = com.linkedin.venice.exceptions.VeniceException.class)
  public void parseRejectsNullContent() {
    OperandContent.parse(null);
  }
}
