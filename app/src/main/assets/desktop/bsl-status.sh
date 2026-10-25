#!/bin/sh
# bsl-status — read Android device state bridged into Breadstick Linux by the host app.
# The host writes plain files under /run/bsl/ (battery, online, network_type, ...). Usage:
#   bsl-status            # print everything (key=value)
#   bsl-status battery    # 0-100, or -1 if unknown
#   bsl-status online     # 1 online / 0 offline
#   bsl-status <key>      # any single key
D=/run/bsl
if [ ! -d "$D" ]; then
    echo "host status not available (is the desktop session running?)" >&2
    exit 1
fi
if [ -n "$1" ]; then
    if [ -f "$D/$1" ]; then cat "$D/$1"; echo; else
        # fall back to the combined file
        grep "^$1=" "$D/status" 2>/dev/null | cut -d= -f2-
    fi
    exit 0
fi
cat "$D/status" 2>/dev/null
