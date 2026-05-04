local values = std.range(0, 512);
local words = std.makeArray(128, function(i) 'w' + i);
local repeated = std.repeat('ab', 4096);
local decimal = std.join('|', ['%08d' % v for v in values]);
local hex = std.join('|', ['%010x' % v for v in values]);
local left = std.join('|', ['%-20s' % w for w in words]);
local right = std.join('|', ['%20s' % w for w in words]);

{
  repeatedLen: std.length(repeated),
  decimalLen: std.length(decimal),
  decimalTail: std.substr(decimal, std.length(decimal) - 8, 8),
  hexLen: std.length(hex),
  hexTail: std.substr(hex, std.length(hex) - 10, 10),
  leftLen: std.length(left),
  leftTail: std.substr(left, std.length(left) - 20, 20),
  rightLen: std.length(right),
  rightTail: std.substr(right, std.length(right) - 20, 20),
}
