# External Engine Performance Research

This report summarizes optimization ideas and benchmarking lessons from three external references:

- `CertainLach/jrsonnet` benchmark documentation: `docs/benchmarks.md`
- `grafana/rustanka` repository sources, especially `README.md`, `CLAUDE.md`, `Cargo.toml`, and `rtk-benchmarks/run-benchmark.py`
- `grafana/rustanka#60` (`hackathon: JITanka`), including the PR metadata, changed files, and benchmark bot comment

The goal is not to justify porting Rust code into sjsonnet. The goal is to identify what seems to explain the external speedups, which ideas plausibly transfer to a JVM Jsonnet engine, and which ones should remain out of scope.

## Source summary by external reference

### 1. jrsonnet benchmark documentation

**Primary-source evidence used**

- `CertainLach/jrsonnet/docs/benchmarks.md`
- `CertainLach/jrsonnet/Cargo.toml`
- `CertainLach/jrsonnet/crates/jrsonnet-evaluator/Cargo.toml`

**What the source says**

- The benchmark doc publishes head-to-head numbers across Rust/jrsonnet, Go, Scala/sjsonnet, and sometimes C++.
- Published wins over Scala/sjsonnet are especially large on string-heavy and manifest-heavy cases:
  - **GraalVM CI**: Rust `93.6 ms` vs Scala `720.0 ms` (~`7.70x`)
  - **kube-prometheus manifests**: Rust `129.4 ms` vs Scala `947.9 ms` (~`7.33x`)
  - **large string join**: Rust `5.6 ms` vs Scala `331.1 ms` (~`58.79x`)
  - **large string template**: Rust `6.7 ms` vs Scala `392.7 ms` (~`58.29x`)
  - **realistic 1**: Rust `12.6 ms` vs Scala `382.3 ms` (~`30.46x`)
- The workspace `Cargo.toml` explicitly documents **two parsers**: a fast `peg` parser for execution and a `rowan` parser for diagnostics/lint/LSP.
- The same `Cargo.toml` also shows a very aggressive release profile: `opt-level = 3`, `lto = "fat"`, `codegen-units = 1`, `panic = "abort"`, `strip = true`.
- The jrsonnet workspace depends on crates that imply deliberate runtime/data-structure tuning: `jrsonnet-interner`, `jrsonnet-gcmodule`, `hashbrown`, `rustc-hash`, `indexmap`, and `mimallocator`.
- `crates/jrsonnet-evaluator/Cargo.toml` adds more signal around the hot evaluator path: `rustc-hash`, `jrsonnet-gcmodule`, and `stacker` are all in the evaluator crate.

**Bottom line**

jrsonnet's published speed story looks like a combination of efficient Rust execution, optimized build settings, host-language builtins, and specialized runtime/data-structure choices. The benchmark doc does **not** point to a single trick; it points to a stacked set of engineering choices.

### 2. rustanka repository

**Primary-source evidence used**

- `grafana/rustanka/README.md`
- `grafana/rustanka/CLAUDE.md`
- `grafana/rustanka/Cargo.toml`
- `grafana/rustanka/rtk-benchmarks/run-benchmark.py`

**What the source says**

- The README states that rustanka is currently **"hyper-optimized"** for Grafana Labs deployment-orchestration workflows, which is an explicit caveat that performance wins are workload-specific.
- `CLAUDE.md` states that the primary goal is **exact output compatibility with Tanka**, and that golden fixtures from real `tk` output are the source of truth.
- `CLAUDE.md` also states that `exportJsonnetImplementation` is a no-op in rustanka and that `rtk` always uses its built-in **jrsonnet evaluator**, eliminating an external engine process boundary.
- `Cargo.toml` still carries strong jrsonnet lineage: repository metadata points at `CertainLach/jrsonnet`, the workspace uses `jrsonnet-*` crates, and it keeps a similar performance-oriented release profile.
- `rtk-benchmarks/run-benchmark.py` is highly relevant because it shows **how** rustanka measures speed:
  - builds release binaries
  - uses `hyperfine`
  - validates that `tk` and `rtk` outputs match before benchmarking generated-fixture tests
  - supports **baseline comparison** via `BENCHMARK_BASE_REF` or `--rtk-base-binary-path`
  - uses a separate worktree plus `cargo build --release -p rtk` to build the base revision
  - classifies workload surfaces around real commands such as `diff`, `env list`, `eval`, `export`, importer counting, and import scanning
  - uses `--warmup 1`
  - reports `vs_tk` and `vs_base`, marking branch results as `equal` when the delta is within combined standard deviation

**Bottom line**

rustanka's extra performance story is not just "jrsonnet but faster." It is jrsonnet's engine baseline plus workflow specialization, in-process integration, and a benchmark harness built around real Tanka commands with correctness checks and base comparisons.

### 3. rustanka PR #60 (`hackathon: JITanka`)

**Primary-source evidence used**

- `grafana/rustanka#60` PR metadata
- PR changed files at head commit `2a73430f29aa3cf69108848a68aff99ec0accb31`
- `cmds/rtk/src/evaluator/configurable.rs`
- `cmds/rtk/src/evaluator/jit.rs`
- `crates/rtk-jit-compiler/Cargo.toml`
- `crates/rtk-jit-compiler/src/lib.rs`
- `crates/rtk-jit-compiler/src/analysis/mod.rs`
- `crates/rtk-jit-compiler/src/codegen/mod.rs`
- PR benchmark bot comment

**What the source says**

- PR #60 is a **draft** PR titled `hackathon: JITanka` with `58` changed files and a large diff (`+7583 / -2443`).
- The PR adds new crates for a JIT-oriented pipeline:
  - `crates/rtk-jit-parser`
  - `crates/rtk-jit-compiler`
  - `crates/rtk-jit-evaluator`
- `cmds/rtk/src/evaluator/configurable.rs` shows that rustanka is wiring in an evaluator kind switch between `Interpreted` and `Jit`.
- `cmds/rtk/src/evaluator/jit.rs` shows the public `JitEvaluator` entry points still call `todo!("JIT file evaluation")` and `todo!("JIT snippet evaluation")`.
- `crates/rtk-jit-compiler/src/lib.rs` shows a real compiler-facing surface, including:
  - `compile(source, import_resolver)`
  - an `Options` type with `cache_dir`, `cache_wait_to_populate`, `cache_step_two_opt_level`, and `opt_level`
  - an async-aware import resolver abstraction
- `crates/rtk-jit-compiler/src/analysis/mod.rs` shows a non-trivial lowering/analysis phase that:
  - tracks imports through a channel
  - stores layered local-variable maps
  - keeps an `average_local_count` heuristic
  - promotes work into **thunks**
  - infers coarse result categories such as `ConstantInteger`, `InferredInteger`, `ConstantFloat`, `InferredBoolean`, `ConstantString`, and `InferredString`
- `crates/rtk-jit-compiler/src/codegen/mod.rs` shows Cranelift-backed code generation that:
  - uses `JITModule` and `ObjectModule`
  - declares functions for analyzed thunks, then defines them
  - finalizes JIT definitions and exposes a root thunk entry point
  - still contains at least one unresolved `todo!()` on the layered/object-output path
- The PR benchmark bot comment is the key outcome signal: the branch is much faster than `tk`, but **`equal` vs base** across the listed suite.

**Bottom line**

PR #60 is best read as a promising architecture spike, not a completed speed feature. The transferable part is the lowering/analysis/caching design. The non-transferable or low-priority part is the idea of embedding a second JIT inside an already-JITed JVM runtime.

## Techniques that may explain the external performance wins

| Source | Technique | Why it probably matters |
| --- | --- | --- |
| jrsonnet | Host-language implementation of Jsonnet runtime and stdlib | Avoids interpreting stdlib Jsonnet and enables builtin-specific fast paths. |
| jrsonnet | Separate fast execution parser from tooling parser | Keeps the hot execution path optimized instead of paying for tooling-friendly structures everywhere. |
| jrsonnet | Specialized crates/data structures (`jrsonnet-interner`, `hashbrown`, `rustc-hash`, `indexmap`) | Suggests attention to symbol handling, object-field lookup, map behavior, and allocation overhead. |
| jrsonnet | Performance-oriented release profile and allocator choices | Helps native code generation and memory behavior, even if the exact knobs are Rust-specific. |
| rustanka repo | In-process evaluator use, not an external Jsonnet engine | Removes process boundaries and orchestration overhead around evaluation. |
| rustanka repo | Benchmark harness built around real commands (`diff`, `env list`, `eval`, `export`) | Optimizes for workloads users actually run instead of only microbenchmarks. |
| rustanka repo | Output validation before timing | Prevents measuring an incorrect-but-fast implementation. |
| rustanka repo | Base-branch comparison with `rtk-base` | Prevents false wins where a branch beats a competitor but not its own previous baseline. |
| rustanka PR #60 | Typed lowering before execution/codegen | Reduces dynamic uncertainty and creates opportunities for specialization. |
| rustanka PR #60 | Thunk-oriented analysis and code generation | Gives the compiler smaller units to analyze, cache, declare, and compile. |
| rustanka PR #60 | Reuse heuristics for locals (`average_local_count`) and layered maps | Direct attack on repeated small allocation costs inside compiler analysis. |
| rustanka PR #60 | Cache-oriented compiler options | Implies a design that expects repeat runs and import reuse to matter. |

## Data-structure, runtime, and algorithm choices

### Observed choices and likely effect

| Choice | Evidence | Likely benefit |
| --- | --- | --- |
| Symbol interning | `jrsonnet-interner` in jrsonnet workspace | Cheaper identifier equality, less duplicate string churn. |
| Specialized hash/map implementations | `hashbrown`, `rustc-hash`, `indexmap`, `fxhash` | Lower overhead for hot maps and object-field tables. |
| Custom GC/allocation support | `jrsonnet-gcmodule`, `mimallocator` | Lower allocator/GC overhead on native workloads. |
| Split parser strategy | execution parser + rowan diagnostics parser | Lets the runtime parser optimize for throughput. |
| Builtin-heavy host runtime | jrsonnet/rustanka architecture and rustanka's built-in evaluator use | Less interpreted overhead on stdlib-heavy workloads. |
| Layered local-scope maps with reuse heuristics | PR #60 analysis pass | Reduces repeated allocation and re-sizing costs. |
| Thunk-based lowering | PR #60 analysis/codegen | Provides stable compilation units and cleaner dependency handling. |
| Coarse static type inference | PR #60 analysis categories (`ConstantInteger`, `InferredString`, etc.) | Opens the door to specialized fast paths before execution. |
| Per-thunk code generation | PR #60 codegen declaring/defining functions for thunks | Supports modular compilation and reuse. |
| Cached compilation options | PR #60 compiler `Options` | Acknowledges that imports and repeated runs are important workloads. |
| Real-command benchmark harness | rustanka benchmark runner | Keeps optimization work aligned with actual user-visible speed. |

### JVM relevance notes

| Choice | JVM relevance |
| --- | --- |
| Symbol interning | **High relevance.** sjsonnet already uses indexed scopes in places; deeper identifier interning or symbol-table reuse could still help if profiles show string/key churn. |
| Specialized hash/map implementations | **Medium-high relevance.** The JVM cannot reuse Rust crates, but it can adopt targeted specialized maps or more compact field-key representations where hot. |
| Custom allocator / native GC modules | **Low direct relevance.** The JVM owns allocation and GC policy. The actionable analogue is reducing allocation rate and temporary object churn. |
| Split parser strategy | **Medium relevance.** Worth considering only if parser profiling shows real execution-path cost. A second parser is expensive to maintain. |
| Host-language builtin runtime | **Already relevant.** sjsonnet already benefits from this pattern; the lesson is to keep moving hot semantics into optimized host intrinsics when justified. |
| Layered locals reuse and allocation heuristics | **High relevance.** JVM-side lowering/optimizer passes can benefit from reusable buffers/maps just as much as Rust compiler passes do. |
| Typed lowering before execution | **High relevance.** A JVM interpreter can still use a cheaper lowered IR or specialized expression forms without adding a separate JIT. |
| Per-thunk compilation/codegen | **Low-medium direct relevance.** The decomposition idea is useful; full codegen is less compelling inside HotSpot/Graal. |
| Compiler cache options | **Medium relevance.** Parse/lowered-form caching and import reuse are very relevant for repeated config evaluation. |
| Real-command benchmarks | **High relevance.** This transfers directly to sjsonnet's perf process. |

## Transferability analysis for sjsonnet

### Directly applicable

1. **Adopt rustanka-style benchmark discipline for performance work.**
   - Validate correctness before timing when benchmarking alternative execution paths.
   - Compare candidate vs baseline revision, not just candidate vs external engines.
   - Keep workload suites tied to real user tasks, not only synthetic microbenchmarks.

2. **Expand workload coverage around the places where jrsonnet most outperforms sjsonnet.**
   - The biggest published gaps are string-heavy templating/join workloads and large manifest/object-generation cases.
   - Those should be first-class benchmark classes in sjsonnet's bench suite and roadmap.

3. **Keep pushing hot semantics into optimized host intrinsics.**
   - This is already part of sjsonnet's architecture, but the external evidence reinforces it as the right default.

4. **Treat repeated-run and import-reuse workloads as first-class.**
   - rustanka's compiler/cache design and workload harness both assume repeated execution matters.
   - That lines up with sjsonnet's existing emphasis on parse caching and daemon/warm-engine usage.

### Needs adaptation

1. **Specialized map/key/data-structure work.**
   - The Rust crates do not transfer directly.
   - The transferable idea is to measure object-field lookup, key storage, scope extension, and temporary map allocation, then replace only the hot structures with more JVM-friendly compact or specialized representations.

2. **A lowered IR with more type/specialization information.**
   - PR #60's strongest idea is probably not Cranelift itself; it is the explicit lowering step that infers coarse types and decomposes code into thunks.
   - For sjsonnet, the equivalent would likely live between `StaticOptimizer` and `Evaluator`, or as richer specialized expression/value forms, not as native codegen.

3. **Parser-path separation.**
   - jrsonnet's split parser strategy is sensible, but maintaining two Scala parsers would be costly.
   - It should only be considered if parser profiling shows execution parsing is a meaningful contributor after caching and warmup are controlled.

4. **More explicit cache layers.**
   - PR #60's compiler options suggest artifact reuse beyond simple parse caching.
   - For sjsonnet, a more realistic adaptation would be caching optimized/lowered forms, import dependency metadata, or reusable manifest/render structures.

### Not applicable or low priority

1. **Rust release-profile flags and allocator tricks.**
   - `lto = "fat"`, `panic = "abort"`, and `mimallocator` do not map cleanly onto JVM behavior.
   - The JVM analogue is careful warmup, allocation reduction, and stable benchmarking, not copying native build knobs.

2. **Embedding a second JIT as a near-term strategy.**
   - HotSpot/Graal are already JIT compilers.
   - Adding another JIT layer inside sjsonnet would be high complexity, high risk, and likely lower ROI than improving lowering, specialization, allocation behavior, and benchmark-driven tuning first.

3. **Tanka-specific command-path specialization as engine work.**
   - rustanka's `diff`/`env list`/`export` wins are meaningful for rustanka, but some of that speed is outside pure Jsonnet evaluation.
   - sjsonnet should borrow the methodology, not the Tanka-specific product surface.

## Benchmark methodology lessons and caveats

### Lessons worth copying

1. **Benchmark actual workflows, not only isolated engine kernels.**
   - rustanka benchmarks real commands, generated fixtures, and diff workflows.
   - This makes perf work harder to game and easier to connect to user impact.

2. **Validate outputs before timing.**
   - rustanka's benchmark runner explicitly checks that `tk` and `rtk` agree before running generated-fixture benchmarks.
   - That is a strong pattern for any future alternative evaluator or optimizer path in sjsonnet.

3. **Always compare against a base revision.**
   - rustanka's `rtk-base` mechanism is especially valuable.
   - PR #60 is the proof: despite a large architectural JIT diff, the published branch result is still `equal` vs base.

4. **Use explicit warmup and keep benchmark artifacts.**
   - rustanka's runner passes `--warmup 1`, exports markdown/json, and records versions.
   - That is reproducible and reviewable.

5. **Classify workloads.**
   - rustanka's runner naturally separates generated fixtures from diff-mode tests.
   - sjsonnet should continue building out workload classes such as parser-heavy, string-heavy, object-heavy, import-heavy/reuse-heavy, and render-heavy.

### Caveats when interpreting the external results

1. **jrsonnet's published tables are not automatically apples-to-apples with sjsonnet's best deployment mode.**
   - sjsonnet's own README notes that its best numbers come from a **warm long-lived daemon**.
   - jrsonnet's benchmark doc presents CLI-process results. The gap is directionally important, but exact ratios should not be over-interpreted without matching runtime mode.

2. **Some benchmark rows are missing due to feature/support differences.**
   - jrsonnet's benchmark doc includes missing Scala results on some workloads because sjsonnet lacks specific features or semantics there.
   - That makes the benchmark matrix informative but incomplete.

3. **rustanka is optimized for Grafana/Tanka workflows.**
   - Its README explicitly warns that current performance is tied to those workflows.
   - Some wins may come from product-surface choices rather than core Jsonnet engine improvements.

4. **PR #60 is not evidence that the JIT is already a win.**
   - The public evaluator entry points are still `todo!()`.
   - The benchmark bot comment says the branch is `equal` vs base.
   - The architectural ideas are more useful than the current outcome.

## Ranked takeaways for sjsonnet

1. **Upgrade performance methodology before chasing exotic implementation ideas.**
   - Base-vs-branch comparisons, correctness validation, workload classification, and saved benchmark artifacts are immediately actionable and low risk.

2. **Prioritize string-heavy and manifest/object-generation hotspots.**
   - jrsonnet's biggest published wins over sjsonnet are in string joins/templates and large real-world manifest generation.
   - Those areas are likely to produce the highest-value JVM improvements.

3. **Attack allocation and lookup overhead in hot runtime structures.**
   - The external Rust projects show consistent attention to interners, hash maps, layered locals, and reuse.
   - For sjsonnet, the likely analogue is tighter key/scope representations and less temporary allocation, not a wholesale rewrite.

4. **Investigate a richer lowering/specialization layer before considering JVM-side JIT experiments.**
   - PR #60's typed/thunk lowering is the most interesting transferable idea.
   - A better lowered form could help sjsonnet's interpreter without the complexity of emitting native code.

5. **Treat repeated-run/import-reuse scenarios as first-class performance targets.**
   - rustanka's cache-oriented design and sjsonnet's own daemon/cache story both point in the same direction.
   - Reuse-heavy workloads should have explicit benchmark coverage.

6. **Do not treat "build a JIT" as the current default recommendation.**
   - On the JVM, a second JIT is probably the wrong first move.
   - It should stay behind lower-cost improvements in specialization, caching, runtime data structures, and benchmark rigor.

## Suggested immediate follow-up for sjsonnet perf work

1. Add or promote benchmark cases for:
   - large string joins/templates
   - large object/manifest generation
   - repeated-import/reuse-heavy evaluation
   - render/materialize-heavy workloads
2. Ensure every kept perf change is judged against a base revision, not just against memory or an external engine.
3. Profile allocation-heavy paths in object field handling, string operations, and scope management.
4. Explore whether `StaticOptimizer` can produce a more specialized/lowered form for the evaluator without changing semantics.
