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
printf '%s ALL=(ALL:ALL) ALL\n' "$U" > /etc/sudoers.d/openstick
chmod 0440 /etc/sudoers.d/openstick
# Maintain backward compatibility link
ln -sf /etc/sudoers.d/openstick /etc/sudoers.d/breadstick 2>/dev/null || true

mkdir -p /etc/openstick /etc/breadstick
printf '%s' "$U" > /etc/openstick/username
printf '%s' "$U" > /etc/breadstick/username

# OpenStick Linux identity - a Debian-based fork. Cosmetic os identity so the guest
# presents as OpenStick Linux; the Debian base is unchanged underneath. Version fields are DERIVED from
# /etc/debian_version, never hardcoded.
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
PRETTY_NAME="OpenStick Linux"
NAME="OpenStick Linux"
VERSION="$DEBVER ($DEBCODE)"
VERSION_ID="$DEBVER"
VERSION_CODENAME=$DEBCODE
ID=openstick
ID_LIKE=debian
HOME_URL="https://openstick.org"
SUPPORT_URL="https://openstick.org"
EOF
cp /etc/os-release /usr/lib/os-release 2>/dev/null || true
printf 'OpenStick Linux \\n \\l\n\n' > /etc/issue 2>/dev/null || true
printf 'Welcome to OpenStick Linux.\n' > /etc/motd 2>/dev/null || true

# The greeting on every interactive shell, in all three ways to get one: the app's Terminal,
# xfce4-terminal on the desktop, and ssh. It goes in ~/.bashrc rather than /etc/profile.d because
# only login shells read profile.d, and neither the app's terminal nor xfce4-terminal starts one.
#
# Seeded here rather than from apply-look.sh because Terminal-only users never start a desktop
# session, so apply-look would never run for them.
#
# Marked and guarded so it is appended once and is obvious to anyone who wants it gone: deleting
# the two lines is the supported way to turn it off.
# Clean up any legacy Breadstick greetings
sed -i '/bsl-fetch/d' "$BRC" 2>/dev/null || true
sed -i '/Breadstick greeting/d' "$BRC" 2>/dev/null || true
sed -i '/bsl-fetch/d' /etc/skel/.bashrc 2>/dev/null || true
sed -i '/Breadstick greeting/d' /etc/skel/.bashrc 2>/dev/null || true
rm -f /usr/bin/bsl-fetch 2>/dev/null || true

BRC="/home/$U/.bashrc"
if [ -f "$BRC" ] && ! grep -q 'openstick-fetch' "$BRC" 2>/dev/null; then
    cat >> "$BRC" <<'BRCEOF'

# OpenStick greeting. Delete these lines if you would rather have a quiet shell.
case $- in *i*)
    if [ -z "$OPENSTICK_MOTD_SHOWN" ] && [ -x /usr/bin/openstick-fetch ]; then
        export OPENSTICK_MOTD_SHOWN=1
        openstick-fetch
    fi
    ;;
esac
BRCEOF
    chown "$U:$U" "$BRC" 2>/dev/null || true
fi

# Same for any account made later.
if [ -f /etc/skel/.bashrc ] && ! grep -q 'openstick-fetch' /etc/skel/.bashrc 2>/dev/null; then
    cat >> /etc/skel/.bashrc <<'BRCEOF'

# OpenStick greeting. Delete these lines if you would rather have a quiet shell.
case $- in *i*)
    if [ -z "$OPENSTICK_MOTD_SHOWN" ] && [ -x /usr/bin/openstick-fetch ]; then
        export OPENSTICK_MOTD_SHOWN=1
        openstick-fetch
    fi
    ;;
esac
BRCEOF
fi

echo OPENSTICK_USER_OK
