package com.reynaldi.cppstudio

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

/** Offline mode: runs the installed Android arm64 clang++ (NDK) and then the program itself. */
class LocalBackend(private val ctx: Context) : CompilerBackend {

    companion object {
        fun findCompiler(ctx: Context): File? = ToolchainInstaller.findCompiler(ctx)
    }

    override fun run(code: String, stdin: String, std: String, extraFlags: List<String>): RunResult {
        val compiler = findCompiler(ctx)
            ?: return RunResult(infraError = "Offline compiler is not installed. Open the ⬇ tab to download it, or use Online mode.")
        val work = File(ctx.cacheDir, "work").apply { deleteRecursively(); mkdirs() }
        File(work, "main.cpp").writeText(code)
        val exe = File(work, "main.out")
        val bin = compiler.parentFile!!
        val sysroot = File(bin.parentFile, "sysroot")

        fun proc(cmd: List<String>) = ProcessBuilder(cmd).directory(work).apply {
            environment()["TMPDIR"] = work.absolutePath
            environment()["HOME"] = work.absolutePath
        }

        return try {
            val cmd = mutableListOf(compiler.absolutePath, "--target=aarch64-linux-android28", "-std=$std",
                "-Wall", "-Wextra", "-fuse-ld=lld", "-static-libstdc++", "main.cpp", "-o", "main.out")
            cmd.addAll(1, extraFlags)
            if (sysroot.isDirectory) cmd.add(1, "--sysroot=${sysroot.absolutePath}")
            val c = proc(cmd).redirectErrorStream(true).start()
            val cOut = c.inputStream.bufferedReader().readText()
            c.waitFor(30, TimeUnit.SECONDS)
            if (c.isAlive) { c.destroyForcibly(); return RunResult(compilerLog = cOut, timedOut = true) }
            if (c.exitValue() != 0 || !exe.exists()) return RunResult(compilerLog = cOut, compiled = false)

            exe.setExecutable(true)
            val p = proc(listOf(exe.absolutePath)).start()
            Thread { try { p.outputStream.use { it.write(stdin.toByteArray()) } } catch (_: Exception) {} }.start()
            val outBuf = StringBuilder(); val errBuf = StringBuilder()
            val t1 = Thread { p.inputStream.bufferedReader().use { r -> val b = CharArray(4096); while (true) { val n = r.read(b); if (n < 0) break; if (outBuf.length < 300_000) outBuf.append(b, 0, n) } } }
            val t2 = Thread { p.errorStream.bufferedReader().use { r -> val b = CharArray(4096); while (true) { val n = r.read(b); if (n < 0) break; if (errBuf.length < 50_000) errBuf.append(b, 0, n) } } }
            t1.start(); t2.start()
            val finished = p.waitFor(10, TimeUnit.SECONDS)
            if (!finished) p.destroyForcibly()
            t1.join(1500); t2.join(1500)
            RunResult(cOut, outBuf.toString(), errBuf.toString(), if (finished) p.exitValue() else null, compiled = true, timedOut = !finished)
        } catch (e: Exception) {
            RunResult(infraError = "Could not run the offline compiler: ${e.message}\nRe-install it from the ⬇ tab.")
        }
    }
}
