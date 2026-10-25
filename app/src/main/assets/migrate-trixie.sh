#!/bin/sh
# In-place upgrade of an existing Breadstick guest: Debian 12 (bookworm) -> 13 (trixie).
# Run as root under proot, streamed to the Settings UI. Idempotent; safe to re-run after
# a failure (apt resumes). User data (home directories) is untouched by design.
export DEBIAN_FRONTEND=noninteractive

echo '[BSL] preparing proot shims (systemd / setpriv / dpkg-statoverride)...'
printf '#!/bin/sh\nexit 101\n' > /usr/sbin/policy-rc.d
chmod 0755 /usr/sbin/policy-rc.d
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

echo '[BSL] switching apt sources: bookworm -> trixie...'
[ -f /etc/apt/sources.list ] && sed -i 's/bookworm/trixie/g' /etc/apt/sources.list
for f in /etc/apt/sources.list.d/*; do
    [ -f "$f" ] && sed -i 's/bookworm/trixie/g' "$f"
done

# Never let dpkg block on an interactive conffile prompt: with no controlling terminal that
# hangs forever, which looks exactly like "ran 30 minutes then did nothing". Force the
# non-interactive answers everywhere.
APTOPTS='-y -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold -o Dpkg::Use-Pty=false'

echo '[BSL] apt-get update...'
apt-get update 2>&1 || { echo '[BSL] apt update failed - are you online?'; exit 1; }

# Two-stage upgrade (Debian's recommended dist-upgrade path): a minimal-disruption `upgrade`
# first, then `full-upgrade` to bring in the rest. Each is retried once after a dpkg repair.
echo '[BSL] stage 1/2: apt-get upgrade (this can take a while)...'
# shellcheck disable=SC2086
apt-get upgrade $APTOPTS 2>&1 || { dpkg --configure -a 2>&1 || true; apt-get upgrade $APTOPTS 2>&1 || true; }

echo '[BSL] stage 2/2: apt-get full-upgrade...'
# shellcheck disable=SC2086
apt-get full-upgrade $APTOPTS 2>&1 || {
    echo '[BSL] recovering and retrying...'
    dpkg --configure -a 2>&1 || true
    apt-get install -f $APTOPTS 2>&1 || true
    apt-get full-upgrade $APTOPTS 2>&1 || true
}
# shellcheck disable=SC2086
apt-get autoremove $APTOPTS 2>&1 || true
apt-get clean 2>&1 || true
dpkg --configure -a 2>&1 || true

. /etc/os-release
echo "[BSL] now running: $PRETTY_NAME"
[ "$VERSION_CODENAME" = "trixie" ] || exit 1
echo 'BSL_MIGRATE_OK'
