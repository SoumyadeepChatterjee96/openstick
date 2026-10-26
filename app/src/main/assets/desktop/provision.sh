#!/bin/sh
# Installs the XFCE desktop + apps + the X client stack inside the Debian guest.
# Run as root under proot. XFCE runs fine without systemd/logind (only its logout/shutdown
# actions need a real session); openbox stays as a fallback WM for the floating/rootless path
# and for installs that cannot fetch XFCE. The X *server* is embedded in the app (libXlorie),
# so no Xvnc/Xorg here.
set -e
export DEBIAN_FRONTEND=noninteractive

# proot has no init/systemd: stop maintainer scripts from starting services.
printf '#!/bin/sh\nexit 101\n' > /usr/sbin/policy-rc.d
chmod 0755 /usr/sbin/policy-rc.d

echo "[OpenStick] apt-get update..."
apt-get update

# Install the desktop stack
echo "[OpenStick] installing desktop packages..."
# Emit apt's machine-readable progress so the app can show a real percentage instead of an
# indeterminate spinner — this is a ~180 MB download and the wait IS the first-run experience.
# fd 3 carries apt's status protocol to the pipe; apt's ordinary output goes to stderr, which
# the app merges into the session log either way. Piping costs us apt's exit status, so success
# is judged by the outcome instead (below): did we actually end up with a desktop?
apt_with_progress() {
    apt-get install -y --no-install-recommends -o APT::Status-Fd=3 "$@" 3>&1 1>&2 \
        | while IFS= read -r l; do
            case "$l" in
                dlstatus:*|pmstatus:*) echo "[BSL-PROGRESS] $l" ;;
            esac
          done
}
apt_with_progress breadstick-desktop || true
# Printing through Android (Breadstick 1.3): its own package, without recommends, which would bring
# avahi and cups-browsed. A repository that does not carry it yet is not an error.
apt-get install -y --no-install-recommends breadstick-print >/dev/null 2>&1 || true
# Outcome check, not exit status: an older breadstick-desktop that predates the XFCE switch
# installs fine but leaves no desktop session, so fall through to the explicit list.
if ! command -v xfce4-session >/dev/null 2>&1; then
    echo "[BSL] breadstick-desktop unavailable — falling back to the explicit package list"
    # Also via apt_with_progress: until the metapackage carries XFCE everywhere, THIS is the
    # branch that actually installs the desktop, so it is the one the user watches.
    apt_with_progress \
        xfce4-session xfwm4 xfce4-panel xfdesktop4 xfce4-settings \
        xfce4-terminal xfce4-appfinder xfce4-screenshooter thunar \
        openbox wmctrl xdotool \
        xterm \
        adwaita-icon-theme dbus dbus-x11 \
        pulseaudio pulseaudio-utils \
        xkb-data x11-xkb-utils xauth x11-xserver-utils x11-utils \
        libgl1-mesa-dri libglx-mesa0 mesa-utils \
        fonts-dejavu-core \
        python3-dbus python3-gi librsvg2-common fonts-noto-core fonts-noto-color-emoji || true
fi

# proot has no systemd/logind, can't change real uid/gid, and can't apply ownership overrides, so
# several maintainer scripts fail and wedge dpkg. Shim the tools they call. Install to
# /usr/local/bin (ahead of /usr/bin in the maintainer-script PATH, so the shims survive future apt
# upgrades that would restore the real binaries) and mirror to /usr/bin for absolute-path callers.
#   - systemctl / systemd-tmpfiles : no-ops (no init / no tmpfiles service)
#   - dpkg-statoverride            : no-op — setuid/ownership overrides are meaningless under proot's
#                                    fake root, and the real one fails creating its backup (EPERM),
#                                    which is what wedges dbus -> libpam-systemd -> dbus-user-session
#   - systemd-sysusers             : actually create the declared system users (so dbus, which needs
#                                    the messagebus user, configures cleanly)
#   - setpriv                      : drop the privilege-change flags and exec the program
echo "[BSL] installing proot shims (systemd / setpriv / dpkg-statoverride)..."
mkdir -p /usr/local/bin
for t in systemctl systemd-tmpfiles dpkg-statoverride; do
    printf '#!/bin/sh\nexit 0\n' > "/usr/local/bin/$t"
done
cat > /usr/local/bin/systemd-sysusers <<'SU'
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
SU
cat > /usr/local/bin/setpriv <<'SP'
#!/bin/sh
while [ "$#" -gt 0 ]; do case "$1" in
    --) shift; break ;;
    --reuid|--regid|--ruid|--rgid|--euid|--egid|--uid|--gid|--groups|--inh-caps|--ambient-caps|--bounding-set|--securebits|--pdeathsig|--selinux-label|--apparmor-profile) shift 2 ;;
    --*) shift ;;
    *) break ;;
esac; done
exec "$@"
SP
for t in systemctl systemd-tmpfiles dpkg-statoverride systemd-sysusers setpriv; do
    chmod 0755 "/usr/local/bin/$t"; cp -f "/usr/local/bin/$t" "/usr/bin/$t"
done

# Re-run any postinsts that failed before the shims were in place.
dpkg --configure -a || true
apt-get install -y --no-install-recommends -f || true

# Remove DE/system packages that can't run unprivileged under proot (pulled by an earlier build, or
# as a dependency). Unused by the app model; left half-configured they wedge apt. Best-effort, so a
# previously-broken rootfs also recovers on the next provision.
echo "[BSL] removing proot-incompatible desktop packages (if present)..."
apt-get purge -y nautilus gnome-sushi gvfs gvfs-daemons gvfs-backends gvfs-fuse gvfs-common \
    policykit-1 polkitd polkitd-pkla pkexec lxpolkit udisks2 upower rtkit 2>/dev/null \
    || dpkg --purge --force-all nautilus gnome-sushi gvfs gvfs-daemons gvfs-backends gvfs-fuse \
       policykit-1 polkitd polkitd-pkla pkexec lxpolkit udisks2 upower rtkit 2>/dev/null || true
dpkg --configure -a || true

# The browser the user chose at setup. Done HERE, not earlier, because the installer ships in
# breadstick-theme -- it has to exist before it can be called, and it arrives with the desktop
# above. A browser failing to install must not fail the whole desktop, so this is best-effort:
# the user ends up with a working desktop and no browser rather than neither.
if [ -n "${BREADSTICK_BROWSER:-}" ] && [ "$BREADSTICK_BROWSER" != "none" ]; then
    if [ -x /usr/lib/breadstick/install-browser.sh ]; then
        echo "[BSL] installing the $BREADSTICK_BROWSER browser..."
        apt_with_progress_wrap() {
            sh /usr/lib/breadstick/install-browser.sh "$BREADSTICK_BROWSER" 2>&1 \
                | while IFS= read -r l; do
                    case "$l" in
                        dlstatus:*|pmstatus:*) echo "[BSL-PROGRESS] $l" ;;
                        *) echo "$l" ;;
                    esac
                  done
        }
        apt_with_progress_wrap || echo "[BSL] browser install failed; the desktop is still usable"
    else
        echo "[BSL] browser installer missing; skipping"
    fi
fi

apt-get clean
if command -v xfce4-session >/dev/null 2>&1 || command -v openbox >/dev/null 2>&1; then
    mkdir -p /etc/openstick /etc/breadstick
    : > /etc/openstick/desktop-ok
    : > /etc/breadstick/desktop-ok
    echo "OPENSTICK_PROVISION_DONE"
    echo "BREADSTICK_PROVISION_DONE"
else
    rm -f /etc/openstick/desktop-ok /etc/breadstick/desktop-ok 2>/dev/null || true
    echo "OPENSTICK_PROVISION_INCOMPLETE"
    echo "BREADSTICK_PROVISION_INCOMPLETE"
    exit 1
fi
