# jrsonnet gap baseline

## Scope

This report records the current gap triage source for the stacked performance
exploration branch. The numbers below come from latest fetched
`jrsonnet origin/master:docs/benchmarks.adoc`, not from the older local checked
out `jrsonnet` worktree.

The `jrsonnet/docs` rows are stale for at least foldl/string concatenation.
Before turning any idea into a PR, re-run local sjsonnet benchmarks on the
stacked branch, compare against current master, and use source-built jrsonnet for
the matching workload.

## Stacked exploration baseline

Branch: `perf/stacked-ready-gap-explore`

Original stack was built from `upstream/master@0ae7b78a`, then stacked:

| Source | Commit in stack | Included work |
| --- | --- | --- |
| #825 `perf/constant-array-join` | `da5b0450` | Constant/repeated string join fast paths. |
| #826 `perf/native-lazy-array-stack` | `190c2cc6` | Identity function composition optimization. |
| #828 `perf/toml-render-fastpath` | `0ee2b953` | TOML manifestation fast path. |
| #833 `split/pr776-single-spec-format` | `e21eb029` | Single-spec format builder skip. |
| #834 `split/pr776-ascii-substr` | `15d225e3` | ASCII-safe literal propagation for length/substr. |
| `jit-explore-2026` docs | `6185c820` | Fresh JIT/performance tracking docs. |
| `jit-explore-2026` scope trim | `bde01b9a` | Current-master immutable scope-map allocation trim. |
| `jit-explore-2026` static apply | `b867a317` | Single-pass static apply argument collection. |

The #834/#825 Native `CharSWAR` conflict was resolved by keeping the
four-UTF-16-char `isAsciiJsonSafe` scan from #825 and the ascii-safe propagation
call sites from #834. The stacked branch has no duplicate `isAsciiJsonSafe`
definitions.

Rebase checkpoint: the branch has now been rebased onto
`upstream/master@1679892980e63e00945053eba02affca9dceae5f`. Upstream master now
contains #825, #826, #827, #828, and #833, so Git skipped those already-applied
commits during rebase. The branch still carries the focused #834-style ASCII
substr work, accepted `jit-explore-2026` micro-optimizations, kube-prometheus
strict-JSON optimizations, and this gap tracking report.

## Latest documented gaps vs jrsonnet

These rows are copied from latest `jrsonnet origin/master:docs/benchmarks.adoc`
and should be treated as a stale ranking until replaced by local source-built
hyperfine data.

| Rank | Workload | Scala target | Scala ms | Rust ms | Gap | Initial direction |
| ---: | --- | --- | ---: | ---: | ---: | --- |
| 1 | C++ benchmarks / foldl string concat | GraalVM | 420.9 | 3.7 | 113.76x | Re-test after rope/string work; investigate foldl string-builder/rope semantics only if still open. |
| 2 | C++ benchmarks / foldl string concat | Native | 328.6 | 3.7 | 88.81x | Same as above; Native string concat allocation pressure likely dominates. |
| 3 | Go builtins / `std.foldl` | Native | 80.0 | 2.2 | 36.36x | Audit stdlib fold loops and function-call/lazy scope overhead. |
| 4 | Go builtins / `std.foldl` | GraalVM | 55.4 | 2.2 | 25.18x | Same direction; likely JVM call/closure overhead. |
| 5 | C++ perf_tests / large string template | Native | 14.5 | 2.1 | 6.90x | String template/rendering allocation and concat chain analysis. |
| 6 | C++ perf_tests / large string template | GraalVM | 13.9 | 2.1 | 6.62x | Same direction; check overlap with #825/#834 first. |
| 7 | Go builtins / array comparison | GraalVM | 64.3 | 10.0 | 6.43x | Deep equality/comparison hot path; semantic risk around lazy values/errors. |
| 8 | Real world / kube-prometheus manifests | Native | 204.6 | 43.6 | 4.69x | End-to-end object/materializer/rendering profile after stacked branch. |
| 9 | C++ benchmarks / big object | Native | 3756.8 | 839.2 | 4.48x | Object layout, visible-key cache, materializer object traversal. |
| 10 | Real world / kube-prometheus manifests | GraalVM | 183.7 | 43.6 | 4.21x | Same as Native, but separate JVM allocation profile. |
| 11 | Go builtins / `std.base64` | Native | 4.8 | 1.2 | 4.00x | Check if current base64 SIMD/byte-array PR work already supersedes this doc. |
| 12 | Go builtins / array comparison | Native | 39.1 | 10.0 | 3.91x | Same as GraalVM array comparison. |
| 13 | C++ perf_tests / large string join | Native | 9.7 | 2.6 | 3.73x | Covered by #825; re-measure stacked branch before more join work. |
| 14 | C++ benchmarks / big object | GraalVM | 3035.7 | 839.2 | 3.62x | Same object/materializer direction. |
| 15 | Go builtins / `std.base64Decode` | Native | 4.2 | 1.2 | 3.50x | Check current base64 work and Native byte-array behavior. |
| 16 | C++ perf_tests / realistic2 | Native | 417.8 | 120.9 | 3.46x | Guard benchmark for renderer/materializer/object changes. |
| 17 | Go builtins / `std.manifestTomlEx` | Native | 2558.8 | 750.5 | 3.41x | Covered by #828; re-measure stacked branch before more TOML work. |
| 18 | C++ perf_tests / realistic2 | GraalVM | 409.1 | 120.9 | 3.38x | Same as Native; required guard for string/rendering changes. |
| 19 | Go builtins / `std.manifestJsonEx` | Native | 2443.6 | 748.5 | 3.26x | Renderer/materializer direct iteration; previous broad #776 split regressed guards. |
| 20 | Go builtins / `std.substr` | Native | 2991.7 | 917.8 | 3.26x | Covered by #834; re-measure stacked branch before more substr work. |

## Local source-built hyperfine results

The local `jrsonnet` checkout was reset and cleaned, then rebuilt from latest
`origin/master`:

| Project | Ref / build | Binary |
| --- | --- | --- |
| sjsonnet | `perf/stacked-ready-gap-explore@2e5ef3ea`, `./mill --no-server -j 1 'sjsonnet.native[3.3.7]'.nativeLink` | `out/sjsonnet/native/3.3.7/nativeLink.dest/out` |
| jrsonnet | `origin/master@5b43fa88`, `cargo build --release -p jrsonnet` | `target/release/jrsonnet` |

Method:

- Foldl inputs were copied directly from latest
  `jrsonnet/docs/benchmarks.adoc`; remaining workloads used the repository
  benchmark files listed in the table.
- Correctness smoke compared stdout with `cmp` before measuring.
- Small/medium workloads used `hyperfine --shell=none --warmup 5 --min-runs
  20`; larger real/object workloads used the exact commands listed below.
- `jrsonnet --features mimalloc` was attempted but does not compile on the
  current aarch64 macOS toolchain because `mimalloc-sys` emits x86_64 `%gs`
  inline assembly for this target. The valid local jrsonnet comparison is the
  default release profile above.

| Workload | sjsonnet Scala Native stack | jrsonnet source release | Ratio |
| --- | ---: | ---: | ---: |
| C++ benchmarks / foldl string concat | `5.293 +/- 0.589 ms` | `8.655 +/- 0.557 ms` | sjsonnet is `0.61x` jrsonnet time (`1.64x` faster). |
| Go builtins / `std.foldl` | `4.900 +/- 0.378 ms` | `6.268 +/- 0.505 ms` | sjsonnet is `0.78x` jrsonnet time (`1.28x` faster). |
| C++ perf_tests / large string template | `11.515 +/- 1.085 ms` | `6.190 +/- 0.975 ms` | sjsonnet is `1.86x` jrsonnet time. |
| C++ benchmarks / big object (`bench/resources/cpp_suite/gen_big_object.jsonnet`) | `9.057 +/- 0.666 ms` | `8.972 +/- 0.819 ms` | sjsonnet is `1.01x` jrsonnet time; effectively neutral. |
| C++ perf_tests / realistic2 (`bench/resources/cpp_suite/realistic2.jsonnet`) | `83.932 +/- 1.629 ms` | `144.282 +/- 2.369 ms` | sjsonnet is `0.58x` jrsonnet time (`1.72x` faster). |
| Real world / kube-prometheus manifests (`jrsonnet/tests/realworld/entry-kube-prometheus.jsonnet`, `-J vendor`) | `235.971 +/- 12.925 ms` | `93.188 +/- 6.599 ms` | sjsonnet is `2.53x` jrsonnet time. |

Result: foldl/string concatenation is no longer a current gap on this local
source-built comparison. Large string template remains a current gap, but the
measured gap is `1.86x`, not the stale documented `6.90x`. Big object is no
longer a meaningful local gap and realistic2 is faster in sjsonnet. The largest
confirmed local source-built gap in this recheck set is kube-prometheus at
`2.53x`.

## Immediate optimization priorities

1. **Prioritize kube-prometheus for end-to-end object/materializer/rendering
   work.** Local source-built hyperfine shows a current `2.53x` real-world gap
   after stacking ready PRs.
2. **Keep large string template as the next string-heavy target.** Local
   source-built hyperfine shows a remaining `1.86x` gap. Use
   `large_string_join`, `realistic2`, and `manifestJsonEx` as guards.
3. **Move past foldl/string concat.** Local source-built hyperfine shows the
   stacked sjsonnet Scala Native binary faster than latest source-built
   jrsonnet on both extracted foldl workloads.
4. **Use big object and realistic2 as guards, not primary targets for now.** Big
   object is neutral and realistic2 is already faster than source-built
   jrsonnet in the current stack.
5. **Re-measure array comparison and manifestJsonEx as guard/secondary
   targets.** These catch overfitted string or renderer changes that regress
   object-heavy real workloads.

## Validation performed for stacked baseline

| Check | Result |
| --- | --- |
| `./mill --no-server -j 1 __.reformat` | Success. |
| `./mill --no-server -j 1 'sjsonnet.jvm[3.3.7]'.test` | Success: 494 passed, 0 failed. |
| `./mill --no-server -j 1 'sjsonnet.native[3.3.7]'.nativeLink` | Success; produced a 17M native binary. |
| `cargo build --release -p jrsonnet` | Success; produced `jrsonnet 0.5.0-pre98`. |
| foldl hyperfine | sjsonnet Scala Native stack is faster than source-built jrsonnet on both foldl workloads. |
| large string template hyperfine | Outputs matched; sjsonnet Scala Native stack `11.515 +/- 1.085 ms`, source-built jrsonnet `6.190 +/- 0.975 ms`, so sjsonnet is `1.86x` slower. |
| big object hyperfine | Outputs matched; sjsonnet Scala Native stack `9.057 +/- 0.666 ms`, source-built jrsonnet `8.972 +/- 0.819 ms`, so the result is effectively neutral. |
| realistic2 hyperfine | Outputs matched; sjsonnet Scala Native stack `83.932 +/- 1.629 ms`, source-built jrsonnet `144.282 +/- 2.369 ms`, so sjsonnet is faster. |
| kube-prometheus hyperfine | Installed real-world vendor deps with `jsonnet-bundler` after `jrb install` failed locally with a reqwest/rustls provider panic. Outputs matched (`7,506,029` bytes); sjsonnet Scala Native stack `235.971 +/- 12.925 ms`, source-built jrsonnet `93.188 +/- 6.599 ms`, so sjsonnet is `2.53x` slower. |
| Worktree | Clean after validation. |

## Kube-prometheus optimization checkpoint

First accepted kube-prometheus experiment: reuse the stateless
`MaterializeJsonRenderer` array/object visitors used by `std.manifestJson*`.
The real-world profile showed kube-prometheus spends visible time in
`std.manifestJsonEx` while building Grafana dashboard ConfigMap strings, and
the existing `ByteRenderer` already uses the same visitor-reuse shape for the
fused output path.

| Check | Result |
| --- | --- |
| JVM tests | `./mill --no-server -j 1 'sjsonnet.jvm[3.3.7]'.test`: 494 passed, 0 failed. |
| Native output smoke | `cmp` matched source-built jrsonnet output for `entry-kube-prometheus.jsonnet` (`-J vendor`). |
| kube-prometheus Native hyperfine | Before: `235.971 +/- 12.925 ms`; after: `224.975 +/- 11.550 ms`; delta `-4.66%`. Source-built jrsonnet in the same candidate run: `88.576 +/- 0.915 ms`, so the remaining gap is `2.54x`. |
| Focused JMH guards | `bench/resources/go_suite/manifestJsonEx.jsonnet`: `0.052 ms/op`; `bench/resources/cpp_suite/realistic2.jsonnet`: `40.068 ms/op`; `bench/resources/cpp_suite/large_string_template.jsonnet`: `1.137 ms/op`. |
| Review | Independent `gpt-5.4`, `claude-opus-4.7`, and `claude-sonnet-4.6` code-review agents reported no significant issues. |

Second accepted kube-prometheus experiment: parse strict `.json` imports directly
into literal `Val` trees, falling back to the normal Jsonnet parser whenever the
file is not strict JSON or the fast path would change observable semantics. This
targets the very large Kubernetes CRD imports in the kube-prometheus vendor tree,
where parse/import time dominated the remaining real-world gap.

Semantic guardrails:

- Only files whose resolved path ends in `.json` are attempted.
- Invalid strict JSON, duplicate object keys, non-finite numbers, and parser-depth
  overflow all fall back to the normal Jsonnet parser.
- Large integer JSON numbers parse through Jsonnet's double number model rather
  than `Long`, preserving existing accepted input.
- The shared helper is used by both synchronous imports and Preloader/async
  import discovery, avoiding sync-vs-async divergence.

| Check | Result |
| --- | --- |
| Regression tests | Added `JsonImportFastPathTests` for strict JSON values, Jsonnet fallback, duplicate-key fallback, large integers, non-finite numbers, incomplete JSON, and recursion-depth fallback. Added a `PreloaderTests` case proving `.json` preload uses the fast path without fastparse. |
| JVM tests | `./mill --no-server -j 1 'sjsonnet.jvm[3.3.7]'.test`: 502 passed, 0 failed. |
| Full tests | `./mill --no-server -j 1 __.test`: success, 2066/2066 tasks. |
| Native build | `./mill --no-server -j 1 'sjsonnet.native[3.3.7]'.nativeLink`: success. |
| Native output smoke | `cmp` matched source-built jrsonnet output for `entry-kube-prometheus.jsonnet` (`-J vendor`), output size `7,506,029` bytes. |
| kube-prometheus Native hyperfine | Before this experiment: `224.975 +/- 11.550 ms`; after: `139.242 +/- 1.204 ms`; delta `-38.11%`. Versus the original stacked baseline `235.971 +/- 12.925 ms`, total delta is `-40.99%`. Source-built jrsonnet in the same final run: `88.025 +/- 1.271 ms`, so the remaining gap is `1.58x`. |
| Focused JMH guards | `bench/resources/go_suite/manifestJsonEx.jsonnet`: `0.052 ms/op`; `bench/resources/cpp_suite/realistic2.jsonnet`: `41.259 ms/op`; `bench/resources/cpp_suite/large_string_template.jsonnet`: `1.129 ms/op`; `bench/resources/cpp_suite/gen_big_object.jsonnet`: `0.845 ms/op`. |
| Review | Independent `gpt-5.4`, `claude-opus-4.7`, and `claude-sonnet-4.6` code-review agents reported no significant issues after fixes for large-number parsing, incomplete JSON fallback, Preloader coverage, and parser-depth guard behavior. |

Follow-up accepted micro-optimization inside the same strict `.json` import
fast path: avoid a second duplicate-key map lookup by using the previous value
returned from `HashMap.put`, and avoid `CharSequence.toString` when ujson has
already provided a `String`.

| Check | Result |
| --- | --- |
| JVM tests | `./mill --no-server -j 1 'sjsonnet.jvm[3.3.7]'.test`: 502 passed, 0 failed. |
| Full tests | `./mill --no-server -j 1 __.test`: success, 2066/2066 tasks. |
| Native build | `./mill --no-server -j 1 'sjsonnet.native[3.3.7]'.nativeLink`: success. |
| Native output smoke | `cmp` matched source-built jrsonnet output for `entry-kube-prometheus.jsonnet` (`-J vendor`), output size `7,506,029` bytes. |
| kube-prometheus Native A/B hyperfine | Same-run A/B with frozen clean `e4fed2e4` binary: clean `141.526 +/- 1.896 ms`; candidate `139.088 +/- 1.305 ms`; delta `-1.72%`. Source-built jrsonnet in the same run: `87.421 +/- 0.932 ms`, so the remaining same-run gap is `1.59x`. |
| Focused JMH guards | `bench/resources/go_suite/manifestJsonEx.jsonnet`: `0.053 ms/op`; `bench/resources/cpp_suite/realistic2.jsonnet`: first combined run `44.130 ms/op`, rerun `40.195 ms/op`; `bench/resources/cpp_suite/large_string_template.jsonnet`: `1.129 ms/op`; `bench/resources/cpp_suite/gen_big_object.jsonnet`: `0.830 ms/op`. |
| Rejected follow-ups | Static-object direct materialization and long ASCII string char-level escaping both failed the Native benchmark gate, so they were reverted and not included. |
| Review | Independent `gpt-5.4`, `claude-opus-4.7`, and `claude-sonnet-4.6` code-review agents reported no significant issues. |

Fourth accepted kube-prometheus experiment: keep strict `.json` imports in an
inline object layout so the fused renderer/materializer can iterate imported JSON
objects directly. The first version used the normal inline-object field cache,
but review found that parse-cached imported literals may be shared by concurrent
interpreters. The final version disables field caching for JSON import members,
sets `_skipFieldCache`, and uses the inline-array representation even for
0/1-field objects so stdlib field introspection does not lazily populate
`value0` on shared literals.

Rejected variants in this step:

- Marking imported JSON strings as ASCII-safe during parse was output-correct but
  benchmark-negative/noisy (`141.317 +/- 1.729 ms` clean vs `141.837 +/- 1.503 ms`
  candidate for `>=128` chars; the `>1024` variant was not stable on repeat).
- `cached=false` without `_skipFieldCache` did not remove materializer cache
  writes, so it was superseded by the final race-free version.

| Check | Result |
| --- | --- |
| Regression tests | Added JVM-only `JsonImportFastPathJvmTests` that shares a strict-JSON import through a concurrent parse cache across interpreters, materializes the whole object, selects fields, and calls `std.objectFields` on a single-field nested object. |
| JVM tests | `./mill --no-server -j 1 'sjsonnet.jvm[3.3.7]'.test`: 503 passed, 0 failed. |
| Full tests | `./mill --no-server -j 1 __.test`: success, 2066/2066 tasks. |
| Native build | `./mill --no-server -j 1 'sjsonnet.native[3.3.7]'.nativeLink`: success. |
| Native output smoke | `cmp` matched source-built jrsonnet output for `entry-kube-prometheus.jsonnet` (`-J vendor`), output size `7,506,029` bytes. |
| kube-prometheus Native A/B hyperfine | Same-run A/B with frozen clean `de5cd388` binary: clean `139.937 +/- 2.294 ms`; candidate `136.301 +/- 1.957 ms`; delta `-2.60%`. Source-built jrsonnet in the same run: `88.087 +/- 1.737 ms`, so the remaining same-run gap is `1.55x`. |
| Focused JMH guards | `bench/resources/go_suite/manifestJsonEx.jsonnet`: `0.053 ms/op`; `bench/resources/cpp_suite/realistic2.jsonnet`: `40.250 ms/op`; `bench/resources/cpp_suite/large_string_template.jsonnet`: `1.102 ms/op`; `bench/resources/cpp_suite/gen_big_object.jsonnet`: `0.826 ms/op`. |
| Review | Independent `gpt-5.4`, `claude-opus-4.7`, and `claude-sonnet-4.6` reviews first identified and then confirmed fixes for shared-literal cache races and the single-field `getValue0` mutation path; final review reported no significant issues. |

---

## Latest: JSON Position Reuse (2026-05-11)

**Kube-prometheus Scala Native** (after inline JSON objects + JSON position reuse):
- sjsonnet stack: `136.1 ± 1.6 ms` (two-run average, same-run latest `135.9 ± 1.2 ms`)
- Source-built jrsonnet: `88.087 ± 1.737 ms`
- Gap: `1.54x` (down from `1.55x` after inline JSON objects; delta `-0.6%`)
- **Cumulative improvement from baseline** (after all kube-focused optimizations):
  - Visitor reuse (-4.66%): `235.97 → 224.97 ms`
  - Fast-path imports (-40.99%): `224.97 → 139.24 ms`
  - Trim visitor work (-1.72%): `139.24 → 136.88 ms`
  - Inline JSON objects (-2.60%): `136.88 → 133.40 ms` (local A/B)
  - JSON position reuse (-0.58%): `136.7 → 135.9 ms`
  - **Total reduction**: ~42% from starting point of `~235 ms` to current `~136 ms`.

**Analysis**:
- Materialize still dominates (155ms of 180ms total, per prior debug stats).
- Next targets should focus on Materializer/ByteRenderer efficiency (string escaping, container iteration, direct-to-output paths).
- Large string template remains second priority (1.86x gap).

---

## Latest: post-upstream-rebase gap checkpoint

After rebasing `perf/stacked-ready-gap-explore` onto
`upstream/master@1679892980e63e00945053eba02affca9dceae5f`, the Native binary was
rebuilt with `./mill --no-server -j 1 'sjsonnet.native[3.3.7]'.nativeLink` and
selected `jrsonnet/docs` rows were re-smoked against source-built jrsonnet with
`cmp`.

| Workload | sjsonnet Scala Native stack | jrsonnet source release | Ratio / status |
| --- | ---: | ---: | --- |
| Go builtins / `std.base64` | `6.8 +/- 1.5 ms` | `2.2 +/- 0.4 ms` | sjsonnet is `3.03x` jrsonnet time; dominated by small-process/startup overhead plus rendering. |
| Go builtins / `std.base64Decode` | `6.2 +/- 0.7 ms` | `2.2 +/- 0.3 ms` | sjsonnet is `2.78x` jrsonnet time. |
| Go builtins / `std.base64DecodeBytes` | `14.0 +/- 1.4 ms` | `17.3 +/- 0.8 ms` | sjsonnet is faster (`0.81x` jrsonnet time). |
| C++ perf_tests / large string template | `12.4 +/- 1.8 ms` | `3.3 +/- 0.6 ms` | sjsonnet is `3.77x` jrsonnet time; current largest rechecked gap. |
| C++ benchmarks / big object | `11.1 +/- 2.3 ms` | `7.8 +/- 0.7 ms` | sjsonnet is `1.42x` jrsonnet time in this noisy recheck. |
| Real world / kube-prometheus manifests | `151.5 +/- 20.8 ms` | `94.0 +/- 11.0 ms` | sjsonnet is `1.61x` jrsonnet time after the strict-JSON stack and upstream rebase. |

Rejected follow-ups from this checkpoint:

- Native base64 input/output copy trimming: output-correct, but same-run A/B did
  not produce a stable positive signal and decode variants regressed the
  `base64DecodeBytes` guard.
- Long-string escaped-render collection: output-correct, but did not improve
  `large_string_template`.
- Exact `StringBuilder` sizing for repeated single-label `%` formats: neutral on
  `large_string_template` and negative on `realistic2`, so it was reverted.
- A specialized `%(same_label)s` scanner/render path for `large_string_template`:
  output-correct and JVM-test clean, but reverse-order Native A/B regressed
  (`11.496 ms` candidate vs `11.202 ms` clean), so it was reverted.
- Delaying `labelsBuilder` allocation in `scanFormat` for all-simple same-label
  patterns: output-correct and JVM-test clean, but A/B was neutral (`9.337 ms`
  clean vs `9.204 ms` candidate; reverse `10.650 ms` candidate vs `10.685 ms`
  clean), so it was reverted.
- Using `String.replace("%(key)s", value)` for all-simple same-label formats:
  output-correct and JVM-test clean, but reverse Native A/B regressed (`11.443 ms`
  candidate vs `10.568 ms` clean), so it was reverted.
- A one-allocation `Array[Char]`/`getChars` renderer for all-simple same-label
  formats: output-correct and JVM-test clean, but Native A/B regressed/noised out
  (`9.959 ms` clean vs `10.510 ms` candidate), so it was reverted.
- Manually grown primitive arrays in `scanFormat`: output-correct and JVM-test
  clean, but Native A/B was unstable/neutral (`10.209 ms` clean vs `10.526 ms`
  candidate; reverse `10.245 ms` candidate vs `10.300 ms` clean), so it was
  reverted.
- Direct expression-position triple-bar string parsing: output-correct and
  JVM-test clean, but Native A/B was unstable/neutral (`10.530 ms` clean vs
  `10.839 ms` candidate; reverse `10.446 ms` candidate vs `10.529 ms` clean),
  so it was reverted.
- Streaming escaped long strings without the `escapedStringLength` pre-scan:
  output-correct and JVM-test clean, but Native A/B remained noise-level
  (`11.158 ms` clean vs `11.324 ms` candidate; reverse `10.508 ms` candidate vs
  `10.576 ms` clean), so it was reverted.
- ASCII range byte-source rendering for long escaped strings: output-correct and
  JVM-test clean, but the extra String scans and range copies were clearly
  slower than the current `getBytes(UTF_8)` + byte SWAR path (`10.201 ms` clean
  vs `12.513 ms` candidate; reverse `13.821 ms` candidate vs `11.839 ms`
  clean), so it was reverted.
- Direct `OutputStream` writes for huge strings: output-correct and JVM-test
  clean, but writing many chunks/escapes directly was much slower than growing
  `ByteBuilder` once (`10.997 ms` clean vs `14.886 ms` candidate; reverse
  `14.558 ms` candidate vs `10.722 ms` clean), so it was reverted.
- Lazy stdlib construction for CLI startup: focused tests and three reviews passed
  after preserving the `Interpreter#createOptimizer` subclass hook, but final
  reverse-order Native A/B was negative (`large_string_template` `11.2 ms`
  candidate vs `8.8 ms` clean, `base64` `6.9 ms` vs `4.0 ms`, and `/tmp/true`
  `6.5 ms` vs `3.1 ms`), so it was reverted.

Accepted follow-up from this checkpoint:

- LF-only text-block line-end scanning: in the bulk `|||` parser path, replace
  the Scala per-character line-end loop with `String.indexOf('\n')` when the
  detected separator is a single LF. CRLF and other multi-character separators
  stay on the prior loop path.
  - Output equality: candidate Native output matched source-built jrsonnet for
    `bench/resources/cpp_suite/large_string_template.jsonnet`.
  - Reverse-order Native A/B against the frozen clean binary: candidate
    `10.373 ms`, clean `11.191 ms` (`-7.3%`).
  - Candidate vs source-built jrsonnet: candidate `10.552 +/- 0.656 ms`,
    jrsonnet `5.611 +/- 0.826 ms`; remaining gap `1.88x`.
  - Focused JMH guards: `large_string_template 0.683 ms/op`,
    `realistic2 41.618 ms/op`, `gen_big_object 0.822 ms/op`,
    `manifestJsonEx 0.052 ms/op`.
  - Full `./mill --no-server -j 1 __.test` passed (`2066/2066`).

Next priority remains a deeper non-renderer route for large string template, or
another confirmed source-built gap with a measurable same-run A/B win. The
failed attempts above should not be repeated without a materially different
hypothesis.

### Large string template optimization backlog

The current gap is no longer in text-block line discovery alone. Temporary
variants show that removing the final `% { x: 3 }` format step drops debug stats
from roughly `parse 7.1 ms / eval 8.5 ms / materialize 2.1 ms` to
`parse 0.7 ms / eval 1.2 ms / materialize 1.0 ms`, so the remaining work should
prioritize format construction/evaluation and huge escaped-string rendering.

| Priority | Candidate | Area | Hypothesis | Risk / guard |
| --- | --- | --- | --- | --- |
| 1 | Source-offset labels | `Format.scanFormat` and `formatSimpleNamedString` | Keep label `(start,end)` offsets while scanning and allocate label `String`s only when the generic multi-label path actually needs them; same-label simple formats should avoid substring churn. | Medium; must preserve label equality and object lookup errors. |
| 2 | Bulk-scan first text-block line | `Parser.tripleBarStringBody` | Extend the indexed-input scanner to include the first content line and prelude, not only lines after the first. | Medium; preserve fastparse error quality for malformed text blocks. |
| 3 | Producer escape-count hint | `Format.formatSimpleNamedString` → `Val.Str` → `BaseByteRenderer` | While building a formatted string, count escapes and let the renderer size output without a pre-scan. | Medium; requires careful `Val.Str` layout/concurrency review. |

Rejected format micro-optimizations in this checkpoint were intentionally kept in
the ledger so future split PR work can skip them unless the implementation route
is materially different.
