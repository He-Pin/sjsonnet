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

---

## Optimization Campaign Results (Waves 50–79)

### Session Summary
- **25 commits** on branch `jit`
- **All 59 tests pass**, all 35 benchmark regressions pass
- Commits: `fc06d63c`..`29c920a2`

### Key Benchmark Improvements (before → after)

| Benchmark | Baseline | Current | Δ |
|-----------|----------|---------|---|
| bench.02 (object Fib) | ~51 ms | **36.8 ms** | **-28%** |
| comparison2 (1M comp) | ~28 ms | **17.9 ms** | **-36%** |
| realistic2 (config gen) | ~58 ms | **55.5 ms** | -4% |
| bench.04 | ~0.58 ms | **0.48 ms** | -17% |
| bench.06 | ~0.30 ms | **0.24 ms** | -20% |
| base64 | ~0.60 ms | **0.52 ms** | -13% |
| base64Decode | ~0.49 ms | **0.40 ms** | -18% |

### Committed Waves

| Wave | Commit | Description | Impact |
|------|--------|-------------|--------|
| 50 | `fc06d63c` | Parser number fast path, Materializer.stringify | Neutral |
| 51 | `c3642100` | OP_+ and OP_% nested match (avoid Tuple2) | Neutral |
| 52 | `90a5c075` | visitLookup and OP_in nested match | Neutral |
| 53 | `a28445e6` | Renderer commaIndent cache | Neutral |
| 54 | `c4ecbe9e` | Val.Arr System.arraycopy concat | Neutral |
| 55 | `e2ec0abc` | Format direct Val dispatch, Long fast path | -4% realistic2 |
| 56 | `4393b612` | ObjComp allLocals, sum/avg while-loop, remove arraycopy | -2.5% bench.03 |
| 57 | `bce90804` | visibleKeyNames forEach → while-loop | Neutral |
| 59 | `9ec24403` | minArray/maxArray zipWithIndex elimination | Neutral |
| 60 | `dbb8bcb9` | base64DecodeBytes while-loop, sum/avg single pass | -1.3% base64 |
| 61 | `49f22e1b` | DecimalFormat StringBuilder | Neutral |
| 62 | `f66bb4a3` | DecodeUTF8 single-pass, SplitLimitR reverse | Neutral |
| 63 | `154b3cd4` | ObjectModule .map() → while-loops | Neutral |
| 64 | `2f191610` | prune single-pass, Lines StringBuilder | Neutral |
| 65 | `9ca5a081` | SetModule sortArr reuse arg buffer | Neutral |
| 66 | `3d4ce63f` | manifestIni/manifestPythonVars StringBuilder | Neutral |
| 67 | `db54a728` | OP_+ fast-path Num stringify | Neutral |
| 68 | `cb0fd37c` | manifestYamlStream StringBuilder, std.any loop | Neutral |
| 71 | `8a16c106` | std.count, escapeStringXml while-loops | Neutral |
| 70 | `dfc7ae1b` | Parser single-pass member classification | Neutral |
| 72 | `5138711a` | compareStringsByCodepoint fast path, std.all/member/contains/repeat | Neutral |
| 74 | `1631a268` | LazyDefault class + lazy valueCache | Neutral |
| 76 | `5b0fdca4` | Tailstrict foreach, visitArr/visitComp/visitImportBin loops | Neutral |
| 79 | `b802d6b6` | **Comprehension inline BinaryOp dispatch** | **-36% comparison2** |
| 78 | `29c920a2` | **Single-field object avoid LinkedHashMap** | **-28% bench.02** |

### Rejected/Blocked Waves
- visitArr while-loop → REGRESSES bench.02 +4.5% (ArrayOps.map JIT-optimized)
- Materializer subVisitor hoist → BLOCKED (PrettyYamlRenderer/TomlRenderer side effects)
- NewEvaluator tag-dispatch → +59% slower bench.03
- Val.Num small integer cache → thread-unsafe (mutable pos)
- Scope reuse for function calls → UNSAFE (LazyExpr captures scope references)

### Current Full Suite (post Wave 79)

| Benchmark | ms/op |
|-----------|-------|
| assertions | 0.224 |
| bench.01 | 0.079 |
| **bench.02** | **36.809** |
| bench.03 | 13.845 |
| bench.04 | 0.476 |
| bench.06 | 0.239 |
| bench.07 | 3.147 |
| bench.08 | 0.062 |
| bench.09 | 0.069 |
| gen_big_object | 1.070 |
| large_string_join | 2.212 |
| large_string_template | 2.268 |
| realistic1 | 1.937 |
| realistic2 | 55.543 |
| base64 | 0.520 |
| base64Decode | 0.401 |
| base64DecodeBytes | 7.719 |
| base64_byte_array | 1.146 |
| comparison | 20.270 |
| **comparison2** | **17.941** |
| escapeStringJson | 0.055 |
| foldl | 0.281 |
| lstripChars | 0.453 |
| manifestJsonEx | 0.073 |
| manifestTomlEx | 0.088 |
| manifestYamlDoc | 0.076 |
| member | 0.727 |
| parseInt | 0.055 |
| reverse | 8.327 |
| rstripChars | 0.439 |
| stripChars | 0.435 |
| substr | 0.126 |
| setDiff | 0.502 |
| setInter | 0.444 |
| setUnion | 0.700 |

### Next actions
- Continue optimizing realistic2 (55.5ms — config generation with format/object creation)
- Explore comparison benchmark (20.3ms — element-wise array comparison)
- Investigate bench.03 (13.8ms — function call overhead)
- Consider deeper structural changes: scope pooling, object layout caching
