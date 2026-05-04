local seed = 'sjsonnet-π-你好-🙂';
local payload = std.repeat(seed, 4096);

[
  std.md5(payload),
  std.sha1(payload),
  std.sha256(payload),
  std.sha512(payload),
  std.sha3(payload),
  std.md5(payload + 'x'),
  std.sha1(payload + 'x'),
  std.sha256(payload + 'x'),
  std.sha512(payload + 'x'),
  std.sha3(payload + 'x'),
]
