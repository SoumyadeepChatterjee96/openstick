#!/bin/sh
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
# Neutralize 90gpg-agent so XFCE4 startup does not hang indefinitely on Unix domain sockets under PRoot
if [ -f /etc/X11/Xsession.d/90gpg-agent ]; then
    echo "exit 0" > /etc/X11/Xsession.d/90gpg-agent 2>/dev/null || true
fi
# Launch a lightweight window manager (openbox) plus apps on the embedded X server.
# NO desktop environment, NO systemd, NO logind — apps are the unit (ChromeOS-style).
# The app already started the X server + created /tmp/.X11-unix/XN. Args: DISPLAY_NUM USER.
# Invoked as root under proot.
D="${1:-0}"
U="${2:-root}"
R="${3:-0}"   # 1 = rootless (apps are host windows, no guest WM)
export DEBIAN_FRONTEND=noninteractive

bsl_write_package_fixups() {
# LibreOffice. Its launcher hands over to `oosplash`, which looks at /proc/version and refuses to
# go on without it: "ERROR: /proc not mounted - LibreOffice is unlikely to work well if at all".
# /proc IS mounted; Android simply does not let an app read that one file. Nothing else in
# LibreOffice looks at it (checked in the binaries: only oosplash carries the string), so the
# office is started directly, the way its own launcher does when asked for a backtrace, with the
# two things oosplash would have done for it: the Java library path, and starting it again when
# it exits asking to be restarted (81 after first-run setup, 79 after a crash report).
#
# Written by a script that apt ALSO runs after every package change, because LibreOffice is
# usually installed from a terminal in a running session, and a fix that waits for the next
# desktop start is a fix the user never sees working.
mkdir -p /usr/local/lib/openstick /usr/local/lib/breadstick /usr/local/bin
cat > /usr/local/lib/openstick/package-fixups <<'FIX'
#!/bin/sh
# Breadstick Linux: what has to be redone when packages change. See start-desktop-x11.sh.
P=/usr/lib/libreoffice/program
MARK="Breadstick Linux: LibreOffice"
ours() { [ ! -e "$1" ] || grep -q "$MARK" "$1" 2>/dev/null; }
if [ -x "$P/soffice.bin" ]; then
    if ours /usr/local/bin/libreoffice; then
        cat > /usr/local/bin/libreoffice <<'LOW'
#!/bin/sh
# Breadstick Linux: LibreOffice without its splash launcher. See start-desktop-x11.sh.
P=/usr/lib/libreoffice/program
# Only where it is needed. The splash launcher fails on devices that will not let an app read
# /proc/version (reported on Android 14; Android 15 and later were fine), and everywhere else
# LibreOffice's own launcher is the better choice, so it is left alone there.
if head -c 1 /proc/version >/dev/null 2>&1; then
    exec "$P/soffice" "$@"
fi
SAL_ENABLE_FILE_LOCKING=1; export SAL_ENABLE_FILE_LOCKING
[ -e "$P/ooenv" ] && . "$P/ooenv"
if [ -x "$P/javaldx" ]; then
    j=$("$P/javaldx" 2>/dev/null)
    [ -n "$j" ] && { LD_LIBRARY_PATH="$j${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"; export LD_LIBRARY_PATH; }
fi
n=0
while :; do
    "$P/soffice.bin" "$@"
    rc=$?
    case "$rc" in
        79|81) n=$((n+1)); [ "$n" -lt 4 ] && continue ;;
    esac
    exit "$rc"
done
LOW
        chmod 755 /usr/local/bin/libreoffice
    fi
    # The lo* commands call the launcher by its full path, so each needs its own way round it.
    for pair in soffice: loffice: lowriter:--writer localc:--calc loimpress:--impress \
                lodraw:--draw lobase:--base lomath:--math loweb:--web lofromtemplate:.uno:NewDoc; do
        name=${pair%%:*}; flag=${pair#*:}
        ours "/usr/local/bin/$name" || continue
        printf '#!/bin/sh\n# %s, by way of /usr/local/bin/libreoffice.\nexec /usr/local/bin/libreoffice %s "$@"\n' \
            "$MARK" "$flag" > "/usr/local/bin/$name"
        chmod 755 "/usr/local/bin/$name"
    done
else
    # LibreOffice is gone: take the wrappers with it, but only files that are ours.
    for name in libreoffice soffice loffice lowriter localc loimpress lodraw lobase lomath loweb lofromtemplate; do
        [ -e "/usr/local/bin/$name" ] && grep -q "Breadstick Linux" "/usr/local/bin/$name" 2>/dev/null \
            && rm -f "/usr/local/bin/$name"
    done
fi

# Debian security fixes and stable updates. The images were built with only the release suite
# ("deb https://deb.debian.org/debian trixie main"), so no install received Debian security fixes
# until the next point release, and only then if someone upgraded by hand. Written beside the
# release line, with the same suite and components, and only while nothing else already names
# these suites. Regenerated rather than written once, so a system moved to a newer Debian release
# follows it. Create /etc/breadstick/no-debian-updates to keep this file out for good.
DEBUPD=/etc/apt/sources.list.d/breadstick-debian-updates.sources
# Each Debian source names Debian's keyring. apt 3 (trixie) prints "Notice: Missing Signed-By in the
# sources.list(5) entry for ..." at every update for a source that leaves the key to the global
# trusted.gpg.d, and both of these did: the release line the images were built with, and this file.
KR=/usr/share/keyrings/debian-archive-keyring.gpg
[ -r "$KR" ] || KR=""
if [ ! -e /etc/breadstick/no-debian-updates ] && [ -r /etc/apt/sources.list ]; then
    rel=$(sed -n 's/\[[^]]*\]//; s/^[[:space:]]*deb[[:space:]]\{1,\}\(https\{0,1\}\):\/\/deb\.debian\.org\/debian\/\{0,1\}[[:space:]]\{1,\}/\1 /p' /etc/apt/sources.list | head -n 1)
    # rel is now "https trixie main ..." (scheme, suite, components), or empty.
    set -- $rel
    if [ $# -ge 3 ]; then
        scheme=$1; suite=$2; shift 2; comps="$*"
        case "$suite" in
            *-*|sid|unstable|experimental|rc-buggy) suite="" ;;   # no security suite, or not a release
            *[!a-z]*) suite="" ;;
        esac
        if [ -n "$suite" ]; then
            others=$(grep -rlsE "(^|[[:space:]])${suite}-(security|updates)([[:space:]/]|$)" \
                /etc/apt/sources.list /etc/apt/sources.list.d/ 2>/dev/null | grep -vxF "$DEBUPD")
            if [ -z "$others" ]; then
                {
                    echo "# Breadstick Linux: Debian security fixes and stable updates for $suite."
                    echo "# Written by /usr/local/lib/breadstick/package-fixups. To keep it out, delete it and"
                    echo "# create /etc/breadstick/no-debian-updates."
                    echo "Types: deb"
                    echo "URIs: $scheme://deb.debian.org/debian"
                    echo "Suites: $suite-updates"
                    echo "Components: $comps"
                    [ -z "$KR" ] || echo "Signed-By: $KR"
                    echo
                    echo "Types: deb"
                    echo "URIs: $scheme://security.debian.org/debian-security"
                    echo "Suites: $suite-security"
                    echo "Components: $comps"
                    [ -z "$KR" ] || echo "Signed-By: $KR"
                } > "$DEBUPD.new"
                if cmp -s "$DEBUPD.new" "$DEBUPD"; then rm -f "$DEBUPD.new"; else mv -f "$DEBUPD.new" "$DEBUPD"; fi
            fi
        fi
    fi
fi
# The release line itself, and any other plain Debian line: a line with no options gets Debian's
# keyring. A line with options, and every source that is not Debian's, is left exactly as it is.
DEBLINE='^[[:space:]]*deb(-src)?[[:space:]]+https?://(deb|security)\.debian\.org/debian(-security)?/?[[:space:]]'
if [ -n "$KR" ] && [ -f /etc/apt/sources.list ] && grep -qE "$DEBLINE" /etc/apt/sources.list; then
    sed -i -E "s#^([[:space:]]*deb(-src)?)[[:space:]]+(https?://(deb|security)\.debian\.org/debian(-security)?/?[[:space:]])#\1 [signed-by=$KR] \3#" /etc/apt/sources.list
fi
# This file, where it is no longer regenerated (sources.list moved aside by apt modernize-sources).
if [ -n "$KR" ] && [ -f "$DEBUPD" ] && grep -q "^# Breadstick Linux: Debian security" "$DEBUPD" && ! grep -q "^Signed-By:" "$DEBUPD"; then
    sed -i "/^Components:/a Signed-By: $KR" "$DEBUPD"
fi

# Google Chrome under proot. It does NOT read /etc/chromium.d (that is the convention of Debians
# chromium wrapper), so it gets the same treatment two ways: a wrapper earlier in PATH for terminal
# launches, and the .desktop Exec lines, which use an absolute path and would otherwise skip the
# wrapper, pointed at it. Here rather than only at desktop start because a Chrome update rewrites
# both, and the first launch after an update in a running session used to go out without any of
# the flags.
if [ -x /opt/google/chrome/google-chrome ]; then
    # The real flag set, process limits included, lives in /etc/breadstick/chromium-flags, written
    # by install-browser.sh. The short list is the fallback for a system that has not run it yet.
    CHROME_FLAGS="--no-sandbox --disable-gpu --disable-dev-shm-usage"
    [ -r /etc/breadstick/chromium-flags ] && CHROME_FLAGS=$(cat /etc/breadstick/chromium-flags)
    mkdir -p /usr/local/bin
    # Straight to the binary, with the environment Googles launcher script would set. The script
    # itself keeps two cat processes alive for as long as Chrome runs; install-browser.sh writes
    # breadstick-browser the same way and explains it.
    for b in google-chrome google-chrome-stable; do
        cat > "/usr/local/bin/$b.new" <<CHREOF
#!/bin/sh
export CHROME_WRAPPER=/usr/local/bin/$b CHROME_VERSION_EXTRA=stable GNOME_DISABLE_CRASH_DIALOG=SET_BY_GOOGLE_CHROME
# Chrome wants a mimeapps.list to exist. touch, not ": >": a failed redirection on ":" ends a
# dash script, so a folder that is there but not writable stopped Chrome from starting at all.
d="\${XDG_DATA_HOME:-\$HOME/.local/share}/applications"
mkdir -p "\$d" 2>/dev/null && { [ -f "\$d/mimeapps.list" ] || touch "\$d/mimeapps.list" 2>/dev/null; }
F='$CHROME_FLAGS'
# Whether Chrome may draw with the device GPU (Pro), bsl-gpu-allowed decides (breadstick-gl): the
# server must answer, the app must say the GPU is on, and the browser must not be on a software list.
# Chrome on the device GPU was only verified on the Mali of the Tab S11 Ultra; on the Adreno 830 of a
# Galaxy Z Fold7 (2026-09-26) ANGLE could not create its context, the connection to the GPU server
# dropped, and Chrome crashed at every launch, so the browsers sit on the apt software list until each
# GPU family is measured. With an app older than 2.0 the 1.0.21 rule stays: software unless
# /etc/breadstick/browser-gpu exists (TESTING ONLY). On no, the originals are saved as BSL_GL_ORIG_*
# and the browser switched to software the way the policy preload does, so both agree. Asked with
# the BINARY name, chrome, which is what the preload sees inside the browser (/proc/self/exe) and
# what the apt list and a user entry name; the wrapper name differs and would get a different answer.
# Native mode (x86-64: GALLIUM_DRIVER=zink with BSL_GL_NATIVE) asks the same question; on no, every
# variable that steers Mesa to zink is saved and dropped, as the preload does there.
if [ "\$GALLIUM_DRIVER" = virpipe ] || { [ "\$GALLIUM_DRIVER" = zink ] && [ -n "\${BSL_GL_NATIVE:-}" ]; }; then
    if /usr/lib/breadstick/bsl-gpu-allowed chrome 2>/dev/null; then
        F=\$(printf '%s' "\$F" | sed 's/--disable-gpu //')
    elif [ "\$GALLIUM_DRIVER" = zink ]; then
        BSL_GL_ORIG_GALLIUM_DRIVER=\$GALLIUM_DRIVER BSL_GL_ORIG_MESA_LOADER_DRIVER_OVERRIDE=\${MESA_LOADER_DRIVER_OVERRIDE:-} BSL_GL_ORIG_LIBGL_ALWAYS_SOFTWARE=\${LIBGL_ALWAYS_SOFTWARE:-} BSL_GL_ORIG_LIBGL_KOPPER_DISABLE=\${LIBGL_KOPPER_DISABLE:-} BSL_GL_ORIG_INTEL_FORCE_PROBE=\${INTEL_FORCE_PROBE:-}
        export BSL_GL_ORIG_GALLIUM_DRIVER BSL_GL_ORIG_MESA_LOADER_DRIVER_OVERRIDE BSL_GL_ORIG_LIBGL_ALWAYS_SOFTWARE BSL_GL_ORIG_LIBGL_KOPPER_DISABLE BSL_GL_ORIG_INTEL_FORCE_PROBE
        unset GALLIUM_DRIVER MESA_LOADER_DRIVER_OVERRIDE INTEL_FORCE_PROBE
        export LIBGL_ALWAYS_SOFTWARE=1 LIBGL_KOPPER_DISABLE=1
    else
        BSL_GL_ORIG_GALLIUM_DRIVER=\$GALLIUM_DRIVER BSL_GL_ORIG_VTEST_SOCKET_NAME=\${VTEST_SOCKET_NAME:-} BSL_GL_ORIG_LIBGL_ALWAYS_SOFTWARE=\${LIBGL_ALWAYS_SOFTWARE:-}
        export BSL_GL_ORIG_GALLIUM_DRIVER BSL_GL_ORIG_VTEST_SOCKET_NAME BSL_GL_ORIG_LIBGL_ALWAYS_SOFTWARE
        unset GALLIUM_DRIVER VTEST_SOCKET_NAME
        export LIBGL_ALWAYS_SOFTWARE=1
    fi
fi
exec /opt/google/chrome/chrome \$F "\$@" </dev/null
CHREOF
        chmod 755 "/usr/local/bin/$b.new"
        if cmp -s "/usr/local/bin/$b.new" "/usr/local/bin/$b"; then rm -f "/usr/local/bin/$b.new"
        else mv -f "/usr/local/bin/$b.new" "/usr/local/bin/$b"; fi
    done
    # Googles own menu entry runs /usr/bin/google-chrome-stable, which is not the wrapper: its Exec
    # lines go through the wrapper instead, which carries the flags and the GPU rule, so the entry
    # itself carries no flags (older versions added them there; they are taken out again, and only
    # Googles own --incognito stays). Other channels (beta, unstable) keep the flags added here.
    for d in /usr/share/applications/google-chrome*.desktop; do
        [ -f "$d" ] || continue
        awk -v flags="$CHROME_FLAGS" '
            /^Exec=/ {
                n = split(substr($0, 6), t, " "); b = t[1]; sub(/.*\//, "", b)
                if (b == "google-chrome" || b == "google-chrome-stable") {
                    line = "Exec=/usr/local/bin/" b
                    for (i = 2; i <= n; i++) if (t[i] !~ /^--/ || t[i] == "--incognito") line = line " " t[i]
                    print line; next
                }
                if (b ~ /^google-chrome/ && $0 !~ / --no-sandbox/) sub(/^Exec=[^ ]*/, "& " flags)
            }
            { print }' "$d" > "$d.bsl-new" 2>/dev/null || { rm -f "$d.bsl-new"; continue; }
        if cmp -s "$d.bsl-new" "$d"; then rm -f "$d.bsl-new"; else mv -f "$d.bsl-new" "$d"; fi
    done
fi
# Microsoft Edge (installed from Microsofts .deb; x86-64 only, there is no Edge for Linux on ARM):
# Chromium underneath, with the same need for --no-sandbox, and a launcher that is a copy of Googles
# (the same two cat processes, no flags). Without the flags its zygote cannot start under proot and
# Edge exits at once ("zygote_linux.cc: write: Broken pipe", an Intel Googlebook, 2026-10-12). Same
# treatment as Google Chrome above: a wrapper earlier in PATH, and its menu entries pointed at it,
# redone after every package change because an Edge update rewrites both. Always software: Edge on
# the device GPU has not been measured on any GPU family (msedge is on the GPU software lists too).
if [ -x /opt/microsoft/msedge/msedge ]; then
    EDGE_FLAGS="--no-sandbox --disable-gpu --disable-dev-shm-usage"
    [ -r /etc/breadstick/chromium-flags ] && EDGE_FLAGS=$(cat /etc/breadstick/chromium-flags)
    mkdir -p /usr/local/bin
    for b in microsoft-edge microsoft-edge-stable; do
        cat > "/usr/local/bin/$b.new" <<EDGEEOF
#!/bin/sh
# Breadstick Linux: Microsoft Edge with the flags it needs under proot. See package-fixups.
export CHROME_WRAPPER=/usr/local/bin/$b CHROME_VERSION_EXTRA=stable GNOME_DISABLE_CRASH_DIALOG=SET_BY_GOOGLE_CHROME
d="\${XDG_DATA_HOME:-\$HOME/.local/share}/applications"
mkdir -p "\$d" 2>/dev/null && { [ -f "\$d/mimeapps.list" ] || touch "\$d/mimeapps.list" 2>/dev/null; }
# Software rendering whatever the GPU setting, the way the GPU wrappers switch a program off it.
if [ -n "\${GALLIUM_DRIVER:-}" ]; then
    BSL_GL_ORIG_GALLIUM_DRIVER=\$GALLIUM_DRIVER BSL_GL_ORIG_VTEST_SOCKET_NAME=\${VTEST_SOCKET_NAME:-} BSL_GL_ORIG_MESA_LOADER_DRIVER_OVERRIDE=\${MESA_LOADER_DRIVER_OVERRIDE:-} BSL_GL_ORIG_LIBGL_ALWAYS_SOFTWARE=\${LIBGL_ALWAYS_SOFTWARE:-} BSL_GL_ORIG_INTEL_FORCE_PROBE=\${INTEL_FORCE_PROBE:-}
    export BSL_GL_ORIG_GALLIUM_DRIVER BSL_GL_ORIG_VTEST_SOCKET_NAME BSL_GL_ORIG_MESA_LOADER_DRIVER_OVERRIDE BSL_GL_ORIG_LIBGL_ALWAYS_SOFTWARE BSL_GL_ORIG_INTEL_FORCE_PROBE
    unset GALLIUM_DRIVER VTEST_SOCKET_NAME MESA_LOADER_DRIVER_OVERRIDE INTEL_FORCE_PROBE
    export LIBGL_ALWAYS_SOFTWARE=1 LIBGL_KOPPER_DISABLE=1
fi
exec /opt/microsoft/msedge/msedge $EDGE_FLAGS "\$@" </dev/null
EDGEEOF
        chmod 755 "/usr/local/bin/$b.new"
        if cmp -s "/usr/local/bin/$b.new" "/usr/local/bin/$b"; then rm -f "/usr/local/bin/$b.new"
        else mv -f "/usr/local/bin/$b.new" "/usr/local/bin/$b"; fi
    done
    # Edges menu entries run /usr/bin/microsoft-edge-stable (its launcher): through the wrapper
    # instead, keeping only Edges own --inprivate.
    for d in /usr/share/applications/microsoft-edge*.desktop /usr/share/applications/com.microsoft.Edge*.desktop; do
        [ -f "$d" ] || continue
        awk '
            /^Exec=/ {
                n = split(substr($0, 6), t, " "); b = t[1]; sub(/.*\//, "", b)
                if (b == "microsoft-edge" || b == "microsoft-edge-stable") {
                    line = "Exec=/usr/local/bin/" b
                    for (i = 2; i <= n; i++) if (t[i] !~ /^--/ || t[i] == "--inprivate") line = line " " t[i]
                    print line; next
                }
            }
            { print }' "$d" > "$d.bsl-new" 2>/dev/null || { rm -f "$d.bsl-new"; continue; }
        if cmp -s "$d.bsl-new" "$d"; then rm -f "$d.bsl-new"; else mv -f "$d.bsl-new" "$d"; fi
    done
    # The --no-sandbox warning bar (the sandbox cannot exist here) and the default browser prompt
    # (Linux links go to Android), through Edges own policy mechanism, as for Chrome.
    if [ ! -e /etc/opt/edge/policies/managed/breadstick.json ]; then
        mkdir -p /etc/opt/edge/policies/managed
        printf '%s\n' '{' '  "CommandLineFlagSecurityWarningsEnabled": false,' '  "DefaultBrowserSettingEnabled": false' '}' \
            > /etc/opt/edge/policies/managed/breadstick.json
        chmod 644 /etc/opt/edge/policies/managed/breadstick.json
    fi
fi
# Chromium, through Debians wrapper, which reads /etc/chromium.d. Here rather than at desktop start
# so that an upgrade reaches a running session: the next launch already has it. The browser draws in
# software even with GPU acceleration on, exactly as the Google Chrome wrapper above explains, and
# /etc/breadstick/browser-gpu (testing only) is the same exception.
mkdir -p /etc/chromium.d
cat > /etc/chromium.d/.00-breadstick.new <<'CHRD'
# Breadstick Linux: proot has no user namespaces (no sandbox). Whether the browser may draw with the
# device GPU, bsl-gpu-allowed decides (breadstick-gl; with an app older than 2.0, only
# /etc/breadstick/browser-gpu, for testing). See /usr/local/lib/breadstick/package-fixups.
# Native mode (x86-64: GALLIUM_DRIVER=zink with BSL_GL_NATIVE) asks the same; on no, zink is dropped.
if { [ "$GALLIUM_DRIVER" = virpipe ] || { [ "$GALLIUM_DRIVER" = zink ] && [ -n "${BSL_GL_NATIVE:-}" ]; }; } && /usr/lib/breadstick/bsl-gpu-allowed chromium 2>/dev/null; then
    export CHROMIUM_FLAGS="$CHROMIUM_FLAGS --no-sandbox --disable-dev-shm-usage"
else
    if [ "$GALLIUM_DRIVER" = zink ] && [ -n "${BSL_GL_NATIVE:-}" ]; then
        BSL_GL_ORIG_GALLIUM_DRIVER=$GALLIUM_DRIVER BSL_GL_ORIG_MESA_LOADER_DRIVER_OVERRIDE=${MESA_LOADER_DRIVER_OVERRIDE:-} BSL_GL_ORIG_LIBGL_ALWAYS_SOFTWARE=${LIBGL_ALWAYS_SOFTWARE:-} BSL_GL_ORIG_LIBGL_KOPPER_DISABLE=${LIBGL_KOPPER_DISABLE:-} BSL_GL_ORIG_INTEL_FORCE_PROBE=${INTEL_FORCE_PROBE:-}
        export BSL_GL_ORIG_GALLIUM_DRIVER BSL_GL_ORIG_MESA_LOADER_DRIVER_OVERRIDE BSL_GL_ORIG_LIBGL_ALWAYS_SOFTWARE BSL_GL_ORIG_LIBGL_KOPPER_DISABLE BSL_GL_ORIG_INTEL_FORCE_PROBE
        unset GALLIUM_DRIVER MESA_LOADER_DRIVER_OVERRIDE INTEL_FORCE_PROBE
        export LIBGL_ALWAYS_SOFTWARE=1 LIBGL_KOPPER_DISABLE=1
    fi
    if [ "$GALLIUM_DRIVER" = virpipe ]; then
        BSL_GL_ORIG_GALLIUM_DRIVER=$GALLIUM_DRIVER BSL_GL_ORIG_VTEST_SOCKET_NAME=${VTEST_SOCKET_NAME:-} BSL_GL_ORIG_LIBGL_ALWAYS_SOFTWARE=${LIBGL_ALWAYS_SOFTWARE:-}
        export BSL_GL_ORIG_GALLIUM_DRIVER BSL_GL_ORIG_VTEST_SOCKET_NAME BSL_GL_ORIG_LIBGL_ALWAYS_SOFTWARE
        unset GALLIUM_DRIVER VTEST_SOCKET_NAME
        export LIBGL_ALWAYS_SOFTWARE=1
    fi
    export CHROMIUM_FLAGS="$CHROMIUM_FLAGS --no-sandbox --disable-gpu --disable-dev-shm-usage"
fi
CHRD
# The new copy has a dot name: Debians launcher sources every other file in that folder, and must
# never read one half written.
if cmp -s /etc/chromium.d/.00-breadstick.new /etc/chromium.d/00-breadstick; then rm -f /etc/chromium.d/.00-breadstick.new
else mv -f /etc/chromium.d/.00-breadstick.new /etc/chromium.d/00-breadstick; fi

# VS Code process budget. Android allows this app 32 child processes and kills the entire guest
# when it goes over, and VS Code is the flagship use case, so it has to fit. argv.json cannot carry
# these: it accepts only a short allowlist of switches. The desktop entries launch the Electron
# binary directly, so flags placed right after it reach Chromium on every Exec line, the New Window
# action included. Verified on a Galaxy Tab S11 Ultra: the flags reach Chromium, no zygote and no
# GPU process appear, and the window renders normally.
#
#   --no-zygote        the zygote is two processes that exist for sandboxing, which is off here.
#                      It refuses to start unless the sandbox is off ON THE COMMAND LINE, and
#                      argv.json applies its setting later, from inside the app, so --no-sandbox
#                      is repeated here rather than trusted to arrive from there.
#   --in-process-gpu   the GPU service runs inside the main process instead of its own.
#   --enable-features=NetworkServiceInProcess2  the network stack runs in the main process.
#                      (Plain NetworkServiceInProcess is a dead name that does nothing.)
#   --disable-crash-reporter  VS Codes own switch, not Chromiums, for its crash handler process.
#
# Here, and not only at desktop start, because a VS Code update rewrites these files and the next
# launch in the same session used to start without the flags: about three extra processes.
# UPGRADES: the guard looks for the NEWEST flag. A file carrying the 1.0.3 set has exactly that set
# stripped first, then the current one inserted, so it never ends up with the flags twice.
VSCODE_FLAGS="--no-sandbox --disable-gpu --no-zygote --in-process-gpu --enable-features=NetworkServiceInProcess2 --disable-crash-reporter"
VSCODE_FLAGS_103="--no-sandbox --disable-gpu --no-zygote --in-process-gpu"
for d in /usr/share/applications/code.desktop /usr/share/applications/code-url-handler.desktop; do
    [ -f "$d" ] || continue
    grep -q -- "--disable-crash-reporter" "$d" && continue
    sed -i "s#^\(Exec=/usr/share/code/code\) $VSCODE_FLAGS_103#\1#" "$d" 2>/dev/null || true
    sed -i "s#^\(Exec=/usr/share/code/code\)\( \|\$\)#\1 $VSCODE_FLAGS\2#" "$d" 2>/dev/null || true
done
# ChatGPT, OpenAIs desktop app (their .deb, arm64 and x86-64): Electron, whose launcher passes it
# nothing, so under proot its zygote could not start and it closed at once ("zygote_linux.cc: write:
# Broken pipe", 2026-10-13; ELECTRON_DISABLE_SANDBOX does not stop the zygote, as with VS Code). Its
# menu entry runs the bare command chatgpt, so a wrapper of that name earlier in PATH covers the
# menu, the Apps drawer and a terminal alike, without touching OpenAIs files: the VS Code flags,
# software rendering whatever the GPU setting (not measured on any GPU), and the basic password
# store (no keyring daemon here). It is removed again when ChatGPT is.
CGMARK="Breadstick Linux: ChatGPT"
if [ -x /usr/lib/chatgpt/ChatGPT ]; then
    if [ ! -e /usr/local/bin/chatgpt ] || grep -q "$CGMARK" /usr/local/bin/chatgpt 2>/dev/null; then
        mkdir -p /usr/local/bin
        cat > /usr/local/bin/chatgpt.new <<CGEOF
#!/bin/sh
# $CGMARK, with the flags it needs under proot. See /usr/local/lib/breadstick/package-fixups.
if [ -n "\${GALLIUM_DRIVER:-}" ]; then
    unset GALLIUM_DRIVER VTEST_SOCKET_NAME MESA_LOADER_DRIVER_OVERRIDE INTEL_FORCE_PROBE
    export LIBGL_ALWAYS_SOFTWARE=1 LIBGL_KOPPER_DISABLE=1
fi
exec /usr/lib/chatgpt/ChatGPT $VSCODE_FLAGS --password-store=basic "\$@"
CGEOF
        chmod 755 /usr/local/bin/chatgpt.new
        if cmp -s /usr/local/bin/chatgpt.new /usr/local/bin/chatgpt; then rm -f /usr/local/bin/chatgpt.new
        else mv -f /usr/local/bin/chatgpt.new /usr/local/bin/chatgpt; fi
    fi
elif grep -q "$CGMARK" /usr/local/bin/chatgpt 2>/dev/null; then
    rm -f /usr/local/bin/chatgpt
fi
# The desktop keyring (gnome-keyring, a dependency since 1.0.32). Apps that keep secrets through the
# Secret Service (Element, Signal and other Electron apps, git's libsecret helper) otherwise ask
# about "weaker encryption" or for a new keyring password. Nothing unlocks a keyring here (there is
# no login), so each user gets one with no password, which gnome-keyring keeps unlocked and stores
# as a plain file: the protection those apps fall back to anyway, inside the app's private storage
# on an encrypted device, without the question. Here rather than in the session so it is in place
# the moment apt installs gnome-keyring, before any app can ask. Only for a user with no keyring at
# all, so one made or protected later is never touched. The daemon starts through D-Bus when an app
# first asks (its autostart entries are GNOME-only), so it costs a process only then.
if [ -x /usr/bin/gnome-keyring-daemon ]; then
    getent passwd | while IFS=: read -r kname _ kuid _ _ khome _; do
        { [ "$kuid" = 0 ] || { [ "$kuid" -ge 1000 ] && [ "$kuid" -lt 60000 ]; }; } 2>/dev/null || continue
        [ -d "$khome" ] && [ "$khome" != / ] || continue
        kr="$khome/.local/share/keyrings"
        [ -e "$kr/default" ] && continue
        ls "$kr"/*.keyring >/dev/null 2>&1 && continue
        for d in "$khome/.local" "$khome/.local/share" "$kr"; do
            [ -d "$d" ] || { mkdir "$d" && chown "$kuid" "$d"; } || continue 2
        done
        chmod 700 "$kr"
        printf '[keyring]\ndisplay-name=Login\nctime=0\nmtime=0\nlock-on-idle=false\nlock-after=false\n' > "$kr/login.keyring" \
            && printf 'login' > "$kr/default" && chmod 600 "$kr/login.keyring" "$kr/default" \
            && chown "$kuid" "$kr/login.keyring" "$kr/default" || true
    done
fi
# Any other Chromium or Electron app installed from a .deb (Slack, Discord, Obsidian, Element, Signal
# and the rest): the same need as Edge and ChatGPT above, found by bsl-chromium-apps, which explains
# it. /etc/breadstick/no-chromium-fixups turns it off.
[ -x /usr/lib/breadstick/bsl-chromium-apps ] && [ -x /usr/bin/python3 ] && \
    /usr/bin/python3 -I /usr/lib/breadstick/bsl-chromium-apps >/dev/null 2>&1 || true
# The systemd-sysusers shim provision.sh installs. Version 1 looked for the file a package names
# (openssh-server.conf) in the current directory instead of the sysusers.d folders, so no user was
# created for a package set up after the image was made: no sshd user, and the SSH server never
# started. Version 2 replaces version 1, never anything else, and when it does it runs once, which
# creates the users and groups those packages declared.
V1='[ "$#" -gt 0 ] || set -- /usr/lib/sysusers.d/*.conf /etc/sysusers.d/*.conf'
if grep -qxF "$V1" /usr/local/bin/systemd-sysusers 2>/dev/null; then
    cat > /usr/local/bin/systemd-sysusers.new <<'SYSU'
#!/bin/sh
# Breadstick Linux: systemd-sysusers under proot (sysusers shim version 2). Creates the users and
# groups that sysusers.d files declare. Packages name their file bare (openssh-server.conf), found
# in the sysusers.d folders as the real tool finds it; version 1 looked in the current directory,
# created nothing, and so the SSH server never started (no sshd user).
apply() {
    while read -r t n rest; do
        case "$t" in
            u|u!) getent passwd "$n" >/dev/null 2>&1 || useradd -r -M -s /usr/sbin/nologin "$n" 2>/dev/null || true ;;
            g|g!) getent group  "$n" >/dev/null 2>&1 || groupadd -r "$n" 2>/dev/null || true ;;
        esac
    done
}
named=
for a in "$@"; do
    case "$a" in
        -) named=1; apply ;;
        -*) ;;
        */*) named=1; [ -f "$a" ] && apply < "$a" ;;
        *) named=1
           for d in /etc/sysusers.d /run/sysusers.d /usr/local/lib/sysusers.d /usr/lib/sysusers.d; do
               if [ -f "$d/$a" ]; then apply < "$d/$a"; break; fi
           done ;;
    esac
done
if [ -z "$named" ]; then
    for f in /usr/lib/sysusers.d/*.conf /etc/sysusers.d/*.conf; do [ -f "$f" ] && apply < "$f"; done
fi
exit 0
SYSU
    chmod 755 /usr/local/bin/systemd-sysusers.new
    mv -f /usr/local/bin/systemd-sysusers.new /usr/local/bin/systemd-sysusers
    if grep -qxF "$V1" /usr/bin/systemd-sysusers 2>/dev/null; then
        cp -f /usr/local/bin/systemd-sysusers /usr/bin/systemd-sysusers
    fi
    /usr/local/bin/systemd-sysusers
fi
# Mesa's Vulkan path (kopper, zink) never works here, and with no Vulkan driver installed Mesa 25.0
# deadlocks in the first GLX call of any program that tries it: the X server offers DRI3 without an
# open, so Mesa does try. The session and the app set LIBGL_KOPPER_DISABLE for what they start;
# /etc/environment covers logins through pam_env (su -, login). Debian 13's sudo loads no pam_env, so
# sudo keeps it through sudoers.d/breadstick-env instead. SSH logins get neither (the app runs sshd
# with UsePAM no); an /etc/profile.d export would reach them.
if [ -f /etc/environment ] && ! grep -q '^LIBGL_KOPPER_DISABLE=' /etc/environment; then
    # A last line without its newline would run into ours and break both.
    if [ -s /etc/environment ] && [ -n "$(tail -c 1 /etc/environment)" ]; then
        echo >> /etc/environment
    fi
    printf '%s\n' '# Breadstick Linux: Mesa must not try its Vulkan path here (see start-desktop-x11.sh).' \
        'LIBGL_KOPPER_DISABLE=1' >> /etc/environment
fi
# The session bus, open to every proot of this app. Programs the app starts itself (the Apps
# drawer, every Floating window, Terminal tabs, SSH) each run in a proot of their own, and proot
# vouches for the user of a socket peer only when that peer is traced by the SAME proot: it looks
# the pid up among its own tracees and otherwise hands back the real Android uid. The stock session
# bus therefore refused every one of them. GDBus programs (XFCE, GTK) got "The connection is closed"
# and libdbus ones (Chrome, Python) "NoReply", so xfce4-appfinder quit at once, xfconf settings were
# never saved, and the fix people find for that, dbus-run-session, starts a private bus for each
# program with its own copy of every service it uses: three or more extra processes each against
# the process limit of Android (a Galaxy Tab S9, SM-X716N, went to 39 and its desktop was killed,
# 2026-09-28).
# This configuration lets them in. Any user id may connect (GDBus authenticates as whatever uid the
# socket reports), and ANONYMOUS is offered for libdbus, which claims uid 1000 and is turned away
# when the socket says otherwise. It opens nothing to anyone else: the socket lives in the private
# storage of the app, which no other Android app can reach. The session script starts its bus with
# this file and falls back to the stock configuration without it.
#
# The uid the socket reports is the real one of the app (10361 on a Pixel), and dbus-daemon looks up
# every user that connects and drops one it cannot find, whatever its policy allows: GDBus programs
# from another proot were still cut off until that uid had a name (measured on the Pixel, 2026-09-28).
# So it gets one, bsl-app, locked and without a login. /proc/self/status gives the real uid, which proot
# leaves alone. A system restored into another install runs as another uid: its old entry is replaced.
# BSL_APP_UID overrides the uid (for the gate, where nothing runs under proot).
APPUID=${BSL_APP_UID:-$(awk '/^Uid:/ {print $2; exit}' /proc/self/status 2>/dev/null)}
APPMARK="Breadstick Linux, the Android app itself"
case "$APPUID" in
    ""|*[!0-9]*) ;;
    *)
        if [ "$APPUID" -ge 10000 ] && ! getent passwd "$APPUID" >/dev/null 2>&1; then
            if getent passwd bsl-app | grep -q ":$APPMARK:"; then
                userdel -f bsl-app >/dev/null 2>&1 || true
            fi
            getent passwd bsl-app >/dev/null 2>&1 || useradd -r -M -N -g nogroup -u "$APPUID" \
                -d /nonexistent -s /usr/sbin/nologin -c "$APPMARK" bsl-app >/dev/null 2>&1 || true
        fi ;;
esac
BUSCONF=/usr/local/lib/breadstick/session-bus.conf
cat > "$BUSCONF.new" <<'BUSXML'
<!DOCTYPE busconfig PUBLIC "-//freedesktop//DTD D-Bus Bus Configuration 1.0//EN"
 "http://www.freedesktop.org/standards/dbus/1.0/busconfig.dtd">
<!-- Breadstick Linux: the session bus of the desktop, open to every proot of the app.
     Written by /usr/local/lib/breadstick/package-fixups, which explains it. -->
<busconfig>
  <include>/usr/share/dbus-1/session.conf</include>
  <auth>ANONYMOUS</auth>
  <allow_anonymous/>
  <policy context="default">
    <allow user="*"/>
  </policy>
</busconfig>
BUSXML
if cmp -s "$BUSCONF.new" "$BUSCONF"; then rm -f "$BUSCONF.new"; else mv -f "$BUSCONF.new" "$BUSCONF"; fi
# dbus-run-session, for the programs people wrapped in it while the session bus turned them away.
# Each wrapped program gets a bus of its own (dbus-run-session and a dbus-daemon) plus a second copy
# of every service it activates there (xfconfd, dconf-service, Thunar for Show in folder): with the
# three the Tab S9 had open, nine processes more than the same programs on the session bus (34
# against 25 on a Pixel, the limit being 32 for the whole device). While the session bus
# answers, a program started this way from the desktop, the Apps drawer or a launcher joins that bus
# instead, where those services already run. Run from a terminal, with any option, with
# BREADSTICK_PRIVATE_BUS=1 in the environment, or with /etc/breadstick/no-shared-bus present, it is
# the real dbus-run-session: a test suite run from a terminal keeps the private bus it asked for.
# Only a file carrying its marker is ever replaced or removed.
# Firefox installed with apt by hand, not from Recommended apps, has no Breadstick entry: its own
# menu entry starts it without the variables its sandboxes need under proot (no video with sound
# plays, see install-browser.sh), and an entry written before apt 1.0.28 ran whichever browser was
# installed last. install-browser.sh --refresh-one writes Firefox's wrapper and entry, without apt.
FFENTRY=/usr/local/share/applications/firefox-esr.desktop
FFWRAP=/usr/local/bin/breadstick-browser-firefox
if [ -x /usr/lib/firefox-esr/firefox-esr ] && [ -r /usr/lib/breadstick/install-browser.sh ]; then
    if ! grep -q MOZ_DISABLE_UTILITY_SANDBOX "$FFWRAP" 2>/dev/null || \
            ! grep -q "^Exec=$FFWRAP" "$FFENTRY" 2>/dev/null; then
        sh /usr/lib/breadstick/install-browser.sh --refresh-one firefox >/dev/null 2>&1 || true
    fi
elif [ ! -e /usr/lib/firefox-esr/firefox-esr ] && grep -q "^Exec=$FFWRAP" "$FFENTRY" 2>/dev/null; then
    rm -f "$FFENTRY" "$FFWRAP" # Firefox removed: no icon left that starts nothing
fi
DRS=/usr/local/bin/dbus-run-session
DRSMARK="Breadstick Linux: dbus-run-session"
if [ ! -e "$DRS" ] || grep -q "$DRSMARK" "$DRS" 2>/dev/null; then
    if [ -x /usr/bin/dbus-run-session ]; then
        cat > "$DRS.new" <<'DRSEOF'
#!/bin/sh
# Breadstick Linux: dbus-run-session, joining the session bus while one answers.
# See /usr/local/lib/breadstick/package-fixups for why.
REAL=/usr/bin/dbus-run-session
case "${1:-}" in
    --) shift ;;
    -*|"") exec "$REAL" "$@" ;;
esac
[ "$#" -gt 0 ] || exec "$REAL"
if [ -z "${BREADSTICK_PRIVATE_BUS:-}" ] && [ ! -e /etc/breadstick/no-shared-bus ] && \
        [ -n "${DBUS_SESSION_BUS_ADDRESS:-}" ] && ! [ -t 0 ] && ! [ -t 1 ] && ! [ -t 2 ] && \
        dbus-send --session --print-reply --reply-timeout=3000 --dest=org.freedesktop.DBus \
            /org/freedesktop/DBus org.freedesktop.DBus.GetId >/dev/null 2>&1; then
    exec "$@"
fi
exec "$REAL" -- "$@"
DRSEOF
        chmod 755 "$DRS.new"
        if cmp -s "$DRS.new" "$DRS"; then rm -f "$DRS.new"; else mv -f "$DRS.new" "$DRS"; fi
    else
        rm -f "$DRS"
    fi
fi
exit 0
FIX
chmod 755 /usr/local/lib/openstick/package-fixups
ln -sf /usr/local/lib/openstick/package-fixups /usr/local/lib/breadstick/package-fixups 2>/dev/null || true
mkdir -p /etc/apt/apt.conf.d
cat > /etc/apt/apt.conf.d/90openstick-fixups <<'HOOK'
// OpenStick Linux: see /usr/local/lib/openstick/package-fixups.
DPkg::Post-Invoke { "[ -x /usr/local/lib/openstick/package-fixups ] && /usr/local/lib/openstick/package-fixups || true"; };
HOOK
/usr/local/lib/openstick/package-fixups || true
}

# `--fixups`: write the package fixups, run them, and stop. The package's postinst calls this so
# the LibreOffice fix is in place the moment the package is upgraded, rather than at the next
# desktop start. Nothing above this line has side effects, and nothing below it runs.
if [ "${1:-}" = --fixups ]; then
    bsl_write_package_fixups
    exit 0
fi

# OpenStick Linux identity (idempotent - also rebrands installs made before the fork).
# The version fields are DERIVED, never hardcoded.
if ! grep -q '^ID=openstick' /etc/os-release 2>/dev/null; then
    DEBVER="$(cut -d. -f1 /etc/debian_version 2>/dev/null | tr -dc 0-9)"
    [ -n "$DEBVER" ] || DEBVER=12
    case "$DEBVER" in
        11) DEBCODE=bullseye ;;
        12) DEBCODE=bookworm ;;
        13) DEBCODE=trixie ;;
        14) DEBCODE=forky ;;
        *)  DEBCODE=unknown ;;
    esac
    cat > /etc/os-release <<OSEOF
PRETTY_NAME="OpenStick Linux"
NAME="OpenStick Linux"
VERSION="$DEBVER ($DEBCODE)"
VERSION_ID="$DEBVER"
VERSION_CODENAME=$DEBCODE
ID=openstick
ID_LIKE=debian
HOME_URL="https://openstick.org"
SUPPORT_URL="https://openstick.org"
OSEOF
    cp /etc/os-release /usr/lib/os-release 2>/dev/null || true
    printf 'Welcome to OpenStick Linux.\n' > /etc/motd 2>/dev/null || true
    printf 'OpenStick Linux \\n \\l\n\n' > /etc/issue 2>/dev/null || true
fi

# System D-Bus (some GTK apps expect it). With no systemd, start it ourselves; a stale
# socket from a dead daemon causes "Connection refused", so probe then restart if needed.
mkdir -p /run/dbus /var/lib/dbus
dbus-uuidgen --ensure 2>/dev/null || true
if ! dbus-send --system --print-reply --reply-timeout=2000 --dest=org.freedesktop.DBus \
        /org/freedesktop/DBus org.freedesktop.DBus.ListNames >/dev/null 2>&1; then
    rm -f /run/dbus/pid /run/dbus/system_bus_socket 2>/dev/null || true
    dbus-daemon --system --fork 2>/dev/null || true
fi

# An upgrade that was cut off -- the session killed by Androids process limit mid-install, the app
# force-stopped -- leaves dpkg half-configured, and from then on every apt run refuses to start
# ("dpkg was interrupted, you must manually run dpkg --configure -a"), including the daily update
# the app runs, which hides the error. Finish the job here, before anything below needs apt.
if [ -n "$(ls -A /var/lib/dpkg/updates 2>/dev/null)" ]; then
    echo "[BSL] finishing an interrupted package install..."
    dpkg --configure -a >/dev/null 2>&1 && echo "[BSL] packages: repaired" || echo "[BSL] packages: repair failed"
fi

# Ensure the WM, a couple of apps, fonts, audio and the software-GL driver. All small and
# DE-free; safe to run every start (no-ops once present).
[ -d /usr/share/X11/xkb ] || apt-get install -y --no-install-recommends xkb-data >/dev/null 2>&1 || true
command -v dbus-launch >/dev/null 2>&1 || apt-get install -y --no-install-recommends dbus-x11 >/dev/null 2>&1 || true
# XFCE is the desktop (whole-screen mode). Only fetch it when the session manager is absent,
# and only in whole-screen mode — rootless/floating never runs a guest desktop, so a Floating
# user should not pay a ~200 MB download for one. Existing installs pick it up on first
# Desktop start; new ones get it from breadstick-desktop at provisioning time.
if [ "$R" != "1" ] && ! command -v xfce4-session >/dev/null 2>&1; then
    echo "[BSL] installing XFCE (first desktop start — this takes a few minutes)..."
    # This is a ~200 MB download on a phone, so it MUST report progress — a silent multi-minute
    # wait on a blank screen reads as a hang. APT::Status-Fd makes apt emit machine-readable
    # progress ("dlstatus:1:37.5:Retrieving file 30 of 78"); tag those lines so the app can
    # parse them into a progress bar, and drop apt's ordinary chatter.
    echo "[BSL-PROGRESS] dlstatus:0:0:Preparing to download XFCE"
    apt-get install -y --no-install-recommends -o APT::Status-Fd=1 \
        xfce4-session xfwm4 xfce4-panel xfdesktop4 xfce4-settings \
        xfce4-terminal xfce4-appfinder thunar 2>/dev/null \
        | while IFS= read -r l; do
            case "$l" in
                dlstatus:*|pmstatus:*) echo "[BSL-PROGRESS] $l" ;;
            esac
          done
    echo "[BSL-PROGRESS] done"
    echo "[BSL] XFCE install finished: $(command -v xfce4-session >/dev/null 2>&1 && echo ok || echo FAILED)"
fi
command -v xterm >/dev/null 2>&1 || apt-get install -y --no-install-recommends xterm fonts-dejavu-core >/dev/null 2>&1 || true
command -v wmctrl >/dev/null 2>&1 || apt-get install -y --no-install-recommends wmctrl >/dev/null 2>&1 || true
command -v xdotool >/dev/null 2>&1 || apt-get install -y --no-install-recommends xdotool >/dev/null 2>&1 || true
# One test per match: a single [ -d ] over the glob errors out when it matches two directories
# (an armhf multiarch install adds a second), which read as "missing" and reinstalled Mesa at
# every start.
BSL_DRI=0
for d in /usr/lib/*/dri /usr/lib/dri; do [ -d "$d" ] && BSL_DRI=1; done
[ "$BSL_DRI" = 1 ] || \
    apt-get install -y --no-install-recommends libgl1-mesa-dri libglx-mesa0 >/dev/null 2>&1 || true
command -v pulseaudio >/dev/null 2>&1 || apt-get install -y --no-install-recommends pulseaudio pulseaudio-utils >/dev/null 2>&1 || true
echo "[BSL] xfce4-session: $(command -v xfce4-session >/dev/null 2>&1 && echo present || echo MISSING); openbox: $(command -v openbox >/dev/null 2>&1 && echo present || echo MISSING); xterm: $(command -v xterm >/dev/null 2>&1 && echo present || echo MISSING)"

# ICE (session management) dir — harmless if unused.
mkdir -p /tmp/.ICE-unix && chmod 1777 /tmp/.ICE-unix 2>/dev/null || true

# Chromium/Electron under proot: the sandbox needs user namespaces / a setuid helper,
# neither of which unprivileged proot can provide, and the GPU process has no real device —
# so chromium, VS Code and other Electron apps abort at sandbox init (or render blank)
# unless told otherwise. Cover each knob the ecosystem reads; all idempotent.
# Chromium (/etc/chromium.d), Google Chrome and the VS Code launch flags are handled in package-fixups
# (above), which apt also runs after every package change, so an update mid-session no longer drops
# them. It runs further down at every start too.
# VS Code reads ~/.vscode/argv.json (JSONC). Seed it for the session user if absent.
VHOME="/root"; [ "$U" = "root" ] || VHOME="/home/$U"
if [ -d "$VHOME" ] && [ ! -f "$VHOME/.vscode/argv.json" ]; then
    mkdir -p "$VHOME/.vscode"
    cat > "$VHOME/.vscode/argv.json" <<'VSEOF'
{
    // Breadstick Linux: proot cannot provide the Chromium sandbox or GPU access.
    "disable-chromium-sandbox": true,
    "disable-hardware-acceleration": true,
    // There is no desktop keyring here; keep sign-ins in VS Code's own store.
    "password-store": "basic"
}
VSEOF
    [ "$U" = "root" ] || chown -R "$U" "$VHOME/.vscode" 2>/dev/null || true
    echo "[BSL] seeded $VHOME/.vscode/argv.json (no sandbox, no GPU accel, basic password store)"
fi
# VS Code keeps sign-in tokens (GitHub, Copilot, extension accounts) in the desktop keyring. Until
# 1.0.32 there was none, and VS Code warned that the OS keyring was not available and could keep
# nobody signed in. "basic" is VS Code's own documented store for this case, and the same choice
# Chrome makes here (--password-store=basic). It stays basic now that there is a keyring (below):
# tokens written to the keyring are unreadable whenever it cannot be reached, which would sign
# people out, and everyone's existing tokens are in the basic store.
#
# Existing argv.json files get it added, but never over a password-store someone set themselves, and
# only through a parser that proves the result still reads: a VS Code that cannot parse argv.json
# silently drops disable-chromium-sandbox and then will not start under proot at all.
if [ -f "$VHOME/.vscode/argv.json" ] && ! grep -q '"password-store"' "$VHOME/.vscode/argv.json" 2>/dev/null \
        && command -v python3 >/dev/null 2>&1; then
    if python3 - "$VHOME/.vscode/argv.json" <<'PYEOF'
import json, re, sys

path = sys.argv[1]
text = open(path, encoding="utf-8").read()

def strip_comments(s):
    out, i, n, in_str = [], 0, len(s), False
    while i < n:
        c = s[i]
        if in_str:
            out.append(c)
            if c == "\\" and i + 1 < n:
                out.append(s[i + 1])
                i += 2
                continue
            if c == '"':
                in_str = False
            i += 1
            continue
        if c == '"':
            in_str = True
        elif s.startswith("//", i):
            j = s.find("\n", i)
            i = n if j < 0 else j
            continue
        elif s.startswith("/*", i):
            j = s.find("*/", i + 2)
            i = n if j < 0 else j + 2
            continue
        out.append(c)
        i += 1
    return "".join(out)

def load(s):
    return json.loads(strip_comments(s))

try:
    current = load(text)
except ValueError:
    sys.exit(1)  # not valid to begin with: not ours to repair, leave it exactly as it is
if not isinstance(current, dict) or "password-store" in current:
    sys.exit(1)

brace = re.search(r"^[ \t]*\{[ \t]*\n", text, re.M)  # the object's opening brace, on its own line
if not brace:
    sys.exit(1)
entry = '    "password-store": "basic"' + ("," if current else "") + "\n"
updated = text[:brace.end()] + entry + text[brace.end():]

try:
    ok = load(updated) == dict(current, **{"password-store": "basic"})
except ValueError:
    ok = False
if not ok:
    sys.exit(1)
with open(path, "w", encoding="utf-8") as f:  # in place, so the owner and mode stay the user's
    f.write(updated)
PYEOF
    then
        echo "[BSL] VS Code: added password-store basic to argv.json"
    fi
fi
# VS Code's extension signature check runs a bundled native helper (@vscode/vsce-sign) that
# fails under proot ("signature verification UnknownError"), blocking extension installs. Turn
# it off in the user settings so extensions install cleanly. Seeded only if settings.json absent.
VSCFG="$VHOME/.config/Code/User"
if [ -d "$VHOME" ] && [ ! -f "$VSCFG/settings.json" ]; then
    mkdir -p "$VSCFG"
    cat > "$VSCFG/settings.json" <<'VSSET'
{
    "extensions.verifySignature": false
}
VSSET
    [ "$U" = "root" ] || chown -R "$U" "$VHOME/.config/Code" 2>/dev/null || true
    echo "[BSL] seeded VS Code settings.json (extension signature verification off)"
fi
# Firefox (Debian firefox-esr reads /etc/firefox-esr/*.js as system default prefs): turn the
# content sandbox off at the pref level as well, so it holds however Firefox is launched.
mkdir -p /etc/firefox-esr
cat > /etc/firefox-esr/breadstick.js <<'FFEOF'
// Breadstick Linux: proot cannot provide the content-process sandbox.
pref("security.sandbox.content.level", 0);
FFEOF

# Make apps open MAXIMIZED so each one fills the display (more app-like on a phone). We
# extend openbox's stock config (safe) rather than ship a hand-written one.
OBDIR="/root/.config/openbox"
[ "$U" = "root" ] || OBDIR="/home/$U/.config/openbox"
if [ ! -f "$OBDIR/rc.xml" ] && [ -f /etc/xdg/openbox/rc.xml ]; then
    mkdir -p "$OBDIR"
    cp /etc/xdg/openbox/rc.xml "$OBDIR/rc.xml"
    sed -i 's#</applications>#  <application class="*"><maximized>yes</maximized></application>\n</applications>#' "$OBDIR/rc.xml"
    [ "$U" = "root" ] || chown -R "$U" "/home/$U/.config" 2>/dev/null || true
    echo "[BSL] openbox: apps will open maximized"
fi

# XFCE first-run: xfce4-panel asks "default config or one empty panel?" in a MODAL dialog and
# waits. With no way to answer it before the desktop is up, the session would just sit there
# looking hung. Pre-answering it = copying the stock panel config into place. Same trick for
# xfce4-desktop so the backdrop is set rather than plain black. Both only when absent, so a
# user who rearranges their panel keeps it across restarts.
UHOME="/root"
[ "$U" = "root" ] || UHOME="/home/$U"
XFDIR="$UHOME/.config/xfce4/xfconf/xfce-perchannel-xml"

# The Breadstick look (ChromeOS-style shelf, frames, wallpaper, palette) ships in breadstick-theme
# and seeds ALL of the xfconf channels below, so when it runs the stock seeding underneath is not
# just redundant, it would fight it -- the legacy block rewrites the GTK theme name to Adwaita.
# Falls through to the stock seeding when the theme package is not installed (older installs, or a
# guest built before it existed), which is why it is a fallback rather than a replacement.
LOOK_APPLIED=0
if [ -r /usr/lib/breadstick/apply-look.sh ] && command -v xfce4-session >/dev/null 2>&1; then
    if HOME="$UHOME" BREADSTICK_DARK="$BREADSTICK_DARK" BREADSTICK_DPI="$BREADSTICK_DPI" \
        sh /usr/lib/breadstick/apply-look.sh; then
        LOOK_APPLIED=1
    fi
    [ "$U" = "root" ] || chown -R "$U" "$UHOME/.config" 2>/dev/null || true
fi

# OpenStick: optimize touch/mouse double-click (650ms, 36px) and window placement/drag
if [ -d "$XFDIR" ]; then
    if [ -f "$XFDIR/xsettings.xml" ]; then
        sed -i 's#<property name="DoubleClickTime" type="int" value=".*"/>#<property name="DoubleClickTime" type="int" value="650"/>#' "$XFDIR/xsettings.xml" 2>/dev/null || true
        grep -q 'name="DoubleClickDistance"' "$XFDIR/xsettings.xml" || sed -i '/name="DoubleClickTime"/a \    <property name="DoubleClickDistance" type="int" value="36"/>' "$XFDIR/xsettings.xml" 2>/dev/null || true
    fi
    if [ -f "$XFDIR/xfwm4.xml" ]; then
        sed -i 's#<property name="placement_mode" type="string" value=".*"/>#<property name="placement_mode" type="string" value="center"/>#' "$XFDIR/xfwm4.xml" 2>/dev/null || true
        grep -q 'name="placement_mode"' "$XFDIR/xfwm4.xml" || sed -i '/name="general"/a \    <property name="placement_mode" type="string" value="center"/>' "$XFDIR/xfwm4.xml" 2>/dev/null || true
        grep -q 'name="easy_click"' "$XFDIR/xfwm4.xml" || sed -i '/name="general"/a \    <property name="easy_click" type="string" value="Alt"/>' "$XFDIR/xfwm4.xml" 2>/dev/null || true
        grep -q 'name="double_click_time"' "$XFDIR/xfwm4.xml" || sed -i '/name="general"/a \    <property name="double_click_time" type="int" value="650"/>' "$XFDIR/xfwm4.xml" 2>/dev/null || true
        grep -q 'name="double_click_distance"' "$XFDIR/xfwm4.xml" || sed -i '/name="general"/a \    <property name="double_click_distance" type="int" value="36"/>' "$XFDIR/xfwm4.xml" 2>/dev/null || true
        grep -q 'name="titleless_maximize"' "$XFDIR/xfwm4.xml" && sed -i 's#<property name="titleless_maximize" type="bool" value=".*"/>#<property name="titleless_maximize" type="bool" value="false"/>#' "$XFDIR/xfwm4.xml" 2>/dev/null || true
        grep -q 'name="borderless_maximize"' "$XFDIR/xfwm4.xml" && sed -i 's#<property name="borderless_maximize" type="bool" value=".*"/>#<property name="borderless_maximize" type="bool" value="false"/>#' "$XFDIR/xfwm4.xml" 2>/dev/null || true
    fi
    if [ -f "$XFDIR/thunar.xml" ]; then
        sed -i 's#<property name="misc-single-click" type="bool" value=".*"/>#<property name="misc-single-click" type="bool" value="true"/>#' "$XFDIR/thunar.xml" 2>/dev/null || true
    fi
fi

# Removable storage: SD cards and USB drives. The app binds each mounted volume at /mnt/<name>
# (sd-card for a card Android describes as "SD card"). Nothing pointed at those, and the second
# bind the app makes into the home folder under the volumes own label is what file managers listed
# instead -- as a broken entry whenever the label has a space in it, because the mount table proot
# writes for Linux programs cannot carry the space ("/home/you/SD card" arrives as "/home/you/SD").
# So each volume gets a bookmark (Thunar side pane and every GTK file dialog, in both modes) and,
# on the desktop, a link like Shared and Android have. BREADSTICK_VOLUMES, when the app passes it,
# names them ("sd-card=SD card;usb-1=Backup"); otherwise the name is made from the mount name.
bsl_vol_label() {
    l=""
    case ";${BREADSTICK_VOLUMES:-};" in
        *";$1="*) l=${BREADSTICK_VOLUMES#*"$1="}; l=${l%%;*} ;;
    esac
    if [ -z "$l" ]; then
        case "$1" in
            sd-card*) l="SD card" ;;
            usb*)     l="USB drive" ;;
            *)        l=$(printf '%s' "$1" | tr '-' ' ') ;;
        esac
    fi
    printf '%s' "$l" | tr -d '/\n'
}
# Mounted volumes only: a bind from Android storage sits on a different device than the rootfs,
# which is what tells it apart from an ordinary folder someone made under /mnt.
BSL_ROOTDEV=$(stat -c %d / 2>/dev/null)
BSL_VOLS=""
for m in /mnt/*; do
    [ -d "$m" ] || continue
    slug=${m#/mnt/}
    case "$slug" in host|android|*[!a-z0-9-]*) continue ;; esac
    [ "$(stat -c %d "$m" 2>/dev/null)" != "$BSL_ROOTDEV" ] || continue
    [ -n "$(ls -A "$m" 2>/dev/null)" ] || continue
    BSL_VOLS="$BSL_VOLS $slug"
done
BSL_HOME="/root"; [ "$U" = "root" ] || BSL_HOME="/home/$U"
BM="$BSL_HOME/.config/gtk-3.0/bookmarks"
VOLREC="$BSL_HOME/.config/breadstick/volume-bookmarks"
mkdir -p "$BSL_HOME/.config/gtk-3.0" "$BSL_HOME/.config/breadstick" 2>/dev/null
touch "$VOLREC" 2>/dev/null
for slug in $BSL_VOLS; do
    grep -q "^file:///mnt/$slug\( \|\$\)" "$BM" 2>/dev/null && continue
    printf 'file:///mnt/%s %s\n' "$slug" "$(bsl_vol_label "$slug")" >> "$BM"
    grep -qx "$slug" "$VOLREC" 2>/dev/null || echo "$slug" >> "$VOLREC"
    echo "[BSL] storage: bookmarked /mnt/$slug"
done
# Take back only the bookmarks this script added, and only once their volume is gone.
while IFS= read -r slug; do
    [ -n "$slug" ] || continue
    case " $BSL_VOLS " in *" $slug "*) continue ;; esac
    if [ -f "$BM" ]; then
        grep -v "^file:///mnt/$slug\( \|\$\)" "$BM" > "$BM.new" 2>/dev/null
        mv -f "$BM.new" "$BM"
    fi
    grep -vx "$slug" "$VOLREC" > "$VOLREC.new" 2>/dev/null; mv -f "$VOLREC.new" "$VOLREC"
done < "$VOLREC"
[ "$U" = "root" ] || chown "$U" "$BM" "$VOLREC" "$BSL_HOME/.config/gtk-3.0" "$BSL_HOME/.config/breadstick" 2>/dev/null || true

# Desktop links to the device's storage, on EVERY session rather than only the fallback.
#
# This lived inside the block below, which runs only when applying the Breadstick look
# FAILED. Once the look started working the links stopped being created, so a fresh install
# had no Android folder on its desktop and the only systems that did were ones set up before
# the theme existed.
if command -v xfce4-session >/dev/null 2>&1; then
# Put the Android storage bridges on the desktop. The app bind-mounts its shared folder at
# /mnt/host (always) and, when the user has granted all-files access, the whole of Android
# storage at /mnt/android — but nothing ever pointed at them, so the files were reachable
# only by typing the path into a terminal. ~/Desktop is the one place xfdesktop still draws
# icons now that the (unresolvable, gvfs-less) volume icons are off, so link them there.
#
# Only link what is really mounted: /mnt/android exists as an empty mountpoint even when the
# permission was refused, and an icon onto an empty directory is worse than no icon at all.
# Seeded once, recorded by a stamp, so a user who deletes an icon does not get it back on
# every launch.
DESKDIR="/root/Desktop"
[ "$U" = "root" ] || DESKDIR="/home/$U/Desktop"
mkdir -p "$DESKDIR"
for m in host android; do
    case "$m" in
        host)    link="$DESKDIR/Shared" ;;
        android) link="$DESKDIR/Android" ;;
    esac
    stamp="$XFDIR/.bsl-linked-$m"
    [ -f "$stamp" ] && continue
    [ -d "/mnt/$m" ] || continue
    [ -n "$(ls -A "/mnt/$m" 2>/dev/null)" ] || continue
    ln -sfn "/mnt/$m" "$link" 2>/dev/null || continue
    : > "$stamp"
    echo "[BSL] xfce: desktop link -> /mnt/$m"
done
# Same for each SD card or USB drive, once per volume, so a deleted icon stays deleted.
for slug in $BSL_VOLS; do
    stamp="$XFDIR/.bsl-linked-vol-$slug"
    [ -f "$stamp" ] && continue
    link="$DESKDIR/$(bsl_vol_label "$slug")"
    [ -e "$link" ] || [ -L "$link" ] || ln -s "/mnt/$slug" "$link" 2>/dev/null || continue
    mkdir -p "$XFDIR" 2>/dev/null; : > "$stamp"
    echo "[BSL] xfce: desktop link -> /mnt/$slug"
done
[ "$U" = "root" ] || chown -R "$U" "$DESKDIR" 2>/dev/null || true
fi

if [ "$LOOK_APPLIED" = 0 ] && command -v xfce4-session >/dev/null 2>&1; then
    mkdir -p "$XFDIR"
    for c in xfce4-panel xfce4-desktop xfwm4 xsettings; do
        [ -f "$XFDIR/$c.xml" ] && continue
        [ -f "/etc/xdg/xfce4/xfconf/xfce-perchannel-xml/$c.xml" ] || continue
        cp "/etc/xdg/xfce4/xfconf/xfce-perchannel-xml/$c.xml" "$XFDIR/$c.xml"
    done
    # xfdesktop shows a desktop icon per mounted VOLUME by default. Under proot every bind
    # mount (the Android Camera/Documents/Downloads/Music/Pictures folders, and the rootfs
    # itself) appears in /proc/mounts, so GIO lists them all as removable volumes — and their
    # icons come from gvfs, which we deliberately purge because it cannot work unprivileged.
    # The result was a column of blank white boxes that can never resolve. Turn those entries
    # off; Home and real ~/Desktop files still show.
    #
    # WRITTEN, not patched: Debian ships no xfce4-desktop.xml at all (xfdesktop uses built-in
    # defaults), so there is no stock file to edit — an earlier patch-based attempt silently
    # did nothing for exactly that reason.
    if [ ! -f "$XFDIR/xfce4-desktop.xml" ]; then
        cat > "$XFDIR/xfce4-desktop.xml" <<'XFDESK'
<?xml version="1.0" encoding="UTF-8"?>
<channel name="xfce4-desktop" version="1.0">
  <property name="desktop-icons" type="empty">
    <property name="file-icons" type="empty">
      <property name="show-filesystem" type="bool" value="false"/>
      <property name="show-removable" type="bool" value="false"/>
      <property name="show-trash" type="bool" value="false"/>
      <property name="show-home" type="bool" value="true"/>
    </property>
  </property>
</channel>
XFDESK
        echo "[BSL] xfce: wrote xfce4-desktop.xml (volume icons off - no gvfs under proot)"
    fi


    # xfce4-panel ships its default layout here on Debian, not in the xfconf dir.
    if [ ! -f "$XFDIR/xfce4-panel.xml" ] && [ -f /etc/xdg/xfce4/panel/default.xml ]; then
        cp /etc/xdg/xfce4/panel/default.xml "$XFDIR/xfce4-panel.xml"
    fi
    # XFCE's stock xsettings.xml asks for themes Debian does not ship: IconThemeName "Tango"
    # and ThemeName "Xfce" are both absent, so GTK falls back to the hicolor icon theme —
    # which is deliberately almost empty — and every places icon renders as a blank white box
    # (File System, Documents, Downloads, Music, Pictures all showed that way). Adwaita IS
    # installed and carries exactly those icons, so point at something real.
    #
    # Xft/DPI is hardcoded to 96 there too, which is why the panel and text are unreadably
    # small on a high-DPI tablet. BREADSTICK_DPI comes from the device density.
    if [ -f "$XFDIR/xsettings.xml" ]; then
        sed -i \
            -e 's|\(name="IconThemeName" type="string" value="\)[^"]*|\1Adwaita|' \
            -e 's|\(name="ThemeName" type="string" value="\)[^"]*|\1Adwaita|' \
            "$XFDIR/xsettings.xml"
        case "$BREADSTICK_DPI" in
            ''|*[!0-9]*) : ;;   # not a number: leave the stock 96 alone
            *) sed -i 's|\(name="DPI" type="int" value="\)[^"]*|\1'"$BREADSTICK_DPI"'|' \
                   "$XFDIR/xsettings.xml" ;;
        esac
        echo "[BSL] xfce: icon theme=Adwaita, Xft.DPI=${BREADSTICK_DPI:-96}"
    fi
    # Scale the panel with the display too — its size is in PIXELS, so a stock 26 px row is a
    # sliver on a 280 dpi tablet. Only touch the file we just seeded (a user who resized their
    # own panel keeps it, because we skip seeding when it already exists).
    if [ -f "$XFDIR/xfce4-panel.xml" ] && [ -n "$BREADSTICK_DPI" ]; then
        case "$BREADSTICK_DPI" in
            ''|*[!0-9]*) : ;;
            *)
                psize=$(( 26 * BREADSTICK_DPI / 96 ))
                isize=$(( 16 * BREADSTICK_DPI / 96 ))
                [ "$psize" -gt 26 ] && sed -i \
                    -e 's|\(name="size" type="uint" value="\)26|\1'"$psize"'|' \
                    -e 's|\(name="icon-size" type="uint" value="\)16|\1'"$isize"'|' \
                    "$XFDIR/xfce4-panel.xml"
                ;;
        esac
    fi
    [ "$U" = "root" ] || chown -R "$U" "/home/$U/.config" 2>/dev/null || true
    echo "[BSL] xfce: seeded panel/desktop config in $XFDIR"
fi

# Locale follows the device (BREADSTICK_LOCALE, e.g. en_US, from the app). Generate it if
# missing and make it the system default; C.UTF-8 remains the fallback everywhere else.
#
# Only a locale glibc can actually build. Android happily reports combinations glibc has never
# heard of (English in Germany, en_DE; English in the Netherlands, en_NL), and the old code appended
# those to locale.gen, rebuilt every locale at every start trying to make them, and exported a LANG
# that did not exist: GTK and Perl warnings on every apt run, and Python raising on setlocale. Now an
# unknown combination falls back to the same language elsewhere, then to C.UTF-8, and only the one
# locale needed is generated. Androids old codes for Hebrew, Indonesian and Yiddish are mapped too.
BSL_LOC=""
case "$BREADSTICK_LOCALE" in
    *[!A-Za-z_]*|"") : ;;  # unset or suspicious: leave the guest on its current default
    *)
        case "$BREADSTICK_LOCALE" in
            iw_*) BREADSTICK_LOCALE="he_${BREADSTICK_LOCALE#iw_}" ;;
            in_*) BREADSTICK_LOCALE="id_${BREADSTICK_LOCALE#in_}" ;;
            ji_*) BREADSTICK_LOCALE="yi_${BREADSTICK_LOCALE#ji_}" ;;
        esac
        SUPP=/usr/share/i18n/SUPPORTED
        if [ ! -r "$SUPP" ] || grep -q "^${BREADSTICK_LOCALE}\.UTF-8 UTF-8\$" "$SUPP"; then
            BSL_LOC="$BREADSTICK_LOCALE"
        else
            lang=${BREADSTICK_LOCALE%%_*}
            BSL_LOC=$(grep -m1 "^${lang}_US\.UTF-8 UTF-8\$" "$SUPP" | cut -d. -f1)
            [ -n "$BSL_LOC" ] || BSL_LOC=$(grep -m1 "^${lang}_[A-Z][A-Z]\.UTF-8 UTF-8\$" "$SUPP" | cut -d. -f1)
            echo "[BSL] locale: ${BREADSTICK_LOCALE} is not one glibc supports, using ${BSL_LOC:-C.UTF-8}"
        fi
        if [ -n "$BSL_LOC" ] && ! locale -a 2>/dev/null | grep -qi "^${BSL_LOC}\.utf-\{0,1\}8\$"; then
            grep -q "^$BSL_LOC.UTF-8" /etc/locale.gen 2>/dev/null || echo "$BSL_LOC.UTF-8 UTF-8" >> /etc/locale.gen
            if command -v locale-gen >/dev/null 2>&1; then
                locale-gen --keep-existing >/dev/null 2>&1 || locale-gen >/dev/null 2>&1 || true
            fi
            locale -a 2>/dev/null | grep -qi "^${BSL_LOC}\.utf-\{0,1\}8\$" || BSL_LOC=""
        fi
        if [ -n "$BSL_LOC" ]; then
            printf "LANG=%s.UTF-8\n" "$BSL_LOC" > /etc/default/locale
        else
            printf "LANG=C.UTF-8\n" > /etc/default/locale
        fi
        ;;
esac
# What the session exports as LANG: the locale that exists, never the one that was asked for.
BREADSTICK_LOCALE="$BSL_LOC"

# The LibreOffice launcher fix and the apt hook that keeps it current: see the function at the
# top of this file, which the package's postinst also runs.
bsl_write_package_fixups

# The in-app launcher runs /usr/local/bin/breadstick-list-apps, a copy the app refreshes from its own
# bundle at every start. When apt carries a newer lister (fewer processes, same output), that copy is
# replaced for the rest of the session; the version line keeps an older package from ever replacing
# a newer app copy.
bsl_lister_version() { sed -n 's/^# breadstick-list-apps version \([0-9][0-9]*\)$/\1/p' "$1" 2>/dev/null | head -n 1; }
if [ -r /usr/lib/breadstick/list-apps.sh ] && sh -n /usr/lib/breadstick/list-apps.sh 2>/dev/null; then
    pv=$(bsl_lister_version /usr/lib/breadstick/list-apps.sh); pv=${pv:-1}
    av=$(bsl_lister_version /usr/local/bin/breadstick-list-apps); av=${av:-1}
    if [ "$pv" -gt "$av" ]; then
        cp -f /usr/lib/breadstick/list-apps.sh /usr/local/bin/breadstick-list-apps 2>/dev/null \
            && chmod 755 /usr/local/bin/breadstick-list-apps && echo "[BSL] launcher: app lister v$pv from apt"
    fi
fi

# The guest audio stack, as a command the session AND the app can run: `bsl-audio start` at
# session start, `bsl-audio restart` when the app notices the sound stream has gone (1.1.0),
# and from Settings. Written here, like the xdg-open shim, so every system that has run this
# script once has it. Root writes it; the session user runs it.
#
# Why a helper at all: a diagnostics log from a Fold (2026-09-17) showed Chrome falling back to
# ALSA ("cannot find card 0"), which is what a browser does when it cannot reach PulseAudio at
# the moment its audio service starts. The old block ran `pulseaudio --start` once and went on
# regardless; a daemon that had died with a killed session, or had not come up in time, left
# the desktop silent until the next restart. This one checks that the daemon ANSWERS, clears a
# stale pid file and socket when it does not, retries, and loads each module only if missing.
# The modules go in a default.pa drop-in (Debian's default.pa includes the directory), so
# every daemon start loads them in the right order, including the one libpulse autospawns
# for a client that finds no server. On the tablet, a daemon that came up that way had no
# sink at all, and loading the TCP module afterwards failed to find its source.
mkdir -p /etc/pulse/default.pa.d
cat > /etc/pulse/default.pa.d/breadstick.pa.new <<'PAD'
# Breadstick Linux: the sound path to Android. See start-desktop-x11.sh and bsl-audio.
.nofail
load-module module-null-sink sink_name=breadstick sink_properties=device.description=BreadstickLinux
set-default-sink breadstick
load-module module-simple-protocol-tcp record=true source=breadstick.monitor format=s16le rate=48000 channels=2 listen=127.0.0.1 port=4713
load-module module-pipe-source source_name=breadstick_mic file=/tmp/bsl-mic.fifo format=s16le rate=48000 channels=1
set-default-source breadstick_mic
PAD
if cmp -s /etc/pulse/default.pa.d/breadstick.pa.new /etc/pulse/default.pa.d/breadstick.pa; then
    rm -f /etc/pulse/default.pa.d/breadstick.pa.new
else
    mv -f /etc/pulse/default.pa.d/breadstick.pa.new /etc/pulse/default.pa.d/breadstick.pa
fi
mkdir -p /usr/local/bin
cat > /usr/local/bin/bsl-audio <<'AUDIO'
#!/bin/sh
# Breadstick Linux: start, restart or report the guest audio stack. See start-desktop-x11.sh.
export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/tmp/runtime-$(id -un)}"
export PULSE_RUNTIME_PATH="$XDG_RUNTIME_DIR/pulse"
# The daemon registers itself on the session bus; without the address it waits on the
# wrong bus until D-Bus gives up (25 s).
export DBUS_SESSION_BUS_ADDRESS="${DBUS_SESSION_BUS_ADDRESS:-unix:path=$XDG_RUNTIME_DIR/bus}"
# No realtime scheduling: it is asked for through RTKit over the system bus, which does not
# answer under proot, and every sink thread stalled 25 s asking. A daemon a client spawns
# on its own reads this file; the one started below gets the same on its command line.
if [ ! -f "$HOME/.config/pulse/daemon.conf" ]; then
    mkdir -p "$HOME/.config/pulse" 2>/dev/null
    printf "%s\n" "# Breadstick Linux: see bsl-audio." "realtime-scheduling = no" "high-priority = no" "exit-idle-time = -1" > "$HOME/.config/pulse/daemon.conf" 2>/dev/null
fi
up() { pactl info >/dev/null 2>&1; }
has() { pactl list short modules 2>/dev/null | grep -q "$1"; }
# The microphone FIFO. PulseAudio makes it when it loads the pipe source and removes it when it
# stops, but not when it is killed, and every Stop kills it: the next daemon then logged
# "mkfifo(/tmp/bsl-mic.fifo): File exists", started without a microphone, and removed the FIFO on
# its way out of the failed load, so the pipe source loaded when this script asked a second later.
# Removed here before a daemon starts: only a FIFO, and never through a link.
MIC=/tmp/bsl-mic.fifo
clear_mic() {
    if [ ! -L "$MIC" ] && [ -p "$MIC" ]; then rm -f "$MIC" 2>/dev/null; fi
    return 0
}
# The stream to Android plays the sink monitor, never the microphone. When the default source
# changes, PulseAudio moves every stream on the old default to the new one, and the simple protocol
# does not tie its stream to the source it was opened on. A microphone that arrived late (above) took
# over from the monitor as default and took the stream to Android with it: the phone played its own
# microphone, feedback until Breadstick stopped (Galaxy Z Fold7, 2026-09-26). The same happens when
# someone made the monitor the default (a mixer recording desktop sound) and this script sets the
# microphone back. So after every run of the modules below, any such stream goes back to the
# monitor, and moving it there by name also ties it there. A stream already on the monitor is left
# as it is (PulseAudio does nothing for a move to where a stream already is).
pin() {
    pactl list short source-outputs 2>/dev/null | while read -r o s c d rest; do
        case "$d" in
            *protocol-simple*) pactl move-source-output "$o" breadstick.monitor >/dev/null 2>&1 ;;
        esac
    done
    return 0
}
modules() {
    has module-null-sink || pactl load-module module-null-sink sink_name=breadstick sink_properties=device.description=BreadstickLinux >/dev/null 2>&1
    pactl set-default-sink breadstick >/dev/null 2>&1
    # Microphone in: the app writes Android mic PCM into this FIFO in the shared /tmp. Before the
    # stream to Android, so a daemon missing both has its default source before any stream exists.
    if ! has module-pipe-source; then
        clear_mic
        pactl load-module module-pipe-source source_name=breadstick_mic file="$MIC" format=s16le rate=48000 channels=1 >/dev/null 2>&1
    fi
    pactl set-default-source breadstick_mic >/dev/null 2>&1
    # The monitor of that sink, streamed to the app over TCP 4713 (AudioBridge).
    has simple-protocol-tcp || pactl load-module module-simple-protocol-tcp record=true source=breadstick.monitor format=s16le rate=48000 channels=2 listen=127.0.0.1 port=4713 >/dev/null 2>&1
    pin
}
start() {
    if ! up; then
        # A daemon that does not answer is gone, whatever its pid file says. --start trusts the
        # file, so a session killed from outside could leave the next one without sound.
        pid=$(cat "$PULSE_RUNTIME_PATH/pid" 2>/dev/null)
        pulseaudio --kill >/dev/null 2>&1
        # A daemon on its way out removes the microphone FIFO as it goes, the new daemons one
        # included if that is already there: it goes first (a few seconds at most), as in hold.
        n=0
        while [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null && [ "$n" -lt 6 ]; do sleep 0.5; n=$((n+1)); done
        rm -f "$PULSE_RUNTIME_PATH/pid" "$PULSE_RUNTIME_PATH/native" 2>/dev/null
        clear_mic
        mkdir -p "$PULSE_RUNTIME_PATH" 2>/dev/null
        n=0
        while [ "$n" -lt 3 ]; do
            pulseaudio --start --exit-idle-time=-1 --realtime=no --high-priority=no --log-target=file:"$XDG_RUNTIME_DIR/pulse.log" >/dev/null 2>&1 \
                || pulseaudio --start --exit-idle-time=-1 --realtime=no --high-priority=no >/dev/null 2>&1
            sleep 1
            up && break
            n=$((n+1))
        done
    fi
    up && modules
    status
}
hold() {
    # For the app. Every guest command the app runs is a proot with --kill-on-exit, so a daemon
    # that `restart` started died the moment the command returned (seen on the tablet: startup
    # complete, then the whole module list unloading). This runs the daemon in the foreground
    # and stays with it, so the proot lives exactly as long as the daemon does, and the app can
    # end the daemon by ending the command.
    pulseaudio --kill >/dev/null 2>&1; sleep 1
    rm -f "$PULSE_RUNTIME_PATH/pid" "$PULSE_RUNTIME_PATH/native" 2>/dev/null
    clear_mic
    mkdir -p "$PULSE_RUNTIME_PATH" 2>/dev/null
    pulseaudio --daemonize=no --exit-idle-time=-1 --realtime=no --high-priority=no --log-target=file:"$XDG_RUNTIME_DIR/pulse.log" >/dev/null 2>&1 &
    PA=$!
    n=0
    while [ "$n" -lt 10 ] && ! up; do sleep 1; n=$((n+1)); done
    up && modules
    status
    wait "$PA"
}
status() {
    if up; then
        n=$(pactl list short modules 2>/dev/null | grep -c simple-protocol-tcp)
        echo "[BSL] audio: pulseaudio running, tcp-modules=$n"
        [ "$n" -gt 0 ] || { echo "[BSL] audio: the Android stream module is missing"; return 1; }
    else
        echo "[BSL] audio: pulseaudio DOWN"
        tail -n 5 "$XDG_RUNTIME_DIR/pulse.log" 2>/dev/null | sed 's/^/[BSL] pulse: /'
        return 1
    fi
}
case "${1:-start}" in
    start|restart) start ;;
    hold) hold ;;
    status) status ;;
    *) echo "usage: bsl-audio start|restart|hold|status" >&2; exit 2 ;;
esac
AUDIO
chmod 755 /usr/local/bin/bsl-audio

# xdg-open bridge. Links clicked in Linux land in the ANDROID browser or mail app, the way they
# would land in your desktop browser on a real distro -- except sign-in pages, which open in the
# Linux browser. A login that starts in a Linux app has to return to it, through a custom link
# (claude://, vscode://) or a local address, and only a browser inside Linux can hand either back:
# opened on Android the page loads, you sign in, and the app never hears about it (Claude Desktop
# failed exactly this way). Everything that is not a web or mail link goes to the real xdg-open,
# which is what delivers claude:// to the app that registered it. The shim shadows the real one
# (/usr/local/bin wins in PATH); the app tails /tmp/.bsl-open and fires the intent
# (scheme-whitelisted on the host too). ~/.config/breadstick/links can say "linux" or "android"
# to send every web link one way; "auto", or no file, is the split described here.
mkdir -p /usr/local/bin
cat > /usr/local/bin/xdg-open <<'XDG'
#!/bin/sh
# Breadstick Linux: where a link opened by a Linux app goes. See start-desktop-x11.sh.
url="$1"

to_android() { printf %s\\n "$url" >> /tmp/.bsl-open 2>/dev/null; }

to_linux() {
    for b in /usr/local/bin/breadstick-browser x-www-browser; do
        command -v "$b" >/dev/null 2>&1 || continue
        # Detached, like a desktop opener: the caller should not wait for the whole browser session.
        setsid -f "$b" "$url" </dev/null >/dev/null 2>&1 && return 0
    done
    return 1
}

is_sign_in() {
    lower=$(printf %s "$url" | tr 'A-Z' 'a-z')
    case "$lower" in
        *redirect_uri=*|*response_type=*|*code_challenge=*) return 0 ;;
        */oauth/*|*/oauth2/*|*/oauth\?*|*/authorize*|*/login*|*/signin*|*/sign-in*|*/sso/*|*/auth/*|*/auth\?*) return 0 ;;
    esac
    return 1
}

case "$url" in
    http://*|https://*)
        mode=auto
        f="${XDG_CONFIG_HOME:-$HOME/.config}/breadstick/links"
        [ -r "$f" ] && read -r mode < "$f"
        case "$mode" in
            android) to_android && exit 0 ;;
            linux)   to_linux && exit 0; to_android && exit 0 ;;
            *)       if is_sign_in; then to_linux && exit 0; fi
                     to_android && exit 0 ;;
        esac
        exit 1 ;;
    mailto:*)
        to_android && exit 0
        exit 1 ;;
esac

# xdg-open, when nothing handles a link, hands it to $BROWSER, which is this script. Coming back
# from there with a link that is not a web one means nothing handles it: stop, rather than pass it
# back and forth forever.
case "$(tr '\000' ' ' < /proc/$PPID/cmdline 2>/dev/null)" in
    */usr/bin/xdg-open*) exit 3 ;;
esac
[ -x /usr/bin/xdg-open ] && exec /usr/bin/xdg-open "$@"
exit 1
XDG
chmod 755 /usr/local/bin/xdg-open

# Notification bridge daemon: claims org.freedesktop.Notifications on the session bus so
# libnotify (notify-send, browsers, chat apps) works, and forwards each notification to the
# app via /tmp/.bsl-notify, where it is reposted as a real Android notification. Needs
# python3-dbus + python3-gi (in the breadstick-desktop metapackage); silently absent otherwise.
cat > /usr/local/bin/bsl-notifyd <<'NFY'
#!/usr/bin/env python3
"""Breadstick Linux: forward libnotify notifications to Android."""
import time

import dbus
import dbus.service
from dbus.mainloop.glib import DBusGMainLoop
from gi.repository import GLib

OUT = "/tmp/.bsl-notify"
# Records that can also UPDATE or WITHDRAW a notification, one per line:
#   N <tab> id <tab> app <tab> summary <tab> body     (a new id, or one being replaced in place)
#   C <tab> id                                        (the program closed it)
# Only while the app says it reads them (the word notify-v2 in /run/bsl/features). Older apps read
# OUT, three fields a line, and would show an extra field as part of the text. Without these, a
# "3 new messages" that a program keeps updating arrived on the phone as a stack of separate ones.
OUT2 = "/tmp/.bsl-notify2"
FEATURES = "/run/bsl/features"
# A second, durable copy for the desktop notification center. The outboxes are drained and
# truncated by the Android side as soon as they are reposted, so they cannot double as history.
LOG = "/tmp/.bsl-notify-log"
LOG_MAX = 200
# A program stuck in a loop around notify-send must not bury the phone: at most this many new
# notifications per program in any ten seconds. Updates to one already shown always go through.
BURST = 10


def v2():
    try:
        with open(FEATURES) as f:
            return "notify-v2" in f.read().split()
    except OSError:
        return False


def clean(s):
    return str(s).replace("\t", " ").replace("\n", " ")


def append(path, line):
    try:
        with open(path, "a") as f:
            f.write(line)
    except OSError:
        pass


class Notifications(dbus.service.Object):
    def __init__(self, bus):
        super().__init__(bus, "/org/freedesktop/Notifications")
        self.serial = 0
        self.recent = {}

    @dbus.service.method("org.freedesktop.Notifications",
                         in_signature="susssasa{sv}i", out_signature="u")
    def Notify(self, app, replaces, icon, summary, body, actions, hints, timeout):
        if replaces:
            nid = int(replaces)
        else:
            now = time.time()
            stamps = [t for t in self.recent.get(app, []) if now - t < 10]
            self.serial += 1
            nid = self.serial
            if len(stamps) >= BURST:
                self.recent[app] = stamps
                return nid
            stamps.append(now)
            self.recent[app] = stamps
        if v2():
            append(OUT2, "N\t%d\t%s\t%s\t%s\n" % (nid, clean(app), clean(summary), clean(body)))
        else:
            append(OUT, "%s\t%s\t%s\n" % (clean(app), clean(summary), clean(body)))
        try:
            with open(LOG, "a") as f:
                f.write("%d\t%s\t%s\t%s\n" % (time.time(), clean(app), clean(summary), clean(body)))
            # Trim in place occasionally so a long session cannot grow this without bound.
            if nid % 25 == 0:
                with open(LOG) as f:
                    lines = f.readlines()
                if len(lines) > LOG_MAX:
                    with open(LOG, "w") as f:
                        f.writelines(lines[-LOG_MAX:])
        except OSError:
            pass
        return nid

    @dbus.service.method("org.freedesktop.Notifications", out_signature="as")
    def GetCapabilities(self):
        return ["body"]

    @dbus.service.method("org.freedesktop.Notifications", in_signature="u")
    def CloseNotification(self, nid):
        if v2():
            append(OUT2, "C\t%d\n" % nid)
        self.NotificationClosed(nid, 3)

    @dbus.service.signal("org.freedesktop.Notifications", signature="uu")
    def NotificationClosed(self, nid, reason):
        pass

    @dbus.service.method("org.freedesktop.Notifications", out_signature="ssss")
    def GetServerInformation(self):
        return ("breadstick", "breadstick.io", "1.1", "1.2")


DBusGMainLoop(set_as_default=True)
bus = dbus.SessionBus()
name = dbus.service.BusName("org.freedesktop.Notifications", bus)
Notifications(bus)
GLib.MainLoop().run()
NFY
chmod 755 /usr/local/bin/bsl-notifyd

# X resources loaded into the display at session start (xrdb -merge, inside SESSION).
# The Android pinch gesture arrives in the guest as Ctrl+wheel — the standard X zoom
# accelerator that Chrome, Firefox and GTK apps honor natively. xterm does NOT, so bind
# Ctrl+wheel to its font-step actions; with that, pinch zooms UXTerm like everything else.
# A dedicated file rather than ~/.Xresources so a user file is never clobbered.
mkdir -p /etc/breadstick
cat > /etc/breadstick/xresources <<'XRES'
XTerm*VT100.translations: #override \n\
    Ctrl <Btn4Down>: larger-vt-font() \n\
    Ctrl <Btn5Down>: smaller-vt-font()
UXTerm*VT100.translations: #override \n\
    Ctrl <Btn4Down>: larger-vt-font() \n\
    Ctrl <Btn5Down>: smaller-vt-font()
XRES

# The session, run as the target user. NOTE: single-quoted — keep apostrophes OUT of it.
SESSION='
export DISPLAY=":'"$D"'"
export XDG_RUNTIME_DIR="/tmp/runtime-$(id -un)"
mkdir -p "$XDG_RUNTIME_DIR" && chmod 700 "$XDG_RUNTIME_DIR"
# The GPU verdict of the PREVIOUS session (the guest /tmp persists): removed first thing, so the
# app, which starts reading it two seconds into this session, never takes it for this one. The
# GPU block further down writes this start its own.
rm -f "$XDG_RUNTIME_DIR/bsl-gpu-state" 2>/dev/null
export XDG_SESSION_TYPE=x11
export GDK_BACKEND=x11
# OpenGL through the device GPU when the app runs its GPU server (Pro): only if its socket is
# really there, so a server that is off or died leaves software rendering, never a black window.
# With an app that speaks gpu-v2 (2.0 and later) a check, a preload and a guard follow further down,
# after the root window is painted; an older app gets exactly this and nothing more.
BSL_GPU=0
BSL_GPU_V2=0
grep -qw gpu-v2 /run/bsl/features 2>/dev/null && BSL_GPU_V2=1
if [ "$BREADSTICK_GPU" = "1" ] && [ -S /tmp/.virgl_test ]; then
    export GALLIUM_DRIVER=virpipe VTEST_SOCKET_NAME=/tmp/.virgl_test
    # Not zink: the X server offers DRI3 without an open, so Mesa tries its Vulkan path (kopper),
    # and with no Vulkan driver installed Mesa 25.0 deadlocks in its first GLX call: a blank
    # desktop and no GTK app (a fresh install on a Pixel 10 Pro XL, 2026-09-25).
    export LIBGL_KOPPER_DISABLE=1
    unset LIBGL_ALWAYS_SOFTWARE
    BSL_GPU=1
    echo "[BSL] graphics: GPU (virgl)"
elif [ "$BREADSTICK_GPU" = "native" ] && [ "$BSL_GPU_V2" = 1 ] && read -r BSL_GPU_MODE BSL_GPU_REST < /run/bsl/gpu 2>/dev/null && [ "$BSL_GPU_MODE" = native ]; then
    # Native mode (x86-64; gpu.md in the app repo): the guest Mesa drives the device GPU itself, zink
    # on its Vulkan driver, with no GPU server. The app wrote the environment its probe
    # (bsl-gpu-check --native) found under the first line of /run/bsl/gpu; only these names, and
    # only plain values, are taken. Kopper stays ON for it (zink presents through it, with Vulkan
    # software presentation since the X server has no DRI3); every software process keeps it off.
    while IFS= read -r BSL_L; do
        case "${BSL_L%%=*}" in
            MESA_LOADER_DRIVER_OVERRIDE|GALLIUM_DRIVER|MESA_VK_WSI_DEBUG|LIBGL_KOPPER_DISABLE|INTEL_FORCE_PROBE|BSL_GL_NATIVE)
                case "${BSL_L#*=}" in ""|*[!A-Za-z0-9._,:/-]*) ;; *) export "$BSL_L" ;; esac ;;
        esac
    done <<BSLNATIVE
$(sed -n "2,16p" /run/bsl/gpu 2>/dev/null)
BSLNATIVE
    if [ "$GALLIUM_DRIVER" = zink ] && [ -n "$BSL_GL_NATIVE" ] && [ -c "$BSL_GL_NATIVE" ]; then
        unset LIBGL_ALWAYS_SOFTWARE VTEST_SOCKET_NAME
        BSL_GPU=1
        BSL_GPU_NATIVE=1
        echo "[BSL] graphics: GPU (native, zink on $BSL_GL_NATIVE${INTEL_FORCE_PROBE:+, INTEL_FORCE_PROBE=$INTEL_FORCE_PROBE})"
    else
        echo "[BSL] graphics: native GPU environment incomplete, software"
        unset GALLIUM_DRIVER MESA_LOADER_DRIVER_OVERRIDE INTEL_FORCE_PROBE BSL_GL_NATIVE MESA_VK_WSI_DEBUG
        export LIBGL_ALWAYS_SOFTWARE=1 LIBGL_KOPPER_DISABLE=1
    fi
else
    export LIBGL_ALWAYS_SOFTWARE=1
fi
export NO_AT_BRIDGE=1
# Electron apps honor this (equivalent to --no-sandbox); proot has no sandbox support.
export ELECTRON_DISABLE_SANDBOX=1
# Firefox content/media sandboxes abort under proot too (every tab crashes without this).
export MOZ_DISABLE_CONTENT_SANDBOX=1
export MOZ_DISABLE_GMP_SANDBOX=1
export MOZ_DISABLE_RDD_SANDBOX=1
export MOZ_DISABLE_SOCKET_PROCESS_SANDBOX=1
# Firefox decodes audio in its utility process: with that sandbox on, no video with sound plays.
export MOZ_DISABLE_UTILITY_SANDBOX=1

echo "[BSL] DISPLAY=$DISPLAY; probing X server..."
xrandr 2>&1 | head -3 || echo "[BSL] xrandr failed"
# Paint the root window in the theme colour. Between the X server starting and the desktop
# drawing its first frame, THIS is what is on screen; it was a hardcoded blue, which read as a
# flash of somebody elses wallpaper at every launch.
BSL_ROOT="#F6F4F1"
[ "$BREADSTICK_DARK" = "1" ] && BSL_ROOT="#1A1917"
xsetroot -solid "$BSL_ROOT" 2>&1 || echo "[BSL] xsetroot failed"
# Load the Breadstick X resources (xterm Ctrl+wheel zoom, etc.) into the display so every
# client -- including rootless-mode apps, which have no WM or session config -- picks them up.
command -v xrdb >/dev/null 2>&1 && [ -f /etc/breadstick/xresources ] && \
    xrdb -merge /etc/breadstick/xresources 2>/dev/null

# Shared memory for X11 apps (breadstick-shm): Android has none, so without this every frame an
# app draws goes through the X socket (software GL at 1080p ran at half speed). Used only when its
# self-test passes here, on this device and display, in a process of its own; anything else and
# the session runs exactly as it did before the package existed. Off with /etc/breadstick/no-shm,
# and everywhere at once if a package ever ships /usr/lib/breadstick/shm-withdrawn (the way to
# withdraw it: never remove the library itself, which running programs and their children use).
# It runs after the root window is painted, so the desktop colour shows while it runs.
# And a guard for the XFCE desktop: each start with it counts as unconfirmed until the desktop has
# been up for a while (the wallpaper job below clears the count). After two unconfirmed starts in
# a row the next start turns it off by itself, until a newer library arrives with an update.
rm -f "$XDG_RUNTIME_DIR/bsl-shm-ok" 2>/dev/null
BSL_SHM_TRY="$XDG_RUNTIME_DIR/bsl-shm-trying"
BSL_SHM_OFF="$HOME/.cache/breadstick/shm-auto-off"
BSL_SHM_ID=$(stat -c %s.%Y /usr/lib/breadstick/libbsl-shm.so 2>/dev/null)
BSL_SHM_GUARD=0
if [ "$BREADSTICK_ROOTLESS" != "1" ] && [ "$BREADSTICK_COMPOSITOR" != "1" ] && [ "$BREADSTICK_WAYLAND" != "1" ] && command -v startxfce4 >/dev/null 2>&1; then
    BSL_SHM_GUARD=1
fi
BSL_SHM_TRIES=$(cat "$BSL_SHM_TRY" 2>/dev/null | tr -cd 0-9)
if [ "$BSL_SHM_GUARD" = "1" ] && [ "${BSL_SHM_TRIES:-0}" -ge 2 ]; then
    mkdir -p "$HOME/.cache/breadstick" 2>/dev/null
    echo "$BSL_SHM_ID" > "$BSL_SHM_OFF" 2>/dev/null || true
    rm -f "$BSL_SHM_TRY" 2>/dev/null
    echo "[BSL] shared memory for X11 apps: the last two desktops started with it did not come up"
fi
if [ -r "$BSL_SHM_OFF" ] && [ "$(cat "$BSL_SHM_OFF" 2>/dev/null)" != "$BSL_SHM_ID" ]; then
    rm -f "$BSL_SHM_OFF" 2>/dev/null
fi
if [ -r /usr/lib/breadstick/libbsl-shm.so ] && [ ! -e /etc/breadstick/no-shm ] && [ ! -e /usr/lib/breadstick/shm-withdrawn ] && [ ! -e "$BSL_SHM_OFF" ]; then
    # Debian python3, isolated (-I): not whatever python a profile puts first, nor its settings.
    if BSL_SHM=$(LD_PRELOAD=/usr/lib/breadstick/libbsl-shm.so timeout 10 /usr/bin/python3 -I /usr/lib/breadstick/bsl-shm-check 2>&1); then
        export LD_PRELOAD="/usr/lib/breadstick/libbsl-shm.so${LD_PRELOAD:+ $LD_PRELOAD}"
        # AddressSanitizer refuses to start unless its runtime loads first; with a preload it cannot.
        export ASAN_OPTIONS="verify_asan_link_order=0${ASAN_OPTIONS:+:$ASAN_OPTIONS}"
        # For the app, which preloads it for the apps it starts itself only after this passed.
        # Not ": >": a failed redirection on a special builtin ends a POSIX shell, session and all.
        touch "$XDG_RUNTIME_DIR/bsl-shm-ok" 2>/dev/null || true
        # Counted as a start with it below, once the GPU is settled: a start the GPU guard counts
        # is not held against shared memory as well (one attributed guard).
        BSL_SHM_ON=1
        echo "[BSL] shared memory for X11 apps: on"
    else
        [ $? -eq 124 ] && BSL_SHM="timed out after 10 s"
        echo "[BSL] shared memory for X11 apps: off ($(printf %s "${BSL_SHM:-no answer}" | tail -n 1))"
    fi
elif [ -e "$BSL_SHM_OFF" ]; then
    echo "[BSL] shared memory for X11 apps: off until the next update (desktops started with it did not come up; delete $BSL_SHM_OFF to try again)"
fi

# The device GPU with an app that speaks gpu-v2 (2.0 and later; gpu.md in the app repo). Three things
# an app of 1.3 never had, each in a process of its own that holds no connection once it has exited:
#  - a check through the real X server that OpenGL on the GPU answers from this session (a GLX context,
#    renderer virgl, one clear read back), which also writes the GL visual cache of GTK 3 (GDK_VISUALS
#    on the root window) so that no long-lived GTK program probes the server itself; a probe holds a
#    connection for life, and Mesa aborts a program whose server goes away;
#  - the policy preload, libbsl-glpolicy.so (breadstick-gl), in every program started from here on:
#    liveness first, then per-program lists, so a program started after the server went away draws in
#    software instead of aborting, and the desktop shell never holds the GPU;
#  - one attributed guard: a start with the GPU counts as unconfirmed until the desktop is up (the panel
#    mapped; in Floating the clipboard bridge ready). Two unconfirmed starts in a row turn the GPU off
#    for this system (~/.cache/breadstick/gpu-auto-off) until the app, its server or Mesa changes; such
#    a start is not counted against shared memory, which keeps its own guard for the starts that ran
#    without the GPU. The app offers Try again, which deletes the file.
# $XDG_RUNTIME_DIR/bsl-gpu-state tells the app how this start went: "on", "soft check <reason>" (the
# check failed: stop offering the GPU to new programs, count a failed start), "soft guard" (auto-off),
# or "soft no-preload" (the library withdrawn or kept out: the desktop runs in software and only the
# programs the app launches itself get the GPU, as in 1.3). Absent with an older app or the GPU off.
rm -f "$XDG_RUNTIME_DIR/bsl-gpu-state" 2>/dev/null
BSL_GPU_TRY="$XDG_RUNTIME_DIR/bsl-gpu-trying"
BSL_GPU_OFF="$HOME/.cache/breadstick/gpu-auto-off"
BSL_GPU_GUARD=0
bsl_gpu_soft() {
    unset GALLIUM_DRIVER VTEST_SOCKET_NAME MESA_LOADER_DRIVER_OVERRIDE INTEL_FORCE_PROBE BSL_GL_NATIVE MESA_VK_WSI_DEBUG
    export LIBGL_ALWAYS_SOFTWARE=1 LIBGL_KOPPER_DISABLE=1
    BSL_GPU=0
    echo "soft $1" > "$XDG_RUNTIME_DIR/bsl-gpu-state" 2>/dev/null || true
    echo "[BSL] graphics: software for this start ($1)"
    # Primed in software, so the first GTK program still finds the cache written.
    [ -x /usr/lib/breadstick/bsl-gpu-check ] && timeout -k 2 10 /usr/bin/python3 -I /usr/lib/breadstick/bsl-gpu-check --prime >/dev/null 2>&1 || true
}
if [ "$BSL_GPU" = 1 ] && [ "$BSL_GPU_V2" = 1 ]; then
    # What this guard is keyed to: whatever the app wrote after "on" (its fingerprint of chip, driver
    # and server), and the guest Mesa. A different value lifts an auto-off, as a new library does for
    # shared memory.
    BSL_GPU_ID="$(sed -n "1{s/^native[[:space:]]*//p;t;s/^on[[:space:]]*//p;}" /run/bsl/gpu 2>/dev/null | tr -cd "A-Za-z0-9._:-") $(dpkg-query -W -f "\${Version}" libgl1-mesa-dri 2>/dev/null)"
    BSL_GPU_TRIES=$(cat "$BSL_GPU_TRY" 2>/dev/null | tr -cd 0-9)
    if [ "${BSL_GPU_TRIES:-0}" -ge 2 ]; then
        mkdir -p "$HOME/.cache/breadstick" 2>/dev/null
        echo "$BSL_GPU_ID" > "$BSL_GPU_OFF" 2>/dev/null || true
        rm -f "$BSL_GPU_TRY" 2>/dev/null
        echo "[BSL] graphics: the last two starts with the GPU did not come up"
    fi
    if [ -r "$BSL_GPU_OFF" ] && [ "$(cat "$BSL_GPU_OFF" 2>/dev/null)" != "$BSL_GPU_ID" ]; then
        rm -f "$BSL_GPU_OFF" 2>/dev/null
    fi
    if [ -e "$BSL_GPU_OFF" ]; then
        bsl_gpu_soft guard
    elif [ ! -r /usr/lib/breadstick/libbsl-glpolicy.so ] || [ -e /etc/breadstick/no-glpolicy ] || [ -e /usr/lib/breadstick/glpolicy-withdrawn ]; then
        bsl_gpu_soft no-preload
    elif [ ! -x /usr/lib/breadstick/bsl-gpu-check ]; then
        bsl_gpu_soft "check missing"
    else
        # Debian python3, isolated (-I), with the GPU environment and only the shm preload (the check
        # decides for itself; the policy library would leave it alone anyway).
        if BSL_GPU_CHECK=$(timeout -k 2 10 /usr/bin/python3 -I /usr/lib/breadstick/bsl-gpu-check --session ${BSL_GPU_NATIVE:+--native} 2>&1); then
            echo "[BSL] graphics: $(printf %s "$BSL_GPU_CHECK" | tail -n 1)"
            echo on > "$XDG_RUNTIME_DIR/bsl-gpu-state" 2>/dev/null || true
            export LD_PRELOAD="${LD_PRELOAD:+$LD_PRELOAD }/usr/lib/breadstick/libbsl-glpolicy.so"
            export ASAN_OPTIONS="verify_asan_link_order=0${ASAN_OPTIONS:+:$ASAN_OPTIONS}"
            if [ "$BREADSTICK_ROOTLESS" = "1" ] || [ "$BSL_SHM_GUARD" = "1" ]; then
                BSL_GPU_GUARD=1
            fi
        else
            [ $? -eq 124 ] && BSL_GPU_CHECK="timed out after 10 s"
            bsl_gpu_soft "check $(printf %s "${BSL_GPU_CHECK:-no answer}" | tail -n 1 | sed "s/^fail //")"
        fi
    fi
fi
# The unconfirmed-start counters, attributed to one guard: the GPU when this start uses it, else
# shared memory. Cleared below once the desktop is up.
if [ "$BSL_GPU_GUARD" = "1" ]; then
    echo $(( ${BSL_GPU_TRIES:-0} + 1 )) > "$BSL_GPU_TRY" 2>/dev/null || true
elif [ "${BSL_SHM_ON:-0}" = "1" ] && [ "$BSL_SHM_GUARD" = "1" ]; then
    echo $(( ${BSL_SHM_TRIES:-0} + 1 )) > "$BSL_SHM_TRY" 2>/dev/null || true
fi

# The 32-bit compatibility library (app 2.0, native/ia32compat in the app repo): the app puts it in
# place only on x86-64 devices that run 32-bit programs through qemu-i386, and preloads it only for
# Pro users in the programs it starts itself. The same here, for programs started from the desktop:
# Steam from the menu needs it, its client is 32-bit. $LIB stays literal for the dynamic linker, so
# 32-bit programs load the i386 build and 64-bit ones an empty stub. Off by hand:
# /etc/breadstick/no-ia32compat.
if [ "$(cat /run/bsl/pro 2>/dev/null)" = "1" ] && [ -r /usr/lib/breadstick/lib/i386-linux-gnu/libbsl-ia32compat.so ] && [ ! -e /etc/breadstick/no-ia32compat ]; then
    export LD_PRELOAD="${LD_PRELOAD:+$LD_PRELOAD }/usr/lib/breadstick/\$LIB/libbsl-ia32compat.so"
    export ASAN_OPTIONS="verify_asan_link_order=0${ASAN_OPTIONS:+:$ASAN_OPTIONS}"
    echo "[BSL] 32-bit programs: compatibility library on"
fi

# Web links from CLI tools and anything honoring $BROWSER go through the Android bridge too.
export BROWSER=/usr/local/bin/xdg-open
[ -n "$BREADSTICK_LOCALE" ] && export LANG="$BREADSTICK_LOCALE.UTF-8"

# Follow the Android theme. Only ever touch OUR settings file (marker comment) and only move
# the XFCE theme between the two Adwaita defaults -- a user who picked a theme keeps it.
GTKINI="$HOME/.config/gtk-3.0/settings.ini"
GTK_MANAGED=""
if [ "$BREADSTICK_DARK" = "1" ]; then
    GTK_MANAGED="gtk-application-prefer-dark-theme=1
gtk-theme-name=Adwaita-dark"
    WANT_THEME="Adwaita-dark"
else
    WANT_THEME="Adwaita"
fi
# Floating mode: Android draws the window frame, with its own close, minimize and maximize.
# A GTK app that also draws client-side decorations puts a second set inside the first, and
# every floating app ends up wearing two title bars. An empty decoration layout, meaning no
# buttons on either side, leaves the header bar with its title and its actions and drops the
# window controls that the host already provides.
if [ "$BREADSTICK_ROOTLESS" = "1" ]; then
    GTK_MANAGED="$GTK_MANAGED
gtk-decoration-layout=:"
fi
if [ -n "$GTK_MANAGED" ]; then
    if [ ! -f "$GTKINI" ] || grep -q breadstick-managed "$GTKINI" 2>/dev/null; then
        mkdir -p "$HOME/.config/gtk-3.0"
        printf "[Settings]\n# breadstick-managed\n%s\n" "$GTK_MANAGED" > "$GTKINI"
    fi
elif [ -f "$GTKINI" ] && grep -q breadstick-managed "$GTKINI" 2>/dev/null; then
    rm -f "$GTKINI"
fi
# The session bus comes up BEFORE anything below talks to it. xfconf-query is a GLib program, and
# GLib with no bus address and no socket at XDG_RUNTIME_DIR/bus launches a private bus of its own
# through dbus-launch. On a fresh install there is no socket yet, so the theme block that used to
# sit above this started a second bus, a lingering dbus-launch and a second xfconfd: three extra
# processes for the whole first session, which is what pushed a new install over the limit when
# the browser opened. On later launches the dead socket from the last session stopped the private
# bus, but then the theme calls failed against it instead, so the light and dark switch never
# reached the desktop either.
#
# Session D-Bus at a FIXED address so the in-app launcher can start apps INTO this session
# (separate proot launches connect to the same bus + X server via the shared rootfs sockets).
export DBUS_SESSION_BUS_ADDRESS="unix:path=$XDG_RUNTIME_DIR/bus"
# PROBE, do not just look for the socket file. The guest /tmp survives the app being killed, so
# a session that died left its socket behind; the old check saw that file, skipped starting the
# daemon, and the session bus stayed dead for the life of the install. Everything that talks to
# it then failed with "Connection refused" - VS Code logged it dozens of times per launch.
# Same probe-and-restart shape as the system bus above.
# The configuration lets in programs from the other proots of the app (the Apps drawer, Floating
# windows, Terminal tabs), which the stock one refused; package-fixups writes it and says why.
# Without that file, or if it will not load, the stock session configuration as before.
if ! dbus-send --session --print-reply --reply-timeout=2000 --dest=org.freedesktop.DBus \
        /org/freedesktop/DBus org.freedesktop.DBus.ListNames >/dev/null 2>&1; then
    rm -f "$XDG_RUNTIME_DIR/bus" 2>/dev/null || true
    dbus-daemon --config-file=/usr/local/lib/breadstick/session-bus.conf \
            --address="$DBUS_SESSION_BUS_ADDRESS" --fork 2>/dev/null \
        || dbus-daemon --session --address="$DBUS_SESSION_BUS_ADDRESS" --fork 2>/dev/null || true
fi

# Notification bridge (libnotify -> Android). Backgrounded; harmless no-op when the python
# D-Bus stack is not installed. It MUST start after the session bus above, which it binds to. From
# 1.0.5 to 1.0.14 it started before it: with no bus address yet, libdbus launched a private bus of
# its own through dbus-launch (two more processes for the whole session) and the bridge listened
# there, where no Linux program sends notifications; on later starts it met the dead socket of the
# previous session instead and exited. Either way nothing reached Android.
if command -v bsl-notifyd >/dev/null 2>&1 && \
        python3 -c "import dbus, gi" >/dev/null 2>&1; then
    ( bsl-notifyd >/dev/null 2>&1 & )
    echo "[BSL] notification bridge: running"
else
    echo "[BSL] notification bridge: python3-dbus/python3-gi missing, skipped"
fi

# Clipboard bridge: images, formatted text and file lists, both ways. Only when the app says it
# speaks the protocol (clipboard-v1 in /run/bsl/features); until then the X server inside the app
# keeps syncing plain text, as it always has.
if grep -qw clipboard-v1 /run/bsl/features 2>/dev/null && [ -x /usr/lib/breadstick/bsl-clipboard ] && \
        python3 -c "import gi" >/dev/null 2>&1; then
    # A session killed outright leaves the last bridge marker behind (its pid dead): never the
    # marker of this bridge, so gone before it starts; the app and the GPU guard check the pid too.
    rm -f /tmp/.bsl-clip/ready 2>/dev/null
    ( /usr/lib/breadstick/bsl-clipboard 2>>"$XDG_RUNTIME_DIR/clipboard.log" >/dev/null & )
    echo "[BSL] clipboard bridge: running"
fi


if command -v xfconf-query >/dev/null 2>&1; then
    CUR_THEME=$(xfconf-query -c xsettings -p /Net/ThemeName 2>/dev/null)
    case "$CUR_THEME" in
        # Only ever move between themes WE chose. Anything else was chosen by the user and is left
        # alone -- which is also why the Breadstick names are listed: the desktop must follow the
        # Android light/dark switch, and after the retheme those are the names it will be on.
        ""|Adwaita|Adwaita-dark|Default)
            xfconf-query -c xsettings -p /Net/ThemeName --create -t string -s "$WANT_THEME" 2>/dev/null || true ;;
        Breadstick|Breadstick-dark|OpenStick|OpenStick-dark)
            if [ "${OPENSTICK_DARK:-${BREADSTICK_DARK:-1}}" = "1" ]; then BSL_T=OpenStick-dark; else BSL_T=OpenStick; fi
            [ -d "/usr/share/themes/$BSL_T" ] || { if [ "${OPENSTICK_DARK:-${BREADSTICK_DARK:-1}}" = "1" ]; then BSL_T=Breadstick-dark; else BSL_T=Breadstick; fi; }
            if [ "$CUR_THEME" != "$BSL_T" ]; then
                xfconf-query -c xsettings -p /Net/ThemeName --create -t string -s "$BSL_T" 2>/dev/null || true
                # Frames are a separate setting and must move with the widgets.
                BSL_F="$BSL_T"
                dpi="${OPENSTICK_DPI:-${BREADSTICK_DPI:-192}}"
                case "$dpi" in
                    ""|*[!0-9]*) : ;;
                    *) [ "$dpi" -ge 132 ] && BSL_F="$BSL_T-hidpi" ;;
                esac
                [ -d "/usr/share/themes/$BSL_F" ] || BSL_F="$BSL_T"
                xfconf-query -c xfwm4 -p /general/theme --create -t string -s "$BSL_F" 2>/dev/null || true
                BSL_W=/usr/share/backgrounds/openstick/breadstick-light.png
                [ -f "$BSL_W" ] || BSL_W=/usr/share/backgrounds/breadstick/breadstick-light.png
                if [ "${OPENSTICK_DARK:-${BREADSTICK_DARK:-1}}" = "1" ]; then
                    BSL_W=/usr/share/backgrounds/openstick/breadstick-dark.png
                    [ -f "$BSL_W" ] || BSL_W=/usr/share/backgrounds/breadstick/breadstick-dark.png
                fi
                xfconf-query -c xfce4-desktop \
                    -p /backdrop/screen0/monitor0/workspace0/last-image \
                    --create -t string -s "$BSL_W" 2>/dev/null || true
            fi ;;
    esac
fi

# Audio: a null sink whose monitor is streamed to the app over TCP 4713 (AudioBridge). The
# work lives in /usr/local/bin/bsl-audio, written above as root, so the app can rerun it when
# the stream disappears mid-session. The machine-id is what PulseAudio needs to start at all.
[ -s /etc/machine-id ] || dbus-uuidgen > /etc/machine-id 2>/dev/null || true
[ -s /var/lib/dbus/machine-id ] || { mkdir -p /var/lib/dbus; cp /etc/machine-id /var/lib/dbus/machine-id 2>/dev/null; } || true
export PULSE_RUNTIME_PATH="$XDG_RUNTIME_DIR/pulse"
bsl-audio start || true

# The launcher reads /tmp/.bsl-display to know which X display apps should target.
# Default is our embedded server; the Wayland session overwrites it with the XWayland
# display once weston is up.
echo ":0" > /tmp/.bsl-display

# Rootless: each guest window is rendered as its own host (Android) window (X11 pipeline).
# Whole-screen desktop: Wayland-first — weston (with XWayland for X11 apps) fullscreen on
# the embedded server; falls back to the classic openbox session when weston is absent.
if [ "$BREADSTICK_ROOTLESS" = "1" ]; then
    # No auto-launched terminal here: the Breadstick main window doubles as the app
    # launcher in this mode, so the session starts empty and the user picks an app.
    echo "[BSL] rootless mode: apps are host windows, no guest WM"
    # A start with the GPU is confirmed once the clipboard bridge, the first GTK program, is up:
    # its marker names its pid, and that process must be alive (a marker a killed session left
    # behind is removed above, and would name a dead pid anyway).
    if [ -e "$BSL_GPU_TRY" ]; then
        ( n=0
          while [ $n -lt 8 ]; do
              sleep 5; n=$((n+1))
              cp=
              read -r cp _ < /tmp/.bsl-clip/ready 2>/dev/null || cp=
              if [ -n "$cp" ] && kill -0 "$cp" 2>/dev/null && [ -e "$BSL_GPU_TRY" ]; then rm -f "$BSL_GPU_TRY"; break; fi
          done ) 2>/dev/null &
    fi
    exec sleep infinity
elif [ "$BREADSTICK_COMPOSITOR" = "1" ] && command -v breadstick-compositor >/dev/null 2>&1; then
    # EXPERIMENTAL, opt-in (BREADSTICK_COMPOSITOR=1): the native Breadstick Wayland compositor.
    # It runs headless and exports each guest window to the Android app over /tmp/.bsl-comp,
    # which draws each as its own DeX task (see compositor/DESIGN.md). No host X server or WM
    # needed. Guest apps target the WAYLAND_DISPLAY the compositor prints; app-routing + the
    # bundled Xwayland for X11 apps are wired on-device in later phases.
    echo "[BSL] starting native Wayland compositor (per-window export)..."
    export BSL_COMP_DIR=/tmp WLR_BACKENDS=headless WLR_RENDERER=pixman WLR_LIBINPUT_NO_DEVICES=1
    exec breadstick-compositor 2>&1
elif [ "$BREADSTICK_WAYLAND" = "1" ] && command -v weston >/dev/null 2>&1; then
    # EXPERIMENTAL, opt-in only (BREADSTICK_WAYLAND=1). Nesting weston on the embedded X
    # server needs more work before it is the default: weston cannot fullscreen without a
    # WM on the host X server, and XWayland/app-routing into the session is not wired yet.
    # Until that is solved the default desktop stays the proven openbox session below.
    echo "[BSL] starting weston (Wayland session, XWayland enabled)..."
    mkdir -p "$HOME/.config"
    printf "[core]\nxwayland=true\n[shell]\npanel-position=top\n" > "$HOME/.config/weston.ini"
    rm -f "$XDG_RUNTIME_DIR/wayland-0" "$XDG_RUNTIME_DIR/wayland-0.lock" 2>/dev/null
    # After weston brings XWayland up, record its display for the app launcher, then
    # bridge the Wayland clipboard toward the embedded X server (Android sync path).
    ( n=0
      while [ $n -lt 30 ]; do
          sleep 1; n=$((n+1))
          for d in 1 2 3 4 5; do
              if [ -S "/tmp/.X11-unix/X$d" ]; then echo ":$d" > /tmp/.bsl-display; n=99; break; fi
          done
      done
      command -v wl-paste >/dev/null 2>&1 && command -v xclip >/dev/null 2>&1 && \
          WAYLAND_DISPLAY=wayland-0 wl-paste -n -w xclip -selection clipboard -d :0 2>/dev/null
    ) &
    # No WM on the host X server, so --fullscreen is refused. Instead size westons single
    # output window to the current display geometry so it covers the screen at 0,0.
    GEO=$(xdotool getdisplaygeometry 2>/dev/null)
    WW=${GEO%% *}; HH=${GEO##* }
    case "$WW" in ""|*[!0-9]*) WW=1280 ;; esac
    case "$HH" in ""|*[!0-9]*) HH=720 ;; esac
    echo "[BSL] weston output ${WW}x${HH}"
    exec weston --backend=x11-backend.so --socket=wayland-0 --width="$WW" --height="$HH" --renderer=pixman 2>&1
elif command -v startxfce4 >/dev/null 2>&1; then
    # XFCE: a real desktop (panel, menu, desktop icons, window buttons, settings) instead of a
    # bare WM. It needs no systemd or logind to run — only the session bus, which is already up
    # above. Logout/shutdown from its menu cannot work under proot (there is no session to hand
    # back to); stopping the session from the app is the way out.
    echo "[BSL] starting XFCE..."
    # xfdesktop names its backdrop settings after the RandR output, which cannot be known before
    # the X server is up, so the wallpaper is applied once the desktop is running rather than
    # guessed in a config file written in advance.
    # The same job confirms a start that used shared memory (the desktop came up and stayed up) or
    # the GPU (the panel MAPPED under a LIVE window manager: a hung xfwm4 passes pgrep and holds
    # the panel unmapped; one that never came up lets the panel map by itself, unmanaged).
    (
        sleep 3
        [ -x /usr/lib/breadstick/set-wallpaper.sh ] && /usr/lib/breadstick/set-wallpaper.sh
        if [ -e "$BSL_SHM_TRY" ]; then
            sleep 12
            pgrep -x xfwm4 >/dev/null && pgrep -x xfce4-panel >/dev/null && rm -f "$BSL_SHM_TRY"
        fi
        if [ -e "$BSL_GPU_TRY" ]; then
            n=0
            while [ $n -lt 4 ] && [ -e "$BSL_GPU_TRY" ]; do
                sleep 8; n=$((n+1))
                if timeout 10 /usr/bin/python3 -I /usr/lib/breadstick/bsl-gpu-check --desktop-up >/dev/null 2>&1; then
                    rm -f "$BSL_GPU_TRY"
                fi
            done
        fi
    ) 2>/dev/null &
    # startxfce4 would otherwise run its own dbus-launch and get a SECOND session bus, which the
    # app launcher could not reach — the fixed-address bus above is the one apps connect to.
    exec startxfce4 --disable-tcp 2>&1
elif command -v xfwm4 >/dev/null 2>&1; then
    # XFCE present but no session manager: run the pieces directly.
    echo "[BSL] starting XFCE components (no session manager)..."
    ( sleep 1; xfce4-panel 2>/dev/null ) &
    ( sleep 1; xfdesktop 2>/dev/null ) &
    ( sleep 1; xfsettingsd 2>/dev/null ) &
    exec xfwm4 2>&1
else
    # Last resort: the old openbox session, so an install that cannot fetch XFCE still gets a
    # usable desktop rather than a black screen.
    ( sleep 1; tint2 2>/dev/null ) &
    ( sleep 1.5; xfce4-terminal 2>/dev/null || xterm -fa "DejaVu Sans Mono" -fs 11 2>/dev/null || xterm 2>/dev/null ) &
    echo "[BSL] XFCE unavailable — falling back to openbox"
    exec openbox
fi
'

: "${OPENSTICK_ROOTLESS:=${BREADSTICK_ROOTLESS:=$R}}"
export OPENSTICK_ROOTLESS
export BREADSTICK_ROOTLESS="$OPENSTICK_ROOTLESS"
: "${OPENSTICK_WAYLAND:=${BREADSTICK_WAYLAND:=0}}"
export OPENSTICK_WAYLAND
export BREADSTICK_WAYLAND="$OPENSTICK_WAYLAND"
: "${OPENSTICK_COMPOSITOR:=${BREADSTICK_COMPOSITOR:=0}}"
export OPENSTICK_COMPOSITOR
export BREADSTICK_COMPOSITOR="$OPENSTICK_COMPOSITOR"
: "${OPENSTICK_GPU:=${BREADSTICK_GPU:=0}}"
export OPENSTICK_GPU
export BREADSTICK_GPU="$OPENSTICK_GPU"
: "${OPENSTICK_DARK:=${BREADSTICK_DARK:=1}}"
export OPENSTICK_DARK
export BREADSTICK_DARK="$OPENSTICK_DARK"
: "${OPENSTICK_LOCALE:=${BREADSTICK_LOCALE:=C.UTF-8}}"
export OPENSTICK_LOCALE
export BREADSTICK_LOCALE="$OPENSTICK_LOCALE"
: "${OPENSTICK_DPI:=${BREADSTICK_DPI:=192}}"
export OPENSTICK_DPI
export BREADSTICK_DPI="$OPENSTICK_DPI"

# exec, because nothing runs after this and a shell left waiting on the session is one more
# process against Android's per-app limit (see the process budget notes in apply-look.sh). The
# exit status is unchanged: su already returns the session's.
if [ "$U" = "root" ]; then
    exec sh -c "$SESSION"
else
    # su starts a FRESH environment: every OPENSTICK_* / BREADSTICK_* the session reads must be listed here
    exec su - "$U" -c "export OPENSTICK_ROOTLESS=$OPENSTICK_ROOTLESS BREADSTICK_ROOTLESS=$OPENSTICK_ROOTLESS OPENSTICK_WAYLAND=$OPENSTICK_WAYLAND BREADSTICK_WAYLAND=$OPENSTICK_WAYLAND OPENSTICK_COMPOSITOR=$OPENSTICK_COMPOSITOR BREADSTICK_COMPOSITOR=$OPENSTICK_COMPOSITOR OPENSTICK_GPU=$OPENSTICK_GPU BREADSTICK_GPU=$OPENSTICK_GPU OPENSTICK_DARK=$OPENSTICK_DARK BREADSTICK_DARK=$OPENSTICK_DARK OPENSTICK_LOCALE=$OPENSTICK_LOCALE BREADSTICK_LOCALE=$OPENSTICK_LOCALE OPENSTICK_DPI=$OPENSTICK_DPI BREADSTICK_DPI=$OPENSTICK_DPI; $SESSION"
fi
