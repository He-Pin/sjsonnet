# static-lookup-wave

## Scope
- Lane: `static-lookup-wave`.
- Objective: add a narrow one-shot static lookup path on top of `static-object-wave` interned layouts, without broad visible-read caching.
- Guardrails honored:
  - changes stayed in `Val.Obj` and `StaticOptimizer` only
  - no materializer/object-read cache hooks were added

## Attempt 1: one-shot static lookup helper + static-aware folds (reverted)
Change summary:
- Added `Val.Obj.hasStaticLayout` and `Val.Obj.staticValueOrNull(key)` for one-shot static-layout reads.
- Reused a shared `staticLookupIndex` helper to avoid duplicate static key-map probes in `containsKey`/static lookup.
- Updated optimizer folds:
  - `Select`: first try `staticValueOrNull` for object constants, then existing generic object fold fallback.
  - `Lookup` (`obj["k"]`): fold when object/index are constants and static key exists.
  - `OP_in`: use static-layout one-shot membership check when available.

## Level-4 branch-vs-base record (review follow-up)
Baseline and candidate:
- Baseline revision (clean separate worktree): `9cc087697a8dc05433da4f6c9aadc0b9cb9e2158`.
- Candidate revision: `9cc087697a8dc05433da4f6c9aadc0b9cb9e2158` + current uncommitted `static-lookup-wave` working-tree changes (`Val.scala`, `StaticOptimizer.scala`).

Environment pinning:
- Same machine and same shell session for both sides.
- Mill runtime (`./mill --version`) for both worktrees: `java.version: 21.0.9`, `java.vendor: Azul Systems, Inc.`, `os.arch: aarch64`, `os.name: Mac OS X`.
- JMH VM for both sides reported `JDK 21.0.9` with identical command lines.

Protocol:
- Used a clean base worktree: `.worktrees/static-lookup-base`.
- Warmed both revisions (`./mill --version`) before timed commands.
- Used identical commands per side and alternating order (`base -> branch -> branch -> base` pattern across the focused command list).

Commands measured on both revisions:
- `./mill bench.runJmh -i 1 -wi 1 -f 1 'sjsonnet.bench.MainBenchmark.main'`
- `./mill bench.runJmh -i 1 -wi 1 -f 1 'sjsonnet.bench.OptimizerBenchmark.main'`
- `./mill bench.runRegressions bench/resources/go_suite/manifestJsonEx.jsonnet`
- `./mill bench.runRegressions bench/resources/go_suite/manifestYamlDoc.jsonnet`
- `./mill bench.runRegressions bench/resources/go_suite/manifestTomlEx.jsonnet`
- `./mill bench.runRegressions bench/resources/cpp_suite/realistic1.jsonnet`

Results (`ms/op`, lower is better):

| Benchmark | Base | Branch | Delta |
| --- | ---: | ---: | ---: |
| `MainBenchmark.main` | 3.442 | 3.653 | +6.13% (slower) |
| `OptimizerBenchmark.main` | 0.573 | 0.662 | +15.53% (slower) |
| `manifestJsonEx` | 0.085 | 0.077 | -9.41% (faster) |
| `manifestYamlDoc` | 0.083 | 0.081 | -2.41% (faster) |
| `manifestTomlEx` | 0.098 | 0.095 | -3.06% (faster) |
| `realistic1` | 2.744 | 2.688 | -2.04% (faster) |

Interpretation of focused regressions:
- The three `manifest*` rows plus `realistic1` are **targeted spot checks** for this lookup/manifest-adjacent path; they are not a broad keep-gate by themselves.
- `realistic1` improved in this branch-vs-base run, which is directionally good, but that single-file result does not offset the branch slowdown seen in `MainBenchmark.main` and `OptimizerBenchmark.main`.

## Decision status
- Level-4 branch-vs-base evidence fails broad gates:
  - `MainBenchmark.main` regressed by `+6.13%`
  - `OptimizerBenchmark.main` regressed by `+15.53%`
- Focused `manifest*` and `realistic1` improvements are preserved as evidence, but per the ladder they do not override broad benchmark regressions.
- Final status: **rejected and reverted**.

## Post-revert verification
- Reverted lane source files:
  - `sjsonnet/src/sjsonnet/Val.scala`
  - `sjsonnet/src/sjsonnet/StaticOptimizer.scala`
- Verification command:
  - `git --no-pager diff -- sjsonnet/src/sjsonnet/Val.scala sjsonnet/src/sjsonnet/StaticOptimizer.scala`
- Result: empty diff (no remaining source changes from `static-lookup-wave`).
