package com.reynaldi.cppstudio

/**
 * Makes `cin` interactive without a real terminal.
 *
 * A tiny hook is appended to the END of the user's code (so line numbers do not change).
 * When the program tries to read input that we have not provided yet, the hook flushes
 * stdout, writes SENTINEL to stderr and exits. The app then shows a cursor right after the
 * program's last prompt; when you press Enter the program is re-run with all inputs so far.
 */
object InputHook {
    const val SENTINEL = "\u0001CSX_NEED_INPUT\u0001"

    private val hook = """

#include <iostream>
#include <streambuf>
#include <cstdio>
#include <cstdlib>
namespace csx_hook {
struct Buf : std::streambuf {
    std::streambuf* src;
    explicit Buf(std::streambuf* s) : src(s) {}
    static void need() {
        std::cout.flush(); std::fflush(stdout);
        std::fputs("\x01""CSX_NEED_INPUT\x01", stderr); std::fflush(stderr);
        std::_Exit(0);
    }
    int_type underflow() override { int_type c = src->sgetc();  if (c == traits_type::eof()) need(); return c; }
    int_type uflow() override     { int_type c = src->sbumpc(); if (c == traits_type::eof()) need(); return c; }
};
struct Init { Init() { static Buf b(std::cin.rdbuf()); std::cin.rdbuf(&b); } };
static Init init_;
}
"""

    fun instrument(code: String): String = code + (if (code.endsWith("\n")) "" else "\n") + hook
}
