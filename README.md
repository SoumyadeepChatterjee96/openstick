# OpenStick Linux

OpenStick is a rootless Linux environment and X11 desktop runner for Android. It lets you run a full Debian container on unrooted Android devices using PRoot, complete with hardware bridges, audio, an X11 display subsystem, and a multi-window workspace.

This project is 100% free and open source. There are no paywalls, accounts, tracking, or feature gating.

---

## Features

### Graphical Workspace & X11
- **Full Desktop Mode:** Run complete desktop environments like XFCE4 or lightweight window managers like Openbox directly on device.
- **Windowed Floating Mode:** Run individual apps in standalone floating windows on the canvas instead of being locked into a single full-screen desktop session.
  - Windows can be moved, resized from the corner handle, minimized to the bottom taskbar dock, maximized to full canvas, and closed.
  - Built-in launchers for Terminal, Process Monitor, Package Maintenance Tool, Camera Bridge, and embedded X11 Viewports.
- **Hardware 3D Acceleration:** Experimental VirGL acceleration support (`virpipe` socket) for GLES rendering.

### Terminal Subsystem
- Multi-tab terminal manager built on top of native PTY streams.
- Extra key row with `ESC`, `TAB`, `CTRL`, `ALT`, pipes, arrows, and symbol shortcuts.
- Direct root/user execution modes.

### Hardware & Device Bridges
- **Camera Bridge:** Exposes the Android camera feed to the container via `/run/bsl/camera`.
- **Audio & Microphone:** PulseAudio TCP server routing sound output and microphone input between Android and guest Linux.
- **Android Notifications:** Forwards `notify-send` desktop notifications straight to your Android notification tray.
- **Calendar & Clipboard Sync:** Bidirectional clipboard sharing and Android calendar event synchronization.
- **Storage Mounting:** Shared device storage mounted automatically at `/mnt/sdcard`.
- **Printing Bridge:** PDF and CUPS print backend service.

### System & Maintenance
- **1-Click App Catalogue:** Quick-install development tools (Python, Node.js, C/C++ Build Essential, VS Code) and desktop browsers (Chromium, Firefox ESR) through APT, plus manual `.deb` installation.
- **Real-Time Resource Monitor:** CPU usage, RAM consumption, storage, and live process list with per-process kill controls.
- **Network Configuration:** One-tap DNS switcher (Cloudflare, Google, OpenDNS, Custom IP), embedded Dropbear SSH server on port `2222`, and VNC server on port `5900`.
- **Snapshots & Recovery:** Export your entire Linux container as a compressed `.tar.zst` archive to Downloads, restore from storage, or run automated `dpkg --configure -a` repair.

---

## Project Structure

```
openstick/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/org/openstick/desktop/lorie/   # Native X11 LorieView surface bindings
│       ├── jni/                               # PRoot and native PTY helper sources
│       ├── kotlin/org/openstick/
│       │   ├── MainActivity.kt                # Root container and navigation scaffold
│       │   ├── apps/                          # App catalogue & .deb package manager
│       │   ├── desktop/                       # X11 subsystem & Windowed floating workspace
│       │   ├── engine/                        # PRoot runtime, LinuxEnvironment, hardware bridges
│       │   ├── home/                          # Main dashboard & device access status
│       │   ├── monitor/                       # Live procfs metrics & process viewer
│       │   ├── settings/                      # Hardware toggles, network, snapshots & recovery
│       │   ├── terminal/                      # Interactive PTY terminal shell
│       │   └── ui/theme/                      # AMOLED pitch black theme (#000000)
│       └── res/                               # App drawables, mipmaps, and strings
├── gradle/
├── build.gradle.kts
├── settings.gradle.kts
├── CONTRIBUTING.md
├── LICENSE
└── README.md
```

---

## Building from Source

### Prerequisites
- JDK 17 or higher
- Android SDK (API Level 34, build-tools 34.0.0)
- Android NDK (r25c or newer for native PRoot compilation)
- Gradle 8.2+ (bundled via `./gradlew`)

### Build Steps

1. Clone the repository:
   ```bash
   git clone https://github.com/SoumyadeepChatterjee96/openstick.git
   cd openstick
   ```

2. Assemble the debug APK:
   ```bash
   ./gradlew assembleDebug
   ```
   *(Note: On systems with constrained RAM, pass `-Dorg.gradle.jvmargs="-Xmx1536m -XX:MaxMetaspaceSize=512m"` to avoid host OOM).*

3. The generated APK will be at:
   ```
   app/build/outputs/apk/debug/app-debug.apk
   ```

4. Install onto your device via ADB:
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

---

## First Run & Setup

1. Launch OpenStick on your Android device.
2. On first launch, the app sets up the internal rootfs directory (`/data/data/org.openstick/files/rootfs`).
3. If an initial rootfs tarball is required, download or export a Debian rootfs archive (`rootfs.tar.xz` or `.tar.zst`) into the internal directory or use the snapshot restore picker in **Settings > Snapshots & Backup**.
4. Once set up, the home screen status will change to **Ready**, allowing you to launch Desktop, Floating, or Terminal mode with one tap.

---

## Contributing

Contributions, bug reports, and pull requests are welcome. Please read [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines on code formatting and test procedures on physical hardware.

---

## License

OpenStick Linux is licensed under the [GNU General Public License v3.0](LICENSE).
