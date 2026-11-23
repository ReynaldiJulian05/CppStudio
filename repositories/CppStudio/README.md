# C++ Studio 1.3

**C++ Studio** is an Android C++23 editor and compiler environment. It supports online compilation, an optional on-device offline compiler, interactive standard input, syntax highlighting, code analysis, formatting, and editable source files.

## Main features

### Code editor
- C++ syntax highlighting for keywords, types, functions, numbers, strings, comments, operators, and punctuation.
- Automatic closing of brackets and quotation marks, plus automatic indentation when creating a new line.
- Adjustable editor text size with **A− / A+** controls. The selected size is remembered.
- Dedicated editor full-screen mode. The zoom and exit controls remain available; Android Back also exits full screen.
- Tap the file name at the top to rename an unsaved file or a saved document when its storage provider supports renaming.
- Open, Save, Save as, and Clear all actions.
- Undo and Redo history, including a single-step undo for accepted code repairs.

### Compilation and program input
- C++23 compilation standard.
- **Online mode** with Wandbox, Compiler Explorer, and Judge0. Choose a server or enable automatic fallback when a server is unavailable.
- **Offline mode** using an installed Android NDK `clang++` toolchain for arm64 devices (Android 9 / API 28 and newer).
- Offline compiler installation supports the available ZIP, TAR.GZ, and TAR.XZ release packages, displays download/extraction progress, speed, and estimated time, and supports cancellation.
- The offline installer keeps the selected compiler, its Clang resources, the arm64 Android system headers/libraries for the app's target API, and required runtime files. It omits other CPU architectures, unrelated NDK utilities, documentation, and other API-level library copies. The original download archive is removed after a successful installation.
- Program input is entered in the Output panel. The app supports common C++ and C input patterns including `cin`, `getline`, `scanf`, `getchar`, and `fgets`. **End program** sends end-of-file (EOF) to the running program.
- Compiler output, standard output, standard error, and the exit status are shown in the Output and Log panels.

### Code analysis and repair
- Run **Analyze** to perform local static checks without compiling or running the program.
- Findings include explanations, severity, and line navigation where a source line is available.
- Checks cover common syntax/style issues such as missing semicolons, duplicate includes, trailing whitespace, inconsistent indentation, suspicious assignment in conditions, and selected non-portable console calls.
- Safe repair previews compare the current source with the proposed source before applying changes.
- Repairs are deliberately conservative. Ambiguous logic and undeclared identifiers are not automatically rewritten.

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

## Offline compiler storage

The offline compiler is optional; Online mode does not require it. The app downloads and extracts the arm64 toolchain into its private app storage. Installation needs temporary free space for the compressed package and the extracted compiler. The installer trims known-unneeded architecture, API-level, documentation, and helper-tool payloads while retaining the compiler and its required headers/libraries. It does not remove C++ headers or runtime libraries to chase an unverified smaller size.

Use the Download tab to install, import a supported compiler archive, cancel an installation, or remove the installed toolchain to reclaim storage.

## Project structure

```text
CppStudio/
├── app/
│   └── src/main/
│       ├── java/com/reynaldi/cppstudio/   # Kotlin application source
│       ├── res/                           # Layouts, icons, themes, colours
│       └── AndroidManifest.xml
├── gradle/                                # Gradle wrapper files
├── README.md
├── build.gradle.kts
├── settings.gradle.kts
└── gradlew / gradlew.bat
```

## GitHub repository folder

This folder is the complete source repository. To publish it, create an empty GitHub repository and upload the **contents of this project folder** as the repository root, or initialize Git here and push it.

Build outputs, local IDE settings, local SDK paths, and Gradle caches are excluded from the repository template.

## App details

- **Version name:** 1.3
- **Version code:** 17
- **Application ID:** `com.reynaldi.cppstudio`
- **Minimum Android version:** Android 9 (API 28)
- **C++ standard:** C++23
- **Created by:** Reynaldi Julian
