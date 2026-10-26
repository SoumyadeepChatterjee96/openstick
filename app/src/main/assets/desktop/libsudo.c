/*
 * libsudo.so — PRoot privilege shim for OpenStick Linux
 *
 * LD_PRELOAD library that fakes root identity and silences ownership
 * operations.  Under PRoot the real kernel UID is the Android app's
 * unprivileged UID; this library intercepts the libc wrappers so that
 * programs that check "am I root?" (sudo, dpkg, tar, apt, …) see
 * uid 0 and all chown() calls silently succeed.
 *
 * Compiled once for the guest arch (aarch64) and shipped in
 * /usr/lib/openstick/libsudo.so inside the rootfs.
 */

/* We define everything from scratch to avoid host header mismatches
 * when cross-compiling with the Android NDK clang.                    */
typedef unsigned int  uid_t;
typedef unsigned int  gid_t;
typedef unsigned long size_t;

/* ── identity ────────────────────────────────────────────────────── */
uid_t getuid  (void) { return 0; }
uid_t geteuid (void) { return 0; }
gid_t getgid  (void) { return 0; }
gid_t getegid (void) { return 0; }

int getresuid(uid_t *r, uid_t *e, uid_t *s) { if(r)*r=0; if(e)*e=0; if(s)*s=0; return 0; }
int getresgid(gid_t *r, gid_t *e, gid_t *s) { if(r)*r=0; if(e)*e=0; if(s)*s=0; return 0; }

/* ── privilege transitions (all no-ops) ──────────────────────────── */
int setuid   (uid_t u)                         { (void)u; return 0; }
int seteuid  (uid_t u)                         { (void)u; return 0; }
int setgid   (gid_t g)                         { (void)g; return 0; }
int setegid  (gid_t g)                         { (void)g; return 0; }
int setreuid (uid_t r, uid_t e)                { (void)r;(void)e; return 0; }
int setregid (gid_t r, gid_t e)                { (void)r;(void)e; return 0; }
int setresuid(uid_t r, uid_t e, uid_t s)       { (void)r;(void)e;(void)s; return 0; }
int setresgid(gid_t r, gid_t e, gid_t s)      { (void)r;(void)e;(void)s; return 0; }
int initgroups(const char *u, gid_t g)         { (void)u;(void)g; return 0; }
int setgroups (size_t n, const gid_t *l)       { (void)n;(void)l; return 0; }

/* ── ownership changes (critical for dpkg / tar / apt) ───────────── */
int chown    (const char *p, uid_t o, gid_t g) { (void)p;(void)o;(void)g; return 0; }
int fchown   (int fd, uid_t o, gid_t g)        { (void)fd;(void)o;(void)g; return 0; }
int lchown   (const char *p, uid_t o, gid_t g) { (void)p;(void)o;(void)g; return 0; }
int fchownat (int d, const char *p, uid_t o, gid_t g, int f) {
    (void)d;(void)p;(void)o;(void)g;(void)f; return 0;
}
