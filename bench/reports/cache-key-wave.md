# cache-key wave

## Scope

Investigated one contained JVM optimization wave around parse/import cache concurrency and cache-key fingerprinting, limited to parse-cache/import-related code paths and existing benchmarks.

## Baseline

Commands run before code changes:

```bash
./mill 'sjsonnet.jvm[3.3.7]'.test
./mill bench.runJmh -i 1 -wi 1 -f 1 'sjsonnet.bench.MultiThreadedBenchmark.main'
./mill bench.runJmh -i 1 -wi 1 -f 1 'sjsonnet.bench.MainBenchmark.main'
```

Observed baseline:

| Benchmark | Baseline |
| --- | ---: |
| `MultiThreadedBenchmark.main` | `5.451 ms/op` |
| `MainBenchmark.main` | `3.191 ms/op` |

The JVM test suite passed.

## Attempt 1: safer default JVM parse-cache concurrency

### Change tried

- Replaced `DefaultParseCache`'s mutable `HashMap` with a `ConcurrentHashMap`.
- Updated `bench/src/sjsonnet/bench/MultiThreadedBenchmark.scala` to use `new DefaultParseCache` directly instead of its local synchronized cache shim.

### Result

This was functionally unsafe and was reverted.

The first version used `computeIfAbsent`, which made `MultiThreadedBenchmark` fail during warmup with:

- `sjsonnet.Error: [std.assertEqual] Internal Error`
- `java.lang.ClassCastException: class sjsonnet.Val$Num cannot be cast to class sjsonnet.Val$Func`
- stack rooted in `sjsonnet.LazyApply1.value(Val.scala:112)`

I then tried a narrower hypothesis test using `ConcurrentHashMap#get` + `putIfAbsent` to avoid explicit miss coalescing. `MultiThreadedBenchmark` still failed, this time with:

- `sjsonnet.Error: [std.assertEqual] Internal Error`
- `java.lang.NullPointerException: Cannot invoke "sjsonnet.Eval.value()" because "args[0]" is null`
- stack rooted in `sjsonnet.Val$Builtin1.evalRhs(Val.scala:1079)`

### Interpretation

The concurrent-cache direction is not a safe contained wave right now. The benchmark evidence suggests that publishing a shared optimized parse result during concurrent cold loads is unsafe for the current evaluator/lazy runtime object graph, so a default concurrent parse-cache implementation would need a broader design pass than this task allows.

## Attempt 2: cached parse-cache key fingerprint for static resolved files

### Change tried

- Changed `StaticResolvedFile.contentHash()` from returning the full source string to returning a cached short fingerprint via `Platform.hashString(...)`.

### Validation commands

```bash
./mill 'sjsonnet.jvm[3.3.7]'.test
./mill bench.runJmh -i 1 -wi 1 -f 1 'sjsonnet.bench.MultiThreadedBenchmark.main'
./mill bench.runJmh -i 1 -wi 1 -f 1 'sjsonnet.bench.MainBenchmark.main'
```

### Result

The change was benchmark-negative and was reverted.

| Benchmark | Baseline | Fingerprint attempt | Delta |
| --- | ---: | ---: | ---: |
| `MultiThreadedBenchmark.main` | `5.451 ms/op` | `5.606 ms/op` | `+0.155 ms/op` (`+2.84%`) |
| `MainBenchmark.main` | `3.191 ms/op` | `3.385 ms/op` | `+0.194 ms/op` (`+6.08%`) |

The JVM test suite still passed, but the benchmark signal was not positive enough to keep.

## Conclusion

This wave is rejected and the code was reverted. No production code changes are kept from this task.

I did **not** run `./mill bench.runRegressions` because the process gate for that suite is "before keeping", and neither investigated slice produced a keepable result.
