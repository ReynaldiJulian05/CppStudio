# C++ Studio 1.4

**C++ Studio** is an Android C++23 editor and compiler environment. It supports online compilation, an optional on-device offline compiler, interactive standard input, syntax highlighting, code analysis, formatting, and editable source files.

Version **1.4** (version code **24**) adds an offline C++ example catalogue and variant-aware code analysis. The existing editor, Ignore/Restore workflow, indentation improvements, and offline compiler installation safeguards from the 1.3.1 / 1.3.2 maintenance work are retained.

## Changes in 1.3.1(already deleted) (version code 22)

### Compatibility fix

- Fixed the interactive C standard-input hook for Android NDK r29 headers. The generated wrappers for `scanf`, `fscanf`, `getchar`, and `fgets` now use exception specifications that match the NDK declarations, avoiding the `exception specification in declaration does not match previous declaration` compilation errors. The four conflicting declarations were `scanf()`, `fscanf()`, `getchar()`, and `fgets()`.
- The wrapper still delegates formatted input to `vscanf` / `vfscanf`, character input to `fgetc`, and preserves the app's interactive-input sentinel behavior.

### Problems panel: Ignore and Restore
- Every finding card has an **Ignore** button. Ignored findings leave the active list, the Problems tab counter, the line-number highlights, and "Review all safe repairs".
- Ignored findings are not deleted: an **Ignored (N)** row at the bottom of the Problems tab expands to show them, and each has a **Restore** button (plus **Restore all**).
- An ignore is remembered by the finding's message and the text of its line, so it survives line-number shifts when you edit above it. It is cleared by **Clear all** or when you open another file.

- **Revert / Keep original** in the repair preview now hides the suggestions it covered (they move to the **Ignored** section and can be restored) and shows a small **Undo** button. The list is also sorted by importance (errors, then warnings, then info), so more serious problems appear first.
- **Keep changes** shows a small **Undo** button that restores the original code and brings the findings back.

### Offline compiler button
- After a compiler is installed, the **Download & install** button changes to a green **Downloaded ✓**. This applies to both an automatic download and a package added with **Use a file…**, and it lasts until the package is removed with **Remove**, when the button returns to normal.

### Indentation guides and comments
- Indentation guides are no longer drawn on comment lines: `//` comments, `/* ... */` comments, and every line inside a multi-line block comment.
- Guides are also no longer drawn on the soft-wrapped continuation rows of a long line, where wrapped text starts at the left edge and a guide used to cross it.

### Build performance
- [Gradle configuration cache](https://docs.gradle.org/current/userguide/configuration_cache.html) is enabled (`org.gradle.configuration-cache=true`). Problems are reported as warnings (`org.gradle.configuration-cache.problems=warn`) rather than failing the build.
- Gradle build cache (`org.gradle.caching=true`) and parallel project execution (`org.gradle.parallel=true`) are enabled.
- To turn the configuration cache off for one build, run `./gradlew assembleDebug --no-configuration-cache`.

## Changes in 1.4 (version code 24)

### Offline C++ example database
- Added **44 original C++ programs** to the Download tab. The catalogue is built into the app and works without an internet connection.
- Examples cover basic input/output and arithmetic, decisions and loops, functions and recursion, arrays, searching, sorting, strings, matrices, patterns, and structures.
- Each entry has a stable ID, category, implementation variant, short explanation, and reference links.
- **Export ZIP** saves every example as an individual `.cpp` source file, together with `index.csv`, `README.txt`, and `SOURCES.md`.
- GeeksforGeeks and Programiz are used as topic indexes and further-reading references. The included source programs are authored for C++ Studio rather than copied from those websites.

### Variant-aware Analyze checks
- Analyze identifies the Linear Search implementation as **Using std::find()**, **Iterative Implementation**, or **Recursive Implementation** before applying variant-specific checks.
- Iterative-only findings are not applied to `std::find` or recursive implementations. A recursive implementation is checked for a stopping condition and progress between recursive calls.
- A known iterative Linear Search mistake—returning `-1` from inside the loop—is explained, and a safe repair preview can move the not-found return below the loop when the structure is unambiguous.
- Additional targeted advice covers binary-search range updates, bubble-sort next-element bounds, a prime-check upper bound, product initialisation, and the leap-year condition.
- Repairs remain preview-first. The analyzer does not silently replace one algorithm or implementation variant with another.

### Download page layout
- The offline compiler controls and example catalogue are separate sections in the same scrollable Download page, so their controls and lists do not overlap.
- Example search filters the catalogue by title, category, ID, or implementation variant.

## Main features

### Code editor
- C++ syntax highlighting for keywords, types, functions, numbers, strings, comments, operators, and punctuation.
- Automatic closing of brackets and quotation marks, plus automatic indentation when creating a new line.
- Adjustable editor text size with **A− / A+** controls. The selected size is remembered.
- Dedicated editor full-screen mode. The zoom and exit controls remain available; Android Back also exits full screen.
- Tap the file name at the top to rename an unsaved file or a saved document when its storage provider supports renaming.
- Open, Save, Save as, and Clear actions.
- Undo and Redo history, including a single-step undo for accepted code repairs.

### Compilation and program input
- C++23 compilation standard.
- **Online mode** with Wandbox, Compiler Explorer, and Judge0. Select the server in the server dialog.
- Automatic server fallback is **off by default** to avoid waiting through several remote services after a slow or unavailable response. It can be enabled manually in the server dialog.
- HTTP requests use shorter connection/read timeouts, fixed-length request bodies, compressed responses when supported, and bounded response reading. Judge0 polling is time-limited with a gradual polling interval.
- Online execution still depends on the phone's network, the remote compiler's load, and the selected provider. These changes reduce avoidable app-side waiting; they cannot make a slow connection or remote server inherently faster.
- **Offline mode** uses an installed Android arm64 NDK `clang++` toolchain (Android 9 / API 28 and newer).
- Offline compilation runs with effective process timeouts and concurrent output draining, so a full output pipe cannot make compilation look frozen. Each run uses its own temporary folder, captures standard output and standard error separately, and removes the temporary files afterwards.
- The installer verifies the downloaded toolchain by compiling and running a small C++23 test before marking it Installed. Failed updates leave an earlier installation untouched.
- The installer supports ZIP, TAR.GZ, and TAR.XZ packages, displays download/extraction progress, speed, and estimated time, and supports cancellation and archive import.
- The installer keeps the arm64 compiler, host runtime libraries, Clang resources, C++ headers, and required Android sysroot files. It trims other target architectures, unrelated toolchains/utilities, documentation, and unneeded API-level library copies. It does not remove compiler host libraries or C++ headers just to reduce the storage figure.
- Program input is entered in the Output panel. The app supports common C++ and C input patterns including `cin`, `getline`, `scanf`, `getchar`, and `fgets`. **End program** sends end-of-file (EOF) to the running program.
- Compiler output, standard output, standard error, and the exit status are shown in the Output and Log panels.

### Code analysis and repair
- Run **Analyze** to perform local static checks without compiling or running the program.
- Findings include explanations, severity, and line navigation where a source line is available.
- Checks cover common syntax/style issues such as missing semicolons, duplicate includes, trailing whitespace, inconsistent indentation, suspicious assignment in conditions, and selected non-portable console calls.
- Safe repair previews compare the current source with the proposed source before applying changes.
- Repairs are deliberately conservative. Ambiguous logic and undeclared identifiers are not automatically rewritten.
- Example-based findings are tagged separately from compiler and general pre-check findings. Variant-specific rules run only after the matching implementation has been recognised.

### Formatting and indentation guides
- **Tidy** offers Professional style (four spaces) or Match my current code (infer tabs/spaces from the current file).
- The formatter preserves source lines, strings, comments, and program logic while applying supported whitespace cleanup.
- Nested indentation guides follow C++ brace blocks, remain in the indentation area, continue through blank lines within a block, and stop when blocks close.
- Guide contrast is increased in both themes. Guides refresh while typing, inserting or deleting lines, and after Tidy.
- Indentation issues are also shown in the line-number gutter and Problems panel.

### Interface and appearance
- Explicit **Light** and **Dark** themes. The selected theme is remembered between launches.
- Separate full-screen controls for the editor and the Output / Problems / Log / Download panel.
- The launcher mark uses a smaller `</>` symbol.

## Offline compiler: one-time reinstall for older installations

This revision changes the toolchain installation format because earlier extraction rules could remove host-side LLVM runtime libraries without proving that the compiler still worked. An offline compiler installed by an older app revision is therefore shown as **Reinstall required** and must be downloaded/imported again once.

The new installer extracts to a staging folder, checks that `clang++` exists, compiles a C++23 test program, runs that program, and only then activates the toolchain. If the new archive fails validation, it does not replace a previously installed working toolchain. The app reports the reason for a failed validation rather than marking a broken compiler as Installed.

The offline toolchain is optional; Online mode does not require it. Installation needs temporary free space for the compressed archive and the extracted compiler. Use the Download tab to install/import a supported archive, cancel an installation, or remove the installed toolchain to reclaim storage. The same tab also contains the built-in C++ example catalogue and its ZIP export action.

## Build the Android app

Open the project root in Android Studio with **JDK 17** and **Android SDK Platform 34** installed, or build from a terminal:

```bash
./gradlew assembleDebug
```

On Windows, use `gradlew.bat assembleDebug`. The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. The Gradle wrapper is configured for Gradle 8.9; the first build needs network access to download Gradle and resolve the Android Gradle Plugin and AndroidX dependencies.

## Project structure

```text
CppStudio/
├── app/
│   └── src/main/
│       ├── java/com/reynaldi/cppstudio/   # Kotlin application source
│       ├── res/                           # Layouts, icons, themes, colours
│       └── AndroidManifest.xml
├── gradle/                                # Gradle wrapper files
├── repositories/                          # GitHub-ready source mirror
├── database/                              # Exported C++ examples ZIP
├── CHANGELOG.md
├── README.md
├── build.gradle.kts
├── settings.gradle.kts
└── gradlew / gradlew.bat
```

## App details

- **Version name:** 1.4
- **Version code:** 24
- **Application ID:** `com.reynaldi.cppstudio`
- **Minimum Android version:** Android 9 (API 28)
- **C++ standard:** C++23
- **Created by:** Reynaldi Julian
