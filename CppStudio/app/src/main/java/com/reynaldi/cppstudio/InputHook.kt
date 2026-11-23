package com.reynaldi.cppstudio

/**
 * Makes common C++ and C standard-input calls interactive in the editor.
 *
 * The app re-runs the program when the user submits another input line. When a read reaches
 * EOF before the user presses "End program", this hook flushes stdout and writes SENTINEL.
 * The hook is appended after the source so compiler diagnostic line numbers stay unchanged.
 */
object InputHook {
    const val SENTINEL = "\u0001CSX_NEED_INPUT\u0001"

    private val hook = """

#include <iostream>
#include <streambuf>
#include <cstdio>
#include <cstdarg>
#include <cstdlib>
namespace csx_hook {
    static void need() {
        std::cout.flush();
        std::fflush(stdout);
        std::fputs("\x01""CSX_NEED_INPUT\x01", stderr);
        std::fflush(stderr);
        std::_Exit(0);
    }

    struct Buf : std::streambuf {
        std::streambuf* src;
        explicit Buf(std::streambuf* s) : src(s) {}
        int_type underflow() override {
            int_type c = src->sgetc();
            if (c == traits_type::eof()) need();
            return c;
        }
        int_type uflow() override {
            int_type c = src->sbumpc();
            if (c == traits_type::eof()) need();
            return c;
        }
    };

    struct Init {
        Init() {
            static Buf buffer(std::cin.rdbuf());
            std::cin.rdbuf(&buffer);
        }
    };
    static Init init_;
}

// Intercept C input functions too. vscanf/vfscanf/fgetc are used internally so these
// wrappers do not call themselves recursively. Checking feof also catches partial formats
// such as scanf("%d %d", ...) when only the first value has been submitted so far.
extern "C" int scanf(const char* format, ...) noexcept {
    va_list args;
    va_start(args, format);
    int result = ::vscanf(format, args);
    va_end(args);
    if (result == EOF || std::feof(stdin)) csx_hook::need();
    return result;
}

extern "C" int fscanf(FILE* stream, const char* format, ...) noexcept {
    va_list args;
    va_start(args, format);
    int result = ::vfscanf(stream, format, args);
    va_end(args);
    if (stream == stdin && (result == EOF || std::feof(stdin))) csx_hook::need();
    return result;
}

extern "C" int getchar() noexcept {
    int result = ::fgetc(stdin);
    if (result == EOF) csx_hook::need();
    return result;
}

extern "C" char* fgets(char* buffer, int size, FILE* stream) noexcept {
    if (size <= 0) return nullptr;
    if (size == 1) { buffer[0] = '\0'; return buffer; }
    int used = 0;
    while (used < size - 1) {
        int ch = ::fgetc(stream);
        if (ch == EOF) {
            if (stream == stdin && used == 0) csx_hook::need();
            break;
        }
        buffer[used++] = static_cast<char>(ch);
        if (ch == '\n') break;
    }
    if (used == 0) return nullptr;
    buffer[used] = '\0';
    return buffer;
}
""".trimIndent()

    /** Turn off sync-with-stdio disabling so std::cin keeps the interactive stream buffer. */
    private fun keepCinHookInstalled(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0
        var blockComment = false
        var quote: Char? = null
        var escaped = false
        var rawEnd: String? = null

        while (i < source.length) {
            if (rawEnd != null) {
                val end = source.indexOf(rawEnd, i)
                if (end < 0) {
                    out.append(source, i, source.length)
                    break
                }
                out.append(source, i, end + rawEnd.length)
                i = end + rawEnd.length
                rawEnd = null
                continue
            }
            if (blockComment) {
                if (source.startsWith("*/", i)) {
                    out.append("*/")
                    i += 2
                    blockComment = false
                } else {
                    out.append(source[i++])
                }
                continue
            }
            val q = quote
            if (q != null) {
                val c = source[i]
                out.append(c)
                i++
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == q) quote = null
                continue
            }
            if (source.startsWith("//", i)) {
                val end = source.indexOf('\n', i).let { if (it < 0) source.length else it }
                out.append(source, i, end)
                i = end
                continue
            }
            if (source.startsWith("/*", i)) {
                out.append("/*")
                i += 2
                blockComment = true
                continue
            }
            val rawPrefix = listOf("u8R\"", "uR\"", "UR\"", "LR\"", "R\"")
                .firstOrNull { source.startsWith(it, i) }
            if (rawPrefix != null) {
                val quoteStart = i + rawPrefix.length - 1
                val open = source.indexOf('(', quoteStart + 1)
                if (open >= 0 && open - quoteStart <= 17) {
                    val delimiter = source.substring(quoteStart + 1, open)
                    rawEnd = ")$delimiter\""
                    out.append(source, i, open + 1)
                    i = open + 1
                    continue
                }
            }
            if (source[i] == '"' || source[i] == '\'') {
                quote = source[i]
                out.append(source[i++])
                continue
            }

            val name = "sync_with_stdio"
            val isName = source.regionMatches(i, name, 0, name.length) &&
                (i == 0 || !(source[i - 1].isLetterOrDigit() || source[i - 1] == '_')) &&
                (i + name.length == source.length || !(source[i + name.length].isLetterOrDigit() || source[i + name.length] == '_'))
            if (isName) {
                var p = i + name.length
                while (p < source.length && source[p].isWhitespace()) p++
                if (p < source.length && source[p] == '(') {
                    p++
                    while (p < source.length && source[p].isWhitespace()) p++
                    if (source.regionMatches(p, "false", 0, 5) &&
                        (p + 5 == source.length || !(source[p + 5].isLetterOrDigit() || source[p + 5] == '_'))) {
                        val after = p + 5
                        var end = after
                        while (end < source.length && source[end].isWhitespace()) end++
                        if (end < source.length && source[end] == ')') {
                            out.append(source, i, p).append("true").append(source, after, end + 1)
                            i = end + 1
                            continue
                        }
                    }
                }
            }
            out.append(source[i++])
        }
        return out.toString()
    }

    fun instrument(code: String): String {
        val safeSource = keepCinHookInstalled(code)
        return safeSource + (if (safeSource.endsWith("\n")) "" else "\n") + hook + "\n"
    }
}
