# Optimization Log

## Wave 1: direct self-tailrec detection
- Scope: mark direct self-tail-calls in `StaticOptimizer` without requiring explicit `tailstrict`.
- Outcome: kept.
- Validation:
  - `./mill 'sjsonnet.jvm[3.3.7]'.test.testOnly sjsonnet.TailCallOptimizationTests sjsonnet.EvaluatorTests sjsonnet.AstVisitProfilerTests`
  - `./mill 'sjsonnet.jvm[3.3.7]'.test`
  - `./mill bench.runJmh -i 1 -wi 1 -f 1 'sjsonnet.bench.OptimizerBenchmark.main'`
- Notes:
  - semantic feature is correct and verified
  - optimizer benchmark stayed effectively flat (`~0.552-0.564 ms/op` across short JMH runs)

## Wave 2: AST visit profiler + corpus runner
- Scope: instrument both evaluators, run the success-path corpus, and persist measured counts.
- Outcome: kept.
- Artifacts:
  - `bench/AST_VISIT_COUNTS.md`
  - `sjsonnet/src/sjsonnet/AstVisitProfiler.scala`
  - `sjsonnet/src-jvm-native/sjsonnet/AstVisitCorpusRunner.scala`
- Key corpus findings:
  - hottest old-dispatch arms: `ValidId`, `BinaryOp`, `Val`, `Select`
  - hottest normal tags: `ValidId`, `BinaryOp`, `Val.Literal`, `Select`

## Wave 3: data-driven dispatch/tag reordering
- Scope: reorder old `Evaluator.visitExpr` pattern matches and renumber `Expr.tag` values from the measured corpus.
- Outcome: reverted.
- Reason:
  - benchmark results regressed despite the frequency-guided order
  - `Select`/`Self`/`$`/`Super` share low-tag fast paths in `StaticOptimizer`, which constrains safe tag reshuffling
  - the naive reorder appears to fight Scala/JVM codegen rather than help it
- Rejected measurements:
  - `MainBenchmark.main`: `2.788 ms/op -> 3.222 ms/op`
  - `OptimizerBenchmark.main`: `0.555 ms/op -> 0.569 ms/op`
  - corpus runner new evaluator time: `235 ms -> 261 ms`
- Resolution: restore the original dispatch/tag order and keep the profiler data for later, more targeted optimizations.

## Wave 4: make `NewEvaluator` the default
- Scope: flip `Settings.useNewEvaluator` default from `false` to `true` so CLI / default `Interpreter` paths use the tag-dispatch evaluator.
- Outcome: reverted.
- Reason:
  - prior corpus evidence was strong (`AST_VISIT_COUNTS.md` old evaluator `1070 ms` vs new evaluator `268 ms` across the success-path corpus), but the default-flip gate was not benchmark-positive on the existing perf suite.
  - `MainBenchmark.main` was effectively flat (`3.252 ms/op -> 3.243 ms/op`), while regression `bench/resources/go_suite/comparison2.jsonnet` regressed (`72.801 ms/op -> 76.142 ms/op`).
- Resolution: keep `NewEvaluator` available behind `Settings.useNewEvaluator`, but do not make it the default until the regression cases are explained and fixed.

## Wave 5: array comparison / concat micro-optimizations
- Scope: try to speed array-heavy workloads (`comparison2`) by micro-optimizing evaluator array comparison paths and `Val.Arr.concat`.
- Outcome: reverted.
- Attempts:
  1. Skip recursive array element comparison when both arrays referenced the same strict element object.
  2. Replace `Val.Arr.concat`'s `arr ++ rhs.arr` with manual `Arrays.copyOf`/`System.arraycopy`.
  3. Preserve forcing semantics but short-circuit recursive compare/equal when the forced `Val` instances were identical.
- Reason:
  - all three variants produced benchmark noise or regressions on the existing regression gate, even when `MainBenchmark.main` occasionally improved.
  - representative rejected measurements:
    - shared-strict compare fast path: `comparison2 72.801 ms/op -> 77.465 ms/op`
    - manual concat copy: `comparison2 72.801 ms/op -> 82.432 ms/op`
    - safe identity compare fast path: `comparison2 72.801 ms/op -> 76.455 ms/op`
- Resolution: restore the original evaluator/array implementation and focus on optimizer-side wins with cleaner signal.

## Wave 6: reduce `StaticOptimizer` scope-building allocations
- Scope: rewrite `nestedConsecutiveBindings`, `nestedBindings`, and `nestedNames` to build immutable scope maps with explicit loops instead of `zipWithIndex.map` tuple arrays.
- Outcome: kept.
- Validation:
  - `./mill 'sjsonnet.jvm[3.3.7]'.test`
  - `./mill bench.runJmh -i 1 -wi 1 -f 1 'sjsonnet.bench.OptimizerBenchmark.main'`
  - `./mill bench.runJmh -i 1 -wi 1 -f 1 'sjsonnet.bench.MainBenchmark.main'`
  - `./mill bench.runRegressions bench/resources/go_suite/comparison2.jsonnet`
  - `./mill bench.runRegressions`
- Measurements:
  - `OptimizerBenchmark.main`: `0.554 ms/op -> 0.545 ms/op`
  - `MainBenchmark.main`: `3.252 ms/op -> 2.952 ms/op`
  - `comparison2`: `72.801 ms/op -> 72.631 ms/op`
  - Full `bench.runRegressions`: completed successfully in `00:07:16` after the change.
- Notes:
  - this is a small but repeatable optimizer win, and it did not put the regression suite into a negative state.
  - the change is intentionally mechanical: preserve the same immutable `HashMap` / `Scope` model, but stop allocating intermediate tuple arrays for common scope-extension paths.
