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

## Wave 9: lazy sorted object-key cache reuse
- Scope: lazily cache sorted object key arrays on `Val.Obj` and reuse them from materialization / object builtins / TOML manifest key walks instead of re-sorting the same visible-key snapshots.
- Outcome: kept.
- Validation:
  - `./mill 'sjsonnet.jvm[3.3.7]'.test`
  - `./mill bench.runJmh -i 1 -wi 1 -f 1 'sjsonnet.bench.MainBenchmark.main'`
  - `./mill bench.runRegressions bench/resources/go_suite/manifestJsonEx.jsonnet`
  - `./mill bench.runRegressions bench/resources/go_suite/manifestYamlDoc.jsonnet`
  - `./mill bench.runRegressions bench/resources/go_suite/manifestTomlEx.jsonnet`
  - `./mill bench.runRegressions`
- Measurements (baseline `9f08ee66` -> kept change):
  - `MainBenchmark.main`: `3.237 ms/op -> 2.903 ms/op`
  - `manifestJsonEx`: `0.075 ms/op -> 0.077 ms/op`
  - `manifestYamlDoc`: `0.080 ms/op -> 0.078 ms/op`
  - `manifestTomlEx`: `0.092 ms/op -> 0.090 ms/op`
  - full `bench.runRegressions`: completed successfully in `437s`
    - suite row `manifestJsonEx`: `0.077 ms/op`
    - suite row `manifestYamlDoc`: `0.081 ms/op`
    - suite row `manifestTomlEx`: `0.094 ms/op`
    - suite row `comparison2`: `73.623 ms/op`
- Notes:
  - keep the cache lazy and per-object so semantics stay unchanged: the existing unsorted key snapshots remain the source of truth, while sorted arrays are only allocated on demand for sorted materialization paths.
  - `std.manifestTomlEx` now partitions an already-sorted visible-key array, avoiding its prior double re-sort of section and non-section key groups.

## Wave 10: ASCII/common-case string fast-path investigation
- Scope: try one contained JVM string-path wave focused on common no-surrogate cases, with at most one alternative before deciding whether to keep it.
- Outcome: reverted; no code change kept.
- Baseline revision: `fdf932fe`
- Correctness checks:
  - `./mill __.checkFormat`
  - `./mill 'sjsonnet.jvm[3.3.7]'.test`
- Attempts:
  1. Fast-path `std.length`, `std.substr`, and `Util.sliceStr` for strings with no UTF-16 surrogates.
     - Validation:
       - `./mill bench.runRegressions bench/resources/go_suite/substr.jsonnet`
       - `./mill bench.runRegressions bench/resources/cpp_suite/large_string_template.jsonnet`
       - `./mill bench.runJmh -i 1 -wi 1 -f 1 'sjsonnet.bench.MainBenchmark.main'`
     - Measurements:
       - `substr`: `0.161 ms/op -> 0.163 ms/op`
       - `large_string_template`: `2.158 ms/op -> 2.205 ms/op`
       - `MainBenchmark.main`: `3.118 ms/op -> 3.135 ms/op`
     - Resolution: rejected and reverted before trying the fallback idea.
  2. Fast-path `stripChars` / `lstripChars` / `rstripChars` / `trim` for no-surrogate character sets using direct `charAt` / `indexOf` margin scans.
     - Validation:
       - `./mill bench.runRegressions bench/resources/go_suite/stripChars.jsonnet`
       - `./mill bench.runRegressions bench/resources/go_suite/lstripChars.jsonnet`
       - `./mill bench.runRegressions bench/resources/go_suite/rstripChars.jsonnet`
       - `./mill bench.runJmh -i 1 -wi 1 -f 1 'sjsonnet.bench.MainBenchmark.main'`
     - Measurements:
       - `stripChars`: `0.598 ms/op -> 0.603 ms/op`
       - `lstripChars`: `0.617 ms/op -> 0.624 ms/op`
       - `rstripChars`: `0.617 ms/op -> 0.611 ms/op`
       - `MainBenchmark.main`: `3.118 ms/op -> 3.140 ms/op`
     - Resolution: rejected and reverted.
- Notes:
  - both contained string-path ideas produced targeted regressions or a worse `MainBenchmark.main`, so this wave was stopped before the full `./mill bench.runRegressions` keep-gate.
  - revisit this lane with a more evidence-driven workload or a lower-overhead specialization; the naive extra branch/scan work did not pay for itself on the current suite.

## Wave 11: parser fast-path investigation
- Scope: try one contained parser allocation/fast-path wave with at most one alternative before deciding whether to keep it.
- Outcome: reverted; no code change kept.
- Correctness checks:
  - `./mill 'sjsonnet.jvm[3.3.7]'.test`
- Baseline measurements:
  - `ParserBenchmark.main`: `1.434 ms/op`
  - `MainBenchmark.main`: `3.097 ms/op`
  - `large_string_join`: `2.214 ms/op`
  - `large_string_template`: `2.192 ms/op`
  - `member`: `0.720 ms/op`
- Attempts:
  1. Numeric no-underscore fast path in `Parser.number`.
     - Validation:
       - `./mill 'sjsonnet.jvm[3.3.7]'.test`
       - `./mill bench.runJmh -i 1 -wi 1 -f 1 'sjsonnet.bench.ParserBenchmark.main'`
       - `./mill bench.runJmh -i 1 -wi 1 -f 1 'sjsonnet.bench.MainBenchmark.main'`
       - `./mill bench.runRegressions bench/resources/go_suite/member.jsonnet`
     - Measurements:
       - `ParserBenchmark.main`: `1.434 -> 1.533 ms/op`
       - `MainBenchmark.main`: `3.097 -> 3.268 ms/op`
       - `member`: `0.720 -> 0.796 ms/op`
     - Resolution: rejected and reverted.
  2. Quoted string fast path for plain literals.
     - Validation:
       - `./mill 'sjsonnet.jvm[3.3.7]'.test`
     - Result:
       - rejected before benchmarking after 8 golden mismatches changed externally checked parse-error text
     - Resolution: rejected and reverted.
- Notes:
  - no candidate survived correctness and focused benchmark gates, so the full `./mill bench.runRegressions` keep-gate was intentionally skipped.
  - detailed evidence is captured in `bench/reports/parser-fast-path-wave.md`.

## Wave 12: cache-key and parse-cache investigation
- Scope: try one contained cache-related JVM wave around default parse-cache concurrency and cache-key fingerprinting.
- Outcome: reverted; no code change kept.
- Correctness checks:
  - `./mill 'sjsonnet.jvm[3.3.7]'.test`
- Baseline measurements:
  - `MultiThreadedBenchmark.main`: `5.451 ms/op`
  - `MainBenchmark.main`: `3.191 ms/op`
- Attempts:
  1. Make `DefaultParseCache` concurrent and use it directly in `MultiThreadedBenchmark`.
     - Validation:
       - `./mill 'sjsonnet.jvm[3.3.7]'.test`
       - `./mill bench.runJmh -i 1 -wi 1 -f 1 'sjsonnet.bench.MultiThreadedBenchmark.main'`
     - Result:
       - rejected for functional failures under concurrent cold loads (`LazyApply1` `ClassCastException`, then `Val$Builtin1` `NullPointerException`)
     - Resolution: reverted; current runtime is not safe for this contained miss-sharing change.
  2. Replace `StaticResolvedFile.contentHash()` full-string keys with a cached short fingerprint.
     - Validation:
       - `./mill 'sjsonnet.jvm[3.3.7]'.test`
       - `./mill bench.runJmh -i 1 -wi 1 -f 1 'sjsonnet.bench.MultiThreadedBenchmark.main'`
       - `./mill bench.runJmh -i 1 -wi 1 -f 1 'sjsonnet.bench.MainBenchmark.main'`
     - Measurements:
       - `MultiThreadedBenchmark.main`: `5.451 -> 5.606 ms/op`
       - `MainBenchmark.main`: `3.191 -> 3.385 ms/op`
     - Resolution: rejected and reverted.
- Notes:
  - default concurrent parse-cache miss sharing appears unsafe with today's cold-load parse/evaluator result publication model.
  - detailed evidence is captured in `bench/reports/cache-key-wave.md`.

## Wave 13: optimizer scratch-state investigation
- Scope: revisit `StaticOptimizer.nestedConsecutiveBindings` with a narrow allocation-churn wave, allowing up to two contained variants before deciding whether to keep anything.
- Outcome: reverted; no code change kept.
- Correctness checks:
  - `./mill 'sjsonnet.jvm[3.3.7]'.test`
- Baseline measurements:
  - `OptimizerBenchmark.main`: `0.533 ms/op`
  - `MainBenchmark.main`: `3.132 ms/op`
  - `bench/resources/go_suite/comparison2.jsonnet`: `71.522 ms/op`
- Attempts:
  1. Build the initial merged binding map with a builder and also skip redundant second-pass scope-map updates when a binding rewrite returned the original bind.
     - Validation:
       - `./mill 'sjsonnet.jvm[3.3.7]'.test`
       - `./mill bench.runJmh -i 1 -wi 1 -f 1 'sjsonnet.bench.OptimizerBenchmark.main'`
       - `./mill bench.runJmh -i 1 -wi 1 -f 1 'sjsonnet.bench.MainBenchmark.main'`
       - `./mill bench.runRegressions bench/resources/go_suite/comparison2.jsonnet`
     - Measurements:
       - `OptimizerBenchmark.main`: `0.533 -> 0.541 ms/op`
       - `MainBenchmark.main`: `3.132 -> 3.487 ms/op`
       - `comparison2`: `71.522 -> 74.171 ms/op`
     - Resolution: rejected and reverted.
  2. Keep the original first-pass map build, but skip redundant second-pass scope-map updates when `transformBind` returned the original bind.
     - Validation:
       - `./mill 'sjsonnet.jvm[3.3.7]'.test`
       - `./mill bench.runJmh -i 1 -wi 1 -f 1 'sjsonnet.bench.OptimizerBenchmark.main'`
       - `./mill bench.runJmh -i 1 -wi 1 -f 1 'sjsonnet.bench.MainBenchmark.main'`
       - `./mill bench.runRegressions bench/resources/go_suite/comparison2.jsonnet`
     - Measurements:
       - `OptimizerBenchmark.main`: `0.533 -> 0.546 ms/op`
       - `MainBenchmark.main`: `3.132 -> 3.155 ms/op`
       - `comparison2`: `71.522 -> 72.370 ms/op`
     - Resolution: rejected and reverted.
- Notes:
  - both variants moved the focused optimizer and end-to-end signals in the wrong direction, so the full keep-gate `./mill bench.runRegressions` was intentionally skipped.
  - detailed evidence is captured in `bench/reports/optimizer-scratch-wave.md`.

## Wave 14: stdlib loop combinator investigation
- Scope: evaluate the stdlib loop lane (`member`/`foldl` family) with up to two contained allocation-reduction attempts in `ArrayModule`.
- Outcome: reverted; no code change kept.
- Correctness checks:
  - `./mill 'sjsonnet.jvm[3.3.7]'.test`
- Baseline measurements:
  - `member`: `0.749 ms/op`
  - `reverse`: `11.297 ms/op`
  - `foldl`: `10.090 ms/op`
  - `setDiff`: `0.514 ms/op`
  - `setInter`: `0.450 ms/op`
  - `setUnion`: `0.790 ms/op`
  - `MainBenchmark.main`: `3.302 ms/op`
- Attempts:
  1. `std.member` array membership `indexWhere` -> indexed while loop.
     - Measurements:
       - `member`: `0.806 ms/op`
       - `reverse`: `11.632 ms/op`
       - `foldl`: `10.456 ms/op`
       - `setDiff`: `0.520 ms/op`
       - `setInter`: `0.448 ms/op`
       - `setUnion`: `0.806 ms/op`
       - `MainBenchmark.main`: `3.550 ms/op`
     - Resolution: rejected and reverted.
  2. `std.foldl` array branch Scala iterator loop -> indexed while loop.
     - Measurements:
       - `member`: `0.726 ms/op`
       - `reverse`: `11.133 ms/op`
       - `foldl`: `10.446 ms/op`
       - `setDiff`: `0.492 ms/op`
       - `setInter`: `0.443 ms/op`
       - `setUnion`: `0.785 ms/op`
       - `MainBenchmark.main`: `3.435 ms/op`
     - Resolution: rejected and reverted.
- Notes:
  - both contained attempts failed the focused broad-gate signal (`MainBenchmark.main`), so the full keep-gate `./mill bench.runRegressions` was intentionally skipped.
  - detailed evidence is captured in `bench/reports/stdlib-loop-wave.md`.

## Wave 15: parser object-member single-pass investigation
- Scope: evaluate `Parser.objinside` member-list parsing for allocation reduction by collapsing multi-pass member collection / duplicate checks into contained single-pass variants.
- Outcome: reverted; no code change kept.
- Correctness checks:
  - `./mill 'sjsonnet.jvm[3.3.7]'.test`
- Baseline measurements:
  - `ParserBenchmark.main`: `1.541 ms/op`
  - `MainBenchmark.main`: `3.434 ms/op`
  - `gen_big_object`: `1.150 ms/op`
  - `realistic1`: `3.184 ms/op`
- Attempts:
  1. Full member-list single-pass classification + duplicate tracking in `objinside`.
     - Result:
       - rejected on correctness before benchmark comparison (parser error text/position drift, including `duplicateFields` and `localInObj` assertion mismatches plus golden mismatches in `FileTests` suites).
     - Resolution: rejected and reverted.
  2. Conservative pass reduction in `case (exprs, None)` only (single classification pass for fields/asserts while preserving existing duplicate-check stages).
     - Measurements:
       - `ParserBenchmark.main`: `1.538 ms/op`
       - `MainBenchmark.main`: `4.384 ms/op`
       - `gen_big_object`: `1.882 ms/op`
       - `realistic1`: `4.260 ms/op`
     - Resolution: rejected and reverted.
- Notes:
  - attempt 1 changed externally checked parser failure behavior, so it was discarded immediately.
  - attempt 2 preserved correctness but was strongly benchmark-negative on broad and focused gates, so full keep-gate `./mill bench.runRegressions` was intentionally skipped.
  - detailed evidence is captured in `bench/reports/parser-object-pass-wave.md`.

## Wave 16: materializer-render-wave
- Scope: evaluate a contained materialization/rendering wave with two attempts:
  1) `Materializer.scala` hot-loop cleanup (remove redundant sorted-adjacent checks; trialed sub-visitor hoist),
  2) fallback local cleanup in `ManifestModule.renderTableInternal` for `manifestTomlEx`.
- Outcome: reverted; no source code change kept.
- Correctness checks:
  - `./mill 'sjsonnet.jvm[3.3.7]'.test`
- Baseline measurements:
  - `manifestJsonEx`: `0.089 ms/op`
  - `manifestYamlDoc`: `0.085 ms/op`
  - `manifestTomlEx`: `0.094 ms/op`
  - `realistic1`: `2.829 ms/op`
  - `MainBenchmark.main`: `3.801 ms/op`
  - `MaterializerBenchmark.*`: setup failure (`NoSuchElementException: None.get` in `MaterializerBenchmark.setup`) in this environment.
- Attempts:
  1. Materializer sorted-loop assertion removal (sub-visitor hoist variant first rolled back due FileTests failures).
     - Measurements:
       - `manifestJsonEx`: `0.226 ms/op`
       - `manifestYamlDoc`: `0.303 ms/op`
       - `manifestTomlEx`: `0.182 ms/op`
       - `realistic1`: `2.897 ms/op`
       - `MainBenchmark.main`: `3.298 ms/op`
     - Resolution: rejected and reverted.
  2. `manifestTomlEx` single-pass section classification / lookup-reduction cleanup.
     - Measurements:
       - `manifestJsonEx`: `0.082 ms/op`
       - `manifestYamlDoc`: `0.081 ms/op`
       - `manifestTomlEx`: `0.118 ms/op`
       - `realistic1`: `3.760 ms/op`
       - `MainBenchmark.main`: `4.516 ms/op`
     - Resolution: rejected and reverted.
- Notes:
  - Neither attempt passed the focused gate; full keep-gate `./mill bench.runRegressions` was intentionally skipped.
  - Detailed evidence is captured in `bench/reports/materializer-render-wave.md`.

## Wave 17: string-template-wave2
- Scope: evaluate a contained JVM string-template wave with two attempts:
  1. `Format.format` primitive `%` fast path to avoid unnecessary materialization for primitives,
  2. fallback `std.join` string-separator capacity precompute in `StringModule.Join`.
- Outcome: reverted; no source code change kept.
- Correctness checks:
  - `./mill 'sjsonnet.jvm[3.3.7]'.test`
- Baseline measurements:
  - `large_string_template`: `2.777 ms/op`
  - `realistic1`: `3.214 ms/op`
  - `large_string_join`: `2.875 ms/op`
  - `MainBenchmark.main`: `3.136 ms/op`
- Attempts:
  1. `Format.format` primitive fast path.
     - Measurements:
       - `large_string_template`: `2.442 ms/op`
       - `realistic1`: `2.942 ms/op`
       - `large_string_join`: `2.296 ms/op`
       - `MainBenchmark.main`: `3.286 ms/op`
     - Resolution: rejected and reverted due broad-gate regression.
  2. `std.join` string path capacity precompute.
     - Measurements:
       - `large_string_template`: `2.465 ms/op`
       - `realistic1`: `2.956 ms/op`
       - `large_string_join`: `2.598 ms/op`
       - `MainBenchmark.main`: `3.652 ms/op`
     - Resolution: rejected and reverted due strong broad-gate regression.
- Notes:
  - both contained attempts improved some targeted template-heavy cases but failed to preserve `MainBenchmark.main`.
  - full keep-gate `./mill bench.runRegressions` was intentionally skipped because neither attempt was benchmark-positive and both were reverted.
  - detailed evidence is captured in `bench/reports/string-template-wave2.md`.

## Wave 18: format-chunk-wave
- Scope: `Format.scala`-only string-format execution wave with two contained attempts:
  1. no-star runtime fast path using cached `hasAnyStar` metadata,
  2. fallback chunk lowering into indexed arrays with precomputed static literal chars + while-loop runtime iteration.
- Outcome: **kept via attempt 2**.
- Correctness checks (kept attempt):
  - `./mill __.checkFormat`
  - `./mill 'sjsonnet.jvm[3.3.7]'.test`
- Baseline measurements:
  - `large_string_template`: `2.625 ms/op`
  - `realistic1`: `3.146 ms/op`
  - `MainBenchmark.main`: `3.735 ms/op`
- Attempts:
  1. Attempt 1 (`hasAnyStar` no-star fast path).
     - Measurements:
       - `large_string_template`: `2.446 ms/op`
       - `realistic1`: `3.047 ms/op`
       - `MainBenchmark.main`: `3.943 ms/op`
     - Resolution: rejected and reverted (broad-gate regression).
  2. Attempt 2 (lowered runtime chunk arrays + static `StringBuilder` sizing + indexed while loop).
     - Measurements:
       - `large_string_template`: `2.512 ms/op`
       - `realistic1`: `2.673 ms/op`
       - `MainBenchmark.main`: `3.454 ms/op`
     - Resolution: kept.
- Keep-gate:
  - `./mill bench.runRegressions` succeeded (`125/125`) in `442s`.
  - full-suite rows include:
    - `large_string_template`: `2.400 ms/op`
    - `realistic1`: `2.782 ms/op`
- Notes:
  - attempt 1 was reverted before trying attempt 2 per wave policy.
  - detailed evidence is captured in `bench/reports/format-chunk-wave.md`.

## Wave 19: format-followup-wave
- Scope: `Format.scala`-only follow-up wave with two contained attempts:
  1. compact runtime spec/op lowering using sentinel ints + bit/flag metadata,
  2. fallback builder-oriented append/widen helpers to reduce temporary strings.
- Outcome: reverted; no source code change kept.
- Correctness checks (for each attempt):
  - `./mill __.checkFormat`
  - `./mill 'sjsonnet.jvm[3.3.7]'.test`
- Baseline measurements:
  - `large_string_template`: `2.482 ms/op`
  - `realistic1`: `2.707 ms/op`
  - `MainBenchmark.main`: `3.115 ms/op`
- Attempts:
  1. Compact runtime spec/op lowering.
     - Measurements:
       - `large_string_template`: `3.025 ms/op`
       - `realistic1`: `3.229 ms/op`
       - `MainBenchmark.main`: `3.442 ms/op`
     - Resolution: rejected and reverted.
  2. Builder-oriented append/widen helpers.
     - Measurements:
       - `large_string_template`: `2.697 ms/op`
       - `realistic1`: `2.924 ms/op`
       - `MainBenchmark.main`: `4.166 ms/op`
     - Resolution: rejected and reverted.
- Notes:
  - Attempt 1 was reverted before attempting attempt 2, per policy.
  - Full keep-gate `./mill bench.runRegressions` was intentionally skipped because neither attempt was positive.
  - Post-revert verification: `git --no-pager diff --name-only -- sjsonnet/src/sjsonnet/Format.scala` was empty; only this log update and `bench/reports/format-followup-wave.md` remained.
  - Detailed evidence is captured in `bench/reports/format-followup-wave.md`.

## Wave 20: object-read-cache-wave
- Scope: evaluate a narrow object repeated-read cache / merged visible-read fast path, allowing one contained fallback in the same lane.
- Outcome: reverted; no source code change kept.
- Correctness checks (each attempt):
  - `./mill 'sjsonnet.jvm[3.3.7]'.test`
- Baseline measurements:
  - `manifestJsonEx`: `0.122 ms/op`
  - `manifestYamlDoc`: `0.121 ms/op`
  - `manifestTomlEx`: `0.164 ms/op`
  - `realistic1`: `2.840 ms/op`
  - `realistic2`: `72.551 ms/op`
  - `MainBenchmark.main`: `3.920 ms/op`
- Attempts:
  1. `Val.Obj` visible-read value-array cache + Materializer object-loop hookup.
     - Measurements:
       - `manifestJsonEx`: `0.081 ms/op`
       - `manifestYamlDoc`: `0.083 ms/op`
       - `manifestTomlEx`: `0.097 ms/op`
       - `realistic1`: `2.988 ms/op`
       - `realistic2`: `82.266 ms/op`
       - `MainBenchmark.main`: `5.028 ms/op`
     - Resolution: rejected and reverted (broad regressions).
  2. Fallback contained repeated-read reuse in `ManifestModule.renderTableInternal` for `manifestTomlEx`.
     - Measurements:
       - `manifestJsonEx`: `0.099 ms/op`
       - `manifestYamlDoc`: `0.105 ms/op`
       - `manifestTomlEx`: `0.113 ms/op`
       - `realistic1`: `2.841 ms/op`
       - `realistic2`: `89.704 ms/op`
       - `MainBenchmark.main`: `7.201 ms/op`
     - Resolution: rejected and reverted (strong broad regressions).
- Notes:
  - Attempt 1 was reverted before attempt 2, per policy.
  - Full keep-gate `./mill bench.runRegressions` was intentionally skipped because neither attempt was positive.
  - Post-revert verification: `git --no-pager diff --name-only -- sjsonnet/src/sjsonnet/Val.scala sjsonnet/src/sjsonnet/Materializer.scala sjsonnet/src/sjsonnet/stdlib/ManifestModule.scala` was empty; only this log update and `bench/reports/object-read-cache-wave.md` remained.
  - Detailed evidence is captured in `bench/reports/object-read-cache-wave.md`.

## Wave 21: static-object-wave
- Scope: static-object-only representation specialization in `Val.staticObject` and static lookup paths, with interned shared layout metadata (field order + key-index) and per-object values arrays.
- Outcome: kept.
- Validation:
  - `./mill 'sjsonnet.jvm[3.3.7]'.test`
  - `./mill bench.runJmh -i 1 -wi 1 -f 1 'sjsonnet.bench.MainBenchmark.main'`
  - `./mill bench.runJmh -i 1 -wi 1 -f 1 'sjsonnet.bench.OptimizerBenchmark.main'`
  - `./mill bench.runRegressions bench/resources/go_suite/manifestJsonEx.jsonnet`
  - `./mill bench.runRegressions bench/resources/go_suite/manifestYamlDoc.jsonnet`
  - `./mill bench.runRegressions bench/resources/go_suite/manifestTomlEx.jsonnet`
  - `./mill bench.runRegressions bench/resources/cpp_suite/realistic1.jsonnet`
  - `./mill bench.runRegressions bench/resources/cpp_suite/realistic2.jsonnet`
  - `./mill bench.runRegressions`
- Measurements:
  - `MainBenchmark.main`: `3.718 -> 3.389 ms/op`
  - `OptimizerBenchmark.main`: `0.577 -> 0.569 ms/op`
  - Focused regressions after change (smoke timings only; paired baseline rows were not captured in the initial run output):
    - `manifestJsonEx`: `0.089 ms/op`
    - `manifestYamlDoc`: `0.087 ms/op`
    - `manifestTomlEx`: `0.107 ms/op`
    - `realistic1`: `2.877 ms/op`
    - `realistic2`: `74.410 ms/op`
  - Full keep-gate `bench.runRegressions`: `SUCCESS` (`125/125`) in `442s`.
- Notes:
  - did not retry rejected broad visible-read caching / materializer consumer-hook approaches from `object-read-cache-wave`.
  - detailed evidence is captured in `bench/reports/static-object-wave.md`.

## Wave 22: static-lookup-wave
- Scope: add one-shot static-layout lookup helpers and static-aware optimizer folds (`Select`, constant-string `Lookup`, and `OP_in`) on top of `static-object-wave`, avoiding broad object read caching.
- Outcome: **rejected and reverted after Level-4 branch-vs-base proof**.
- Validation:
  - `./mill 'sjsonnet.jvm[3.3.7]'.test`
  - `./mill bench.runJmh -i 1 -wi 1 -f 1 'sjsonnet.bench.MainBenchmark.main'`
  - `./mill bench.runJmh -i 1 -wi 1 -f 1 'sjsonnet.bench.OptimizerBenchmark.main'`
  - `./mill bench.runRegressions bench/resources/go_suite/manifestJsonEx.jsonnet`
  - `./mill bench.runRegressions bench/resources/go_suite/manifestYamlDoc.jsonnet`
  - `./mill bench.runRegressions bench/resources/go_suite/manifestTomlEx.jsonnet`
  - `./mill bench.runRegressions bench/resources/cpp_suite/realistic1.jsonnet`
  - `./mill bench.runRegressions`
- Measurements:
  - Initial same-branch snapshots (non-Level-4):
    - `MainBenchmark.main`: `4.037 -> 3.424 ms/op`
    - `OptimizerBenchmark.main`: `0.557 -> 0.546 ms/op`
    - Focused regressions after change:
      - `manifestJsonEx`: `0.082 ms/op`
      - `manifestYamlDoc`: `0.082 ms/op`
      - `manifestTomlEx`: `0.096 ms/op`
      - `realistic1`: `2.772 ms/op`
    - Full keep-gate completed in `00:07:22` (exit code `0`), including:
      - `manifestJsonEx`: `0.077 ms/op`
      - `manifestYamlDoc`: `0.081 ms/op`
      - `manifestTomlEx`: `0.092 ms/op`
      - `realistic1`: `2.764 ms/op`
      - `comparison2`: `75.605 ms/op`
  - Level-4 branch-vs-base follow-up (base worktree at `9cc087697a8dc05433da4f6c9aadc0b9cb9e2158`, candidate = same SHA + working-tree wave changes, same machine/JDK 21.0.9 Azul):
    - `MainBenchmark.main`: `3.442 -> 3.653 ms/op` (branch slower, +6.13%)
    - `OptimizerBenchmark.main`: `0.573 -> 0.662 ms/op` (branch slower, +15.53%)
    - `manifestJsonEx`: `0.085 -> 0.077 ms/op` (branch faster, -9.41%)
    - `manifestYamlDoc`: `0.083 -> 0.081 ms/op` (branch faster, -2.41%)
    - `manifestTomlEx`: `0.098 -> 0.095 ms/op` (branch faster, -3.06%)
    - `realistic1`: `2.744 -> 2.688 ms/op` (branch faster, -2.04%)
- Notes:
  - change was intentionally narrow to static-layout-backed objects (`Val.staticObject` path) and did not reintroduce rejected visible-read caches.
  - focused regressions (`manifest*`, `realistic1`) are targeted spot checks for this path and are not by themselves a broad keep claim.
  - Level-4 branch-vs-base broad gates regressed (`MainBenchmark.main` +6.13%, `OptimizerBenchmark.main` +15.53%), so the lane was rejected.
  - post-revert verification: `git --no-pager diff -- sjsonnet/src/sjsonnet/Val.scala sjsonnet/src/sjsonnet/StaticOptimizer.scala` returned empty output (no kept source diff).
  - detailed evidence is captured in `bench/reports/static-lookup-wave.md`.

## Wave 23: optimizer-helper-wave
- Scope: while-loop in `tryStaticApply` replacing `forall`+`map`, short-circuit rebind when positional args match arity.
- Outcome: **kept**.
- Commit: `9a67672f`
- Validation:
  - Tests: 59/59 passed
  - Full regression suite: 125/125 SUCCESS in 446s
  - A/B comparison:
    - `realistic1`: `3.137 → 2.658 ms/op` (-15.3%)
    - `gen_big_object`: `1.271 → 1.163 ms/op` (-8.5%)
    - `bench.06`: `0.463 → 0.427 ms/op` (-7.8%)
    - `bench.07`: `3.578 → 3.317 ms/op` (-7.3%)

## Wave 24: evaluator-allocation-wave
- Scope: replace `map` with while-loops in `visitApply`, `visitArr`, `visitComp`; add `visitCompArr` array-based comprehension handler.
- Outcome: **rejected**.
- Reason: A/B benchmark comparison showed essentially neutral results. Some benchmarks showed slight improvements, others slight regressions — all within noise.
- Key insight: JVM JIT already optimizes evaluator core loops well; while-loop replacements provide no benefit.

## Wave 25: stdlib-allocation-wave
- Scope: Range while-loop, Foldl while-loop, sum/avg while-loop, contains while-loop in stdlib.
- Outcome: **rejected**.
- Reason: A/B comparison showed neutral/mixed results. Isolated Range-only change caused `comparison2` to regress significantly (75→99 ms).
- Key insight: Scala `Range.map` chain is optimized better by JIT than manual while-loops.

## Wave 26: optimizer-cleanup-wave
- Scope: Replace `forall`+`map` with single while-loop in `Arr` literal folding; replace `forall` with while-loop in `optimizeMemberList`.
- Outcome: **rejected**.
- Reason: Changes are correct but optimizer runs once per cold load, so improvements are too small to measure in hot benchmarks (all within noise).

## Wave 27: new-evaluator-default
- Scope: Enable `NewEvaluator` (tag-based `@switch` dispatch) as default.
- Outcome: **rejected**.
- Reason: NewEvaluator is 7-10% slower across all benchmarks despite using `@switch` dispatch.
- Key insight: JIT optimizes pattern-match cascade better than `@switch` on byte tags with `asInstanceOf` casts.
- Results:
  - `comparison2`: `76.775 → 82.423 ms/op` (+7.4% regression)
  - `bench.02`: `48.671 → 53.722 ms/op` (+10.4% regression)

## Wave 28: sort-inplace-wave
- Scope: Replace `map`+`sortBy` chain with in-place `java.util.Arrays.sort` for numeric and string sorting in `SetModule.sortArr`.
- Outcome: **kept**.
- Commit: `af1e090e`
- Validation:
  - Tests: all passed (177 in main suite)
  - Full regression suite: 35/35 benchmarks pass with no regressions
  - A/B comparison:
    - `bench.06` (sort benchmark): `0.481 → 0.430 ms/op` (-10.6%)
  - Second confirmation: `0.481 → 0.430 ms/op` (-10.6%)

## Wave 29: const-member-wave
- Scope: For object fields with simple bodies (Val literals or parent-scope ValidId references that are already evaluated), use `Val.Obj.ConstMember` instead of anonymous Member closures. ConstMember.invoke returns the stored value directly — no scope extension, no visitExpr dispatch.
- Outcome: **kept**.
- Commit: `743a61e1`
- Validation:
  - Tests: 32/32 passed
  - Full regression suite: 35/35 benchmarks pass, no regressions
  - Key benchmark improvements:
    - `realistic2`: `74.4 → 55.8 ms/op` (-25.0%)
    - `realistic2` alloc: `186MB → 173MB` (-7.0%)
    - `bench.02`: `32.4 ms/op` (no regression)
  - Applied to all three visitMemberList paths (single-field, inline 2-8, general 9+)

## Wave 30: loop-invariant-hoisting
- Scope: For nested comprehensions `[body for x in A for y in B]` where B doesn't depend on x and body is non-capturing, evaluate B once before the outer loop and use a single mutable scope with 2 extra slots.
- Outcome: **kept**.
- Commit: `abdceb02`
- Key changes:
  - `isInvariantExpr(e, maxIdx)`: checks if expression only references scope variables below maxIdx
  - `visitCompTwoLevel()`: extracted helper method for the two-level invariant case
  - Includes BinaryOp fast path for `ValidId op ValidId` bodies (inline scope lookups + Num-Num dispatch)
- Critical fix: initial inline implementation (60+ lines in `visitCompInline`) caused JIT degradation on realistic2 (55ms → 94ms). Extracting into a separate method restored performance.
- Lesson: method size matters — hot methods that grow too large degrade JIT compilation quality and inlining decisions.
- Validation:
  - Tests: 53/53 passed
  - Full regression suite: 35/35 benchmarks pass, no regressions
  - comparison2 A/B focused benchmark: `20.8ms → 18.5ms` (-11% time improvement)
  - realistic2 regression suite: `55.3ms` (no regression from ConstMember Wave 29)
  - comparison2 regression suite: `21.9ms`

## Wave 31: invariant-body-hoisting
- Scope: For comprehensions `[body for x in A]` where body doesn't reference x, evaluate body once and replicate the result across all iterations.
- Outcome: **kept**.
- Commit: `3797990f`
- Validation:
  - Tests: 167/167 passed (all suites)
  - Full regression suite: 35/35 pass, no regressions
  - Optimization fires correctly (confirmed via diagnostics) but has minimal measurable impact on current benchmarks because most invariant bodies are already pre-computed Val.Literal instances.
- Key insight: Val.Arr, Val.Num, Val.Str all extend Val.Literal (extends Val with Expr). When a body evaluates to a Val.Literal, visitExpr returns it directly, making the optimization a no-op. The optimization will benefit future cases with genuinely unevaluated invariant bodies.

## Wave 32: small-integer-num-cache
- Scope: Add a 256-entry pool of pre-allocated Val.Num instances for integers 0–255. Applied to all evaluator arithmetic operations (+, -, *, /, %, unary, bitwise, shift) and base64DecodeBytes.
- Outcome: **kept**.
- Commit: `9b989b0a`
- Validation:
  - Tests: 23/23 test suites passed
  - Full regression suite: 35/35 benchmarks pass, no regressions
  - Key benchmark improvements:
    - `bench.03` (fibonacci): `15.3 → 13.6 ms/op` (-11.1%), alloc `20.5MB → 11.7MB` (-42.6%)
    - `realistic2`: `55 → 48.9 ms/op` (-11.1%)
    - `comparison2`: `21.9 → 19.8 ms/op` (-9.6%)
    - `base64DecodeBytes`: `9 → 7.7 ms/op` (-14.4%)
    - `bench.02`: `~32 → 31.4 ms/op` (-2%)
  - GC metrics for bench.03: count 381→234 (-38.6%), time 166ms→84ms (-49.4%)
- Design: `Val.cachedNum(pos, d)` checks `d.toInt` in 0-255 and `i.toDouble == d` — branch-free for non-matching values. Safe because evaluator-created Val.Num pos is never mutated at runtime (only StaticOptimizer mutates pos, and it creates fresh instances).
