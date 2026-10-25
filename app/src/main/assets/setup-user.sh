#!/bin/sh
# Create the primary user account with sudo access.
#   $1 = username (already validated lowercase by the app)
#   password is read from stdin so it never appears in argv/ps
set -e

U="$1"
read -r P

if ! id "$U" >/dev/null 2>&1; then
    useradd -m -s /bin/bash "$U"
fi

# Group membership (for tools that look at it) ...
groupadd -f sudo 2>/dev/null || true
usermod -aG sudo "$U" 2>/dev/null || true

printf '%s:%s\n' "$U" "$P" | chpasswd

# ... but authorize by NAME too: under proot the faked uid may not carry the
# sudo group, so a %sudo rule alone isn't reliable.
mkdir -p /etc/sudoers.d
printf '%s ALL=(ALL:ALL) ALL\n' "$U" > /etc/sudoers.d/breadstick
chmod 0440 /etc/sudoers.d/breadstick

mkdir -p /etc/breadstick
printf '%s' "$U" > /etc/breadstick/username

# Breadstick Linux (BSL) identity - a Debian-based fork. Cosmetic os identity so the guest
# presents as BSL; the Debian base is unchanged underneath. Version fields are DERIVED from
# /etc/debian_version, never hardcoded: this used to stamp "12 (bookworm)" onto trixie
# installs, so Settings offered an upgrade to Debian 13 on a system already running it.
DEBVER="$(cut -d. -f1 /etc/debian_version 2>/dev/null | tr -dc 0-9)"
[ -n "$DEBVER" ] || DEBVER=12
case "$DEBVER" in
    11) DEBCODE=bullseye ;;
    12) DEBCODE=bookworm ;;
    13) DEBCODE=trixie ;;
    14) DEBCODE=forky ;;
    *)  DEBCODE=unknown ;;
esac
cat > /etc/os-release <<EOF
PRETTY_NAME="Breadstick Linux (BSL)"
NAME="Breadstick Linux"
VERSION="$DEBVER ($DEBCODE)"
VERSION_ID="$DEBVER"
VERSION_CODENAME=$DEBCODE
ID=breadstick
ID_LIKE=debian
HOME_URL="https://breadstick.io"
SUPPORT_URL="https://breadstick.io"
EOF
ln -sf /etc/os-release /usr/lib/os-release 2>/dev/null || true
printf 'Breadstick Linux (BSL) \\n \\l\n\n' > /etc/issue 2>/dev/null || true
printf 'Welcome to Breadstick Linux (BSL) - a Debian-based fork.\n' > /etc/motd 2>/dev/null || true

# The greeting on every interactive shell, in all three ways to get one: the app's Terminal,
# xfce4-terminal on the desktop, and ssh. It goes in ~/.bashrc rather than /etc/profile.d because
# only login shells read profile.d, and neither the app's terminal nor xfce4-terminal starts one.
#
# Seeded here rather than from apply-look.sh because Terminal-only users never start a desktop
# session, so apply-look would never run for them.
#
# Marked and guarded so it is appended once and is obvious to anyone who wants it gone: deleting
# the two lines is the supported way to turn it off.
BRC="/home/$U/.bashrc"
if [ -f "$BRC" ] && ! grep -q 'bsl-fetch' "$BRC" 2>/dev/null; then
    cat >> "$BRC" <<'BRCEOF'

# Breadstick greeting. Delete these two lines if you would rather have a quiet shell.
case $- in *i*) [ -x /usr/bin/bsl-fetch ] && bsl-fetch ;; esac
BRCEOF
    chown "$U:$U" "$BRC" 2>/dev/null || true
fi

# Same for any account made later.
if [ -f /etc/skel/.bashrc ] && ! grep -q 'bsl-fetch' /etc/skel/.bashrc 2>/dev/null; then
    cat >> /etc/skel/.bashrc <<'BRCEOF'

# Breadstick greeting. Delete these two lines if you would rather have a quiet shell.
case $- in *i*) [ -x /usr/bin/bsl-fetch ] && bsl-fetch ;; esac
BRCEOF
fi

echo BREADSTICK_USER_OK
