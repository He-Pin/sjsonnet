# Materializer render wave (`materializer-render-wave`)

## Scope
- Primary candidate (attempt 1): contained `Materializer.scala` hot-loop cleanup.
  - Keep trusting `Val.Obj.sortedVisibleKeyNames` ordering cache.
  - Remove redundant adjacent-key order assertions in sorted object materialization loops.
  - Sub-visitor hoist/cast cleanup was trialed first but reverted immediately when it changed behavior.
- Fallback candidate (attempt 2): contained `ManifestModule.renderTableInternal` cleanup for `manifestTomlEx`.

## Baseline (pre-attempt measurements)
Commands:
- `./mill bench.runJmh -i 1 -wi 1 -f 1 'sjsonnet.bench.MaterializerBenchmark.*'`
- `./mill bench.runRegressions bench/resources/go_suite/manifestJsonEx.jsonnet`
- `./mill bench.runRegressions bench/resources/go_suite/manifestYamlDoc.jsonnet`
- `./mill bench.runRegressions bench/resources/go_suite/manifestTomlEx.jsonnet`
- `./mill bench.runRegressions bench/resources/cpp_suite/realistic1.jsonnet`
- `./mill bench.runJmh -i 1 -wi 1 -f 1 'sjsonnet.bench.MainBenchmark.main'`

Measurements:
- `MaterializerBenchmark.*`: benchmark setup currently fails in this environment (`NoSuchElementException: None.get` in `MaterializerBenchmark.setup`), no usable score (baseline and attempts behave the same here).
- `manifestJsonEx`: `0.089 ms/op`
- `manifestYamlDoc`: `0.085 ms/op`
- `manifestTomlEx`: `0.094 ms/op`
- `realistic1`: `2.829 ms/op`
- `MainBenchmark.main`: `3.801 ms/op`

## Attempt 1: Materializer hot-loop cleanup
Implementation notes:
- Initial variant removed sorted-order checks and hoisted `subVisitor` fetch/casts.
- The hoist variant was not semantics-safe in practice (`FileTests` failures: `stdlib.jsonnet`, `builtin_manifestTomlEx.jsonnet`), so it was rolled back.
- Final attempt-1 delta kept only redundant sorted-order assertion removal in `Materializer.scala`.

Validation commands:
- `./mill 'sjsonnet.jvm[3.3.7]'.test`
- `./mill bench.runJmh -i 1 -wi 1 -f 1 'sjsonnet.bench.MaterializerBenchmark.*'`
- `./mill bench.runRegressions bench/resources/go_suite/manifestJsonEx.jsonnet`
- `./mill bench.runRegressions bench/resources/go_suite/manifestYamlDoc.jsonnet`
- `./mill bench.runRegressions bench/resources/go_suite/manifestTomlEx.jsonnet`
- `./mill bench.runRegressions bench/resources/cpp_suite/realistic1.jsonnet`
- `./mill bench.runJmh -i 1 -wi 1 -f 1 'sjsonnet.bench.MainBenchmark.main'`

Measurements vs baseline:
- `manifestJsonEx`: `0.089 -> 0.226 ms/op` (regressed)
- `manifestYamlDoc`: `0.085 -> 0.303 ms/op` (regressed)
- `manifestTomlEx`: `0.094 -> 0.182 ms/op` (regressed)
- `realistic1`: `2.829 -> 2.897 ms/op` (regressed)
- `MainBenchmark.main`: `3.801 -> 3.298 ms/op` (improved but not enough to offset targeted regressions)
- `MaterializerBenchmark.*`: still setup-failing (`NoSuchElementException`), no comparable score.

Decision:
- **Rejected** (targeted regressions on manifest and realistic gates).

## Attempt 2: `manifestTomlEx` fallback cleanup
Implementation notes:
- Localized `ManifestModule.renderTableInternal` cleanup:
  - single-pass key classification,
  - reduced repeated `v.value(...)` lookups and section reclassification,
  - removed unused `indexedPath` threading.

Validation commands:
- `./mill 'sjsonnet.jvm[3.3.7]'.test`
- `./mill bench.runRegressions bench/resources/go_suite/manifestJsonEx.jsonnet`
- `./mill bench.runRegressions bench/resources/go_suite/manifestYamlDoc.jsonnet`
- `./mill bench.runRegressions bench/resources/go_suite/manifestTomlEx.jsonnet`
- `./mill bench.runRegressions bench/resources/cpp_suite/realistic1.jsonnet`
- `./mill bench.runJmh -i 1 -wi 1 -f 1 'sjsonnet.bench.MainBenchmark.main'`

Measurements vs baseline:
- `manifestJsonEx`: `0.089 -> 0.082 ms/op` (improved)
- `manifestYamlDoc`: `0.085 -> 0.081 ms/op` (improved)
- `manifestTomlEx`: `0.094 -> 0.118 ms/op` (regressed)
- `realistic1`: `2.829 -> 3.760 ms/op` (strong regression)
- `MainBenchmark.main`: `3.801 -> 4.516 ms/op` (strong regression)

Decision:
- **Rejected** (broad and realistic regressions dominate).

## Final outcome
- Both contained attempts were benchmark-negative.
- Source changes were reverted.
- Full keep-gate `./mill bench.runRegressions` was intentionally skipped because no candidate passed the focused gate.

## Post-revert verification

Commands:
- `./mill 'sjsonnet.jvm[3.3.7]'.test`
- `git --no-pager diff --exit-code -- sjsonnet/src/sjsonnet/Materializer.scala sjsonnet/src/sjsonnet/stdlib/ManifestModule.scala`

Result:
- Passed. The JVM test suite succeeded after the revert and neither touched source file has a remaining diff from this lane.

## Final repository state for this wave
- Retained changes:
  - `bench/reports/materializer-render-wave.md` (this report)
  - `bench/OPTIMIZATION_LOG.md` (new wave entry)
- No retained source diffs in `sjsonnet/src/...` from this lane.
