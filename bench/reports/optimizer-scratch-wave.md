# Optimizer scratch wave

Outcome: rejected and reverted. No `StaticOptimizer.scala` code change is kept from this wave.

## Baseline

Commands run:

```bash
./mill bench.runJmh -i 1 -wi 1 -f 1 'sjsonnet.bench.OptimizerBenchmark.main'
./mill bench.runJmh -i 1 -wi 1 -f 1 'sjsonnet.bench.MainBenchmark.main'
./mill bench.runRegressions bench/resources/go_suite/comparison2.jsonnet
```

Baseline results:

| Benchmark | Baseline |
| --- | ---: |
| `OptimizerBenchmark.main` | `0.533 ms/op` |
| `MainBenchmark.main` | `3.132 ms/op` |
| `bench/resources/go_suite/comparison2.jsonnet` | `71.522 ms/op` |

Notes:

- The two JMH baselines were captured before any code change.
- The focused `comparison2` baseline was captured after reverting because the first baseline pass omitted it; the revert restored the original `StaticOptimizer.scala`, so this still reflects the baseline implementation for this wave.

## Attempt 1: builder-backed initial scope map + skip redundant second-pass updates

Change tried:

- In `StaticOptimizer.nestedConsecutiveBindings`, build the initial merged binding scope with `HashMap.newBuilder` instead of repeated `updated`, and skip the second-pass `mappings.updated(...)` when a binding transform returns the original bind.

Commands run:

```bash
./mill 'sjsonnet.jvm[3.3.7]'.test
./mill bench.runJmh -i 1 -wi 1 -f 1 'sjsonnet.bench.OptimizerBenchmark.main'
./mill bench.runJmh -i 1 -wi 1 -f 1 'sjsonnet.bench.MainBenchmark.main'
./mill bench.runRegressions bench/resources/go_suite/comparison2.jsonnet
```

Results:

| Benchmark | Baseline | Attempt 1 | Delta |
| --- | ---: | ---: | ---: |
| `OptimizerBenchmark.main` | `0.533 ms/op` | `0.541 ms/op` | `+0.008 ms/op` (`+1.5%`) |
| `MainBenchmark.main` | `3.132 ms/op` | `3.487 ms/op` | `+0.355 ms/op` (`+11.3%`) |
| `bench/resources/go_suite/comparison2.jsonnet` | `71.522 ms/op` | `74.171 ms/op` | `+2.649 ms/op` (`+3.7%`) |

Decision: reject. The builder rewrite regressed both optimizer and end-to-end JMH, and the focused regression benchmark also landed worse than the reverted baseline.

## Attempt 2: skip redundant second-pass updates only

Change tried:

- Keep the original first-pass immutable `HashMap.updated` loop in `nestedConsecutiveBindings`, but skip the second-pass `bindingScope.mappings.updated(...)` when `transformBind` returns the original binding.

Commands run:

```bash
./mill 'sjsonnet.jvm[3.3.7]'.test
./mill bench.runJmh -i 1 -wi 1 -f 1 'sjsonnet.bench.OptimizerBenchmark.main'
./mill bench.runJmh -i 1 -wi 1 -f 1 'sjsonnet.bench.MainBenchmark.main'
./mill bench.runRegressions bench/resources/go_suite/comparison2.jsonnet
```

Results:

| Benchmark | Baseline | Attempt 2 | Delta |
| --- | ---: | ---: | ---: |
| `OptimizerBenchmark.main` | `0.533 ms/op` | `0.546 ms/op` | `+0.013 ms/op` (`+2.4%`) |
| `MainBenchmark.main` | `3.132 ms/op` | `3.155 ms/op` | `+0.023 ms/op` (`+0.7%`) |
| `bench/resources/go_suite/comparison2.jsonnet` | `71.522 ms/op` | `72.370 ms/op` | `+0.848 ms/op` (`+1.2%`) |

Decision: reject. This narrower version avoided the large regression from attempt 1, but it still moved all three focused measurements in the wrong direction.

## Final state

- `sjsonnet/src/sjsonnet/StaticOptimizer.scala` was reverted to its original contents.
- No full `./mill bench.runRegressions` keep-gate was run, because neither candidate survived the focused benchmark gate and there was no change eligible to keep.

Post-revert verification:

```bash
./mill 'sjsonnet.jvm[3.3.7]'.test
git --no-pager diff --exit-code -- sjsonnet/src/sjsonnet/StaticOptimizer.scala
test -f bench/reports/optimizer-scratch-wave.md
```

Result: passed. The JVM test suite succeeded after the revert, the optimizer source diff is empty, and the rejection report is present.
