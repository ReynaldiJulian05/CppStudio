package com.reynaldi.cppstudio

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

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

            val conn = (URL("https://wandbox.org/api/compile.json").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 40000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            try {
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code2 = conn.responseCode
                val text = (if (code2 in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                if (code2 !in 200..299) return RunResult(infraError = "Server returned HTTP $code2. $text".take(400))

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
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Http.fail("Wandbox", e)
        }
    }
}
