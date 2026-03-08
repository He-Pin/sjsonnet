# static-object-wave

## Scope
- Lane: `static-object-wave`.
- Objective: keep the change strictly static-object-only by specializing static-object representation in `Val.staticObject` and static lookup paths.
- Guardrails honored:
  - did **not** retry broad visible-read caching or materializer consumer hooks from rejected `object-read-cache-wave`.

## Baseline (pre-change)
Commands:
- `./mill 'sjsonnet.jvm[3.3.7]'.test`
- `./mill bench.runJmh -i 1 -wi 1 -f 1 'sjsonnet.bench.MainBenchmark.main'`
- `./mill bench.runJmh -i 1 -wi 1 -f 1 'sjsonnet.bench.OptimizerBenchmark.main'`
- `./mill bench.runRegressions bench/resources/go_suite/manifestJsonEx.jsonnet`
- `./mill bench.runRegressions bench/resources/go_suite/manifestYamlDoc.jsonnet`
- `./mill bench.runRegressions bench/resources/go_suite/manifestTomlEx.jsonnet`
- `./mill bench.runRegressions bench/resources/cpp_suite/realistic1.jsonnet`
- `./mill bench.runRegressions bench/resources/cpp_suite/realistic2.jsonnet`

Measurements:
- `MainBenchmark.main`: `3.718 ms/op`
- `OptimizerBenchmark.main`: `0.577 ms/op`
- Focused regression commands completed successfully, but baseline timing rows for those individual cases were not captured in the first run output; treat them as targeted post-change smoke timings rather than paired before/after perf evidence.

## Attempt 1: static-object layout specialization (kept)
Change summary:
- Added a static layout object (`keys` + shared key->index map + shared `allKeys`) interned by static field set.
- Switched `Val.staticObject` to produce per-object value arrays while reusing an interned layout for field order and lookup index.
- Updated static object lookup/read paths (`value`, `valueRaw`, `containsKey`, `allKeyNames`, static `value0` materialization) to use layout/index+array access, with legacy map fallback when no static layout is present.
- Propagated interned static-layout cache type through `Parser`, `Importer`, `Interpreter`, and `StaticOptimizer`.

Validation commands:
- `./mill 'sjsonnet.jvm[3.3.7]'.test`
- `./mill bench.runJmh -i 1 -wi 1 -f 1 'sjsonnet.bench.MainBenchmark.main'`
- `./mill bench.runJmh -i 1 -wi 1 -f 1 'sjsonnet.bench.OptimizerBenchmark.main'`
- `./mill bench.runRegressions bench/resources/go_suite/manifestJsonEx.jsonnet`
- `./mill bench.runRegressions bench/resources/go_suite/manifestYamlDoc.jsonnet`
- `./mill bench.runRegressions bench/resources/go_suite/manifestTomlEx.jsonnet`
- `./mill bench.runRegressions bench/resources/cpp_suite/realistic1.jsonnet`
- `./mill bench.runRegressions bench/resources/cpp_suite/realistic2.jsonnet`

Attempt-1 focused measurements:
- `MainBenchmark.main`: `3.389 ms/op` (improved vs `3.718`)
- `OptimizerBenchmark.main`: `0.569 ms/op` (improved vs `0.577`)
- Post-change targeted smoke timings:
  - `manifestJsonEx`: `0.089 ms/op`
  - `manifestYamlDoc`: `0.087 ms/op`
  - `manifestTomlEx`: `0.107 ms/op`
  - `realistic1`: `2.877 ms/op`
  - `realistic2`: `74.410 ms/op`

Decision after attempt 1:
- **Positive and safe** (tests passed, focused benchmarks improved, no semantic failures observed), so fallback attempt was skipped.

## Keep-gate
Command:
- `./mill bench.runRegressions`

Result:
- `SUCCESS` (`125/125`) in `442s`.

## Final decision
- Wave outcome: **accepted via attempt 1**.
- Retained source changes:
  - `sjsonnet/src/sjsonnet/Val.scala`
  - `sjsonnet/src/sjsonnet/Parser.scala`
  - `sjsonnet/src/sjsonnet/Importer.scala`
  - `sjsonnet/src/sjsonnet/Interpreter.scala`
  - `sjsonnet/src/sjsonnet/StaticOptimizer.scala`
