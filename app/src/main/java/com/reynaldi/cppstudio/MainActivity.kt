package com.reynaldi.cppstudio

import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.InputFilter
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout

class MainActivity : AppCompatActivity() {

    // editor
    private lateinit var editor: EditText
    private lateinit var gutter: TextView
    private lateinit var editorScroll: ScrollView
    private lateinit var fileName: TextView

    // bottom panel
    private lateinit var tabs: TabLayout
    private lateinit var console: EditText
    private lateinit var consoleScroll: ScrollView
    private lateinit var btnEof: MaterialButton
    private lateinit var problems: LinearLayout
    private lateinit var problemsScroll: ScrollView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var downloadScroll: ScrollView

    // toolbar
    private lateinit var progress: ProgressBar
    private lateinit var btnRun: MaterialButton
    private lateinit var btnStd: MaterialButton
    private lateinit var btnDownload: MaterialButton
    private lateinit var modeGroup: MaterialButtonToggleGroup

    // download tab
    private lateinit var dlInstalled: TextView
    private lateinit var dlStatus: TextView
    private lateinit var dlBar: ProgressBar
    private lateinit var dlDetail: TextView
    private lateinit var dlUrl: EditText
    private lateinit var btnInstall: MaterialButton
    private lateinit var btnCancel: MaterialButton

    private var std = "c++17"
    private val stds = arrayOf("c++11", "c++14", "c++17", "c++20", "c++23")
    private var errorLines = setOf<Int>()
    private var warnLines = setOf<Int>()
    private var highlighted = false
    private var lastLineCount = -1
    private var busy = false

    // interactive console session
    private var waiting = false
    private var lockedLen = 0
    private var programmatic = false
    private var session = 0
    private var runSource = ""
    private var runOffline = false
    private var runStd = "c++17"
    private var runLint: List<Diagnostic> = emptyList()
    private val inputs = mutableListOf<String>()
    private val echoAt = mutableListOf<Int>()
    private var lastStdout = ""
    private var eofMode = false

    // installer
    private var installing = false
    private var cancelFlag = ToolchainInstaller.Cancel()

    private val openFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) loadUri(uri) }
    private val saveFile = registerForActivityResult(ActivityResultContracts.CreateDocument("text/x-c++src")) { uri -> if (uri != null) saveUri(uri) }
    private val pickToolchain = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) installFromUri(uri) }

    private val template = """#include <iostream>
using namespace std;

int main() {
    int n;
    cout << "Enter a value : ";
    cin >> n;
    cout << "The value you entered is : " << n << endl;
    return 0;
}
"""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        editor = findViewById(R.id.editor); gutter = findViewById(R.id.gutter)
        editorScroll = findViewById(R.id.editorScroll); fileName = findViewById(R.id.fileName)
        tabs = findViewById(R.id.tabs); console = findViewById(R.id.console)
        consoleScroll = findViewById(R.id.consoleScroll); btnEof = findViewById(R.id.btnEof)
        problems = findViewById(R.id.problems); problemsScroll = findViewById(R.id.problemsScroll)
        logView = findViewById(R.id.logView); logScroll = findViewById(R.id.logScroll)
        downloadScroll = findViewById(R.id.downloadScroll)
        progress = findViewById(R.id.progress); btnRun = findViewById(R.id.btnRun)
        btnStd = findViewById(R.id.btnStd); btnDownload = findViewById(R.id.btnDownload)
        modeGroup = findViewById(R.id.modeGroup)
        dlInstalled = findViewById(R.id.dlInstalled); dlStatus = findViewById(R.id.dlStatus)
        dlBar = findViewById(R.id.dlBar); dlDetail = findViewById(R.id.dlDetail)
        dlUrl = findViewById(R.id.dlUrl); btnInstall = findViewById(R.id.btnInstall); btnCancel = findViewById(R.id.btnCancel)

        editor.setText(template)
        updateGutter()
        setupConsole()
        setConsole(welcome(), false)

        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable) {
                if (highlighted) {
                    for (sp in s.getSpans(0, s.length, BackgroundColorSpan::class.java)) s.removeSpan(sp)
                    highlighted = false; errorLines = emptySet(); warnLines = emptySet(); lastLineCount = -1
                }
                updateGutter()
            }
        })

        for (t in listOf("Output", "Problems", "Log", "Download")) tabs.addTab(tabs.newTab().setText(t))
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(t: TabLayout.Tab) = showTab(t.position)
            override fun onTabUnselected(t: TabLayout.Tab) {}
            override fun onTabReselected(t: TabLayout.Tab) {}
        })

        findViewById<MaterialButton>(R.id.btnOpen).setOnClickListener { openFile.launch(arrayOf("*/*")) }
        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener { saveFile.launch(fileName.text.toString()) }
        findViewById<MaterialButton>(R.id.btnAbout).setOnClickListener { showAbout() }
        btnDownload.setOnClickListener { selectTab(3) }
        btnRun.setOnClickListener { startSession() }
        btnEof.setOnClickListener { endInput() }
        btnStd.text = std.uppercase()
        btnStd.setOnClickListener {
            MaterialAlertDialogBuilder(this).setTitle("C++ standard")
                .setSingleChoiceItems(stds.map { it.uppercase() }.toTypedArray(), stds.indexOf(std)) { d, i ->
                    std = stds[i]; btnStd.text = std.uppercase(); d.dismiss()
                }.show()
        }
        modeGroup.addOnButtonCheckedListener { _, id, checked ->
            if (checked && id == R.id.modeOffline && LocalBackend.findCompiler(this) == null) offerToolchain()
        }

        btnInstall.setOnClickListener { startInstall() }
        btnCancel.setOnClickListener { cancelFlag.cancelled = true }
        findViewById<MaterialButton>(R.id.btnPickFile).setOnClickListener { if (!installing) pickToolchain.launch(arrayOf("*/*")) }
        findViewById<MaterialButton>(R.id.btnRemove).setOnClickListener {
            if (installing) return@setOnClickListener
            MaterialAlertDialogBuilder(this).setTitle("Remove offline compiler?")
                .setMessage("This frees the storage it uses.")
                .setPositiveButton("Remove") { _, _ ->
                    ToolchainInstaller.remove(this); refreshInstalled(); modeGroup.check(R.id.modeOnline)
                }.setNegativeButton("Cancel", null).show()
        }
        refreshInstalled()
        setInstallUi(false)

        intent?.data?.let { loadUri(it) }
    }

    // =====================================================================
    // Console: program output and keyboard input live in the same view
    // =====================================================================

    private fun setupConsole() {
        console.filters = arrayOf(InputFilter { source, start, end, dest, dstart, dend ->
            if (programmatic) return@InputFilter null
            val keep = dest.subSequence(dstart, dend)
            if (!waiting || dstart < lockedLen) return@InputFilter keep   // protect program output
            val s = source.subSequence(start, end).toString()
            val nl = s.indexOf('\n')
            if (nl >= 0) {
                val d = dest.toString()
                val typed = d.substring(lockedLen, dstart) + s.substring(0, nl) + d.substring(dend)
                console.post { submitInput(typed.trim('\r', '\n')) }
                return@InputFilter keep
            }
            null
        })
    }

    private fun setConsole(text: CharSequence, editable: Boolean) {
        programmatic = true
        console.setText(text)
        programmatic = false
        lockedLen = text.length
        waiting = editable
        console.showSoftInputOnFocus = editable
        console.isCursorVisible = editable
        console.setSelection(console.text.length)
        if (editable) {
            console.requestFocus()
            getSystemService(InputMethodManager::class.java)?.showSoftInput(console, 0)
        }
        consoleScroll.post { consoleScroll.fullScroll(View.FOCUS_DOWN) }
        updateEofButton()
    }

    private fun updateEofButton() {
        btnEof.visibility = if (waiting && tabs.selectedTabPosition == 0) View.VISIBLE else View.GONE
    }

    private var pendingOffset = 0

    private fun submitInput(line: String) {
        if (!waiting || busy) return
        waiting = false
        echoAt.add(pendingOffset)
        inputs.add(line)
        setConsole(render(lastStdout), false)       // typed value appears right after the prompt
        execute()
    }

    private fun endInput() {
        if (!waiting || busy) return
        waiting = false
        eofMode = true
        setConsole(render(lastStdout), false)
        execute()
    }

    /** Program stdout with the typed inputs inserted where they were requested (like a terminal). */
    private fun render(stdout: String): String {
        val sb = StringBuilder()
        var prev = 0
        for ((k, off) in echoAt.withIndex()) {
            val o = off.coerceIn(prev, stdout.length)
            sb.append(stdout, prev, o).append(inputs[k]).append('\n')
            prev = o
        }
        sb.append(stdout, prev, stdout.length)
        return sb.toString()
    }

    private fun welcome() = "Ready.\n\nPress ▶ Run. If your program uses cin, type the value right here in the Output, just like a terminal.\n\n" +
        "Created by Reynaldi Julian\nreynaldijulian05@gmail.com"

    // =====================================================================
    // Run
    // =====================================================================

    private fun startSession() {
        val code = editor.text.toString()
        if (code.isBlank()) { Toast.makeText(this, "Write or open some code first", Toast.LENGTH_SHORT).show(); return }
        if (busy) return
        session++
        runSource = code
        runOffline = modeGroup.checkedButtonId == R.id.modeOffline
        runStd = std
        runLint = Analyzer.preCheck(code)
        inputs.clear(); echoAt.clear(); lastStdout = ""; eofMode = false; pendingOffset = 0
        selectTab(0)
        setConsole(if (runOffline) "Compiling on device…" else "Compiling…", false)
        execute()
    }

    private fun execute() {
        setBusy(true)
        val mySession = session
        val src = if (eofMode) runSource else InputHook.instrument(runSource)
        val stdinText = if (inputs.isEmpty()) "" else inputs.joinToString("\n") + "\n"
        val backend: CompilerBackend = if (runOffline) LocalBackend(this) else OnlineBackend()
        val chosen = runStd
        Thread {
            val res = try { backend.run(src, stdinText, chosen) }
            catch (e: Exception) { RunResult(infraError = "Unexpected error: ${e.message}") }
            runOnUiThread { if (mySession == session) onResult(res) }
        }.start()
    }

    private fun setBusy(b: Boolean) {
        busy = b
        progress.visibility = if (b) View.VISIBLE else View.INVISIBLE
        btnRun.isEnabled = !b
        btnRun.text = if (b) "…" else "▶  Run"
    }

    private fun onResult(raw: RunResult) {
        setBusy(false)
        val needsInput = raw.stderr.contains(InputHook.SENTINEL)
        val r = raw.copy(stderr = raw.stderr.replace(InputHook.SENTINEL, ""))

        val userLines = runSource.count { it == '\n' } + 1
        val compilerDiags = Analyzer.parseCompilerLog(r.compilerLog).filter { it.line <= userLines }
        val covered = compilerDiags.filter { it.line > 0 }.map { it.line }.toSet()
        val failedCompile = !r.compiled && r.infraError == null
        val extraLint = runLint.filter { it.line == 0 || it.line !in covered }
            .filter { l -> compilerDiags.none { it.severity == Severity.ERROR } || (l.severity == Severity.ERROR && l.line > 0) }
        val runtime = if (needsInput) emptyList() else Analyzer.runtimeAdvice(r)
        val all = (compilerDiags + extraLint + runtime)
            .sortedWith(compareBy({ it.severity.ordinal }, { if (it.line == 0) Int.MAX_VALUE else it.line }))
        highlight(all)
        renderProblems(all)
        val errCount = all.count { it.severity == Severity.ERROR }
        val warnCount = all.count { it.severity == Severity.WARNING }
        tabs.getTabAt(1)?.text = if (all.isEmpty()) "Problems" else "Problems ($errCount·$warnCount)"
        logView.text = buildString {
            append("Mode: ").append(if (runOffline) "offline (on-device clang++)" else "online (remote GCC)").append("  |  ").append(runStd).append("\n\n")
            append(r.compilerLog.trim().ifEmpty { "(no compiler messages)" })
            if (r.infraError != null) append("\n\n").append(r.infraError)
        }

        when {
            r.infraError != null -> {
                val extra = if (runLint.any { it.severity == Severity.ERROR }) "\n\nThe built-in check still found problems in your code – see the Problems tab." else ""
                setConsole("⚠ ${r.infraError}$extra", false)
            }
            failedCompile -> {
                setConsole("✖ Compilation failed – see the Problems tab for what to fix.\n\n" + r.compilerLog.trim().take(4000), false)
                if (all.isNotEmpty()) selectTab(1)
            }
            needsInput -> {
                lastStdout = r.stdout
                pendingOffset = r.stdout.length
                setConsole(render(r.stdout), true)
            }
            else -> {
                val sb = StringBuilder(render(r.stdout))
                if (r.stderr.isNotBlank()) { if (!sb.endsWith("\n")) sb.append('\n'); sb.append("[stderr] ").append(r.stderr.trimEnd()).append('\n') }
                if (!sb.endsWith("\n") && sb.isNotEmpty()) sb.append('\n')
                sb.append("\n— ").append(when {
                    r.timedOut -> "stopped: time limit exceeded"
                    r.exitCode == 0 || r.exitCode == null -> "finished (exit code 0)"
                    else -> "finished with exit code ${r.exitCode}"
                })
                setConsole(sb, false)
                if (all.any { it.source == "runtime" }) selectTab(1)
            }
        }
    }

    // =====================================================================
    // Editor helpers
    // =====================================================================

    private fun updateGutter() {
        val count = editor.text.count { it == '\n' } + 1
        if (count == lastLineCount && !highlighted) return
        lastLineCount = count
        val sb = SpannableStringBuilder()
        for (i in 1..count) {
            val start = sb.length
            sb.append(i.toString())
            if (i < count) sb.append('\n')
            val color = when {
                i in errorLines -> ContextCompat.getColor(this, R.color.error)
                i in warnLines -> ContextCompat.getColor(this, R.color.warn)
                else -> 0
            }
            if (color != 0) sb.setSpan(ForegroundColorSpan(color), start, start + i.toString().length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        gutter.text = sb
    }

    private fun lineRange(line: Int): IntRange? {
        val text = editor.text
        var cur = 1; var start = 0
        while (cur < line) {
            val nl = text.indexOf('\n', start)
            if (nl < 0) return null
            start = nl + 1; cur++
        }
        var end = text.indexOf('\n', start)
        if (end < 0) end = text.length
        return start..end
    }

    private fun highlight(diags: List<Diagnostic>) {
        val e = editor.text
        for (sp in e.getSpans(0, e.length, BackgroundColorSpan::class.java)) e.removeSpan(sp)
        errorLines = diags.filter { it.severity == Severity.ERROR && it.line > 0 }.map { it.line }.toSet()
        warnLines = diags.filter { it.severity == Severity.WARNING && it.line > 0 }.map { it.line }.toSet() - errorLines
        for (l in warnLines) lineRange(l)?.let { if (it.last > it.first) e.setSpan(BackgroundColorSpan(0x33FFCB6B), it.first, it.last, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE) }
        for (l in errorLines) lineRange(l)?.let { if (it.last > it.first) e.setSpan(BackgroundColorSpan(0x55FF5370), it.first, it.last, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE) }
        highlighted = errorLines.isNotEmpty() || warnLines.isNotEmpty()
        lastLineCount = -1
        updateGutter()
    }

    private fun goToLine(line: Int) {
        val r = lineRange(line) ?: return
        editor.requestFocus()
        editor.setSelection(r.first.coerceAtMost(editor.text.length))
        editor.layout?.let { editorScroll.smoothScrollTo(0, (it.getLineTop(line - 1) - 120).coerceAtLeast(0)) }
    }

    private fun showTab(pos: Int) {
        consoleScroll.visibility = if (pos == 0) View.VISIBLE else View.GONE
        problemsScroll.visibility = if (pos == 1) View.VISIBLE else View.GONE
        logScroll.visibility = if (pos == 2) View.VISIBLE else View.GONE
        downloadScroll.visibility = if (pos == 3) View.VISIBLE else View.GONE
        updateEofButton()
    }

    private fun selectTab(pos: Int) { tabs.getTabAt(pos)?.select(); showTab(pos) }

    private fun renderProblems(list: List<Diagnostic>) {
        problems.removeAllViews()
        if (list.isEmpty()) {
            problems.addView(TextView(this).apply {
                text = "✓ No problems found"; setTextColor(ContextCompat.getColor(this@MainActivity, R.color.ok)); textSize = 14f; setPadding(dp(8), dp(8), dp(8), dp(8))
            })
            return
        }
        for (d in list) {
            val color = ContextCompat.getColor(this, when (d.severity) { Severity.ERROR -> R.color.error; Severity.WARNING -> R.color.warn; else -> R.color.accent })
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.card_bg)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
                if (d.line > 0) setOnClickListener { goToLine(d.line) }
            }
            val place = if (d.line > 0) "Line ${d.line}${if (d.col > 0) ":${d.col}" else ""}" else "General"
            card.addView(TextView(this).apply {
                text = "${d.severity.name}  ·  $place  ·  ${d.source}"
                setTextColor(color); textSize = 11f; typeface = Typeface.DEFAULT_BOLD
            })
            card.addView(TextView(this).apply {
                text = d.message; setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text)); textSize = 14f; setPadding(0, dp(4), 0, dp(6))
            })
            card.addView(TextView(this).apply {
                text = "💡 ${d.advice}"; setTextColor(ContextCompat.getColor(this@MainActivity, R.color.muted)); textSize = 13f
            })
            problems.addView(card)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // =====================================================================
    // Files
    // =====================================================================

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) return c.getString(i)
        }
        return uri.lastPathSegment ?: "main.cpp"
    }

    private fun displaySize(uri: Uri): Long {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.SIZE)
            if (i >= 0 && c.moveToFirst()) return c.getLong(i)
        }
        return -1
    }

    private fun loadUri(uri: Uri) {
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalStateException("Cannot open file")
            if (bytes.size > 1_000_000) { Toast.makeText(this, "File too large (max 1 MB)", Toast.LENGTH_LONG).show(); return }
            val text = String(bytes, Charsets.UTF_8).replace("\r\n", "\n").replace("\uFEFF", "")
            val name = displayName(uri)
            fileName.text = if (name.endsWith(".txt", true)) name.dropLast(4) + ".cpp" else name   // .txt becomes .cpp
            editor.setText(text)
            errorLines = emptySet(); warnLines = emptySet(); problems.removeAllViews()
            tabs.getTabAt(1)?.text = "Problems"
            session++; setBusy(false)
            setConsole(welcome(), false)
            Toast.makeText(this, "Loaded $name", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open file: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveUri(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri, "wt")?.use { it.write(editor.text.toString().toByteArray()) }
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Could not save: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // =====================================================================
    // Offline compiler download / install
    // =====================================================================

    private fun offerToolchain() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Offline compiler")
            .setMessage("Offline mode needs a C++ compiler stored on this phone (Android NDK clang for arm64, roughly 0.5–1 GB). It is downloaded once from GitHub. Keep the app open while it downloads; a Wi-Fi connection is recommended.")
            .setPositiveButton("Download") { _, _ -> selectTab(3); startInstall() }
            .setNegativeButton("Use Online") { _, _ -> modeGroup.check(R.id.modeOnline) }
            .setOnCancelListener { modeGroup.check(R.id.modeOnline) }
            .show()
    }

    private fun refreshInstalled() {
        val c = LocalBackend.findCompiler(this)
        dlInstalled.text = if (c != null) "Installed ✓  ${c.parentFile?.parentFile?.name ?: ""}" else "Not installed"
    }

    private fun setInstallUi(on: Boolean) {
        installing = on
        btnInstall.isEnabled = !on
        btnCancel.isEnabled = on
        dlUrl.isEnabled = !on
        btnDownload.text = if (on) "⬇ …" else "⬇"
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun showProgress(p: ToolchainInstaller.Progress) {
        val pct = if (p.total > 0) (p.done * 100.0 / p.total) else 0.0
        dlBar.progress = (pct * 10).toInt()
        dlStatus.text = p.phase + "…"
        btnDownload.text = "⬇ ${pct.toInt()}%"
        val parts = mutableListOf(String.format("%.1f%%", pct))
        parts += if (p.total > 0) "${ToolchainInstaller.fmt(p.done)} / ${ToolchainInstaller.fmt(p.total)}" else ToolchainInstaller.fmt(p.done)
        if (p.speed > 1) parts += String.format("%.1f MB/s", p.speed / 1048576.0)
        if (p.etaSec >= 0) parts += "ETA " + fmtEta(p.etaSec)
        dlDetail.text = parts.joinToString("  ·  ")
    }

    private fun fmtEta(s: Long): String =
        if (s >= 3600) String.format("%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60) else String.format("%d:%02d", s / 60, s % 60)

    private fun startInstall() {
        if (installing) return
        cancelFlag = ToolchainInstaller.Cancel()
        val flag = cancelFlag
        val custom = dlUrl.text.toString().trim()
        setInstallUi(true)
        dlBar.progress = 0
        dlStatus.text = "Looking for the package…"
        dlDetail.text = ""
        Thread {
            val result = try {
                val asset = if (custom.isNotEmpty())
                    ToolchainInstaller.Asset(custom, custom.substringAfterLast('/').substringBefore('?').ifEmpty { "toolchain.zip" }, -1)
                else ToolchainInstaller.resolveAsset()
                runOnUiThread { dlStatus.text = "Package: ${asset.name}" }
                ToolchainInstaller.installFromUrl(this, asset, flag) { p -> runOnUiThread { showProgress(p) } }
                null
            } catch (e: ToolchainInstaller.Cancelled) { "Cancelled. Tap Download again to resume." }
            catch (e: java.net.UnknownHostException) { "No internet connection." }
            catch (e: Exception) { "Failed: ${e.message}" }
            runOnUiThread { finishInstall(result) }
        }.start()
    }

    private fun installFromUri(uri: Uri) {
        if (installing) return
        cancelFlag = ToolchainInstaller.Cancel()
        val flag = cancelFlag
        val name = displayName(uri); val size = displaySize(uri)
        setInstallUi(true)
        selectTab(3)
        Thread {
            val result = try {
                val ins = contentResolver.openInputStream(uri) ?: throw java.io.IOException("Cannot open file")
                ins.use { ToolchainInstaller.installFromStream(this, name, it, size, flag) { p -> runOnUiThread { showProgress(p) } } }
                null
            } catch (e: ToolchainInstaller.Cancelled) { "Cancelled." }
            catch (e: Exception) { "Failed: ${e.message}" }
            runOnUiThread { finishInstall(result) }
        }.start()
    }

    private fun finishInstall(error: String?) {
        setInstallUi(false)
        refreshInstalled()
        if (error == null && LocalBackend.findCompiler(this) != null) {
            dlBar.progress = 1000
            dlStatus.text = "Done ✓ Offline compiler is ready."
            dlDetail.text = ""
            modeGroup.check(R.id.modeOffline)
        } else {
            dlStatus.text = error ?: "Install finished but the compiler was not found."
            if (LocalBackend.findCompiler(this) == null) modeGroup.check(R.id.modeOnline)
        }
    }

    private fun showAbout() {
        MaterialAlertDialogBuilder(this)
            .setTitle("C++ Studio 1.1")
            .setMessage("Compile and run C++ on Android, with error locations and repair advice.\n\nCreated by\nReynaldi Julian\nreynaldijulian05@gmail.com\nreynaldijulian05@student.esaunggul.ac.id\n\nOnline mode uses a remote GCC (wandbox.org). Offline mode uses the Android NDK clang you download in the ⬇ tab.")
            .setPositiveButton("OK", null).show()
    }
}
