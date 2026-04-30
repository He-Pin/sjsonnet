// Directional tests for array-level lazy views used by std.map, std.mapWithIndex,
// and std.makeArray. The important property is selective forcing: indexing one
// element must not allocate per-element thunks or evaluate the whole result array.
local mapped = std.map(function(x) if x == 0 then error 'forced map zero' else x + 10, [0, 1, 2]);
local indexed =
  std.mapWithIndex(function(i, x) if i == 0 then error 'forced index zero' else i + x, [10, 20, 30]);
local made = std.makeArray(100000, function(i) if i == 0 then error 'forced makeArray zero' else i + 1);
local chain = std.map(function(x) x + 1, std.map(function(x) x * 2, std.makeArray(50000, function(i) i)));
local withIdx = std.mapWithIndex(function(i, x) i * 10 + x, std.range(1, 3));

std.assertEqual(mapped[1], 11) &&
std.assertEqual(indexed[2], 32) &&
std.assertEqual(made[99999], 100000) &&
std.assertEqual(chain[49999], 99999) &&
std.assertEqual(std.reverse(withIdx), [23, 12, 1]) &&
std.assertEqual(std.foldl(function(acc, x) acc + x, std.makeArray(1000, function(i) i), 0), 499500) &&
true
