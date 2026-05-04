local n = 200000;
local probes = 20000;
local made = std.makeArray(n, function(i) i + 1);
local mapped = std.map(function(x) x * 3 + 1, made);
local indexed = std.mapWithIndex(function(i, x) i + x, mapped);

std.foldl(
  function(acc, i)
    local idx = (i * 7919) % n;
    acc + mapped[idx] + indexed[idx],
  std.range(0, probes - 1),
  0
)
