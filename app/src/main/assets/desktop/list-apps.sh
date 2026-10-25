#!/bin/sh
# breadstick-list-apps version 4
# List installed GUI applications as "Name<TAB>Exec<TAB>Icon<TAB>DesktopId<TAB>StartupWMClass" lines,
# for the in-app launcher. The last two (version 3) let the app tell which installed app a window
# belongs to, from the WM_CLASS the X server reports; readers of the first three are unaffected.
# Version 4 reads the user's own directory several folders deep, for an app that can launch what
# it finds there (see below).
# Run as the session user (reads system + per-user .desktop files).
#
# Directories are searched in XDG PRECEDENCE ORDER, highest first, and a desktop id is only used
# from the first directory that has it. That is what makes an override work: hiding an entry means
# dropping a copy with NoDisplay=true into /usr/local/share/applications, and this used to list
# BOTH that copy and the original underneath it, so nothing was ever actually hidden here.
#
# One awk pass over every file. Version 1 ran grep, cut and sed several times per desktop file,
# about a dozen short-lived processes each, and up to eight were alive at once every time the Apps
# drawer opened. Android counts those against the 32 processes it allows this app, so opening the
# drawer next to a browser and VS Code was enough to have the whole desktop killed. This version is
# the shell, one awk and one sort, whatever the number of apps. The output is identical.
#
# The version line above is read by start-desktop-x11.sh, which copies this file over the one the
# app bundles only when this one is newer.

# The entries that cannot work here, as shared data rather than as something this script has to
# be told about after the fact. Reading only the NoDisplay shadows that apply-look.sh writes made
# the drawer's contents depend on whether apply-look had run yet, so a different stray XFCE entry
# turned up on each launch. Absent file = nothing extra filtered, which is the pre-1.0 behaviour.
HIDE_LIST=${BSL_HIDE_LIST:-/usr/share/breadstick/hidden-apps.list}
# Entries Breadstick presents under its own name, same data apply-look.sh shadows them with.
# Applied here too, so the drawer shows "Terminal" and not "Xfce Terminal" on a fresh session,
# before apply-look has had a chance to write the shadow copies.
RENAME_LIST=${BSL_RENAME_LIST:-/usr/share/breadstick/renamed-apps.list}
# Some entries only make sense in one mode. Floating renders each window into its own buffer with
# no single screen behind them, so a screen-capture tool has nothing to grab there.
FLOAT_LIST=${BSL_FLOAT_LIST:-/usr/share/breadstick/hidden-floating.list}
[ "${BREADSTICK_ROOTLESS:-0}" = "1" ] || FLOAT_LIST=/dev/null

# The files, in precedence order, first occurrence of each desktop id only. Globbing, case and [
# are shell builtins, so this costs no processes. An empty file still claims its id (it hides the
# same id further down, exactly as before) but has nothing for awk to read.
#
# The user's own directory is also read up to six folders down. Wine files the Start menu entries
# of the Windows programs it installs under wine/Programs/, with the installer's own folders below
# that, and the desktop launcher (GIO) lists them while this used to see only the top level, so
# they never reached the Apps drawer. A nested entry's desktop id is its path with / as -
# (wine-Programs-Foo.desktop), as the XDG menu spec defines, and two with the same Name are told
# apart by their folder (see the end). The system directories stay one level deep: their
# subfolders hold things that are not apps (screensavers). The ids are kept one per line, because
# Wine's contain spaces.
#
# Only for an app that can launch them: app 1.3 says so with nested-apps-v1 in /run/bsl/features.
# App 1.2 would list Wine's entries and fail to start every one. read is a builtin, so this costs
# no process either.
nested=
if [ -r /run/bsl/features ]; then
    IFS= read -r bsl_features < /run/bsl/features || true
    case " $bsl_features " in *" nested-apps-v1 "*) nested=1 ;; esac
fi
NL='
'
user_apps="$HOME/.local/share/applications"
set --
seen=$NL
for f in "$user_apps"/*.desktop "$user_apps"/*/*.desktop "$user_apps"/*/*/*.desktop \
        "$user_apps"/*/*/*/*.desktop "$user_apps"/*/*/*/*/*.desktop \
        "$user_apps"/*/*/*/*/*/*.desktop "$user_apps"/*/*/*/*/*/*/*.desktop \
        /usr/local/share/applications/*.desktop /usr/share/applications/*.desktop; do
    [ -f "$f" ] || continue
    case "$f" in
        "$user_apps"/*/*)
            [ -n "$nested" ] || continue
            # Not through a folder that is a symbolic link: it can point anywhere, even back up.
            d=${f%/*}
            while [ "$d" != "$user_apps" ]; do [ -h "$d" ] && continue 2; d=${d%/*}; done
            id=${f#"$user_apps"/} ;;
        "$user_apps"/*) id=${f#"$user_apps"/} ;;
        *) id=${f##*/} ;;
    esac
    while :; do
        case "$id" in */*) id="${id%%/*}-${id#*/}" ;; *) break ;; esac
    done
    case "$seen" in *"$NL$id$NL"*) continue ;; esac
    seen="$seen$id$NL"
    [ -s "$f" ] && set -- "$@" "$f"
done
[ $# -gt 0 ] || exit 0

awk -v hide_list="$HIDE_LIST" -v float_list="$FLOAT_LIST" -v rename_list="$RENAME_LIST" -v user_apps="$user_apps/" '
function load_ids(path,    line) {
    while ((getline line < path) > 0) {
        if (line ~ /^#/ || line ~ /^[ \t]*$/) continue
        hidden[line] = 1
    }
    close(path)
}
function value(line) { return substr(line, index(line, "=") + 1) }
# Emit the previous file, if it qualified. Every test matches what version 1 did with grep:
# NoDisplay, Hidden and Type anywhere in the file, case-insensitive; Name, Exec and Icon from the
# first line that starts with exactly that key.
function finish(    exe, rn) {
    if (!cur || skip) return
    if (nodisplay || hiddenkey || !isapp) return
    exe = exec_
    gsub(/ %[A-Za-z]/, "", exe)
    gsub(/%[A-Za-z]/, "", exe)
    if (id in rename_name) {
        if (rename_name[id] != "") name = rename_name[id]
        if (rename_icon[id] != "") icon = rename_icon[id]
    }
    if (name == "" || exe == "") return
    # Kept until the end, when every Name is known (see END). Not "n": BEGIN uses that.
    nout++; o_name[nout] = name; o_exe[nout] = exe; o_icon[nout] = icon; o_id[nout] = id
    o_wm[nout] = wmclass; o_dir[nout] = folder
    names[tolower(name)]++
}
BEGIN {
    load_ids(hide_list)
    if (float_list != "/dev/null") load_ids(float_list)
    while ((getline line < rename_list) > 0) {
        n = split(line, part, "|")
        if (n < 1 || part[1] == "" || (part[1] in rename_name)) continue   # first line wins
        rename_name[part[1]] = (n >= 2 ? part[2] : "")
        rename_icon[part[1]] = (n >= 3 ? part[3] : "")
    }
    close(rename_list)
}
FNR == 1 {
    finish()
    cur = FILENAME
    id = FILENAME
    # The same desktop id the shell gave it above: nested user entries join their folders with -.
    folder = ""
    if (index(id, user_apps) == 1) {
        id = substr(id, length(user_apps) + 1)
        # The folders a nested entry sits in, for telling two entries with one Name apart.
        if (id ~ /\//) { folder = id; sub(/\/[^\/]*$/, "", folder) }
        gsub(/\//, "-", id)
    }
    else sub(/.*\//, "", id)
    # Hidden by Breadstick. (Precedence was settled by the shell: one file per id reaches here.)
    skip = (id in hidden)
    nodisplay = hiddenkey = isapp = 0
    name = exec_ = icon = wmclass = ""
    have_name = have_exec = have_icon = have_wmclass = 0
}
skip { next }
{
    low = tolower($0)
    if (low ~ /^nodisplay=true/) nodisplay = 1
    if (low ~ /^hidden=true/) hiddenkey = 1
    if (low ~ /^type=application/) isapp = 1
    if (!have_name && $0 ~ /^Name=/) { name = value($0); have_name = 1 }
    if (!have_exec && $0 ~ /^Exec=/) { exec_ = value($0); have_exec = 1 }
    if (!have_icon && $0 ~ /^Icon=/) { icon = value($0); have_icon = 1 }
    if (!have_wmclass && $0 ~ /^StartupWMClass=/) { wmclass = value($0); have_wmclass = 1 }
}
# Two programs in Wine often both have an "Uninstall" or a "Readme" entry. The app keeps one entry
# per Name, so a nested entry whose Name is taken gets its folder added: "Uninstall (Acme Tools)".
# When that is still taken (two vendors both file theirs under a "Tools" folder), it gets the
# whole path below wine/Programs instead: "Uninstall (Acme/Tools)".
END {
    finish()
    for (i = 1; i <= nout; i++) {
        label[i] = o_name[i]
        if (names[tolower(o_name[i])] > 1 && o_dir[i] != "") {
            leaf = o_dir[i]; sub(/.*\//, "", leaf)
            label[i] = o_name[i] " (" leaf ")"
        }
        labels[tolower(label[i])]++
    }
    for (i = 1; i <= nout; i++) {
        if (labels[tolower(label[i])] > 1 && o_dir[i] != "") {
            path = o_dir[i]; sub(/^wine\/Programs\//, "", path)
            label[i] = o_name[i] " (" path ")"
        }
        printf "%s\t%s\t%s\t%s\t%s\n", label[i], o_exe[i], o_icon[i], o_id[i], o_wm[i]
    }
}
' "$@" | sort -u
