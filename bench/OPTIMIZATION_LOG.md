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

## Wave 7: investigate `NewEvaluator` default regression
- Scope: explain why the prior `Settings.useNewEvaluator = true` flip looked strong in `AST_VISIT_COUNTS.md` but regressed `bench/resources/go_suite/comparison2.jsonnet`, then try up to two focused fixes.
- Outcome: investigation kept as notes only; no code change kept.
- Reproduction:
  - Baseline current HEAD (`useNewEvaluator = false` by default):
    - `comparison2`: `72.683 ms/op`
    - `MainBenchmark.main`: `3.152 ms/op`
    - `assertions.jsonnet`: `0.301 ms/op`
  - Reproduced prior default flip (`useNewEvaluator = true` only):
    - `comparison2`: `75.151 ms/op`
    - `MainBenchmark.main`: `3.282 ms/op`
    - `assertions.jsonnet`: `0.305 ms/op`
- Root cause notes:
  - `bench/AST_VISIT_COUNTS.md`'s elapsed-time comparison is not a benchmark-quality A/B signal. `AstVisitCorpusRunner` always executes the old evaluator first and the new evaluator second for every file, with no warmup and separate parse caches, so the second evaluator systematically benefits from a warmer JVM / filesystem / class metadata state.
  - `comparison2.jsonnet` is just `[i < j for i in std.range(1, 1000) for j in std.range(1, 1000)]`, so its hot loop is dominated by `ValidId` and `BinaryOp` dispatch inside the evaluator rather than array equality/concat work. That explains why earlier array-path micro-opts could not help this case.
- Attempts:
  1. Hybrid `NewEvaluator.visitExpr`: handle `ValidId`, `BinaryOp`, and `Val` with the old `instanceof` fast path before falling back to the tag switch.
     - Result:
       - targeted `comparison2`: improved on reruns (`75.151 -> 69.415 ms/op`, later `72.051 ms/op` vs `72.683 ms/op` baseline)
       - `MainBenchmark.main`: still regressed (`3.152 -> 3.220 ms/op`)
       - full `bench.runRegressions`: completed successfully in `437s`, but the suite row for `comparison2` still landed at `74.741 ms/op`
     - Resolution: rejected. The hot-path fix helped the specific regression benchmark but was not a safe overall default-flip win.
  2. Add `final` modifiers to `NewEvaluator` / dispatch overrides on top of attempt 1 to encourage JVM devirtualization.
     - Result:
       - `comparison2`: regressed back to `73.269 ms/op`
     - Resolution: rejected immediately and reverted.
- Resolution:
  - leave `Settings.useNewEvaluator` defaulted to `false`
  - keep the existing evaluator implementation from Wave 6 unchanged
  - treat the corpus elapsed-time gap as diagnostic only, not as benchmark evidence for a default flip

## Wave 8: reuse the mutable `Scope` shell during consecutive binding rewrites
- Scope: cut `StaticOptimizer` scope-management allocation churn further by reusing a single `Scope` object inside `nestedConsecutiveBindings` while still keeping immutable `HashMap` updates for the actual bindings.
- Outcome: kept.
- Hypotheses explored:
  1. Remove the unused `ScopedVal.sc` back-reference to shrink each scoped binding record.
     - Result: rejected.
     - Measurements:
       - `OptimizerBenchmark.main`: `0.551 ms/op -> 0.556 ms/op`
       - `MainBenchmark.main`: `2.938 ms/op -> 2.884 ms/op`
     - Reason: mixed signal; the optimizer-focused benchmark regressed, so the change was reverted.
  2. Reuse one `Scope` instance across the `nestedConsecutiveBindings` transform loop and mutate only its `mappings` field as rewritten bindings become available.
     - Result: kept.
- Validation:
  - `./mill 'sjsonnet.jvm[3.3.7]'.test`
  - `./mill bench.runJmh -i 1 -wi 1 -f 1 'sjsonnet.bench.OptimizerBenchmark.main'`
  - `./mill bench.runJmh -i 1 -wi 1 -f 1 'sjsonnet.bench.MainBenchmark.main'`
  - `./mill bench.runRegressions bench/resources/go_suite/comparison2.jsonnet`
  - `./mill bench.runRegressions`
- Measurements:
  - Baseline `2ebebc0f`:
    - `OptimizerBenchmark.main`: `0.551 ms/op`
    - `MainBenchmark.main`: `2.938 ms/op`
    - `comparison2`: `75.632 ms/op`
  - Kept change:
    - `OptimizerBenchmark.main`: `0.542 ms/op`
    - `MainBenchmark.main`: `2.793 ms/op`
    - targeted `comparison2`: `72.272 ms/op`
    - full `bench.runRegressions` suite row for `comparison2`: `74.844 ms/op`
    - full `bench.runRegressions`: completed successfully in `436s`
- Notes:
  - the win comes from avoiding one transient `Scope` allocation per transformed binding in `nestedConsecutiveBindings`; the immutable `HashMap` structure and binding semantics stay unchanged.
  - keeping `Scope` itself mutable is intentionally narrow: only the active shell object for the current consecutive-binding block is reused, and nested scopes still get their own `Scope` instances.

## Wave 9: cache parsed dynamic `%` formats
- Scope: avoid reparsing non-literal `%` format strings on every call by adding a small bounded parsed-format cache in `Format.format`, while keeping literal-format specialization in `StaticOptimizer` unchanged.
- Outcome: kept.
- Hypothesis:
  - repeated dynamic format strings pay `fastparse.parse` every time today
  - a small LRU cache keyed by the full format string should remove that repeated parse cost for template-heavy workloads without changing formatting semantics
- Validation:
  - `./mill 'sjsonnet.jvm[3.3.7]'.test`
  - `./mill bench.runJmh -i 1 -wi 1 -f 1 'sjsonnet.bench.MainBenchmark.main'`
  - `./mill bench.runRegressions /tmp/dynamic_format_cache.jsonnet`
  - `./mill bench.runRegressions`
- Measurements (baseline `9f08ee66` -> kept change):
  - focused dynamic-format regression `/tmp/dynamic_format_cache.jsonnet`: `8.946 ms/op -> 4.839 ms/op`
  - `MainBenchmark.main`: `3.141 ms/op -> 3.085 ms/op`
  - full `bench.runRegressions`: completed successfully in `437s`
  - selected suite rows after the change:
    - `assertions`: `0.271 ms/op`
    - `large_string_template`: `2.391 ms/op`
    - `comparison2`: `73.623 ms/op`
- Notes:
  - the cache is intentionally small and bounded (`256` entries) to keep retention risk low while still catching repeated dynamic templates
  - parse failures are not cached; only successful parsed-format tuples are reused
  - static literal `%` formats still go through `Format.PartialApplyFmt`, so this wave specifically targets the remaining dynamic path in `Evaluator` / `std.format`
