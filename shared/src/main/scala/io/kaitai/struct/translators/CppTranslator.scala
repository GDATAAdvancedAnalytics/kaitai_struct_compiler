package io.kaitai.struct.translators

import java.nio.charset.Charset

import io.kaitai.struct.CppRuntimeConfig.{RawPointers, SharedPointers, UniqueAndRawPointers}
import io.kaitai.struct.datatype.DataType
import io.kaitai.struct.datatype.DataType._
import io.kaitai.struct.exprlang.Ast
import io.kaitai.struct.exprlang.Ast.expr
import io.kaitai.struct.format.Identifier
import io.kaitai.struct.languages.CppCompiler
import io.kaitai.struct.languages.components.CppImportList
import io.kaitai.struct.{RuntimeConfig, Utils}

class CppTranslator(provider: TypeProvider, importListSrc: CppImportList, importListHdr: CppImportList, config: RuntimeConfig)
  extends BaseTranslator(provider)
    with MinSignedIntegers {
  val CHARSET_UTF8 = Charset.forName("UTF-8")

  /**
    * Handles integer literals for C++ by appending relevant suffix to decimal notation.
    *
    * Note that suffixes essentially mean "long", "unsigned long", and "unsigned long long", which
    * are not really guaranteed to match `int32_t`, `uint32_t` and `uint64_t`, but it would work for
    * the majority of current compilers.
    *
    * For reference, ranges of integers that are used in this conversion are:
    *
    *   - int32_t (no suffix): -2147483648..2147483647
    *   - uint32_t (UL): 0..4294967295
    *   - int64_t (LL): -9223372036854775808..9223372036854775807
    *   - uint64_t (ULL): 0..18446744073709551615
    *
    * Beyond these boundaries, C++ is unlikely to be able to represent these anyway, so we just drop
    * the suffix and hope for the miracle.
    *
    * The minimum signed 32-bit and 64-bit integers (Int.MinValue and Long.MinValue) are
    * intentionally omitted, since they're handled by [[MinSignedIntegers]].
    *
    * @param n integer to render
    * @return rendered integer literal in C++ syntax as string
    */
  override def doIntLiteral(n: BigInt): String = {
    val suffixOpt: Option[String] =
      if (n >= Int.MinValue + 1 && n <= Int.MaxValue) {
        Some("") // -2147483647..2147483647
      } else if (n > Int.MaxValue && n <= Utils.MAX_UINT32) {
        Some("UL") // 2147483648..4294967295
      } else if ((n >= Long.MinValue + 1 && n < Int.MinValue) || (n > Utils.MAX_UINT32 && n <= Long.MaxValue)) {
        Some("LL") // -9223372036854775807..-2147483649 | 4294967296..9223372036854775807
      } else if (n > Long.MaxValue && n <= Utils.MAX_UINT64) {
        Some("ULL") // 9223372036854775808..18446744073709551615
      } else {
        None
      }

    suffixOpt match {
      case Some(suffix) => s"$n$suffix"
      case None => super.doIntLiteral(n) // delegate to parent implementations
    }
  }

  /**
    * Handles string literal for C++ by wrapping a C `const char*`-style string
    * into a std::string constructor. Note that normally std::string
    * constructor treats given string in C manner, i.e. as zero-terminated
    * (and it is indeed would be generated by compiler as zero-terminated const
    * in .rodata segment). However, this is bad for string literals that contain
    * zero inside them: they would be cut abruptly at that zero. So, for string
    * literals that contain zero inside them, we use another constructor, which
    * allows explicit byte size argument.
    *
    * @param s string to present as C++ string literal
    * @return string as C++ string literal
    */
  override def doStringLiteral(s: String): String = {
    val lenSuffix = if (s.contains("\u0000")) {
      ", " + s.getBytes(CHARSET_UTF8).length
    } else {
      ""
    }
    s"std::string(${super.doStringLiteral(s)}$lenSuffix)"
  }

  /**
    * http://en.cppreference.com/w/cpp/language/escape
    */
  override val asciiCharQuoteMap: Map[Char, String] = Map(
    '\t' -> "\\t",
    '\n' -> "\\n",
    '\r' -> "\\r",
    '"' -> "\\\"",
    '\\' -> "\\\\",

    '\u0007' -> "\\a",
    '\f' -> "\\f",
    '\u000b' -> "\\v",
    '\b' -> "\\b"
  )

  override def doArrayLiteral(t: DataType, values: Seq[expr]): String = {
    if (config.cppConfig.useListInitializers) {
      importListHdr.addSystem("vector")
      val cppElType = CppCompiler.kaitaiType2NativeType(config.cppConfig, t)
      val rawInit = s"new std::vector<$cppElType>{" + values.map((value) => translate(value)).mkString(", ") + "}"
      config.cppConfig.pointers match {
        case RawPointers =>
          rawInit
        case UniqueAndRawPointers =>
          s"std::unique_ptr<std::vector<$cppElType>>($rawInit)"
        // TODO: C++14
      }
    } else {
      throw new RuntimeException("C++ literal arrays are not implemented yet")
    }
  }

  override def doByteArrayLiteral(arr: Seq[Byte]): String =
    "std::string(\"" + Utils.hexEscapeByteArray(arr) + "\", " + arr.length + ")"

  override def numericBinOp(left: Ast.expr, op: Ast.operator, right: Ast.expr) = {
    (detectType(left), detectType(right), op) match {
      case (_: IntType, _: IntType, Ast.operator.Mod) =>
        s"${CppCompiler.kstreamName}::mod(${translate(left)}, ${translate(right)})"
      case _ =>
        super.numericBinOp(left, op, right)
    }
  }

  override def anyField(value: expr, attrName: String): String =
    s"${translate(value)}->${doName(attrName)}"

  override def doName(s: String) = s match {
    case Identifier.ITERATOR => "_"
    case Identifier.ITERATOR2 => "_buf"
    case Identifier.INDEX => "i"
    case _ => s"$s()"
  }

  override def doEnumByLabel(enumType: List[String], label: String): String =
    CppCompiler.types2class(enumType.dropRight(1)) + "::" +
      Utils.upperUnderscoreCase(enumType.last + "_" + label)
  override def doEnumById(enumType: List[String], id: String): String =
    s"static_cast<${CppCompiler.types2class(enumType)}>($id)"

  override def doStrCompareOp(left: Ast.expr, op: Ast.cmpop, right: Ast.expr) = {
    if (op == Ast.cmpop.Eq) {
      s"${translate(left)} == (${translate(right)})"
    } else if (op == Ast.cmpop.NotEq) {
      s"${translate(left)} != ${translate(right)}"
    } else {
      s"(${translate(left)}.compare(${translate(right)}) ${cmpOp(op)} 0)"
    }
  }

  override def arraySubscript(container: expr, idx: expr): String =
    s"${translate(container)}->at(${translate(idx)})"
  override def doIfExp(condition: expr, ifTrue: expr, ifFalse: expr): String =
    s"((${translate(condition)}) ? (${translate(ifTrue)}) : (${translate(ifFalse)}))"
  override def doCast(value: Ast.expr, typeName: DataType): String =
    config.cppConfig.pointers match {
      case RawPointers | UniqueAndRawPointers =>
        cppStaticCast(value, typeName)
      case SharedPointers =>
        typeName match {
          case ut: UserType =>
            s"std::static_pointer_cast<${CppCompiler.types2class(ut.classSpec.get.name)}>(${translate(value)})"
          case _ => cppStaticCast(value, typeName)
        }
    }

  def cppStaticCast(value: Ast.expr, typeName: DataType): String =
    s"static_cast<${CppCompiler.kaitaiType2NativeType(config.cppConfig, typeName)}>(${translate(value)})"

  // Predefined methods of various types
  override def strToInt(s: expr, base: expr): String = {
    val baseStr = translate(base)
    s"std::stoi(${translate(s)}" + (baseStr match {
      case "10" => ""
      case _ => s", 0, $baseStr"
    }) + ")"
  }
  override def enumToInt(v: expr, et: EnumType): String =
    translate(v)
  override def boolToInt(v: expr): String =
    s"((${translate(v)}) ? 1 : 0)"
  override def floatToInt(v: expr): String =
    s"static_cast<int>(${translate(v)})"
  override def intToStr(i: expr, base: expr): String = {
    val baseStr = translate(base)
    baseStr match {
      case "10" =>
        // FIXME: proper way for C++11, but not available in earlier versions
        //s"std::to_string(${translate(i)})"
        s"${CppCompiler.kstreamName}::to_string(${translate(i)})"
      case _ => throw new UnsupportedOperationException(baseStr)
    }
  }
  override def bytesToStr(bytesExpr: String, encoding: Ast.expr): String =
    s"${CppCompiler.kstreamName}::bytes_to_str($bytesExpr, ${translate(encoding)})"
  override def bytesLength(b: Ast.expr): String =
    s"${translate(b)}.length()"

  override def bytesSubscript(container: Ast.expr, idx: Ast.expr): String =
    s"${translate(container)}[${translate(idx)}]"
  override def bytesFirst(b: Ast.expr): String = {
    config.cppConfig.stdStringFrontBack match {
      case true => s"${translate(b)}.front()"
      case false => s"${translate(b)}[0]"
    }
  }
  override def bytesLast(b: Ast.expr): String = {
    config.cppConfig.stdStringFrontBack match {
      case true => s"${translate(b)}.back()"
      case false => s"${translate(b)}[${translate(b)}.length() - 1]"
    }
  }
  override def bytesMin(b: Ast.expr): String =
    s"${CppCompiler.kstreamName}::byte_array_min(${translate(b)})"
  override def bytesMax(b: Ast.expr): String =
    s"${CppCompiler.kstreamName}::byte_array_max(${translate(b)})"

  override def strLength(s: expr): String =
    s"${translate(s)}.length()"
  override def strReverse(s: expr): String =
    s"${CppCompiler.kstreamName}::reverse(${translate(s)})"
  override def strSubstring(s: expr, from: expr, to: expr): String =
    s"${translate(s)}.substr(${translate(from)}, (${translate(to)}) - (${translate(from)}))"

  override def arrayFirst(a: expr): String =
    s"${translate(a)}->front()"
  override def arrayLast(a: expr): String =
    s"${translate(a)}->back()"
  override def arraySize(a: expr): String =
    s"${translate(a)}->size()"
  override def arrayMin(a: expr): String = {
    importListSrc.addSystem("algorithm")
    val v = translate(a)
    s"*std::min_element($v->begin(), $v->end())"
  }
  override def arrayMax(a: expr): String = {
    importListSrc.addSystem("algorithm")
    val v = translate(a)
    s"*std::max_element($v->begin(), $v->end())"
  }
}
