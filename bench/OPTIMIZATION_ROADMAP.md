# Optimization Roadmap

## Context / problem statement

Recent wins on branch `jit` came from **reducing allocation churn in `StaticOptimizer` scope building**, not from evaluator-dispatch surgery. The current evidence says:

- `bench/AST_VISIT_COUNTS.md` shows evaluator traffic is dominated by `ValidId` (~38%), `BinaryOp` (~30%), `Val.Literal` (~20%), and then `Select`.
- `bench/OPTIMIZATION_LOG.md` shows several intuitive evaluator micro-optimizations regressed real benchmarks and were reverted.
- `bench/EXTERNAL_ENGINE_ANALYSIS.md` points toward a JVM-native path: better benchmark discipline, deeper lowering, more caching, and explicit string/render/object work.

The roadmap below prioritizes **benchmark-backed JVM-friendly wins first**, then larger structural bets.

## Guardrails from already-attempted waves

Build on the kept wins from Wave 6 and Wave 8, but do **not** blindly retry these rejected directions:

1. **Wave 3: dispatch/tag reordering** — reverted after `MainBenchmark.main`, `OptimizerBenchmark.main`, and corpus timing regressed.
2. **Wave 4: make `NewEvaluator` the default** — reverted because `comparison2.jsonnet` regressed even though the corpus runner looked favorable.
3. **Wave 5: array comparison / concat micro-optimizations** — reverted; no reliable suite-level win.
4. **Wave 7 attempt 1: hybrid `NewEvaluator` hot-path fast path** — improved a targeted repro but still lost on broader gates.
5. **Wave 7 attempt 2: add `final` hints to `NewEvaluator`** — rejected immediately after regression.
6. **Wave 8 hypothesis 1: remove `ScopedVal.sc`** — rejected due to mixed signal.

Important lesson: **`AST_VISIT_COUNTS.md` is diagnostic, not benchmark authority**. It is useful for hotspot ranking, but not for deciding evaluator defaults by itself.

## Prioritized near-term wins

| Pri | Item | Target area | Why it matters | Expected impact | Risk | First experiment |
| ---: | --- | --- | --- | --- | --- | --- |
| 1 | Cache optimized/lowered ASTs above parse caching | `Interpreter` + `StaticOptimizer` pipeline | Parse caching exists, but repeated imports still pay optimization/lowering cost. | Medium | Medium | Add an opt-in cache keyed by canonical path + source hash + relevant settings; benchmark repeated/import-heavy workloads. |
| 2 | Add sorted visible-key caching for objects | `Val.Obj`, `Materializer`, render/equality paths | Object render/materialize work repeatedly walks and sorts visible keys. | Medium | Medium | Lazily cache sorted visible keys for stable objects; benchmark `manifestJson*`, YAML, and object-heavy workloads. |
| 3 | Push optimizer lowering further for hot cases | `StaticOptimizer` lowering of `BinaryOp`, `Select`, builtin/apply shapes | The hottest runtime ops are exactly the cases where optimizer-side specialization can pay broadly. | Medium | Medium | Prototype extra lowering for numeric binary chains, simple field selects, and common builtin/application shapes. |
| 4 | Cache dynamic `%` format parsing | `Format.scala`, `%` formatting path | Static literal formats already get help; dynamic format strings still reparse each call. | Medium targeted | Low-Medium | Add a small parsed-format cache keyed by format string; benchmark template-heavy cases. |
| 5 | Add ASCII fast paths for string operations | string lookup/slice/join/split paths | Many real workloads are ASCII-heavy but still pay code-point machinery. | Medium-High targeted | Low | Guarded ASCII fast paths for indexing, slicing, `std.join`, and split variants. |
| 6 | Reduce object-field resolution churn | `Val.Obj.value/valueRaw/getAllKeys/addSuper` | Real config workloads are object-heavy and may repeatedly walk super chains and rebuild key views. | Medium | Medium-High | Profile object-heavy manifests, then prototype one contained no-super/no-exclusion or merged-key-view fast path. |
| 7 | Special-case materialize/render hot paths | `Materializer`, `Renderer`, manifest builtins | Rendering is part of end-to-end time and repeatedly traverses arrays/objects. | Medium targeted | Medium | Add focused JMHs for materialize/render paths, then try stable-key reuse or a specialized minified render path. |
| 8 | Trim generic apply-path allocation | generic `visitApply` / builtin apply paths | Arity-specialized nodes exist, but unspecialized paths still allocate argument arrays and wrappers. | Small-Medium | Medium | Add a benchmark for named/default-heavy callsites; if hot, prototype more lowering or small-array reuse. |

## Prioritized structural bets

| Pri | Item | Target area | Why it matters | Expected impact | Risk | First experiment |
| ---: | --- | --- | --- | --- | --- | --- |
| 9 | Lowered hot-path IR after `StaticOptimizer` | post-parse / pre-eval representation | The best transferable idea from external engines is typed/lowered execution, not a second JIT. | High potential | High | Build an opt-in lowered form for `ValidId`, numeric `BinaryOp`, simple `Select`, and common builtin/application forms. |
| 10 | Specialized comprehension executor | array comprehension execution, `ValScope` churn | `comparison2.jsonnet` is dominated by comprehension-time `ValidId`/`BinaryOp` traffic, not array compare/concat work. | High targeted | High | Create a nested-`std.range` comprehension benchmark and prototype a mutable-frame executor for the common `for`/`for`/`if` path. |
| 11 | Revisit alternate evaluator shapes only behind strict A/B harness | `Evaluator` / `NewEvaluator` experimentation | Evaluator shape may still matter, but prior signals were distorted by measurement methodology. | Unknown-High | High | Build an A/B harness with warmup, alternating order, shared parse cache, and branch-vs-base reporting before any new evaluator experiments. |
| 12 | Specialize symbol/object-field handling if profiling justifies it | field-name storage and lookup representation | Hashing/lookup overhead may matter in large object-heavy configs, but only if data proves it. | Medium potential | Medium-High | Profile field lookup and visible-key handling on large manifests; if justified, prototype interned/static-key indexing for static objects only. |
| 13 | Expand the benchmark ladder by workload class | `bench/` design and regression gates | Several plausible wins already regressed real workloads; better measurement is an enabler for every structural bet. | Indirect but critical | Low | Split and grow benchmark coverage for import-heavy, string-heavy, render-heavy, object-heavy, comprehension-heavy, and optimizer-only cases. |

## Top 3 next executable waves

### Wave A: benchmark-and-profile hardening

**Goal:** make future optimizer/evaluator experiments trustworthy.

- Add workload buckets for import-heavy, string/template-heavy, render/materialize-heavy, object-heavy, and comprehension-heavy cases.
- Ensure perf comparisons are branch-vs-base, not just single-run local intuition.
- Separate cold-start, warm JVM, and daemon/repeated-eval measurements.

Why now: this is the clearest lesson from the rejected waves and from the external-engine review.

### Wave B: low-blast-radius experiments with direct payoff

**Goal:** land one or two measurable wins without reopening reverted evaluator work.

- optimized/lowered AST cache above parse cache
- sorted visible-key caching for object materialization
- dynamic `%` format cache
- ASCII string fast paths

Why next: these are localized, benchmarkable, and aligned with current evidence.

### Wave C: one structural prototype, not several

**Goal:** test the strongest long-term idea in a controlled way.

- prototype a narrow lowered IR for `ValidId`, numeric `BinaryOp`, simple `Select`, and common builtin/application forms
- keep it opt-in
- gate it with `comparison2`, string-heavy, render-heavy, and full regression coverage

Why third: a lowered IR has the highest upside, but it should be proven on a narrow slice before broader evaluator redesign.

## Success criteria / benchmark guardrails

An optimization should be considered successful only if it:

1. shows a **clear positive branch-vs-base result** on its target workload class,
2. does **not regress `bench.runRegressions`**,
3. preserves correctness on the relevant test suite,
4. comes with a plausible explanation for the win, such as lower allocation, less sorting, fewer scope rebuilds, or less repeated parsing,
5. avoids relying on corpus-runner elapsed time alone as proof of an evaluator-wide win.

Practical default gate for risky work:

- targeted JMH or focused regression for the intended hotspot,
- `MainBenchmark.main`,
- `bench.runRegressions` or the most relevant subset first, then the full suite before keeping the change.

## Summary

The best next path is:

- keep harvesting optimizer/allocation wins,
- attack string/render/object hotspots directly,
- add cache layers above parse caching,
- and only then spend major complexity on lowered execution forms.

The path to avoid is repeating already-reverted ideas without materially new evidence: dispatch/tag reorder, defaulting `NewEvaluator`, array compare/concat micro-tuning, or cosmetic devirtualization tweaks.
