# object-read-cache-wave

Outcome: rejected and reverted. No source code change is kept from this wave.

## Scope
- Lane: `object-read-cache-wave`.
- Goal: try a narrow repeated-read cache / merged visible-read fast path built on `Val.Obj` key caches without changing dynamic object semantics.
- Files inspected as requested:
  - `sjsonnet/src/sjsonnet/Val.scala`
  - `sjsonnet/src/sjsonnet/Materializer.scala`
  - `sjsonnet/src/sjsonnet/stdlib/ManifestModule.scala`
  - `sjsonnet/src/sjsonnet/stdlib/ObjectModule.scala`

## Baseline (no lane source diff)
Commands run:

```bash
./mill 'sjsonnet.jvm[3.3.7]'.test
./mill bench.runRegressions bench/resources/go_suite/manifestJsonEx.jsonnet
./mill bench.runRegressions bench/resources/go_suite/manifestYamlDoc.jsonnet
./mill bench.runRegressions bench/resources/go_suite/manifestTomlEx.jsonnet
./mill bench.runRegressions bench/resources/cpp_suite/realistic1.jsonnet
./mill bench.runRegressions bench/resources/cpp_suite/realistic2.jsonnet
./mill bench.runJmh -i 1 -wi 1 -f 1 'sjsonnet.bench.MainBenchmark.main'
```

Baseline measurements:

| Benchmark | Baseline |
| --- | ---: |
| `manifestJsonEx` | `0.122 ms/op` |
| `manifestYamlDoc` | `0.121 ms/op` |
| `manifestTomlEx` | `0.164 ms/op` |
| `realistic1` | `2.840 ms/op` |
| `realistic2` | `72.551 ms/op` |
| `MainBenchmark.main` | `3.920 ms/op` |

## Attempt 1: `Val.Obj` visible-read value-array cache + Materializer hookup
Change tried:
- Added a conservative `Val.Obj` visible-read value-array cache API (`getVisibleValuesForKnownKeys`) gated to static/no-super/no-exclusion/all-cached-member cases.
- Hooked `Materializer` object loops (recursive and stackless) to consume the cached value arrays when available.

Validation commands run:

```bash
./mill 'sjsonnet.jvm[3.3.7]'.test
./mill bench.runRegressions bench/resources/go_suite/manifestJsonEx.jsonnet
./mill bench.runRegressions bench/resources/go_suite/manifestYamlDoc.jsonnet
./mill bench.runRegressions bench/resources/go_suite/manifestTomlEx.jsonnet
./mill bench.runRegressions bench/resources/cpp_suite/realistic1.jsonnet
./mill bench.runRegressions bench/resources/cpp_suite/realistic2.jsonnet
./mill bench.runJmh -i 1 -wi 1 -f 1 'sjsonnet.bench.MainBenchmark.main'
```

Measurements vs baseline:

| Benchmark | Baseline | Attempt 1 | Delta |
| --- | ---: | ---: | ---: |
| `manifestJsonEx` | `0.122` | `0.081` | `-0.041` (`-33.6%`) |
| `manifestYamlDoc` | `0.121` | `0.083` | `-0.038` (`-31.4%`) |
| `manifestTomlEx` | `0.164` | `0.097` | `-0.067` (`-40.9%`) |
| `realistic1` | `2.840` | `2.988` | `+0.148` (`+5.2%`) |
| `realistic2` | `72.551` | `82.266` | `+9.715` (`+13.4%`) |
| `MainBenchmark.main` | `3.920` | `5.028` | `+1.108` (`+28.3%`) |

Resolution:
- Rejected and reverted due broad regressions (`realistic2` and `MainBenchmark.main`) despite manifest-case wins.

## Attempt 2 (fallback): contained repeated-read reuse in `manifestTomlEx`
Change tried:
- Reverted attempt 1.
- Added a narrower consumer-only fallback in `ManifestModule.renderTableInternal`:
  - single pass over `sortedVisibleKeyNames` to read each field once,
  - reused cached local arrays (`keyValues`, `sectionFlags`) in both non-section and section loops.

Validation commands run:

```bash
./mill 'sjsonnet.jvm[3.3.7]'.test
./mill bench.runRegressions bench/resources/go_suite/manifestJsonEx.jsonnet
./mill bench.runRegressions bench/resources/go_suite/manifestYamlDoc.jsonnet
./mill bench.runRegressions bench/resources/go_suite/manifestTomlEx.jsonnet
./mill bench.runRegressions bench/resources/cpp_suite/realistic1.jsonnet
./mill bench.runRegressions bench/resources/cpp_suite/realistic2.jsonnet
./mill bench.runJmh -i 1 -wi 1 -f 1 'sjsonnet.bench.MainBenchmark.main'
```

Measurements vs baseline:

| Benchmark | Baseline | Attempt 2 | Delta |
| --- | ---: | ---: | ---: |
| `manifestJsonEx` | `0.122` | `0.099` | `-0.023` (`-18.9%`) |
| `manifestYamlDoc` | `0.121` | `0.105` | `-0.016` (`-13.2%`) |
| `manifestTomlEx` | `0.164` | `0.113` | `-0.051` (`-31.1%`) |
| `realistic1` | `2.840` | `2.841` | `+0.001` (`+0.0%`) |
| `realistic2` | `72.551` | `89.704` | `+17.153` (`+23.6%`) |
| `MainBenchmark.main` | `3.920` | `7.201` | `+3.281` (`+83.7%`) |

Resolution:
- Rejected and reverted due strong broad regressions.
- `realistic2` and `MainBenchmark.main` are treated here as broader smoke/broad-gate signals rather than direct targeted path-isolation measurements for the TOML-only fallback. Even with that caveat, the regressions were large enough that the fallback was not safe to keep.

## Final state
- All lane source changes were reverted:
  - `sjsonnet/src/sjsonnet/Val.scala` (attempt 1 reverted)
  - `sjsonnet/src/sjsonnet/Materializer.scala` (attempt 1 reverted)
  - `sjsonnet/src/sjsonnet/stdlib/ManifestModule.scala` (attempt 2 reverted)
- Keep-gate `./mill bench.runRegressions` full suite was **not** run because neither attempt was positive and both were reverted.
- Repository left consistent with a rejected wave (report + log update only).

## Post-revert verification
- `git --no-pager diff --name-only -- sjsonnet/src/sjsonnet/Val.scala sjsonnet/src/sjsonnet/Materializer.scala sjsonnet/src/sjsonnet/stdlib/ManifestModule.scala` produced no output.
- `git --no-pager status --short` after revert showed only:
  - `M bench/OPTIMIZATION_LOG.md`
  - `?? bench/reports/object-read-cache-wave.md`
