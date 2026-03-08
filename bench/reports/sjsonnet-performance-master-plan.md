# sjsonnet Performance Master Plan

Scope: JVM-first, benchmark-backed optimization plan for this branch. This document consolidates the current internal scan, external research, baseline measurements, and the canonical `bench/` performance docs into one executable plan.

## 1. Executive summary

sjsonnet already has nine benchmark-backed optimization waves recorded in `bench/OPTIMIZATION_LOG.md`, and the strongest internal signal is now clear: **allocation and data-shape work in `StaticOptimizer`, string formatting, and object-key reuse have produced real wins, while evaluator-dispatch reshuffles and defaulting `NewEvaluator` have not held up under broader gates**. The current baseline in `bench/reports/performance-progress.md` is a full `bench.runRegressions` pass over 35 cases in **7m16s**, with the slowest rows at **`cpp_suite/realistic2.jsonnet` 74.539 ms/op**, **`go_suite/comparison2.jsonnet` 72.629 ms/op**, and **`cpp_suite/bench.02.jsonnet` 50.931 ms/op**.

The repo’s own evidence points to a JVM-first plan built around **parser allocation trimming, further optimizer-side lowering and scratch-state reuse, string/template/render fast paths, object field/super-chain cost reduction, and selected cache improvements**. `bench/AST_VISIT_COUNTS.md` shows the runtime is dominated by `ValidId` (~38.33%) and `BinaryOp` (~29.85%), but `bench/BENCHMARK_LADDER.md` and `bench/OPTIMIZATION_LOG.md` both show that diagnostic hotspot data is **not** benchmark authority; multiple evaluator-shape experiments were reverted despite intuitive hotspot stories.

External research sharpens the target rather than changing the direction. `bench/reports/external-engine-performance-research.md` and `bench/EXTERNAL_ENGINE_ANALYSIS.md` show the biggest published jrsonnet advantages over sjsonnet are in **string-heavy** and **manifest/object-heavy** classes, e.g. **large string join 5.6 ms vs 331.1 ms**, **large string template 6.7 ms vs 392.7 ms**, **realistic1 12.6 ms vs 382.3 ms**, and **kube-prometheus 129.4 ms vs 947.9 ms**. The right takeaway is **not** “build a second JIT inside the JVM”; it is “borrow better benchmark discipline, better lowering, stronger cache layering where it actually adds new reuse, and more explicit string/object/render work.”

Two roadmap cleanups are mandatory before doing more implementation work:

- `bench/OPTIMIZATION_ROADMAP.md` items for **dynamic `%` format caching** and **sorted visible-key caching** are stale because they already shipped and are documented in `bench/OPTIMIZATION_LOG.md`.
- Do **not** repeat “cache optimized ASTs above parse cache” as a generic task. `sjsonnet/src/sjsonnet/Interpreter.scala` currently routes imported code through `CachedResolver.process`, and that processing already applies `StaticOptimizer` before the result is stored in `ParseCache`. Any future cache work must therefore target something **new**, such as a lowered representation, dependency metadata, or repeated-run structures that are not already covered by the current parse+optimize cache.

## 2. Current baseline / evidence snapshot

### 2.1 Internal baseline from `bench/reports/performance-progress.md`

| Metric | Current baseline |
| --- | --- |
| Full regression suite runtime | **7m16s** |
| Cases measured | **35** |
| Min / median / mean / max | **0.053 / 0.734 / 9.123 / 74.539 ms/op** |
| Slowest case 1 | `bench/resources/cpp_suite/realistic2.jsonnet` — **74.539 ms/op** |
| Slowest case 2 | `bench/resources/go_suite/comparison2.jsonnet` — **72.629 ms/op** |
| Slowest case 3 | `bench/resources/cpp_suite/bench.02.jsonnet` — **50.931 ms/op** |
| Benchmark JVM | Zulu OpenJDK **21.0.9** via Mill/JMH |
| Benchmark mode | JMH `AverageTime`, **1 fork**, **1 warmup x 2s**, **1 measurement x 10s** |

### 2.2 What already worked on this branch

These are the strongest retained wins from `bench/OPTIMIZATION_LOG.md` and should shape the next plan:

| Wave | Change | Evidence |
| --- | --- | --- |
| Wave 6 | Reduce `StaticOptimizer` scope-building allocation churn | `OptimizerBenchmark.main` **0.554 -> 0.545 ms/op**; `MainBenchmark.main` **3.252 -> 2.952 ms/op**; `comparison2` **72.801 -> 72.631 ms/op** |
| Wave 8 | Reuse mutable `Scope` shell during consecutive binding rewrites | `OptimizerBenchmark.main` **0.551 -> 0.542 ms/op**; `MainBenchmark.main` **2.938 -> 2.793 ms/op**; targeted `comparison2` **75.632 -> 72.272 ms/op** |
| Wave 9 | Cache parsed dynamic `%` formats | focused dynamic-format case **8.946 -> 4.839 ms/op**; `MainBenchmark.main` **3.141 -> 3.085 ms/op** |
| Wave 9 | Lazily cache sorted object key arrays | `MainBenchmark.main` **3.237 -> 2.903 ms/op**; manifest rows flat-to-slightly-better |

The recurring pattern is that **mechanical allocation reduction and explicit reuse in optimizer/runtime support code have paid off**, while broad evaluator redesign has not yet converted into safe suite-level wins.

### 2.3 What not to repeat without materially new evidence

From `bench/OPTIMIZATION_LOG.md` and `bench/BENCHMARK_LADDER.md`:

- **Wave 3 dispatch/tag reordering** regressed `MainBenchmark.main` and `OptimizerBenchmark.main`; do not re-run that idea as a default JVM tactic.
- **Wave 4 default `NewEvaluator`** was effectively flat on `MainBenchmark.main` and regressed `comparison2`; do not make evaluator-default changes based on corpus elapsed time.
- **Wave 5 array compare/concat micro-opts** were benchmark-negative; do not return to those code paths unless a new workload or profiler trace proves the situation changed.
- **`bench/AST_VISIT_COUNTS.md` elapsed time is diagnostic only**. It is useful for hotspot ranking, but not for claiming evaluator-wide wins.

### 2.4 Hotspot distribution and what it implies

`bench/AST_VISIT_COUNTS.md` shows the dominant evaluator traffic on the success corpus is:

| Tag / arm | Count | Share |
| --- | ---: | ---: |
| `ValidId` | 73,011 | 38.33% |
| `BinaryOp` | 56,856 | 29.85% |
| `Val.Literal` | 37,473 | 19.67% |
| `Select` | 6,689 | 3.51% |

That distribution supports two conclusions:

1. **Lowering and specialization around `ValidId`, `BinaryOp`, and `Select` are plausible high-upside areas.**
2. **Raw dispatch reshuffling is not enough by itself**, because the repo already tried a data-driven reorder and it regressed real measurements.

### 2.5 External gap snapshot and target classes

From `bench/reports/external-engine-performance-research.md` and `bench/EXTERNAL_ENGINE_ANALYSIS.md`, the biggest published gaps vs jrsonnet are in these classes:

| Class | Example evidence |
| --- | --- |
| String-heavy | `large_string_join`: **5.6 ms (jrsonnet) vs 331.1 ms (sjsonnet)** |
| String/template-heavy | `large_string_template`: **6.7 ms vs 392.7 ms** |
| Realistic config/object build | `realistic1`: **12.6 ms vs 382.3 ms** |
| Manifest/object-heavy | `kube-prometheus`: **129.4 ms vs 947.9 ms** |

This means the campaign should track progress **by workload class**, not by one aggregate headline number.

### 2.6 Current documentation state and stale ideas

- `bench/BENCHMARK_LADDER.md` is already the canonical protocol for promotion from hotspot guess -> focused benchmark -> class regression -> full gate -> branch-vs-base proof.
- `bench/OPTIMIZATION_ROADMAP.md` still contains partially stale items. Treat the shipped `%` format cache and sorted-key cache as baseline, then build on them instead of re-proposing them.
- Because `Interpreter.createResolver` already runs `StaticOptimizer` before entries hit `ParseCache`, any new cache proposal must be phrased as one of:
  - lowered-form reuse,
  - import/dependency metadata reuse,
  - repeated-run/shared-cache improvements,
  - or manifest/render-side reuse that is not already stored in parse cache.

## 3. Priority-ranked optimization backlog

The ranking below balances expected ROI, risk, and the amount of repository evidence already pointing at the area. Each item is intentionally small enough to support a multi-commit campaign.

### 1. Parser number + string fast paths

- **Subsystem/files:** `sjsonnet/src/sjsonnet/Parser.scala`, `bench/src/sjsonnet/bench/ParserBenchmark.scala`
- **Hypothesis:** Avoiding unconditional `replace("_", "")`, adding no-underscore and no-escape fast paths, and bypassing fragment-sequence assembly for trivial strings will cut parser allocation enough to improve parser-heavy and string-heavy workloads with low semantic risk.
- **Expected impact:** **Medium-high** on parser-heavy cases; **small-medium** end-to-end; useful groundwork for string/template gaps.
- **Risk:** **Low-medium**; parser regressions are possible but highly testable.
- **Validation command set:**
  ```bash
  ./mill 'sjsonnet.jvm[3.3.7]'.test
  ./mill bench.runJmh -i 5 -wi 5 -f 1 'sjsonnet.bench.ParserBenchmark.main'
  ./mill bench.runJmh -i 5 -wi 5 -f 1 'sjsonnet.bench.MainBenchmark.main'
  ./mill bench.runRegressions bench/resources/cpp_suite/large_string_join.jsonnet
  ./mill bench.runRegressions bench/resources/cpp_suite/large_string_template.jsonnet
  ```
- **Recommended commit granularity:** **2 commits** — one for numeric fast paths, one for string-literal fast paths.

### 2. String/template fast paths beyond the shipped dynamic `%` cache

- **Subsystem/files:** `sjsonnet/src/sjsonnet/Format.scala`, string/manifest/render helpers under `sjsonnet/src/sjsonnet/stdlib/`, `sjsonnet/src/sjsonnet/Materializer.scala`
- **Hypothesis:** The external gap is largest on string join/template workloads, so ASCII/common-case join/template/render paths can provide high-ROI wins without reopening evaluator architecture.
- **Expected impact:** **High targeted** on string-heavy and template-heavy classes; potentially **medium** on `MainBenchmark.main` and realistic config workloads.
- **Risk:** **Low-medium**; semantics around escaping, Unicode/code-point behavior, and formatting must be preserved.
- **Validation command set:**
  ```bash
  ./mill 'sjsonnet.jvm[3.3.7]'.test
  ./mill bench.runRegressions bench/resources/cpp_suite/large_string_join.jsonnet
  ./mill bench.runRegressions bench/resources/cpp_suite/large_string_template.jsonnet
  ./mill bench.runRegressions bench/resources/cpp_suite/realistic1.jsonnet
  ./mill bench.runJmh -i 5 -wi 5 -f 1 'sjsonnet.bench.MainBenchmark.main'
  ./mill bench.runRegressions
  ```
- **Recommended commit granularity:** **2-3 commits** — split join/template work from render/manifest work.

### 3. Continue `StaticOptimizer` scratch-state reuse and scope-bookkeeping reduction

- **Subsystem/files:** `sjsonnet/src/sjsonnet/StaticOptimizer.scala`
- **Hypothesis:** Waves 6 and 8 already proved that small allocation reductions in optimizer scope handling pay off. More loop-based rewriting, scratch-array reuse, or narrower mutable-shell reuse should keep producing measurable wins.
- **Expected impact:** **Medium** with **high confidence** because this lane already worked twice.
- **Risk:** **Low-medium** if changes stay mechanical and preserve existing AST semantics.
- **Validation command set:**
  ```bash
  ./mill 'sjsonnet.jvm[3.3.7]'.test
  ./mill bench.runJmh -i 5 -wi 5 -f 1 'sjsonnet.bench.OptimizerBenchmark.main'
  ./mill bench.runJmh -i 5 -wi 5 -f 1 'sjsonnet.bench.MainBenchmark.main'
  ./mill bench.runRegressions bench/resources/go_suite/comparison2.jsonnet
  ./mill bench.runRegressions
  ```
- **Recommended commit granularity:** **1 small commit per transformed optimizer hotspot** so each change can be kept or reverted independently.

### 4. Parser object-member single-pass collection

- **Subsystem/files:** `sjsonnet/src/sjsonnet/Parser.scala`
- **Hypothesis:** Replacing multiple post-parse passes (duplicate checks plus filtered extraction of binds/fields/asserts) with one collection pass reduces parser allocation and object-heavy parse cost without changing the public AST.
- **Expected impact:** **Medium** on parser-heavy and large-object workloads.
- **Risk:** **Low-medium** because duplicate-detection behavior must remain exact.
- **Validation command set:**
  ```bash
  ./mill 'sjsonnet.jvm[3.3.7]'.test
  ./mill bench.runJmh -i 5 -wi 5 -f 1 'sjsonnet.bench.ParserBenchmark.main'
  ./mill bench.runRegressions bench/resources/cpp_suite/gen_big_object.jsonnet
  ./mill bench.runRegressions bench/resources/cpp_suite/realistic1.jsonnet
  ./mill bench.runJmh -i 5 -wi 5 -f 1 'sjsonnet.bench.MainBenchmark.main'
  ```
- **Recommended commit granularity:** **2 commits** — add/confirm the object-heavy benchmark coverage first, then land the parser change.

### 5. Parse-cache key fingerprinting and safer JVM-default concurrency

- **Subsystem/files:** `sjsonnet/src/sjsonnet/ParseCache.scala`, `sjsonnet/src/sjsonnet/Importer.scala`, `sjsonnet/src/sjsonnet/Interpreter.scala`, `bench/src/sjsonnet/bench/MultiThreadedBenchmark.scala`
- **Hypothesis:** Replacing full-source-string cache-key behavior with a stable fingerprint and improving the default JVM cache story for shared/repeated evaluation should reduce hashing/retention overhead and make reuse-heavy workloads safer.
- **Expected impact:** **Medium** for repeated-run, daemon, and multi-thread/shared-cache scenarios; **small** for cold single-file runs.
- **Risk:** **Low-medium**; invalidation and thread-safety must stay correct.
- **Validation command set:**
  ```bash
  ./mill 'sjsonnet.jvm[3.3.7]'.test
  ./mill bench.runJmh -i 5 -wi 5 -f 1 'sjsonnet.bench.MultiThreadedBenchmark.main'
  ./mill bench.runJmh -i 5 -wi 5 -f 1 'sjsonnet.bench.MainBenchmark.main'
  ./mill bench.runRegressions
  ```
- **Recommended commit granularity:** **2 commits** — one for cache-key/fingerprint work, one for concurrent-cache behavior or defaulting.

### 6. Materializer/render specialization on top of the shipped sorted-key cache

- **Subsystem/files:** `sjsonnet/src/sjsonnet/Materializer.scala`, manifest/render builtins under `sjsonnet/src/sjsonnet/stdlib/`, `sjsonnet/src/sjsonnet/Val.scala`
- **Hypothesis:** Since sorted visible keys are already cached, the next layer is to reduce repeated manifest/render traversal and repeated small allocations in materialization.
- **Expected impact:** **Medium** on manifest/render-heavy classes; potentially **medium** on `MainBenchmark.main` because the prior sorted-key cache moved that benchmark materially.
- **Risk:** **Medium**; rendering behavior is user-visible, so correctness gates must stay strict.
- **Validation command set:**
  ```bash
  ./mill 'sjsonnet.jvm[3.3.7]'.test
  ./mill bench.runJmh -i 5 -wi 5 -f 1 'sjsonnet.bench.MaterializerBenchmark.*'
  ./mill bench.runRegressions bench/resources/go_suite/manifestJsonEx.jsonnet
  ./mill bench.runRegressions bench/resources/go_suite/manifestYamlDoc.jsonnet
  ./mill bench.runRegressions bench/resources/go_suite/manifestTomlEx.jsonnet
  ./mill bench.runJmh -i 5 -wi 5 -f 1 'sjsonnet.bench.MainBenchmark.main'
  ./mill bench.runRegressions
  ```
- **Recommended commit granularity:** **2 commits** — one for benchmark/measurement scaffolding if needed, one for the first render/materialize fast path.

### 7. Object field/super-chain read-view caching

- **Subsystem/files:** `sjsonnet/src/sjsonnet/Val.scala`, `sjsonnet/src/sjsonnet/Evaluator.scala`, `sjsonnet/src/sjsonnet/Materializer.scala`, `sjsonnet/src/sjsonnet/stdlib/ObjectModule.scala`
- **Hypothesis:** A cached merged read view for the common “stable super chain / no exclusions / repeated reads” case can cut object-heavy and manifest-heavy overhead more effectively than more evaluator dispatch tweaks.
- **Expected impact:** **High targeted** on manifest/object classes and realistic configs.
- **Risk:** **Medium-high** because Jsonnet inheritance semantics are subtle.
- **Validation command set:**
  ```bash
  ./mill 'sjsonnet.jvm[3.3.7]'.test
  ./mill bench.runJmh -i 5 -wi 5 -f 1 'sjsonnet.bench.MaterializerBenchmark.*'
  ./mill bench.runRegressions bench/resources/go_suite/manifestJsonEx.jsonnet
  ./mill bench.runRegressions bench/resources/go_suite/manifestYamlDoc.jsonnet
  ./mill bench.runRegressions bench/resources/go_suite/manifestTomlEx.jsonnet
  ./mill bench.runRegressions bench/resources/cpp_suite/realistic1.jsonnet
  ./mill bench.runRegressions bench/resources/cpp_suite/realistic2.jsonnet
  ./mill bench.runRegressions
  ```
- **Recommended commit granularity:** **2-3 commits** — benchmark-first, narrow fast path, then any broadening only if the first slice is clearly positive.

### 8. Precise post-cache reuse: lowered-form or dependency-metadata reuse, not another generic optimized-AST cache

- **Subsystem/files:** `sjsonnet/src/sjsonnet/Interpreter.scala`, `sjsonnet/src/sjsonnet/ParseCache.scala`, `sjsonnet/src/sjsonnet/Importer.scala`, any new lowered-representation cache module
- **Hypothesis:** Because the current parse cache already stores optimized expressions, the next reusable layer should target something the repo does **not** currently retain: a lowered representation, dependency metadata, or repeated-run structures for import-heavy workloads.
- **Expected impact:** **Medium-high** on repeated-import and warm/daemon workflows if the new layer avoids real repeated work; **low** elsewhere.
- **Risk:** **Medium**; cache scope and invalidation boundaries must be explicit.
- **Validation command set:**
  ```bash
  ./mill 'sjsonnet.jvm[3.3.7]'.test
  ./mill bench.runJmh -i 5 -wi 5 -f 1 'sjsonnet.bench.MultiThreadedBenchmark.main'
  ./mill bench.runJmh -i 5 -wi 5 -f 1 'sjsonnet.bench.OptimizerBenchmark.main'
  ./mill bench.runJmh -i 5 -wi 5 -f 1 'sjsonnet.bench.MainBenchmark.main'
  ./mill bench.runRegressions
  ```
  Before shipping this as a default path, add a dedicated repeated-import / repeated-eval workload and require it in the wave gate.
- **Recommended commit granularity:** **2 commits** — first add the missing workload and cache boundary notes, then implement the reuse layer.

### 9. Replace selected stdlib collection combinators with manual loops

- **Subsystem/files:** `sjsonnet/src/sjsonnet/stdlib/ArrayModule.scala`, `sjsonnet/src/sjsonnet/stdlib/ObjectModule.scala`
- **Hypothesis:** Replacing `map`, `flatMap`, `slice ++ slice`, `indexWhere`, and `.map(...).sum` on hot helpers with explicit loops trims allocation at very low blast radius.
- **Expected impact:** **Small-medium** but with a good safety/effort ratio.
- **Risk:** **Low**.
- **Validation command set:**
  ```bash
  ./mill 'sjsonnet.jvm[3.3.7]'.test
  ./mill bench.runRegressions bench/resources/go_suite/member.jsonnet
  ./mill bench.runRegressions bench/resources/go_suite/reverse.jsonnet
  ./mill bench.runRegressions bench/resources/go_suite/foldl.jsonnet
  ./mill bench.runRegressions bench/resources/sjsonnet_suite/setDiff.jsonnet
  ./mill bench.runRegressions bench/resources/sjsonnet_suite/setInter.jsonnet
  ./mill bench.runRegressions bench/resources/sjsonnet_suite/setUnion.jsonnet
  ./mill bench.runJmh -i 5 -wi 5 -f 1 'sjsonnet.bench.MainBenchmark.main'
  ```
- **Recommended commit granularity:** **1 commit per builtin family** to keep blame and rollback clean.

### 10. Lower hot-path IR in `StaticOptimizer` for `ValidId`, numeric `BinaryOp`, `Select`, and common apply/builtin shapes

- **Subsystem/files:** `sjsonnet/src/sjsonnet/StaticOptimizer.scala`, `sjsonnet/src/sjsonnet/Expr.scala`, `sjsonnet/src/sjsonnet/Evaluator.scala`
- **Hypothesis:** The best structural bet is a cheaper lowered form for the hottest runtime shapes, produced before evaluation rather than through evaluator-dispatch surgery.
- **Expected impact:** **High** across multiple workload classes if the lowering is narrow and correct.
- **Risk:** **High**; this changes the pre-eval representation and must be introduced incrementally.
- **Validation command set:**
  ```bash
  ./mill 'sjsonnet.jvm[3.3.7]'.test
  ./mill bench.runJmh -i 5 -wi 5 -f 1 'sjsonnet.bench.OptimizerBenchmark.main'
  ./mill bench.runJmh -i 5 -wi 5 -f 1 'sjsonnet.bench.MainBenchmark.main'
  ./mill bench.runRegressions bench/resources/go_suite/comparison2.jsonnet
  ./mill bench.runRegressions bench/resources/cpp_suite/realistic1.jsonnet
  ./mill bench.runRegressions
  ```
  Promotion requires branch-vs-base comparison, not just local wins.
- **Recommended commit granularity:** **3-4 commits** — scaffolding, first lowered shapes, expansion to additional shapes, then cleanup/promotion.

### 11. Specialized comprehension executor with mutable frame reuse

- **Subsystem/files:** `sjsonnet/src/sjsonnet/Evaluator.scala`, `sjsonnet/src/sjsonnet/ValScope.scala`
- **Hypothesis:** `comparison2.jsonnet` and similar nested comprehensions currently amplify `ValScope` copying and intermediate array churn; a narrow mutable-frame fast path can remove that overhead.
- **Expected impact:** **High targeted** on comprehension-heavy cases and any real workloads with similar patterns.
- **Risk:** **High**; laziness, scope semantics, and error behavior are delicate here.
- **Validation command set:**
  ```bash
  ./mill 'sjsonnet.jvm[3.3.7]'.test
  ./mill bench.runRegressions bench/resources/go_suite/comparison2.jsonnet
  ./mill bench.runRegressions bench/resources/go_suite/comparison.jsonnet
  ./mill bench.runJmh -i 5 -wi 5 -f 1 'sjsonnet.bench.MainBenchmark.main'
  ./mill bench.runRegressions
  ```
  A dedicated comprehension JMH should be added before broadening this fast path.
- **Recommended commit granularity:** **2-3 commits** — comprehension benchmark first, opt-in fast path second, broader rollout only if it survives the full gate.

### 12. Static object field-name / symbol specialization

- **Subsystem/files:** `sjsonnet/src/sjsonnet/Val.scala`, `sjsonnet/src/sjsonnet/StaticOptimizer.scala`
- **Hypothesis:** Pre-indexed/static-key handling for static objects can reduce repeated hashing and lookup cost in object-heavy workloads without changing dynamic-object semantics.
- **Expected impact:** **Medium** with upside on manifest/object classes.
- **Risk:** **Medium-high** because static and dynamic object behavior must remain unified at the semantic level.
- **Validation command set:**
  ```bash
  ./mill 'sjsonnet.jvm[3.3.7]'.test
  ./mill bench.runJmh -i 5 -wi 5 -f 1 'sjsonnet.bench.MaterializerBenchmark.*'
  ./mill bench.runRegressions bench/resources/go_suite/manifestJsonEx.jsonnet
  ./mill bench.runRegressions bench/resources/cpp_suite/realistic1.jsonnet
  ./mill bench.runJmh -i 5 -wi 5 -f 1 'sjsonnet.bench.MainBenchmark.main'
  ./mill bench.runRegressions
  ```
- **Recommended commit granularity:** **2 commits** — profiling/benchmark coverage first, specialization second.

## 4. Recommended execution waves / order

### Wave 0 — measurement hygiene and workload coverage

Goal: make later wins trustworthy and class-targeted.

1. Add or promote benchmark coverage for the missing workload classes called out by `bench/BENCHMARK_LADDER.md` and the external research:
   - string-heavy / template-heavy,
   - object-heavy / manifest-heavy,
   - import-heavy / repeated-run,
   - render/materialize-heavy,
   - comprehension-heavy.
2. Normalize the evidence template used for each wave in `bench/OPTIMIZATION_LOG.md`.
3. Treat `bench/reports/performance-progress.md` as the current suite baseline until a later wave earns a replacement baseline.

Why first: the repo already learned that intuitive low-level changes can look good locally and still lose on broader checks.

### Wave 1 — safest high-ROI parser and cache hygiene

Start with the best ROI/risk items that have clear local benchmarks and low semantic blast radius:

1. Item 1 — parser number fast paths
2. Item 1 — parser string fast paths
3. Item 4 — parser object-member single-pass collection
4. Item 5 — parse-cache key fingerprinting
5. Item 5 — safer JVM concurrent-cache default
6. Item 9 — selected stdlib loop rewrites

Stop condition: if these changes do not move any class-level workloads measurably, re-profile before entering deeper structural work.

### Wave 2 — exploit the biggest external gap classes

Focus on the areas where jrsonnet’s published lead is largest and where sjsonnet already has supporting internal evidence:

1. Item 2 — string/template fast paths
2. Item 6 — materializer/render specialization on top of sorted-key reuse
3. Item 7 — object read-view/super-chain caching
4. Item 12 — static object key/symbol specialization if profiling justifies it

This wave is the clearest path toward class-by-class improvements against the external string and manifest/object gaps.

### Wave 3 — extend the proven optimizer lane

1. Item 3 — additional `StaticOptimizer` scratch reuse and bookkeeping cleanup
2. Item 10 — lowered hot-path IR scaffolding
3. Item 10 — first lowered forms for `ValidId`, numeric `BinaryOp`, and simple `Select`
4. Item 8 — post-cache reuse only if it targets something new beyond the current parse+optimize cache

Why now: the optimizer lane already produced retained wins, so it is the best structural area to push next.

### Wave 4 — only one risky evaluator/runtime prototype at a time

1. Item 11 — specialized comprehension executor, or
2. a narrow extension of Item 10 if the lowered IR already absorbs the same hotspot.

Do **not** attempt multiple risky runtime redesigns in the same wave. `comparison2.jsonnet` has already demonstrated how easy it is to win one targeted number and still lose the broader gate.

### Wave 5 — consolidation, proof, and baseline refresh

1. Run branch-vs-base comparisons for any wave that survived its local gates.
2. Keep only the waves that are positive on the target class and non-negative on the full regression gate.
3. Refresh `bench/reports/performance-progress.md` only after a new suite baseline has actually been earned and documented.

## 5. Benchmark protocol / decision gates

Use `bench/BENCHMARK_LADDER.md` as the canonical policy. The master plan below is simply the operational form of that document.

### Gate A — correctness first

Every candidate wave must pass the relevant correctness suite before timing claims are taken seriously:

```bash
./mill 'sjsonnet.jvm[3.3.7]'.test
```

If the change is parser-heavy, manifest-heavy, or stdlib-heavy, also run the most obviously relevant focused regressions before broader JMH work.

### Gate B — focused benchmark matched to the hypothesis

Use the existing focused JMH entry points and only compare the benchmark that matches the subsystem being changed:

```bash
./mill bench.runJmh -i 5 -wi 5 -f 1 'sjsonnet.bench.ParserBenchmark.main'
./mill bench.runJmh -i 5 -wi 5 -f 1 'sjsonnet.bench.OptimizerBenchmark.main'
./mill bench.runJmh -i 5 -wi 5 -f 1 'sjsonnet.bench.MainBenchmark.main'
./mill bench.runJmh -i 5 -wi 5 -f 1 'sjsonnet.bench.MaterializerBenchmark.*'
./mill bench.runJmh -i 5 -wi 5 -f 1 'sjsonnet.bench.MultiThreadedBenchmark.main'
```

### Gate C — class-specific regression file(s)

Promote from micro/JMH results to the most relevant class anchor:

```bash
./mill bench.runRegressions bench/resources/go_suite/comparison2.jsonnet
./mill bench.runRegressions bench/resources/cpp_suite/large_string_join.jsonnet
./mill bench.runRegressions bench/resources/cpp_suite/large_string_template.jsonnet
./mill bench.runRegressions bench/resources/go_suite/manifestJsonEx.jsonnet
./mill bench.runRegressions bench/resources/go_suite/manifestYamlDoc.jsonnet
./mill bench.runRegressions bench/resources/go_suite/manifestTomlEx.jsonnet
```

Use additional class-specific rows as needed, but do not claim a broad win from a single favorable microbenchmark.

### Gate D — broad end-to-end benchmark

At minimum, a kept wave that touches runtime behavior should also survive:

```bash
./mill bench.runJmh -i 5 -wi 5 -f 1 'sjsonnet.bench.MainBenchmark.main'
```

### Gate E — full regression sanity gate

Before a wave is marked “kept,” run the full suite:

```bash
./mill bench.runRegressions
```

A targeted win that regresses the suite is a rejected wave, not a partial success.

### Gate F — branch-vs-base proof

For any non-trivial wave, require the branch-vs-base proof described in `bench/BENCHMARK_LADDER.md`:

1. compare against the merge base, parent checkpoint, or last known-good perf SHA,
2. pin JDK/JVM args/workload list,
3. warm both sides,
4. alternate run order,
5. record both SHAs,
6. only keep a wave if it is positive on the target class and non-negative on the broad gate.

### Hard rules

- Do **not** use `bench/AST_VISIT_COUNTS.md` elapsed time as proof of a win.
- If the result is inside obvious noise or only favorable in one order, treat it as **not proven**.
- If a wave only beats an external engine comparison but does not beat the repo’s own base revision, it is **not** a kept optimization wave.

## 6. Documentation & evidence policy

After each implementation wave, persist the result in the canonical `bench/` docs rather than relying on commit memory.

### Required updates after every kept or reverted wave

1. **`bench/OPTIMIZATION_LOG.md`**
   - Add the wave entry with:
     - hypothesis,
     - workload class,
     - files touched,
     - exact commands,
     - baseline revision,
     - candidate revision,
     - measured results,
     - correctness checks,
     - decision (`kept`, `reverted`, or `diagnostic only`).
2. **`bench/reports/performance-progress.md`**
   - Update only when a new full-suite baseline is actually earned or when the benchmark environment changes materially.
3. **`bench/BENCHMARK_LADDER.md`**
   - Update only if the methodology changes (new mandatory gates, new benchmark classes, or new evidence-template requirements).
4. **`bench/OPTIMIZATION_ROADMAP.md`**
   - Remove or reframe completed items once a wave ships, so the roadmap does not keep advertising already-landed work.
5. **`bench/reports/*.md` deep dives**
   - Use these for one-off research, class-specific investigations, or post-wave summaries that are too large for the optimization log.

### Evidence policy details

- Always record the exact benchmark command lines.
- Always record the JVM/JDK actually used by Mill/JMH.
- Always record the workload class being targeted.
- Always record whether the result is targeted-only, broad-gate-safe, or fully branch-vs-base proven.
- Prefer appending evidence over overwriting history; the goal is durable performance archaeology.

## 7. Proposed 10+ commit campaign structure

The goal is many small, safe commits, each with a clear benchmark story and an easy revert path.

1. **bench: add missing workload-class coverage and standardize wave evidence template**
   - Add or promote string/template, object/manifest, import/reuse, render, and comprehension anchors.
2. **parser: add numeric no-underscore fast path**
3. **parser: add trivial-string / no-escape fast path**
4. **parser: collapse object-member post-processing into one pass**
5. **parser: replace suffix closure chains with direct suffix builders**
6. **cache: switch parse-cache keying to a stable fingerprint / less-retentive form**
7. **cache: improve JVM concurrent parse-cache behavior and benchmark it**
8. **optimizer: next scope-bookkeeping allocation cleanup**
9. **stdlib: rewrite first hot helper family with manual loops**
10. **strings: fast-path join/template hot cases beyond the existing dynamic `%` cache**
11. **render: specialize materializer/manifest paths on top of sorted-key reuse**
12. **object-runtime: add narrow merged-read-view / super-chain cache for common cases**
13. **optimizer: introduce lowered hot-path IR scaffolding behind a guarded path**
14. **optimizer/evaluator: lower `ValidId`, numeric `BinaryOp`, and simple `Select` first**
15. **evaluator: prototype comprehension mutable-frame fast path behind opt-in gating**
16. **perf-docs: run branch-vs-base sweep, update `OPTIMIZATION_LOG.md`, refresh `performance-progress.md` if earned**

Recommended stop points:

- After commit 5: decide whether parser work is still moving class benchmarks.
- After commit 8 or 9: decide whether the optimizer lane is still the best short-term ROI.
- After commit 12: decide whether object/runtime work is closing more real gap than further parser/cache work.
- Before commit 15: require a fresh profiling pass; do not enter risky evaluator work by habit.

## 8. External ideas to borrow vs not borrow

### Borrow

These ideas are strongly supported by the external research and fit the JVM / current sjsonnet architecture:

1. **Rustanka-style benchmark discipline**
   - correctness before timing,
   - explicit base-vs-branch comparison,
   - saved artifacts,
   - workload-class labeling.
2. **Typed/lowered pre-execution representation**
   - borrow the best part of rustanka PR #60 without borrowing the Cranelift subsystem.
3. **Scratch-state reuse in compiler/optimizer-style passes**
   - this already matches sjsonnet’s retained optimizer wins.
4. **Explicit focus on string-heavy and manifest/object-heavy classes**
   - those are the biggest published jrsonnet advantages.
5. **Stronger cache layering only where it adds new reuse**
   - lowered forms, dependency metadata, repeated-run structures, not another copy of what parse cache already stores.
6. **Cleaner evaluator abstraction boundaries for experiments**
   - useful for A/B testing and guarded rollouts, not as a reason to maintain two permanent evaluators prematurely.

### Do not borrow as primary strategy

These ideas are low-transfer or actively misleading for sjsonnet’s JVM-first plan:

1. **A second JIT inside the JVM**
   - HotSpot/Graal already exist; this is very high complexity and low near-term ROI.
2. **Rust allocator / LTO / release-profile tricks**
   - the JVM analogue is allocation reduction and better benchmarking, not copying Rust build knobs.
3. **Rust ABI/value-layout tricks (`repr(C)` unions, manual native runtime ABI)**
   - wrong platform shape for this codebase.
4. **A second parser as a near-term implementation plan**
   - the idea behind parser separation is useful, but a second Scala parser is too much maintenance cost right now.
5. **Tanka-specific command-path specialization as engine strategy**
   - borrow the methodology, not the product-specific surfaces.
6. **Generic “cache optimized ASTs above parse cache” wording**
   - stale for this repo because current parse-cache flow already stores optimized expressions.

## 9. Recommended scoreboard for the campaign

To keep the “beat jrsonnet across benchmark classes” goal concrete, track wins by class rather than one aggregate number:

1. **String/template class**
   - `large_string_join`, `large_string_template`, string-heavy JMHs
2. **Manifest/object class**
   - `manifestJsonEx`, `manifestYamlDoc`, `manifestTomlEx`, object-heavy synthetic cases, `realistic1`, `realistic2`
3. **Comprehension/evaluator class**
   - `comparison2`, `comparison`, dedicated comprehension JMH
4. **Parser class**
   - `ParserBenchmark.main`, parser-heavy synthetic cases
5. **Optimizer class**
   - `OptimizerBenchmark.main` plus `MainBenchmark.main`
6. **Reuse/concurrency class**
   - `MultiThreadedBenchmark.main` plus repeated-import/repeated-eval benchmarks

A wave is only a success if it improves the intended class, survives the broad gate, and leaves durable evidence behind.
