package com.reynaldi.cppstudio

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

/** Offline mode: runs the installed Android arm64 clang++ (NDK) and then the program itself. */
class LocalBackend(private val ctx: Context) : CompilerBackend {

    companion object {
        fun findCompiler(ctx: Context): File? = ToolchainInstaller.findCompiler(ctx)
    }

    private data class Captured(
        val output: String,
        val error: String,
        val exitCode: Int?,
        val timedOut: Boolean
    )

    /** Drain both process pipes concurrently so a full pipe cannot freeze execution. */
    private fun runCommand(
        command: List<String>,
        directory: File,
        timeoutMs: Long,
        environment: Map<String, String> = emptyMap(),
        stdin: String? = null,
        maxOutput: Int = 300_000,
        mergeError: Boolean = true
    ): Captured {
        val builder = ProcessBuilder(command).directory(directory).apply {
            this.environment().putAll(environment)
            redirectErrorStream(mergeError)
        }
        val process = builder.start()
        val output = StringBuilder()
        val error = StringBuilder()

        fun drain(stream: java.io.InputStream, target: StringBuilder, limit: Int, threadName: String) =
            Thread {
                try {
                    stream.bufferedReader(Charsets.UTF_8).use { reader ->
                        val buffer = CharArray(4096)
                        while (true) {
                            val count = reader.read(buffer)
                            if (count < 0) break
                            synchronized(target) {
                                if (target.length < limit) {
                                    val accepted = minOf(count, limit - target.length)
                                    target.append(buffer, 0, accepted)
                                }
                            }
                        }
                    }
                } catch (_: Exception) { }
            }.apply { isDaemon = true; name = threadName; start() }

        val outputThread = drain(process.inputStream, output, maxOutput, "CppStudio-process-output")
        val errorThread = if (mergeError) null else drain(process.errorStream, error, 50_000, "CppStudio-process-error")
        val inputThread = if (stdin != null) Thread {
            try {
                process.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(stdin) }
            } catch (_: Exception) { }
        }.apply { isDaemon = true; name = "CppStudio-process-input"; start() } else null

        val completed = try {
            process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
            false
        }
        if (!completed) {
            process.destroyForcibly()
            try { process.waitFor(1500, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
        try { outputThread.join(1500) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        if (errorThread != null) try { errorThread.join(1500) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        if (inputThread != null) try { inputThread.join(500) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }

        fun textOf(target: StringBuilder, limit: Int): String = synchronized(target) {
            target.toString() + if (target.length >= limit) "\n… output truncated …" else ""
        }
        return Captured(textOf(output, maxOutput), textOf(error, 50_000),
            if (completed) process.exitValue() else null, !completed)
    }

    private fun compilerEnvironment(compiler: File, work: File): Map<String, String> {
        val bin = compiler.parentFile ?: return emptyMap()
        val prebuilt = bin.parentFile ?: return emptyMap()
        val libraryDirs = linkedSetOf<String>()
        listOf(File(prebuilt, "lib"), File(prebuilt, "lib64")).filter { it.isDirectory }
            .forEach { libraryDirs.add(it.absolutePath) }
        val clangRoot = File(prebuilt, "lib/clang")
        clangRoot.listFiles()?.filter { it.isDirectory }?.forEach { version ->
            val linux = File(version, "lib/linux")
            if (linux.isDirectory) libraryDirs.add(linux.absolutePath)
        }
        val oldLd = System.getenv("LD_LIBRARY_PATH").orEmpty()
        val ld = (libraryDirs + oldLd.split(File.pathSeparator).filter { it.isNotBlank() }).joinToString(File.pathSeparator)
        val oldPath = System.getenv("PATH").orEmpty()
        return mapOf(
            "HOME" to work.absolutePath,
            "TMPDIR" to work.absolutePath,
            "PATH" to listOf(bin.absolutePath, oldPath).filter { it.isNotBlank() }.joinToString(File.pathSeparator),
            "LD_LIBRARY_PATH" to ld,
            "LC_ALL" to "C"
        )
    }

    override fun run(code: String, stdin: String, std: String, extraFlags: List<String>): RunResult {
        val compiler = findCompiler(ctx)
            ?: return RunResult(infraError = "Offline compiler is not installed or its saved compiler file is missing. Open the ⬇ tab and reinstall it, or use Online mode.")
        if (!compiler.isFile) return RunResult(infraError = "Offline compiler file is missing: ${compiler.absolutePath}. Reinstall it from the ⬇ tab.")

        val bin = compiler.parentFile
            ?: return RunResult(infraError = "Invalid offline compiler path: ${compiler.absolutePath}")
        val prebuilt = bin.parentFile
            ?: return RunResult(infraError = "Invalid offline compiler folder: ${bin.absolutePath}")
        val sysroot = File(prebuilt, "sysroot")
        if (!sysroot.isDirectory) {
            return RunResult(infraError = "Offline compiler installation is incomplete: its sysroot folder is missing. Open the ⬇ tab, remove the offline compiler, then download it again.")
        }

        val work = try {
            File.createTempFile("cppstudio-run-", "", ctx.cacheDir).apply {
                delete()
                mkdirs()
            }
        } catch (e: Exception) {
            return RunResult(infraError = "Cannot create a temporary build folder: ${e.message}")
        }
        if (!work.isDirectory) return RunResult(infraError = "Cannot create a temporary build folder in ${ctx.cacheDir.absolutePath}.")

        return try {
            val source = File(work, "main.cpp")
            val exe = File(work, "main.out")
            source.writeText(code, Charsets.UTF_8)
            val env = compilerEnvironment(compiler, work)
            val command = mutableListOf(
                compiler.absolutePath,
                "--target=aarch64-linux-android28",
                "--sysroot=${sysroot.absolutePath}",
                "-std=$std",
                "-Wall", "-Wextra", "-fuse-ld=lld", "-static-libstdc++",
                "-fno-color-diagnostics",
            )
            command.addAll(extraFlags)
            command.addAll(listOf(source.absolutePath, "-o", exe.absolutePath))

            val compile = runCommand(command, work, 30_000, env, maxOutput = 120_000)
            if (compile.timedOut) {
                return RunResult(compilerLog = compile.output, timedOut = true,
                    infraError = "Offline compilation exceeded 30 seconds and was stopped. Check whether the compiler installation is complete.")
            }
            if (compile.exitCode != 0 || !exe.exists()) {
                val details = compile.output.ifBlank { "The compiler exited with code ${compile.exitCode ?: "unknown"} and produced no diagnostic output." }
                return RunResult(compilerLog = details, compiled = false)
            }

            if (!exe.setExecutable(true, true) && !exe.canExecute()) {
                return RunResult(compilerLog = compile.output,
                    infraError = "The compiled program could not be marked executable in the app cache folder.")
            }
            val program = runCommand(
                listOf(exe.absolutePath), work, 10_000, env,
                stdin = stdin, maxOutput = 300_000, mergeError = false
            )
            RunResult(
                compilerLog = compile.output,
                stdout = program.output,
                stderr = program.error,
                exitCode = program.exitCode,
                compiled = true,
                timedOut = program.timedOut
            )
        } catch (e: Exception) {
            val detail = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            RunResult(infraError = "Could not run the offline compiler: $detail\nCompiler: ${compiler.absolutePath}\nReinstall it from the ⬇ tab if the problem continues.")
        } finally {
            work.deleteRecursively()
        }
    }
}
