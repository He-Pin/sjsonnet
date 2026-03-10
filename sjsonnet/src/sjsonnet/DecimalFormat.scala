package sjsonnet

/**
 * Minimal re-implementation of java.text.DecimalFormat, for Scala.js compatibility (the existing
 * shim for java.text scala-java-locales isn't fully compliant, and adds tons of unnecessary code
 * that bloats the JS bundle)
 */
object DecimalFormat {

  private def trailingZeroes(n: Long): Int = {
    var count = 0
    var current = n
    var done = false
    while (!done && current > 0) {
      if (current % 10 == 0) count += 1
      else done = true
      current /= 10
    }
    count
  }

  private def leftPad(n: Long, targetWidth: Int): String = {
    val absN = math.abs(n)
    val nWidth = if (absN == 0) 1 else Math.log10(absN.toDouble).toInt + 1
    val padCount = targetWidth - nWidth
    val sb = new java.lang.StringBuilder(targetWidth + 2)
    if (n < 0) sb.append('-')
    var i = 0
    while (i < padCount) { sb.append('0'); i += 1 }
    sb.append(absN)
    sb.toString
  }

  private def rightPad(n0: Long, minWidth: Int, maxWidth: Int): String = {
    if (n0 == 0 && minWidth == 0) ""
    else {
      val n = (n0 / Math.pow(10, trailingZeroes(n0))).toInt
      assert(n == math.abs(n))
      val nWidth = if (n == 0) 1 else Math.log10(n).toInt + 1
      val sb = new java.lang.StringBuilder(maxWidth + 2)
      sb.append(n)
      var i = 0
      val padCount = minWidth - nWidth
      while (i < padCount) { sb.append('0'); i += 1 }
      val str = sb.toString
      if (str.length > maxWidth) str.substring(0, maxWidth) else str
    }
  }

  def format(
      zeroes: Int,
      hashes: Int,
      alternate: Boolean,
      expLengthOpt: Option[Int],
      number: Double): String = {
    expLengthOpt match {
      case Some(expLength) =>
        val roundLog10 = if (number == 0.0) 1L else Math.ceil(Math.log10(math.abs(number))).toLong
        val expNum = roundLog10 - 1
        val scaled = number / math.pow(10, expNum.toDouble)
        val prefix = scaled.toLong.toString
        val expFrag = leftPad(expNum, expLength)
        val precision = zeroes + hashes

        (precision, alternate) match {
          case (0, false) =>
            val sb = new java.lang.StringBuilder(prefix.length + 1 + expFrag.length)
            sb.append(prefix).append('E').append(expFrag).toString
          case (0, true) =>
            val sb = new java.lang.StringBuilder(prefix.length + 2 + expFrag.length)
            sb.append(prefix).append('.').append('E').append(expFrag).toString
          case (_, _) =>
            val divided = number / Math.pow(10, (expNum - precision).toDouble)
            val scaledFrac = divided % Math.pow(10, precision)
            val frac = rightPad(Math.abs(Math.round(scaledFrac)), zeroes, precision)
            if (frac.isEmpty) {
              val sb = new java.lang.StringBuilder(prefix.length + 1 + expFrag.length)
              sb.append(prefix).append('E').append(expFrag).toString
            } else {
              val sb = new java.lang.StringBuilder(prefix.length + 2 + frac.length + expFrag.length)
              sb.append(prefix).append('.').append(frac).append('E').append(expFrag).toString
            }
        }

      case None =>
        val precision = zeroes + hashes
        val denominator = math.pow(10, precision)
        val numerator = number * denominator + 0.5
        val whole = math.floor(numerator / denominator)
        var fracNum = (math.floor(numerator) % denominator).toLong
        val prefix = whole.toLong.toString

        val frac =
          if (fracNum == 0 && zeroes == 0) ""
          else {
            var n = 0
            while (n < hashes && fracNum % 10 == 0 && fracNum != 0) {
              fracNum /= 10
              n += 1
            }
            leftPad(fracNum, precision - n)
          }

        (precision, alternate) match {
          case (0, false) => prefix
          case (0, true) =>
            val sb = new java.lang.StringBuilder(prefix.length + 1)
            sb.append(prefix).append('.').toString
          case (_, _) =>
            if (frac.isEmpty) prefix
            else {
              val sb = new java.lang.StringBuilder(prefix.length + 1 + frac.length)
              sb.append(prefix).append('.').append(frac).toString
            }
        }
    }
  }
}
