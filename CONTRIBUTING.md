# Contributing to OpenStick Linux

Thanks for taking an interest in contributing to OpenStick. This project aims to provide a completely free, rootless Linux workstation experience for unrooted Android devices.

---

## Core Principles

Before opening a PR, keep these fundamentals in mind:
1. **100% Free & Open Source:** We will never add paywalls, "Pro" tags, subscription checks, telemetry, or third-party ads. Everything developed here remains free for everyone.
2. **AMOLED-First Design:** All UI components follow the pure black theme (`#000000`). Avoid dark grays that light up OLED pixels on primary surfaces.
3. **Clean-Room Code:** Do not copy proprietary or decompiled closed-source binaries. Write clean, readable Kotlin and Compose code.

---

## Development Workflow

1. Fork the repository and clone your fork:
   ```bash
   git clone https://github.com/<your-username>/openstick.git
   cd openstick
   ```

2. Create a feature branch:
   ```bash
   git checkout -b feature/your-feature-name
   ```

3. Make your changes and test the build:
   ```bash
   ./gradlew assembleDebug -Dorg.gradle.jvmargs="-Xmx1536m -XX:MaxMetaspaceSize=512m"
   ```

4. Verify on real hardware using ADB:
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   adb shell am start -n org.openstick/.MainActivity
   ```

5. Check logs for unexpected crashes:
   ```bash
   adb logcat -d | grep -i openstick
   ```

6. Commit your changes with a clear, concise commit message:
   ```bash
   git add .
   git commit -m "feat(desktop): add gesture support for floating window resize"
   ```

7. Push to your branch and submit a Pull Request.

---

## Submitting Pull Requests

- Keep PRs focused on a single feature, bug fix, or performance improvement.
- Describe what was changed and attach before/after screenshots for UI changes.
- Ensure the app builds without errors or compiler warnings.
