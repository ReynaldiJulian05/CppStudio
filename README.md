# C++ Studio (Android 9 / API 28+)

Created by Reynaldi Julian – reynaldijulian05@gmail.com

## Build the APK
1. Install Android Studio (Koala or newer). In SDK Manager install "Android SDK Platform 34".
2. File > Open > select the CppStudio folder. Wait for "Gradle sync" to finish (needs internet once).
3. Build > Build Bundle(s) / APK(s) > Build APK(s). Click "locate" in the popup:
   app/build/outputs/apk/debug/app-debug.apk
4. Copy the APK to the phone and install it (allow "Install unknown apps").

## Modes
- Online (default): code is compiled by GCC on wandbox.org. Needs internet.
- Offline: open the ⬇ tab, tap "Download & install". It downloads the Android NDK for aarch64
  (lzhiyong/termux-ndk, statically linked) and shows percent, speed and ETA.
  Needs roughly 3x the package size in free storage while installing.

## Using cin
Type the value directly in the Output tab after the prompt, press Enter. The program is re-run
with your inputs, so it should be deterministic. "End input (Ctrl+D)" sends EOF for loops like while (cin >> x).
