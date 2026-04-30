local n = 200000;
local made = std.makeArray(n, function(i) i);
local mapped = std.map(function(x) x * 2 + 1, made);

std.foldl(function(acc, x) acc + x, [x + 1 for x in mapped], 0)
