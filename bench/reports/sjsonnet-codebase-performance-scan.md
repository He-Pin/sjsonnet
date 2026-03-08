# sjsonnet codebase performance scan

Date: 2026-03-07
Scope: JVM-focused review of parser, optimizer, evaluator, materializer, interpreter, runtime/value structures, importer/path/cache layers, stdlib hot spots, and current benchmark/debug surfaces.

## Executive summary

sjsonnet already contains several important JVM-aware optimizations:

- allocation-reduced lazy thunks (`LazyExpr`, `LazyApply1`, `LazyApply2`) in `sjsonnet/src/sjsonnet/Val.scala`
- arity-specialized apply nodes and TCO plumbing in `Expr.scala`, `StaticOptimizer.scala`, `Evaluator.scala`, and `Val.scala`
- hybrid recursive/iterative materialization in `sjsonnet/src/sjsonnet/Materializer.scala`
- lazy sorted-key reuse and dynamic `%` format caching already documented in `bench/OPTIMIZATION_LOG.md`
- a benchmark ladder and profiler/diagnostic surfaces in `bench/`

The highest-value remaining opportunities are not evaluator-dispatch reshuffles. The current evidence points instead to:

1. **comprehension and scope-frame churn** in `Evaluator.visitComp` + `ValScope`
2. **parser allocation trimming** for strings, numbers, object members, and suffix parsing
3. **object field/super-chain resolution cost** in `Val.Obj`
4. **further optimizer-side lowering / scope bookkeeping reduction** in `StaticOptimizer`
5. **parse/import cache key and concurrency strategy improvements**
6. **select stdlib collection combinators that still allocate more than necessary**

The repo’s own artifacts support this direction:

- `bench/AST_VISIT_COUNTS.md` shows runtime traffic dominated by `ValidId` (~38%) and `BinaryOp` (~30%), with `Select` next.
- `bench/OPTIMIZATION_LOG.md` shows several intuitive evaluator micro-opts and `NewEvaluator` defaulting were benchmark-negative or mixed.
- `bench/BENCHMARK_LADDER.md` explicitly calls out missing import-heavy, string-heavy, and object-heavy focused workloads.

## Hotspot map by subsystem

### 1) Evaluator / comprehension execution — highest priority

Relevant files:
- `sjsonnet/src/sjsonnet/Evaluator.scala:196-203`
- `sjsonnet/src/sjsonnet/Evaluator.scala:1229-1305`
- `sjsonnet/src/sjsonnet/ValScope.scala:17-62`
- `bench/resources/go_suite/comparison2.jsonnet:1`
- `bench/AST_VISIT_COUNTS.md:27-37`

Why it is hot:
- `comparison2.jsonnet` is a nested comprehension over `std.range`, and the repo already documents it as a regression catcher.
- `visitComp` materializes intermediate `Array[ValScope]` values and repeatedly calls `ValScope.extendSimple`, which copies the bindings array on every iteration.
- `ValScope.extend`, `extendSimple`, and `extendBy` all use `Arrays.copyOf` / `System.arraycopy`.
- AST visit counts show the steady-state evaluator is dominated by variable lookup and numeric binary ops, which is exactly the shape comprehension-heavy workloads amplify.

### 2) Parser allocation pressure — high priority

Relevant files:
- `sjsonnet/src/sjsonnet/Parser.scala:141-168`
- `sjsonnet/src/sjsonnet/Parser.scala:263-288`
- `sjsonnet/src/sjsonnet/Parser.scala:527-559`
- `sjsonnet/src/sjsonnet/Parser.scala:618-621`
- `sjsonnet/src/sjsonnet/Parser.scala:676-723`
- `bench/src/sjsonnet/bench/ParserBenchmark.scala:18-35`

Why it is hot:
- number parsing always does `numStr.replace("_", "")`, even when no underscores are present
- string parsing builds `Seq[String]` fragments and then `mkString`s them
- postfix parsing builds `Expr => Expr` closures for select/index/apply/object-extend suffixes
- object parsing does multiple passes over the same member sequence: duplicate-field detection, duplicate-local detection, then filtered array extraction for binds / fields / asserts

### 3) Object runtime / field lookup / super-chain work — high priority for manifest/object-heavy workloads

Relevant files:
- `sjsonnet/src/sjsonnet/Val.scala:417-454`
- `sjsonnet/src/sjsonnet/Val.scala:495-573`
- `sjsonnet/src/sjsonnet/Val.scala:604-709`
- `sjsonnet/src/sjsonnet/Materializer.scala:79-104`
- `sjsonnet/src/sjsonnet/Evaluator.scala:1352-1369`
- `sjsonnet/src/sjsonnet/stdlib/ObjectModule.scala:125-141`
- `bench/resources/go_suite/manifestJsonEx.jsonnet:1-46`

Why it is hot:
- `Val.Obj` still computes merged views and recursively walks super chains for value resolution
- `addSuper` allocates wrapper chains
- `gatherKeys` builds object chains and exclusion sets
- equality, materialization, and object builtins repeatedly enumerate keys and resolve values
- sorted visible keys are already cached, but field lookup and merged-super resolution are still dynamic

### 4) StaticOptimizer scope bookkeeping and rewrite cost — medium-high priority

Relevant files:
- `sjsonnet/src/sjsonnet/StaticOptimizer.scala:667-766`
- `sjsonnet/src/sjsonnet/StaticOptimizer.scala:861-980`
- `sjsonnet/src/sjsonnet/StaticOptimizer.scala:982-1035`
- `bench/src/sjsonnet/bench/OptimizerBenchmark.scala:18-70`
- `bench/OPTIMIZATION_LOG.md:61-77`
- `bench/OPTIMIZATION_LOG.md:110-141`

Why it is hot:
- recent kept wins in the repo came from reducing optimizer allocation churn, so this subsystem is already proven sensitive
- `nestedBindings`, `nestedNames`, and friends still do repeated immutable `HashMap.updated` chains
- the optimizer still rebuilds many arrays/lists and repeatedly rebinds callsites
- tailrec marking and apply rebinding are effective, but the system is still AST-centric rather than lowered/hot-path-centric

### 5) Parse/import cache layer — medium priority

Relevant files:
- `sjsonnet/src/sjsonnet/ParseCache.scala:27-37`
- `sjsonnet/src/sjsonnet/Importer.scala:160-179`
- `sjsonnet/src/sjsonnet/Importer.scala:182-249`
- `sjsonnet/src/sjsonnet/Interpreter.scala:85-115`
- `bench/src/sjsonnet/bench/MultiThreadedBenchmark.scala:21-35`

Why it is hot:
- `DefaultParseCache` is a mutable `HashMap` and explicitly not thread-safe
- `StaticResolvedFile.contentHash()` returns the entire content string, so parse-cache keys can retain large source strings and pay full-string hashing/comparison cost
- multi-thread benchmarking already has to inject a custom synchronized cache
- imported files are cached, but repeated evaluation / concurrency stories still depend heavily on the cache implementation supplied from outside

### 6) Stdlib array/object helpers with avoidable collection allocations — medium priority, low risk

Relevant files:
- `sjsonnet/src/sjsonnet/stdlib/ArrayModule.scala:152-176`
- `sjsonnet/src/sjsonnet/stdlib/ArrayModule.scala:428-519`
- `sjsonnet/src/sjsonnet/stdlib/ObjectModule.scala:131-141`

Why it is hot:
- several builtins still use `map`, `flatMap`, `indexWhere`, `slice ++ slice`, and `.map(...).sum` over lazy arrays
- those are readable, but they allocate intermediate arrays/options/functions on the JVM
- these are good low-blast-radius tuning candidates after measurement confirms workload relevance

## Concrete optimization candidates

### A. Specialized comprehension executor with mutable frame reuse

What to change:
- Introduce a fast path for common comprehension shapes (`for` / nested `for` / `if`) that reuses a mutable frame or stack-backed binding storage instead of allocating a fresh copied `ValScope` for every iteration.
- Keep the existing generic path as a fallback.

Rationale:
- Current `visitComp` creates many `ValScope` copies and intermediate arrays.
- This directly targets the known `comparison2` stress case and the dominant `ValidId`/`BinaryOp` runtime mix.

Risk / complexity:
- **High**. Scope semantics, laziness, and error behavior are subtle.
- Best implemented as an opt-in fast path for narrow patterns first.

Measurement strategy:
- `./mill bench.runRegressions bench/resources/go_suite/comparison2.jsonnet`
- `./mill bench.runJmh -i 5 -wi 5 -f 1 'sjsonnet.bench.MainBenchmark.main'`
- add a dedicated comprehension JMH with nested ranges and object comprehensions
- use JFR allocation profiling to confirm `ValScope` / array-copy reduction

### B. Reduce parser allocations in numeric, string, and object-member parsing

What to change:
- only call `replace("_", "")` when underscores are actually present
- add single-fragment / no-escape fast paths for string literals before `Seq[String]` + `mkString`
- replace multi-pass object-member post-processing with a single pass that collects binds, fields, asserts, and duplicate checks together
- explore a non-closure representation for postfix suffix chains

Rationale:
- All of these paths are visible in `Parser.scala` and map cleanly to JVM allocation reduction.

Risk / complexity:
- **Low to medium** if done incrementally.
- Parser correctness is easy to regress; lean on existing tests.

Measurement strategy:
- `./mill bench.runJmh -i 5 -wi 5 -f 1 'sjsonnet.bench.ParserBenchmark.main'`
- add focused parser inputs for large string templates, large object literals, and long select/apply suffix chains
- confirm no regressions in `MainBenchmark.main`

### C. Reduce `Val.Obj` super-chain and merged-view work

What to change:
- prototype a cached merged lookup/index structure for common cases: no exclusions, shallow stable super chain, or read-mostly objects
- consider a faster field-resolution path for non-dynamic/static-key objects
- avoid rebuilding wrapper chains or repeated merged traversal where a precomputed read view is safe

Rationale:
- Rendering/manifests/object builtins are object-key and field-lookup heavy.
- Sorted key caching is already done; field/value lookup is the next obvious layer.

Risk / complexity:
- **Medium-high** because Jsonnet inheritance semantics are subtle.
- Start with narrowly-scoped fast paths, not a global representation rewrite.

Measurement strategy:
- `bench/resources/go_suite/manifestJsonEx.jsonnet`
- `bench/resources/go_suite/manifestYamlDoc.jsonnet`
- `bench/resources/go_suite/manifestTomlEx.jsonnet`
- `sjsonnet.bench.MaterializerBenchmark.*`
- add an object-heavy synthetic workload with deep super chains and repeated field access

### D. Continue optimizer-side lowering rather than evaluator-shape experimentation

What to change:
- push `StaticOptimizer` toward lower-level hot-path forms for the most frequent cases: `ValidId`, numeric `BinaryOp`, simple `Select`, comprehension scaffolding, and common builtin/apply shapes
- consider replacing immutable `HashMap`-based symbol tracking in hot optimizer paths with a purpose-built mutable symbol table / stack discipline

Rationale:
- repo history already shows optimizer allocation reductions produce measurable wins
- repo history also shows evaluator dispatch surgery is not a reliable win

Risk / complexity:
- **Medium to high**, depending on how far lowering goes
- strong candidate for opt-in / staged rollout

Measurement strategy:
- `sjsonnet.bench.OptimizerBenchmark.main`
- `sjsonnet.bench.MainBenchmark.main`
- full `./mill bench.runRegressions`
- branch-vs-base comparison per `bench/BENCHMARK_LADDER.md`

### E. Improve parse cache keying and concurrency behavior

What to change:
- avoid using full source text as the cache key hash for `StaticResolvedFile`; use a stable digest or cached fingerprint instead
- provide a default JVM-grade concurrent parse cache implementation (e.g. CHM/Caffeine-backed) for repeated/multi-threaded use
- verify importer/read cache behavior under mixed repeated evaluations

Rationale:
- This is mostly about memory retention, hashing cost, and multi-thread safety.
- The current benchmark surface already highlights the need for a custom synchronized cache under concurrency.

Risk / complexity:
- **Low to medium**.
- Lower semantic risk than optimizer/evaluator changes.

Measurement strategy:
- `sjsonnet.bench.MultiThreadedBenchmark.main`
- repeated-run import-heavy workload (should be added; currently called out as a gap in `bench/BENCHMARK_LADDER.md`)
- heap profile around parse-cache key retention

### F. Replace selected stdlib collection combinators with manual loops on hot paths

What to change:
- in `ArrayModule` and selected `ObjectModule` helpers, replace `map`/`flatMap`/`slice ++ slice`/`indexWhere`/`.map(...).sum` with `while` loops over `asLazyArray`
- prioritize builtins that appear in real regressions or manifest/string workloads

Rationale:
- These are classic JVM low-risk allocation trims.
- The surrounding codebase already favors manual loops in many hot paths.

Risk / complexity:
- **Low**.
- Likely modest wins, but easy to benchmark and revert if noisy.

Measurement strategy:
- add focused JMHs per builtin family or run specific regression files using those builtins heavily
- re-check `MainBenchmark.main` and full regressions before keeping anything broad

## Risk / complexity notes

- **Do not prioritize evaluator dispatch/tag reorder work**. The repo has already measured and reverted that class of change.
- **Do not assume `NewEvaluator` should become default** without new branch-vs-base proof. Current repo evidence says that conclusion is not stable.
- **Comprehension and object-runtime work are the highest upside, but also the highest semantic risk**.
- **Parser, cache-key, and stdlib loop cleanup are the best low-blast-radius entry points**.

## Suggested measurement strategy by area

- **Parser**: `ParserBenchmark.main`, plus new parser-only workloads for large strings / objects / long suffix chains.
- **Optimizer**: `OptimizerBenchmark.main`, then `MainBenchmark.main`, then full regressions.
- **Evaluator/comprehensions**: `comparison2.jsonnet`, a new comprehension JMH, `MainBenchmark.main`, full regressions.
- **Object/materialize**: `MaterializerBenchmark.*`, manifest regressions, and a new deep-inheritance object benchmark.
- **Cache/import**: `MultiThreadedBenchmark.main` plus a missing import-heavy repeated-eval benchmark.
- **All risky work**: follow `bench/BENCHMARK_LADDER.md` and record branch-vs-base evidence in `bench/OPTIMIZATION_LOG.md`.

## Highest-value next steps

1. Prototype a **comprehension fast path** that removes `ValScope` copy churn.
2. Land **parser allocation trims** with `ParserBenchmark` proof.
3. Investigate **object field/super-chain caching/indexing** for manifest-heavy workloads.
4. Expand benchmarks with **import-heavy, object-heavy, and string-heavy** dedicated cases before broader structural changes.
