package com.reynaldi.cppstudio

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.DocumentsContract
import android.util.TypedValue
import android.text.Editable
import android.text.InputFilter
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import java.io.File
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout

class MainActivity : AppCompatActivity() {

    // editor
    private lateinit var editor: EditText
    private lateinit var gutter: TextView
    private lateinit var editorScroll: ScrollView
    private lateinit var fileName: TextView
    private lateinit var headerBar: View
    private lateinit var modeBar: View
    private lateinit var fileActionsBar: View
    private lateinit var editorToolsBar: View
    private lateinit var bottomPanel: View
    private lateinit var btnEditorExpand: MaterialButton
    private lateinit var btnZoomIn: MaterialButton
    private lateinit var btnZoomOut: MaterialButton
    private var editorTextSize = 13f

    // bottom panel
    private lateinit var tabs: TabLayout
    private lateinit var console: EditText
    private lateinit var consoleScroll: ScrollView
    private lateinit var consoleBox: View
    private lateinit var btnEof: MaterialButton
    private lateinit var problems: LinearLayout
    private lateinit var problemsScroll: ScrollView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var downloadScroll: ScrollView
    private lateinit var examplesSearch: EditText
    private lateinit var examplesCount: TextView
    private lateinit var examplesList: LinearLayout
    private var lastProblemsText = ""
    private var lastDiagnostics: List<Diagnostic> = emptyList()
    private var lastAnalyzedCode = ""
    private val ignoredKeys = mutableSetOf<String>()   // findings the user chose to ignore (kept until Clear all / new file)
    private var showIgnored = false

    // toolbar
    private lateinit var progress: ProgressBar
    private lateinit var btnRun: MaterialButton
    private lateinit var btnDownload: MaterialButton
    private lateinit var modeGroup: MaterialButtonToggleGroup
    private lateinit var btnServer: MaterialButton
    private lateinit var btnRuler: MaterialButton
    private lateinit var btnTidy: MaterialButton
    private lateinit var btnAnalyze: MaterialButton
    private lateinit var btnExpand: MaterialButton
    private lateinit var backCallback: OnBackPressedCallback
    private var expanded = false
    private var editorExpanded = false
    private val history = EditHistory()
    private var rulerEnabled = true
    private var untidyLines: Set<Int> = emptySet()
    private lateinit var btnUndo: MaterialButton
    private lateinit var btnRedo: MaterialButton

    // download tab
    private lateinit var dlInstalled: TextView
    private lateinit var dlStatus: TextView
    private lateinit var dlBar: ProgressBar
    private lateinit var dlDetail: TextView
    private lateinit var dlUrl: EditText
    private lateinit var btnInstall: MaterialButton
    private lateinit var btnCancel: MaterialButton

    private val std = "c++23"                 // only the newest standard
    private var currentUri: Uri? = null       // file the editor is saved to (null = not saved yet)
    private var editorProgrammatic = false
    private val hl = Handler(Looper.getMainLooper())
    private val hlRun = Runnable { Syntax.apply(editor.text, this) }
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
    private var runStd = "c++23"
    private var runLint: List<Diagnostic> = emptyList()
    private val inputs = mutableListOf<String>()
    private val echoAt = mutableListOf<Int>()
    private var lastStdout = ""
    private var eofMode = false

    // installer
    private var installing = false
    private var cancelFlag = ToolchainInstaller.Cancel()

    private val openFile = registerForActivityResult(OpenDoc()) { uri -> if (uri != null) loadUri(uri) }
    private val saveFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null && writeTo(uri)) { currentUri = uri; fileName.text = displayName(uri) }
    }
    private val pickToolchain = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) installFromUri(uri) }
    private val exportExamples = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) {
            try {
                val output = contentResolver.openOutputStream(uri)
                    ?: throw java.io.IOException("Unable to open the selected file for writing.")
                ExampleDatabase.exportZip(output)
                Toast.makeText(this, "Exported ${ExampleDatabase.entries.size} C++ examples.", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Example export failed: ${e.message ?: "unknown error"}", Toast.LENGTH_LONG).show()
            }
        }
    }

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
        val savedDarkMode = getSharedPreferences("cppstudio_settings", MODE_PRIVATE)
            .getBoolean("dark_theme", true)
        AppCompatDelegate.setDefaultNightMode(
            if (savedDarkMode) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        editor = findViewById(R.id.editor); gutter = findViewById(R.id.gutter)
        editorScroll = findViewById(R.id.editorScroll); fileName = findViewById(R.id.fileName)
        headerBar = findViewById(R.id.headerBar); modeBar = findViewById(R.id.modeBar)
        fileActionsBar = findViewById(R.id.fileActionsBar); editorToolsBar = findViewById(R.id.editorToolsBar)
        bottomPanel = findViewById(R.id.bottomPanel)
        btnEditorExpand = findViewById(R.id.btnEditorExpand)
        btnZoomIn = findViewById(R.id.btnZoomIn); btnZoomOut = findViewById(R.id.btnZoomOut)
        editorTextSize = getPreferences(MODE_PRIVATE).getFloat("editor_text_size", 13f).coerceIn(9f, 32f)
        applyEditorTextSize()
        fileName.setOnClickListener { showRenameDialog() }
        fileName.contentDescription = "Current file name. Tap to rename."
        btnZoomIn.setOnClickListener { changeEditorTextSize(1f) }
        btnZoomOut.setOnClickListener { changeEditorTextSize(-1f) }
        btnEditorExpand.setOnClickListener { setEditorExpanded(!editorExpanded) }
        val btnTheme = findViewById<MaterialButton>(R.id.btnTheme)
        updateThemeButton(btnTheme, savedDarkMode)
        btnTheme.setOnClickListener {
            val nextDark = !getSharedPreferences("cppstudio_settings", MODE_PRIVATE).getBoolean("dark_theme", true)
            getSharedPreferences("cppstudio_settings", MODE_PRIVATE).edit().putBoolean("dark_theme", nextDark).apply()
            AppCompatDelegate.setDefaultNightMode(
                if (nextDark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
            )
        }
        tabs = findViewById(R.id.tabs); console = findViewById(R.id.console)
        consoleScroll = findViewById(R.id.consoleScroll); consoleBox = findViewById(R.id.consoleBox); btnEof = findViewById(R.id.btnEof)
        problems = findViewById(R.id.problems); problemsScroll = findViewById(R.id.problemsScroll)
        logView = findViewById(R.id.logView); logScroll = findViewById(R.id.logScroll)
        downloadScroll = findViewById(R.id.downloadScroll)
        examplesSearch = findViewById(R.id.examplesSearch)
        examplesCount = findViewById(R.id.examplesCount)
        examplesList = findViewById(R.id.examplesList)
        renderExampleCatalogue("")
        examplesSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                renderExampleCatalogue(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        findViewById<MaterialButton>(R.id.btnExportExamples).setOnClickListener {
            exportExamples.launch("CppStudio-1.4-examples.zip")
        }
        progress = findViewById(R.id.progress); btnRun = findViewById(R.id.btnRun)
        btnDownload = findViewById(R.id.btnDownload)
        modeGroup = findViewById(R.id.modeGroup)
        btnServer = findViewById(R.id.btnServer)
        btnServer.setOnClickListener { showServerDialog() }
        btnRuler = findViewById(R.id.btnRuler)
        rulerEnabled = getPreferences(MODE_PRIVATE).getBoolean("indent_ruler", true)
        updateRulerButton()
        btnRuler.setOnClickListener {
            rulerEnabled = !rulerEnabled
            getPreferences(MODE_PRIVATE).edit().putBoolean("indent_ruler", rulerEnabled).apply()
            updateRulerButton()
            refreshIndentGuides()
        }
        btnTidy = findViewById(R.id.btnTidy)
        btnTidy.setOnClickListener { showTidyDialog() }
        btnAnalyze = findViewById(R.id.btnAnalyze)
        btnAnalyze.setOnClickListener { analyzeCurrentCode() }
        btnUndo = findViewById(R.id.btnUndo); btnRedo = findViewById(R.id.btnRedo)
        btnUndo.setOnClickListener { doUndo() }
        btnRedo.setOnClickListener { doRedo() }
        btnExpand = findViewById(R.id.btnExpand)
        btnExpand.setOnClickListener { setExpanded(!expanded) }
        backCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                when {
                    editorExpanded -> setEditorExpanded(false)
                    expanded -> setExpanded(false)
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, backCallback)
        dlInstalled = findViewById(R.id.dlInstalled); dlStatus = findViewById(R.id.dlStatus)
        dlBar = findViewById(R.id.dlBar); dlDetail = findViewById(R.id.dlDetail)
        dlUrl = findViewById(R.id.dlUrl); btnInstall = findViewById(R.id.btnInstall); btnCancel = findViewById(R.id.btnCancel)

        editor.filters = arrayOf(autoEditFilter())
        if (savedInstanceState != null) {
            fileName.text = savedInstanceState.getString("editor_file_name", "main.cpp")
            currentUri = savedInstanceState.getParcelable("editor_uri")
        }
        val startingCode = if (savedInstanceState != null) {
            val statePath = savedInstanceState.getString("editor_state_file")
            val stateFile = statePath?.let { File(it) }
            if (stateFile != null && stateFile.isFile) {
                try { stateFile.readText().also { stateFile.delete() } } catch (_: Exception) { template }
            } else template
        } else template
        setEditorText(startingCode)
        updateGutter()
        refreshIndentGuides()
        setupConsole()
        setConsole(welcome(), false)

        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable) {
                if (!editorProgrammatic) { history.record(s.toString()); updateUndoButtons() }
                if (highlighted) {
                    for (sp in s.getSpans(0, s.length, BackgroundColorSpan::class.java)) s.removeSpan(sp)
                    highlighted = false; errorLines = emptySet(); warnLines = emptySet(); lastLineCount = -1
                }
                updateGutter()
                refreshIndentGuides()
                scheduleHighlight()
            }
        })

        for (t in listOf("Output", "Problems", "Log", "Download")) tabs.addTab(tabs.newTab().setText(t))
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(t: TabLayout.Tab) = showTab(t.position)
            override fun onTabUnselected(t: TabLayout.Tab) {}
            override fun onTabReselected(t: TabLayout.Tab) {}
        })

        findViewById<MaterialButton>(R.id.btnOpen).setOnClickListener { openFile.launch(arrayOf("*/*")) }
        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener { saveCurrent() }
        findViewById<MaterialButton>(R.id.btnSaveAs).setOnClickListener { saveFile.launch(suggestName()) }
        findViewById<MaterialButton>(R.id.btnClear).setOnClickListener { confirmClear() }
        findViewById<MaterialButton>(R.id.btnAbout).setOnClickListener { showAbout() }
        btnDownload.setOnClickListener { selectTab(3) }
        btnRun.setOnClickListener { startSession() }
        btnEof.setOnClickListener { endInput() }
        modeGroup.addOnButtonCheckedListener { _, id, checked ->
            if (checked) btnServer.visibility = if (id == R.id.modeOnline) View.VISIBLE else View.GONE
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

        if (savedInstanceState == null) {
            intent?.data?.let { loadUri(it) }
        } else {
            val savedCursor = savedInstanceState.getInt("editor_cursor", 0)
            editor.post { editor.setSelection(savedCursor.coerceIn(0, editor.text.length)) }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::editor.isInitialized) {
            try {
                val stateFile = File(filesDir, "editor-state.cpp")
                stateFile.writeText(editor.text.toString())
                outState.putString("editor_state_file", stateFile.absolutePath)
            } catch (_: Exception) {
                // Keep the rest of the small UI state even if private storage is unavailable.
            }
            outState.putString("editor_file_name", fileName.text.toString())
            outState.putParcelable("editor_uri", currentUri)
            outState.putInt("editor_cursor", editor.selectionStart.coerceAtLeast(0))
        }
        super.onSaveInstanceState(outState)
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
        btnEof.text = "End program"
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
        MaterialAlertDialogBuilder(this)
            .setTitle("End program?")
            .setMessage("This sends end-of-file to the program (like Ctrl+D in a terminal), so input loops such as while (cin >> x) can finish. Any input the program has not received will remain unavailable.")
            .setPositiveButton("End program") { _, _ ->
                if (!waiting || busy) return@setPositiveButton
                waiting = false
                eofMode = true
                setConsole(render(lastStdout), false)
                execute()
            }
            .setNegativeButton("Cancel", null).show()
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
        setConsole(if (runOffline) "Compiling on device…" else "Compiling on ${ServerPrefs.selected(this).title}…", false)
        execute()
    }

    private fun execute() {
        setBusy(true)
        val mySession = session
        val src = if (eofMode) runSource else InputHook.instrument(runSource)
        val stdinText = if (inputs.isEmpty()) "" else inputs.joinToString("\n") + "\n"
        val backend: CompilerBackend = if (runOffline) LocalBackend(this) else FailoverBackend(ServerPrefs.order(this))
        val chosen = runStd
        // Initialize otherwise-uninitialized local values when the user explicitly ends program input.
        val extra = if (eofMode) listOf("-ftrivial-auto-var-init=zero") else emptyList()
        Thread {
            var res = try { backend.run(src, stdinText, chosen, extra) }
            catch (e: Exception) { RunResult(infraError = "Unexpected error: ${e.message}") }
            if (extra.isNotEmpty() && !res.compiled && res.infraError == null && res.compilerLog.contains("trivial-auto-var-init")) {
                res = try { backend.run(src, stdinText, chosen) } catch (e: Exception) { res }   // compiler too old for the flag
            }
            val finalRes = res
            runOnUiThread { if (mySession == session) onResult(finalRes) }
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
        val active = activeDiagnostics(all, runSource)
        highlight(active)
        renderProblems(all, runSource)
        lastProblemsText = active.filter { it.severity != Severity.INFO }.take(8)
            .joinToString("\n") { (if (it.line > 0) "line ${it.line}: " else "") + it.message }
        updateProblemsTab(all, runSource)
        logView.text = buildString {
            append("Mode: ").append(if (runOffline) "offline (on-device clang++)" else "online (${r.server.ifEmpty { "remote server" }})").append("  |  ").append(runStd).append("\n\n")
            if (r.failoverNote.isNotEmpty()) append("Servers that failed first:\n").append(r.failoverNote).append("\n\n")
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
        lastLineCount = count
        untidyLines = CppFormatter.findUntidyLines(editor.text.toString())
        val sb = SpannableStringBuilder()
        for (i in 1..count) {
            val start = sb.length
            sb.append(i.toString())
            if (i < count) sb.append('\n')
            val color = when {
                i in errorLines -> ContextCompat.getColor(this, R.color.error)
                i in warnLines -> ContextCompat.getColor(this, R.color.warn)
                i in untidyLines -> ContextCompat.getColor(this, R.color.warn)
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
        for (l in warnLines) lineRange(l)?.let { if (it.last > it.first) e.setSpan(BackgroundColorSpan(ContextCompat.getColor(this, R.color.diagnostic_warn_bg)), it.first, it.last, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE) }
        for (l in errorLines) lineRange(l)?.let { if (it.last > it.first) e.setSpan(BackgroundColorSpan(ContextCompat.getColor(this, R.color.diagnostic_error_bg)), it.first, it.last, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE) }
        highlighted = errorLines.isNotEmpty() || warnLines.isNotEmpty()
        lastLineCount = -1
        updateGutter()
    }

    private fun goToLine(line: Int) {
        val r = lineRange(line) ?: return
        if (expanded) setExpanded(false)   // the editor must be visible to show the line
        editor.requestFocus()
        editor.setSelection(r.first.coerceAtMost(editor.text.length))
        editor.layout?.let { editorScroll.smoothScrollTo(0, (it.getLineTop(line - 1) - 120).coerceAtLeast(0)) }
    }

    /** Full-screen output: hides the editor so Output / Problems / Log / Download fill the screen. */
    private fun setExpanded(on: Boolean) {
        if (on && editorExpanded) setEditorExpanded(false)
        expanded = on
        editorScroll.visibility = if (on) View.GONE else View.VISIBLE
        editorToolsBar.visibility = if (on) View.GONE else View.VISIBLE
        btnExpand.setIconResource(if (on) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen)
        btnExpand.contentDescription = if (on) "Back to editor" else "Full screen output"
        backCallback.isEnabled = expanded || editorExpanded
        if (on) getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(editor.windowToken, 0)
    }

    /** Editor-only full screen; the zoom and exit controls stay available. */
    private fun setEditorExpanded(on: Boolean) {
        if (on && expanded) setExpanded(false)
        editorExpanded = on
        headerBar.visibility = if (on) View.GONE else View.VISIBLE
        modeBar.visibility = if (on) View.GONE else View.VISIBLE
        fileActionsBar.visibility = if (on) View.GONE else View.VISIBLE
        bottomPanel.visibility = if (on) View.GONE else View.VISIBLE
        editorToolsBar.visibility = View.VISIBLE
        editorScroll.visibility = View.VISIBLE
        btnEditorExpand.text = if (on) "Exit full screen" else "Full screen"
        btnEditorExpand.setIconResource(if (on) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen)
        backCallback.isEnabled = expanded || editorExpanded
        if (on) getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(editor.windowToken, 0)
    }

    private fun renderExampleCatalogue(query: String) {
        if (!::examplesList.isInitialized || !::examplesCount.isInitialized) return
        val needle = query.trim().lowercase()
        val matching = ExampleDatabase.entries.filter { example ->
            needle.isEmpty() || listOf(example.title, example.category, example.variant, example.summary, example.id)
                .any { it.lowercase().contains(needle) }
        }
        examplesList.removeAllViews()
        examplesCount.text = "Showing ${matching.size} of ${ExampleDatabase.entries.size} offline examples"
        for (example in matching) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.card_bg)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) }
            }
            card.addView(TextView(this).apply {
                text = example.title
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text))
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
            })
            card.addView(TextView(this).apply {
                text = "${example.category}  ·  ${example.variant}"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent))
                textSize = 11f
                setPadding(0, dp(3), 0, dp(4))
            })
            card.addView(TextView(this).apply {
                text = example.summary
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.muted))
                textSize = 12f
            })
            card.addView(MaterialButton(this).apply {
                text = "Open in editor"
                isAllCaps = false
                setOnClickListener {
                    setEditorText(example.code, keepHistory = true)
                    currentUri = null
                    fileName.text = "${example.id}.cpp"
                    ignoredKeys.clear()
                    showIgnored = false
                    lastDiagnostics = emptyList()
                    lastAnalyzedCode = ""
                    problems.removeAllViews()
                    tabs.getTabAt(1)?.text = "Problems"
                    selectTab(0)
                    editor.setSelection(0)
                    editor.requestFocus()
                    Toast.makeText(this@MainActivity, "Opened ${example.title}. Tap Analyze to check it.", Toast.LENGTH_SHORT).show()
                }
            })
            examplesList.addView(card)
        }
        if (matching.isEmpty()) {
            examplesList.addView(TextView(this).apply {
                text = "No examples match this search. Try a topic such as arrays, recursion, or search."
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.muted))
                textSize = 13f
                setPadding(dp(8), dp(12), dp(8), dp(12))
            })
        }
    }

    private fun showTab(pos: Int) {
        consoleBox.visibility = if (pos == 0) View.VISIBLE else View.GONE
        problemsScroll.visibility = if (pos == 1) View.VISIBLE else View.GONE
        logScroll.visibility = if (pos == 2) View.VISIBLE else View.GONE
        downloadScroll.visibility = if (pos == 3) View.VISIBLE else View.GONE
        updateEofButton()
    }

    private fun selectTab(pos: Int) { tabs.getTabAt(pos)?.select(); showTab(pos) }

    /** Stable identity of a finding: survives line shifts because it uses the line's text, not its number. */
    private fun diagKey(d: Diagnostic, code: String): String {
        val lineText = if (d.line > 0) code.split("\n").getOrNull(d.line - 1)?.trim().orEmpty() else ""
        return "${d.source}|${d.severity}|${d.message}|$lineText"
    }

    private fun activeDiagnostics(list: List<Diagnostic>, code: String): List<Diagnostic> =
        list.filter { diagKey(it, code) !in ignoredKeys }

    private fun updateProblemsTab(list: List<Diagnostic>, code: String) {
        val active = activeDiagnostics(list, code)
        val errors = active.count { it.severity == Severity.ERROR }
        val warnings = active.count { it.severity == Severity.WARNING }
        tabs.getTabAt(1)?.text = if (active.isEmpty()) "Problems" else "Problems ($errors·$warnings)"
    }

    /** Re-draws the Problems list, tab counter and line highlights after ignoring or restoring. */
    private fun onIgnoreChanged() {
        renderProblems(lastDiagnostics, lastAnalyzedCode)
        updateProblemsTab(lastDiagnostics, lastAnalyzedCode)
        if (highlighted && editor.text.toString() == lastAnalyzedCode)
            highlight(activeDiagnostics(lastDiagnostics, lastAnalyzedCode))
    }

    private fun renderProblems(list: List<Diagnostic>, analyzedCode: String = editor.text.toString()) {
        lastDiagnostics = list
        lastAnalyzedCode = analyzedCode
        problems.removeAllViews()
        val code = analyzedCode
        val editorMatchesAnalysis = editor.text.toString() == analyzedCode
        // Most important first: errors, then warnings, then info; within a level, by line.
        val active = activeDiagnostics(list, code)
            .sortedWith(compareBy({ it.severity.ordinal }, { if (it.line == 0) Int.MAX_VALUE else it.line }))
        val ignored = list.filter { it !in active }
        val allPlan = if (editorMatchesAnalysis && active.isNotEmpty()) Analyzer.buildRepairPlan(code, active) else null

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(12))
        }
        header.addView(TextView(this).apply {
            text = when {
                list.isEmpty() -> "Code analysis"
                active.isEmpty() -> "No active findings"
                else -> "${active.size} finding${if (active.size == 1) "" else "s"} found"
            }
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text))
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
        })
        header.addView(TextView(this).apply {
            text = if (editorMatchesAnalysis)
                "Static checks and compiler diagnostics. Review every proposed edit before applying it."
            else "The editor changed after this run. Tap Analyze to refresh findings before repairing the current source."
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.muted))
            textSize = 12f
            setPadding(0, dp(3), 0, dp(8))
        })
        if (allPlan != null) {
            header.addView(MaterialButton(this).apply {
                text = "Review all safe repairs (${allPlan.changes.size})"
                isAllCaps = false
                setOnClickListener { showRepairPreview(allPlan, active.filter { isPlanHandled(it) }) }
            })
        }
        problems.addView(header)

        if (list.isEmpty() || active.isEmpty()) {
            problems.addView(TextView(this).apply {
                text = if (list.isEmpty()) "✓ No problems found" else "✓ No active problems (${ignored.size} ignored)"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.ok))
                textSize = 14f
                setPadding(dp(8), dp(8), dp(8), dp(8))
            })
        }

        for (d in active) problems.addView(buildProblemCard(d, list, code, editorMatchesAnalysis, isIgnored = false))

        if (ignored.isNotEmpty()) {
            problems.addView(TextView(this).apply {
                text = (if (showIgnored) "▾" else "▸") + "  Ignored (${ignored.size}) – tap to " + (if (showIgnored) "hide" else "show")
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent))
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(8), dp(12), dp(8), dp(12))
                setOnClickListener { showIgnored = !showIgnored; renderProblems(lastDiagnostics, lastAnalyzedCode) }
            })
            if (showIgnored) {
                problems.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = "Restore all (${ignored.size})"
                    isAllCaps = false
                    layoutParams = LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(8); marginStart = dp(8) }
                    setOnClickListener { ignoredKeys.clear(); showIgnored = false; onIgnoreChanged() }
                })
                for (d in ignored) problems.addView(buildProblemCard(d, list, code, editorMatchesAnalysis, isIgnored = true))
            }
        }
    }

    private fun buildProblemCard(d: Diagnostic, list: List<Diagnostic>, code: String, editorMatchesAnalysis: Boolean, isIgnored: Boolean): View {
        val color = ContextCompat.getColor(this, when (d.severity) {
            Severity.ERROR -> R.color.error
            Severity.WARNING -> R.color.warn
            else -> R.color.accent
        })
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.card_bg)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
            if (isIgnored) alpha = 0.7f
            if (d.line > 0 && editorMatchesAnalysis) setOnClickListener { goToLine(d.line) }
        }
        val place = if (d.line > 0) "Line ${d.line}${if (d.col > 0) ":${d.col}" else ""}" else "General"
        card.addView(TextView(this).apply {
            text = (if (isIgnored) "IGNORED  ·  " else "") + "${d.severity.name}  ·  $place  ·  ${d.source}"
            setTextColor(color)
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
        })
        card.addView(TextView(this).apply {
            text = d.message
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text))
            textSize = 14f
            setPadding(0, dp(4), 0, dp(6))
        })
        card.addView(TextView(this).apply {
            text = "Solution: ${d.advice}"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.muted))
            textSize = 13f
        })

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        // Main action: only for active findings, and only when a deterministic local edit exists.
        if (!isIgnored && editorMatchesAnalysis && isDirectlyRepairable(d)) {
            val relatedDiagnostics = if (d.message.contains("is never closed", ignoreCase = true))
                list.filter { it.message.contains("is never closed", ignoreCase = true) }
            else listOf(d)
            Analyzer.buildRepairPlan(code, relatedDiagnostics)?.let { singlePlan ->
                row.addView(MaterialButton(this).apply {
                    text = "Preview suggested fix"
                    isAllCaps = false
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                    setOnClickListener { showRepairPreview(singlePlan, relatedDiagnostics) }
                })
            }
        } else if (editorMatchesAnalysis && d.line > 0) {
            row.addView(MaterialButton(this).apply {
                text = "Go to line ${d.line}"
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                setOnClickListener { goToLine(d.line) }
            })
        }
        // Ignore / Restore: ignored findings stay in the "Ignored" section so they can be brought back.
        row.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = if (isIgnored) "Restore" else "Ignore"
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { if (row.childCount > 0) marginStart = dp(8) }
            setOnClickListener {
                val key = diagKey(d, code)
                if (isIgnored) ignoredKeys.remove(key) else ignoredKeys.add(key)
                onIgnoreChanged()
            }
        })
        card.addView(row)
        return card
    }

    private fun isDirectlyRepairable(d: Diagnostic): Boolean =
        d.message.contains("missing a ';'", ignoreCase = true) ||
            d.message.contains("expected ';'", ignoreCase = true) ||
            d.message.startsWith("Uses something from ") ||
            d.message.startsWith("Standard-library names are used without std::") ||
            d.message.startsWith("Header <") ||
            d.message == "This line has trailing whitespace" ||
            d.message.contains("is never closed", ignoreCase = true) ||
            (d.source.endsWith("linear-search-iterative") &&
                d.message == ExampleAnalyzer.ITERATIVE_LINEAR_RETURN_MESSAGE)

    private fun analyzeCurrentCode() {
        val code = editor.text.toString()
        if (code.isBlank()) {
            Toast.makeText(this, "Write or open some C++ code first.", Toast.LENGTH_SHORT).show()
            return
        }
        val findings = Analyzer.preCheck(code)
        renderProblems(findings)
        updateProblemsTab(findings, code)
        selectTab(1)
        val activeCount = activeDiagnostics(findings, code).size
        val ignoredCount = findings.size - activeCount
        Toast.makeText(this, "Analysis complete: $activeCount finding(s)" + (if (ignoredCount > 0) ", $ignoredCount ignored." else "."), Toast.LENGTH_SHORT).show()
    }

    /** Findings that a repair plan can fix (used to hide them if the user keeps the original code). */
    private fun isPlanHandled(d: Diagnostic): Boolean =
        isDirectlyRepairable(d) || d.message.contains("Indentation does not match", ignoreCase = true)

    private fun showRepairPreview(plan: RepairPlan, handled: List<Diagnostic> = emptyList()) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(4))
        }
        container.addView(TextView(this).apply {
            text = "${plan.changes.size} proposed change(s). Nothing is applied until you choose Keep changes."
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.muted))
            textSize = 12f
            setPadding(0, 0, 0, dp(8))
        })
        container.addView(TextView(this).apply {
            text = "PROPOSED REPAIRS"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent))
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
        })
        container.addView(TextView(this).apply {
            text = plan.changes.joinToString("\n") { "• $it" }
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text))
            textSize = 12f
            setPadding(0, dp(4), 0, dp(10))
        })
        addCodePreview(container, "BEFORE — CURRENT CODE", plan.originalCode)
        addCodePreview(container, "AFTER — PROPOSED CODE", plan.proposedCode)

        MaterialAlertDialogBuilder(this)
            .setTitle("Review code changes")
            .setView(ScrollView(this).apply {
                isFillViewport = true
                addView(container)
            })
            .setPositiveButton("Keep changes") { _, _ -> applyRepairPlan(plan) }
            .setNegativeButton("Revert / Keep original") { _, _ -> keepOriginal(plan, handled) }
            .show()
    }

    private fun addCodePreview(parent: LinearLayout, heading: String, source: String) {
        parent.addView(TextView(this).apply {
            text = heading
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text))
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(4), 0, dp(5))
        })
        val codeView = TextView(this).apply {
            text = source.ifEmpty { "(empty)" }
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        parent.addView(ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(126)).apply { bottomMargin = dp(8) }
            setBackgroundResource(R.drawable.editor_bg)
            addView(codeView)
        })
    }

    /**
     * The user declined the suggested repairs: hide those findings (they stay retrievable in the
     * "Ignored" section) so the list moves on to the more important problems.
     */
    private fun keepOriginal(plan: RepairPlan, handled: List<Diagnostic>) {
        val added = handled.map { diagKey(it, plan.originalCode) }.filter { ignoredKeys.add(it) }
        if (added.isEmpty()) return
        onIgnoreChanged()
        problemsScroll.post { problemsScroll.scrollTo(0, 0) }
        Snackbar.make(findViewById(android.R.id.content),
            "Kept your original code. ${added.size} suggestion(s) hidden.", Snackbar.LENGTH_LONG)
            .setAction("Undo") { ignoredKeys.removeAll(added.toSet()); onIgnoreChanged() }
            .show()
    }

    private fun applyRepairPlan(plan: RepairPlan) {
        val current = editor.text.toString()
        if (current != plan.originalCode) {
            Toast.makeText(this, "The code changed after the preview. Analyze again before applying repairs.", Toast.LENGTH_LONG).show()
            return
        }
        val cursor = editor.selectionStart.coerceIn(0, current.length)
        editorProgrammatic = true
        try { editor.setText(plan.proposedCode) } finally { editorProgrammatic = false }
        history.recordStep(plan.proposedCode)
        editor.setSelection(cursor.coerceAtMost(editor.text.length))
        updateUndoButtons()
        updateGutter()
        refreshIndentGuides()
        scheduleHighlight()

        val remaining = Analyzer.preCheck(plan.proposedCode)
        renderProblems(remaining, plan.proposedCode)
        updateProblemsTab(remaining, plan.proposedCode)
        problemsScroll.post { problemsScroll.scrollTo(0, 0) }
        Snackbar.make(findViewById(android.R.id.content), "Repairs applied.", Snackbar.LENGTH_LONG)
            .setAction("Undo") {
                doUndo()
                val restored = editor.text.toString()
                val findings = Analyzer.preCheck(restored)
                renderProblems(findings, restored)
                updateProblemsTab(findings, restored)
            }
            .show()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Lets the user pick which online compiler to use, and shows what each one is best for. */
    private fun updateRulerButton() {
        btnRuler.alpha = if (rulerEnabled) 1f else 0.45f
        btnRuler.contentDescription = if (rulerEnabled) "Hide indentation guides" else "Show indentation guides"
    }

    private fun refreshIndentGuides() {
        CppFormatter.updateRuler(
            editor,
            rulerEnabled,
            guideColor = ContextCompat.getColor(this, R.color.indent_guide)
        )
    }

    private fun changeEditorTextSize(delta: Float) {
        editorTextSize = (editorTextSize + delta).coerceIn(9f, 32f)
        getPreferences(MODE_PRIVATE).edit().putFloat("editor_text_size", editorTextSize).apply()
        applyEditorTextSize()
    }

    private fun applyEditorTextSize() {
        if (!::editor.isInitialized || !::gutter.isInitialized) return
        editor.setTextSize(TypedValue.COMPLEX_UNIT_SP, editorTextSize)
        gutter.setTextSize(TypedValue.COMPLEX_UNIT_SP, editorTextSize)
        if (::btnZoomIn.isInitialized) btnZoomIn.isEnabled = editorTextSize < 32f
        if (::btnZoomOut.isInitialized) btnZoomOut.isEnabled = editorTextSize > 9f
    }

    private fun updateThemeButton(button: MaterialButton, dark: Boolean) {
        button.text = if (dark) "Light" else "Dark"
        button.contentDescription = if (dark) "Switch to light theme" else "Switch to dark theme"
    }

    private fun showTidyDialog() {
        val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val professional = MaterialRadioButton(this).apply {
            id = View.generateViewId()
            text = "Professional style — 4 spaces"
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val match = MaterialRadioButton(this).apply {
            id = View.generateViewId()
            text = "Match my current code — use this file as the style reference"
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        group.addView(professional)
        group.addView(match)
        val prefs = getPreferences(MODE_PRIVATE)
        val saved = prefs.getString("format_style", "professional")
        group.check(if (saved == "match") match.id else professional.id)

        MaterialAlertDialogBuilder(this)
            .setTitle("Tidy C++ code")
            .setMessage("Tidy fixes indentation and trailing spaces while preserving your existing lines and C++ statements. It does not rewrite the logic.")
            .setView(group)
            .setPositiveButton("Tidy code") { _, _ ->
                val style = if (group.checkedRadioButtonId == match.id)
                    CppFormatter.Style.MATCH_CURRENT_FILE else CppFormatter.Style.PROFESSIONAL
                prefs.edit().putString("format_style", if (style == CppFormatter.Style.MATCH_CURRENT_FILE) "match" else "professional").apply()
                applyTidy(style)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applyTidy(style: CppFormatter.Style) {
        val oldText = editor.text.toString()
        val formatted = CppFormatter.format(oldText, style)
        if (formatted == oldText) {
            Toast.makeText(this, "The code is already tidy.", Toast.LENGTH_SHORT).show()
            return
        }

        val oldCursor = editor.selectionStart.coerceAtLeast(0).coerceAtMost(oldText.length)
        val line = oldText.substring(0, oldCursor).count { it == '\n' }
        val oldLineStart = oldText.lastIndexOf('\n', (oldCursor - 1).coerceAtLeast(0)).let { it + 1 }
        val oldLineEnd = oldText.indexOf('\n', oldLineStart).let { if (it < 0) oldText.length else it }
        val oldLineText = oldText.substring(oldLineStart, oldLineEnd)
        val oldIndent = oldLineText.takeWhile { it == ' ' || it == '\t' }.length
        val contentColumn = (oldCursor - oldLineStart - oldIndent).coerceAtLeast(0)
        val newLines = formatted.split('\n')
        var newCursor = 0
        for (i in 0 until line.coerceAtMost(newLines.lastIndex)) newCursor += newLines[i].length + 1
        val newLineText = newLines.getOrElse(line) { "" }
        val newIndent = newLineText.takeWhile { it == ' ' || it == '\t' }.length
        newCursor += newIndent + contentColumn.coerceAtMost((newLineText.length - newIndent).coerceAtLeast(0))

        editorProgrammatic = true
        try { editor.setText(formatted) } finally { editorProgrammatic = false }
        history.recordStep(formatted)
        editor.setSelection(newCursor.coerceIn(0, editor.text.length))
        updateUndoButtons()
        updateGutter()
        refreshIndentGuides()
        scheduleHighlight()
        Toast.makeText(this, "Code tidied. You can Undo to restore the previous layout.", Toast.LENGTH_SHORT).show()
    }

    private fun showServerDialog() {
        val servers = OnlineServer.values()
        var picked = ServerPrefs.selected(this)
        val pad = dp(20)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, dp(8), pad, 0) }
        val group = RadioGroup(this)
        val ids = HashMap<Int, OnlineServer>()
        for (srv in servers) {
            val sb = SpannableStringBuilder()
            sb.append(srv.title, StyleSpan(Typeface.BOLD), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            val start = sb.length
            sb.append("\n").append(srv.compiler).append("\n").append(srv.bestFor)
            sb.setSpan(RelativeSizeSpan(0.85f), start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(ContextCompat.getColor(this, R.color.muted)), start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            val rb = MaterialRadioButton(this).apply {
                id = View.generateViewId()
                text = sb
                setPadding(dp(4), dp(8), 0, dp(8))
            }
            ids[rb.id] = srv
            group.addView(rb)
            if (srv == picked) group.check(rb.id)
        }
        group.setOnCheckedChangeListener { _, checkedId -> ids[checkedId]?.let { picked = it } }
        val auto = MaterialCheckBox(this).apply {
            text = "Automatically try other servers if this one fails (may take longer)"
            isChecked = ServerPrefs.autoFallback(this@MainActivity)
        }
        box.addView(group)
        box.addView(auto)
        MaterialAlertDialogBuilder(this)
            .setTitle("Online compiler server")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Use") { _, _ ->
                ServerPrefs.save(this, picked, auto.isChecked)
                Toast.makeText(this, "Online server: ${picked.title}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

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
            val isTxt = name.endsWith(".txt", true)
            fileName.text = if (isTxt) name.dropLast(4) + ".cpp" else name   // .txt becomes .cpp
            currentUri = if (isTxt) null else uri                             // a converted .txt is saved as a new file
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (_: Exception) {}
            setEditorText(text)
            errorLines = emptySet(); warnLines = emptySet(); problems.removeAllViews()
            ignoredKeys.clear(); showIgnored = false
            tabs.getTabAt(1)?.text = "Problems"
            session++; setBusy(false)
            setConsole(welcome(), false)
            Toast.makeText(this, "Loaded $name", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open file: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showRenameDialog() {
        val input = EditText(this).apply {
            setSingleLine(true)
            setText(fileName.text)
            setSelection(text.length)
            hint = "main.cpp"
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Rename source file")
            .setMessage("Tap the file name again whenever you want to rename it.")
            .setView(input)
            .setPositiveButton("Rename") { _, _ -> renameCurrentFile(input.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun renameCurrentFile(rawName: String) {
        var newName = rawName.trim()
        if (newName.isEmpty() || newName.contains('/') || newName.contains('\\')) {
            Toast.makeText(this, "Enter a valid file name.", Toast.LENGTH_SHORT).show()
            return
        }
        if (!newName.contains('.')) newName += ".cpp"
        val oldUri = currentUri
        if (oldUri != null) {
            try {
                val renamed = DocumentsContract.renameDocument(contentResolver, oldUri, newName)
                if (renamed != null) {
                    currentUri = renamed
                    fileName.text = displayName(renamed)
                    Toast.makeText(this, "File renamed.", Toast.LENGTH_SHORT).show()
                    return
                }
            } catch (_: Exception) {
                // Some document providers do not implement rename. Do not change the
                // label while keeping a URI that still points at the old filename.
            }
            Toast.makeText(this, "This storage provider cannot rename the file. Use Save as instead.", Toast.LENGTH_LONG).show()
            return
        }
        fileName.text = newName
        Toast.makeText(this, "File name updated.", Toast.LENGTH_SHORT).show()
    }

    private fun suggestName(): String {
        val n = fileName.text.toString()
        return if (n.contains('.')) n else "$n.cpp"
    }

    /** Save: overwrite the current file, or ask where to save when the file is new. */
    private fun saveCurrent() {
        val u = currentUri
        if (u == null || !writeTo(u)) saveFile.launch(suggestName())
    }

    private fun writeTo(uri: Uri): Boolean = try {
        contentResolver.openOutputStream(uri, "wt")?.use { it.write(editor.text.toString().toByteArray()) }
            ?: throw IllegalStateException("Cannot open for writing")
        Toast.makeText(this, "Saved ${displayName(uri)}", Toast.LENGTH_SHORT).show()
        true
    } catch (e: Exception) {
        Toast.makeText(this, "Could not save there. Choose a location.", Toast.LENGTH_LONG).show()
        false
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete all code?")
            .setMessage("The editor will be empty, like a new file. Undo can bring it back, but Save first if you want to keep it.")
            .setPositiveButton("Delete all") { _, _ -> clearAll() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun clearAll() {
        setEditorText("", keepHistory = true)
        currentUri = null
        fileName.text = "main.cpp"
        errorLines = emptySet(); warnLines = emptySet(); highlighted = false
        ignoredKeys.clear(); showIgnored = false
        problems.removeAllViews()
        tabs.getTabAt(1)?.text = "Problems"
        logView.text = ""
        session++; setBusy(false)
        setConsole(welcome(), false)
        editor.requestFocus()
    }

    // =====================================================================
    // Editor: auto-close brackets/quotes, auto-indent, syntax colours
    // =====================================================================

    private val pairs = mapOf('(' to ')', '[' to ']', '{' to '}', '"' to '"', '\'' to '\'')

    /** Replaces the whole text. By default the undo history starts over (new file); keepHistory = true makes it one undoable step. */
    private fun setEditorText(t: String, keepHistory: Boolean = false) {
        editorProgrammatic = true
        editor.setText(t)
        editorProgrammatic = false
        Syntax.apply(editor.text, this)
        if (keepHistory) history.record(t) else history.reset(t)
        updateUndoButtons()
    }

    private fun updateUndoButtons() {
        btnUndo.isEnabled = history.canUndo()
        btnRedo.isEnabled = history.canRedo()
    }

    private fun doUndo() { history.undo()?.let { showHistoryText(it) } }
    private fun doRedo() { history.redo()?.let { showHistoryText(it) } }

    /** Puts [target] in the editor by replacing only the part that differs, then places the cursor there. */
    private fun showHistoryText(target: String) {
        val cur = editor.text
        val old = cur.toString()
        val max = minOf(old.length, target.length)
        var p = 0
        while (p < max && old[p] == target[p]) p++
        var s = 0
        while (s < max - p && old[old.length - 1 - s] == target[target.length - 1 - s]) s++
        editorProgrammatic = true
        try { cur.replace(p, old.length - s, target.substring(p, target.length - s)) } finally { editorProgrammatic = false }
        editor.setSelection((target.length - s).coerceIn(0, editor.text.length))
        updateUndoButtons()
    }

    // Rotating the screen restores the text from the system, not through the history: start fresh.
    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        history.reset(editor.text.toString())
        updateUndoButtons()
    }

    private fun scheduleHighlight() { hl.removeCallbacks(hlRun); hl.postDelayed(hlRun, 150) }

    private fun autoEditFilter() = InputFilter { source, start, end, dest, dstart, dend ->
        if (editorProgrammatic) return@InputFilter null
        val len = end - start

        // Backspace on an empty pair like () removes both characters
        if (len == 0 && dend - dstart == 1) {
            val closer = pairs[dest[dstart]]
            if (closer != null && dend < dest.length && dest[dend] == closer) {
                editor.post { val t = editor.text; if (dstart < t.length && t[dstart] == closer) t.delete(dstart, dstart + 1) }
            }
            return@InputFilter null
        }
        if (len != 1 || dend != dstart) return@InputFilter null    // only plain single-character typing

        val c = source[start]
        val next = if (dstart < dest.length) dest[dstart] else '\u0000'
        val prev = if (dstart > 0) dest[dstart - 1] else '\u0000'
        val closers = ")]}\"'"

        when {
            c == '\n' -> {
                val d = dest.toString()
                val ls = d.lastIndexOf('\n', dstart - 1) + 1
                var k = ls
                while (k < dstart && (d[k] == ' ' || d[k] == '\t')) k++
                val indent = d.substring(ls, k)
                val opens = d.substring(ls, dstart).trimEnd().endsWith("{")
                val extra = if (opens) "    " else ""
                if (opens && next == '}') {
                    editor.post { editor.setSelection(dstart + 1 + indent.length + extra.length) }
                    "\n$indent$extra\n$indent"
                } else "\n$indent$extra"
            }
            c in closers && next == c -> {                          // typing the closer that is already there: skip over it
                editor.post { editor.setSelection(dstart + 1) }
                ""
            }
            c == '(' || c == '[' || c == '{' -> {
                editor.post { editor.setSelection(dstart + 1) }
                "$c${pairs[c]}"
            }
            (c == '"' || c == '\'') && !prev.isLetterOrDigit() -> {  // not for words like don't
                editor.post { editor.setSelection(dstart + 1) }
                "$c$c"
            }
            else -> null
        }
    }

    // =====================================================================
    // Offline compiler download / install
    // =====================================================================

    private fun offerToolchain() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Offline compiler")
            .setMessage("Offline mode needs a C++23 compiler stored on this phone (Android arm64 clang). This update verifies the compiler by compiling and running a test program before enabling Offline mode. An older compiler installation may need to be downloaded again. Keep the app open during installation; Wi-Fi is recommended.")
            .setPositiveButton("Download") { _, _ -> selectTab(3); startInstall() }
            .setNegativeButton("Use Online") { _, _ -> modeGroup.check(R.id.modeOnline) }
            .setOnCancelListener { modeGroup.check(R.id.modeOnline) }
            .show()
    }

    private fun refreshInstalled() {
        val c = LocalBackend.findCompiler(this)
        val sz = ToolchainInstaller.installedSize(this)
        val oldPath = getSharedPreferences("toolchain", MODE_PRIVATE).getString("clang", null)
        val oldInstallExists = oldPath?.let { File(it).isFile } == true
        dlInstalled.text = when {
            c != null -> "Installed ✓  C++23 (clang)" + (if (sz > 0) "  ·  uses ${ToolchainInstaller.fmt(sz)}" else "")
            oldInstallExists -> "Reinstall required · previous compiler not verified"
            else -> "Not installed"
        }
        updateInstallButton()
    }

    /**
     * The install button shows "Downloaded" (green) for as long as a verified compiler is installed,
     * whether it came from the download or from "Use a file…", and returns to "Download & install"
     * once the package is removed.
     */
    private fun updateInstallButton() {
        if (!::btnInstall.isInitialized) return
        val installed = LocalBackend.findCompiler(this) != null
        val (label, colorRes) = when {
            installing -> "Installing…" to R.color.accent
            installed -> "Downloaded ✓" to R.color.ok
            else -> "Download & install" to R.color.accent
        }
        btnInstall.text = label
        btnInstall.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, colorRes))
    }

    private fun setInstallUi(on: Boolean) {
        installing = on
        updateInstallButton()
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
        if (LocalBackend.findCompiler(this) != null) {
            Toast.makeText(this, "The offline compiler is already installed. Tap Remove first to install a different package.", Toast.LENGTH_LONG).show()
            return
        }
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
            .setTitle("C++ Studio 1.4")
            .setMessage("Compile and run C++ on Android, with error locations, variant-aware example analysis, and an offline C++ example library.\n\nCreated by\nReynaldi Julian\nreynaldijulian05@gmail.com\n\nOnline mode uses a remote compiler (Wandbox, Compiler Explorer or Judge0 – pick one with the server button). Offline mode uses the Android NDK clang you download in the ⬇ tab.")
            .setPositiveButton("OK", null).show()
    }
}

/** Like the normal file picker, but also asks for permission to write back to the chosen file. */
private class OpenDoc : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
}
