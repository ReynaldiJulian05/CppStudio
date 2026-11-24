package com.reynaldi.cppstudio

import org.json.JSONObject

/** Compiles on a remote GCC via the Wandbox public API. Needs internet, keeps the APK tiny. */
class WandboxBackend : CompilerBackend {
    override fun run(code: String, stdin: String, std: String, extraFlags: List<String>): RunResult {
        return try {
            val body = JSONObject().apply {
                put("code", code)
                put("compiler", "gcc-head")
                put("compiler-option-raw", (listOf("-std=$std", "-Wall", "-Wextra") + extraFlags).joinToString("\n"))
                put("stdin", stdin)
                put("save", false)
            }.toString()

            val reply = Http.postJson(
                "https://wandbox.org/api/compile.json",
                body,
                accept = "application/json",
                readTimeoutMs = 25000,
                connectTimeoutMs = 8000
            )
            val code2 = reply.code
            val text = reply.body
            if (code2 !in 200..299) return RunResult(infraError = "Server returned HTTP $code2. $text".take(400))
            if (reply.truncated) return RunResult(infraError = "Wandbox returned more than 1.5 MB. Reduce the program's output and try again.")

            val j = JSONObject(text)
            val compilerMsg = j.optString("compiler_message")
            val log = if (compilerMsg.isNotEmpty()) compilerMsg else listOf(
                j.optString("compiler_output"), j.optString("compiler_error")
            ).filter { it.isNotBlank() }.joinToString("\n")
            val status = j.optString("status", "").toIntOrNull()
            val signal = j.optString("signal", "")
            // Wandbox may omit the output fields when a successful program prints nothing.
            val ran = status != null || signal.isNotBlank() ||
                j.has("program_message") || j.has("program_output") || j.has("program_error")
            val exit = when {
                !ran -> null
                signal == "Segmentation fault" -> 139
                signal == "Floating point exception" -> 136
                signal == "Aborted" -> 134
                signal == "Killed" -> 137
                else -> status
            }
            RunResult(
                compilerLog = log,
                stdout = j.optString("program_output"),
                stderr = j.optString("program_error"),
                exitCode = exit,
                compiled = ran,
                timedOut = signal == "Killed" && j.optString("program_error").isEmpty() && j.optString("program_output").isEmpty()
            )
        } catch (e: Exception) {
            Http.fail("Wandbox", e)
        }
    }
}
