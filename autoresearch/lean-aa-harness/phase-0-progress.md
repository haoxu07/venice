# Phase 0 Progress — Dependency graph & skeleton

## Approach taken

1. Read `GOAL.md` end-to-end to internalize the harness's purpose, in/out of scope, and Phase 0 deliverables.
   (Note: the prompt referenced a `session-notes.md` at `autoresearch/260419-1758-aa-partial-update-collection-merge/`,
   but that directory does not exist in the working tree — only `autoresearch/lean-aa-harness/GOAL.md` is present.
   I proceeded with `GOAL.md` alone, which contains all the context Phase 0 actually needs; I documented the missing
   path under "Blockers".)
2. Read `clients/da-vinci-client/src/main/java/com/linkedin/davinci/kafka/consumer/StoreIngestionTaskFactory.java`
   end-to-end, enumerating every `public Builder set*()` method on the inner Builder class. There are exactly 25
   such setters (verified via `grep -c`).
3. Cross-referenced with `KafkaStoreIngestionService.java` lines 522-548 to confirm which setters production calls
   (all 25) and to capture what each dependency is wired to in production.
4. For each setter, classified as REAL / STUB / NOOP / MOCK with a written justification, source-of-truth, and
   risk level. Added a 26th entry covering the non-setter-helper `getBlobTransferHelper`, plus a section
   covering the per-task constructor arguments to `getNewIngestionTask(...)` (including the `Lazy<ZKHelixAdmin>`
   escape hatch flagged by Risk #2 in `GOAL.md`).
5. Created `MinimalAAIngestionHarness.java` skeleton with constructor, `start()`, `stop()`,
   `getProducerForRegion(int)`, `readFromVT(int, byte[])` methods. All operational methods throw
   `UnsupportedOperationException("TODO Phase N: ...")`. Added a nested `Config` value type so the public API
   surface is stable from Phase 0 onward.
6. Verified compilation via `./gradlew :internal:venice-test-common:jmhClasses --console=plain`.

## Decisions made

- **`Object` as return type for `getProducerForRegion(int)` for now** — the production producer is
  `VeniceSystemProducer`, but importing it pulls in samza-related dependencies that may not be needed in the
  harness (we may instead expose a thinner producer-like interface in Phase 5). Keeping the return type loose
  in Phase 0 avoids prematurely committing to a contract.
- **Nested `Config` value type instead of separate file** — keeps the Phase 0 deliverable to a single new file,
  matching the constraint "do not modify any file other than the new harness skeleton file".
- **`setMetaStoreWriter` classified as `NOOP` (null), not `MOCK`** — production code at
  `KafkaStoreIngestionService:415` itself sets the field to `null` when meta-system-store is disabled. SIT is
  therefore null-tolerant on this path. Documented inline.
- **`setBlobTransferManagerSupplier` classified as `NOOP` rather than skipped** — the Builder's `getBlobTransferManager()`
  helper at `StoreIngestionTaskFactory.java:343` already null-tolerates a missing supplier, but supplying
  `() -> null` makes the intent explicit and is symmetric with how `setBlobTransferDisabledStores` is handled.
- **Did NOT implement actual `start()/stop()` in this phase** — explicit Phase 0 instruction is "stubs that throw
  UnsupportedOperationException".
- **No HIGH-risk classifications produced** at the SIT-Builder level. The genuinely uncertain dependency
  (`Lazy<ZKHelixAdmin>`) is *not* a Builder setter but a per-task argument; documented separately.

## Blockers encountered

- **Missing session-notes file:** prompt referenced
  `autoresearch/260419-1758-aa-partial-update-collection-merge/session-notes.md`, which does not exist in the
  working tree. Resolution: proceeded with `GOAL.md` alone, which Phase 0 deliverables can be completed against
  without external session notes. No information loss for Phase 0 (which is a pure code-audit + skeleton task).

## Files modified

- `internal/venice-test-common/src/jmh/java/com/linkedin/venice/benchmark/lean/MinimalAAIngestionHarness.java`
  — new skeleton class with stub `start/stop/getProducerForRegion/readFromVT` methods plus a `Config` value type.
- `autoresearch/lean-aa-harness/dep-graph.md` — exhaustive 25-entry dependency catalog covering every
  `StoreIngestionTaskFactory.Builder.set*()` setter, plus per-task constructor argument notes.
- `autoresearch/lean-aa-harness/phase-0-progress.md` — this file.

## Verification I ran

### Command 1: count Builder setters in source

```
grep -c "public Builder set" /home/coder/Projects/venice/clients/da-vinci-client/src/main/java/com/linkedin/davinci/kafka/consumer/StoreIngestionTaskFactory.java
```

Output: `25`

Confirms my dep-graph has all 25 setters covered (entries #1 through #25 in `dep-graph.md`).

### Command 2: cross-check against `KafkaStoreIngestionService`

```
grep -n "\.set" /home/coder/Projects/venice/clients/da-vinci-client/src/main/java/com/linkedin/davinci/kafka/consumer/KafkaStoreIngestionService.java | grep -E "ingestionTaskFactory|builder" -A 30
```

Confirmed all 25 setters are also called by production at `KafkaStoreIngestionService:522-548`. There are no
"orphan" setters that production doesn't use, and no setters production uses that aren't on the Builder.

### Command 3: gradle jmhClasses build

```
./gradlew :internal:venice-test-common:jmhClasses --console=plain
```

Last 20 lines of output:

```
> Task :internal:venice-test-common:integrationTestJar

> Task :internal:venice-test-common:compileJmhJava
Picked up JAVA_TOOL_OPTIONS: -Dfile.encoding=UTF8
Note: Some input files use or override a deprecated API.
Note: Recompile with -Xlint:deprecation for details.
Note: Some input files use unchecked or unsafe operations.
Note: Recompile with -Xlint:unchecked for details.

> Task :internal:venice-test-common:processJmhResources
> Task :internal:venice-test-common:jmhClasses

Deprecated Gradle features were used in this build, making it incompatible with Gradle 8.0.

You can use '--warning-mode all' to show the individual deprecation warnings and determine if they come from your own scripts or plugins.

See https://docs.gradle.org/7.3/userguide/command_line_interface.html#sec:command_line_warnings

BUILD SUCCESSFUL in 9s
43 actionable tasks: 6 executed, 12 from cache, 25 up-to-date
```

The deprecation/unchecked warnings come from pre-existing benchmark sources (not from the new
`MinimalAAIngestionHarness.java`); this was confirmed by inspecting the new file (it has no deprecated APIs and
no unchecked operations). The skeleton compiles cleanly under the `compileJmhJava` task.

### Command 4: verify branch & no unrelated modifications

```
git status; git branch --show-current
```

Output:
- Branch: `haoxu07/aa-bench-jmh-improvements` (correct)
- Untracked files: `autoresearch/` (the new dep-graph.md, GOAL.md, phase-0-progress.md), and the new harness file
  under `internal/venice-test-common/src/jmh/.../lean/MinimalAAIngestionHarness.java`. No unrelated files modified.

## Status

Status: SUCCESS
