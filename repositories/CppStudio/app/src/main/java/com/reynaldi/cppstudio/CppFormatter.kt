package com.reynaldi.cppstudio

import android.graphics.Canvas
import android.graphics.Paint
import android.text.Spannable
import android.text.style.LineBackgroundSpan
import android.widget.EditText
import kotlin.math.abs

/** Conservative C++ formatter. It preserves source lines and tokens, normalizing whitespace only between tokens. */
object CppFormatter {
    enum class Style { PROFESSIONAL, MATCH_CURRENT_FILE }

    private data class LexState(
        var blockComment: Boolean = false,
        var quote: Char? = null,
        var escaped: Boolean = false,
        var rawEnd: String? = null
    )

    private data class LineInfo(
        val indentColumns: Int,
        val expectedDepth: Int,
        val opens: Int,
        val closes: Int,
        val isPreprocessor: Boolean,
        val isCaseLabel: Boolean,
        val isBlank: Boolean
    )

    private fun visualIndent(prefix: String): Int {
        var columns = 0
        for (c in prefix) columns += if (c == '\t') 4 else 1
        return columns
    }

    private fun leadingWhitespace(line: String): String = line.takeWhile { it == ' ' || it == '\t' }

    /** Count braces while ignoring strings, character literals, raw strings and comments. */
    private fun countBraces(line: String, state: LexState): Pair<Int, Int> {
        var opens = 0
        var closes = 0
        var i = 0
        while (i < line.length) {
            val rawEnd = state.rawEnd
            if (rawEnd != null) {
                val end = line.indexOf(rawEnd, i)
                if (end < 0) return opens to closes
                i = end + rawEnd.length
                state.rawEnd = null
                continue
            }
            if (state.blockComment) {
                val end = line.indexOf("*/", i)
                if (end < 0) return opens to closes
                i = end + 2
                state.blockComment = false
                continue
            }
            val q = state.quote
            if (q != null) {
                val c = line[i]
                if (state.escaped) state.escaped = false
                else if (c == '\\') state.escaped = true
                else if (c == q) state.quote = null
                i++
                continue
            }

            if (i + 1 < line.length && line[i] == '/' && line[i + 1] == '/') break
            if (i + 1 < line.length && line[i] == '/' && line[i + 1] == '*') {
                state.blockComment = true
                i += 2
                continue
            }
            // C++ raw string literal: R"tag(contents)tag".
            if (line.startsWith("R\"", i)) {
                val open = line.indexOf('(', i + 2)
                if (open >= 0 && open - (i + 2) <= 16) {
                    val tag = line.substring(i + 2, open)
                    val ending = ")$tag\""
                    val end = line.indexOf(ending, open + 1)
                    if (end < 0) {
                        state.rawEnd = ending
                        return opens to closes
                    }
                    i = end + ending.length
                    continue
                }
            }
            when (line[i]) {
                '"', '\'' -> state.quote = line[i]
                '{' -> opens++
                '}' -> closes++
            }
            i++
        }
        // A normal C++ string cannot continue across a physical newline unless escaped.
        if (state.quote != null && !state.escaped) state.quote = null
        state.escaped = false
        return opens to closes
    }

    private fun firstCodeCharacter(line: String, stateAtLineStart: LexState): Char? {
        if (stateAtLineStart.blockComment || stateAtLineStart.rawEnd != null || stateAtLineStart.quote != null) return null
        val trimmed = line.trimStart()
        if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("/*")) return null
        return trimmed.firstOrNull()
    }

    private fun analyzeLines(code: String): List<LineInfo> {
        val lines = code.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val state = LexState()
        val infos = ArrayList<LineInfo>(lines.size)
        var depth = 0
        var caseDepth: Int? = null
        var macroContinuation = false

        for (line in lines) {
            val leading = leadingWhitespace(line)
            val content = line.substring(leading.length)
            val protectedLiteralLine = state.rawEnd != null || state.quote != null
            val blank = content.isBlank() || protectedLiteralLine
            val directive = !state.blockComment && state.rawEnd == null && content.startsWith("#")
            val isCase = Regex("^(case\\b.*|default\\s*:)" ).containsMatchIn(content)
            val beforeState = state.copy()
            val closeAtStart = if (firstCodeCharacter(line, beforeState) == '}') {
                content.takeWhile { it == '}' }.length
            } else 0
            val expectedDepth = (depth - closeAtStart).coerceAtLeast(0) +
                if (!isCase && caseDepth == depth) 1 else 0
            infos += LineInfo(
                indentColumns = visualIndent(leading),
                expectedDepth = if (directive || macroContinuation) 0 else expectedDepth,
                opens = 0,
                closes = 0,
                isPreprocessor = directive || macroContinuation,
                isCaseLabel = isCase,
                isBlank = blank
            )

            if (directive || macroContinuation) {
                macroContinuation = content.endsWith("\\")
                continue
            }
            macroContinuation = false
            val (opens, closes) = countBraces(line, state)
            depth = (depth + opens - closes).coerceAtLeast(0)
            if (isCase) caseDepth = depth
            if (caseDepth != null && depth < caseDepth!!) caseDepth = null
        }
        return infos
    }

    /** Detect lines whose leading whitespace does not match the nesting structure. */
    fun preferredIndentWidth(code: String): Int = inferStyle(code).first

    fun findUntidyLines(code: String, indentWidth: Int = preferredIndentWidth(code)): Set<Int> {
        val infos = analyzeLines(code)
        return infos.mapIndexedNotNull { index, info ->
            if (info.isBlank) null
            else if (info.indentColumns != info.expectedDepth * indentWidth) index + 1
            else null
        }.toSet()
    }

    /** Normalize only whitespace between C++ tokens; literals and comments are copied verbatim. */
    private fun normalizeCodeSegment(source: String, streamLine: Boolean, controlLine: Boolean): String {
        var s = source
        s = Regex("\\b(if|for|while|switch|catch)\\s*\\(").replace(s) { "${it.groupValues[1]} (" }
        s = Regex("\\)\\s*\\{").replace(s, ") {")
        s = Regex("\\b(do|else|try)\\s*\\{").replace(s) { "${it.groupValues[1]} {" }
        s = Regex("\\}\\s*(else|catch|while)\\b").replace(s) { "} ${it.groupValues[1]}" }
        s = Regex("\\s*(<=>|<<=|>>=|\\+=|-=|\\*=|/=|%=|&=|\\|=|\\^=|==|!=|<=|>=|&&|\\|\\|)\\s*")
            .replace(s) { " ${it.groupValues[1]} " }
        if (streamLine) {
            s = Regex("(?<!<)\\s*(<<|>>)(?![=>])\\s*").replace(s) { " ${it.groupValues[1]} " }
        }
        if (controlLine) {
            s = Regex("(?<!<)\\s*<(?![<=])\\s*").replace(s, " < ")
            s = Regex("(?<!>)\\s*>(?![>=])\\s*").replace(s, " > ")
        }
        s = Regex("(?<![=!<>+\\-*/%&|^])\\s*=(?!=|>)\\s*").replace(s, " = ")
        s = Regex("\\s*,\\s*").replace(s, ", ")
        s = Regex("\\(\\s+").replace(s, "(")
        s = Regex("\\s+\\)").replace(s, ")")
        s = Regex(";(?=\\S)").replace(s, "; ")
        s = Regex("[ \\t]{2,}").replace(s, " ")
        return s
    }

    private fun normalizeProfessionalLine(line: String): String {
        val out = StringBuilder(line.length + 8)
        val code = StringBuilder()
        val streamLine = Regex("\\b(cout|cerr|clog|wcout|wcerr|cin|wcin)\\b").containsMatchIn(line)
        val controlLine = Regex("\\b(if|for|while|switch)\\b").containsMatchIn(line)
        fun flushCode() {
            if (code.isNotEmpty()) {
                out.append(normalizeCodeSegment(code.toString(), streamLine, controlLine))
                code.setLength(0)
            }
        }

        var i = 0
        while (i < line.length) {
            if (line.startsWith("//", i)) {
                flushCode()
                out.append(line.substring(i))
                break
            }
            if (line.startsWith("/*", i)) {
                flushCode()
                val end = line.indexOf("*/", i + 2)
                if (end < 0) {
                    out.append(line.substring(i))
                    break
                }
                out.append(line, i, end + 2)
                i = end + 2
                continue
            }
            val rawPrefix = listOf("u8R\"", "uR\"", "UR\"", "LR\"", "R\"")
                .firstOrNull { line.startsWith(it, i) }
            if (rawPrefix != null) {
                flushCode()
                val quoteStart = i + rawPrefix.length - 1
                val open = line.indexOf('(', quoteStart + 1)
                if (open >= 0 && open - quoteStart <= 17) {
                    val delimiter = line.substring(quoteStart + 1, open)
                    val endToken = ")$delimiter\""
                    val end = line.indexOf(endToken, open + 1)
                    if (end >= 0) {
                        out.append(line, i, end + endToken.length)
                        i = end + endToken.length
                    } else {
                        out.append(line.substring(i))
                        break
                    }
                    continue
                }
            }
            val c = line[i]
            if (c == '\"' || c == '\'') {
                flushCode()
                val quote = c
                val start = i++
                var escaped = false
                while (i < line.length) {
                    val ch = line[i++]
                    if (escaped) escaped = false
                    else if (ch == '\\') escaped = true
                    else if (ch == quote) break
                }
                out.append(line, start, i)
                continue
            }
            code.append(c)
            i++
        }
        flushCode()
        return out.toString()
    }

    /** Infer 2/4/8 spaces or tabs from the current source for a personal style match. */
    private fun inferStyle(code: String): Pair<Int, Boolean> {
        val lines = code.replace("\r\n", "\n").lines()
        val tabLines = lines.count { leadingWhitespace(it).contains('\t') }
        val spaceLines = lines.count { leadingWhitespace(it).contains(' ') }
        val useTabs = tabLines > 0 && tabLines >= spaceLines
        if (useTabs) return 4 to true

        val samples = mutableListOf<Pair<Int, Int>>()
        val state = LexState()
        var depth = 0
        var macroContinuation = false
        for (line in lines) {
            val lead = leadingWhitespace(line)
            val body = line.substring(lead.length)
            val directive = !state.blockComment && body.startsWith("#")
            val closeCount = if (body.startsWith("}")) body.takeWhile { it == '}' }.length else 0
            val effective = (depth - closeCount).coerceAtLeast(0)
            // Ignore completely unindented nested lines: they are often the very formatting
            // mistakes being repaired, not evidence that the author prefers zero indentation.
            if (!body.isBlank() && !directive && !macroContinuation && effective > 0 &&
                lead.isNotEmpty() && !lead.contains('\t')) {
                samples += effective to visualIndent(lead)
            }
            if (directive || macroContinuation) {
                macroContinuation = body.endsWith("\\")
                continue
            }
            macroContinuation = false
            val (o, c) = countBraces(line, state)
            depth = (depth + o - c).coerceAtLeast(0)
        }
        if (samples.isEmpty()) return 4 to false
        val candidates = listOf(2, 4, 8)
        val chosen = candidates.minByOrNull { width -> samples.sumOf { (depth, actual) -> abs(actual - depth * width) } } ?: 4
        return chosen to false
    }

    fun format(code: String, style: Style): String {
        if (code.isEmpty()) return code
        val (width, useTabs) = if (style == Style.PROFESSIONAL) 4 to false else inferStyle(code)
        val lines = code.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val state = LexState()
        val result = ArrayList<String>(lines.size)
        var depth = 0
        var caseDepth: Int? = null
        var macroContinuation = false

        for (line in lines) {
            // Whitespace inside a multi-line raw or escaped string is part of the literal value.
            // Keep continuation lines byte-for-byte as written and only update lexer state.
            if (state.rawEnd != null || state.quote != null || state.blockComment) {
                result += line
                val (opens, closes) = countBraces(line, state)
                depth = (depth + opens - closes).coerceAtLeast(0)
                if (caseDepth != null && depth < caseDepth!!) caseDepth = null
                continue
            }
            val content = line.trim()
            if (content.isEmpty()) {
                result += ""
                continue
            }
            val leading = leadingWhitespace(line)
            val rawContent = line.substring(leading.length)
            val directive = !state.blockComment && state.rawEnd == null && rawContent.startsWith("#")
            val closeAtStart = if (rawContent.startsWith("}")) rawContent.takeWhile { it == '}' }.length else 0
            val isCase = Regex("^(case\\b.*|default\\s*:)" ).containsMatchIn(rawContent)
            val indentDepth = if (directive || macroContinuation) 0 else
                ((depth - closeAtStart).coerceAtLeast(0) + if (!isCase && caseDepth == depth) 1 else 0)
            val prefix = if (directive || macroContinuation) "" else if (useTabs) "\t".repeat(indentDepth) else " ".repeat(indentDepth * width)
            val body = when {
                style == Style.PROFESSIONAL && directive && rawContent.startsWith("#") ->
                    Regex("^#\\s*include\\s*([<\"])").replace(rawContent) { "#include ${it.groupValues[1]}" }
                style == Style.PROFESSIONAL && !directive && !macroContinuation -> normalizeProfessionalLine(rawContent)
                else -> rawContent
            }
            result += prefix + body.trimEnd()

            if (directive || macroContinuation) {
                macroContinuation = rawContent.endsWith("\\")
                continue
            }
            macroContinuation = false
            val (opens, closes) = countBraces(line, state)
            depth = (depth + opens - closes).coerceAtLeast(0)
            if (isCase) caseDepth = depth
            if (caseDepth != null && depth < caseDepth!!) caseDepth = null
        }
        while (result.isNotEmpty() && result.last().isEmpty()) result.removeAt(result.lastIndex)
        return result.joinToString("\n") + if (result.isEmpty()) "" else "\n"
    }

    private data class IndentGuideLine(
        val indentColumns: Int,
        val activeDepth: Int,
        val isBlank: Boolean,
        val isComment: Boolean
    )

    /**
     * Rebuilds block-based indentation guides. Each open C++ brace adds a guide
     * that continues down the following lines until its matching closing brace.
     * A guide is drawn only in the line's indentation whitespace of code lines, so it
     * never runs through source-code characters, and it is skipped on comment lines
     * (// and /* */) and on soft-wrapped continuation rows. The text watcher refreshes this span
     * after typing, Enter, deletion and Tidy.
     */
    fun updateRuler(editor: EditText, enabled: Boolean, indentWidth: Int = 4, guideColor: Int = 0xD08EA8FF.toInt()) {
        val text = editor.text as? Spannable ?: return
        text.getSpans(0, text.length, IndentRulerSpan::class.java).forEach { text.removeSpan(it) }
        if (!enabled || text.isEmpty()) return

        val source = text.toString()
        val rows = ArrayList<IndentGuideLine>()
        val starts = ArrayList<Int>()
        val indentationSamples = ArrayList<Int>()
        val lexState = LexState()
        var depth = 0
        var macroContinuation = false
        var lineStart = 0

        // Keep the active brace depth for every physical source line. The depth
        // is captured before processing that line, so a closing-brace line still
        // shows its block guide up to the closing brace.
        while (lineStart <= source.length) {
            val lineEnd = source.indexOf('\n', lineStart).let { if (it < 0) source.length else it }
            val line = source.substring(lineStart, lineEnd)
            val leading = leadingWhitespace(line)
            val content = line.substring(leading.length)
            var indentColumns = 0
            for (c in leading) {
                indentColumns = if (c == '\t') ((indentColumns / 4) + 1) * 4 else indentColumns + 1
            }

            // Comment lines (// ..., /* ... */, and every line inside a block comment)
            // get no guides, so the guides never run through or beside comment text.
            val insideBlockComment = lexState.blockComment
            val commentLine = insideBlockComment || content.startsWith("//") || content.startsWith("/*")

            starts += lineStart
            rows += IndentGuideLine(indentColumns, depth, content.isBlank(), commentLine)
            if (indentColumns > 0 && content.isNotBlank()) indentationSamples += indentColumns

            val directive = !lexState.blockComment && lexState.rawEnd == null && content.startsWith("#")
            if (directive || macroContinuation) {
                macroContinuation = content.endsWith("\\")
            } else {
                macroContinuation = false
                val (opens, closes) = countBraces(line, lexState)
                depth = (depth + opens - closes).coerceAtLeast(0)
            }

            if (lineEnd == source.length) break
            lineStart = lineEnd + 1
        }

        // Use the common indentation step (2/4/8 columns); tabs use four-column stops.
        var detected = 0
        for (sample in indentationSamples) {
            detected = if (detected == 0) sample else gcd(detected, sample)
        }
        val unitColumns = detected.takeIf { it in 2..8 } ?: indentWidth.coerceIn(2, 8)
        text.setSpan(
            IndentRulerSpan(unitColumns, rows, starts.toIntArray(), guideColor),
            0,
            text.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }

    private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

    private class IndentRulerSpan(
        private val unitColumns: Int,
        private val rows: List<IndentGuideLine>,
        private val lineStarts: IntArray,
        private val guideColor: Int
    ) : LineBackgroundSpan {
        override fun drawBackground(
            canvas: Canvas,
            paint: Paint,
            left: Int,
            right: Int,
            top: Int,
            baseline: Int,
            bottom: Int,
            text: CharSequence,
            start: Int,
            end: Int,
            lineNumber: Int
        ) {
            // Locate the physical source line. This also handles soft-wrapped lines.
            var low = 0
            var high = lineStarts.lastIndex
            var rowIndex = 0
            while (low <= high) {
                val middle = (low + high) ushr 1
                if (lineStarts[middle] <= start) {
                    rowIndex = middle
                    low = middle + 1
                } else {
                    high = middle - 1
                }
            }
            val row = rows.getOrNull(rowIndex) ?: return

            // Never draw on comment lines, and never on the soft-wrapped continuation of a
            // long line: wrapped text starts at the left edge, so a guide there would cross it.
            if (row.isComment) return
            if (start != lineStarts[rowIndex]) return

            // For non-empty lines, stop at the first source character. Blank lines
            // can carry all active guides through the empty row within the block.
            val visibleLevels = if (row.isBlank) row.activeDepth else
                minOf(row.activeDepth, row.indentColumns / unitColumns)
            if (visibleLevels <= 0) return

            val oldColor = paint.color
            val oldWidth = paint.strokeWidth
            val oldStyle = paint.style
            val oldCap = paint.strokeCap
            val oldAntiAlias = paint.isAntiAlias
            try {
                paint.color = guideColor
                paint.strokeWidth = 1.7f
                paint.strokeCap = Paint.Cap.BUTT
                paint.isAntiAlias = true
                paint.style = Paint.Style.STROKE

                val cellWidth = paint.measureText(" ").coerceAtLeast(1f)
                for (level in 1..visibleLevels) {
                    // The guide sits just before the next indentation column,
                    // inside whitespace and never on top of the first code glyph.
                    val x = left + cellWidth * (level * unitColumns - 0.5f)
                    if (x >= left && x <= right) {
                        canvas.drawLine(x, top.toFloat(), x, bottom.toFloat(), paint)
                    }
                }
            } finally {
                paint.color = oldColor
                paint.strokeWidth = oldWidth
                paint.style = oldStyle
                paint.strokeCap = oldCap
                paint.isAntiAlias = oldAntiAlias
            }
        }
    }
}
