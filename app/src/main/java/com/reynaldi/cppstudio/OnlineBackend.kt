package com.reynaldi.cppstudio

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Compiles on a remote GCC via the Wandbox public API. Needs internet, keeps the APK tiny. */
class OnlineBackend : CompilerBackend {
    override fun run(code: String, stdin: String, std: String): RunResult {
        return try {
            val body = JSONObject().apply {
                put("code", code)
                put("compiler", "gcc-head")
                put("compiler-option-raw", "-std=$std\n-Wall\n-Wextra")
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
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code2 = conn.responseCode
            val text = (if (code2 in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code2 !in 200..299) return RunResult(infraError = "Server returned HTTP $code2. $text".take(400))

            val j = JSONObject(text)
            val compilerErr = j.optString("compiler_error")
            val compilerMsg = j.optString("compiler_message")
            val log = if (compilerMsg.isNotEmpty()) compilerMsg else compilerErr
            val ran = j.has("program_message") || j.has("program_output") || j.has("program_error")
            val status = j.optString("status", "").toIntOrNull()
            val signal = j.optString("signal", "")
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
        } catch (e: java.net.UnknownHostException) {
            RunResult(infraError = "No internet connection. Connect to the internet, or switch to Offline mode (needs a toolchain).")
        } catch (e: java.net.SocketTimeoutException) {
            RunResult(infraError = "The compile server timed out. Try again.")
        } catch (e: Exception) {
            RunResult(infraError = "Could not reach the compile server: ${e.message}")
        }
    }
}
