# string-template-wave2

## Scope
Evaluate the next JVM string-template wave with two contained attempts:
1. `Format.format` primitive fast path to avoid unnecessary `ujson` materialization/render for primitive `%` arguments.
2. Fallback: optimize `std.join` string-separator path with exact output-capacity precomputation.

Both attempts were required to preserve semantics and pass targeted correctness/perf gates.

## Commands run

Baseline:
- `./mill 'sjsonnet.jvm[3.3.7]'.test`
- `./mill bench.runRegressions bench/resources/cpp_suite/large_string_template.jsonnet`
- `./mill bench.runRegressions bench/resources/cpp_suite/realistic1.jsonnet`
- `./mill bench.runRegressions bench/resources/cpp_suite/large_string_join.jsonnet`
- `./mill bench.runJmh -i 1 -wi 1 -f 1 'sjsonnet.bench.MainBenchmark.main'`

Attempt 1 (`Format.format` primitive fast path):
- same 5 commands above

Attempt 2 (`std.join` string path capacity precompute):
- same 5 commands above

## Measurements (ms/op, lower is better)

| Benchmark | Baseline | Attempt 1 | Attempt 2 |
|---|---:|---:|---:|
| `large_string_template` | 2.777 | 2.442 | 2.465 |
| `realistic1` | 3.214 | 2.942 | 2.956 |
| `large_string_join` | 2.875 | 2.296 | 2.598 |
| `MainBenchmark.main` | 3.136 | 3.286 | 3.652 |

## Attempt notes

### Attempt 1: `Format.format` primitive fast path
- Change: direct handling of `Val.Str` / `Val.Num` / `Val.Bool` / `Val.Null` in `Format.format` before materialization; kept array/object handling via stringification path.
- Correctness: `./mill 'sjsonnet.jvm[3.3.7]'.test` passed.
- Performance:
  - focused template/join regressions improved,
  - but broad gate regressed (`MainBenchmark.main` 3.136 -> 3.286, ~+4.8%).
- Decision: **rejected and reverted**.

### Attempt 2: `std.join` string path capacity precompute
- Change: two-pass string join path in `StringModule.Join`:
  - pre-validate element types and compute exact expected output length,
  - allocate `StringBuilder` with computed capacity,
  - append joined output in one second pass.
- Correctness: `./mill 'sjsonnet.jvm[3.3.7]'.test` passed.
- Performance:
  - still improved focused template/join benchmarks versus baseline,
  - but was worse than attempt 1 and strongly regressed broad gate (`MainBenchmark.main` 3.136 -> 3.652, ~+16.5%).
- Decision: **rejected and reverted**.

## Final decision
- **Wave rejected**.
- No source changes kept in `Format.scala` or `StringModule.scala`.
- Keep this lane parked until a narrower idea can improve template-focused regressions without harming `MainBenchmark.main`.
