# Performance sync points

This file is the ledger for performance migration and exploration work. Keep it
updated before porting, skipping, or re-testing optimization commits so the same
idea is not repeated without new evidence.

## Active baselines

| Area | Ref | Notes |
| --- | --- | --- |
| Upstream master | `ca61a7a3e3e35e3efe459453e3cb7e09eac9c0a7` | Latest `databricks/sjsonnet` master fetched for this branch. Includes merged #825, #826, #827, #828, #833, #837, #838, #839, #840, and #841. |
| Historical JIT branch | `hepinssh/jit@9dc20016b0e2d1a061d1c0451ed555dcc46a0a33` | Source material only; do not mechanically rebase wholesale. |
| Fresh exploration branch | `jit-explore-2026` | Clean branch from upstream master for benchmark-gated, atomic ports. |
| Ready-PR stacked branch | `perf/stacked-ready-gap-explore` | Rebased onto latest upstream master. The branch now carries #834-style ASCII substr work, accepted `jit-explore-2026` micro-optimizations, kube-prometheus strict-JSON optimizations, and gap tracking docs. |
| jrsonnet benchmark source | `jrsonnet origin/master:docs/benchmarks.adoc` | Use fetched `origin/master` contents, not the dirty/stale local jrsonnet checkout. Current gap triage is in `bench/reports/jrsonnet-gap-baseline-2026-05-10.md`. |

## Branch strategy decision

The historical `jit` branch is 104 commits ahead of upstream master and contains
many old experiments that now conflict with newer master-side optimizations. A
mechanical rebase was attempted and stopped at the second commit because it would
force large rewrites of `StaticOptimizer`, `ExprTransform`, `Evaluator`, and
`Val` before proving that the old shape still helps current master.

Decision: keep `hepinssh/jit` as a reference branch, then port or reimplement
only ideas that pass current semantic and benchmark gates.

## Commit migration ledger

| Upstream/source commit | Decision | Reason / next action |
| --- | --- | --- |
| `04b7ff60` `chore: reduce allocation of Num` | Skipped | Superseded by current master `Val.cachedNum` and related no-tuple/static-singleton work. Reapplying the old diff risks undoing newer allocation fixes. |
| `6269dfe2` `refactor static optimizer dispatch` | Deferred | High-conflict historical refactor. Revisit only as a fresh, benchmark-backed `StaticOptimizer` micro-change, not as a full-file port. |
| `068afa11` `add tailrec profiling checkpoint` | Deferred | Diagnostic-only idea. Keep out of production PRs unless converted into a report or benchmark harness improvement. |
| `bfced4ec` `Optimize StaticOptimizer scope map construction` | Ported as current-master variant | Reimplemented the low-risk allocation part in `ScopedExprTransform`: replace `zipWithIndex.map`, tuple creation, and intermediate merge arrays with while-loop `HashMap.updated` construction. Kept `Scope` immutable instead of porting the mutable shell. |
| `f5959f27` `Reuse StaticOptimizer binding scope shell` | Partially skipped | The mutable `Scope.mappings` shell was intentionally not ported after review; it saves only wrapper allocations and weakens the invariant. |
| `f9337010` `Cache parsed dynamic percent formats` | Candidate / verify duplicate | Check current master and open PRs before porting; avoid duplicate `%` format caching. |
| `1e84155c` `Cache sorted object key arrays lazily` | Candidate / verify duplicate | Check overlap with current renderer/materializer key-order work and semantic effects on object visibility. |
| `e98cd1f8` `Optimize format chunk runtime` | Candidate / high scrutiny | Prior #776 format follow-up was not benchmark-positive on current master; only revisit with a materially different micro-benchmark signal. |
| `8b87e03a` `Specialize static object layouts` | Candidate / high risk | Potentially high value but touches object semantics; requires strong regression tests and review. |
| `6367df6d` `Optimize StaticOptimizer apply specialization` | Partially ported | Retained only the stable `tryStaticApply` single-pass `Array[Val]` construction. Tested and rejected the direct `rebindApply` shortcut because repeated JMH was noisy/negative. |
| `926a6d0f` `Optimize sort to use in-place Arrays.sort` | Candidate | Re-test with correctness coverage for Jsonnet ordering and stability expectations. |
| `49a1d51b` `Optimize assertEqual to use structural equality` | Candidate | Requires exact compatibility checks for error messages and deep equality behavior. |
| `ee740182` `Optimize boolean allocation with singleton Val.True/Val.False` | Verify duplicate | Current master already uses static boolean values; likely superseded. |
| `c4ee6be7` `Optimize std.range allocation and cleanup singletons` | Candidate | Compare against current range implementation and Native/JS constraints. |
| `4fa535fb` `Optimize stdlib allocation: foldl while-loop, join pre-sized, flatten two-pass, reverse direct` | Partially superseded | Join/string pieces overlap existing PRs; remaining stdlib loops require per-function benchmark gates. |
| `2d3e56d8` `Optimize foldl string concatenation with StringBuilder` | Verify duplicate | Rope/string-join work may already cover the biggest foldl gap; re-test before porting. |
| `b4b2da5e` `Optimize Materializer: direct array iteration for static objects` | Candidate / high scrutiny | Prior renderer/materializer splits produced regressions; isolate direct iteration only. |
| `d63ce904` `Optimize Renderer: write integer digits directly without String allocation` | Candidate | Re-test against current renderer and direct long-to-chars follow-ups. |
| `1d72a474` `Optimize string rendering: fast path for escape-free strings` | Partially superseded | Current split work already explored escape-safe paths; revisit only with new guard-clean data. |
| Current kube-prometheus profile: `MaterializeJsonRenderer` visitor allocation | Ported as current-stack micro-optimization | Reused stateless `MaterializeJsonRenderer` array/object visitors, following the existing `ByteRenderer` shape. Kube-prometheus Native improved `235.971 +/- 12.925 ms` to `224.975 +/- 11.550 ms` (`-4.66%`) with output equality and no reviewer objections. |
| Current kube-prometheus profile: strict `.json` CRD import parse cost | Ported as current-stack micro-optimization | Added a strict `.json` import fast path that builds literal `Val` trees and falls back to the Jsonnet parser for malformed JSON, duplicate keys, non-finite numbers, recursion-depth overflow, and other semantic edge cases. Kube-prometheus Native improved from `224.975 +/- 11.550 ms` to `139.242 +/- 1.204 ms` (`-38.11%` for this step, `-40.99%` vs original stacked baseline), leaving a `1.58x` source-built jrsonnet gap. |
| Current kube-prometheus profile: strict `.json` visitor micro-cost | Ported as current-stack micro-optimization | In the accepted strict-JSON import path, use `HashMap.put`'s previous-value result for duplicate-key detection and avoid `CharSequence.toString` when the parser already provides a `String`. Same-run Native A/B with frozen clean `e4fed2e4`: `141.526 +/- 1.896 ms` to `139.088 +/- 1.305 ms` (`-1.72%`), with output equality and three independent reviews. |
| Current kube-prometheus profile: strict `.json` object materialization | Ported as current-stack micro-optimization | Build strict-JSON import objects in a race-free inline-array layout so renderer/materializer direct iteration can bypass `visibleKeyNames` + `value()` lookup. Disabled `ConstMember` caching and `_skipFieldCache` for parse-cache shared literals, and avoided the single-field object form after review found a lazy `getValue0` mutation path. Same-run Native A/B with frozen clean `de5cd388`: `139.937 +/- 2.294 ms` to `136.301 +/- 1.957 ms` (`-2.60%`), with output equality, full tests, JVM concurrency coverage, focused JMH guards, and three final reviews. |
| Current kube-prometheus follow-up: static object direct materialization | Rejected | Tried both generic `Materializer` and fused `ByteRenderer` static-object direct value lookup. Both preserved output but failed the Native benchmark gate (`140.850 ms` and `144.815 ms` in their runs), so the changes were reverted. |
| Current kube-prometheus follow-up: long ASCII string char-level escaping | Rejected | Tried avoiding UTF-8 byte-array allocation for long ASCII output strings. The first variant changed Unicode output; the fixed variant restored equality but regressed Native kube-prometheus to `147.900 ms`, so it was reverted. |
| Current kube-prometheus follow-up: imported JSON ASCII-safe string marking | Rejected | Marking imported JSON strings as ASCII-safe during parse was output-correct but did not pass the Native benchmark gate. The `>=128` char variant regressed (`141.317 +/- 1.729 ms` clean vs `141.837 +/- 1.503 ms` candidate); the `>1024` char variant was not stable on repeat. |
| Current base64 follow-up: Native input/output copy trimming | Rejected | Tried passing Scala Native byte arrays directly into aklomp/base64 and constructing encode output with `fromCString`. Output matched source-built jrsonnet and `Base64Tests` passed, but same-run A/B was not stable-positive: encode-only repeat was `6.7 +/- 0.6 ms` clean vs `6.8 +/- 0.6 ms` candidate, while decode variants either stayed neutral or regressed `base64DecodeBytes`. |
| Current long-string renderer follow-up: collected escape positions | Rejected | Tried collecting escape offsets for 4KB+ strings to avoid the second SWAR scan in escaped long-string rendering. Output matched, but large string template stayed neutral/slightly negative (`12.3 ms` candidate in the focused run), so the change was reverted. |
| Current format follow-up: exact capacity for repeated single-label `%` formats | Rejected | Tried sizing `formatSimpleNamedString` with the exact repeated value length for workloads like `large_string_template`. Repeat A/B did not clear the gate: large template was neutral (`13.0 +/- 1.5 ms` clean vs `12.9 +/- 1.6 ms` candidate) and `realistic2` regressed (`91.0 +/- 5.8 ms` clean vs `94.1 +/- 6.5 ms` candidate), so the change was reverted. |
| Current format follow-up: single-label simple format scanner | Rejected | Tried a specialized `%(same_label)s` scanner/render path to bypass generic `RuntimeFormat` construction for `large_string_template`. JVM tests passed and output matched, but reverse-order Native A/B failed the gate: candidate `11.496 ms` vs clean `11.202 ms` (`+2.6%`), so the runtime/test changes were reverted. |
| Current large-template follow-up: LF text-block line-end scan | Ported as current-stack micro-optimization | In the bulk `|||` text-block parser, use `String.indexOf('\n')` for single-character LF separators instead of a Scala char loop, leaving CRLF/multi-character separators on the old path. Reverse-order Native A/B: clean `11.191 ms`, candidate `10.373 ms` (`-7.3%`); candidate vs source-built jrsonnet: `10.552 ms` vs `5.611 ms` (`1.88x` gap). Full `__.test` passed and focused JMH guards were clean. |
| Current format follow-up: defer labelsBuilder for same-label formats | Rejected | Tried delaying `labelsBuilder` allocation in `scanFormat` for all-simple same-label patterns so `large_string_template` would avoid 256 label-list writes. Output matched and JVM tests passed, but A/B was neutral (`9.337 ms` clean vs `9.204 ms` candidate; reverse `10.650 ms` candidate vs `10.685 ms` clean), so the code change was reverted. |
| Current format follow-up: single-label `String.replace` renderer | Rejected | Tried using `source.replace(\"%(key)s\", value)` after `scanFormat` proved all specs were simple same-label string substitutions. Output matched and JVM tests passed, but reverse Native A/B regressed (`11.443 ms` candidate vs `10.568 ms` clean, `+8.3%`), so the code change was reverted. |
| Current format follow-up: char-array single-label renderer | Rejected | Tried a one-allocation `Array[Char]`/`getChars` renderer for all-simple same-label format strings, distinct from `String.replace` and exact-capacity `StringBuilder`. Output matched and JVM tests passed, but Native A/B regressed/noised out (`9.959 ms` clean vs `10.510 ms` candidate, `+5.5%`), so the code change was reverted. |
| Current format follow-up: primitive arrays in `scanFormat` | Rejected | Replaced `ArrayBuilder.ofLong`/`ArrayBuilder.ofInt` with manually grown primitive arrays and one trim. Output matched and JVM tests passed, but Native A/B did not clear the gate: forward `10.209 ms` clean vs `10.526 ms` candidate (`+3.1%`), reverse `10.245 ms` candidate vs `10.300 ms` clean (`-0.5%`, noisy). The code change was reverted. |
| Current parser follow-up: direct triple-bar string path | Rejected | Tried parsing expression-position `|||` text blocks directly to `String` instead of routing through `Seq[String]` wrappers. Output matched and JVM tests passed, but Native A/B was not stable-positive: forward `10.530 ms` clean vs `10.839 ms` candidate (`+2.9%`), reverse `10.446 ms` candidate vs `10.529 ms` clean (`-0.8%`, noisy). The code change was reverted. |
| Current renderer follow-up: streaming escaped long strings | Rejected | Tried skipping `escapedStringLength` for long escaped strings and rendering with dynamic buffer growth, reducing one pre-scan without collecting escape positions. Output matched and JVM tests passed, but Native A/B remained noise-level: forward `11.158 ms` clean vs `11.324 ms` candidate (`+1.5%`), reverse `10.508 ms` candidate vs `10.576 ms` clean (`-0.65%`). The code change was reverted. |
| Current renderer follow-up: ASCII range byte-source path | Rejected | Tried proving long strings are ASCII, then rendering escaped chunks directly from `String` ranges to bytes to avoid `getBytes(UTF_8)` allocation. Output matched and JVM tests passed, but the extra String scans/range copies were clearly slower: forward `10.201 ms` clean vs `12.513 ms` candidate (`+22.7%`), reverse `13.821 ms` candidate vs `11.839 ms` clean (`+16.7%`). The code change was reverted. |
| Current renderer follow-up: direct OutputStream huge-string writes | Rejected | Tried flushing `ByteBuilder` before huge strings and writing quoted chunks/escapes directly to the underlying `OutputStream` to avoid growing the builder to the full escaped payload. Output matched and JVM tests passed, but many direct writes were much slower: forward `10.997 ms` clean vs `14.886 ms` candidate (`+35.4%`), reverse `14.558 ms` candidate vs `10.722 ms` clean (`+35.8%`). The code change was reverted. |
| Current renderer/format follow-up: producer escape-count hint | Rejected | Tried carrying a precomputed JSON escape-extra count from large formatted strings through `Val.Str` so `BaseByteRenderer` could size the output buffer without its `escapedStringLength` scan. Output matched and JVM tests passed, but Native A/B was consistently slower: forward `9.996 ms` clean vs `11.454 ms` candidate (`+14.6%`), reverse `11.577 ms` candidate vs `10.514 ms` clean (`+10.1%`). The code change was reverted. |
| Current parser follow-up: bulk-scan first text-block line | Rejected | Tried extending the indexed `|||` body scanner so the first content line bypassed the fastparse `(CharsWhile ~ sep)` path and only fell back for malformed separators. Formatting, JVM tests, Native link, and output equality passed, but A/B did not clear the gate: forward `10.390 ms` clean vs `10.801 ms` candidate (`+4.0%`), reverse was noise-dominated (`12.143 ms` candidate vs `12.882 ms` clean, sd ~5 ms), and 40-run confirmation was neutral (`12.339 ms` clean vs `12.262 ms` candidate, `-0.6%`). The code change was reverted. |
| Current format follow-up: source-offset labels | Already covered / not actionable | Audited after the remaining large-template backlog. Current `scanFormat` already stores source offsets for literals, reuses the previous named label to avoid repeated `substring`, and sets `labels = null` for all-simple same-label formats so `large_string_template` performs only one label allocation and uses `singleNamedLabel`. A separate source-offset-label rewrite would not target the remaining gap. |
| Current startup follow-up: lazy stdlib construction | Rejected | Tried lazy CLI stdlib construction plus a compatibility bridge for `Interpreter#createOptimizer` overrides. Focused tests and reviews passed, but final reverse-order Native A/B was clearly negative: `true` `6.5 ms` candidate vs `3.1 ms` clean, `large_string_template` `11.2 ms` vs `8.8 ms`, and `base64` `6.9 ms` vs `4.0 ms`. The change was reverted. |
| `dd90b11a` `Use ASCII bitset for strip chars instead of scala Set` | Existing PR | Covered by PR #789; keep tracking there rather than duplicating. |
| `9dc20016` `Inline arithmetic fast path in tryEagerEval` | Candidate / late-stage | Only after current eager-eval semantics and optimizer tests are audited. |

## Required gate before pushing optimization PRs

1. Search open PRs and current master for duplicate work.
2. Add or update regression tests for any semantic edge being touched.
3. Run formatting and the relevant unit tests locally.
4. Run focused JMH plus nearby guard benchmarks.
5. Run the full test suite before pushing a PR branch.
6. Update this ledger and the relevant PR body with current benchmark evidence.

## Local results

| Change | Evidence |
| --- | --- |
| `ScopedExprTransform` scope-map allocation trim | `OptimizerBenchmark.main`: master `0.432 +/- 0.004 ms/op`, branch `0.422 +/- 0.004 ms/op` (`-2.3%`). |
| Guard benchmark | `MainBenchmark.main`: master `2.223 +/- 0.106 ms/op`, branch `2.204 +/- 0.031 ms/op` (neutral/slightly positive). |
| Tests | `./mill --no-server -j 1 sjsonnet.jvm[3.3.7].test`: 493 passed, 0 failed. `./mill --no-server -j 1 __.test`: success, 2066/2066 tasks. |
| Review | Independent `gpt-5.4` and `claude-sonnet-4.6` code-review agents reported no significant issues. |
| `tryStaticApply` single-pass static value collection | `OptimizerBenchmark.main`: prior branch `0.422 +/- 0.004 ms/op`, candidate `0.414 +/- 0.005 ms/op` on the stable split run. Full historical `rebindApply` shortcut was rejected after repeat runs (`0.414 +/- 0.024`, then `0.430 +/- 0.014`). |
| Guard benchmark | `MainBenchmark.main`: candidate `2.228 +/- 0.062 ms/op`, neutral versus prior branch `2.204 +/- 0.031` and master `2.223 +/- 0.106`. |
| Tests | `./mill --no-server -j 1 sjsonnet.jvm[3.3.7].test`: 493 passed, 0 failed. `./mill --no-server -j 1 __.test`: success, 2066/2066 tasks. |
| Review | Independent `gpt-5.4` and `claude-sonnet-4.6` code-review agents reported no significant issues. |
| Ready-PR stacked gap baseline | Created `perf/stacked-ready-gap-explore` from upstream master, stacked #825/#826/#828/#833/#834 and accepted JIT micro-optimizations. Resolved the #825/#834 Native `CharSWAR` conflict by keeping the four-UTF-16-char ascii-safe SWAR scan and #834 propagation call sites. |
| Stacked baseline validation | `./mill --no-server -j 1 __.reformat`: success. `./mill --no-server -j 1 sjsonnet.jvm[3.3.7].test`: 494 passed, 0 failed. Worktree clean after validation. |
| Latest source-built foldl comparison | Reset and cleaned local `jrsonnet` to `origin/master@5b43fa88`, built `target/release/jrsonnet` with `cargo build --release -p jrsonnet`, built stack branch Scala Native with `sjsonnet.native[3.3.7].nativeLink`, and ran `hyperfine --shell=none --warmup 5 --min-runs 20`. Foldl string concat: sjsonnet `5.293 +/- 0.589 ms`, jrsonnet `8.655 +/- 0.557 ms`. Go `std.foldl`: sjsonnet `4.900 +/- 0.378 ms`, jrsonnet `6.268 +/- 0.505 ms`. Foldl is not a current local gap. |
| Latest source-built large string template comparison | Used `bench/resources/cpp_suite/large_string_template.jsonnet`, verified stdout equality with `cmp`, then ran `hyperfine --shell=none --warmup 5 --min-runs 20`. sjsonnet Scala Native stack: `11.515 +/- 1.085 ms`; source-built jrsonnet: `6.190 +/- 0.975 ms`; current local gap is `1.86x`. |
| Latest source-built big object comparison | Used `bench/resources/cpp_suite/gen_big_object.jsonnet`, verified stdout equality with `cmp`, then ran `hyperfine --shell=none --warmup 5 --min-runs 20`. sjsonnet Scala Native stack: `9.057 +/- 0.666 ms`; source-built jrsonnet: `8.972 +/- 0.819 ms`; result is effectively neutral (`1.01x`). |
| Latest source-built realistic2 comparison | Used `bench/resources/cpp_suite/realistic2.jsonnet`, verified stdout equality with `cmp`, then ran `hyperfine --shell=none --warmup 3 --min-runs 10`. sjsonnet Scala Native stack: `83.932 +/- 1.629 ms`; source-built jrsonnet: `144.282 +/- 2.369 ms`; sjsonnet is faster (`0.58x` jrsonnet time). |
| Latest source-built kube-prometheus comparison | Used `jrsonnet/tests/realworld/entry-kube-prometheus.jsonnet` from `jrsonnet/tests/realworld` with `-J vendor`. `jrb install` failed locally with a reqwest/rustls provider panic, so vendor deps were installed with `/tmp/sjsonnet-tools/jb install`. Verified stdout equality with `cmp` (`7,506,029` bytes), then ran `hyperfine --shell=none --warmup 3 --min-runs 10`. sjsonnet Scala Native stack: `235.971 +/- 12.925 ms`; source-built jrsonnet: `93.188 +/- 6.599 ms`; current local gap is `2.53x`. |
| `MaterializeJsonRenderer` visitor reuse | Reused stateless array/object visitors for `std.manifestJson*`. Validation: `__.reformat`, `sjsonnet.jvm[3.3.7].test` (494/494), kube-prometheus output equality, focused JMH guards, and three independent reviews. Kube-prometheus Native stack improved to `224.975 +/- 11.550 ms` (`-4.66%` vs prior stack); source-built jrsonnet in the same run was `88.576 +/- 0.915 ms`, leaving a `2.54x` gap. |
| `.json` import fast path | Added shared sync/Preloader strict-JSON parse path with duplicate-key, malformed JSON, non-finite number, large-integer, and parser-depth regression coverage. Validation: `__.reformat`, `sjsonnet.jvm[3.3.7].test` (502/502), full `__.test` (2066/2066), Native link, kube-prometheus output equality, focused JMH guards, and three independent reviews. Kube-prometheus Native stack improved to `139.242 +/- 1.204 ms`; source-built jrsonnet in the same run was `88.025 +/- 1.271 ms`, leaving a `1.58x` gap. |
| `.json` import visitor micro-optimization | Validation: `__.reformat`, `sjsonnet.jvm[3.3.7].test` (502/502), full `__.test` (2066/2066), Native link, kube-prometheus output equality, focused JMH guards, and three independent reviews. Same-run Native A/B against frozen clean `e4fed2e4`: clean `141.526 +/- 1.896 ms`; candidate `139.088 +/- 1.305 ms`; source-built jrsonnet `87.421 +/- 0.932 ms`, leaving a `1.59x` same-run gap. |
| `.json` import inline object layout | Validation: `__.reformat`, focused JVM JSON/concurrency tests, `sjsonnet.jvm[3.3.7].test` (503/503), full `__.test` (2066/2066), Native link, kube-prometheus output equality, focused JMH guards, and three final reviews. Same-run Native A/B against frozen clean `de5cd388`: clean `139.937 +/- 2.294 ms`; candidate `136.301 +/- 1.957 ms`; source-built jrsonnet `88.087 +/- 1.737 ms`, leaving a `1.55x` same-run gap. |

---

## 2026-05-11: JSON Position Reuse Optimization (Commit 24762a56)

**Status**: ✅ Accepted & Pushed

**Idea**: Strict `.json` imports (syntactically correct JSON, no code execution) create a Position object for each JSON scalar/container during traversal. Since JSON is static, all positions can safely reuse `fileScope.noOffsetPos` (a singleton sentinel used for input-less imports).

**Implementation**: 
- Modified `JsonImportVisitor.pos()` to return cached `jsonPos = fileScope.noOffsetPos` instead of allocating `new Position(fileScope, index)` per element.
- 2-line change in `Importer.scala`.

**Reasoning**: 
- Position objects are only used for error reporting (stack frames).
- Strict JSON never executes code, so no Position construction is needed during evaluation.
- Position is immutable; sharing the singleton is safe.
- Imported JSON trees are shared via ParseCache; concurrency-safe (JSON reads are cached).

**Results**:
- Kube-prometheus Native: `136.7 ± 1.5 ms` → `135.9 ± 1.2 ms` (-0.58%, repeat `137.5 → 136.1`).
- Gap: `1.55x` → `1.54x` (vs jrsonnet 88.087 ms).
- No regression: all JMH guards stable (manifestJsonEx 0.053, realistic2 39.485, large_string_template 1.102, gen_big_object 0.801).

**Validation**: 
- Formatting ✓, focused tests 8/8 ✓, JVM tests 503/503 ✓, full cross-platform 2066/2066 ✓, Native link ✓.
- Output equality with source-built jrsonnet verified.

**Next**: Remaining `1.54x` gap is still Materializer-dominated (155ms of 180ms total per prior debug stats). Profile ByteRenderer/escaping hot paths for next candidate.
