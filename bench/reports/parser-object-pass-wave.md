# parser-object-pass-wave

Outcome: rejected and reverted. No parser source change is kept from this wave.

## Scope
- Lane: parser object-member single-pass collection (`Parser.objinside` member-list path).
- Goal: reduce allocation churn from multi-pass member extraction (`binds`/`fields`/`asserts`) without changing parse behavior.

## Baseline (no lane source diff)
Commands run:

```bash
./mill 'sjsonnet.jvm[3.3.7]'.test
./mill bench.runJmh -i 1 -wi 1 -f 1 'sjsonnet.bench.ParserBenchmark.main'
./mill bench.runJmh -i 1 -wi 1 -f 1 'sjsonnet.bench.MainBenchmark.main'
./mill bench.runRegressions bench/resources/cpp_suite/gen_big_object.jsonnet
./mill bench.runRegressions bench/resources/cpp_suite/realistic1.jsonnet
```

Baseline measurements:

| Benchmark | Baseline |
| --- | ---: |
| `ParserBenchmark.main` | `1.541 ms/op` |
| `MainBenchmark.main` | `3.434 ms/op` |
| `gen_big_object.jsonnet` | `1.150 ms/op` |
| `realistic1.jsonnet` | `3.184 ms/op` |

## Attempt 1: full member-list single-pass classification + duplicate tracking
Change tried:
- Reworked `objinside` member-list parsing to classify object members in one pass and perform duplicate-field/local detection within that pass.

Validation command run:

```bash
./mill 'sjsonnet.jvm[3.3.7]'.test
```

Result:
- Rejected on correctness before benchmark comparison.
- Parse behavior changed (golden/assertion mismatches in externally-checked parser text/positions).
- Representative mismatches:
  - expected `Expected no duplicate field: a:1:13, found " }"` vs actual `Expected no duplicate field: a:1:14, found "}"`
  - expected `Expected no duplicate local: x:4:6, found "\n}"` vs actual `Expected no duplicate local: x:5:1, found "}"`
- Failing test buckets included:
  - `sjsonnet.ParserTests.duplicateFields`
  - `sjsonnet.ParserTests.localInObj`
  - `sjsonnet.FileTests.test_suite`
  - `sjsonnet.FileTests.go_test_suite`

Resolution:
- Reverted attempt 1 before proceeding.

## Attempt 2: conservative extraction pass reduction in member-list branch
Change tried:
- Kept existing duplicate-field stage and duplicate-local logic unchanged.
- Reduced extraction passes in `case (exprs, None)` by replacing separate filter-based `fields`/`asserts` collection with one contained classification pass.

Commands run:

```bash
./mill 'sjsonnet.jvm[3.3.7]'.test
./mill bench.runJmh -i 1 -wi 1 -f 1 'sjsonnet.bench.ParserBenchmark.main'
./mill bench.runJmh -i 1 -wi 1 -f 1 'sjsonnet.bench.MainBenchmark.main'
./mill bench.runRegressions bench/resources/cpp_suite/gen_big_object.jsonnet
./mill bench.runRegressions bench/resources/cpp_suite/realistic1.jsonnet
```

Measurements vs baseline:

| Benchmark | Baseline | Attempt 2 | Delta |
| --- | ---: | ---: | ---: |
| `ParserBenchmark.main` | `1.541 ms/op` | `1.538 ms/op` | `-0.003 ms/op` (`-0.2%`) |
| `MainBenchmark.main` | `3.434 ms/op` | `4.384 ms/op` | `+0.950 ms/op` (`+27.7%`) |
| `gen_big_object.jsonnet` | `1.150 ms/op` | `1.882 ms/op` | `+0.732 ms/op` (`+63.7%`) |
| `realistic1.jsonnet` | `3.184 ms/op` | `4.260 ms/op` | `+1.076 ms/op` (`+33.8%`) |

Resolution:
- Rejected and reverted due large regressions on broad and focused gates.

## Final state
- `sjsonnet/src/sjsonnet/Parser.scala` reverted to pre-wave state.
- Keep-gate `./mill bench.runRegressions` full suite was **not** run, because no attempt was both behavior-safe and benchmark-positive.

Post-revert verification:

```bash
./mill 'sjsonnet.jvm[3.3.7]'.test
git --no-pager diff --exit-code -- sjsonnet/src/sjsonnet/Parser.scala
```

Post-revert result: passed. The JVM test suite succeeded after the revert and `Parser.scala` has no remaining diff from this lane.
