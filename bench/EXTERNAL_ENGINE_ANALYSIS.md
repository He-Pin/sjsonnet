# External Engine Analysis: jrsonnet, rustanka, and rustanka PR #60

_Context: branch `jit`, commit `52ee6da9` in sjsonnet. This report is intended to guide future JVM-side optimization work, not to justify a direct port of Rust code._

## Source summary

### Local sjsonnet sources consulted

- `CLAUDE.md`
- `readme.md`
- `bench/OPTIMIZATION_LOG.md`

### External sources consulted

Required `web_fetch` sources:

- <https://github.com/CertainLach/jrsonnet/blob/master/docs/benchmarks.md>
- <https://github.com/grafana/rustanka>
- <https://github.com/grafana/rustanka/pull/60>

Additional GitHub source inspection used to make the report concrete:

- `CertainLach/jrsonnet` `README.adoc`, `Cargo.toml`, `crates/jrsonnet-evaluator/Cargo.toml`
- `grafana/rustanka` `README.md`, `CLAUDE.md`, `Cargo.toml`, `rtk-benchmarks/run-benchmark.py`
- `grafana/rustanka` PR #60 metadata, changed files, and the new JIT-related files at head commit `2a73430f29aa3cf69108848a68aff99ec0accb31`

### High-level takeaways from the sources

1. **rustanka is not a clean-room engine**. It is effectively a Tanka-focused fork layered on top of jrsonnet internals: the workspace still contains `jrsonnet-*` crates and even keeps `CertainLach/jrsonnet` as repository metadata in `Cargo.toml`.
2. **jrsonnet's current speed story is mostly: efficient Rust implementation + aggressive build/profile choices + host-language stdlib + careful runtime data structures**, not a magical JIT.
3. **rustanka's extra speed story is mostly workflow specialization**: it is explicitly "hyper-optimized" for Grafana deployment orchestration pipelines, and its benchmark suite is built around those workflows.
4. **PR #60 is mostly a JIT architecture spike, not a completed optimization win**. It adds parser/compiler/runtime scaffolding for a Cranelift-based JIT, but the actual `JitEvaluator` entry points are still `todo!()`, some coordinator/codegen pieces are still incomplete, and the posted benchmark summary is **equal to base** across the measured suite.
5. **For sjsonnet on the JVM, the transferable lessons are mostly about measurement, lowering, caching, specialization, and workload-focused fast paths**. A second JIT inside the JVM is the least compelling part to copy.

## Optimization techniques by project

## 1. jrsonnet performance / optimization techniques

jrsonnet's benchmark document shows a very large performance lead over sjsonnet on many workloads, especially string-heavy and Kubernetes-style configuration cases. Examples from `docs/benchmarks.md`:

- **GraalVM CI**: Rust `93.6 ms` vs Scala `720.0 ms` (~`7.70x` faster)
- **kube-prometheus manifests**: Rust `129.4 ms` vs Scala `947.9 ms` (~`7.33x` faster)
- **large string join**: Rust `5.6 ms` vs Scala `331.1 ms` (~`58.79x` faster)
- **large string template**: Rust `6.7 ms` vs Scala `392.7 ms` (~`58.29x` faster)
- **realistic 1**: Rust `12.6 ms` vs Scala `382.3 ms` (~`30.46x` faster)

Important nuance: the jrsonnet benchmark doc compares **CLI processes**, while sjsonnet's own `readme.md` highlights that its best numbers come from a **warm long-lived daemon**. That means the published gap is directionally real, but not automatically apples-to-apples for all deployment modes.

### Techniques that appear to drive jrsonnet's performance

#### A. Host-language stdlib and runtime intrinsics

Like sjsonnet, jrsonnet implements the standard library in the host language rather than in Jsonnet itself. That avoids interpreting stdlib source, reduces dispatch overhead, and makes it easier to specialize builtin-heavy hot paths.

**Transfer signal for sjsonnet:** already adopted. The lesson is not "switch to Rust" but "continue moving hot semantics into directly optimized host intrinsics when justified by measurement."

#### B. Parser split: fast execution parser vs richer tooling parser

The workspace comments in `Cargo.toml` explicitly say jrsonnet has **two parsers**:

- a **fast parser** for execution, based on `peg`
- a **rowan-based parser** for better diagnostics / lint / LSP use cases

That is a classic performance trade-off: keep the hot execution path optimized for throughput, and isolate tooling ergonomics elsewhere.

**Transfer signal for sjsonnet:** partial. A second full parser would be expensive to maintain in Scala, but the underlying idea — keeping the execution representation minimal and not over-burdening the hot path with tooling concerns — is sound.

#### C. Aggressive release engineering

jrsonnet's `Cargo.toml` uses a notably performance-oriented release profile:

- `opt-level = 3`
- `lto = "fat"`
- `codegen-units = 1`
- `panic = "abort"`
- `strip = true`

That combination is not subtle: it is explicitly choosing compile time and binary flexibility in exchange for maximum optimized runtime code.

**Transfer signal for sjsonnet:** low direct transfer. These are Rust-native knobs; the JVM equivalent is not "copy flags" but "be explicit about warmup mode, daemon usage, and JIT/compiler configuration when benchmarking."

#### D. Custom allocator / memory behavior attention

The workspace depends on `mimallocator`, and rustanka's main binary can install it as the global allocator under the right feature flags. Even when this is only a small part of the total win, it shows a willingness to optimize the memory subsystem rather than just AST logic.

**Transfer signal for sjsonnet:** low direct transfer. The JVM owns allocation/GC policy. The useful analogue is reducing allocation rate and object graph churn rather than trying to outsmart the allocator.

#### E. Specialized data structures and crate factoring

The workspace uses specialized crates and data structures, including:

- `jrsonnet-interner`
- `hashbrown`
- `rustc-hash`
- `indexmap`
- a custom `jrsonnet-gcmodule`

This suggests a deliberate focus on symbol representation, object/key handling, and avoiding generic standard-library overhead in hot structures.

**Transfer signal for sjsonnet:** medium. The exact crates do not transfer, but the idea does: measure object-field lookup, symbol storage, and map allocation behavior, then use more specialized JVM data structures only where the profiler proves it matters.

#### F. Hot-path-oriented architecture rather than broad generality

jrsonnet's crate layout isolates evaluator, parser, interner, stdlib, types, and tooling. That makes it easier to optimize the evaluator without dragging tooling concerns into the same layer.

**Transfer signal for sjsonnet:** medium. sjsonnet already has a reasonably clean pipeline, but this reinforces that any future lowering / specialization work should be isolated from error-reporting and tooling concerns.

### What jrsonnet seems especially good at

From the benchmark results, the most striking strengths are:

- **string-heavy operations**
- **templating / manifest generation**
- **large real-world Kubernetes-style object construction**

Those are exactly the areas where sjsonnet should assume there is still room to improve.

## 2. rustanka performance / optimization techniques

rustanka inherits most of jrsonnet's engine-level advantages, but adds a second layer of optimization: it is designed around **Grafana's Tanka workflows**, not around generic Jsonnet evaluation alone.

The repo README is unusually explicit about this:

> Rustanka is still in its infancy... at the minute, it's hyper-optimized for the workflows that we use at Grafana Labs for our deployment orchestration pipelines.

The rustanka `CLAUDE.md` is similarly explicit that the goal is **drop-in Tanka compatibility**, with golden tests from real `tk` output as the source of truth.

### rustanka-specific techniques

#### A. Workflow specialization instead of general benchmark chasing

rustanka's benchmark suite (`rtk-benchmarks/run-benchmark.py`) is built around concrete Tanka actions:

- `diff`
- `env list`
- `eval`
- `export`
- importer counting / inspection
- import scanning

The benchmark runner supports both generated fixtures and fixture directories, builds the binary in release mode, uses `hyperfine`, and can compare:

- `tk`
- current `rtk`
- `rtk-base` (via `BENCHMARK_BASE_REF` or a provided binary)

That is important: the project is optimizing against **real commands people run**, not just microbenchmarks.

**Transfer signal for sjsonnet:** high. This is one of the best ideas in the whole external survey.

#### B. Elimination of process boundaries

rustanka's local `CLAUDE.md` says `exportJsonnetImplementation` is a no-op in rustanka and that rustanka always uses its built-in jrsonnet evaluator. That removes the cost and complexity of delegating Jsonnet evaluation to an external binary.

**Transfer signal for sjsonnet:** already mostly realized in sjsonnet when used directly. The lesson is to keep hot workflows in-process and avoid unnecessary orchestration layers around the engine.

#### C. Exact-compatibility optimization

A lot of rustanka work is aimed at byte-for-byte compatibility with `tk`, especially around YAML emission and export formatting. That is not a raw-throughput optimization, but it has an indirect performance effect: it constrains the solution space and prevents slow compatibility workarounds from leaking all over the stack.

**Transfer signal for sjsonnet:** partial. sjsonnet is a more general Jsonnet engine, but the discipline of having golden outputs tied to real workloads is worth borrowing.

#### D. Inherited jrsonnet baseline plus Tanka-specific surfaces

The repo still uses the jrsonnet crate family (`jrsonnet-evaluator`, `jrsonnet-parser`, `jrsonnet-stdlib`, etc.), the same release profile style, and the same overall evaluator lineage. rustanka's extra performance is therefore best understood as:

**jrsonnet engine speed** + **workflow-specific command design** + **compatibility-focused integration work**.

#### E. Benchmark-driven engineering with regression comparison to base

The benchmark runner's ability to compare against both `tk` and `rtk-base` is especially useful. It prevents a misleading narrative where a feature branch looks faster than Tanka but is actually slower than the previous rustanka baseline.

That matters a lot for interpreting PR #60.

## 3. rustanka PR #60 (`hackathon: JITanka`) in detail

### Executive summary

PR #60 is a large draft PR that introduces the **architecture for a future Cranelift-based JIT**, but it does **not** look like a finished, end-to-end performance feature yet.

Structured PR metadata:

- PR: `grafana/rustanka#60`
- Title: `hackathon: JITanka`
- State: `open`, `draft`
- Files changed: `58`
- Additions / deletions: `+7583 / -2443`
- Head commit used in benchmark comment: `2a73430f29aa3cf69108848a68aff99ec0accb31`

### What the PR adds

#### A. A new JIT-specific parser crate

New crate:

- `crates/rtk-jit-parser`

This crate defines its own parser / AST layer (`expr.rs`, `source.rs`, `location.rs`, etc.). That strongly suggests the author is avoiding direct JIT compilation from the existing general-purpose execution AST.

**Interpretation:** the JIT effort wants a representation tailored to compilation, source tracking, and lowering, not just tree-walking interpretation.

#### B. A new JIT compiler crate built on Cranelift

New crate:

- `crates/rtk-jit-compiler`

Important signals from the sources:

- depends on **`cranelift`** with `jit`, `module`, `object`, and `native` features
- has `analysis`, `codegen`, `runtime`, `shared`, and `unshared` modules
- exports `OptLevel`
- defines a compiler API with `compile(source, import_resolver)`
- includes `Options` for:
  - `cache_dir`
  - `cache_wait_to_populate`
  - `cache_step_two_opt_level`
  - `opt_level`

**Interpretation:** the intended design is not merely "compile once in-memory". It is aiming for:

- configurable optimization levels
- at least some concept of caching / artifact reuse
- support for dependency-aware compilation across imports

#### C. A typed analysis pass before codegen

`crates/rtk-jit-compiler/src/analysis/mod.rs` is not a trivial syntax walk. The extracted content shows it does all of the following:

- tracks **imports** via a channel
- models and reuses **local variable maps**
- keeps an `average_local_count` heuristic to size maps
- promotes expressions into **thunks**
- assigns coarse types such as:
  - `ConstantInteger`
  - `InferredInteger`
  - `ConstantFloat`
  - `InferredFloat`
  - `ConstantBoolean`
  - `InferredBoolean`
  - `ConstantString`
  - `InferredString`
  - plus `Any`, `Import`, etc.
- infers result categories for arithmetic, logical, comparison, bitwise, and string operations

This is one of the most interesting parts of the PR. It is not just introducing a JIT backend; it is introducing a **lowering and type-inference layer** that can make codegen simpler and faster.

#### D. Reusable scratch structures for local analysis

The same analysis code explicitly reuses internal hash maps / layered maps and sizes them based on observed average local counts.

That is a very practical optimization technique: not grand theory, just reducing repeated small allocations in a compiler pass that will run over many expressions.

#### E. Cranelift code generation per thunk

`crates/rtk-jit-compiler/src/codegen/mod.rs` shows a codegen layer that:

- builds **Cranelift functions** per thunk
- declares functions up front, then defines them
- uses `JITModule` for executable code and `ObjectModule` for object output
- finalizes definitions and retrieves a root thunk as a native function pointer
- passes a `StructReturn` result slot and `VMContext` into generated functions

This is a fairly serious compiler structure, not a toy macro.

#### F. A native runtime ABI for JIT/interpreter interop

`crates/rtk-jit-compiler/src/runtime/val.rs` defines a `#[repr(C)]` value with:

- an explicit discriminant
- a `union` payload for null/bool/num/string/array/object/function
- manual `Clone`, `Drop`, and conversion to `jrsonnet_evaluator::Val`
- an `extern "C"` function-pointer-based vtable shape

This is a classic systems-language move: build a stable ABI so compiled code can interoperate with the existing runtime.

#### G. Evaluator abstraction in the CLI

PR #60 refactors `rtk` command evaluation to go through an evaluator abstraction:

- `EvaluatorKind::{Interpreted, Jit}`
- `ConfigurableEvaluator`
- `InterpreterEvaluator`
- `JitEvaluator`

The CLI `eval` command grows an `--evaluator` option, and command code starts routing through the evaluator interface rather than a single monolithic interpreter path.

This refactor is valuable even before the JIT works, because it isolates the engine choice behind a stable command interface.

### What is incomplete / not yet delivering speedups

This is the critical part.

The PR looks architecturally ambitious, but the inspected sources show several obvious incompletions:

1. **`JitEvaluator` is still `todo!()`** for both file and snippet evaluation.
2. **The shared compiler coordinator loop is empty** (`loop {}` in `shared.rs`).
3. **The layered/object-code path still has a `todo!()` root thunk** in codegen.
4. The PR is still a **draft hackathon branch**, which matches the incomplete implementation.

That means PR #60 is best understood as a **compiler/JIT prototype scaffold**, not as a merged optimization technique already proven in production.

### How to interpret the benchmark comment on the PR

The PR comment shows impressive numbers such as:

- Diff `cluster_scoped`: `91.92x faster` vs `tk`, `equal` vs base
- Env List All Environments: `96.27x faster` vs `tk`, `equal` vs base
- Eval Single Static Environment: `16.76x faster` vs `tk`, `equal` vs base
- Export (Replace) All Environments: `17.66x faster` vs `tk`, `equal` vs base

The key phrase is **`equal` vs base**.

That means the measured branch is not yet outperforming the already-fast rustanka baseline in those benchmarked commands. So the benchmark comment should **not** be read as evidence that the new JIT architecture has already produced a win. It is better read as:

- rustanka base is already much faster than Tanka
- PR #60 has not yet made that baseline meaningfully faster on the measured suite

### Bottom line on PR #60

PR #60 contains **valuable ideas**, especially around lowering, typed analysis, reusable compiler scratch state, and evaluator pluggability.

But it does **not** currently justify the headline idea "JIT made rustanka much faster." The most important deliverables today are architectural, not empirical.

## JVM transferability assessment

## What transfers well to sjsonnet on the JVM

### 1. Workload-specific benchmark design

**Transferability: very high**

Rustanka's benchmark setup is probably the single best idea to borrow. It measures real commands, compares against baseline, and distinguishes wins over the incumbent tool from wins over the previous fast implementation.

For sjsonnet, this maps well to:

- benchmark suites for large real-world config repos
- separate categories for evaluation, imports, rendering/materialization, and string-heavy workloads
- explicit comparison against previous sjsonnet baselines, not just against go-jsonnet/jsonnet

This is especially important because `bench/OPTIMIZATION_LOG.md` already shows that plausible low-level changes can regress on the JVM.

### 2. Lowering / typed-analysis before the hot evaluator path

**Transferability: high**

sjsonnet already has `StaticOptimizer`, which means the project already believes in pre-processing the AST before evaluation. PR #60's most transferable insight is to push that idea further:

- infer coarse hot-path types where safe
- pre-classify arithmetic/string/logical operations
- lower selected patterns into simpler evaluator forms
- make import and thunk boundaries more explicit when it helps caching or specialization

This fits the JVM far better than a second JIT does.

### 3. Reuse of scratch structures / object-pool style thinking

**Transferability: medium-high**

The JIT analysis code's reuse of layered hash maps and its `average_local_count` heuristic are good reminders that compiler / optimizer passes can waste a surprising amount of time on small allocations.

For sjsonnet, possible analogues are:

- reusable scratch arrays for optimizer passes
- pooled builder objects in materialization or parsing hot spots
- reusing temporary collections in import resolution / object-field assembly

This should be done carefully and only with profiling, but it is a very plausible JVM win.

### 4. Stronger cache layering

**Transferability: medium-high**

sjsonnet already has parse caching. PR #60's options and compilation model suggest a broader cache stack:

- source -> parsed AST
- AST -> optimized / lowered representation
- import graph -> dependency-aware reuse

That is more transferable than the native-code stage itself. A JVM implementation could cache optimized/lowered forms keyed by source + import path + flags, without ever generating machine code.

### 5. Clear evaluator abstraction boundaries

**Transferability: medium**

Even if sjsonnet never adds a second evaluator, having a clean abstraction boundary is useful. It enables:

- experiments with alternate optimized evaluators
- scoped fast paths for special workloads
- easier A/B benchmarking in-process

This can be useful if future work wants to compare the current evaluator against a lowered evaluator or a specialized string/rendering engine.

## What transfers only partially

### 6. Separate execution parser vs tooling parser

**Transferability: partial**

The principle transfers; the exact implementation probably does not. Maintaining two parsers in Scala is a high ongoing cost. But the general lesson still applies: do not burden the hot execution path with extra structure only needed for tooling.

The more realistic sjsonnet version is probably:

- keep Fastparse for source parsing
- introduce a smaller lowered IR after parsing/optimization, rather than a second front-end parser

### 7. Specialized map / symbol / interning strategies

**Transferability: partial**

Rust's `FxHashMap`, custom interner crates, and low-level value layouts do not map directly to the JVM. But sjsonnet can still ask equivalent questions:

- are object field names paying too much hashing cost?
- is scope lookup spending too much time boxing / allocating?
- would specialized arrays, switch tables, or pre-indexed symbols help some hotspots?

This is an area for targeted measurement, not blanket rewriting.

### 8. Async/shared compilation pipelines

**Transferability: partial at best**

PR #60's shared compiler model includes global and thread-local compiler state plus async worker/coordinator infrastructure. That makes sense in Rust when compiling/importing can be orchestrated explicitly.

For sjsonnet, the complexity budget is different. Laziness, exception semantics, and JVM scheduling overhead mean this kind of machinery should only be adopted if there is a very clear daemon/server-side use case.

## What does not transfer well

### 9. A second JIT compiler inside the JVM process

**Transferability: low**

This is the headline non-transferable idea.

Why:

- sjsonnet already runs on a runtime with a sophisticated JIT (HotSpot / Graal)
- adding another JIT layer in Scala/Java risks duplicating work the JVM is already good at
- the engineering cost is huge: new IR, new runtime ABI, new deoptimization/debugging story, new correctness surface
- the likely first-order wins for sjsonnet are still available at the IR / specialization / caching level

If sjsonnet ever explores code generation, the JVM-native version would be **bytecode generation or Graal-friendly lowering**, not a Rust-style Cranelift subsystem.

### 10. Rust-native ABI/value-layout tricks

**Transferability: very low**

The `repr(C)` discriminated union approach in PR #60 is a rational Rust systems technique. It is not a natural JVM technique.

sjsonnet already has its own JVM-aware value-shape optimizations, such as:

- a flat `Eval` hierarchy specifically chosen for bimorphic inlining
- literal nodes that are both `Expr` and `Val`
- array-backed `ValScope`

Those are the right kind of representation tricks for the platform.

### 11. Allocator and release-profile tuning from Rust

**Transferability: very low**

`mimalloc`, fat LTO, `panic = abort`, and codegen-unit tuning are not meaningful templates for JVM engine design.

### 12. Tanka-specific compatibility work

**Transferability: low**

A large chunk of rustanka exists to match Tanka's YAML/export/import behavior. That matters for rustanka, but it is not a generic sjsonnet optimization opportunity.

## Specific recommendations for sjsonnet

Below is the concrete prioritized list I would use for future work.

### Priority 1: build a better benchmark ladder before deeper engine surgery

**Recommendation:** expand `bench/` so it separates at least these categories:

1. real-world full-evaluation workloads
2. import-heavy workloads
3. string-heavy / template-heavy workloads
4. render/materialization-heavy workloads
5. hot microbenchmarks for already-known hotspots

**Why first:** rustanka's biggest meta-lesson is not a code trick; it is a measurement discipline. sjsonnet already has proof that intuitive low-level changes can regress on the JVM.

### Priority 2: extend `StaticOptimizer` into a more explicit lowered IR for hot operations

**Recommendation:** prototype a post-parse, pre-eval lowering stage that carries coarse type / shape information for hot cases:

- numeric binary ops
- string concatenation / formatting paths
- selected field accesses / selects
- common builtin invocation shapes

**Why:** this borrows the best part of PR #60 (typed lowering) without importing the least transferable part (native codegen).

### Priority 3: attack string-heavy paths explicitly

**Recommendation:** use JMH + `--debug-stats` + targeted profiling on:

- `std.join`
- string templates / formatting
- renderer / manifest code paths
- large repeated concatenation / templating workloads

**Why:** jrsonnet's published lead over sjsonnet is most dramatic on string-heavy cases. That is too large a gap to ignore.

### Priority 4: add deeper cache layers above parse caching

**Recommendation:** investigate caching of optimized/lowered representations keyed by:

- canonical source path
- source contents / version
- import graph identity
- strictness / evaluator flags that affect semantics

**Why:** sjsonnet already wins from parse caching; the next logical step is caching more expensive derived forms.

### Priority 5: reduce allocation churn in optimizer/evaluator/materializer internals

**Recommendation:** use profiler-guided experiments to reuse scratch arrays / buffers / temporary collections in:

- `StaticOptimizer`
- import resolution
- object merge / field collection
- materialization

**Why:** this is the JVM analogue of rustanka PR #60's layered-map reuse idea and fits the platform much better than native-value-layout tricks.

### Priority 6: keep representation work JVM-native, not Rust-inspired

**Recommendation:** prefer improvements that strengthen existing JVM-friendly design choices:

- sealed/final class hierarchies for inlining
- array-backed scopes and indexed locals
- explicit specialization of hot apply/select cases
- fewer megamorphic call sites

**Why:** sjsonnet's own README already documents that these choices matter to the JVM JIT.

### Priority 7: do not start with a second parser or a second JIT

**Recommendation:** treat these as late-stage options only if the simpler path stalls.

**Why:** they add massive maintenance burden and correctness risk, while the current evidence suggests lower-hanging fruit remains.

## Borrowable ideas / experiments ranked

1. **Adopt rustanka-style benchmark discipline** for real workloads and branch-vs-base comparisons.
2. **Prototype a typed/lowered hot-path IR** as an extension of `StaticOptimizer`.
3. **Profile and optimize string-heavy workloads** where jrsonnet's lead over sjsonnet is largest.
4. **Add cache layers for optimized/lowered forms**, not just parsed forms.
5. **Reduce temporary allocation churn** in optimizer/evaluator/materializer passes.
6. **Introduce more explicit A/B evaluator hooks** only if needed to compare alternate lowered execution paths.
7. **Investigate specialized symbol/object-field handling** if profiling shows hashing/lookup overhead.
8. **Avoid a Rust-style native JIT or ABI redesign** unless there is exceptionally strong evidence that JVM-native optimization avenues are exhausted.

## Final assessment

The most valuable lesson from jrsonnet and rustanka is **not** "Rust is faster" and it is **not** "build a JIT." The real lessons are:

- measure real workloads relentlessly
- preserve a hot-path-friendly internal representation
- lower and specialize before execution
- cache more than just parsing when reuse is common
- optimize for the host runtime you actually have

For sjsonnet on the JVM, PR #60 is useful mainly as a reminder that **typed lowering and clear engine boundaries are worth exploring**, while the Cranelift/JIT layer is mostly a non-transferable systems-language experiment.
