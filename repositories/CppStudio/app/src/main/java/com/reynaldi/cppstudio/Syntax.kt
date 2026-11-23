package com.reynaldi.cppstudio

import android.content.Context
import android.text.Editable
import android.text.Spannable
import android.text.style.ForegroundColorSpan
import androidx.core.content.ContextCompat

/** Simple C++ syntax colouring (keywords, types, numbers, strings, comments, operators ...). */
object Syntax {
    class Span(color: Int) : ForegroundColorSpan(color)

    private val KEYWORDS = setOf(
        "if", "else", "for", "while", "do", "switch", "case", "default", "break", "continue", "return", "goto",
        "try", "catch", "throw", "class", "struct", "enum", "union", "namespace", "template", "typename", "using",
        "typedef", "public", "private", "protected", "virtual", "static", "const", "constexpr", "consteval",
        "constinit", "inline", "new", "delete", "operator", "this", "nullptr", "true", "false", "explicit",
        "friend", "mutable", "override", "final", "volatile", "extern", "noexcept", "static_assert", "sizeof",
        "decltype", "typeid", "co_await", "co_return", "co_yield", "concept", "requires", "export", "register",
        "thread_local", "alignas", "alignof", "and", "or", "not", "xor"
    )

    private val TYPES = setOf(
        "int", "long", "short", "char", "float", "double", "bool", "void", "auto", "unsigned", "signed",
        "wchar_t", "char8_t", "char16_t", "char32_t", "size_t", "ssize_t", "ptrdiff_t",
        "int8_t", "int16_t", "int32_t", "int64_t", "uint8_t", "uint16_t", "uint32_t", "uint64_t",
        "std", "string", "vector", "map", "set", "unordered_map", "unordered_set", "queue", "stack", "deque",
        "list", "pair", "array", "optional", "bitset", "cout", "cin", "cerr", "endl"
    )

    fun apply(e: Editable, context: Context) {
        val keywordColor = ContextCompat.getColor(context, R.color.syntax_keyword)
        val typeColor = ContextCompat.getColor(context, R.color.syntax_type)
        val numberColor = ContextCompat.getColor(context, R.color.syntax_number)
        val stringColor = ContextCompat.getColor(context, R.color.syntax_string)
        val commentColor = ContextCompat.getColor(context, R.color.syntax_comment)
        val preprocColor = ContextCompat.getColor(context, R.color.syntax_preproc)
        val functionColor = ContextCompat.getColor(context, R.color.syntax_function)
        val operatorColor = ContextCompat.getColor(context, R.color.syntax_operator)
        val punctColor = ContextCompat.getColor(context, R.color.syntax_punct)
        val bracketColor = ContextCompat.getColor(context, R.color.syntax_bracket)
        for (sp in e.getSpans(0, e.length, Span::class.java)) e.removeSpan(sp)
        val s = e.toString()
        val n = minOf(s.length, 200_000)
        var i = 0
        var lineStart = true
        fun put(a: Int, b: Int, c: Int) { if (b > a) e.setSpan(Span(c), a, b, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE) }

        while (i < n) {
            val c = s[i]
            val nx = if (i + 1 < n) s[i + 1] else '\u0000'
            if (c == '\n') { lineStart = true; i++; continue }
            if (c == ' ' || c == '\t' || c == '\r') { i++; continue }
            val atLineStart = lineStart
            lineStart = false
            when {
                c == '/' && nx == '/' -> {
                    var j = i
                    while (j < n && s[j] != '\n') j++
                    put(i, j, commentColor); i = j
                }
                c == '/' && nx == '*' -> {
                    var j = s.indexOf("*/", i + 2)
                    j = if (j < 0 || j + 2 > n) n else j + 2
                    put(i, j, commentColor); i = j
                }
                c == '#' && atLineStart -> {
                    var j = i + 1
                    while (j < n && (s[j] == ' ' || s[j] == '\t')) j++
                    while (j < n && s[j].isLetter()) j++
                    val directive = s.substring(i + 1, j).trim()
                    put(i, j, preprocColor); i = j
                    if (directive == "include") {
                        var k = i
                        while (k < n && (s[k] == ' ' || s[k] == '\t')) k++
                        if (k < n && s[k] == '<') {
                            val close = s.indexOf('>', k)
                            val eol = s.indexOf('\n', k).let { if (it < 0) n else it }
                            if (close in 0 until eol) { put(k, close + 1, stringColor); i = close + 1 }
                        }
                    }
                }
                c == '"' || c == '\'' -> {
                    var j = i + 1
                    while (j < n && s[j] != c && s[j] != '\n') { if (s[j] == '\\') j++; j++ }
                    if (j < n && s[j] == c) j++
                    j = minOf(j, n)
                    put(i, j, stringColor); i = j
                }
                c.isDigit() || (c == '.' && nx.isDigit()) -> {
                    var j = i + 1
                    val hex = s.startsWith("0x", i, true)
                    while (j < n && (s[j].isLetterOrDigit() || s[j] == '.' || s[j] == '_' || s[j] == '\'' ||
                                ((s[j] == '+' || s[j] == '-') && (s[j - 1] == 'e' || s[j - 1] == 'E') && !hex))) j++
                    put(i, j, numberColor); i = j
                }
                c.isLetter() || c == '_' -> {
                    var j = i + 1
                    while (j < n && (s[j].isLetterOrDigit() || s[j] == '_')) j++
                    val w = s.substring(i, j)
                    when {
                        w in KEYWORDS -> put(i, j, keywordColor)
                        w in TYPES -> put(i, j, typeColor)
                        else -> {
                            var k = j
                            while (k < n && (s[k] == ' ' || s[k] == '\t')) k++
                            if (k < n && s[k] == '(') put(i, j, functionColor)
                        }
                    }
                    i = j
                }
                c == ';' || c == ',' -> { put(i, i + 1, punctColor); i++ }
                c in "()[]{}" -> { put(i, i + 1, bracketColor); i++ }
                c in "+-*/%=<>!&|^~?:." -> { put(i, i + 1, operatorColor); i++ }
                else -> i++
            }
        }
    }
}
