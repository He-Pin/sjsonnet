# stdlib-loop-wave

## Scope
- Lane: item 9 from the master plan (`stdlib: replace selected combinators with manual loops`).
- Files evaluated: `sjsonnet/src/sjsonnet/stdlib/ArrayModule.scala`.
- Process: baseline + two contained attempts (required retry policy), revert non-positive candidates.

## Baseline (no wave source change)
Commands run:
- `./mill 'sjsonnet.jvm[3.3.7]'.test`
- `./mill bench.runRegressions bench/resources/go_suite/member.jsonnet`
- `./mill bench.runRegressions bench/resources/go_suite/reverse.jsonnet`
- `./mill bench.runRegressions bench/resources/go_suite/foldl.jsonnet`
- `./mill bench.runRegressions bench/resources/sjsonnet_suite/setDiff.jsonnet`
- `./mill bench.runRegressions bench/resources/sjsonnet_suite/setInter.jsonnet`
- `./mill bench.runRegressions bench/resources/sjsonnet_suite/setUnion.jsonnet`
- `./mill bench.runJmh -i 1 -wi 1 -f 1 'sjsonnet.bench.MainBenchmark.main'`

Measurements:
- `member.jsonnet`: `0.749 ms/op`
- `reverse.jsonnet`: `11.297 ms/op`
- `foldl.jsonnet`: `10.090 ms/op`
- `setDiff.jsonnet`: `0.514 ms/op`
- `setInter.jsonnet`: `0.450 ms/op`
- `setUnion.jsonnet`: `0.790 ms/op`
- `MainBenchmark.main`: `3.302 ms/op`

## Attempt 1 (starting candidate): `std.member` array path `indexWhere` -> manual while loop
Change:
- `ArrayModule.Member`: replaced `indexWhere(v => ev.equal(v.value, x.value)) >= 0` with indexed while loop.

Commands run:
- `./mill 'sjsonnet.jvm[3.3.7]'.test`
- `./mill bench.runRegressions bench/resources/go_suite/member.jsonnet`
- `./mill bench.runRegressions bench/resources/go_suite/reverse.jsonnet`
- `./mill bench.runRegressions bench/resources/go_suite/foldl.jsonnet`
- `./mill bench.runRegressions bench/resources/sjsonnet_suite/setDiff.jsonnet`
- `./mill bench.runRegressions bench/resources/sjsonnet_suite/setInter.jsonnet`
- `./mill bench.runRegressions bench/resources/sjsonnet_suite/setUnion.jsonnet`
- `./mill bench.runJmh -i 1 -wi 1 -f 1 'sjsonnet.bench.MainBenchmark.main'`

Measurements vs baseline:
- `member.jsonnet`: `0.806 ms/op` (`+0.057`, `+7.6%`)
- `reverse.jsonnet`: `11.632 ms/op` (`+0.335`, `+3.0%`)
- `foldl.jsonnet`: `10.456 ms/op` (`+0.366`, `+3.6%`)
- `setDiff.jsonnet`: `0.520 ms/op` (`+0.006`, `+1.2%`)
- `setInter.jsonnet`: `0.448 ms/op` (`-0.002`, `-0.4%`)
- `setUnion.jsonnet`: `0.806 ms/op` (`+0.016`, `+2.0%`)
- `MainBenchmark.main`: `3.550 ms/op` (`+0.248`, `+7.5%`)

Decision:
- **Rejected and reverted** (broadly negative, especially `MainBenchmark.main`).

## Attempt 2 (fallback in same family): `std.foldl` array iteration for-loop -> indexed while loop
Change:
- `ArrayModule.Foldl` array branch: replaced Scala `for (item <- arr.asLazyArray)` iterator loop with indexed while loop over `lazyArr`.

Commands run:
- `./mill 'sjsonnet.jvm[3.3.7]'.test`
- `./mill bench.runRegressions bench/resources/go_suite/member.jsonnet`
- `./mill bench.runRegressions bench/resources/go_suite/reverse.jsonnet`
- `./mill bench.runRegressions bench/resources/go_suite/foldl.jsonnet`
- `./mill bench.runRegressions bench/resources/sjsonnet_suite/setDiff.jsonnet`
- `./mill bench.runRegressions bench/resources/sjsonnet_suite/setInter.jsonnet`
- `./mill bench.runRegressions bench/resources/sjsonnet_suite/setUnion.jsonnet`
- `./mill bench.runJmh -i 1 -wi 1 -f 1 'sjsonnet.bench.MainBenchmark.main'`

Measurements vs baseline:
- `member.jsonnet`: `0.726 ms/op` (`-0.023`, `-3.1%`)
- `reverse.jsonnet`: `11.133 ms/op` (`-0.164`, `-1.5%`)
- `foldl.jsonnet`: `10.446 ms/op` (`+0.356`, `+3.5%`)
- `setDiff.jsonnet`: `0.492 ms/op` (`-0.022`, `-4.3%`)
- `setInter.jsonnet`: `0.443 ms/op` (`-0.007`, `-1.6%`)
- `setUnion.jsonnet`: `0.785 ms/op` (`-0.005`, `-0.6%`)
- `MainBenchmark.main`: `3.435 ms/op` (`+0.133`, `+4.0%`)

Decision:
- **Rejected and reverted** (core gate `MainBenchmark.main` regressed; targeted foldl case also regressed).

## Final wave decision
- Outcome: **rejected**.
- Keep/reject rationale: both contained attempts were benchmark-negative on the broad gate (`MainBenchmark.main`) after passing correctness checks.
- Keep-gate status: `./mill bench.runRegressions` full suite was **not run** because no attempt cleared the positive focused gate.
- Repo state for this lane: **no retained source diffs** in `ArrayModule.scala`.
