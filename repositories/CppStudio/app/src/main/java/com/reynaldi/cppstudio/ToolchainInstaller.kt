package com.reynaldi.cppstudio

import android.content.Context
import android.system.Os
import org.json.JSONObject
import org.tukaani.xz.XZInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/** Downloads (with progress/speed/ETA) and unpacks an Android arm64 C++ toolchain. */
object ToolchainInstaller {

    // Increment when extraction rules or runtime validation change. Older trimmed installs
    // are deliberately treated as unverified so the user can reinstall a known-good toolchain.
    private const val INSTALL_FORMAT = 2

    // Android NDK for aarch64 hosts, statically linked (runs on Android 9+ without Termux).
    private const val RELEASE_API = "https://api.github.com/repos/lzhiyong/termux-ndk/releases/tags/android-ndk"

    class Cancel { @Volatile var cancelled = false }
    class Cancelled : Exception("Cancelled")

    data class Asset(val url: String, val name: String, val size: Long)
    data class Progress(val phase: String, val done: Long, val total: Long, val speed: Double, val etaSec: Long)

    fun toolchainDir(ctx: Context) = File(ctx.filesDir, "toolchain")
    private fun dlDir(ctx: Context) = File(ctx.filesDir, "dl").apply { mkdirs() }
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("toolchain", Context.MODE_PRIVATE)

    fun findCompiler(ctx: Context): File? {
        val p = prefs(ctx)
        if (p.getInt("install_format", 0) != INSTALL_FORMAT) return null
        return p.getString("clang", null)?.let { File(it) }?.takeIf { it.isFile }
    }

    fun remove(ctx: Context) {
        toolchainDir(ctx).deleteRecursively()
        File(ctx.filesDir, "toolchain.installing").deleteRecursively()
        File(ctx.filesDir, "toolchain.previous").deleteRecursively()
        dlDir(ctx).deleteRecursively()
        prefs(ctx).edit().remove("clang").remove("size").remove("install_format").apply()
    }

    // ---------- find the right package ----------

    fun resolveAsset(): Asset {
        val c = URL(RELEASE_API).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 20000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "CppStudio")
        if (c.responseCode !in 200..299) throw IOException("GitHub API returned HTTP ${c.responseCode}")
        val json = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        val arr = json.getJSONArray("assets")
        val ok = Regex(".*(\\.zip|\\.tar\\.gz|\\.tgz|\\.tar\\.xz|\\.txz)$", RegexOption.IGNORE_CASE)
        for (i in 0 until arr.length()) {
            val a = arr.getJSONObject(i)
            val name = a.getString("name")
            val l = name.lowercase()
            if (ok.matches(name) && "ndk" in l && "sdk" !in l && "jdk" !in l)
                return Asset(a.getString("browser_download_url"), name, a.optLong("size", -1))
        }
        val names = (0 until arr.length()).joinToString { arr.getJSONObject(it).getString("name") }
        throw IOException("No suitable NDK archive in the release. Found: $names. Paste a direct URL instead.")
    }

    // ---------- public entry points ----------

    fun installFromUrl(ctx: Context, asset: Asset, cancel: Cancel, cb: (Progress) -> Unit) {
        val existingInstall = if (toolchainDir(ctx).isDirectory) dirSize(toolchainDir(ctx)) else 0L
        // Keep the currently working installation until the replacement passes its smoke test.
        val need = asset.size + 700L * 1024 * 1024 + existingInstall
        if (asset.size > 0 && dlDir(ctx).usableSpace < need)
            throw IOException("Not enough free storage to keep the current compiler safe during the update. About ${fmt(need)} is needed, ${fmt(dlDir(ctx).usableSpace)} free. Remove the old compiler first if you need to free space.")
        val part = File(dlDir(ctx), asset.name + ".part")
        download(asset, part, cancel, cb)
        extract(ctx, part, asset.name, cancel, cb)
        part.delete()
    }

    fun installFromStream(ctx: Context, name: String, input: InputStream, size: Long, cancel: Cancel, cb: (Progress) -> Unit) {
        val existingInstall = if (toolchainDir(ctx).isDirectory) dirSize(toolchainDir(ctx)) else 0L
        val need = size + 700L * 1024 * 1024 + existingInstall
        if (size > 0 && dlDir(ctx).usableSpace < need)
            throw IOException("Not enough free storage to import and verify the compiler. About ${fmt(need)} is needed, ${fmt(dlDir(ctx).usableSpace)} free. Remove the old compiler first if necessary.")
        val f = File(dlDir(ctx), "imported_$name")
        val meter = Meter(cb, "Copying", size)
        var done = 0L
        FileOutputStream(f).use { out ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                if (cancel.cancelled) throw Cancelled()
                val n = input.read(buf); if (n < 0) break
                out.write(buf, 0, n); done += n; meter.update(done)
            }
        }
        try {
            extract(ctx, f, name, cancel, cb)
        } finally {
            // Imported archives are copied into app storage only for extraction; do not
            // leave a second full-size archive behind after success or validation failure.
            f.delete()
        }
    }

    // ---------- download (resumable) ----------

    private fun download(asset: Asset, part: File, cancel: Cancel, cb: (Progress) -> Unit) {
        var existing = if (part.exists()) part.length() else 0L
        if (asset.size > 0 && existing >= asset.size) return
        val c = URL(asset.url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000; c.readTimeout = 30000; c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "CppStudio")
        if (existing > 0) c.setRequestProperty("Range", "bytes=$existing-")
        val code = c.responseCode
        if (code == 416 && existing > 0) return
        if (code !in 200..299) throw IOException("Download failed: HTTP $code")
        val append = code == 206
        if (!append) existing = 0
        val len = c.contentLengthLong
        val total = if (len > 0) existing + len else asset.size
        val meter = Meter(cb, "Downloading", total)
        var done = existing
        FileOutputStream(part, append).use { out ->
            c.inputStream.use { inp ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    if (cancel.cancelled) throw Cancelled()
                    val n = inp.read(buf); if (n < 0) break
                    out.write(buf, 0, n); done += n; meter.update(done)
                }
            }
        }
        meter.update(done, true)
        if (total > 0 && done < total) throw IOException("Connection lost at ${fmt(done)} of ${fmt(total)}. Tap Download again to resume.")
    }

    // ---------- extract ----------

    private class Counting(i: InputStream) : FilterInputStream(i) {
        @Volatile var count = 0L
        override fun read(): Int { val r = super.read(); if (r >= 0) count++; return r }
        override fun read(b: ByteArray, off: Int, len: Int): Int { val r = super.read(b, off, len); if (r > 0) count += r; return r }
    }

    private fun extract(ctx: Context, archive: File, name: String, cancel: Cancel, cb: (Progress) -> Unit) {
        // Extract into a staging directory. A failed update must not destroy a previously working compiler.
        val root = File(ctx.filesDir, "toolchain.installing")
        root.deleteRecursively()
        root.mkdirs()
        val rootPath = root.toPath().toAbsolutePath().normalize()
        val meter = Meter(cb, "Extracting", archive.length())
        try {
            val counting = Counting(FileInputStream(archive).buffered(1 shl 16))
            val l = name.lowercase()
            counting.use {
                when {
                    l.endsWith(".zip") -> extractZip(counting, root, rootPath, meter, cancel)
                    l.endsWith(".tar.xz") || l.endsWith(".txz") -> extractTar(XZInputStream(counting), counting, root, rootPath, meter, cancel)
                    l.endsWith(".tar.gz") || l.endsWith(".tgz") -> extractTar(GZIPInputStream(counting), counting, root, rootPath, meter, cancel)
                    else -> throw IOException("Unsupported format: $name. Use .zip, .tar.gz or .tar.xz (not .7z).")
                }
            }
            meter.update(archive.length(), true)
            if (l.endsWith(".zip")) fixZipSymlinks(root)
            val stagedCompiler = locate(root) ?: throw IOException("bin/clang++ was not found inside the package.")
            validateCompiler(stagedCompiler)

            val destination = toolchainDir(ctx)
            val backup = File(ctx.filesDir, "toolchain.previous")
            backup.deleteRecursively()
            if (destination.exists() && !destination.renameTo(backup)) {
                throw IOException("Could not safely replace the existing toolchain. Close other CppStudio tasks and try again.")
            }
            if (!root.renameTo(destination)) {
                if (backup.exists()) backup.renameTo(destination)
                throw IOException("Could not activate the verified compiler folder. Please check available storage and try again.")
            }
            val relativeCompiler = stagedCompiler.relativeTo(root).path
            val activeCompiler = File(destination, relativeCompiler)
            if (!activeCompiler.isFile) {
                destination.deleteRecursively()
                if (backup.exists()) backup.renameTo(destination)
                throw IOException("The compiler file was missing after installation was activated.")
            }
            prefs(ctx).edit()
                .putString("clang", activeCompiler.absolutePath)
                .putLong("size", dirSize(destination))
                .putInt("install_format", INSTALL_FORMAT)
                .apply()
            backup.deleteRecursively()
        } catch (e: Exception) {
            root.deleteRecursively()
            throw e
        }
    }

    /** Run a real C++23 build and the produced executable before marking the toolchain installed. */
    private fun validateCompiler(compiler: File) {
        if (!compiler.setExecutable(true, false) && !compiler.canExecute()) {
            throw IOException("The extracted clang++ file is not executable: ${compiler.absolutePath}")
        }
        val bin = compiler.parentFile ?: throw IOException("Invalid clang++ folder in the archive.")
        val prebuilt = bin.parentFile ?: throw IOException("Invalid LLVM toolchain folder in the archive.")
        val sysroot = File(prebuilt, "sysroot")
        if (!sysroot.isDirectory) throw IOException("The archive is missing toolchains/llvm/prebuilt/.../sysroot.")

        val checkDir = File(prebuilt, ".cppstudio-toolchain-check")
        checkDir.deleteRecursively()
        checkDir.mkdirs()
        try {
            val source = File(checkDir, "check.cpp")
            val executable = File(checkDir, "check")
            source.writeText("#include <iostream>\n#include <vector>\nint main(){ std::vector<int> v{2,3,5}; std::cout << v.size(); return 0; }\n")
            val env = System.getenv().toMutableMap()
            env["HOME"] = checkDir.absolutePath
            env["TMPDIR"] = checkDir.absolutePath
            env["PATH"] = bin.absolutePath + File.pathSeparator + env["PATH"].orEmpty()
            val libDirs = linkedSetOf<String>()
            listOf(File(prebuilt, "lib"), File(prebuilt, "lib64")).filter { it.isDirectory }
                .forEach { libDirs.add(it.absolutePath) }
            File(prebuilt, "lib/clang").listFiles()?.filter { it.isDirectory }?.forEach { version ->
                val linux = File(version, "lib/linux")
                if (linux.isDirectory) libDirs.add(linux.absolutePath)
            }
            val oldLd = env["LD_LIBRARY_PATH"].orEmpty().split(File.pathSeparator).filter { it.isNotBlank() }
            if (libDirs.isNotEmpty() || oldLd.isNotEmpty()) env["LD_LIBRARY_PATH"] = (libDirs + oldLd).joinToString(File.pathSeparator)
            env["LC_ALL"] = "C"

            val command = listOf(
                compiler.absolutePath, "--target=aarch64-linux-android28", "--sysroot=${sysroot.absolutePath}",
                "-std=c++23", "-fuse-ld=lld", "-static-libstdc++", source.absolutePath, "-o", executable.absolutePath
            )
            val compileResult = runValidation(command, checkDir, env, 30_000)
            if (compileResult.first != 0 || !executable.isFile) {
                val detail = compileResult.second.ifBlank { "clang++ exited with code ${compileResult.first}." }
                throw IOException("The downloaded toolchain failed its C++23 compile test: ${detail.take(1600)}")
            }
            if (!executable.setExecutable(true, true) && !executable.canExecute()) {
                throw IOException("The downloaded compiler produced a test program that cannot be executed.")
            }
            val runResult = runValidation(listOf(executable.absolutePath), checkDir, env, 5_000)
            if (runResult.first != 0 || runResult.second.trim() != "3") {
                throw IOException("The compiler test program could not run correctly: ${runResult.second.take(1200)}")
            }
        } finally {
            checkDir.deleteRecursively()
        }
    }

    private fun runValidation(command: List<String>, directory: File, environment: Map<String, String>, timeoutMs: Long): Pair<Int, String> {
        val process = try {
            ProcessBuilder(command).directory(directory).apply {
                this.environment().putAll(environment)
                redirectErrorStream(true)
            }.start()
        } catch (e: Exception) {
            throw IOException("Could not start ${File(command.first()).name}: ${e.message}", e)
        }
        val output = StringBuilder()
        val reader = Thread {
            try {
                process.inputStream.bufferedReader().use { r ->
                    val buffer = CharArray(2048)
                    while (true) {
                        val n = r.read(buffer); if (n < 0) break
                        synchronized(output) {
                            if (output.length < 12_000) output.append(buffer, 0, minOf(n, 12_000 - output.length))
                        }
                    }
                }
            } catch (_: Exception) { }
        }.apply { isDaemon = true; start() }
        val done = try { process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) }
        catch (_: InterruptedException) { process.destroyForcibly(); Thread.currentThread().interrupt(); false }
        if (!done) {
            process.destroyForcibly()
            throw IOException("The compiler validation test timed out after ${timeoutMs / 1000} seconds.")
        }
        try { reader.join(1000) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        return process.exitValue() to synchronized(output) { output.toString() }
    }

    private fun safe(rootPath: java.nio.file.Path, root: File, name: String): File {
        val f = File(root, name)
        if (!f.toPath().toAbsolutePath().normalize().startsWith(rootPath)) throw SecurityException("Bad path in archive: $name")
        return f
    }

    private fun extractZip(inp: Counting, root: File, rootPath: java.nio.file.Path, meter: Meter, cancel: Cancel) {
        ZipInputStream(inp).use { zin ->
            var e = zin.nextEntry
            while (e != null) {
                if (cancel.cancelled) throw Cancelled()
                if (!keep(e.name)) { meter.update(inp.count); e = zin.nextEntry; continue }
                val f = safe(rootPath, root, e.name)
                if (e.isDirectory) f.mkdirs() else {
                    f.parentFile?.mkdirs()
                    FileOutputStream(f).use { out ->
                        val buf = ByteArray(1 shl 16)
                        while (true) {
                            val n = zin.read(buf); if (n < 0) break
                            out.write(buf, 0, n)
                            meter.update(inp.count)
                        }
                    }
                    f.setReadable(true, false)
                    val entryPath = e.name.replace('\\', '/').let { "/$it/" }
                    if ("/bin/" in entryPath || f.name.endsWith(".sh")) f.setExecutable(true, false)
                }
                meter.update(inp.count)
                e = zin.nextEntry
            }
        }
    }

    private fun readFully(i: InputStream, b: ByteArray): Boolean {
        var o = 0
        while (o < b.size) { val n = i.read(b, o, b.size - o); if (n < 0) return false; o += n }
        return true
    }

    private fun skipFully(i: InputStream, n: Long) {
        var left = n
        val tmp = ByteArray(1 shl 14)
        while (left > 0) {
            val s = i.skip(left)
            if (s > 0) left -= s else { val r = i.read(tmp, 0, minOf(tmp.size.toLong(), left).toInt()); if (r < 0) return; left -= r }
        }
    }

    private fun cstr(b: ByteArray, off: Int, len: Int): String {
        var e = off; while (e < off + len && b[e] != 0.toByte()) e++
        return String(b, off, e - off, Charsets.UTF_8)
    }

    private fun octal(b: ByteArray, off: Int, len: Int): Long {
        if ((b[off].toInt() and 0x80) != 0) { // base-256 encoding for very large sizes
            var v = (b[off].toInt() and 0x7F).toLong()
            for (k in 1 until len) v = (v shl 8) or (b[off + k].toLong() and 0xFF)
            return v
        }
        val s = cstr(b, off, len).trim()
        return if (s.isEmpty()) 0 else s.toLong(8)
    }

    private fun extractTar(inp: InputStream, counting: Counting, root: File, rootPath: java.nio.file.Path, meter: Meter, cancel: Cancel) {
        val hdr = ByteArray(512)
        var longName: String? = null
        while (true) {
            if (cancel.cancelled) throw Cancelled()
            if (!readFully(inp, hdr)) break
            if (hdr.all { it == 0.toByte() }) break
            var name = cstr(hdr, 0, 100)
            val mode = octal(hdr, 100, 8).toInt()
            val size = octal(hdr, 124, 12)
            val type = hdr[156].toInt().toChar()
            val link = cstr(hdr, 157, 100)
            if (cstr(hdr, 257, 6).startsWith("ustar")) {
                val prefix = cstr(hdr, 345, 155)
                if (prefix.isNotEmpty()) name = "$prefix/$name"
            }
            val pad = (512 - size % 512) % 512
            if (type == 'L' || type == 'x') {
                val b = ByteArray(size.toInt()); readFully(inp, b); skipFully(inp, pad)
                if (type == 'L') longName = cstr(b, 0, b.size)
                else String(b, Charsets.UTF_8).split('\n').forEach { ln -> val i = ln.indexOf(" path="); if (i >= 0) longName = ln.substring(i + 6) }
                continue
            }
            if (type == 'g') { skipFully(inp, size + pad); continue }
            if (longName != null) { name = longName!!; longName = null }
            if (!keep(name)) { skipFully(inp, size + pad); meter.update(counting.count); continue }
            val f = safe(rootPath, root, name)
            when (type) {
                '5' -> f.mkdirs()
                '2' -> { f.parentFile?.mkdirs(); f.delete(); try { Os.symlink(link, f.path) } catch (_: Exception) {} }
                '1' -> { f.parentFile?.mkdirs(); val t = safe(rootPath, root, link); if (t.exists()) t.copyTo(f, true) }
                '0', '\u0000' -> {
                    f.parentFile?.mkdirs()
                    FileOutputStream(f).use { out ->
                        var left = size
                        val buf = ByteArray(1 shl 16)
                        while (left > 0) {
                            val n = inp.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (n < 0) throw IOException("Archive is truncated")
                            out.write(buf, 0, n); left -= n
                            meter.update(counting.count)
                        }
                    }
                    f.setReadable(true, false)
                    f.setExecutable((mode and 0x49) != 0, false)
                    skipFully(inp, pad)
                    meter.update(counting.count)
                    continue
                }
                else -> { skipFully(inp, size + pad); continue }
            }
            skipFully(inp, size + pad)
        }
    }

    /** Zip files made on Linux may store symlinks as tiny text files. Turn them back into symlinks. */
    private fun fixZipSymlinks(root: File) {
        val token = Regex("[A-Za-z0-9_.+\\-/]+")
        root.walkTopDown().filter { it.isFile && it.length() in 1..200 }.forEach { f ->
            val txt = try { f.readText().trim() } catch (_: Exception) { return@forEach }
            if (txt.isNotEmpty() && token.matches(txt) && File(f.parentFile, txt).exists() && File(f.parentFile, txt) != f) {
                try { f.delete(); Os.symlink(txt, f.path) } catch (_: Exception) {}
            }
        }
    }

    private fun locate(root: File): File? {
        val skip = setOf("sysroot", "sources", "include", "share", "lib", "lib64", "python3", "build", "shader-tools")
        val bins = root.walkTopDown().maxDepth(10).onEnter { it.name !in skip }
            .filter { it.name == "clang++" || it.name == "g++" }.filter { it.parentFile?.name == "bin" }.toList()
        return bins.firstOrNull { it.name == "clang++" } ?: bins.firstOrNull()
    }

    fun installedSize(ctx: Context): Long = prefs(ctx).getLong("size", 0L)

    private fun dirSize(root: File): Long =
        root.walkTopDown().filter { it.isFile && !java.nio.file.Files.isSymbolicLink(it.toPath()) }.sumOf { it.length() }

    // ---------- keep only what a C++23 arm64 build needs (≈ 1.8 GB -> a few hundred MB) ----------

    private val binKeep = Regex("^(clang(\\+\\+)?(-\\d+)?|lld|ld|ld\\.lld|ld64\\.lld)$")
    private val otherArchInclude = setOf("arm-linux-androideabi", "i686-linux-android", "x86_64-linux-android", "riscv64-linux-android")
    private val denyTop = setOf(
        "build", "sources", "python-packages", "shader-tools", "simpleperf", "prebuilt",
        "wrap.sh", "docs", "tests", "ndk-build", "ndk-gdb", "ndk-lldb",
        "ndk-stack", "ndk-which", "ndk-depends", "ndk-translate", "ndk-abi"
    )
    private const val TARGET_API = "28"

    private fun keep(rawName: String): Boolean {
        val p = rawName.replace('\\', '/').trimStart('/')
        val lowerPath = p.lowercase()
        // Debug databases and compiler test/example payloads are not needed to build user programs.
        if (listOf(".pdb", ".dwo", ".dwp", ".profraw", ".profdata", ".debug").any { lowerPath.endsWith(it) }) return false
        if (lowerPath.contains("/tests/") || lowerPath.contains("/examples/")) return false
        val marker = "toolchains/llvm/prebuilt/"
        val idx = p.indexOf(marker)
        if (idx < 0) {
            if (p.contains("toolchains/")) return false          // other (old GCC / renderscript) toolchains
            val parts = p.split('/')
            val top = if (parts.size > 1 && parts[0].startsWith("android-ndk")) parts[1] else parts[0]
            return top !in denyTop                               // unknown layout: keep
        }
        val after = p.substring(idx + marker.length)             // <host>/bin/clang ...
        val slash = after.indexOf('/')
        if (slash < 0) return true
        val rest = after.substring(slash + 1)
        val seg = rest.split('/')
        return when (seg[0]) {
            "bin" -> { val name = seg.getOrElse(1) { "" }; name.isEmpty() || binKeep.matches(name) }
            "lib" -> {
                // Keep host-side LLVM libraries as well as Clang resources. Some NDK builds
                // dynamically link clang++/lld against libLLVM.so; pruning these files makes
                // the installed compiler appear present but fail with a linker-loader error.
                if (seg.size < 2 || seg[1].isEmpty()) true
                else if (seg[1] != "clang") true
                else {
                    val low = rest.lowercase()
                    !(low.contains("arm-android") || low.contains("armhf") || low.contains("armv7") ||
                            low.contains("i686") || low.contains("i386") || low.contains("x86_64") ||
                            low.contains("riscv64"))
                }
            }
            "sysroot" -> when {
                seg.size >= 4 && seg[1] == "usr" && seg[2] == "lib" -> {
                    val t = seg[3]
                    if (t.isEmpty()) true
                    else if (t.endsWith("-android") || t.endsWith("androideabi")) {
                        if (t != "aarch64-linux-android") false
                        else {
                            val api = seg.getOrNull(4)
                            !(api != null && api.isNotEmpty() && api.all { it.isDigit() } && api != TARGET_API)
                        }
                    } else true
                }
                seg.size >= 4 && seg[1] == "usr" && seg[2] == "include" && seg[3] in otherArchInclude -> false
                else -> true
            }
            "python3", "share", "musl", "libexec", "prebuilt" -> false
            else -> true
        }
    }

    // ---------- progress meter ----------

    private class Meter(val cb: (Progress) -> Unit, val phase: String, val total: Long) {
        private var last = 0L; private var lastBytes = 0L; private var speed = 0.0
        fun update(done: Long, force: Boolean = false) {
            val now = System.nanoTime()
            if (!force && last != 0L && now - last < 400_000_000L) return
            if (last != 0L) {
                val dt = (now - last) / 1e9
                if (dt > 0) { val inst = (done - lastBytes) / dt; speed = if (speed == 0.0) inst else speed * 0.7 + inst * 0.3 }
            }
            last = now; lastBytes = done
            val eta = if (speed > 1 && total > 0) ((total - done) / speed).toLong() else -1L
            cb(Progress(phase, done, total, speed, eta))
        }
    }

    fun fmt(bytes: Long): String = if (bytes >= 1_073_741_824L) String.format("%.2f GB", bytes / 1073741824.0) else String.format("%.1f MB", bytes / 1048576.0)
}
