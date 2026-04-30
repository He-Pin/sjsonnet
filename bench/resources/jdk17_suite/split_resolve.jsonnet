local parts = std.makeArray(512, function(i) 'segment' + i);
local joined = std.join('::', parts);
local files = std.makeArray(512, function(i) 'a/b/c/file' + i + '.jsonnet');
local split = std.split(joined, '::');
local splitLimit = std.splitLimit(joined, '::', 128);
local splitLimitR = std.splitLimitR(joined, '::', 128);
local resolved = std.join('|', [std.resolvePath(f, 'libsonnet/main.libsonnet') for f in files]);

{
  splitLen: std.length(split),
  splitJoinLen: std.length(std.join('', split)),
  splitLimitLen: std.length(splitLimit),
  splitLimitLastLen: std.length(splitLimit[128]),
  splitLimitRLen: std.length(splitLimitR),
  splitLimitRFirstLen: std.length(splitLimitR[0]),
  resolveLen: std.length(resolved),
  resolveTail: std.substr(resolved, std.length(resolved) - 30, 30),
}
