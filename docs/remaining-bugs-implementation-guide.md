# sjsonnet Remaining Bugs - Implementation Guide

**审计时间**: 2026-06-24  
**审计规模**: 10 轮，50+ agents，2200+ 测试场景  
**对比实现**: sjsonnet vs C++ jsonnet vs go-jsonnet vs jrsonnet

---

## Bug 列表

### BUG-001: WASM 构建零外部导出 (P0)

**严重度**: P0 - 关键功能缺失  
**影响**: WASM 构建无法作为外部库使用

#### 问题描述

WASM 模块 `out/sjsonnet/wasm/3.3.7/fullLinkJS.dest/main.js` 导出 `SjsonnetMain` 为空对象，无法访问 `interpret` 和 `interpretAsync` 方法。

**复现代码**:
```javascript
const mod = await import('out/sjsonnet/wasm/3.3.7/fullLinkJS.dest/main.js');
console.log(mod.SjsonnetMain);        // {} (空对象)
console.log(mod.SjsonnetMain.interpret); // undefined
```

**根因**: `/sjsonnet/src-js/sjsonnet/SjsonnetMain.scala` 中的 `@JSExportTopLevel("SjsonnetMain")` 注解在 Scala.js WASM 后端中无法产生 WebAssembly 导出。这是 Scala.js WASM 后端的限制。

**修复方向**:
- 研究 Scala.js WASM 后端的导出机制
- 可能需要使用不同的导出注解或方法
- 参考 Scala.js WASM 文档：https://www.scala-js.org/doc/project/cross-build.html

**测试方法**:
```bash
./mill 'sjsonnet.wasm[3.3.7]'.fullLinkJS
node -e "import('out/sjsonnet/wasm/3.3.7/fullLinkJS.dest/main.js').then(m => console.log(Object.keys(m.SjsonnetMain)))"
```

**预期结果**: 应导出 `interpret` 和 `interpretAsync` 方法

---

### BUG-002: CLI 错误处理改进 (P2)

**严重度**: P2 - 用户体验改进  
**影响**: 11 个 CLI 错误处理问题

#### 问题列表

##### 2.1 未定义环境变量 NullPointerException

**文件**: `sjsonnet/src-jvm-native/sjsonnet/SjsonnetMainBase.scala:359/369`

**复现**:
```bash
java -jar out.jar --ext-str NONEXISTENT_VAR -e 'std.extVar("NONEXISTENT_VAR")'
```

**当前行为**: `java.lang.NullPointerException: Cannot invoke "java.lang.CharSequence.toString()" because "s" is null`

**期望行为** (go-jsonnet): `ERROR: environment variable NONEXISTENT_VAR was undefined`

**根因**: `parseBindings` 中 `System.getenv(s)` 返回 null，传递给 `ujson.write(v)` 时抛出 NPE。

**修复**: 检查 null 并返回描述性错误。

---

##### 2.2 --ext-code 未定义环境变量误导性错误

**文件**: `sjsonnet/src-jvm-native/sjsonnet/SjsonnetMainBase.scala:359/371`

**复现**:
```bash
java -jar out.jar --ext-code NONEXISTENT_VAR -e 'std.extVar("NONEXISTENT_VAR")'
```

**当前行为**: `sjsonnet.Error: [std.extVar] Unsupported external variable kind: code`

**期望行为**: `ERROR: environment variable NONEXISTENT_VAR was undefined`

**根因**: `System.getenv(s)` 返回 null，`identity(null)` 返回 null，创建 `ExternalVariable(Code, null)`。Scala 模式匹配 `case ExternalVariable(ExternalVariableKind.Code, v: String)` 失败（String 类型不匹配 null），落入默认 case。

**修复**: 检查 null 并返回描述性错误。

---

##### 2.3 --ext-str-file 文件不存在时原始 Java 异常

**文件**: `sjsonnet/src-jvm-native/sjsonnet/SjsonnetMainBase.scala:366/370`

**复现**:
```bash
java -jar out.jar --ext-str-file missing=/tmp/nonexistent.txt -e 'std.extVar("missing")'
```

**当前行为**: `java.nio.file.NoSuchFileException: /tmp/nonexistent.txt` (完整 Java 堆栈)

**期望行为**: `RUNTIME ERROR: couldn't open import "/tmp/nonexistent.txt": ...`

**根因**: `os.read()` 抛出 `NoSuchFileException` 未被捕获。

**修复**: 捕获异常并转换为干净的错误消息。

---

##### 2.4 输入文件不存在时原始 Java 异常

**文件**: `sjsonnet/src-jvm-native/sjsonnet/SjsonnetMainBase.scala:407`

**复现**:
```bash
java -jar out.jar /nonexistent/file.jsonnet
```

**当前行为**: `java.nio.file.NoSuchFileException` (完整 Java 堆栈)

**期望行为**: `Opening input file: /nonexistent/file.jsonnet: no such file or directory`

**根因**: `os.read(p)` 调用未被 try/catch 包装。

**修复**: 包装在 try/catch 中并转换为干净的错误消息。

---

##### 2.5 输出文件目录不存在时原始 Java 异常

**文件**: `sjsonnet/src-jvm-native/sjsonnet/SjsonnetMainBase.scala:320`

**复现**:
```bash
java -jar out.jar -o /tmp/nonexistent/dir/output.json -e '{a:1}'
```

**当前行为**: `java.nio.file.NoSuchFileException` (完整 Java 堆栈)

**期望行为**: `open /tmp/nonexistent/dir/output.json: no such file or directory`

**修复**: 捕获异常并转换为干净的错误消息。

---

##### 2.6 --string 输出非字符串时原始堆栈跟踪

**文件**: `sjsonnet/src-jvm-native/sjsonnet/SjsonnetMainBase.scala:259/339-341`

**复现**:
```bash
java -jar out.jar --string -e '{a:1}'
```

**当前行为**: 堆栈跟踪 "Internal error: upickle.core.Abort: expected string result got dictionary"

**期望行为**: `RUNTIME ERROR: expected string result, got: object`

**修复**: 捕获 upickle Abort 并转换为干净的错误消息。

---

##### 2.7 -V 短标志被三个参数共享

**文件**: `sjsonnet/src-jvm-native/sjsonnet/Config.scala:50/62/86`

**问题**: `-V` 短标志分配给三个不同参数：
- `extStr` (line 50)
- `extCode` (line 62)
- `tlaCode` (line 86)

**期望行为** (go-jsonnet): `-V` 仅映射到 `--ext-str`

**修复**: 移除 `extCode` 和 `tlaCode` 的 `-V` 短标志。

---

##### 2.8 不支持多个 -e 标志

**复现**:
```bash
java -jar out.jar -e '{a:1}' -e '{b:2}'
```

**当前行为**: 第二个 `-e` 被当作位置参数

**期望行为**: `ERROR: only one code is allowed`

**修复**: 检查多个 `-e` 标志并返回清晰的错误。

---

##### 2.9 无 --version 标志

**问题**: sjsonnet 不支持 `--version`

**期望行为** (go-jsonnet): 支持 `--version`

**修复**: 添加 `--version` 标志支持。

---

##### 2.10 无 -- 结束选项处理

**问题**: sjsonnet 不识别 `--` 作为选项结束标记

**复现**:
```bash
java -jar out.jar -- -file.jsonnet
```

**当前行为**: 将 `-file.jsonnet` 当作选项

**期望行为**: 将 `-file.jsonnet` 当作文件名

**修复**: 添加 `--` 选项结束标记支持。

---

##### 2.11 --yaml-stream 输出格式不兼容

**注意**: 此问题已在 PR #1026 修复，但可能需要进一步改进。

**问题**: sjsonnet 的 `--yaml-stream` 输出与 go-jsonnet 有显著差异：
1. sjsonnet 将文档渲染为 YAML；go-jsonnet 渲染为 JSON
2. sjsonnet 不在第一个文档前添加 `---`
3. sjsonnet 不在末尾添加 `...` 终止符
4. sjsonnet 不要求顶层是数组（go-jsonnet 要求）

**修复**: 参考 PR #1026 的实现，可能需要进一步调整以完全兼容 go-jsonnet。

---

### BUG-003: 自引用局部变量导致无限惰性求值 (P1)

**严重度**: P1 - 语义错误  
**影响**: 自引用局部变量 + 嵌套数组导致无限求值

#### 问题描述

**复现**:
```jsonnet
local a = 1;
local b = local a = 2; local c = local a = 3; [a, c]; [a, b]; [a, b]
```

**当前行为**: 无限求值，输出无限嵌套的 `[2, [2, [2, ...]]]`，最终 JVM 栈溢出或内存耗尽

**期望行为** (C++/go-jsonnet): `RUNTIME ERROR: max stack frames exceeded.`（快速检测）

**根因**: 
- `checkStackDepth()` 在 `Evaluator.scala` (line 41-59) 仅在函数调用边界调用
- 惰性求值链 `visitValidId -> visitExpr -> visitBinaryOp -> LazyExpr.value -> visitValidId` 不经过 `checkStackDepth`
- 迭代物化器（`Materializer.scala` line 94-98）在深度 128 切换到迭代模式，但这不防止惰性求值链中的无限循环

**修复方向**:
1. 在 `LazyExpr.value()` (`Val.scala` line 77) 添加 `checkStackDepth` 调用
2. 或在 `visitLocalExpr` 和/或 `visitValidId` 添加检查
3. 迭代物化器应有总深度上限（不仅仅是递归到迭代的切换阈值）

**文件**:
- `sjsonnet/src/sjsonnet/Evaluator.scala` (lines 41-59, 315, 307)
- `sjsonnet/src/sjsonnet/Val.scala` (line 77)
- `sjsonnet/src/sjsonnet/Materializer.scala` (lines 44, 93-98, 296-352)
- `sjsonnet/src/sjsonnet/Settings.scala` (line 14: `materializeRecursiveDepthLimit = 128`)

**测试用例**:
```jsonnet
// 测试 1: 自引用局部变量
local x = x + 1; x

// 测试 2: 自引用 + 嵌套数组
local a = 1;
local b = local a = 2; local c = local a = 3; [a, c]; [a, b]; [a, b]

// 测试 3: 互递归
local even(n) = if n == 0 then true else odd(n-1);
local odd(n) = if n == 0 then false else even(n-1);
[even(10), odd(10)]
```

**预期结果**: 所有测试应快速失败并返回清晰的错误消息

---

## 实现优先级

1. **BUG-001** (WASM 导出) - P0，关键功能
2. **BUG-003** (自引用惰性求值) - P1，语义错误
3. **BUG-002** (CLI 错误处理) - P2，用户体验改进

## 测试方法

每个 bug 修复后应：
1. 添加回归测试到 `sjsonnet/test/resources/new_test_suite/`
2. 运行完整测试套件：`./mill 'sjsonnet.jvm[3.3.7]'.test`
3. 验证行为匹配 C++ jsonnet 和 go-jsonnet
4. 创建 PR 并包含跨实现对比表

## 参考资源

- Jsonnet 官方规范: https://jsonnet.org/ref/spec.html
- C++ jsonnet: https://github.com/google/jsonnet
- go-jsonnet: https://github.com/google/go-jsonnet
- jrsonnet: https://github.com/CertainLach/jrsonnet
- Scala.js WASM 文档: https://www.scala-js.org/doc/project/cross-build.html

---

**文档生成时间**: 2026-06-24  
**审计工具**: 50+ 并行 agents，2200+ 测试场景  
**对比实现**: sjsonnet vs C++ jsonnet v0.20.0 vs go-jsonnet v0.20.0 vs jrsonnet v0.5.1
