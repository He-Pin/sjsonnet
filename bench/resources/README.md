# Benchmarks

Regression suites are executed with:

```bash
./mill bench.listRegressions
./mill bench.runRegressions
./mill bench.runRegressions bench/resources/go_suite/comparison2.jsonnet
```

Refresh benchmark golden outputs with:

```bash
./bench/resources/refresh_golden_outputs.sh
```

For benchmark protocol, workload classes, and branch-vs-base comparison rules, see `bench/BENCHMARK_LADDER.md`.
 
## bug_suite
These contain various examples lifted from sjsonnet bug reports.

## cpp_suite
These are benchmarks copied from the C++ Jsonnet implementation's test suite.
Licensed under Apache 2.0, see https://github.com/google/jsonnet/blob/7d1cbf8e69bc8b28e0405080771ccd4da36ac716/LICENSE

## go_suite
These are benchmarks copied from the Go Jsonnet implementation's test suite.
Licensed under Apache 2.0, see https://github.com/google/go-jsonnet/blob/10aef6a96ca825c97c87df137a837e39f5df174c/LICENSE

## sjsonnet_suite
Benchmarks created by this project.
