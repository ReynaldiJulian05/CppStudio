package com.reynaldi.cppstudio

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/** The online compilers the user can choose from (Online mode). */
enum class OnlineServer(val id: String, val title: String, val compiler: String, val bestFor: String) {
    WANDBOX(
        "wandbox", "Wandbox", "GCC (latest snapshot) · wandbox.org",
        "Best for: everyday practice. Simple and quick, with clear GCC error messages. This is the default."
    ),
    GODBOLT(
        "godbolt", "Compiler Explorer", "GCC trunk · godbolt.org",
        "Best for: the newest C++23 features, and as a second opinion when you are not sure about an error. A very widely used C++ service."
    ),
    JUDGE0(
        "judge0", "Judge0", "GCC 14.1 · ce.judge0.com",
        "Best for: contest-style problems. It works like an online judge with strict time and memory limits, so it shows whether a solution is fast enough. Can be busier and slower."
    );

    fun create(): CompilerBackend = when (this) {
        WANDBOX -> WandboxBackend()
        GODBOLT -> GodboltBackend()
        JUDGE0 -> Judge0Backend()
    }
}

/** Remembers the chosen server and whether to fall back to the others automatically. */
object ServerPrefs {
    private fun p(ctx: Context) = ctx.getSharedPreferences("cppstudio_servers", Context.MODE_PRIVATE)

    fun selected(ctx: Context): OnlineServer {
        val id = p(ctx).getString("server", null)
        return OnlineServer.values().firstOrNull { it.id == id } ?: OnlineServer.WANDBOX
    }

    fun autoFallback(ctx: Context): Boolean = p(ctx).getBoolean("auto_fallback", true)

    fun save(ctx: Context, server: OnlineServer, auto: Boolean) {
        p(ctx).edit().putString("server", server.id).putBoolean("auto_fallback", auto).apply()
    }

    /** The chosen server first, then the others (if automatic fallback is on). */
    fun order(ctx: Context): List<OnlineServer> {
        val s = selected(ctx)
        return if (autoFallback(ctx)) listOf(s) + OnlineServer.values().filter { it != s } else listOf(s)
    }
}

/** Tries the servers in order and uses the first one that actually answers. */
class FailoverBackend(private val order: List<OnlineServer>) : CompilerBackend {
    override fun run(code: String, stdin: String, std: String, extraFlags: List<String>): RunResult {
        val notes = StringBuilder()
        var last = RunResult(infraError = "No online server is selected.")
        for (s in order) {
            val r = try { s.create().run(code, stdin, std, extraFlags) }
            catch (e: Exception) { RunResult(infraError = "Unexpected error: ${e.message}") }
            if (r.infraError == null) return r.copy(server = s.title, failoverNote = notes.toString().trim())
            notes.append("• ").append(s.title).append(": ").append(r.infraError).append('\n')
            last = r
        }
        val msg = if (order.size <= 1) (last.infraError ?: "Unknown error")
        else "Could not reach any online server.\n" + notes.toString().trim() +
            "\n\nCheck your internet connection, or switch to Offline mode."
        return last.copy(infraError = msg, server = order.firstOrNull()?.title ?: "", failoverNote = notes.toString().trim())
    }
}

internal object Http {
    class Reply(val code: Int, val body: String)

    fun postJson(url: String, json: String, accept: String? = null, readTimeoutMs: Int = 40000): Reply {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = readTimeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("User-Agent", "CppStudio-Android")
            if (accept != null) setRequestProperty("Accept", accept)
        }
        return try {
            conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            Reply(code, text)
        } finally {
            conn.disconnect()
        }
    }

    fun getJson(url: String, readTimeoutMs: Int = 10000): Reply {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10000
            readTimeout = readTimeoutMs
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "CppStudio-Android")
        }
        return try {
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            Reply(code, text)
        } finally {
            conn.disconnect()
        }
    }

    fun fail(name: String, e: Exception): RunResult = when (e) {
        is UnknownHostException -> RunResult(infraError = "Cannot reach $name (no internet, or the site is blocked on this network).")
        is SocketTimeoutException -> RunResult(infraError = "$name timed out.")
        else -> RunResult(infraError = "Could not reach $name: ${e.message}")
    }

    private val ansi = Regex("\u001B\\[[0-9;]*[A-Za-z]")
    fun clean(s: String) = ansi.replace(s, "")
}

/** Compiler Explorer (godbolt.org): GCC trunk, compile + run through its public API. */
class GodboltBackend : CompilerBackend {
    private fun texts(a: JSONArray?): String {
        if (a == null) return ""
        val sb = StringBuilder()
        for (i in 0 until a.length()) {
            sb.append(a.optJSONObject(i)?.optString("text", "") ?: a.optString(i)).append('\n')
        }
        return sb.toString().trimEnd('\n')
    }

    override fun run(code: String, stdin: String, std: String, extraFlags: List<String>): RunResult {
        return try {
            val flags = (listOf("-std=$std", "-Wall", "-Wextra") + extraFlags).joinToString(" ")
            val options = JSONObject()
            options.put("userArguments", flags)
            options.put("executeParameters", JSONObject().put("args", JSONArray()).put("stdin", stdin))
            options.put("compilerOptions", JSONObject().put("executorRequest", true))
            options.put("filters", JSONObject().put("execute", true))
            options.put("tools", JSONArray())
            options.put("libraries", JSONArray())
            val body = JSONObject().put("source", code).put("lang", "c++").put("options", options)
                .put("allowStoreCodeDebug", false).toString()

            val r = Http.postJson("https://godbolt.org/api/compiler/gsnapshot/compile", body, accept = "application/json")
            if (r.code !in 200..299) return RunResult(infraError = "Compiler Explorer returned HTTP ${r.code}. ${r.body}".take(300))

            val j = JSONObject(r.body)
            val build = j.optJSONObject("buildResult")
            val buildLog = if (build != null)
                Http.clean(listOf(texts(build.optJSONArray("stdout")), texts(build.optJSONArray("stderr"))).filter { it.isNotEmpty() }.joinToString("\n"))
            else ""
            if (build != null && build.optInt("code", 0) != 0) return RunResult(compilerLog = buildLog, compiled = false)
            if (!j.optBoolean("didExecute", true)) return RunResult(infraError = "Compiler Explorer compiled the code but did not run it.")

            val raw = j.optInt("code", 0)
            val exit = if (raw < 0) 128 - raw else raw      // negative = killed by signal
            RunResult(
                compilerLog = buildLog,
                stdout = Http.clean(texts(j.optJSONArray("stdout"))),
                stderr = Http.clean(texts(j.optJSONArray("stderr"))),
                exitCode = if (j.optBoolean("timedOut", false)) null else exit,
                compiled = true,
                timedOut = j.optBoolean("timedOut", false)
            )
        } catch (e: Exception) {
            Http.fail("Compiler Explorer", e)
        }
    }
}

/** Judge0 CE (ce.judge0.com): GCC 14.1. Create a submission and poll its token. */
class Judge0Backend : CompilerBackend {
    private fun enc(s: String) = Base64.encodeToString(s.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    private fun dec(j: JSONObject, key: String): String {
        if (!j.has(key) || j.isNull(key)) return ""
        val value = j.optString(key, "")
        if (value.isEmpty()) return ""
        return try { String(Base64.decode(value, Base64.DEFAULT), Charsets.UTF_8) }
        catch (_: IllegalArgumentException) { value }
    }

    override fun run(code: String, stdin: String, std: String, extraFlags: List<String>): RunResult {
        return try {
            val flags = (listOf("-std=$std", "-Wall", "-Wextra") + extraFlags).joinToString(" ")
            val body = JSONObject()
                .put("source_code", enc(code))
                .put("language_id", 105) // C++ (GCC 14.1.0)
                .put("stdin", enc(stdin))
                .put("compiler_options", flags)
                .toString()

            // wait=true is disabled on some official Judge0 CE hosts. Poll the documented token API.
            val created = Http.postJson(
                "https://ce.judge0.com/submissions?base64_encoded=true&wait=false",
                body,
                accept = "application/json",
                readTimeoutMs = 15000
            )
            if (created.code !in 200..299) {
                return RunResult(infraError = "Judge0 returned HTTP ${created.code}. ${created.body}".take(300))
            }
            val token = JSONObject(created.body).optString("token", "")
            if (token.isBlank()) return RunResult(infraError = "Judge0 did not return a submission token.")

            var result: JSONObject? = null
            var lastStatus = 0
            for (attempt in 0 until 55) {
                if (attempt > 0) Thread.sleep(650)
                val poll = Http.getJson(
                    "https://ce.judge0.com/submissions/$token?base64_encoded=true"
                )
                if (poll.code !in 200..299) {
                    return RunResult(infraError = "Judge0 status check returned HTTP ${poll.code}. ${poll.body}".take(300))
                }
                val j = JSONObject(poll.body)
                lastStatus = j.optJSONObject("status")?.optInt("id", 0) ?: 0
                if (lastStatus != 1 && lastStatus != 2 && lastStatus != 0) {
                    result = j
                    break
                }
            }

            val j = result ?: return RunResult(
                infraError = if (lastStatus == 0) "Judge0 returned an unknown submission status."
                else "Judge0 is still processing the program. Try again."
            )
            val status = j.optJSONObject("status")?.optInt("id", 0) ?: 0
            val compileOut = Http.clean(dec(j, "compile_output"))
            when (status) {
                0 -> return RunResult(infraError = "Judge0 sent an answer the app does not understand.")
                6 -> return RunResult(compilerLog = compileOut, compiled = false)
                13, 14 -> return RunResult(
                    compilerLog = compileOut,
                    infraError = "Judge0 execution error. ${dec(j, "message")}".trim().take(300)
                )
            }
            val signal = if (j.isNull("exit_signal") || !j.has("exit_signal")) null else j.optInt("exit_signal")
            val exit = when {
                status == 5 -> null
                signal != null -> 128 + signal
                j.isNull("exit_code") || !j.has("exit_code") -> if (status == 3) 0 else status
                else -> j.optInt("exit_code")
            }
            RunResult(
                compilerLog = compileOut,
                stdout = Http.clean(dec(j, "stdout")),
                stderr = Http.clean(dec(j, "stderr")),
                exitCode = exit,
                compiled = true,
                timedOut = status == 5
            )
        } catch (e: Exception) {
            Http.fail("Judge0", e)
        }
    }
}
