package com.reynaldi.cppstudio

/**
 * Variant-aware checks for common examples in the offline catalogue.
 * These checks intentionally identify the implementation first. A rule for an iterative
 * algorithm is never applied to a std::find or recursive implementation.
 */
object ExampleAnalyzer {
    const val ITERATIVE_LINEAR_RETURN_MESSAGE =
        "Linear Search (Iterative): return -1 is inside the search loop"

    private enum class LinearVariant(val label: String, val id: String) {
        STD_FIND("Using std::find()", "linear-search-std-find"),
        ITERATIVE("Iterative Implementation", "linear-search-iterative"),
        RECURSIVE("Recursive Implementation", "linear-search-recursive")
    }

    private data class FunctionSpan(
        val name: String,
        val params: String,
        val start: Int,
        val openBrace: Int,
        val closeBrace: Int
    )

    private data class LoopSpan(val start: Int, val bodyStart: Int, val bodyEnd: Int, val closeBrace: Int)
    private data class LinearMatch(val variant: LinearVariant, val function: FunctionSpan?)

    fun analyze(code: String): List<Diagnostic> {
        if (code.isBlank()) return emptyList()
        val masked = maskCommentsAndLiterals(code)
        val findings = mutableListOf<Diagnostic>()
        val linearMatches = detectLinearMatches(masked)

        for (match in linearMatches) {
            val scopeStart = match.function?.openBrace ?: 0
            val scopeEnd = match.function?.closeBrace ?: masked.length
            findings += Diagnostic(
                line = match.function?.let { lineAt(masked, it.start) } ?: 0,
                col = 0,
                severity = Severity.INFO,
                message = "Example match: Linear Search — ${match.variant.label}",
                advice = "Recognised implementation: ${match.variant.label}. Variant-specific checks are limited to this implementation; C++ Studio will not convert it into another Linear Search variant.",
                source = "example-database:${match.variant.id}"
            )

            when (match.variant) {
                LinearVariant.STD_FIND -> Unit // Do not run loop/recursion rules on std::find.
                LinearVariant.ITERATIVE -> {
                    val loops = findLoops(masked, scopeStart, scopeEnd)
                    val faulty = loops.firstOrNull { loop ->
                        val body = masked.substring(loop.bodyStart, loop.bodyEnd.coerceAtMost(masked.length))
                        Regex("\\breturn\\s*-\\s*1\\s*;").containsMatchIn(body) &&
                            Regex("\\breturn\\s+(?!-\\s*1\\b)[A-Za-z_]\\w*\\s*;").containsMatchIn(body)
                    }
                    if (faulty != null) {
                        val body = masked.substring(faulty.bodyStart, faulty.bodyEnd.coerceAtMost(masked.length))
                        val negativeReturn = Regex("\\breturn\\s*-\\s*1\\s*;").find(body)
                        if (negativeReturn != null) {
                            val at = faulty.bodyStart + negativeReturn.range.first
                            findings += Diagnostic(
                                line = lineAt(masked, at), col = columnAt(masked, at),
                                severity = Severity.WARNING,
                                message = ITERATIVE_LINEAR_RETURN_MESSAGE,
                                advice = "In an iterative linear search, a mismatch should continue to the next element. Keep the successful return inside the loop, but move `return -1;` below the loop so the function reports 'not found' only after every element has been checked.",
                                source = "example-database:${match.variant.id}"
                            )
                        }
                    }
                }
                LinearVariant.RECURSIVE -> {
                    val function = match.function ?: continue
                    val body = masked.substring(function.openBrace + 1, function.closeBrace)
                    val recursiveCall = Regex("\\b${Regex.escape(function.name)}\\s*\\(").find(body)
                    if (recursiveCall != null) {
                        val prefix = body.substring(0, recursiveCall.range.first)
                        if (!Regex("\\bif\\s*\\([^)]*\\)\\s*(?:\\{\\s*)?return\\b", RegexOption.DOT_MATCHES_ALL)
                                .containsMatchIn(prefix)) {
                            val at = function.openBrace + 1 + recursiveCall.range.first
                            findings += Diagnostic(
                                line = lineAt(masked, at), col = columnAt(masked, at),
                                severity = Severity.WARNING,
                                message = "Recursive Linear Search: no stopping condition was found before the recursive call",
                                advice = "Add a base case that returns when the current index reaches the array/vector size (or when the remaining range is empty). A recursive search must stop before calling itself again.",
                                source = "example-database:${match.variant.id}"
                            )
                        }
                        val recursiveArgs = extractCallArguments(body, recursiveCall.range.last + 1)
                        val parameterNames = parameterNames(function.params)
                        if (recursiveArgs != null && parameterNames.isNotEmpty()) {
                            val normalArgs = recursiveArgs.splitTopLevel(',').map { it.trim() }
                            val sameArguments = normalArgs.size == parameterNames.size &&
                                normalArgs.indices.all { normalArgs[it] == parameterNames[it] }
                            if (sameArguments) {
                                val at = function.openBrace + 1 + recursiveCall.range.first
                                findings += Diagnostic(
                                    line = lineAt(masked, at), col = columnAt(masked, at),
                                    severity = Severity.WARNING,
                                    message = "Recursive Linear Search: the recursive call does not advance its arguments",
                                    advice = "Change the recursive call so the remaining search gets smaller, for example by calling the function with `index + 1` or `n - 1`. Passing exactly the same arguments repeats the same call forever.",
                                    source = "example-database:${match.variant.id}"
                                )
                            }
                        }
                    }
                }
            }
        }

        // Binary search: only inspect a loop whose bounds are explicitly named low/high.
        for (loop in findLoops(masked, 0, masked.length)) {
            val headerEnd = loop.bodyStart
            val header = masked.substring(loop.start, headerEnd)
            if (!Regex("\\bwhile\\s*\\(\\s*\\w+\\s*<=\\s*\\w+\\s*\\)").containsMatchIn(header)) continue
            val body = masked.substring(loop.bodyStart, loop.bodyEnd.coerceAtMost(masked.length))
            val lowName = Regex("\\bwhile\\s*\\(\\s*(\\w+)\\s*<=").find(header)?.groupValues?.get(1) ?: continue
            val highName = Regex("\\bwhile\\s*\\(\\s*\\w+\\s*<=\\s*(\\w+)").find(header)?.groupValues?.get(1) ?: continue
            val lowMid = Regex("\\b${Regex.escape(lowName)}\\s*=\\s*(mid|middle|m)\\s*;").find(body)
            val highMid = Regex("\\b${Regex.escape(highName)}\\s*=\\s*(mid|middle|m)\\s*;").find(body)
            val unsafeAssignments = listOfNotNull(
                lowMid?.let { it to lowName },
                highMid?.let { it to highName }
            )
            for ((assignment, variable) in unsafeAssignments) {
                val isLow = variable == lowName
                val at = loop.bodyStart + assignment.range.first
                findings += Diagnostic(
                    line = lineAt(masked, at), col = columnAt(masked, at),
                    severity = Severity.WARNING,
                    message = "Binary Search: $variable is assigned mid without shrinking the search range",
                    advice = if (isLow)
                        "The middle element has already been checked. With `low <= high`, update the lower bound to `mid + 1`; setting it to mid can repeat the same range."
                    else "The middle element has already been checked. With `low <= high`, update the upper bound to `mid - 1`; setting it to mid can repeat the same range.",
                    source = "example-database:binary-search-iterative"
                )
            }
        }

        // Bubble sort: the next-element access requires the inner loop to stop before the last index.
        val bubbleLoop = findLoops(masked, 0, masked.length).firstOrNull { loop ->
            val body = masked.substring(loop.bodyStart, loop.bodyEnd.coerceAtMost(masked.length))
            val header = masked.substring(loop.start, loop.bodyStart)
            val condition = Regex(";\\s*([^;]+);").find(header)?.groupValues?.get(1).orEmpty()
            Regex("\\[\\s*\\w+\\s*\\+\\s*1\\s*\\]").containsMatchIn(body) &&
                Regex("\\bif\\s*\\([^)]*\\[\\s*\\w+\\s*\\][^)]*>[^)]*\\[\\s*\\w+\\s*\\+\\s*1\\s*\\]").containsMatchIn(body) &&
                Regex("<\\s*(?:n|size|length|count)\\b").containsMatchIn(condition)
        }
        if (bubbleLoop != null) {
            findings += Diagnostic(
                    line = lineAt(masked, bubbleLoop.start), col = columnAt(masked, bubbleLoop.start),
                    severity = Severity.WARNING,
                    message = "Bubble Sort: the inner loop reads index + 1 but may reach the last index",
                    advice = "The comparison uses the next element (`values[j + 1]`). Stop the inner loop one position earlier, usually with `j < n - i - 1`, so the next-element access stays in range.",
                    source = "example-database:bubble-sort"
                )
        }

        // Common prime-check error: testing n % n by allowing i to reach n.
        val primeBound = Regex("\\bfor\\s*\\(\\s*(?:int|long|long\\s+long|size_t)?\\s*(\\w+)\\s*=\\s*2\\s*;\\s*\\1\\s*<=\\s*(\\w+)\\s*;").find(masked)
        if (primeBound != null) {
            val loop = findLoops(masked, primeBound.range.first, masked.length).firstOrNull()
            val body = loop?.let { masked.substring(it.bodyStart, it.bodyEnd.coerceAtMost(masked.length)) }.orEmpty()
            val variable = primeBound.groupValues[2]
            if (Regex("\\b${Regex.escape(variable)}\\s*%\\s*${Regex.escape(primeBound.groupValues[1])}\\b").containsMatchIn(body)) {
                findings += Diagnostic(
                    line = lineAt(masked, primeBound.range.first), col = columnAt(masked, primeBound.range.first),
                    severity = Severity.WARNING,
                    message = "Prime Check: the divisor loop includes the number itself",
                    advice = "A prime-check loop should stop before testing n % n. Use a strict upper bound such as `i < n`, or the more efficient condition `i <= n / i`.",
                    source = "example-database:prime-check"
                )
            }
        }

        // Factorial/product accumulator should not start at zero if it is only multiplied.
        val zeroProduct = Regex("\\b(?:int|long|long\\s+long|unsigned\\s+long\\s+long)\\s+(\\w*(?:fact|product|result)\\w*)\\s*=\\s*0\\s*;", RegexOption.IGNORE_CASE).find(masked)
        if (zeroProduct != null) {
            val name = zeroProduct.groupValues[1]
            if (Regex("\\b${Regex.escape(name)}\\s*\\*=").containsMatchIn(masked) ||
                Regex("\\b${Regex.escape(name)}\\s*=\\s*${Regex.escape(name)}\\s*\\*").containsMatchIn(masked)) {
                findings += Diagnostic(
                    line = lineAt(masked, zeroProduct.range.first), col = columnAt(masked, zeroProduct.range.first),
                    severity = Severity.WARNING,
                    message = "Factorial/Product: the running product starts at zero",
                    advice = "A product accumulator must normally start at 1. If it starts at 0, every later multiplication remains 0. Initialise this variable with `1`.",
                    source = "example-database:factorial-iterative"
                )
            }
        }

        // Leap-year condition must combine the /4 and non-century tests with AND.
        val leapOr = Regex("(\\b\\w+\\s*%\\s*4\\s*==\\s*0)\\s*\\|\\|\\s*(\\b\\w+\\s*%\\s*100\\s*!=\\s*0)").find(masked)
        if (leapOr != null && leapOr.groupValues[1].substringBefore('%').trim() == leapOr.groupValues[2].substringBefore('%').trim()) {
            findings += Diagnostic(
                line = lineAt(masked, leapOr.range.first), col = columnAt(masked, leapOr.range.first),
                severity = Severity.WARNING,
                message = "Leap Year: the /4 and non-century conditions use OR instead of AND",
                advice = "Use `(year % 4 == 0 && year % 100 != 0) || year % 400 == 0`. The first two conditions must both be true, unless the year is divisible by 400.",
                source = "example-database:leap-year"
            )
        }

        return findings.distinctBy { Triple(it.line, it.message, it.source) }
    }

    /** Only a recognised iterative Linear Search diagnostic can trigger this repair. */
    fun repairIterativeLinearSearch(code: String, diagnostics: List<Diagnostic>): String? {
        if (diagnostics.none { it.message == ITERATIVE_LINEAR_RETURN_MESSAGE && it.source.endsWith("linear-search-iterative") }) return null
        val masked = maskCommentsAndLiterals(code)
        val matches = detectLinearMatches(masked).filter { it.variant == LinearVariant.ITERATIVE }
        if (matches.size != 1) return null
        val function = matches.single().function ?: return null
        val loops = findLoops(masked, function.openBrace + 1, function.closeBrace)
        if (loops.size != 1) return null
        val loop = loops.single()
        if (loop.closeBrace <= loop.bodyStart || loop.bodyEnd != loop.closeBrace) return null
        val maskedBody = masked.substring(loop.bodyStart, loop.closeBrace)
        val returns = Regex("\\breturn\\s*-\\s*1\\s*;").findAll(maskedBody).toList()
        if (returns.size != 1) return null
        val target = returns.single()
        val absoluteStart = loop.bodyStart + target.range.first
        val absoluteEnd = loop.bodyStart + target.range.last + 1
        val rawPrefix = code.substring(loop.bodyStart, absoluteStart)
        val elseStart = Regex("\\belse\\s*$").find(rawPrefix)?.range?.first?.let { loop.bodyStart + it } ?: absoluteStart
        val statementStart = code.lastIndexOf('\n', absoluteStart - 1).let { it + 1 }
        val linePrefix = code.substring(statementStart, elseStart)
        val statementSuffix = code.substring(absoluteEnd, code.indexOf('\n', absoluteEnd).let { if (it < 0) code.length else it })
        // Keep the automatic fix narrow: the return statement must occupy its own line.
        if (linePrefix.any { !it.isWhitespace() } || statementSuffix.any { !it.isWhitespace() && it != '/' }) return null
        val loopLineStart = code.lastIndexOf('\n', loop.start - 1).let { it + 1 }
        val loopIndent = code.substring(loopLineStart, loop.start).takeWhile { it == ' ' || it == '\t' }
        val newlineAtClose = code.indexOf('\n', loop.closeBrace)
        if (newlineAtClose < 0) return null

        val edited = StringBuilder(code)
        // Delete only the early not-found return; leave the successful return intact.
        edited.delete(elseStart, absoluteEnd)
        val fallbackAlreadyExists = Regex("\\breturn\\s*-\\s*1\\s*;").containsMatchIn(
            masked.substring(loop.closeBrace + 1, function.closeBrace)
        )
        if (fallbackAlreadyExists) return edited.toString()

        val shift = absoluteEnd - elseStart
        val adjustedClose = loop.closeBrace - shift
        val adjustedNewline = edited.indexOf("\n", adjustedClose)
        if (adjustedNewline < 0) return null
        val insertion = loopIndent + "return -1;\n"
        edited.insert(adjustedNewline + 1, insertion)
        return edited.toString()
    }

    private fun detectLinearMatches(masked: String): List<LinearMatch> {
        val matches = mutableListOf<LinearMatch>()
        val functions = findFunctions(masked)
        // std::find is recognised by its algorithm call, not by the presence of an arbitrary loop.
        if (Regex("\\b(?:std\\s*::\\s*)?find\\s*\\(").containsMatchIn(masked) &&
            Regex("\\.\\s*(?:c?begin)\\s*\\(").containsMatchIn(masked) &&
            Regex("\\.\\s*(?:c?end)\\s*\\(").containsMatchIn(masked)) {
            matches += LinearMatch(LinearVariant.STD_FIND, functions.firstOrNull { f ->
                Regex("\\b(?:std\\s*::\\s*)?find\\s*\\(").containsMatchIn(masked.substring(f.openBrace, f.closeBrace))
            })
        }
        for (function in functions) {
            val body = masked.substring(function.openBrace + 1, function.closeBrace)
            // Binary search is deliberately excluded: its midpoint loop is not Linear Search.
            if (function.name.contains("binary", ignoreCase = true)) continue
            val hasIndexedEquality = Regex("\\[\\s*\\w+\\s*\\]").containsMatchIn(body) && Regex("==").containsMatchIn(body)
            val isNamedLinearSearch = function.name.contains("linear", ignoreCase = true)
            if (!isNamedLinearSearch && !hasIndexedEquality) continue
            val selfCall = Regex("\\b${Regex.escape(function.name)}\\s*\\(").containsMatchIn(body)
            if (selfCall) {
                if (matches.none { it.variant == LinearVariant.RECURSIVE && it.function?.name == function.name })
                    matches += LinearMatch(LinearVariant.RECURSIVE, function)
                continue
            }
            val loops = findLoops(masked, function.openBrace + 1, function.closeBrace)
            val loopSearch = loops.any { loop ->
                val loopBody = masked.substring(loop.bodyStart, loop.bodyEnd.coerceAtMost(masked.length))
                val header = masked.substring(loop.start, loop.bodyStart)
                val loopVariable = Regex("\\bfor\\s*\\(\\s*(?:(?:int|long|size_t)\\s+)?(\\w+)\\s*=\\s*0\\s*;").find(header)?.groupValues?.get(1)
                loopVariable != null &&
                    Regex("\\[\\s*${Regex.escape(loopVariable)}\\s*\\]").containsMatchIn(loopBody) &&
                    Regex("==").containsMatchIn(loopBody) &&
                    (Regex("\\breturn\\s+\\w+").containsMatchIn(loopBody) ||
                        Regex("\\b(?:found|match|present)\\s*=").containsMatchIn(loopBody))
            }
            if (loopSearch && matches.none { it.variant == LinearVariant.ITERATIVE && it.function?.name == function.name })
                matches += LinearMatch(LinearVariant.ITERATIVE, function)
        }
        return matches.distinctBy { it.variant to it.function?.name }
    }

    private fun findFunctions(code: String): List<FunctionSpan> {
        val result = mutableListOf<FunctionSpan>()
        val declaration = Regex("\\b([A-Za-z_]\\w*)\\s*\\(([^;{}]*)\\)\\s*\\{")
        for (match in declaration.findAll(code)) {
            val name = match.groupValues[1]
            if (name in setOf("if", "for", "while", "switch", "catch")) continue
            val open = match.range.last
            val close = matchingBrace(code, open) ?: continue
            result += FunctionSpan(name, match.groupValues[2], match.range.first, open, close)
        }
        return result
    }

    private fun findLoops(code: String, from: Int, until: Int): List<LoopSpan> {
        val result = mutableListOf<LoopSpan>()
        val loopPattern = Regex("\\b(?:for|while)\\s*\\(")
        for (match in loopPattern.findAll(code, from)) {
            if (match.range.first >= until) break
            val parenOpen = code.indexOf('(', match.range.first)
            val parenClose = matchingPair(code, parenOpen, '(', ')') ?: continue
            var cursor = parenClose + 1
            while (cursor < code.length && code[cursor].isWhitespace()) cursor++
            if (cursor >= until || cursor >= code.length) continue
            if (code[cursor] == '{') {
                val close = matchingBrace(code, cursor) ?: continue
                if (close > until) continue
                result += LoopSpan(match.range.first, cursor + 1, close, close)
            } else {
                val semicolon = code.indexOf(';', cursor)
                if (semicolon < 0 || semicolon >= until) continue
                result += LoopSpan(match.range.first, cursor, semicolon + 1, semicolon)
            }
        }
        return result
    }

    private fun matchingBrace(code: String, open: Int): Int? = matchingPair(code, open, '{', '}')

    private fun matchingPair(code: String, open: Int, left: Char, right: Char): Int? {
        if (open !in code.indices || code[open] != left) return null
        var depth = 0
        for (i in open until code.length) {
            if (code[i] == left) depth++
            if (code[i] == right) {
                depth--
                if (depth == 0) return i
            }
        }
        return null
    }

    private fun extractCallArguments(body: String, afterOpen: Int): String? {
        val open = body.indexOf('(', afterOpen - 1)
        if (open < 0) return null
        var depth = 0
        for (i in open until body.length) {
            when (body[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return body.substring(open + 1, i)
                }
            }
        }
        return null
    }

    private fun parameterNames(params: String): List<String> = params.splitTopLevel(',')
        .map { parameter ->
            val withoutDefault = parameter.substringBefore('=').trim()
            Regex("([A-Za-z_]\\w*)\\s*(?:\\[[^\\]]*\\]\\s*)?$").find(withoutDefault)?.groupValues?.get(1).orEmpty()
        }
        .filter { it.isNotBlank() && it !in setOf("const", "volatile") }

    private fun String.splitTopLevel(separator: Char): List<String> {
        val result = mutableListOf<String>()
        var depth = 0
        var start = 0
        for (i in indices) {
            when (this[i]) {
                '(', '<', '[' -> depth++
                ')', '>', ']' -> if (depth > 0) depth--
                separator -> if (depth == 0) { result += substring(start, i); start = i + 1 }
            }
        }
        result += substring(start)
        return result
    }

    private fun lineAt(code: String, offset: Int): Int = code.take(offset.coerceIn(0, code.length)).count { it == '\n' } + 1
    private fun columnAt(code: String, offset: Int): Int {
        val safe = offset.coerceIn(0, code.length)
        return safe - (code.lastIndexOf('\n', safe - 1) + 1) + 1
    }

    /** Blanks comments and literals while preserving every offset and newline. */
    private fun maskCommentsAndLiterals(source: String): String {
        val chars = source.toCharArray()
        var i = 0
        while (i < chars.size) {
            when {
                chars[i] == '/' && i + 1 < chars.size && chars[i + 1] == '/' -> {
                    chars[i] = ' '; chars[i + 1] = ' '; i += 2
                    while (i < chars.size && chars[i] != '\n') { chars[i] = ' '; i++ }
                }
                chars[i] == '/' && i + 1 < chars.size && chars[i + 1] == '*' -> {
                    chars[i] = ' '; chars[i + 1] = ' '; i += 2
                    while (i < chars.size) {
                        if (chars[i] == '*' && i + 1 < chars.size && chars[i + 1] == '/') {
                            chars[i] = ' '; chars[i + 1] = ' '; i += 2; break
                        }
                        if (chars[i] != '\n') chars[i] = ' '
                        i++
                    }
                }
                chars[i] == '"' || chars[i] == '\'' -> {
                    val quote = chars[i]
                    chars[i] = ' '
                    i++
                    while (i < chars.size) {
                        if (chars[i] == '\\') {
                            chars[i] = ' '
                            if (i + 1 < chars.size && chars[i + 1] != '\n') chars[i + 1] = ' '
                            i += 2
                        } else if (chars[i] == quote) {
                            chars[i] = ' '; i++; break
                        } else {
                            if (chars[i] != '\n') chars[i] = ' '
                            i++
                        }
                    }
                }
                else -> i++
            }
        }
        return String(chars)
    }
}
