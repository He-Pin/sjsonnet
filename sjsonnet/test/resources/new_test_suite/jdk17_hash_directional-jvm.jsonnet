// JVM/Native-only directional hash vectors. Scala.js intentionally does not implement these APIs.

local s = "sjsonnet-π-你好-🙂";

std.assertEqual(std.sha256(s), "d42b7a53590c05dc117ea4cfc8dd0a52670e125ee505dc9a280205081a560d84")
