# kayralrics - auto clicker (Android)

Native Android port of the Fedora KDE visual-button autoclicker (v3-selection-fixed).

- Red / nearly-black interface; English-only
- Visual selection from a screenshot of the actual device screen
- Multiple named targets; enable/disable, rename, delete
- **500 ms** scan interval and **85%** image similarity by default
- Tap again whenever a target remains visible
- Rectangle selection by **drag** or **two separate taps**, with Reset and Save
- Floating `+ target`, `start / stop`, `hide` panel, and foreground notification
- Private internal storage for target PNGs and settings
- No accounts, Internet permission, server communication, or telemetry

## Android support

- **Android 10+** (API 29+)
- The exact Android device, Android version, display shape, screen capture mode and ROM may affect capture and taps. Test on your device.
- Uses `MediaProjection` to obtain screenshots, an explicit `AccessibilityService` for user-authorized touch gestures and optional `SYSTEM_ALERT_WINDOW` to show the selection UI. **No root is needed.**
- The accessibility service only dispatches gestures when scanning is started; it does not read screen text or collect touch events. Screen captures are processed in memory, except user-chosen target crops saved locally.
- The project uses native OpenCV for Android `org.opencv:opencv:4.13.0`, downloaded **at build time** from Maven Central, then bundled into the APK. **Runtime works offline** once installed.

## Build APK using GitHub Actions (no Android SDK setup needed on your PC)

1. Upload the contents of this folder to a GitHub repository (the files must be at repository root).
2. Go to **Actions → build android apk → Run workflow**, or push to `main`.
3. After the build succeeds, open its run → **Artifacts** → `kayralrics-auto-clicker-android-debug-apk`.
4. Download and unzip the artifact. Install `app-debug.apk` on your Android phone. This is a **debug signed APK for testing**; sign a release build with your own key before publishing.

## Build APK locally on Fedora

Requirements: JDK 17, Android SDK Platform 35, Android SDK Build-Tools 35.0.0, Gradle 8.7, network access for initial Gradle/OpenCV downloads. Android Studio can install the SDK automatically.

```bash
sudo dnf install -y java-17-openjdk-devel unzip
# Install Android Studio and Android SDK Platform 35 and Build-Tools 35.0.0.
# Install Gradle 8.7 separately (don't assume your distribution's Gradle version).
# Make sure JAVA_HOME, ANDROID_HOME, and gradle are correctly configured.
./scripts/build.sh
```

The result will be at:

```
app/build/outputs/apk/debug/app-debug.apk
```

This source package intentionally **does not** include a Gradle wrapper JAR or a prebuilt APK. Use installed Gradle 8.7 or the GitHub Actions workflow above. If you have Gradle 8.7 installed, you can generate the standard Gradle wrapper using `gradle wrapper --gradle-version 8.7`.

## Using the app

1. Launch **kayralrics - auto clicker**.
2. Tap **allow floating controls** and grant *Display over other apps* permission.
3. Tap **enable accessibility taps**. Read the explanation, open Android Settings, select *kayralrics - auto clicker*, and enable it.
4. Tap **share full screen**. Choose **entire screen**, not just one app, if you want to switch apps. Approve the Android screen-share prompt.
5. Switch to the app where the button exists. The floating control panel remains available on top.
6. Tap **+ target**. The panel disappears, the app takes a clean screenshot, and shows the frozen screen with a red selection tool. Drag across the desired button, **or tap the first corner and then the second**. Tap **save target**. The selection remains locked until you hit **reset**.
7. Add other button screenshots as needed. In the main app, rename targets and enable / disable them. Customize **interval (ms)** and **match (%)** and tap **save settings**.
8. Tap **start** on the floating panel, or **start clicking** in the main UI. The app scans repeatedly, and taps the center of one found target per frame. Different targets are considered in turn, avoiding multiple stale taps based on one screenshot.
9. Tap **stop** in the floating panel (or app UI) to pause. Tap **end share** in the ongoing notification (or **disconnect screen** in the app) to release screen capture completely.

## Important behavior and limitations

- A 500 ms interval is a **minimum desired cycle spacing**; actual interval can grow with device speed, image size, number of targets, and Accessibility gesture latency.
- Correlation uses grayscale OpenCV `TM_CCOEFF_NORMED`, like the original Fedora matcher, with one high-confidence match per checked target. The port intentionally dispatches **one gesture per fresh frame** to avoid tapping obsolete screens.
- Choose a region containing button text or a distinct icon; a solid-color rectangle cannot match reliably.
- **Screenshots match at the stored size.** Screen rotation, display scaling, button theme, and animation can break matches. Re-capture the target if the UI changes.
- Android forbids screen capture of secure or protected content (e.g. `FLAG_SECURE`), and some applications may resist simulated taps. This app does not try to bypass those restrictions.
- Android 13+ can block enabling an accessibility service for a sideloaded APK until you explicitly allow **restricted settings** from the app’s Android App Info screen (this varies by device).
- Android 14+ requires user consent for **each new MediaProjection session**; restarting screen sharing asks again. Android may end capture when the device locks, or when another app starts screen sharing.
- The ongoing notification and device privacy controls can always end a capture session. **Never activate automation on payment, confirmation, delete, or other sensitive screens.**
- If the floating overlay is hidden, restore it with the app's **show floating controls** button or the notification **controls** action.
- App target PNGs and settings are stored privately under `files/targets/` and `files/settings.json`. Android uninstall removes them (no backup is configured).

## Source files

- `app/src/main/java/net/kayralrics/autoclicker/MainActivity.java` — native red/black settings, target management, permission flow
- `CaptureService.java` — MediaProjection foreground service, `ImageReader`, rotation/resize handling, snapshot and scanning loop
- `TapAccessibilityService.java` — explicit user-enabled `dispatchGesture` touch service
- `OverlayController.java` — floating buttons and resettable drag/two-tap selection
- `VisualMatcher.java` — OpenCV template matching
- `TargetStore.java` — atomic JSON persistence and local PNG target storage
- `.github/workflows/build-apk.yml` — GitHub hosted debug APK builder

## Validation

Source structure, XML and Java syntax can be validated without an Android SDK. **An installable APK still must be compiled and run-tested against a real Android device/emulator before it can be described as error-free.** In an environment without Android SDK / dependency downloads this package cannot be honestly represented as a verified APK.
