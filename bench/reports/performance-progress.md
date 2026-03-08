# Performance Progress

## Baseline Benchmark

- Status: complete

- Date: 2026-03-07

- Todo: `baseline-benchmark`


### Commands run

```bash
./mill bench.runRegressions
uname -a
./mill --version
java -version
```

### Environment / assumptions

- Repository benchmark entry points documented in `bench/resources/README.md` and implemented in `build.mill`.
- Harness: `bench.runRegressions` invokes JMH benchmark `sjsonnet.bench.RegressionBenchmark.main` with a parameterized `path` list over all benchmark resource files.
- JMH runtime (from benchmark output): version 1.37, mode `AverageTime`, 1 fork, 1 warmup iteration x 2s, 1 measurement iteration x 10s, timeout 10 min/iteration, 1 thread.
- JVM used by Mill/JMH: Zulu OpenJDK 21.0.9 (`/Users/hepin/Library/Caches/Coursier/.../zulu-21.jdk/.../bin/java`) with `-Xss100m --enable-native-access=ALL-UNNAMED`.
- Host runtime observed separately: Darwin arm64 (`uname -a`), Mill 1.1.2; shell `java -version` resolves to Java 25.0.1, but the benchmark itself ran on the Mill-selected JDK 21.0.9 reported by JMH.

### Results

- Full regression benchmark suite completed successfully in **7m16s** across **35** cases.
- Aggregate score summary: min **0.053 ms/op**, median **0.734 ms/op**, mean **9.123 ms/op**, max **74.539 ms/op**.
- Slowest cases observed:
  - `bench/resources/cpp_suite/realistic2.jsonnet` — **74.539 ms/op**
  - `bench/resources/go_suite/comparison2.jsonnet` — **72.629 ms/op**
  - `bench/resources/cpp_suite/bench.02.jsonnet` — **50.931 ms/op**
  - `bench/resources/cpp_suite/bench.04.jsonnet` — **33.608 ms/op**
  - `bench/resources/go_suite/comparison.jsonnet` — **22.903 ms/op**

| Benchmark path | Score (ms/op) |
| --- | ---: |
| `bench/resources/bug_suite/assertions.jsonnet` | 0.307 |
| `bench/resources/cpp_suite/bench.01.jsonnet` | 0.077 |
| `bench/resources/cpp_suite/bench.02.jsonnet` | 50.931 |
| `bench/resources/cpp_suite/bench.03.jsonnet` | 14.567 |
| `bench/resources/cpp_suite/bench.04.jsonnet` | 33.608 |
| `bench/resources/cpp_suite/bench.06.jsonnet` | 0.455 |
| `bench/resources/cpp_suite/bench.07.jsonnet` | 3.215 |
| `bench/resources/cpp_suite/bench.08.jsonnet` | 0.062 |
| `bench/resources/cpp_suite/bench.09.jsonnet` | 0.069 |
| `bench/resources/cpp_suite/gen_big_object.jsonnet` | 1.117 |
| `bench/resources/cpp_suite/large_string_join.jsonnet` | 2.312 |
| `bench/resources/cpp_suite/large_string_template.jsonnet` | 2.343 |
| `bench/resources/cpp_suite/realistic1.jsonnet` | 2.762 |
| `bench/resources/cpp_suite/realistic2.jsonnet` | 74.539 |
| `bench/resources/go_suite/base64.jsonnet` | 0.796 |
| `bench/resources/go_suite/base64Decode.jsonnet` | 0.612 |
| `bench/resources/go_suite/base64DecodeBytes.jsonnet` | 9.452 |
| `bench/resources/go_suite/base64_byte_array.jsonnet` | 1.479 |
| `bench/resources/go_suite/comparison.jsonnet` | 22.903 |
| `bench/resources/go_suite/comparison2.jsonnet` | 72.629 |
| `bench/resources/go_suite/escapeStringJson.jsonnet` | 0.053 |
| `bench/resources/go_suite/foldl.jsonnet` | 9.445 |
| `bench/resources/go_suite/lstripChars.jsonnet` | 0.610 |
| `bench/resources/go_suite/manifestJsonEx.jsonnet` | 0.074 |
| `bench/resources/go_suite/manifestTomlEx.jsonnet` | 0.091 |
| `bench/resources/go_suite/manifestYamlDoc.jsonnet` | 0.078 |
| `bench/resources/go_suite/member.jsonnet` | 0.735 |
| `bench/resources/go_suite/parseInt.jsonnet` | 0.055 |
| `bench/resources/go_suite/reverse.jsonnet` | 10.969 |
| `bench/resources/go_suite/rstripChars.jsonnet` | 0.604 |
| `bench/resources/go_suite/stripChars.jsonnet` | 0.595 |
| `bench/resources/go_suite/substr.jsonnet` | 0.167 |
| `bench/resources/sjsonnet_suite/setDiff.jsonnet` | 0.460 |
| `bench/resources/sjsonnet_suite/setInter.jsonnet` | 0.414 |
| `bench/resources/sjsonnet_suite/setUnion.jsonnet` | 0.734 |

### Failures / blockers

- No benchmark execution blockers encountered; the preferred full baseline command completed successfully.
- Minor environment note: `java -version` in the shell differs from the JDK actually used by Mill/JMH, so future comparisons should continue to rely on the JMH-reported JVM details.

### Next actions

- Use this report as the baseline for future branch-vs-base comparisons.
- When re-running, compare against the same Mill/JMH/JDK setup or record any environment drift explicitly.
- Investigate the highest-cost cases first (`cpp_suite/realistic2.jsonnet`, `go_suite/comparison2.jsonnet`, `cpp_suite/bench.02.jsonnet`) if optimization work continues.
