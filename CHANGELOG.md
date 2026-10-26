# Changelog

All notable changes to the OpenStick project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [1.3.0] - 2026-10-26

### Added
- **Stuck Window Controls Toolbar**: Added dedicated window-management quick actions to the floating desktop toolbar:
  - `✕ Close`: Immediately closes the active window via `wmctrl -c :ACTIVE:` or `xdotool windowkill`.
  - `⤢ Max`: Toggles maximize and restore on the frontmost window.
  - `⎚ Min`: Minimizes the window to the taskbar.
  - `⇲ Move`: Relocates windows that opened under the top screen bezel back into the visible canvas.
- **1-Click Developer Application Catalog**:
  - **Docker & Podman**: Rootless container runtime, `podman-compose`, and Docker CLI aliases with terminal launcher.
  - **n8n Automation**: Self-hosted workflow automation platform running locally with web access at `http://localhost:5678`.
  - **Node-RED**: Low-code visual event-driven flow engine on port `1880`.
- **Dynamic AMOLED Accent Colors**: Added custom accent theme selection in Settings (**Electric Cyan**, **Emerald Neon**, **Electric Violet**, **Amber Glow**, **Sunset Coral**) dynamically updating badges, cards, and launch buttons.

### Changed
- **Vertical Screen Space Overhaul**:
  - Removed the persistent 80dp Android bottom navigation bar across the entire application.
  - Replaced bulky, text-heavy page headers with a compact floating frosted-glass navigation pill (`38dp` height, `22dp` rounded corners) containing an expandable drawer toggle and 1-tap quick switcher icons.
  - Collapses into a minimal floating `[ ☰ ]` button on Desktop and Terminal views, providing 100% edge-to-edge screen real estate for the Linux workstation.
  - Converted all Settings sections into collapsible accordion cards so no settings are removed while keeping the UI clean and scannable.
- **Glassmorphism Aesthetic**: Introduced translucent frosted glass surfaces (`#CC0D0D0D` and `#DD161616`) over pure `#000000` AMOLED pitch-black backgrounds with subtle contrast borders (`#2EFFFFFF`).

### Fixed
- **Touch & Mouse Double-Clicks**: Increased double-click timing threshold to 650ms and distance to 36px in `xsettings.xml`, `xfwm4.xml`, and touch listeners, allowing reliable double-tap detection on mobile screens.
- **Alt+Drag Window Repositioning**: Enabled `easy_click = "Alt"` in the window manager so users can hold the desktop ALT modifier chip and drag any window from anywhere on its body.

---

## [1.2.1] - 2026-10-26

### Added
- **Three-State Bridge Synchronization**: Introduced an interactive state engine distinguishing:
  - `ACTIVE` (Green): Bridge enabled by user and granted runtime permission.
  - `DISABLED` (Neutral Gray): Android permission granted, but feature toggled off by user.
  - `NEEDS_PERMISSION` (Amber): Android runtime permission required.
- **Interactive Home Status Rows**: Tapping any bridge row on the Home screen now directly toggles the feature or launches the permission prompt without having to navigate to Settings.

### Changed
- **Material 3 Dropdown Menus**:
  - **Application Theme**: Replaced 4 full-width static cards with a compact dropdown showing live color preview badges (Pure Black, Charcoal Dark, Light, System).
  - **Desktop Display Scaling (DPI)**: Added an interactive scaling dropdown supporting 96 DPI (1:1 desktop), 144 DPI (foldables/tablets), 192 DPI (recommended mobile default), and 240 DPI (large UI).
  - **DNS Configuration**: Replaced cramped horizontal filter chips with a detailed dropdown with one-tap presets for Cloudflare (1.1.1.1), Google Public DNS (8.8.8.8), OpenDNS, and Custom IP.

### Fixed
- **Bridge Toggle Mismatch**: Resolved issue where Home screen displayed "Active" in green even when bridges were toggled OFF in Settings, simply because Android runtime permissions were previously granted.

---

## [1.2.0] - 2026-10-26

### Changed
- **Complete OpenStick Rebranding**: Removed all legacy references across package names, startup scripts (`start-desktop-x11.sh`), assets, documentation, and environment variables (`OPENSTICK_DPI`, `OPENSTICK_MOTD_SHOWN`).

### Fixed
- **Terminal Resize / stty Exit 69 Pollution**: Eliminated `stty: 'standard input': unable to perform all requested operations` errors printed when the terminal PTY resized during shell initialization.
- **Responsive Terminal Greeting**: Fixed ASCII MOTD banner wrapping and distortion on narrow mobile displays by detecting PTY column width before rendering.

---

## [1.1.0] - 2026-10-26

### Added
- **Full-Screen Edge-to-Edge Desktop**: Removed outer Android padding around the XFCE canvas for a complete immersive desktop environment.
- **Integrated WebVNC Screen Sharing**: Added built-in WebVNC server allowing users to mirror and interact with their X11 desktop session from any web browser on the local network.

### Fixed
- **D-Bus Freezes**: Resolved race condition where stale D-Bus daemon sockets caused subsequent desktop launches to hang.
- **PulseAudio Feedback Loops**: Fixed recursive audio feedback when both microphone input and PulseAudio output were active simultaneously.
- **Keyboard Inset Occlusion**: Adjusted Compose IME window insets so soft keyboards no longer cover the terminal command prompt.

---

## [1.0.2] - 2026-10-26

### Added
- **Bidirectional Hardware Bridges**:
  - PulseAudio TCP audio server on `127.0.0.1:4713`.
  - Hardware camera video feed piped directly to `/run/bsl/camera`.
  - Android notification bridge forwarding `notify-send` desktop alerts to the Android system notification shade.
  - Android calendar synchronization and CUPS/PDF printing bridge.
- **Desktop Touch Gestures & Navigation**:
  - Relative trackpad emulation mode with sensitivity tuning.
  - Two-finger scrolling emulation.
  - Long-press right-click mouse simulation with haptic feedback.
  - Quick-action modifier bar with single-tap buttons for `L`, `M`, `R` mouse clicks, `ESC`, `TAB`, `CTRL`, `ALT`, `SUPER`, and arrow keys.
- **Android 16 Runtime Permission Support**: Implemented permission handling and status indicators for Android 16 (API level 36).

---

## [1.0.1] - 2026-10-26

### Added
- **Interactive VT100 / xterm-256color Terminal**:
  - Monospace terminal emulator with full ANSI 256-color escape code parsing.
  - Raw PTY session allocation backed by Debian `/usr/bin/script`.
  - Modifier quick-bar (`ESC`, `TAB`, `CTRL`, `ALT`, arrows) for efficient terminal navigation on mobile touchscreens.
- **Default XFCE4 Session**: Set XFCE4 as the default desktop environment so launching the display opens a fully interactive desktop with panels, wallpaper, and app menus instead of an empty root window.

### Fixed
- **X11 Display Server Crash on Restart**: Decoupled native `libXlorie.so` socket initialization from Android `SurfaceView` / EGL lifecycles. Stopping the display now safely detaches the surface while keeping the socket warm, allowing displays to stop and restart without triggering a SIGSEGV abort.
- **Soft Keyboard Key Drops**: Implemented a `KeyboardType.Password` delta buffer to prevent mobile IME soft keyboards from dropping keystrokes or double-typing in the raw PTY shell.

---

## [1.0.0] - 2026-10-25

### Added
- **Initial Clean-Room Release**: Free, open-source Linux container environment for Android with zero telemetry, zero proprietary license checks, and zero paywalls.
- **Debian PRoot Container Subsystem**: Automated Debian rootfs installation, architecture validation (`aarch64`), and non-root user setup.
- **In-Workspace Multi-Window Floating Canvas**: Draggable, resizable mini windows with minimize, maximize, and bottom-docked taskbar management.
- **Core Screen Navigation**: Home launchpad, Full Desktop (X11), Windowed Floating mode, Interactive Terminal, App Catalogue, and System Monitor.
- **Advanced System Settings**: VirGL 3D acceleration switch, SSH (`port 2222`) / VNC (`port 5900`) toggles, and compressed `.tar.zst` snapshot backup and restore.
