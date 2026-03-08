# format-followup-wave

## Scope
- Lane: `format-followup-wave`.
- Constraints honored:
  - `Format.scala`-only source attempts.
  - Attempt 1 executed first; because it was negative, it was reverted before attempt 2.
  - Attempt 2 also negative; all source changes reverted.

## Baseline (pre-change)
Commands:
- `./mill bench.runRegressions bench/resources/cpp_suite/large_string_template.jsonnet`
- `./mill bench.runRegressions bench/resources/cpp_suite/realistic1.jsonnet`
- `./mill bench.runJmh -i 1 -wi 1 -f 1 'sjsonnet.bench.MainBenchmark.main'`

Measurements (ms/op):
- `large_string_template`: `2.482`
- `realistic1`: `2.707`
- `MainBenchmark.main`: `3.115`

## Attempt 1: compact runtime-spec lowering in `Format.scala`
Change summary (reverted):
- Lowered parsed `FormatSpec` into a compact `RuntimeSpec` form for the hot path.
- Replaced `Option`-heavy width/precision accesses with sentinel-int metadata.
- Added bit/flag-style runtime classification for format flags and star handling.

Validation commands:
- `./mill __.checkFormat`
- `./mill 'sjsonnet.jvm[3.3.7]'.test`
- `./mill bench.runRegressions bench/resources/cpp_suite/large_string_template.jsonnet`
- `./mill bench.runRegressions bench/resources/cpp_suite/realistic1.jsonnet`
- `./mill bench.runJmh -i 1 -wi 1 -f 1 'sjsonnet.bench.MainBenchmark.main'`

Attempt-1 focused measurements (ms/op):
- `large_string_template`: `3.025`
- `realistic1`: `3.229`
- `MainBenchmark.main`: `3.442`

Decision:
- **Rejected and reverted** (regressed both focused regressions and broad JMH signal).

## Attempt 2: builder-oriented append/widen helpers in `Format.scala`
Change summary (reverted):
- Added append-oriented widening helpers writing directly to the main `StringBuilder`.
- Replaced several `widenRaw` temporary-string paths with direct builder appends.
- Kept contained to `Format.scala` runtime formatting flow.

Validation commands:
- `./mill __.checkFormat`
- `./mill 'sjsonnet.jvm[3.3.7]'.test`
- `./mill bench.runRegressions bench/resources/cpp_suite/large_string_template.jsonnet`
- `./mill bench.runRegressions bench/resources/cpp_suite/realistic1.jsonnet`
- `./mill bench.runJmh -i 1 -wi 1 -f 1 'sjsonnet.bench.MainBenchmark.main'`

Attempt-2 focused measurements (ms/op):
- `large_string_template`: `2.697`
- `realistic1`: `2.924`
- `MainBenchmark.main`: `4.166`

Decision:
- **Rejected and reverted** (all three focused signals regressed, especially broad JMH).

## Final decision
- Wave outcome: **rejected**.
- Retained source diff: **none** in `sjsonnet/src/sjsonnet/Format.scala` (both attempts reverted).
- Documentation updates kept:
  - `bench/reports/format-followup-wave.md`
  - `bench/OPTIMIZATION_LOG.md`

## Post-revert verification
- `git --no-pager diff --name-only -- sjsonnet/src/sjsonnet/Format.scala` produced no output.
- `git --no-pager status --short` after revert showed only:
  - `M bench/OPTIMIZATION_LOG.md`
  - `?? bench/reports/format-followup-wave.md`
