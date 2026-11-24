# C++ Studio release history

## 1.4 — version code 24
- Added the 44-entry offline C++ example catalogue and ZIP export on the Download page.
- Added variant-aware checks for Linear Search (`std::find`, iterative, and recursive), plus targeted checks for other common examples.
- Added preview-first repair for the narrowly recognised iterative Linear Search early-return mistake.
- Preserved the 1.3.x editor, Ignore/Restore, indentation, and offline compiler work.

## 1.3.2 — version code 23 maintenance milestone
- Offline compiler installation hardening: unpack to a staging directory, verify that `clang++` exists, compile and run a C++23 test, and activate the new toolchain only after validation succeeds.
- Keep an existing working installation when a replacement package fails validation.
- Retain archive import, progress reporting, and cancellation support.

## 1.3.1 — version code 22 maintenance release
- Android NDK r29 compatibility for interactive C input wrappers (`scanf`, `fscanf`, `getchar`, and `fgets`).
- Problems panel Ignore/Restore, restore-all, severity ordering, and Undo after keeping or reverting a repair preview.
- Indentation-guide fixes for comment lines and soft-wrapped continuation rows.
- Correct Download button state after compiler installation/import, plus Gradle configuration/build cache improvements.

The supplied source archive is the 1.3.1 baseline. The 1.3.2 maintenance items above are already present in that baseline's toolchain installer and are carried forward into 1.4; this project archive does not include a separate 1.3.2 APK.
