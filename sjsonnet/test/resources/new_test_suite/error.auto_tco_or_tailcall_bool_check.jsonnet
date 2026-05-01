// The rhs returns through a TailCall, so the boolean check must be delayed until
// the trampoline finishes. This should match direct `false || 0` behavior.
local f(n) =
  if n <= 0 then 0
  else false || f(n - 1);
f(1000)
