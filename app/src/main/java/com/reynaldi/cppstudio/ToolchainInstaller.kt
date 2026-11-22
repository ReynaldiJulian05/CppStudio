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

    // Android NDK for aarch64 hosts, statically linked (runs on Android 9+ without Termux).
    private const val RELEASE_API = "https://api.github.com/repos/lzhiyong/termux-ndk/releases/tags/android-ndk"

    class Cancel { @Volatile var cancelled = false }
    class Cancelled : Exception("Cancelled")

    data class Asset(val url: String, val name: String, val size: Long)
    data class Progress(val phase: String, val done: Long, val total: Long, val speed: Double, val etaSec: Long)

    fun toolchainDir(ctx: Context) = File(ctx.filesDir, "toolchain")
    private fun dlDir(ctx: Context) = File(ctx.filesDir, "dl").apply { mkdirs() }
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("toolchain", Context.MODE_PRIVATE)

    fun findCompiler(ctx: Context): File? =
        prefs(ctx).getString("clang", null)?.let { File(it) }?.takeIf { it.exists() }

    fun remove(ctx: Context) {
        toolchainDir(ctx).deleteRecursively()
        dlDir(ctx).deleteRecursively()
        prefs(ctx).edit().remove("clang").apply()
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
        if (asset.size > 0 && dlDir(ctx).usableSpace < asset.size * 3)
            throw IOException("Not enough free storage. About ${fmt(asset.size * 3)} is needed, ${fmt(dlDir(ctx).usableSpace)} free.")
        val part = File(dlDir(ctx), asset.name + ".part")
        download(asset, part, cancel, cb)
        extract(ctx, part, asset.name, cancel, cb)
        part.delete()
    }

    fun installFromStream(ctx: Context, name: String, input: InputStream, size: Long, cancel: Cancel, cb: (Progress) -> Unit) {
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
        extract(ctx, f, name, cancel, cb)
        f.delete()
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
        val root = toolchainDir(ctx)
        root.deleteRecursively(); root.mkdirs()
        val rootPath = root.toPath().toAbsolutePath().normalize()
        val meter = Meter(cb, "Extracting", archive.length())
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
        val clang = locate(root) ?: throw IOException("bin/clang++ was not found inside the package.")
        prefs(ctx).edit().putString("clang", clang.absolutePath).apply()
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
                    f.setReadable(true, false); f.setExecutable(true, false)
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
