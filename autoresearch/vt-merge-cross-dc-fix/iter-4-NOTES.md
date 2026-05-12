# iter-4 NOTES — Schema evolution support in fold path

## Hypothesis

iter-3 passed the cross-DC per-field DCR case (line 265 now passes) but the OnFields test still fails at line 314:
schema-evolution case where dc-1 PUT key3 with V1 schema, V2 is registered, dc-0 UPDATE with V2 WC sets F3. The expected
result is a V2 SUPERSET record but the actual result is missing F3 — `field3` field is null on the retrieved record.

Root cause: my fold path used `baseSchemaId` (V1) as the reader value schema throughout. The operand carries V2
valueSchemaId. The result record was encoded with V1 schema → no F3 field.

## Fix

1. Scan all operands at fold start to find the max value schemaId (across base + operands).
2. Use this as the READER schema for both:
   - Base value deserialization (Avro handles schema evolution: missing fields → defaults).
   - V2 algorithm reader schema.
   - Final result serialization (already done internally by V2's serializeValueRecord).
3. Add a new `FoldResult { schemaId, bytes }` return type so MaterializingFraming can prepend the correct schemaId.
4. Update MaterializingFraming.foldFramedBaseAndOperands to use the new schemaId.

## Result

(filled in after test runs)
