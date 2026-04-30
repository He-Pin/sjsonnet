local haystack = std.repeat("abcXYZ", 1000);
local hits = [std.findSubstr("XYZ", haystack) for _ in std.range(0, 100)];
{
  total_hits: std.foldl(function(acc, x) acc + std.length(x), hits, 0),
  first: hits[0][0],
  last: hits[100][999],
}
