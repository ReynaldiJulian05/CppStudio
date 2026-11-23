package com.reynaldi.cppstudio

enum class Severity { ERROR, WARNING, INFO }

/** One problem found in the code (by the compiler or by the built-in pre-check). */
data class Diagnostic(
    val line: Int,            // 1-based, 0 = unknown
    val col: Int,
    val severity: Severity,
    val message: String,
    val advice: String,
    val source: String        // "compiler", "pre-check" or "runtime"
)

data class RunResult(
    val compilerLog: String = "",
    val stdout: String = "",
    val stderr: String = "",
    val exitCode: Int? = null,
    val compiled: Boolean = false,
    val infraError: String? = null,   // network / toolchain problem, not a code problem
    val timedOut: Boolean = false,
    val server: String = "",          // which online server answered
    val failoverNote: String = ""     // why other servers were skipped
)

interface CompilerBackend {
    fun run(code: String, stdin: String, std: String, extraFlags: List<String> = emptyList()): RunResult
}

/** A non-destructive preview of deterministic code repairs. */
data class RepairPlan(
    val originalCode: String,
    val proposedCode: String,
    val changes: List<String>
)
