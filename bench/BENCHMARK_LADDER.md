# Benchmark Ladder and Comparison Protocol

This document defines the default measurement ladder for the JVM performance campaign.
It exists to prevent two failure modes that already happened on `jit`:

- **diagnostic data being treated as benchmark authority**, especially the elapsed-time gap in `bench/AST_VISIT_COUNTS.md`
- **feature-branch optimism without a base comparison**, where a targeted run looks good locally but does not hold up against the prior baseline or the broader regression corpus

Use this ladder before keeping any optimizer, evaluator, materializer, or benchmark-harness change.

## Core rules

1. **Do not use corpus elapsed time as proof of a win.**
   `bench/AST_VISIT_COUNTS.md` is useful for hotspot ranking and evaluator-shape diagnostics, but its elapsed times are not a benchmark-grade A/B result.
2. **Always compare branch vs base for risky changes.**
   A branch is only faster if it beats the baseline revision with the same JDK, JVM args, workload, and run order discipline.
3. **Match the benchmark to the hypothesis.**
   Optimizer work should show up in optimizer-heavy or end-to-end workloads, render work in materialization/render workloads, and evaluator work in the relevant regression cases.
4. **Promote evidence up the ladder.**
   A change should graduate from a focused check to a broader gate; do not stop at the first positive number.
5. **Record enough context to reproduce the claim.**
   Every kept performance claim should retain the workload, exact command, commit IDs, and the result summary.

## The ladder

### Level 0: diagnostic-only signals

Use these to find hotspots or explain behavior, not to prove that a change is keepable.

| Tool / artifact | Use | What it can prove | What it cannot prove |
| --- | --- | --- | --- |
| `bench/AST_VISIT_COUNTS.md` | Rank evaluator traffic and compare shape/counter distributions | which tags/arms are hot, whether both evaluators see the same success corpus | that one evaluator is faster overall |
| `OptimizerBenchmark` counters | inspect AST shape before/after optimization | whether a rewrite changes the optimized tree mix | end-to-end performance wins |
| `--debug-stats` | inspect runtime counters and phase timing on a target file | whether a candidate workload really stresses imports, comprehensions, rendering, etc. | stable branch-vs-base deltas |

If a result lives only at Level 0, it is a hypothesis, not evidence.

### Level 1: focused hotspot benchmarks

Use JMH or one-file regressions that isolate the target subsystem.

| Workload class | Current entry point | Typical command |
| --- | --- | --- |
| parser-heavy | `sjsonnet.bench.ParserBenchmark.main` | `./mill bench.runJmh -i 5 -wi 5 -f 1 'sjsonnet.bench.ParserBenchmark.main'` |
| optimizer-heavy | `sjsonnet.bench.OptimizerBenchmark.main` | `./mill bench.runJmh -i 5 -wi 5 -f 1 'sjsonnet.bench.OptimizerBenchmark.main'` |
| end-to-end single-thread | `sjsonnet.bench.MainBenchmark.main` | `./mill bench.runJmh -i 5 -wi 5 -f 1 'sjsonnet.bench.MainBenchmark.main'` |
| render/materialize | `sjsonnet.bench.MaterializerBenchmark.*` | `./mill bench.runJmh -i 5 -wi 5 -f 1 'sjsonnet.bench.MaterializerBenchmark.*'` |
| multi-thread / shared parse cache stress | `sjsonnet.bench.MultiThreadedBenchmark.main` | `./mill bench.runJmh -i 5 -wi 5 -f 1 'sjsonnet.bench.MultiThreadedBenchmark.main'` |
| single regression file | `bench.runRegressions <file>` | `./mill bench.runRegressions bench/resources/go_suite/comparison2.jsonnet` |

Level 1 is where targeted experiments should start, but it is not the final gate for non-trivial changes.

### Level 2: workload-class regression checks

Run the most relevant regression files or suites for the hypothesis, not just the benchmark that already looks favorable.

Current workload anchors:

| Workload class | Current anchor(s) | Notes |
| --- | --- | --- |
| comprehension-heavy evaluator traffic | `bench/resources/go_suite/comparison2.jsonnet` | Already caught a misleading evaluator-default change. |
| general end-to-end config execution | `MainBenchmark.main`, `bench/resources/{cpp,go,sjsonnet}_suite` | Good default cross-check for broad changes. |
| materialize/render | `MaterializerBenchmark`, render-heavy regression files when touched | Use when changing manifest/render behavior. |
| optimizer-only | `OptimizerBenchmark.main` | Must be paired with end-to-end confirmation. |
| correctness-oriented bug repros | `bench/resources/bug_suite/*` | Useful when a fix or optimization targets a previous regression. |

Known coverage gaps worth filling in future waves:

- repeated-import / reuse-heavy workloads
- explicitly string/template-heavy workloads
- explicitly object-heavy workloads
- larger render-heavy manifests

Do not invent a broad performance story from a single file unless the hypothesis is intentionally single-file and narrow.

### Level 3: full regression gate

Before keeping a broad optimization, run:

```bash
./mill bench.runRegressions
```

This is the current suite-wide sanity gate that catches cases where a targeted win hides a broader regression.

### Level 4: branch-vs-base proof

Before claiming a wave is successful, compare the candidate revision against a baseline revision.

Recommended baseline choices:

- the merge base with the target integration branch
- the parent commit before the experiment wave
- the last known-good perf checkpoint called out in `bench/OPTIMIZATION_LOG.md`

Protocol:

1. **Use separate worktrees or clean checkouts.**
   Do not switch the current optimization branch back and forth if that risks interfering with concurrent work.
2. **Pin the environment.**
   Same machine, JDK, JVM args, stack/heap settings, and workload list.
3. **Warm both revisions.**
   Avoid “first run cold, second run warm” comparisons.
4. **Alternate order.**
   Prefer `base -> branch -> branch -> base` or similar over always running one side second.
5. **Keep the command identical except for the revision.**
6. **Record the exact revisions.**
   Include `git rev-parse HEAD` for both sides in the evidence trail.
7. **Promote only when the branch is positive on the target workload and non-negative on the broader gate.**

If a comparison only shows that the branch is “better than my memory” or “better than a competitor,” it is incomplete.

## Workload classes and how to use the current corpus

The repo already contains useful benchmark surfaces, but they should be interpreted by class:

| Class | Primary workloads | When to use them |
| --- | --- | --- |
| Parser | `ParserBenchmark` | parser changes, import parsing cost questions |
| Optimizer | `OptimizerBenchmark` | `StaticOptimizer` allocation/lowering changes |
| End-to-end CLI | `MainBenchmark` | broad user-visible speed claims |
| Materialize / manifest | `MaterializerBenchmark` | JSON/YAML/Python render changes |
| Regression corpus | `bench/resources/*_suite` via `bench.runRegressions` | targeted repros plus broad smoke gate |
| Concurrency | `MultiThreadedBenchmark` | shared-cache / multi-thread behavior |

When proposing a new workload, classify it first. A good new benchmark should make it obvious whether it is:

- parser-heavy
- optimizer-only
- comprehension-heavy
- object-heavy
- string/template-heavy
- import-heavy / reuse-heavy
- render/materialize-heavy

That classification should then drive which existing gates it must also pass.

## Evidence trail template

Capture the following in the PR description, commit notes, or `bench/OPTIMIZATION_LOG.md` when a change is kept:

- **Hypothesis:** what is supposed to get faster, and why
- **Target workload class:** parser / optimizer / comprehension / render / etc.
- **Baseline revision:** commit SHA or named checkpoint
- **Candidate revision:** commit SHA
- **Commands:** exact benchmark commands, including JMH flags
- **Environment:** JDK version plus any notable JVM args
- **Results:** before/after numbers for the target workload and broader gate
- **Correctness checks:** tests or golden verification run alongside the benchmark
- **Decision:** kept / reverted / diagnostic only

If any of those are missing, the result is not ready to carry a broader performance claim.

## Practical default protocol for future waves

For most risky JVM perf work, use this sequence:

1. Level 0 diagnostic data to choose a hotspot
2. Level 1 focused benchmark for the intended subsystem
3. Relevant Level 2 regression file or workload class
4. `MainBenchmark.main`
5. `./mill bench.runRegressions`
6. Level 4 branch-vs-base comparison before keeping the wave

For very small, clearly localized changes, the focused benchmark and the relevant regression slice may be enough during iteration, but promotion still requires the broader checks before the change is considered kept.

## Related files

- `bench/OPTIMIZATION_LOG.md`
- `bench/OPTIMIZATION_ROADMAP.md`
- `bench/EXTERNAL_ENGINE_ANALYSIS.md`
- `bench/AST_VISIT_COUNTS.md`
- `bench/resources/README.md`
