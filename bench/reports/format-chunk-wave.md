# format-chunk-wave

## Scope
- Lane: `format-chunk-wave` (contained changes in `sjsonnet/src/sjsonnet/Format.scala` only).
- Constraints honored:
  - did **not** repeat rejected `string-template-wave2` ideas (primitive-materialization bypass / join-capacity).
  - tried attempt 1 first; because it was not clearly positive, reverted and ran contained attempt 2.

## Baseline (pre-change)
Commands:
- `./mill bench.runRegressions bench/resources/cpp_suite/large_string_template.jsonnet`
- `./mill bench.runRegressions bench/resources/cpp_suite/realistic1.jsonnet`
- `./mill bench.runJmh -i 1 -wi 1 -f 1 'sjsonnet.bench.MainBenchmark.main'`

Measurements (ms/op):
- `large_string_template`: `2.625`
- `realistic1`: `3.146`
- `MainBenchmark.main`: `3.735`

## Attempt 1: no-star runtime fast path with cached `hasAnyStar`
Change summary (Format.scala only):
- Extended parsed-format cache payload to include precomputed `hasAnyStar`.
- Added a no-star fast retrieval path to skip width/precision `*` branch tree when format has no stars.
- Wired `PartialApplyFmt` to precompute/reuse the same metadata.

Validation commands:
- `./mill __.checkFormat`
- `./mill 'sjsonnet.jvm[3.3.7]'.test`
- `./mill bench.runRegressions bench/resources/cpp_suite/large_string_template.jsonnet`
- `./mill bench.runRegressions bench/resources/cpp_suite/realistic1.jsonnet`
- `./mill bench.runJmh -i 1 -wi 1 -f 1 'sjsonnet.bench.MainBenchmark.main'`

Attempt-1 focused measurements (ms/op, final post-format run):
- `large_string_template`: `2.446` (improved vs baseline)
- `realistic1`: `3.047` (improved vs baseline)
- `MainBenchmark.main`: `3.943` (**regressed** vs baseline)

Decision:
- **Rejected and reverted** (not clearly positive due broad-gate regression).

## Attempt 2: chunk lowering + indexed runtime loop + pre-sized builder
Change summary (Format.scala only):
- Lower parsed chunks into a cheaper indexed representation (`RuntimeFormat`) with:
  - `Array[FormatSpec]` specs,
  - `Array[String]` literals,
  - precomputed `staticChars` for `StringBuilder` sizing,
  - precomputed `hasAnyStar`.
- Switched core formatting loop from `for`/`zipWithIndex` to indexed `while` iteration over arrays.
- Reused lowered representation in cached parsing and `PartialApplyFmt`.

Validation commands:
- `./mill __.checkFormat`
- `./mill 'sjsonnet.jvm[3.3.7]'.test`
- `./mill bench.runRegressions bench/resources/cpp_suite/large_string_template.jsonnet`
- `./mill bench.runRegressions bench/resources/cpp_suite/realistic1.jsonnet`
- `./mill bench.runJmh -i 1 -wi 1 -f 1 'sjsonnet.bench.MainBenchmark.main'`

Attempt-2 focused measurements (ms/op, final run):
- `large_string_template`: `2.512` (vs baseline `2.625`)
- `realistic1`: `2.673` (vs baseline `3.146`)
- `MainBenchmark.main`: `3.454` (vs baseline `3.735`)

Decision:
- **Kept** (positive on both focused regressions and broad JMH gate).

## Keep-gate (required after positive attempt)
Command:
- `./mill bench.runRegressions`

Result:
- `SUCCESS` (`125/125`) in `442s`.
- Relevant rows from full suite:
  - `bench/resources/cpp_suite/large_string_template.jsonnet`: `2.400 ms/op`
  - `bench/resources/cpp_suite/realistic1.jsonnet`: `2.782 ms/op`

## Final decision
- Wave outcome: **accepted via attempt 2**.
- Retained source diff: `sjsonnet/src/sjsonnet/Format.scala` only.
- Attempt 1 was reverted before attempt 2, per policy.
