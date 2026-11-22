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
                c == '(' || c == '[' || c == '{' -> stack.addLast(Triple(c, line, col))
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

        if (!Regex("""\bmain\s*\(""").containsMatchIn(code))
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
            Regex("""\b(setw|setprecision)\s*\(""") to "<iomanip>"
        )
        for ((rx, header) in needs) {
            val idx = lines.indexOfFirst { l -> !l.trimStart().startsWith("//") && !l.trimStart().startsWith("#") && rx.containsMatchIn(l) }
            if (idx < 0) continue
            val included = code.contains("#include $header") || code.contains("#include<${header.trim('<', '>')}>") ||
                code.contains("<bits/stdc++.h>") ||
                // iostream-family headers commonly pull these in
                (header == "<string>" && code.contains("<iostream>")) ||
                (header == "<cmath>" && code.contains("<iostream>") && false)
            if (!included) out += Diagnostic(idx + 1, 1, Severity.WARNING,
                "Uses something from $header but it is not included",
                "Add: #include $header", "pre-check")
        }

        // Missing semicolon heuristic: line ends with ) or identifier, next line starts a new statement
        for (k in 0 until lines.size - 1) {
            val t = lines[k].trim()
            val next = lines[k + 1].trim()
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("//")) continue
            if (Regex("""^(int|double|float|char|long|bool|string|auto)\s+\w+\s*(=\s*[^;{}]+)?$""").matches(t) && next.isNotEmpty() && !next.startsWith("{"))
                out += Diagnostic(k + 1, t.length, Severity.WARNING, "Statement may be missing a ';' at the end",
                    "Declarations end with a semicolon, e.g. int x = 5;", "pre-check")
        }
        return out
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
