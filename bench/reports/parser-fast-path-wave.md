# Parser fast path wave

Outcome: rejected and reverted. No parser code change is kept from this wave.

## Baseline

Commands run:

```bash
./mill 'sjsonnet.jvm[3.3.7]'.test
./mill bench.runJmh -i 1 -wi 1 -f 1 'sjsonnet.bench.ParserBenchmark.main'
./mill bench.runJmh -i 1 -wi 1 -f 1 'sjsonnet.bench.MainBenchmark.main'
./mill bench.runRegressions bench/resources/cpp_suite/large_string_join.jsonnet
./mill bench.runRegressions bench/resources/cpp_suite/large_string_template.jsonnet
./mill bench.runRegressions bench/resources/go_suite/member.jsonnet
```

Baseline results:

| Benchmark | Baseline |
| --- | ---: |
| `ParserBenchmark.main` | `1.434 ms/op` |
| `MainBenchmark.main` | `3.097 ms/op` |
| `bench/resources/cpp_suite/large_string_join.jsonnet` | `2.214 ms/op` |
| `bench/resources/cpp_suite/large_string_template.jsonnet` | `2.192 ms/op` |
| `bench/resources/go_suite/member.jsonnet` | `0.720 ms/op` |

## Attempt 1: numeric no-underscore fast path

Change tried:

- In `Parser.number`, skip underscore validation and `replace("_", "")` when the parsed number contains no underscore.

Commands run:

```bash
./mill 'sjsonnet.jvm[3.3.7]'.test
./mill bench.runJmh -i 1 -wi 1 -f 1 'sjsonnet.bench.ParserBenchmark.main'
./mill bench.runJmh -i 1 -wi 1 -f 1 'sjsonnet.bench.MainBenchmark.main'
./mill bench.runRegressions bench/resources/go_suite/member.jsonnet
```

Results:

| Benchmark | Baseline | Attempt 1 | Delta |
| --- | ---: | ---: | ---: |
| `ParserBenchmark.main` | `1.434 ms/op` | `1.533 ms/op` | `+0.099 ms/op` (`+6.9%`) |
| `MainBenchmark.main` | `3.097 ms/op` | `3.268 ms/op` | `+0.171 ms/op` (`+5.5%`) |
| `bench/resources/go_suite/member.jsonnet` | `0.720 ms/op` | `0.796 ms/op` | `+0.076 ms/op` (`+10.6%`) |

Decision: reject. The change regressed the parser benchmark, end-to-end main benchmark, and the numeric-heavy targeted regression.

## Attempt 2: quoted string fast path

Change tried:

- Add direct fast paths for plain `'...'` and `"..."` string literals and bypass `mkString` for one-fragment strings.

Command run:

```bash
./mill 'sjsonnet.jvm[3.3.7]'.test
```

Result:

- Rejected on correctness before any benchmark rerun.
- `sjsonnet.FileTests.test_suite` failed with 8 golden mismatches because parse failures started surfacing the fast-path parser label instead of the historical expected message.
- Representative mismatch:
  - expected: `sjsonnet.ParseError: Expected "\"":2:1, found ""`
  - actual: `sjsonnet.ParseError: Expected (fastDoubleString | doubleString):1:2, found "\n"`
- Failing suite cases:
  - `error.import_syntax-error.jsonnet`
  - `error.parse.string.invalid_escape.jsonnet`
  - `error.parse.string.invalid_escape_unicode_non_hex.jsonnet`
  - `error.parse.string.invalid_escape_unicode_short.jsonnet`
  - `error.parse.string.invalid_escape_unicode_short2.jsonnet`
  - `error.parse.string.invalid_escape_unicode_short3.jsonnet`
  - `error.parse.string.unfinished.jsonnet`
  - `error.parse.string.unfinished2.jsonnet`

Decision: reject. Even before performance measurement, this alternative changed externally checked parser failure text.

## Final state

- `sjsonnet/src/sjsonnet/Parser.scala` was reverted to its original state.
- No kept parser optimization came out of this wave.
- `./mill bench.runRegressions` was **not** run after the attempts because no candidate survived correctness + focused benchmark gates and there was nothing eligible to keep.

Post-revert verification:

```bash
./mill 'sjsonnet.jvm[3.3.7]'.test
```

Post-revert result: passed.
