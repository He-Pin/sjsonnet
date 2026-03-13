l staticValues: Array[Val] = null,
    private val singleFieldKey: String = null,
    private val singleFieldMember: Obj.Member = null,
    private val inlineFieldKeys: Array[String] = null,
    private val inlineFieldMembers: Array[Obj.Member] = null)
    extends Literal with Expr.ObjBody {
  
  // Inline value cache: avoids HashMap allocation for ≤2 cached fields
  private var ck1: Any = null
  private var cv1: Val = null
  private var ck2: Any = null
  private var cv2: Val = null
```

**Key optimizations already in place**:
- **Inline cache for 2 fields** (lines 522-527): Avoids HashMap allocation
- **Static object layout** (lines 89-103 in Materializer): Pre-computed for static objects
- **Value cache** (lines 509): Lazily populated HashMap

**Object comprehension** (Evaluator.scala, lines 1887-1920):
```scala
def visitObjComp(e: ObjBody.ObjComp, sup: Val.Obj)(implicit scope: ValScope): Val.Obj = {
  val binds = e.allLocals
  val compScope: ValScope = scope
  val builder = new java.util.LinkedHashMap[String, Val.Obj.Member]  // Allocate builder
  val compScopes = visitComp(e.first :: e.rest, Array(compScope))   // Get all scopes
  if (debugStats != null) debugStats.objectCompIterations += compScopes.length
  var ci = 0
  while (ci < compScopes.length) {
    val s = compScopes(ci)
    visitExpr(e.key)(s) match {
      case Val.Str(_, k) =>
        val previousValue = builder.put(
          k,
          new Val.Obj.Member(e.plus, Visibility.Normal, deprecatedSkipAsserts = true) {
            def invoke(self: Val.Obj, sup: Val.Obj, fs: FileScope, ev: EvalScope): Val = {
              checkStackDepth(e.value.pos, "object comprehension")
              try {
                lazy val newScope: ValScope = s.extend(newBindings, self, sup)
                lazy val newBindings = visitBindings(binds, newScope)
                visitExpr(e.value)(newScope)
              } finally decrementStackDepth()
            }
          }
        )
        if (previousValue != null) {
          Error.fail(s"Duplicate key $k in evaluated object comprehension.", e.pos)
        }
      ...
    }
    ci += 1
  }
  new Val.Obj(e.pos, builder, false, null, sup)
}
```

### Optimization Opportunities

#### **4a. Optimize Val.Obj.Member Allocation in Object Comprehensions** (MEDIUM PRIORITY)
**Problem**: Each key-value pair in an object comprehension allocates a new anonymous `Val.Obj.Member` subclass instance  (lines 1900-1909).

For `realistic2` (50ms) with large object builds, this creates many small allocations (~120 bytes each for anonymous inn er class).

**Current**:
```scala
new Val.Obj.Member(e.plus, Visibility.Normal, deprecatedSkipAsserts = true) {
  def invoke(self: Val.Obj, sup: Val.Obj, fs: FileScope, ev: EvalScope): Val = { ... }
}
```

**Recommendation**: Create a reusable `LazyMember` class:
```scala
final class LazyMember(
    add: Boolean,
    visibility: Visibility,
    val scope: ValScope,
    val binds: Array[Expr],
    val expr: Expr,
    val evaluator: Evaluator)
    extends Val.Obj.Member(add, visibility, deprecatedSkipAsserts = true) {
  
  def invoke(self: Val.Obj, sup: Val.Obj, fs: FileScope, ev: EvalScope): Val = {
    checkStackDepth(expr.pos, "object comprehension")
    try {
      val newScope = scope.extend(visitBindings(binds, scope), self, sup)
      evaluator.visitExpr(expr)(newScope)
    } finally {
      ev.asInstanceOf[Evaluator].decrementStackDepth()
    }
  }
}
```

Then reuse:
```scala
new LazyMember(e.plus, Visibility.Normal, s, binds, e.value, this)
```

**Benefits**:
- Reduces per-member allocation from ~120B (anonymous class) to ~88B (named class)
- Better GC pressure
- Estimated impact: 5-10% on object-heavy workloads like realistic2

---

#### **4b. Pre-allocate LinkedHashMap with Correct Size** (LOW-MEDIUM PRIORITY)
**Problem**: Line 1890 allocates LinkedHashMap without size hint:
```scala
val builder = new java.util.LinkedHashMap[String, Val.Obj.Member]()
```

For comprehensions with many keys, this causes resizes.

**Recommendation**:
```scala
val builder = Util.preSizedJavaLinkedHashMap[String, Val.Obj.Member](compScopes.length)
```

**Benefits**:
- Avoid HashMap resizes during insertion
- Estimated impact: 2-5% on large object comprehensions

---

#### **4c. Optimize addSuper for Object Merging** (MEDIUM PRIORITY)
**Problem**: Object merge (`lo + ro`) calls `addSuper` (lines 1242, Val.scala 607-650), which builds an array of the sup er chain and reconstructs it.

**Current logic** (Val.scala, lines 607-650):
```scala
def addSuper(pos: Position, lhs: Val.Obj): Val.Obj = {
  // Fast path: no super chain
  if (getSuper == null) {
    val filteredExcluded = if (excludedKeys != null) {
      Util.intersect(excludedKeys, getValue0.keySet())
    } else null
    return new Val.Obj(
      this.pos, this.getValue0, false, this.triggerAsserts, lhs,
      null, null, filteredExcluded
    )
  }
  
  // Slow path: walk super chain, rebuild
  val builder = new mutable.ArrayBuilder.ofRef[Val.Obj]
  var current = this
  while (current != null) {
    builder += current
    current = current.getSuper
  }
  val chain = builder.result()
  
  // ... (rebuild super chain with new root) ...
}
```

**Problem**: For chains like `a + b + c + d`, this does O(chain_length) work per merge:
- `(a + b)` walks a's super chain, rebuilds it with b as root
- `((a+b) + c)` walks the result's super chain again, rebuilds with c as root
- Total: O(n²) work for n merges

**Recommendation**: Flatten super chain references during merge:
```scala
def addSuper(pos: Position, lhs: Val.Obj): Val.Obj = {
  // Fast path: no super, just create new root
  if (getSuper == null) {
    val filteredExcluded = if (excludedKeys != null) {
      Util.intersect(excludedKeys, getValue0.keySet())
    } else null
    return new Val.Obj(
      this.pos, this.getValue0, false, this.triggerAsserts, lhs,
      null, null, filteredExcluded
    )
  }
  
  // Direct merge without rebuilding chain
  return new Val.Obj(
    this.pos, this.getValue0, false, this.triggerAsserts, 
    new ChainedSuper(lhs, getSuper),  // Lazy chain representation
    null, null, excludedKeys
  )
}

// New class for lazy super chain chaining
final class ChainedSuper(val immediate: Val.Obj, val next: Val.Obj) extends Val.Obj(
  immediate.pos, null, false, null, next
) {
  override def getValue0: util.LinkedHashMap[String, Val.Obj.Member] = {
    // On first access, flatten the chain and update
    if (value0 == null) {
      value0 = immediate.getValue0
    }
    value0
  }
  
  override def getSuper: Val.Obj = next
}
```

**Benefits**:
- Avoids O(n²) rebuilds for long merge chains
- Estimated impact: Minimal for typical cases, but crucial for edge cases

---

#### **4d. Inline Cache Hit Rate Analysis** (LOW PRIORITY)
The inline cache for 2 fields (ck1/cv1, ck2/cv2) was added to avoid HashMap allocations.

**Observation**: For bench.02 (object fibonacci), this eliminates ~242K HashMap allocations.

This is already well-optimized. No changes needed.

---

### Data Structure Insights
- **Inline cache**: Two-field optimization is good ✓
- **Static object layout**: Pre-computed values avoid lazy eval ✓
- **LinkedHashMap**: Good choice for maintaining insertion order
- **Member abstraction**: Abstract class with invoke method — good dispatch pattern ✓

---

## 5. Import/Parse Caching

### Current Implementation
**File**: `/Users/hepin/IdeaProjects/sjsonnet/sjsonnet/src/sjsonnet/Importer.scala` (lines 182-247)

**CachedImporter** (lines 182-195):
```scala
class CachedImporter(parent: Importer) extends Importer {
  val cache: mutable.HashMap[Path, ResolvedFile] = mutable.HashMap.empty[Path, ResolvedFile]

  def resolve(docBase: Path, importName: String): Option[Path] = parent.resolve(docBase, importName)

  def read(path: Path, binaryData: Boolean): Option[ResolvedFile] = cache.get(path) match {
    case s @ Some(x) =>
      if (x == null) None else s
    case None =>
      val x = parent.read(path, binaryData)
      cache.put(path, x.orNull)
      x
  }
}
```

**CachedResolver** (lines 197-247):
```scala
class CachedResolver(
    parentImporter: Importer,
    val parseCache: ParseCache,
    internedStrings: mutable.HashMap[String, String],
    internedStaticFieldSets: Val.StaticObjectLayoutCache,
    settings: Settings = Settings.default)
    extends CachedImporter(parentImporter) {

  def parse(path: Path, content: ResolvedFile)(implicit
      ev: EvalErrorScope): Either[Error, (Expr, FileScope)] = {
    parseCache.getOrElseUpdate(
      (path, content.contentHash()),  // Key: (Path, String hash)
      {
        val parsed = fastparse.parse(...)
        parsed.flatMap { case (e, fs) => process(e, fs) }
      }
    )
  }
}
```

**DefaultParseCache** (ParseCache.scala, lines 28-48):
```scala
class DefaultParseCache extends ParseCache {
  val cache = new scala.collection.mutable.HashMap[(Path, String), Either[Error, (Expr, FileScope)]]()

  override def getOrElseUpdate(
      key: (Path, String),
      defaultValue: => Either[Error, (Expr, FileScope)]): Either[Error, (Expr, FileScope)] = {
    cache.getOrElseUpdate(key, defaultValue)
  }
}
```

**Import evaluation** (Evaluator.scala, lines 1146-1163):
```scala
def visitImport(e: Import): Val = {
  val (p, str) = importer.resolveAndReadOrFail(e.value, e.pos, binaryData = false)
  val cached = cachedImports.contains(p)  // Check cache presence
  if (debugStats != null && cached) debugStats.importCacheHits += 1
  cachedImports.getOrElseUpdate(
    p, {
      if (debugStats != null) debugStats.importCalls += 1
      checkStackDepth(e.pos, e)
      try {
        val doc = resolver.parse(p, str) match {
          case Right((expr, _)) => expr
          case Left(err)        => throw err.asSeenFrom(this)
        }
        visitExpr(doc)(ValScope.empty)
      } finally decrementStackDepth()
    }
  )
}
```

### Optimization Opportunities

#### **5a. Avoid Double Hash Lookup in ParseCache** (MEDIUM PRIORITY)
**Problem**: Lines 1148-1150 do a contains check before getOrElseUpdate:

```scala
val cached = cachedImports.contains(p)
if (debugStats != null && cached) debugStats.importCacheHits += 1
cachedImports.getOrElseUpdate(p, { ... })  // Second lookup
```

This does **two HashMap lookups** for every cached import.

**Recommendation**: Use a single lookup:
```scala
cachedImports.get(p) match {
  case Some(v) =>
    if (debugStats != null) debugStats.importCacheHits += 1
    v
  case None =>
    if (debugStats != null) debugStats.importCalls += 1
    checkStackDepth(e.pos, e)
    try {
      val result = resolver.parse(p, str) match {
        case Right((expr, _)) => expr
        case Left(err)        => throw err.asSeenFrom(this)
      }
      cachedImports.put(p, visitExpr(result)(ValScope.empty))
      cachedImports(p)
    } finally decrementStackDepth()
}
```

**Benefits**:
- Eliminates one HashMap lookup per import access
- Better for import-heavy programs
- Estimated impact: 2-5% if imports are frequent

---

#### **5b. Hash Key Concatenation Cost** (MEDIUM PRIORITY)
**Problem**: ParseCache uses `(path, content.contentHash())` as key (line 208).

The `contentHash()` method likely reads and hashes the file content. This is called **before checking the cache**:
```scala
parseCache.getOrElseUpdate(
  (path, content.contentHash()),  // ← Hash computed eagerly
  { ... }
)
```

If the parse is cached, the hash computation was wasted.

**Recommendation**: Lazy hash computation:
```scala
trait ParseCacheKey {
  def path: Path
  def contentHashLazy: () => String
  
  override def hashCode: Int = path.hashCode
  override def equals(obj: Any): Boolean = obj match {
    case other: ParseCacheKey =>
      this.path == other.path && this.contentHashLazy() == other.contentHashLazy()
    case _ => false
  }
}

parseCache.getOrElseUpdate(
  new ParseCacheKey {
    val path = p
    val contentHashLazy = () => content.contentHash()
  },
  { ... }
)
```

But this adds complexity. **Better**: Change cache key strategy:
```scala
// Cache key is just Path; store (hash, parsed) in value
val parseCache: mutable.HashMap[Path, Option[(String, Either[Error, (Expr, FileScope)])]] = ...

def getCached(path: Path, content: ResolvedFile): Option[Either[Error, (Expr, FileScope)]] = {
  parseCache.get(path).flatMap { case (cachedHash, result) =>
    if (cachedHash == content.contentHash()) Some(result) else None
  }
}
```

But this still hashes on miss. **Conclusion**: Current design is acceptable; avoid over-optimization.

---

#### **5c. Interned Strings Cache Efficiency** (LOW PRIORITY)
**Problem**: ParseCache stores `internedStrings` (line 200), but it's not clear when interning helps.

```scala
internedStrings: mutable.HashMap[String, String]
```

For typical workloads with many imports of the same file, string interning helps reduce memory, but:
- HashMap lookup overhead on every string key
- String.intern() in Java is already optimized

**Recommendation**: Monitor if this is actually used. If string deduplication isn't critical, remove it.

---

#### **5d. Content Hash Caching** (LOW PRIORITY)
**Problem**: `content.contentHash()` (line 208) is called on every parse check. If contentHash involves reading the file  again, this is wasteful.

**Observation**: ResolvedFile should cache its content hash.

**Recommendation**: Ensure ResolvedFile caches hash:
```scala
trait ResolvedFile {
  def getParserInput(): ParserInput
  
  private var _contentHash: String = null
  def contentHash(): String = {
    if (_contentHash == null) {
      _contentHash = computeHash(getParserInput().readString())
    }
    _contentHash
  }
}
```

Assume this is already done ✓

---

### Data Structure Insights
- **Two-level cache**: CachedImporter (file reads) + CachedResolver (parsing) ✓ Good
- **HashMap lookup**: Single-key lookup (`Path`) in evaluator cache ✓ Good
- **Tuple2 key**: `(Path, String)` in ParseCache — acceptable
- **orNull sentinel**: Using null as "not found" marker (line 192) — OK but unconventional

---

## Summary of Optimizations by Impact

### Tier 1 (HIGH - 10-30% impact)
1. **3a. Three-level comprehension optimization** (comparison2: 20-30%)
2. **1a. Avoid redundant comparison type checks** (comparison2: 5-10%)
3. **3c. Lazy range element allocation** (comparison2: 10-15%)

### Tier 2 (MEDIUM - 5-10% impact)
4. **4a. Optimize Val.Obj.Member allocation** (realistic2: 5-10%)
5. **1b. Extract concat operation** (BinaryOp cleanup, 5-10%)
6. **5a. Avoid double hash lookup** (import-heavy: 2-5%)
7. **3b. Size hint optimization** (nested comprehension: 2-5%)

### Tier 3 (MEDIUM-LOW - 2-5% impact)
8. **1c. Inline visitBinaryOpAsDouble for hot path** (fibonacci: 2-5%)
9. **4b. Pre-allocate LinkedHashMap** (object-heavy: 2-5%)
10. **4c. Optimize addSuper chain** (merge-heavy edge cases)

### Tier 4 (LOW - <2% impact or exploratory)
11. **2a. String builder pool** (unlikely to help; Materializer already optimized)
12. **5c & 5d**: Cache efficiency (already likely optimized)

---

## Recommended Implementation Order

**Phase 1** (Week 1 - High ROI):
1. **3a** - Three-level comprehension (comparison2 +20-30%)
2. **1a** - Deduplicate comparisons (comparison2 +5-10%)
3. **3c** - Lazy range elements (comparison2 +10-15%)

**Phase 2** (Week 2 - Medium ROI):
4. **4a** - Optimize Val.Obj.Member (realistic2 +5-10%)
5. **1b** - Extract concat (cleanup + slight perf)

**Phase 3** (Week 3 - Polish):
6. **5a** - Double hash lookup (import-heavy +2-5%)
7. **1c** - Inline numeric fast path (fibonacci +2-5%)

---

## Testing Strategy

1. Run benchmarks for each optimization:
   ```bash
   ./benchmark.sh bench.02 bench.03 comparison2 reverse realistic2
   ```

2. Profile with JFR to confirm allocation reductions:
   ```bash
   jcmd Benchmark JFR.start settings=profile duration=10s filename=profile.jfr
   ```

3. Check for regressions in test suite after each change.

---

## Conclusion

The sjsonnet evaluator is well-optimized in most areas (numeric fast paths, scope reuse, inline caching). The main oppor tunities are:

1. **Comprehension optimization** for nested loops (comparison2)
2. **Code deduplication** in comparison operators
3. **Lazy allocation** for array ranges
4. **Object construction** efficiency improvements

Implementing the Tier 1 optimizations could yield **25-50% improvement** on comparison-heavy and comprehension-heavy ben chmarks.

EOF
cat /tmp/sjsonnet_optimization_analysis.md

                EC=$?
                echo "___BEGIN___COMMAND_DONE_MARKER___$EC"
            }
___BEGIN___COMMAND_OUTPUT_MARKER___
# SJSONnet Performance Optimization Opportunities

## Executive Summary

This document identifies 5 key areas for performance optimization in sjsonnet, with specific focus on reducing allocations, virtual method calls, and improving data structure choices to optimize for fibonacci (31.8ms), realistic2 (50ms), comparison2 (18.3ms), and reverse (8.6ms) benchmarks.

---

## 1. BinaryOp Handling in Evaluator.scala

### Current Implementation
**File**: `/Users/hepin/IdeaProjects/sjsonnet/sjsonnet/src/sjsonnet/Evaluator.scala` (lines 1204-1280)

The BinaryOp evaluation has two paths:
1. **Numeric fast path** (lines 1208-1213, 1258-1262): Uses `visitBinaryOpAsDouble()` to avoid intermediate `Val.Num` allocation
2. **Polymorphic fallback** (lines 1227-1255): Evaluates both sides to `Val` then dispatches on types

```scala
// Fast path for pure numeric ops
case Expr.BinaryOp.OP_* =>
  Val.cachedNum(pos, visitBinaryOpAsDouble(e))  // Zero intermediate allocation
  
// Polymorphic ops: string concat, array concat, object merge
case Expr.BinaryOp.OP_+ =>
  val l = visitExpr(e.lhs)  // First evaluation
  val r = visitExpr(e.rhs)  // Second evaluation
  l match {
    case ln: Val.Num => r match { ... }  // Nested match avoids Tuple2
    case ls: Val.Str => r match { ... }  // Each case is a separate virtual dispatch
    case lo: Val.Obj => r match { ... }
    case la: Val.Arr => r match { ... }
    case _ => r match { ... }
  }
```

### Optimization Opportunities

#### **1a. Avoid Redundant Type Checks in Comparison Operations** (HIGH PRIORITY)
**Impact**: comparison2 benchmark (18.3ms) - heavy on comparison operations

**Problem**: Comparison ops (OP_<, OP_>, OP_<=, OP_>=) are implemented with nested pattern matching that duplicates type checking logic across 4 operators (lines 1265-1520).

Example:
```scala
case Expr.BinaryOp.OP_< =>
  val l = visitExpr(e.lhs)
  val r = visitExpr(e.rhs)
  l match {
    case ln: Val.Num => r match {
      case rn: Val.Num => Val.bool(ln.rawDouble < rn.rawDouble)
      case _ => failBinOp(...)
    }
    case ls: Val.Str => r match {
      case rs: Val.Str => Val.bool(Util.compareStringsByCodepoint(ls.str, rs.str) < 0)
      ...
    }
  }
case Expr.BinaryOp.OP_> =>  // DUPLICATES entire match structure
  val l = visitExpr(e.lhs)
  val r = visitExpr(e.rhs)
  l match {
    case ln: Val.Num => r match {
      case rn: Val.Num => Val.bool(ln.rawDouble > rn.rawDouble)  // Only op differs
      ...
    }
  }
```

**Recommendation**: Extract a common comparison function that takes an operator:
```scala
private def compareValues(op: Int, l: Val, r: Val, pos: Position): Val.Bool = {
  l match {
    case ln: Val.Num => r match {
      case rn: Val.Num => 
        val cmp = java.lang.Double.compare(ln.rawDouble, rn.rawDouble)
        Val.bool((op: @switch) match {
          case OP_< => cmp < 0
          case OP_> => cmp > 0
          case OP_<= => cmp <= 0
          case OP_>= => cmp >= 0
        })
      case _ => failBinOp(l, op, r, pos)
    }
    case ls: Val.Str => r match {
      case rs: Val.Str =>
        val cmp = Util.compareStringsByCodepoint(ls.str, rs.str)
        Val.bool((op: @switch) match {
          case OP_< => cmp < 0
          case OP_> => cmp > 0
          case OP_<= => cmp <= 0
          case OP_>= => cmp >= 0
        })
      case _ => failBinOp(l, op, r, pos)
    }
    case la: Val.Arr => r match {
      case ra: Val.Arr =>
        val cmp = compare(la, ra)
        Val.bool((op: @switch) match {
          case OP_< => cmp < 0
          case OP_> => cmp > 0
          case OP_<= => cmp <= 0
          case OP_>= => cmp >= 0
        })
      case _ => failBinOp(l, op, r, pos)
    }
    case _ => failBinOp(l, op, r, pos)
  }
}
```

Then in `visitBinaryOp`:
```scala
case Expr.BinaryOp.OP_< | Expr.BinaryOp.OP_> | Expr.BinaryOp.OP_<= | Expr.BinaryOp.OP_>= =>
  val l = visitExpr(e.lhs)
  val r = visitExpr(e.rhs)
  compareValues(e.op, l, r, pos)
```

**Benefits**:
- Eliminate ~300 lines of duplicated code
- Reduce JIT code size, improving cache efficiency
- Better for i-cache locality on comparison-heavy workloads
- **Estimated impact**: 5-10% on comparison2 benchmark

---

#### **1b. Use Method Extraction for Op-+ (String/Array/Object Concat)** (MEDIUM PRIORITY)
**Problem**: The OP_+ case (lines 1227-1255) has 5 nested levels and handles 4 completely different operations (Num+Num, Num+Str, Str+Str, Arr+Arr, Obj+Obj merging).

**Current**: Each type pairing creates a separate code path. Materializer.stringify() allocations add up when converting non-strings.

**Recommendation**: Extract concat-style operations:
```scala
private def visitConcatOp(l: Val, r: Val, pos: Position): Val = {
  (l, r) match {
    case (ln: Val.Num, rn: Val.Num) => Val.cachedNum(pos, ln.rawDouble + rn.rawDouble)
    case (ln: Val.Num, rs: Val.Str) => Val.Str(pos, RenderUtils.renderDouble(ln.rawDouble) + rs.str)
    case (ls: Val.Str, rs: Val.Str) => Val.Str(pos, ls.str + rs.str)  // Fast path
    case (ls: Val.Str, rn: Val.Num) => Val.Str(pos, ls.str + RenderUtils.renderDouble(rn.rawDouble))
    case (ls: Val.Str, _) => Val.Str(pos, ls.str + Materializer.stringify(r))
    case (lo: Val.Obj, ro: Val.Obj) => ro.addSuper(pos, lo)
    case (lo: Val.Obj, rs: Val.Str) => Val.Str(pos, Materializer.stringify(lo) + rs.str)
    case (lo: Val.Obj, _) => failBinOp(lo, OP_+, r, pos)
    case (la: Val.Arr, ra: Val.Arr) => la.concat(pos, ra)
    case (la: Val.Arr, rs: Val.Str) => Val.Str(pos, Materializer.stringify(la) + rs.str)
    case (la: Val.Arr, _) => failBinOp(la, OP_+, r, pos)
    case (_, rs: Val.Str) => Val.Str(pos, Materializer.stringify(l) + rs.str)
    case _ => failBinOp(l, OP_+, r, pos)
  }
}
```

**Benefits**:
- Cleaner, more maintainable code
- Better JIT specialization
- Easier to see all concat cases at once

---

#### **1c. Inline More Cases into visitBinaryOp** (MEDIUM PRIORITY)
**Problem**: Many operations delegate to `visitBinaryOpAsDouble()` which makes a tail call, creating a stack frame. For the fibonacci benchmark (31.8ms), this adds overhead.

**Current Pattern** (line 1209):
```scala
case Expr.BinaryOp.OP_* =>
  Val.cachedNum(pos, visitBinaryOpAsDouble(e))  // Tail call
```

**Recommendation**: Partially inline the most common cases:
```scala
case Expr.BinaryOp.OP_* =>
  var lNum = 0.0
  var rNum = 0.0
  try {
    lNum = visitExprAsDouble(e.lhs)
    rNum = visitExprAsDouble(e.rhs)
  } catch { 
    case _: Evaluator.NonNumericValue =>
      // Fall back to full visitBinaryOpAsDouble for error handling
      Val.cachedNum(pos, visitBinaryOpAsDouble(e))
  }
  val r = lNum * rNum
  if (r.isInfinite) Error.fail("overflow", pos)
  Val.cachedNum(pos, r)
```

This avoids the nested function call for the happy path.

---

### Data Structure Insights
- **Num caching** (Val.cachedNum): Uses intrinsic pool to avoid allocating small integers repeatedly ✓ Good
- **Bool caching** (Val.bool): Uses Val.True/Val.False singletons ✓ Good  
- **String concatenation**: Relies on JVM String concatenation (optimized by javac to StringBuilder) — OK but could use `StringBuilder` explicitly for chains
- **Nested matching pattern**: Avoids Tuple2 allocation by using nested match ✓ Good pattern

---

## 2. String Operations

### Current Implementation
**File**: `/Users/hepin/IdeaProjects/sjsonnet/sjsonnet/src/sjsonnet/Val.scala` (line 264)

```scala
final case class Str(var pos: Position, str: String) extends Literal {
  def prettyName = "string"
  override def asString: String = str
}
```

**String concat in BinaryOp** (Evaluator.scala, lines 1237-1239):
```scala
case ls: Val.Str => r match {
  case rs: Val.Str => Val.Str(pos, ls.str + rs.str)  // JVM concatenation
  case rn: Val.Num => Val.Str(pos, ls.str + RenderUtils.renderDouble(rn.rawDouble))
  case _ => Val.Str(pos, ls.str + Materializer.stringify(r))
}
```

### Optimization Opportunities

#### **2a. Implement String Builder Pool for repeated concatenation** (MEDIUM PRIORITY)
**Problem**: Benchmarks with string rendering (realistic2, reverse) may hit repeated string concatenation. While javac optimizes `a + b + c`, more complex patterns can create intermediate strings.

**Example pattern in realistic2**:
```jsonnet
{ foo: [ std.reverse(std.range(...)) for i in std.range(...) ] }
```
Array rendering to string + multiple concatenations.

**Recommendation**: 
- Add a `StringBuilderPool` to avoid allocating many StringBuilders
- Use for Materializer.stringify() which builds JSON strings
- Low priority here because Materializer is already optimized with ujson.Value path

**Current mitigation**: Materializer uses `Renderer` (upickle-based) which uses StringBuilder internally ✓

---

#### **2b. Lazy String Concatenation for Thunks** (LOW PRIORITY)
**Problem**: When evaluating `a + b + c`, intermediate `Val.Str` objects are created even if they're only used for further concatenation.

**Observation**: This is hard to optimize without making the type system more complex. Current approach is reasonable.

---

### Data Structure Insights
- **String immutability**: Using `String` directly (immutable) is correct ✓
- **Val.Str allocation**: ~40 bytes per string value (pos + str reference) — acceptable
- **String rendering overhead**: `RenderUtils.renderDouble()` allocates per numeric-to-string conversion
  - **Optimization**: Cache common doubles like 0, 1, -1? (Unlikely to help much; renderDouble is already fast)

---

## 3. Comprehension Evaluation

### Current Implementation
**File**: `/Users/hepin/IdeaProjects/sjsonnet/sjsonnet/src/sjsonnet/Evaluator.scala` (lines 196-420)

Three-level optimization:

**Level 1 - Loop-invariant body** (lines 225-232):
```scala
if (lazyArr.length > 1 && isInvariantExpr(body, scope.bindings.length)) {
  // Body doesn't reference the loop variable — evaluate once, replicate
  val result = visitExpr(body)(scope)
  var j = 0
  while (j < lazyArr.length) {
    results += result  // Just reuse same value
    j += 1
  }
}
```

**Level 2 - Non-capturing body with mutable scope** (lines 233-273):
```scala
if (lazyArr.length > 1 && isNonCapturingBody(body)) {
  val mutableScope = scope.extendMutable()  // Single scope object
  val slot = scope.bindings.length
  val bindings = mutableScope.bindings
  // Mutate the binding slot each iteration instead of creating new scopes
  var j = 0
  while (j < lazyArr.length) {
    bindings(slot) = lazyArr(j)
    results += visitExpr(body)(mutableScope)
    j += 1
  }
}
```

**Level 3 - Binary op specialization** (lines 240-265):
For common pattern `[x op y for i in arr]`:
```scala
case binOp: BinaryOp
    if binOp.lhs.tag == ExprTags.ValidId
      && binOp.rhs.tag == ExprTags.ValidId =>
  // Inline scope lookups and binary-op dispatch
  val lhsIdx = binOp.lhs.asInstanceOf[ValidId].nameIdx
  val rhsIdx = binOp.rhs.asInstanceOf[ValidId].nameIdx
  val op = binOp.op
  val bpos = binOp.pos
  var j = 0
  while (j < lazyArr.length) {
    bindings(slot) = lazyArr(j)
    val l = bindings(lhsIdx).value
    val r = bindings(rhsIdx).value
    l match {
      case ln: Val.Num => r match {
        case rn: Val.Num =>
          results += evalBinaryOpNumNum(op, ln, rn, bpos)  // Fast num-num path
        case _ =>
          results += visitBinaryOpValues(op, l, r, bpos)
      }
      case _ =>
        results += visitBinaryOpValues(op, l, r, bpos)
    }
    j += 1
  }
```

**Level 4 - Two-level comprehension** (lines 280-284, visitCompTwoLevel):
For pattern `[body for outer in A for inner in B]` where B doesn't depend on outer:
```scala
case (nextFor: ForSpec) :: Nil
    if lazyArr.length > 1
      && isNonCapturingBody(body)
      && isInvariantExpr(nextFor.cond, scope.bindings.length) =>
  visitCompTwoLevel(lazyArr, nextFor.cond, scope, body, results)
```

### Optimization Opportunities

#### **3a. Three-Level Comprehension Optimization** (HIGH PRIORITY)
**Impact**: comparison2 benchmark (18.3ms) - `[i < j for i in std.range(1, 1000) for j in std.range(1, 1000)]`

**Problem**: The code currently detects and optimizes:
1. Loop invariant body (lines 225-232)
2. Mutable scope + non-capturing body (lines 233-273)
3. Mutable scope + binary op body (lines 240-265)
4. Two-level comprehension (lines 280-284)

But **missing**: Three-level comprehension when the innermost loop has invariant condition but outer body is non-capturing binary op.

The comparison2 test has **nested comprehension**: `[i < j for i in range(1000) for j in range(1000)]`

**Current flow**:
1. For outer `i in range(1000)`: Falls through to generic case (line 286-290)
2. For each iteration: Recursively calls `visitCompInline` for inner for spec
3. For inner `j in range(1000)`: Should hit Level 2 (mutable scope) but nesting costs allocations

**Recommendation**: Detect and optimize **three-level pattern**:
```scala
case (nextFor @ ForSpec(_, name, expr)) :: rest
    if rest.length == 1 && rest(0).isInstanceOf[IfSpec] &&
       lazyArr.length > 1 &&
       isNonCapturingBody(body) &&
       body.isInstanceOf[BinaryOp] =>
  visitCompThreeLevel(lazyArr, nextFor, rest(0).asInstanceOf[IfSpec], scope, body, results)
```

Implement:
```scala
private def visitCompThreeLevel(
    outerArr: Array[Eval],
    middleForSpec: ForSpec,
    ifSpec: IfSpec,
    scope: ValScope,
    body: Expr,
    results: collection.mutable.ArrayBuilder.ofRef[Eval]): Unit = {
  // Pre-allocate with better size hint for nested loop
  val estimate = outerArr.length.toLong * 1000.toLong  // Typical range
  if (estimate <= (1 << 22)) results.sizeHint(estimate.toInt)
  
  val scopeLen = scope.bindings.length
  val mutableScope = scope.extendBy(2)
  val bindings = mutableScope.bindings
  val outerSlot = scopeLen
  val middleSlot = scopeLen + 1
  
  body match {
    case binOp: BinaryOp
        if binOp.lhs.tag == ExprTags.ValidId
          && binOp.rhs.tag == ExprTags.ValidId =>
      val lhsIdx = binOp.lhs.asInstanceOf[ValidId].nameIdx
      val rhsIdx = binOp.rhs.asInstanceOf[ValidId].nameIdx
      val op = binOp.op
      val bpos = binOp.pos
      
      var i = 0
      while (i < outerArr.length) {
        bindings(outerSlot) = outerArr(i)
        
        // Evaluate middle for-spec condition
        visitExpr(middleForSpec.expr)(mutableScope) match {
          case middleArr: Val.Arr =>
            val middleLazy = middleArr.asLazyArray
            
            // Evaluate if condition (invariant across inner loop)
            val ifCond = ifSpec.expr
            if (isInvariantExpr(ifCond, middleSlot)) {
              // Condition doesn't depend on middle var, evaluate once
              visitExpr(ifCond)(mutableScope) match {
                case Val.True(_) =>
                  var j = 0
                  while (j < middleLazy.length) {
                    bindings(middleSlot) = middleLazy(j)
                    val l = bindings(lhsIdx).value
                    val r = bindings(rhsIdx).value
                    l match {
                      case ln: Val.Num => r match {
                        case rn: Val.Num =>
                          results += evalBinaryOpNumNum(op, ln, rn, bpos)
                        case _ => results += visitBinaryOpValues(op, l, r, bpos)
                      }
                      case _ => results += visitBinaryOpValues(op, l, r, bpos)
                    }
                    j += 1
                  }
                case Val.False(_) => // Skip entire middle loop
                case _ => Error.fail("Condition must be boolean", ifSpec.expr.pos)
              }
            } else {
              // Condition depends on middle var, evaluate each iteration
              var j = 0
              while (j < middleLazy.length) {
                bindings(middleSlot) = middleLazy(j)
                visitExpr(ifCond)(mutableScope) match {
                  case Val.True(_) =>
                    val l = bindings(lhsIdx).value
                    val r = bindings(rhsIdx).value
                    l match {
                      case ln: Val.Num => r match {
                        case rn: Val.Num => results += evalBinaryOpNumNum(op, ln, rn, bpos)
                        case _ => results += visitBinaryOpValues(op, l, r, bpos)
                      }
                      case _ => results += visitBinaryOpValues(op, l, r, bpos)
                    }
                  case Val.False(_) => // Skip this iteration
                  case _ => Error.fail("Condition must be boolean", ifSpec.expr.pos)
                }
                j += 1
              }
            }
          case _ => Error.fail("For spec must iterate array", middleForSpec.expr.pos)
        }
        i += 1
      }
    case _ =>
      // Fallback: use generic nested comprehension
      var i = 0
      while (i < outerArr.length) {
        bindings(outerSlot) = outerArr(i)
        visitCompInline(List(middleForSpec) ++ rest, mutableScope, body, results)
        i += 1
      }
  }
}
```

**Benefits**:
- Eliminates recursive `visitCompInline` calls for nested comprehensions
- Single mutable scope instead of allocating new scope per iteration
- **Estimated impact**: 20-30% on comparison2 benchmark (1M iterations = 1000×1000)

---

#### **3b. Size Hint Optimization** (LOW-MEDIUM PRIORITY)
**Problem**: ArrayBuilder size hints sometimes underestimate nested comprehensions (lines 216-222).

```scala
val estimate = math.pow(lazyArr.length.toDouble, 1 + restForCount).toLong
if (estimate > 256 && estimate <= (1 << 22))
  results.sizeHint(estimate.toInt)
```

For comparison2: outer loop is 999 items, inner loop is 999 items = 998,001 results. The formula could be:
- Outer: 999, inner count: 1 → estimate = 999^2 = 998,001 ✓
- With if-spec filtering: estimate might be reduced by filter selectivity

**Recommendation**: Pass filter selectivity through the recursion:
```scala
case IfSpec(_, expr) =>
  // Estimate how many elements this filter retains (sampling?)
  // For now, conservative: assume 50% retention
  resultFilter selectivity = 0.5
```

But this is likely over-optimization.

---

#### **3c. Avoid Lazy Array Materialization in asLazyArray** (MEDIUM PRIORITY)
**Problem**: Line 214: `val lazyArr = a.asLazyArray` materializes range objects.

```scala
// Val.scala, line 350-352
def asLazyArray: Array[Eval] = {
  if (_rangePos != null) materializeRange()  // Allocates Val.Num for each element
  arr.asInstanceOf[Array[Eval]]
}
```

For benchmark's `std.range(1, 1000)`, this creates 999 `Val.Num` objects unnecessarily.

**Recommendation**: Keep range objects lazy throughout comprehension:
```scala
def tryGetRangeValue(i: Int): Val = {
  if (_rangePos != null && i < rangeLen && arr(i) == null) {
    Val.Num(_rangePos, rangeFrom + i)  // Create on-demand, not stored
  } else {
    arr(i).value
  }
}
```

Use in comprehension:
```scala
while (j < lazyArr.length) {
  // Instead of: bindings(slot) = lazyArr(j)
  // Use: bindings(slot) = LazyExpr for range element
  if (a.isRange && j < a.rangeLen) {
    bindings(slot) = new LazyNum(a.rangeFrom + j, a._rangePos)
  } else {
    bindings(slot) = lazyArr(j)
  }
}
```

This requires defining a lightweight `LazyNum` wrapper (~24 bytes vs 56+ for LazyExpr).

**Benefits**:
- For comparison2's 1M iterations, avoids 1M Val.Num allocations
- Estimated impact: 10-15% on comparison2

---

### Data Structure Insights
- **ArrayBuilder**: Pre-sized with sizeHint — good ✓
- **Mutable scope reuse**: Avoids scope allocation per iteration ✓
- **Binary op inline**: Specialized for `[a op b for i in arr]` pattern ✓
- **Non-capturing detection**: isNonCapturingBody check is good filter ✓

---

## 4. Val.Obj Construction

### Current Implementation
**File**: `/Users/hepin/IdeaProjects/sjsonnet/sjsonnet/src/sjsonnet/Val.scala` (lines 417-650)

**Val.Obj structure** (lines 503-518):
```scala
final class Obj(
    var pos: Position,
    private var value0: util.LinkedHashMap[String, Obj.Member],  // Lazy field defs
    private val static: Boolean,
    private val triggerAsserts: (Val.Obj, Val.Obj) => Unit,
    `super`: Obj,
    private var valueCache: util.HashMap[Any, Val] = null,       // Computed values
    private var allKeys: util.LinkedHashMap[String, java.lang.Boolean] = null,
    private val excludedKeys: java.util.Set[String] = null,
    private val staticLayout: StaticObjectLayout = null,
    private val staticValues: Array[Val] = null,
    private val singleFieldKey: String = null,
    private val singleFieldMember: Obj.Member = null,
    private val inlineFieldKeys: Array[String] = null,
    private val inlineFieldMembers: Array[Obj.Member] = null)
    extends Literal with Expr.ObjBody {
  
  // Inline value cache: avoids HashMap allocation for ≤2 cached fields
  private var ck1: Any = null
  private var cv1: Val = null
  private var ck2: Any = null
  private var cv2: Val = null
```

**Key optimizations already in place**:
- **Inline cache for 2 fields** (lines 522-527): Avoids HashMap allocation
- **Static object layout** (lines 89-103 in Materializer): Pre-computed for static objects
- **Value cache** (lines 509): Lazily populated HashMap

**Object comprehension** (Evaluator.scala, lines 1887-1920):
```scala
def visitObjComp(e: ObjBody.ObjComp, sup: Val.Obj)(implicit scope: ValScope): Val.Obj = {
  val binds = e.allLocals
  val compScope: ValScope = scope
  val builder = new java.util.LinkedHashMap[String, Val.Obj.Member]  // Allocate builder
  val compScopes = visitComp(e.first :: e.rest, Array(compScope))   // Get all scopes
  if (debugStats != null) debugStats.objectCompIterations += compScopes.length
  var ci = 0
  while (ci < compScopes.length) {
    val s = compScopes(ci)
    visitExpr(e.key)(s) match {
      case Val.Str(_, k) =>
        val previousValue = builder.put(
          k,
          new Val.Obj.Member(e.plus, Visibility.Normal, deprecatedSkipAsserts = true) {
            def invoke(self: Val.Obj, sup: Val.Obj, fs: FileScope, ev: EvalScope): Val = {
              checkStackDepth(e.value.pos, "object comprehension")
              try {
                lazy val newScope: ValScope = s.extend(newBindings, self, sup)
                lazy val newBindings = visitBindings(binds, newScope)
                visitExpr(e.value)(newScope)
              } finally decrementStackDepth()
            }
          }
        )
        if (previousValue != null) {
          Error.fail(s"Duplicate key $k in evaluated object comprehension.", e.pos)
        }
      ...
    }
    ci += 1
  }
  new Val.Obj(e.pos, builder, false, null, sup)
}
```

### Optimization Opportunities

#### **4a. Optimize Val.Obj.Member Allocation in Object Comprehensions** (MEDIUM PRIORITY)
**Problem**: Each key-value pair in an object comprehension allocates a new anonymous `Val.Obj.Member` subclass instance (lines 1900-1909).

For `realistic2` (50ms) with large object builds, this creates many small allocations (~120 bytes each for anonymous inner class).

**Current**:
```scala
new Val.Obj.Member(e.plus, Visibility.Normal, deprecatedSkipAsserts = true) {
  def invoke(self: Val.Obj, sup: Val.Obj, fs: FileScope, ev: EvalScope): Val = { ... }
}
```

**Recommendation**: Create a reusable `LazyMember` class:
```scala
final class LazyMember(
    add: Boolean,
    visibility: Visibility,
    val scope: ValScope,
    val binds: Array[Expr],
    val expr: Expr,
    val evaluator: Evaluator)
    extends Val.Obj.Member(add, visibility, deprecatedSkipAsserts = true) {
  
  def invoke(self: Val.Obj, sup: Val.Obj, fs: FileScope, ev: EvalScope): Val = {
    checkStackDepth(expr.pos, "object comprehension")
    try {
      val newScope = scope.extend(visitBindings(binds, scope), self, sup)
      evaluator.visitExpr(expr)(newScope)
    } finally {
      ev.asInstanceOf[Evaluator].decrementStackDepth()
    }
  }
}
```

Then reuse:
```scala
new LazyMember(e.plus, Visibility.Normal, s, binds, e.value, this)
```

**Benefits**:
- Reduces per-member allocation from ~120B (anonymous class) to ~88B (named class)
- Better GC pressure
- Estimated impact: 5-10% on object-heavy workloads like realistic2

---

#### **4b. Pre-allocate LinkedHashMap with Correct Size** (LOW-MEDIUM PRIORITY)
**Problem**: Line 1890 allocates LinkedHashMap without size hint:
```scala
val builder = new java.util.LinkedHashMap[String, Val.Obj.Member]()
```

For comprehensions with many keys, this causes resizes.

**Recommendation**:
```scala
val builder = Util.preSizedJavaLinkedHashMap[String, Val.Obj.Member](compScopes.length)
```

**Benefits**:
- Avoid HashMap resizes during insertion
- Estimated impact: 2-5% on large object comprehensions

---

#### **4c. Optimize addSuper for Object Merging** (MEDIUM PRIORITY)
**Problem**: Object merge (`lo + ro`) calls `addSuper` (lines 1242, Val.scala 607-650), which builds an array of the super chain and reconstructs it.

**Current logic** (Val.scala, lines 607-650):
```scala
def addSuper(pos: Position, lhs: Val.Obj): Val.Obj = {
  // Fast path: no super chain
  if (getSuper == null) {
    val filteredExcluded = if (excludedKeys != null) {
      Util.intersect(excludedKeys, getValue0.keySet())
    } else null
    return new Val.Obj(
      this.pos, this.getValue0, false, this.triggerAsserts, lhs,
      null, null, filteredExcluded
    )
  }
  
  // Slow path: walk super chain, rebuild
  val builder = new mutable.ArrayBuilder.ofRef[Val.Obj]
  var current = this
  while (current != null) {
    builder += current
    current = current.getSuper
  }
  val chain = builder.result()
  
  // ... (rebuild super chain with new root) ...
}
```

**Problem**: For chains like `a + b + c + d`, this does O(chain_length) work per merge:
- `(a + b)` walks a's super chain, rebuilds it with b as root
- `((a+b) + c)` walks the result's super chain again, rebuilds with c as root
- Total: O(n²) work for n merges

**Recommendation**: Flatten super chain references during merge:
```scala
def addSuper(pos: Position, lhs: Val.Obj): Val.Obj = {
  // Fast path: no super, just create new root
  if (getSuper == null) {
    val filteredExcluded = if (excludedKeys != null) {
      Util.intersect(excludedKeys, getValue0.keySet())
    } else null
    return new Val.Obj(
      this.pos, this.getValue0, false, this.triggerAsserts, lhs,
      null, null, filteredExcluded
    )
  }
  
  // Direct merge without rebuilding chain
  return new Val.Obj(
    this.pos, this.getValue0, false, this.triggerAsserts, 
    new ChainedSuper(lhs, getSuper),  // Lazy chain representation
    null, null, excludedKeys
  )
}

// New class for lazy super chain chaining
final class ChainedSuper(val immediate: Val.Obj, val next: Val.Obj) extends Val.Obj(
  immediate.pos, null, false, null, next
) {
  override def getValue0: util.LinkedHashMap[String, Val.Obj.Member] = {
    // On first access, flatten the chain and update
    if (value0 == null) {
      value0 = immediate.getValue0
    }
    value0
  }
  
  override def getSuper: Val.Obj = next
}
```

**Benefits**:
- Avoids O(n²) rebuilds for long merge chains
- Estimated impact: Minimal for typical cases, but crucial for edge cases

---

#### **4d. Inline Cache Hit Rate Analysis** (LOW PRIORITY)
The inline cache for 2 fields (ck1/cv1, ck2/cv2) was added to avoid HashMap allocations.

**Observation**: For bench.02 (object fibonacci), this eliminates ~242K HashMap allocations.

This is already well-optimized. No changes needed.

---

### Data Structure Insights
- **Inline cache**: Two-field optimization is good ✓
- **Static object layout**: Pre-computed values avoid lazy eval ✓
- **LinkedHashMap**: Good choice for maintaining insertion order
- **Member abstraction**: Abstract class with invoke method — good dispatch pattern ✓

---

## 5. Import/Parse Caching

### Current Implementation
**File**: `/Users/hepin/IdeaProjects/sjsonnet/sjsonnet/src/sjsonnet/Importer.scala` (lines 182-247)

**CachedImporter** (lines 182-195):
```scala
class CachedImporter(parent: Importer) extends Importer {
  val cache: mutable.HashMap[Path, ResolvedFile] = mutable.HashMap.empty[Path, ResolvedFile]

  def resolve(docBase: Path, importName: String): Option[Path] = parent.resolve(docBase, importName)

  def read(path: Path, binaryData: Boolean): Option[ResolvedFile] = cache.get(path) match {
    case s @ Some(x) =>
      if (x == null) None else s
    case None =>
      val x = parent.read(path, binaryData)
      cache.put(path, x.orNull)
      x
  }
}
```

**CachedResolver** (lines 197-247):
```scala
class CachedResolver(
    parentImporter: Importer,
    val parseCache: ParseCache,
    internedStrings: mutable.HashMap[String, String],
    internedStaticFieldSets: Val.StaticObjectLayoutCache,
    settings: Settings = Settings.default)
    extends CachedImporter(parentImporter) {

  def parse(path: Path, content: ResolvedFile)(implicit
      ev: EvalErrorScope): Either[Error, (Expr, FileScope)] = {
    parseCache.getOrElseUpdate(
      (path, content.contentHash()),  // Key: (Path, String hash)
      {
        val parsed = fastparse.parse(...)
        parsed.flatMap { case (e, fs) => process(e, fs) }
      }
    )
  }
}
```

**DefaultParseCache** (ParseCache.scala, lines 28-48):
```scala
class DefaultParseCache extends ParseCache {
  val cache = new scala.collection.mutable.HashMap[(Path, String), Either[Error, (Expr, FileScope)]]()

  override def getOrElseUpdate(
      key: (Path, String),
      defaultValue: => Either[Error, (Expr, FileScope)]): Either[Error, (Expr, FileScope)] = {
    cache.getOrElseUpdate(key, defaultValue)
  }
}
```

**Import evaluation** (Evaluator.scala, lines 1146-1163):
```scala
def visitImport(e: Import): Val = {
  val (p, str) = importer.resolveAndReadOrFail(e.value, e.pos, binaryData = false)
  val cached = cachedImports.contains(p)  // Check cache presence
  if (debugStats != null && cached) debugStats.importCacheHits += 1
  cachedImports.getOrElseUpdate(
    p, {
      if (debugStats != null) debugStats.importCalls += 1
      checkStackDepth(e.pos, e)
      try {
        val doc = resolver.parse(p, str) match {
          case Right((expr, _)) => expr
          case Left(err)        => throw err.asSeenFrom(this)
        }
        visitExpr(doc)(ValScope.empty)
      } finally decrementStackDepth()
    }
  )
}
```

### Optimization Opportunities

#### **5a. Avoid Double Hash Lookup in ParseCache** (MEDIUM PRIORITY)
**Problem**: Lines 1148-1150 do a contains check before getOrElseUpdate:

```scala
val cached = cachedImports.contains(p)
if (debugStats != null && cached) debugStats.importCacheHits += 1
cachedImports.getOrElseUpdate(p, { ... })  // Second lookup
```

This does **two HashMap lookups** for every cached import.

**Recommendation**: Use a single lookup:
```scala
cachedImports.get(p) match {
  case Some(v) =>
    if (debugStats != null) debugStats.importCacheHits += 1
    v
  case None =>
    if (debugStats != null) debugStats.importCalls += 1
    checkStackDepth(e.pos, e)
    try {
      val result = resolver.parse(p, str) match {
        case Right((expr, _)) => expr
        case Left(err)        => throw err.asSeenFrom(this)
      }
      cachedImports.put(p, visitExpr(result)(ValScope.empty))
      cachedImports(p)
    } finally decrementStackDepth()
}
```

**Benefits**:
- Eliminates one HashMap lookup per import access
- Better for import-heavy programs
- Estimated impact: 2-5% if imports are frequent

---

#### **5b. Hash Key Concatenation Cost** (MEDIUM PRIORITY)
**Problem**: ParseCache uses `(path, content.contentHash())` as key (line 208).

The `contentHash()` method likely reads and hashes the file content. This is called **before checking the cache**:
```scala
parseCache.getOrElseUpdate(
  (path, content.contentHash()),  // ← Hash computed eagerly
  { ... }
)
```

If the parse is cached, the hash computation was wasted.

**Recommendation**: Lazy hash computation:
```scala
trait ParseCacheKey {
  def path: Path
  def contentHashLazy: () => String
  
  override def hashCode: Int = path.hashCode
  override def equals(obj: Any): Boolean = obj match {
    case other: ParseCacheKey =>
      this.path == other.path && this.contentHashLazy() == other.contentHashLazy()
    case _ => false
  }
}

parseCache.getOrElseUpdate(
  new ParseCacheKey {
    val path = p
    val contentHashLazy = () => content.contentHash()
  },
  { ... }
)
```

But this adds complexity. **Better**: Change cache key strategy:
```scala
// Cache key is just Path; store (hash, parsed) in value
val parseCache: mutable.HashMap[Path, Option[(String, Either[Error, (Expr, FileScope)])]] = ...

def getCached(path: Path, content: ResolvedFile): Option[Either[Error, (Expr, FileScope)]] = {
  parseCache.get(path).flatMap { case (cachedHash, result) =>
    if (cachedHash == content.contentHash()) Some(result) else None
  }
}
```

But this still hashes on miss. **Conclusion**: Current design is acceptable; avoid over-optimization.

---

#### **5c. Interned Strings Cache Efficiency** (LOW PRIORITY)
**Problem**: ParseCache stores `internedStrings` (line 200), but it's not clear when interning helps.

```scala
internedStrings: mutable.HashMap[String, String]
```

For typical workloads with many imports of the same file, string interning helps reduce memory, but:
- HashMap lookup overhead on every string key
- String.intern() in Java is already optimized

**Recommendation**: Monitor if this is actually used. If string deduplication isn't critical, remove it.

---

#### **5d. Content Hash Caching** (LOW PRIORITY)
**Problem**: `content.contentHash()` (line 208) is called on every parse check. If contentHash involves reading the file again, this is wasteful.

**Observation**: ResolvedFile should cache its content hash.

**Recommendation**: Ensure ResolvedFile caches hash:
```scala
trait ResolvedFile {
  def getParserInput(): ParserInput
  
  private var _contentHash: String = null
  def contentHash(): String = {
    if (_contentHash == null) {
      _contentHash = computeHash(getParserInput().readString())
    }
    _contentHash
  }
}
```

Assume this is already done ✓

---

### Data Structure Insights
- **Two-level cache**: CachedImporter (file reads) + CachedResolver (parsing) ✓ Good
- **HashMap lookup**: Single-key lookup (`Path`) in evaluator cache ✓ Good
- **Tuple2 key**: `(Path, String)` in ParseCache — acceptable
- **orNull sentinel**: Using null as "not found" marker (line 192) — OK but unconventional

---

## Summary of Optimizations by Impact

### Tier 1 (HIGH - 10-30% impact)
1. **3a. Three-level comprehension optimization** (comparison2: 20-30%)
2. **1a. Avoid redundant comparison type checks** (comparison2: 5-10%)
3. **3c. Lazy range element allocation** (comparison2: 10-15%)

### Tier 2 (MEDIUM - 5-10% impact)
4. **4a. Optimize Val.Obj.Member allocation** (realistic2: 5-10%)
5. **1b. Extract concat operation** (BinaryOp cleanup, 5-10%)
6. **5a. Avoid double hash lookup** (import-heavy: 2-5%)
7. **3b. Size hint optimization** (nested comprehension: 2-5%)

### Tier 3 (MEDIUM-LOW - 2-5% impact)
8. **1c. Inline visitBinaryOpAsDouble for hot path** (fibonacci: 2-5%)
9. **4b. Pre-allocate LinkedHashMap** (object-heavy: 2-5%)
10. **4c. Optimize addSuper chain** (merge-heavy edge cases)

### Tier 4 (LOW - <2% impact or exploratory)
11. **2a. String builder pool** (unlikely to help; Materializer already optimized)
12. **5c & 5d**: Cache efficiency (already likely optimized)

---

## Recommended Implementation Order

**Phase 1** (Week 1 - High ROI):
1. **3a** - Three-level comprehension (comparison2 +20-30%)
2. **1a** - Deduplicate comparisons (comparison2 +5-10%)
3. **3c** - Lazy range elements (comparison2 +10-15%)

**Phase 2** (Week 2 - Medium ROI):
4. **4a** - Optimize Val.Obj.Member (realistic2 +5-10%)
5. **1b** - Extract concat (cleanup + slight perf)

**Phase 3** (Week 3 - Polish):
6. **5a** - Double hash lookup (import-heavy +2-5%)
7. **1c** - Inline numeric fast path (fibonacci +2-5%)

---

## Testing Strategy

1. Run benchmarks for each optimization:
   ```bash
   ./benchmark.sh bench.02 bench.03 comparison2 reverse realistic2
   ```

2. Profile with JFR to confirm allocation reductions:
   ```bash
   jcmd Benchmark JFR.start settings=profile duration=10s filename=profile.jfr
   ```

3. Check for regressions in test suite after each change.

---

## Conclusion

The sjsonnet evaluator is well-optimized in most areas (numeric fast paths, scope reuse, inline caching). The main opportunities are:

1. **Comprehension optimization** for nested loops (comparison2)
2. **Code deduplication** in comparison operators
3. **Lazy allocation** for array ranges
4. **Object construction** efficiency improvements

Implementing the Tier 1 optimizations could yield **25-50% improvement** on comparison-heavy and comprehension-heavy benchmarks.

___BEGIN___COMMAND_DONE_MARKER___0
