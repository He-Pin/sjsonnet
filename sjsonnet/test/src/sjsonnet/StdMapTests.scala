package sjsonnet

import utest._
import TestUtils.eval

object StdMapTests extends TestSuite {
  def tests: Tests = Tests {
    test("stdMap") {
      eval("std.map(function(x) x * x, [])") ==> ujson.Arr()
      eval("std.map(function(x) x * x, [1, 2, 3, 4])") ==> ujson.Arr(1, 4, 9, 16)

      // Map accepts strings as well, interpreting it as an array of one-character strings
      eval("std.map(function(x) x + x, 'Hello')") ==> ujson.Arr("HH", "ee", "ll", "ll", "oo")

      // Test lazy evaluation
      eval("std.map(function(x) assert x != 'A'; x + x, 'AB')[1]") ==> ujson.Str("BB")
      eval("std.map(function(x) x, [error 'unused', 1])[1]") ==> ujson.Num(1)
      eval("local f = error 'unused'; std.map(function(x) f(f(x)), [])") ==> ujson.Arr()

      // Test returning arbitrary values from the mapping function
      eval("std.map(function(x) std.codepoint(x), 'AB')") ==> ujson.Arr(65, 66)
    }

    test("stdMapWithKey ignores hidden fields") {
      eval("""std.mapWithKey(function(k, v) v + 1, {a: 1, b:: 2, c::: 3})""") ==>
      ujson.Obj("a" -> 2, "c" -> 4)

      eval("""std.objectFieldsAll(std.mapWithKey(function(k, v) v + 1, {a: 1, b:: 2}))""") ==>
      ujson.Arr("a")

      eval("""
             |std.mapWithKey(
             |  function(k, v) if k == "hidden" then error "hidden field mapped" else v,
             |  {visible: 1, hidden:: error "hidden field evaluated"}
             |)
             |""".stripMargin) ==> ujson.Obj("visible" -> 1)
    }
  }
}
