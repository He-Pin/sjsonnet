local n = 5001;
local made = std.makeArray(n, function(i) std.trace('lazy-array-make-reverse-cache ' + i, i));
local base = std.makeArray(n, function(i) i);
local mapped = std.map(function(x) std.trace('lazy-array-map-reverse-cache ' + x, x + 1), base);
local indexed =
  std.mapWithIndex(function(i, x) std.trace('lazy-array-mapWithIndex-reverse-cache ' + i, i + x), base);

std.assertEqual([made[0], std.reverse(made)[n - 1]], [0, 0]) &&
std.assertEqual([mapped[0], std.reverse(mapped)[n - 1]], [1, 1]) &&
std.assertEqual([indexed[0], std.reverse(indexed)[n - 1]], [0, 0]) &&
true
