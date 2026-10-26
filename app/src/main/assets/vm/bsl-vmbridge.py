"""OpenStick VM bridge: the Linux half of the files the app and Linux share in /tmp.

Shipped in the app (assets/vm/bsl-vmbridge.py) and run by it through the VM agent, as the Linux user,
for as long as a desktop session runs; stdin and stdout are the channel (rootfs vm/PROTOCOL.md,
"Bridges"). In the Standard engine the app reads and writes the Linux system's /tmp itself. In the
virtual machine it cannot, so this carries those same files across, unchanged, and the session's own
programs (bsl-notifyd, bsl-clipboard, the xdg-open shim) do not know the difference:

  Linux to Android (stdout)
    A <name> <b64>     what was appended to /tmp/<name> since the last look; the file is taken
                       (renamed away and read), as the app drains it in the Standard engine. Names:
                       .bsl-open .bsl-notify .bsl-notify2 .bsl-notify-action
    B <path>           a file follows, relative to /tmp; only .bsl-clip/to-android/<name>
    D <b64>            a piece of it, at most 48 KiB
    E                  the file is complete
    R <0|1>            bsl-clipboard runs (its ready file names a live process), or not
  Android to Linux (stdin)
    B, D, E            a file into /tmp/.bsl-clip/to-linux/ (or its files/ folder)
    X <path>           empty that folder: .bsl-clip/to-linux/files

Every line is ASCII. Payloads are base64. Each side refuses a path outside its own folders, and a
file is written beside its name and renamed over it, never through a link. Nothing is ever printed
but the protocol: the agent merges stderr into stdout, so stderr goes to a log file.
"""
import base64
import json
import os
import stat
import sys
import threading
import time

TMP = os.environ.get("BSL_VMBRIDGE_TMP", "/tmp")
EVENTS = (".bsl-open", ".bsl-notify", ".bsl-notify2", ".bsl-notify-action")
CLIP = os.path.join(TMP, ".bsl-clip")
TO_ANDROID = os.path.join(CLIP, "to-android")
TO_LINUX = os.path.join(CLIP, "to-linux")
CHUNK = 48 * 1024
EVENT_MAX = 1 << 20
FILE_MAX = 64 << 20
POLL = 0.25

out = sys.stdout.buffer
out_lock = threading.Lock()


def send(*lines):
    with out_lock:
        for line in lines:
            out.write(line.encode("ascii") + b"\n")
        out.flush()


def b64(data):
    return base64.b64encode(data).decode("ascii")


def send_file(rel, data):
    lines = ["B " + rel]
    for i in range(0, len(data), CHUNK):
        lines.append("D " + b64(data[i:i + CHUNK]))
    lines.append("E")
    send(*lines)


def read_regular(path, cap):
    """The file's bytes if it is a regular file of at most cap bytes, opened without following links."""
    try:
        fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    except OSError:
        return None
    try:
        st = os.fstat(fd)
        if not stat.S_ISREG(st.st_mode) or st.st_size > cap:
            return None
        chunks = []
        while True:
            b = os.read(fd, 1 << 16)
            if not b:
                break
            chunks.append(b)
        return b"".join(chunks)
    finally:
        os.close(fd)


# ------------------------------------------------------------------------- Linux to Android

def take_events():
    for name in EVENTS:
        path = os.path.join(TMP, name)
        try:
            st = os.lstat(path)
        except OSError:
            continue
        if not stat.S_ISREG(st.st_mode) or st.st_size == 0 or st.st_uid != os.getuid():
            continue
        taken = path + ".bsl-vm-%d" % os.getpid()
        try:
            os.rename(path, taken)
        except OSError:
            continue
        data = read_regular(taken, EVENT_MAX)
        try:
            os.unlink(taken)
        except OSError:
            pass
        if data:
            send("A %s %s" % (name, b64(data)))


def stamp(path):
    try:
        st = os.lstat(path)
        return (st.st_ino, st.st_mtime_ns, st.st_size) if stat.S_ISREG(st.st_mode) else None
    except OSError:
        return None


def send_clip():
    raw = read_regular(os.path.join(TO_ANDROID, "clip.json"), 1 << 20)
    if raw is None:
        return
    try:
        clip = json.loads(raw)
    except ValueError:
        return
    # The files the clip names first, then the clip itself: the app imports when clip.json lands.
    for key in ("text", "html", "image"):
        name = clip.get(key)
        if not isinstance(name, str) or not name:
            continue
        name = os.path.basename(name)
        data = read_regular(os.path.join(TO_ANDROID, name), FILE_MAX)
        if data is not None:
            send_file(".bsl-clip/to-android/" + name, data)
    send_file(".bsl-clip/to-android/clip.json", raw)


def clipboard_running():
    raw = read_regular(os.path.join(CLIP, "ready"), 256)
    if not raw:
        return False
    try:
        pid = int(raw.split()[0])
        with open("/proc/%d/cmdline" % pid, "rb") as f:
            return b"bsl-clipboard" in f.read()
    except (OSError, ValueError, IndexError):
        return False


def watch():
    # What is already there is from before this started: never sent (the app would take an old
    # copy for a new one). Events, which wait to be delivered, are the exception.
    clip_seen = stamp(os.path.join(TO_ANDROID, "clip.json"))
    running = None
    last_ready = 0.0
    while True:
        try:
            take_events()
            s = stamp(os.path.join(TO_ANDROID, "clip.json"))
            if s is not None and s != clip_seen:
                clip_seen = s
                send_clip()
            now = time.monotonic()
            if now - last_ready > 2:
                last_ready = now
                r = clipboard_running()
                if r != running:
                    running = r
                    send("R %d" % (1 if r else 0))
        except (BrokenPipeError, ValueError):
            os._exit(0)  # the channel is gone (ValueError: stdout closed)
        except Exception as e:  # noqa: BLE001 - one bad file must not end the bridge
            sys.stderr.write("bsl-vmbridge: %r\n" % (e,))
        time.sleep(POLL)


# ------------------------------------------------------------------------- Android to Linux

def safe_to_linux(rel):
    """The absolute path for rel when it is a file in to-linux/ or to-linux/files/, else None."""
    parts = rel.split("/")
    if parts[:2] != [".bsl-clip", "to-linux"] or len(parts) not in (3, 4):
        return None
    if len(parts) == 4 and parts[2] != "files":
        return None
    name = parts[-1]
    if not name or name in (".", "..") or "\0" in name:
        return None
    return os.path.join(TMP, *parts)


def real_dir(path):
    os.makedirs(path, mode=0o700, exist_ok=True)
    st = os.lstat(path)
    if not stat.S_ISDIR(st.st_mode):
        raise OSError("not a folder: " + path)


def write_atomic(path, data):
    real_dir(os.path.dirname(path))
    tmp = path + ".bsl-vm-tmp"
    try:
        os.unlink(tmp)
    except OSError:
        pass
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, "wb") as f:
        f.write(data)
    os.replace(tmp, path)


def clear_files():
    d = os.path.join(TO_LINUX, "files")
    real_dir(d)
    for name in os.listdir(d):
        p = os.path.join(d, name)
        try:
            if stat.S_ISDIR(os.lstat(p).st_mode):
                continue
            os.unlink(p)
        except OSError:
            pass


def receive():
    path, parts, size = None, [], 0
    for raw in sys.stdin.buffer:
        line = raw.rstrip(b"\n").decode("ascii", "replace")
        kind, _, rest = line.partition(" ")
        try:
            if kind == "B":
                path, parts, size = safe_to_linux(rest), [], 0
            elif kind == "D" and path is not None:
                b = base64.b64decode(rest)
                size += len(b)
                if size > FILE_MAX:
                    path = None
                else:
                    parts.append(b)
            elif kind == "E" and path is not None:
                write_atomic(path, b"".join(parts))
                path, parts = None, []
            elif kind == "X" and rest == ".bsl-clip/to-linux/files":
                clear_files()
        except (OSError, ValueError) as e:
            sys.stderr.write("bsl-vmbridge: %s: %s\n" % (kind, e))
            path, parts = None, []
    os._exit(0)  # the app closed the channel: the session is over


def main():
    sys.stderr.write("bsl-vmbridge: started (pid %d, uid %d)\n" % (os.getpid(), os.getuid()))
    threading.Thread(target=watch, daemon=True).start()
    receive()


main()
