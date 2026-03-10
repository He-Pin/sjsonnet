package sjsonnet

/**
 * Minimal re-implementation of Python's `%` formatting logic, since Jsonnet's `%` formatter is
 * basically "do whatever python does", with a link to:
 *
 *   - https://docs.python.org/2/library/stdtypes.html#string-formatting
 *
 * Parses the formatted strings into a sequence of literal strings separated by `%` interpolations
 * modelled as structured [[Format.FormatSpec]]s, and use those to decide how to inteprolate the
 * provided Jsonnet [[Val]]s into the final string.
 */
object Format {
  private type ParsedFormat = RuntimeFormat
  private val ParsedFormatCacheMaxEntries = 256
  private val parsedFormatCache =
    new java.util.LinkedHashMap[String, ParsedFormat](ParsedFormatCacheMaxEntries, 0.75f, true) {
      override def removeEldestEntry(
          eldest: java.util.Map.Entry[String, ParsedFormat]
      ): Boolean = {
        size() > ParsedFormatCacheMaxEntries
      }
    }
  private final case class RuntimeFormat(
      leading: String,
      specs: Array[FormatSpec],
      literals: Array[String],
      hasAnyStar: Boolean,
      staticChars: Int)
  final case class FormatSpec(
      label: Option[String],
      alternate: Boolean,
      zeroPadded: Boolean,
      leftAdjusted: Boolean,
      blankBeforePositive: Boolean,
      signCharacter: Boolean,
      width: Option[Int],
      widthStar: Boolean,
      precision: Option[Int],
      precisionStar: Boolean,
      conversion: Char) {
    def updateWithStarValues(newWidth: Option[Int], newPrecision: Option[Int]): FormatSpec = {
      this.copy(
        width = newWidth.orElse(this.width),
        widthStar = newWidth.isDefined || this.widthStar,
        precision = newPrecision.orElse(this.precision),
        precisionStar = newPrecision.isDefined || this.precisionStar
      )
    }
  }
  import fastparse._, NoWhitespace._
  def integer[$: P]: P[Unit] = P(CharIn("1-9") ~ CharsWhileIn("0-9", 0) | "0")
  def label[$: P]: P[Option[String]] = P(("(" ~ CharsWhile(_ != ')', 0).! ~ ")").?)
  def flags[$: P]: P[String] = P(CharsWhileIn("#0\\- +", 0).!)
  def width[$: P]: P[Option[String]] = P((integer | "*").!.?)
  def precision[$: P]: P[Option[String]] = P(("." ~/ (integer | "*").!).?)
  def conversion[$: P]: P[String] = P(CharIn("diouxXeEfFgGcrsa%").!)
  def formatSpec[$: P]: P[FormatSpec] = P(
    label ~ flags ~ width ~ precision ~ CharIn("hlL").? ~ conversion
  ).map { case (label, flags, width, precision, conversion) =>
    FormatSpec(
      label,
      flags.contains('#'),
      flags.contains('0'),
      flags.contains('-'),
      flags.contains(' '),
      flags.contains('+'),
      width.filterNot(_ == "*").map(_.toInt),
      width.contains("*"),
      precision.filterNot(_ == "*").map(_.toInt),
      precision.contains("*"),
      conversion.charAt(0)
    )
  }

  def plain[$: P]: P[String] = P(CharsWhile(_ != '%', 0).!)
  def format[$: P]: P[(String, Seq[(FormatSpec, String)])] = P(
    plain ~ (("%" ~/ formatSpec) ~ plain).rep ~ End
  )

  def widenRaw(formatted: FormatSpec, txt: String): String =
    if (formatted.width.isEmpty) txt // fast path: no width/padding needed
    else widen(formatted, "", "", txt, numeric = false, signedConversion = false)
  def widen(
      formatted: FormatSpec,
      lhs: String,
      mhs: String,
      rhs: String,
      numeric: Boolean,
      signedConversion: Boolean): String = {

    val lhs2 =
      if (signedConversion && formatted.blankBeforePositive) " " + lhs
      else if (signedConversion && formatted.signCharacter) "+" + lhs
      else lhs

    val missingWidth = formatted.width.getOrElse(-1) - lhs2.length - mhs.length - rhs.length

    if (missingWidth <= 0) {
      if (lhs2.isEmpty && mhs.isEmpty) rhs
      else if (lhs2.isEmpty) mhs + rhs
      else lhs2 + mhs + rhs
    } else if (formatted.zeroPadded) {
      if (numeric) lhs2 + mhs + "0" * missingWidth + rhs
      else {
        if (formatted.leftAdjusted) lhs2 + mhs + rhs + " " * missingWidth
        else " " * missingWidth + lhs2 + mhs + rhs
      }
    } else if (formatted.leftAdjusted) lhs2 + mhs + rhs + " " * missingWidth
    else " " * missingWidth + lhs2 + mhs + rhs
  }

  def format(s: String, values0: Val, pos: Position)(implicit evaluator: EvalScope): String = {
    val parsed = parseFormatCached(s)
    format(parsed, values0, pos)
  }

  private def parseFormatCached(s: String): ParsedFormat = {
    val cached0 = parsedFormatCache.synchronized(parsedFormatCache.get(s))
    if (cached0 != null) cached0
    else {
      val parsed = lowerParsedFormat(fastparse.parse(s, format(_)).get.value)
      parsedFormatCache.synchronized {
        val cached1 = parsedFormatCache.get(s)
        if (cached1 != null) cached1
        else {
          parsedFormatCache.put(s, parsed)
          parsed
        }
      }
    }
  }

  private def lowerParsedFormat(
      parsed: (String, scala.Seq[(FormatSpec, String)])): RuntimeFormat = {
    val (leading, chunks) = parsed
    val size = chunks.size
    val specs = new Array[FormatSpec](size)
    val literals = new Array[String](size)
    var staticChars = leading.length
    var hasAnyStar = false
    var idx = 0
    while (idx < size) {
      val (formatted, literal) = chunks(idx)
      specs(idx) = formatted
      literals(idx) = literal
      staticChars += literal.length
      hasAnyStar ||= formatted.widthStar || formatted.precisionStar
      idx += 1
    }
    RuntimeFormat(leading, specs, literals, hasAnyStar, staticChars)
  }

  def format(leading: String, chunks: scala.Seq[(FormatSpec, String)], values0: Val, pos: Position)(
      implicit evaluator: EvalScope): String = {
    format(lowerParsedFormat((leading, chunks)), values0, pos)
  }

  private def format(parsed: RuntimeFormat, values0: Val, pos: Position)(implicit
      evaluator: EvalScope): String = {
    val values = values0 match {
      case x: Val.Arr => x
      case x: Val.Obj => x
      case x          => Val.Arr(pos, Array[Eval](x))
    }
    val output = new StringBuilder(parsed.staticChars + parsed.specs.length * 8)
    output.append(parsed.leading)
    var i = 0
    var idx = 0
    while (idx < parsed.specs.length) {
      val rawFormatted = parsed.specs(idx)
      val literal = parsed.literals(idx)
      var formatted = rawFormatted
      val cooked0 = formatted.conversion match {
        case '%' => widenRaw(formatted, "%")
        case _   =>
          if (values.isInstanceOf[Val.Arr] && i >= values.cast[Val.Arr].length) {
            Error.fail(
              "Too few values to format: %d, expected at least %d".format(
                values.cast[Val.Arr].length,
                i + 1
              )
            )
          }
          val raw = formatted.label match {
            case None =>
              if (!parsed.hasAnyStar) values.cast[Val.Arr].value(i)
              else
                (formatted.widthStar, formatted.precisionStar) match {
                  case (false, false) => values.cast[Val.Arr].value(i)
                  case (true, false)  =>
                    val width = values.cast[Val.Arr].value(i)
                    if (!width.isInstanceOf[Val.Num]) {
                      Error.fail(
                        "A * was specified at position %d. An integer is expected for a width"
                          .format(
                            idx
                          )
                      )
                    }
                    i += 1
                    formatted = formatted.updateWithStarValues(Some(width.asInt), None)
                    values.cast[Val.Arr].value(i)
                  case (false, true) =>
                    val precision = values.cast[Val.Arr].value(i)
                    if (!precision.isInstanceOf[Val.Num]) {
                      Error.fail(
                        "A * was specified at position %d. An integer is expected for a precision"
                          .format(idx)
                      )
                    }
                    i += 1
                    formatted = formatted.updateWithStarValues(None, Some(precision.asInt))
                    values.cast[Val.Arr].value(i)
                  case (true, true) =>
                    val width = values.cast[Val.Arr].value(i)
                    if (!width.isInstanceOf[Val.Num]) {
                      Error.fail(
                        "A * was specified at position %d. An integer is expected for a width"
                          .format(
                            idx
                          )
                      )
                    }
                    i += 1
                    val precision = values.cast[Val.Arr].value(i)
                    if (!precision.isInstanceOf[Val.Num]) {
                      Error.fail(
                        "A * was specified at position %d. An integer is expected for a precision"
                          .format(idx)
                      )
                    }
                    i += 1
                    formatted =
                      formatted.updateWithStarValues(Some(width.asInt), Some(precision.asInt))
                    values.cast[Val.Arr].value(i)
                }
            case Some(key) =>
              values match {
                case v: Val.Arr => v.value(i)
                case v: Val.Obj => v.value(key, pos)
                case _          => Error.fail("Invalid format values")
              }
          }
          val rawVal = raw.value
          val formattedValue = rawVal match {
            case f: Val.Func => Error.fail("Cannot format function value", f)
            case vs: Val.Str =>
              // Fast path: skip Materializer for strings
              if (formatted.conversion != 's' && formatted.conversion != 'c')
                Error.fail("Format required a number at %d, got string".format(i))
              widenRaw(formatted, vs.str)
            case vn: Val.Num =>
              // Fast path: skip Materializer for numbers
              val s = vn.asDouble
              formatted.conversion match {
                case 'd' | 'i' | 'u' => formatInteger(formatted, s)
                case 'o'             => formatOctal(formatted, s)
                case 'x'             => formatHexadecimal(formatted, s)
                case 'X'             => formatHexadecimal(formatted, s).toUpperCase
                case 'e'             => formatExponent(formatted, s).toLowerCase
                case 'E'             => formatExponent(formatted, s)
                case 'f' | 'F'       => formatFloat(formatted, s)
                case 'g'             => formatGeneric(formatted, s).toLowerCase
                case 'G'             => formatGeneric(formatted, s)
                case 'c'             => widenRaw(formatted, Character.toString(s.toInt))
                case 's'             =>
                  if (s.toLong == s) widenRaw(formatted, s.toLong.toString)
                  else widenRaw(formatted, s.toString)
                case _ =>
                  Error.fail("Format required a %s at %d, got string".format(rawVal.prettyName, i))
              }
            case _: Val.True =>
              // Fast path: skip Materializer for booleans
              val b = 1
              formatted.conversion match {
                case 'd' | 'i' | 'u' => formatInteger(formatted, b)
                case 'o'             => formatOctal(formatted, b)
                case 'x'             => formatHexadecimal(formatted, b)
                case 'X'             => formatHexadecimal(formatted, b).toUpperCase
                case 'e'             => formatExponent(formatted, b).toLowerCase
                case 'E'             => formatExponent(formatted, b)
                case 'f' | 'F'       => formatFloat(formatted, b)
                case 'g'             => formatGeneric(formatted, b).toLowerCase
                case 'G'             => formatGeneric(formatted, b)
                case 'c' => widenRaw(formatted, Character.forDigit(b, 10).toString)
                case 's' => widenRaw(formatted, "true")
                case _   =>
                  Error.fail("Format required a %s at %d, got string".format(rawVal.prettyName, i))
              }
            case _: Val.False =>
              val b = 0
              formatted.conversion match {
                case 'd' | 'i' | 'u' => formatInteger(formatted, b)
                case 'o'             => formatOctal(formatted, b)
                case 'x'             => formatHexadecimal(formatted, b)
                case 'X'             => formatHexadecimal(formatted, b).toUpperCase
                case 'e'             => formatExponent(formatted, b).toLowerCase
                case 'E'             => formatExponent(formatted, b)
                case 'f' | 'F'       => formatFloat(formatted, b)
                case 'g'             => formatGeneric(formatted, b).toLowerCase
                case 'G'             => formatGeneric(formatted, b)
                case 'c' => widenRaw(formatted, Character.forDigit(b, 10).toString)
                case 's' => widenRaw(formatted, "false")
                case _   =>
                  Error.fail("Format required a %s at %d, got string".format(rawVal.prettyName, i))
              }
            case _: Val.Null =>
              widenRaw(formatted, "null")
            case _ =>
              // Complex types (Arr, Obj): materialize via Renderer
              val value = rawVal match {
                case r: Val.Arr => Materializer.apply0(r, new Renderer(indent = -1))
                case r: Val.Obj => Materializer.apply0(r, new Renderer(indent = -1))
                case _          => Materializer(rawVal)
              }
              widenRaw(formatted, value.toString)
          }
          i += 1
          formattedValue
      }
      output.append(cooked0)
      output.append(literal)
      idx += 1
    }

    if (values.isInstanceOf[Val.Arr] && i < values.cast[Val.Arr].length) {
      Error.fail(
        "Too many values to format: %d, expected %d".format(values.cast[Val.Arr].length, i)
      )
    }
    output.toString()
  }

  // Truncate a double toward zero, returning the integer part as a BigInt.
  // Uses Long as a fast path (mirrors RenderUtils.renderDouble); falls back to
  // BigDecimal for values that exceed Long range (~9.2e18).
  private def truncateToInteger(s: Double): BigInt = {
    val sl = s.toLong
    if (sl.toDouble == s) BigInt(sl)
    else BigDecimal(s).toBigInt
  }

  private def formatInteger(formatted: FormatSpec, s: Double): String = {
    val sl = s.toLong
    if (sl.toDouble == s) {
      // Fast path: value fits in Long — avoid BigInt allocation
      val negative = sl < 0
      val lhs = if (negative) "-" else ""
      val rhs = java.lang.Long.toString(if (negative) -sl else sl, 10)
      val rhs2 = precisionPad(lhs, rhs, formatted.precision)
      widen(formatted, lhs, "", rhs2, numeric = true, signedConversion = !negative)
    } else {
      val i = BigDecimal(s).toBigInt
      val negative = i.signum < 0
      val lhs = if (negative) "-" else ""
      val rhs = i.abs.toString(10)
      val rhs2 = precisionPad(lhs, rhs, formatted.precision)
      widen(formatted, lhs, "", rhs2, numeric = true, signedConversion = !negative)
    }
  }

  private def formatFloat(formatted: FormatSpec, s: Double): String = {
    widen(
      formatted,
      if (s < 0) "-" else "",
      "",
      sjsonnet.DecimalFormat
        .format(
          formatted.precision.getOrElse(6),
          0,
          formatted.alternate,
          None,
          math.abs(s)
        )
        .replace("E", "E+"),
      numeric = true,
      signedConversion = s > 0
    )

  }

  private def formatOctal(formatted: FormatSpec, s: Double): String = {
    val sl = s.toLong
    if (sl.toDouble == s) {
      val negative = sl < 0
      val lhs = if (negative) "-" else ""
      val rhs = java.lang.Long.toString(if (negative) -sl else sl, 8)
      val rhs2 = precisionPad(lhs, rhs, formatted.precision)
      widen(formatted, lhs,
        if (!formatted.alternate || rhs2.charAt(0) == '0') "" else "0",
        rhs2, numeric = true, signedConversion = !negative)
    } else {
      val i = BigDecimal(s).toBigInt
      val negative = i.signum < 0
      val lhs = if (negative) "-" else ""
      val rhs = i.abs.toString(8)
      val rhs2 = precisionPad(lhs, rhs, formatted.precision)
      widen(formatted, lhs,
        if (!formatted.alternate || rhs2.charAt(0) == '0') "" else "0",
        rhs2, numeric = true, signedConversion = !negative)
    }
  }

  private def formatHexadecimal(formatted: FormatSpec, s: Double): String = {
    val sl = s.toLong
    if (sl.toDouble == s) {
      val negative = sl < 0
      val lhs = if (negative) "-" else ""
      val rhs = java.lang.Long.toString(if (negative) -sl else sl, 16)
      val rhs2 = precisionPad(lhs, rhs, formatted.precision)
      widen(formatted, lhs,
        if (!formatted.alternate) "" else "0x",
        rhs2, numeric = true, signedConversion = !negative)
    } else {
      val i = BigDecimal(s).toBigInt
      val negative = i.signum < 0
      val lhs = if (negative) "-" else ""
      val rhs = i.abs.toString(16)
      val rhs2 = precisionPad(lhs, rhs, formatted.precision)
      widen(formatted, lhs,
        if (!formatted.alternate) "" else "0x",
        rhs2, numeric = true, signedConversion = !negative)
    }
  }

  private def precisionPad(lhs: String, rhs: String, precision: Option[Int]): String = {
    precision match {
      case None    => rhs
      case Some(p) =>
        val shortage = p - rhs.length
        if (shortage > 0) "0" * shortage + rhs else rhs
    }
  }

  private def formatGeneric(formatted: FormatSpec, s: Double): String = {
    val precision = formatted.precision.getOrElse(6)
    val exponent = if (s != 0) math.floor(math.log10(math.abs(s))).toInt else 0
    if (exponent < -4 || exponent >= precision) {
      widen(
        formatted,
        if (s < 0) "-" else "",
        "",
        sjsonnet.DecimalFormat
          .format(
            if (formatted.alternate) precision - 1 else 0,
            if (formatted.alternate) 0 else precision - 1,
            formatted.alternate,
            Some(2),
            math.abs(s)
          )
          .replace("E", "E+"),
        numeric = true,
        signedConversion = s > 0
      )
    } else {
      val digitsBeforePoint = math.max(1, exponent + 1)
      widen(
        formatted,
        if (s < 0) "-" else "",
        "",
        sjsonnet.DecimalFormat
          .format(
            if (formatted.alternate) precision - digitsBeforePoint else 0,
            if (formatted.alternate) 0 else precision - digitsBeforePoint,
            formatted.alternate,
            None,
            math.abs(s)
          )
          .replace("E", "E+"),
        numeric = true,
        signedConversion = s > 0
      )
    }

  }

  private def formatExponent(formatted: FormatSpec, s: Double): String = {
    widen(
      formatted,
      if (s < 0) "-" else "",
      "",
      sjsonnet.DecimalFormat
        .format(
          formatted.precision.getOrElse(6),
          0,
          formatted.alternate,
          Some(2),
          math.abs(s)
        )
        .replace("E", "E+"),
      numeric = true,
      signedConversion = s > 0
    )
  }

  class PartialApplyFmt(fmt: String) extends Val.Builtin1("format", "values") {
    private[this] val parsed = lowerParsedFormat(fastparse.parse(fmt, format(_)).get.value)
    def evalRhs(values0: Eval, ev: EvalScope, pos: Position): Val =
      Val.Str(pos, format(parsed, values0.value, pos)(ev))
  }
}
