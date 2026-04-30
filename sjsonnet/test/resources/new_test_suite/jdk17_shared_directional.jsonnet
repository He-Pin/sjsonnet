// Directional tests for platform-specialized string helpers.
// These assert exact outputs, not only roundtrips, so consistently-wrong implementations fail.

std.assertEqual(std.repeat("é", 3), "ééé") &&

std.assertEqual("%-5s" % "x", "x    ") &&
std.assertEqual("%010x" % 255, "00000000ff") &&

std.assertEqual(std.split("a::::b::", "::"), ["a", "", "b", ""]) &&
std.assertEqual(std.split("aaaa", "aa"), ["", "", ""]) &&
std.assertEqual(std.splitLimit("a::b::c", "::", 0), ["a::b::c"]) &&
std.assertEqual(std.splitLimitR("a::b::c", "::", 1), ["a::b", "c"]) &&

std.assertEqual(std.resolvePath("a/b/", "d.libsonnet"), "a/b/d.libsonnet")
