package com.reynaldi.cppstudio

/** Parses compiler output, runs quick static checks, and attaches repair advice. */
object Analyzer {

    private val diagRegex = Regex("""^(?:.*?):(\d+):(?:(\d+):)?\s*(fatal error|error|warning):\s*(.*)$""", RegexOption.MULTILINE)

    private val rules: List<Pair<Regex, String>> = listOf(
        Regex("'(cout|cin|cerr|endl)' (was not declared|does not name)|use of undeclared identifier '(cout|cin|cerr|endl)'") to
            "Add #include <iostream> at the top, and either write std::cout / std::cin or add \"using namespace std;\" after the includes.",
        Regex("'(vector|string|map|set|unordered_map|queue|stack|pair|sort)' (was not declared|does not name)|use of undeclared identifier '(vector|string|map|set|unordered_map|queue|stack|pair|sort)'|no template named '(vector|map|set|unordered_map|queue|stack|pair)'") to
            "A standard library name is missing its header. Include <vector>, <string>, <map>, <set>, <unordered_map>, <queue>, <stack>, <utility> or <algorithm> as needed, and use the std:: prefix.",
        Regex("not declared in this scope|undeclared identifier") to
            "The name is not known here. Check the spelling and capital letters, declare the variable/function before using it, and make sure it is not declared inside a { } block that has already ended.",
        Regex("expected ';'|expected ';' before|expected ';' after") to
            "A semicolon is missing. Look at the END of the reported line and the line above it: the compiler often notices only on the next line.",
        Regex("expected '\\}'|expected declaration at end of input|expected '\\}' at end of input") to
            "Braces are unbalanced: a '{' was never closed. Check every function, if, for and while block has its matching '}'.",
        Regex("expected '\\)'|expected '\\(") to
            "Parentheses do not match. Count the ( and ) on the reported line (and the one above).",
        Regex("expected primary-expression|expected expression") to
            "Something is missing or extra around that spot, such as a stray operator, an empty argument, or a missing value after '='.",
        Regex("No such file or directory|file not found") to
            "That #include cannot be found. Check the spelling. Use <...> for standard headers (e.g. <iostream>) and note that your own header files are not available in single-file mode.",
        Regex("undefined reference to `?'?main") to
            "The program has no main() function. Add: int main() { /* your code */ return 0; }",
        Regex("undefined reference|unresolved external|undefined symbol") to
            "A function is declared but never defined (or its definition has a different signature). Write the function body, or check name and parameter types match exactly.",
        Regex("'main' must return 'int'|main.* must return") to
            "Change the declaration to: int main().",
        Regex("redefinition of|redeclaration of|conflicting declaration") to
            "The same name is defined twice in the same scope. Rename one, or remove the duplicate.",
        Regex("has no member named|no member named") to
            "That class/struct has no such field or function. Check the spelling and the type of the variable you are using.",
        Regex("no matching function|no match for call|too few arguments|too many arguments|no matching member function") to
            "The call does not match any function. Check the number and types of the arguments against the function's definition.",
        Regex("no match for 'operator|invalid operands") to
            "That operator cannot be used on these types. Check that both sides have compatible types (e.g. you cannot add a string and a vector).",
        Regex("invalid conversion|cannot convert|could not convert") to
            "The types do not match. Convert explicitly (e.g. static_cast<int>(x)), or change the variable's type.",
        Regex("control reaches end of non-void function|non-void function does not return") to
            "A function that promises a value has a path with no return statement. Add 'return ...;' at the end of every path.",
        Regex("comparison of integer expressions of different signedness|comparison of integers of different signs") to
            "You are comparing int with size_t/unsigned. Use size_t for loop counters over containers, e.g. for (size_t i = 0; i < v.size(); i++).",
        Regex("unused variable|unused parameter") to
            "This variable is never used. Remove it, or use it. (Only a warning, the program still runs.)",
        Regex("may be used uninitialized|is used uninitialized|uninitialized") to
            "The variable is read before it has a value. Give it an initial value, e.g. int x = 0;",
        Regex("missing terminating|unterminated|missing closing quote") to
            "A string or character literal is missing its closing quote.",
        Regex("stray '|extended character|invalid character") to
            "There is a strange character in the code, usually from copy-paste (curly quotes “ ” or a non-breaking space). Retype those characters.",
        Regex("variable length array|ISO C\\+\\+ forbids variable") to
            "Array size must be a constant in standard C++. Use std::vector<int> v(n); instead of int a[n];",
        Regex("division by zero") to
            "You divide by a constant zero. Check the divisor.",
        Regex("format '%.*' expects|format.*expects argument") to
            "printf format and arguments do not match (e.g. %d needs an int, %f a double, %s a C string). Consider std::cout instead.",
        Regex("using namespace.*expected|expected nested-name-specifier|expected namespace") to
            "Check the syntax: using namespace std;"
    )

    fun adviceFor(message: String): String {
        for ((rx, tip) in rules) if (rx.containsMatchIn(message)) return tip
        return "Read the message carefully and fix the reported line first. Often, one early error causes several later ones, so fix the FIRST error and run again."
    }

    fun parseCompilerLog(log: String): List<Diagnostic> {
        val out = mutableListOf<Diagnostic>()
        for (m in diagRegex.findAll(log)) {
            val sev = if (m.groupValues[3] == "warning") Severity.WARNING else Severity.ERROR
            val msg = m.groupValues[4].trim()
            out += Diagnostic(
                line = m.groupValues[1].toIntOrNull() ?: 0,
                col = m.groupValues[2].toIntOrNull() ?: 0,
                severity = sev, message = msg, advice = adviceFor(msg), source = "compiler"
            )
        }
        // Linker errors have no line number
        for (l in log.lines()) {
            if ((l.contains("undefined reference to") || l.contains("ld returned")) && !diagRegex.containsMatchIn(l)) {
                val msg = l.substringAfter(": ", l).trim()
                if (out.none { it.message == msg }) out += Diagnostic(0, 0, Severity.ERROR, msg, adviceFor(msg), "compiler")
            }
        }
        return out.distinctBy { Triple(it.line, it.severity, it.message) }
    }

    /** Fast offline check: runs before compiling, works without internet. */
    fun preCheck(code: String): List<Diagnostic> {
        val out = mutableListOf<Diagnostic>()
        val stack = ArrayDeque<Triple<Char, Int, Int>>()
        var line = 1; var col = 0
        var i = 0
        val n = code.length
        val pairs = mapOf(')' to '(', ']' to '[', '}' to '{')
        while (i < n) {
            val c = code[i]
            col++
            when {
                c == '\n' -> { line++; col = 0 }
                c == '/' && i + 1 < n && code[i + 1] == '/' -> { while (i < n && code[i] != '\n') i++; continue }
                c == '/' && i + 1 < n && code[i + 1] == '*' -> {
                    val startLine = line
                    i += 2
                    var closed = false
                    while (i < n) {
                        if (code[i] == '\n') line++
                        if (code[i] == '*' && i + 1 < n && code[i + 1] == '/') { i += 2; closed = true; break }
                        i++
                    }
                    if (!closed) out += Diagnostic(startLine, 1, Severity.ERROR, "Comment started with /* is never closed",
                        "Add */ to close the comment.", "pre-check")
                    continue
                }
                c == '"' || c == '\'' -> {
                    val q = c; val startLine = line; val startCol = col
                    i++
                    var closed = false
                    while (i < n && code[i] != '\n') {
                        if (code[i] == '\\') { i += 2; continue }
                        if (code[i] == q) { closed = true; break }
                        i++
                    }
                    if (!closed) out += Diagnostic(startLine, startCol, Severity.ERROR,
                        "Missing closing ${if (q == '"') "double quote (\")" else "single quote (')"}",
                        "Close the ${if (q == '"') "string" else "character"} with the same quote before the end of the line.", "pre-check")
                    if (i < n && code[i] == '\n') continue
                }
                c == '(' || c == '[' || c == '{' -> stack.add(Triple(c, line, col))
                c == ')' || c == ']' || c == '}' -> {
                    if (stack.isEmpty() || stack.last().first != pairs[c]) {
                        out += Diagnostic(line, col, Severity.ERROR, "Unexpected '$c' with no matching '${pairs[c]}'",
                            "Remove this '$c' or add the missing '${pairs[c]}' before it.", "pre-check")
                    } else stack.removeLast()
                }
            }
            i++
        }
        for ((c, l, cl) in stack) {
            val close = when (c) { '(' -> ')'; '[' -> ']'; else -> '}' }
            out += Diagnostic(l, cl, Severity.ERROR, "'$c' is never closed",
                "Add a matching '$close' (usually at the end of that block or statement).", "pre-check")
        }

        val analysisLines = stripCommentsAndLiterals(code)
        val analysisCode = analysisLines.joinToString("\n")
        if (!Regex("""\bmain\s*\(""").containsMatchIn(analysisCode))
            out += Diagnostic(0, 0, Severity.ERROR, "No main() function found",
                "Every program needs an entry point: int main() { ... return 0; }", "pre-check")

        val lines = code.lines()
        val needs = listOf(
            Regex("""\b(cout|cin|cerr|endl)\b""") to "<iostream>",
            Regex("""\bvector\s*<""") to "<vector>",
            Regex("""\bstring\b""") to "<string>",
            Regex("""\b(map|multimap)\s*<""") to "<map>",
            Regex("""\b(unordered_map)\s*<""") to "<unordered_map>",
            Regex("""\b(sort|reverse|max_element|min_element|swap)\s*\(""") to "<algorithm>",
            Regex("""\b(printf|scanf|puts)\s*\(""") to "<cstdio>",
            Regex("""\b(sqrt|pow|abs|floor|ceil)\s*\(""") to "<cmath>",
            Regex("""\b(setw|setprecision)\s*\(""") to "<iomanip>",
            Regex("""\b(accumulate|iota|inner_product)\s*\(""") to "<numeric>",
            Regex("""\b(ifstream|ofstream|fstream)\b""") to "<fstream>",
            Regex("""\b(stringstream|istringstream|ostringstream)\b""") to "<sstream>",
            Regex("""\b(toupper|tolower|isdigit|isalpha|isspace)\s*\(""") to "<cctype>",
            Regex("""\b(strlen|strcmp|strcpy|strcat)\s*\(""") to "<cstring>",
            Regex("""\bnumeric_limits\s*<""") to "<limits>",
            Regex("""\b(rand|srand|exit|atoi|atof)\s*\(""") to "<cstdlib>",
            Regex("""\b(time|difftime|clock)\s*\(""") to "<ctime>"
        )
        val includedHeaders = lines.mapNotNull { lineText ->
            Regex("""^\s*#\s*include\s*[<"]([^>"]+)[>"]""").find(lineText)?.groupValues?.get(1)
        }.toSet()
        for ((rx, header) in needs) {
            val idx = analysisLines.indexOfFirst { l -> !l.trimStart().startsWith("#") && rx.containsMatchIn(l) }
            if (idx < 0) continue
            val included = header.trim('<', '>') in includedHeaders ||
                "bits/stdc++.h" in includedHeaders ||
                // iostream-family headers commonly pull these in
                (header == "<string>" && code.contains("<iostream>")) ||
                (header == "<cmath>" && code.contains("<iostream>") && false)
            if (!included) out += Diagnostic(idx + 1, 1, Severity.WARNING,
                "Uses something from $header but it is not included",
                "Add: #include $header", "pre-check")
        }

        val usesNamespaceStd = Regex("""\busing\s+namespace\s+std\s*;""").containsMatchIn(analysisCode)
        val unqualifiedStd = Regex("""(?<![A-Za-z0-9_:])(cout|cin|cerr|clog|endl|vector|string|map|multimap|set|unordered_map|queue|stack|pair|sort|reverse|setw|setprecision)\b""")
        val unqualifiedMatch = unqualifiedStd.find(analysisCode)
        if (!usesNamespaceStd && unqualifiedMatch != null) {
            val lineNumber = analysisCode.substring(0, unqualifiedMatch.range.first).count { it == '\n' } + 1
            out += Diagnostic(lineNumber, 1, Severity.WARNING,
                "Standard-library names are used without std:: or using namespace std",
                "Use std::cout / std::cin and other std:: names, or add using namespace std; after the includes.", "pre-check")
        }

        // Missing semicolon heuristic for common beginner statements. This is intentionally
        // conservative: control headers and function definitions are not treated as statements.
        for (k in analysisLines.indices) {
            val t = analysisLines[k].trim()
            val next = analysisLines.getOrNull(k + 1)?.trim().orEmpty()
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("//") || t.startsWith("/*")) continue
            if (next == ";" || next.startsWith("{")) continue
            if (k > 0 && endsWithContinuation(analysisLines[k - 1].trim())) continue
            if (isLikelyStatementWithoutSemicolon(t)) {
                val originalLine = lines.getOrNull(k).orEmpty()
                val endColumn = originalLine.trimEnd().length.coerceAtLeast(1)
                out += Diagnostic(k + 1, endColumn, Severity.ERROR,
                    "Statement may be missing a ';' at the end",
                    "C++ statements usually end with a semicolon. Add ';' after this declaration, assignment, stream operation, return, or function call.", "pre-check")
            }
        }
        // Duplicate standard headers are harmless but usually indicate an accidental copy-paste.
        val seenHeaders = HashSet<String>()
        for ((index, lineText) in lines.withIndex()) {
            val include = Regex("""^\s*#\s*include\s*[<"]([^>"]+)[>"]""")
                .find(lineText)?.groupValues?.get(1)
            if (include != null && !seenHeaders.add(include)) {
                out += Diagnostic(index + 1, 1, Severity.WARNING,
                    "Header <$include> is included more than once",
                    "Keep one #include for each header. Repeated includes are usually unnecessary.", "pre-check")
            }

            if (lineText.isNotEmpty() && lineText.last() in listOf(' ', '\t')) {
                out += Diagnostic(index + 1, lineText.length, Severity.INFO,
                    "This line has trailing whitespace",
                    "The Tidy button removes spaces and tabs at the end of lines.", "pre-check")
            }
            val indent = lineText.takeWhile { it == ' ' || it == '\t' }
            if (indent.contains(' ') && indent.contains('\t')) {
                out += Diagnostic(index + 1, 1, Severity.WARNING,
                    "Indentation mixes tabs and spaces",
                    "Choose one indentation style. Tidy can use four spaces or match the current file.", "pre-check")
            }
        }

        // Common beginner mistakes and non-portable console calls.
        for ((index, lineText) in lines.withIndex()) {
            val codeLine = analysisLines.getOrElse(index) { "" }
            if (Regex("""\b(if|while)\s*\(\s*[A-Za-z_][A-Za-z0-9_]*\s*=(?!=)""").containsMatchIn(codeLine)) {
                out += Diagnostic(index + 1, 1, Severity.WARNING,
                    "A condition appears to use assignment (=) instead of comparison (==)",
                    "Check whether you intended to compare values with ==. Assignment inside a condition can be intentional, so verify this line.", "pre-check")
            }
            if (Regex("""\bsystem\s*\(\s*"(pause|cls)"\s*\)""").containsMatchIn(lineText)) {
                out += Diagnostic(index + 1, 1, Severity.WARNING,
                    "This console command is platform-dependent",
                    "system(\"pause\") and system(\"cls\") may not work on online compilers or Android. Prefer standard C++ input/output.", "pre-check")
            }
            if (Regex("""#\s*include\s*[<"]conio\.h[>"]""").containsMatchIn(lineText)) {
                out += Diagnostic(index + 1, 1, Severity.WARNING,
                    "<conio.h> is not part of standard C++",
                    "Use standard headers such as <iostream> so the program is more portable across online and offline compilers.", "pre-check")
            }
            if (Regex("""\bwhile\s*\(\s*(true|1)\s*\)""").containsMatchIn(codeLine)) {
                out += Diagnostic(index + 1, 1, Severity.WARNING,
                    "This loop condition is always true",
                    "An intentional infinite loop is valid, but make sure the loop has a reachable break, return, or other exit condition.", "pre-check")
            }
            if (Regex("""(?:/|%)\s*0+(?:\.0+)?(?![\d.])""").containsMatchIn(codeLine)) {
                out += Diagnostic(index + 1, 1, Severity.ERROR,
                    "Division or modulo by the literal zero",
                    "A divisor of zero is invalid. Check the value before using / or %.", "pre-check")
            }
        }

        // Highlight indentation mismatches in both the Problems list and the line-number gutter.
        for (lineNumber in CppFormatter.findUntidyLines(code).take(80)) {
            out += Diagnostic(lineNumber, 1, Severity.WARNING,
                "Indentation does not match the detected block layout",
                "Use Tidy → Professional (4 spaces), or choose Match current file to keep your own indentation style.", "pre-check")
        }

        return out.distinctBy { Triple(it.line, it.severity, it.message) }
    }

    /**
     * Build a conservative repair preview. Only deterministic edits are included; the user's
     * source is never changed until Keep changes is pressed in the preview dialog.
     */
    fun buildRepairPlan(code: String, diagnostics: List<Diagnostic> = preCheck(code)): RepairPlan? {
        val lines = code.split("\n").toMutableList()
        val descriptions = linkedSetOf<String>()
        val headersToAdd = linkedSetOf<String>()
        var addStdNamespace = false
        val existingHeaders = code.lineSequence().mapNotNull { sourceLine ->
            Regex("""^\s*#\s*include\s*[<"]([^>"]+)[>"]""").find(sourceLine)?.groupValues?.get(1)
        }.toSet()
        var shouldTidyIndentation = false

        for (d in diagnostics) {
            val compilerSemicolonLine = if (d.source == "compiler" &&
                d.message.contains("expected ';'", ignoreCase = true) &&
                d.message.contains("before", ignoreCase = true)) d.line - 1 else d.line
            val semicolonIssue = d.message.contains("missing a ';'", ignoreCase = true) ||
                (d.source == "compiler" && d.message.contains("expected ';'", ignoreCase = true))
            if (semicolonIssue && compilerSemicolonLine in 1..lines.size) {
                val index = compilerSemicolonLine - 1
                val repaired = appendSemicolonBeforeLineComment(lines[index])
                if (repaired != lines[index]) {
                    lines[index] = repaired
                    descriptions += "Add a missing semicolon on line $compilerSemicolonLine"
                }
                continue
            }
            if (d.line !in 1..lines.size) continue
            val index = d.line - 1
            val current = lines[index]
            when {
                d.message.startsWith("Uses something from ") && d.message.contains("but it is not included") -> {
                    val header = Regex("""Uses something from (<[^>]+>)""").find(d.message)?.groupValues?.get(1)
                    if (header != null && header.trim('<', '>') !in existingHeaders &&
                        "bits/stdc++.h" !in existingHeaders) {
                        headersToAdd += header
                    }
                }
                d.message.startsWith("Standard-library names are used without std::") -> {
                    addStdNamespace = true
                }
                d.message.startsWith("Header <") && d.message.contains("included more than once") -> {
                    if (current.isNotBlank()) {
                        lines[index] = ""
                        descriptions += "Remove duplicate include on line ${d.line}"
                    }
                }
                d.message == "This line has trailing whitespace" -> {
                    val repaired = current.trimEnd()
                    if (repaired != current) {
                        lines[index] = repaired
                        descriptions += "Remove trailing whitespace on line ${d.line}"
                    }
                }
                d.message.contains("Indentation does not match") -> shouldTidyIndentation = true
            }
        }

        var proposed = lines.joinToString("\n")
        val unclosed = diagnostics.mapNotNull { d ->
            Regex("""^'([({\[])' is never closed$""").find(d.message)?.groupValues?.get(1)?.firstOrNull()?.let {
                Triple(it, d.line, d.col)
            }
        }.sortedWith(compareByDescending<Triple<Char, Int, Int>> { it.second }.thenByDescending { it.third })
        if (unclosed.isNotEmpty()) {
            val braceCount = unclosed.count { it.first == '{' }
            var remainingBraces = braceCount
            val closing = StringBuilder()
            if (proposed.isNotEmpty() && !proposed.endsWith("\n")) closing.append('\n')
            for ((open, _, _) in unclosed) {
                val close = when (open) { '(' -> ')'; '[' -> ']'; else -> '}' }
                if (open == '{') remainingBraces = (remainingBraces - 1).coerceAtLeast(0)
                if (open == '{') closing.append("    ".repeat(remainingBraces))
                closing.append(close).append('\n')
            }
            proposed += closing.toString()
            descriptions += "Add ${unclosed.size} missing closing delimiter(s) at the end of the code"
            shouldTidyIndentation = true
        }
        if (headersToAdd.isNotEmpty()) {
            val currentLines = proposed.split("\n").toMutableList()
            val lastInclude = currentLines.indexOfLast { Regex("""^\s*#\s*include\b""").containsMatchIn(it) }
            val insertAt = if (lastInclude >= 0) lastInclude + 1 else 0
            currentLines.addAll(insertAt, headersToAdd.sorted().map { "#include $it" })
            proposed = currentLines.joinToString("\n")
            descriptions += "Add missing standard header${if (headersToAdd.size > 1) "s" else ""}: ${headersToAdd.joinToString(", ")}"
        }

        if (addStdNamespace && !Regex("""\busing\s+namespace\s+std\s*;""").containsMatchIn(proposed)) {
            val currentLines = proposed.split("\n").toMutableList()
            val lastInclude = currentLines.indexOfLast { Regex("""^\s*#\s*include\b""").containsMatchIn(it) }
            val insertAt = if (lastInclude >= 0) lastInclude + 1 else 0
            currentLines.add(insertAt, "using namespace std;")
            proposed = currentLines.joinToString("\n")
            descriptions += "Add using namespace std; for unqualified standard-library names"
        }

        if (shouldTidyIndentation) {
            val tidied = CppFormatter.format(proposed, CppFormatter.Style.MATCH_CURRENT_FILE)
            if (tidied != proposed) {
                proposed = tidied
                descriptions += "Correct inconsistent indentation"
            }
        }

        if (proposed == code || descriptions.isEmpty()) return null
        return RepairPlan(code, proposed, descriptions.toList())
    }

    /** Returns a code-only view per physical line, preserving enough tokens to identify statements. */
    private fun stripCommentsAndLiterals(code: String): List<String> {
        val result = StringBuilder(code.length)
        var i = 0
        var inBlockComment = false
        while (i < code.length) {
            val c = code[i]
            if (inBlockComment) {
                if (c == '*' && i + 1 < code.length && code[i + 1] == '/') {
                    result.append("  "); i += 2; inBlockComment = false
                } else {
                    result.append(if (c == '\n') '\n' else ' '); i++
                }
                continue
            }
            if (c == '/' && i + 1 < code.length && code[i + 1] == '/') {
                while (i < code.length && code[i] != '\n') { result.append(' '); i++ }
                continue
            }
            if (c == '/' && i + 1 < code.length && code[i + 1] == '*') {
                result.append("  "); i += 2; inBlockComment = true; continue
            }
            if (c == '"' || c == '\'') {
                val quote = c
                result.append('L') // retain a harmless literal token for statement recognition
                result.append(' ')
                i++
                var escaped = false
                while (i < code.length) {
                    val q = code[i]
                    if (q == '\n') { result.append('\n'); i++; break }
                    result.append(' ')
                    i++
                    if (escaped) escaped = false
                    else if (q == '\\') escaped = true
                    else if (q == quote) break
                }
                continue
            }
            result.append(c); i++
        }
        return result.toString().split("\n")
    }

    private fun endsWithContinuation(line: String): Boolean =
        line.endsWith("<<") || line.endsWith(">>") || line.endsWith("=") ||
            line.endsWith("+") || line.endsWith("-") || line.endsWith("*") ||
            line.endsWith("/") || line.endsWith(",") || line.endsWith("&&") ||
            line.endsWith("||") || line.endsWith(".") || line.endsWith("->") ||
            line.endsWith("?") || line.endsWith("\\")

    private fun isLikelyStatementWithoutSemicolon(line: String): Boolean {
        val t = line.trim()
        if (t.isEmpty() || t.startsWith("#") || t.endsWith(";") || t.endsWith("{") ||
            t.endsWith("}") || t.endsWith(":") || t.startsWith("//") || t.startsWith("/*")) return false
        if (Regex("""^(if|for|while|switch|catch|else|do|try|class|struct|enum|namespace|union)\b""").containsMatchIn(t)) return false
        if (Regex("""^(case\b.*|default)\s*:""").containsMatchIn(t)) return false
        if (Regex("""^}\s*while\s*\(.*\)$""").containsMatchIn(t)) return true
        if (endsWithContinuation(t)) return false
        val declaration = Regex("""^(?:(?:const|static|unsigned|signed|long|short|volatile|extern|register)\s+)*(?:int|double|float|char|bool|string|auto|size_t|[A-Za-z_]\w*(?:::\w+)*(?:\s*<[^;{}]+>)?)\s*[*&]?\s+[A-Za-z_]\w*(?:\s*\[[^]]*])?(?:\s*(?:,\s*[A-Za-z_]\w*(?:\s*=\s*[^,]+)?)*|\s*=\s*.+)?$""")
        val stream = Regex("""^(?:(?:std::)?(?:cout|cin|cerr|clog))\s*(?:<<|>>).+""")
        val simple = Regex("""^(?:using\s+namespace\s+[A-Za-z_]\w*(?:::\w*)*|return\b\s*.*|throw\b\s*.+|break|continue)$""")
        val assignment = Regex("""^(?:[A-Za-z_]\w*(?:(?:::|\.|->)\s*[A-Za-z_]\w*)*)\s*(?:=|\+=|-=|\*=|/=|%=|<<=|>>=|&=|\|=|\^=)\s*.+$""")
        val increment = Regex("""^(?:\+\+|--)?[A-Za-z_]\w*(?:\+\+|--)$""")
        val call = Regex("""^(?:(?:[A-Za-z_]\w*|std::[A-Za-z_]\w*)(?:(?:::|\.|->)\s*[A-Za-z_]\w*)*)\s*\([^;{}]*\)$""")
        return declaration.matches(t) || stream.matches(t) || simple.matches(t) ||
            assignment.matches(t) || increment.matches(t) || call.matches(t)
    }

    private fun appendSemicolonBeforeLineComment(line: String): String {
        val trimmed = line.trimEnd()
        if (trimmed.isEmpty() || trimmed.endsWith(";") || trimmed.endsWith("{") || trimmed.endsWith("}")) return line
        var quote: Char? = null
        var escaped = false
        var commentAt = -1
        var i = 0
        while (i + 1 < line.length) {
            val c = line[i]
            if (quote != null) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == quote) quote = null
            } else if (c == '"' || c == '\'') quote = c
            else if (c == '/' && line[i + 1] == '/') { commentAt = i; break }
            i++
        }
        if (commentAt >= 0) {
            val codePart = line.substring(0, commentAt).trimEnd()
            if (codePart.isEmpty() || codePart.endsWith(";") || codePart.endsWith("{") || codePart.endsWith("}")) return line
            return "$codePart;  ${line.substring(commentAt)}"
        }
        return "$trimmed;"
    }

    fun runtimeAdvice(r: RunResult): List<Diagnostic> {
        val out = mutableListOf<Diagnostic>()
        val err = r.stderr
        fun add(msg: String, adv: String) { out += Diagnostic(0, 0, Severity.ERROR, msg, adv, "runtime") }
        if (r.timedOut) add("The program ran too long and was stopped",
            "Probably an infinite loop (a loop condition that never becomes false) or waiting for input that never comes. Check loop counters, or fill the Input tab with the values the program reads.")
        when (r.exitCode) {
            139, 11 -> add("Crash: segmentation fault",
                "The program touched memory it should not. Common causes: array/vector index out of range, using a null or uninitialized pointer, or infinite recursion. Check indexes against size() and initialize pointers.")
            136, 8 -> add("Crash: arithmetic error (SIGFPE)", "Usually integer division or modulo by zero. Check the divisor before dividing.")
            134, 6 -> add("Program aborted (SIGABRT)", "Often an uncaught exception, a failed assert(), or invalid free/delete. See the error text in the Output tab.")
            137, 9 -> add("Program was killed", "Possibly out of memory (very large array/vector) or time limit exceeded.")
            else -> {}
        }
        if (err.contains("terminate called after throwing")) add("Uncaught exception",
            "Wrap risky code in try { } catch (const std::exception& e) { }, and check indexes for .at() calls or invalid stoi() input.")
        if (err.contains("bad_alloc")) add("Out of memory", "You asked for far too much memory. Check the size passed to vector/new.")
        if (err.contains("AddressSanitizer") || err.contains("stack-buffer-overflow") || err.contains("heap-buffer-overflow"))
            add("Memory error detected", "You wrote or read past the end of an array. Check loop bounds (use < size, not <= size).")
        if (r.exitCode != null && r.exitCode != 0 && out.isEmpty() && r.compiled)
            out += Diagnostic(0, 0, Severity.INFO, "Program finished with exit code ${r.exitCode}",
                "main() returns a non-zero value or the program ended abnormally. Return 0 from main() when everything is fine.", "runtime")
        return out
    }
}
