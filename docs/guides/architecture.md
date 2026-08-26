# architecture — mounting NFS on Android

> **When to read this:** before anything touching the mount design, a service,
> receiver, or provider, permissions, or when asking "why is it built this way".
> The Status section records what was chosen; what follows it is the original
> decision input.

## Status

All three directions below are now shipped. Direction C — the rootless
DocumentsProvider — was PICKED on 2026-08-24 and remains the primary way mammon exposes
NFS storage: the configured export shows up in any SAF file manager through
`NfsDocumentsProvider` (authority `app.mammon.nfs`), read-only.
Since v0.4.0 that provider speaks two protocol versions behind one `NfsSession`
interface, chosen per export with no UI switch: NFSv4.1 (`NfsV4Access`, over
`org.dcache:nfs4j-core` XDR and `org.dcache:oncrpc4j-core` RPC) is tried first because
it needs only TCP 2049, and NFSv3 (`NfsAccess`, over `com.emc.ecs:nfs-client`) is the
fallback for servers that still publish rpcbind and mountd. An NFSv4-only server —
the common modern default — was invisible to mammon before that.

Directions A and B both sit behind the single Mount button on the root card. Since
v0.5.0 `RootMount` walks one three-rung ladder and stops at the first rung that answers:

1. kernel NFS via `mount -t nfs -o vers=4.2` under `su --mount-master` (direction A);
2. the same kernel mount with `vers=3`, mirroring the provider's v4-first order;
3. a pure-Kotlin FUSE daemon serving the export out of the same `NfsSession` the SAF
   provider uses (direction B).

The status line names which backing landed, because the three are not interchangeable:
the kernel rungs give a full POSIX mount carrying the server's own ownership and
permission bits, while the FUSE rung gives a read-only view with synthesised metadata
(see below). Rungs 1 and 2 work only on devices whose kernel has NFS support and whose
su setup lets the mount land in the global namespace; rung 3 needs no NFS support in the
kernel at all, only `/dev/fuse` and root — that is the whole reason it exists. One NFS
implementation now backs both surfaces: the daemon carries no second client.

A failure that exhausts the ladder is separated into the causes that need different
fixes: no usable `su`; a kernel that cannot give us FUSE at all (`/dev/fuse` would not
open, or `/proc/filesystems` provably has no `fuse` line, so no rung is left); a FUSE mount the
kernel accepted whose daemon then failed to serve; and everything else.
The `/proc/filesystems` half of that distinction is read in the same root context as
the mount itself (the scripts dump it after the mounts), because the unprivileged app
process can be denied the same read; when even the root read comes back empty the
verdict degrades to "unknown", never to "unsupported".

Since v0.5.1 every rung first tries to load its filesystem's modules
(`modprobe nfs`, `nfsv3`, `nfsv4` on the kernel rungs; `modprobe fuse` on the FUSE
rung), best effort and silent: Android kernels usually build nfs/fuse as loadable
modules that nothing registers until asked, and `/proc/filesystems` only lists what is
registered, so an unloaded-but-available module used to read exactly like missing
support. If the ladder still exhausts with none of nfs/nfs4/fuse registered, the
module dirs decide between two verdicts: a candidate file under
`/vendor/lib/modules` or `/system/lib/modules` (found by one `ls | grep -i` in the same
root call) means the support ships as a module but would not load — its own message,
since the fix differs from every other cause; an empty listing keeps the old
"neither NFS client nor FUSE" wording for kernels that truly lack both.

The app-side preload has two structural limits, which is why the companion
Magisk module `magisk-module/` (`mammon_fsloader`) exists. It runs only when
the user presses Mount, so nothing loaded it at boot, and it shells out to
`modprobe`, whose default search tree `/lib/modules/$(uname -r)` is empty on
stock Android — there is no depmod database, and the `.ko` files actually live
under `/system*/lib*/modules` and `/vendor*/lib*/modules`, reachable only by
`insmod` with a full path. The module instead scans those directories itself
at every boot, `insmod`s each fuse/nfs candidate directly (no dependency
resolution needed when you control the file list), retries failures once
after successes, and appends every step to its own `load.log`. That log is
the definitive oracle for "does this device ship the support as files": it
lists exactly what was found, what loaded or was refused, and what
`/proc/filesystems` registered afterwards.

Since v1.1 the module can also automount the saved share at boot (off by
default behind a flag file): it borrows clifforama/multi-mount's pattern —
config-driven boot mounts with a bounded network wait — but rides the app's
FUSE daemon instead of kernel nfs, so it works on kernels where nfs does not
exist and fuse does.

Since v0.6.0 the app carries this module inside its own APK: `packModuleZip` in
`app/build.gradle.kts` packs the directory deterministically into an asset, and an
Install-module button stages it and hands it to a system chooser for Magisk to flash —
mammon deliberately stops at that hand-off, because only Magisk completing its own flow
proves an install (its probe distinguishes PRESENT from ABSENT, and treats a denied or
timed-out su run as UNAVAILABLE rather than silently absent).

### Why the FUSE rung is pure Kotlin and ships no binary

The obvious design — a JVM process that mounts `/dev/fuse` itself, or that hands the
device to `/system/bin/mount` — is unavailable on Android in both halves:

- Neither `android.system.Os` nor libcore's internal `Os` exposes `mount(2)` or
  `umount2(2)`, so a JVM process cannot perform the mount.
- libcore's `UNIXProcess_md.c` closes every descriptor above stderr in the child before
  `exec`, so a JVM process cannot pass an open `/dev/fuse` descriptor to a `mount` child
  either.
- Inverting the direction removes the problem. The root SHELL opens `/dev/fuse`, keeps
  that descriptor across the `mount` child and across `exec app_process`, and the Kotlin
  daemon adopts the number with `ParcelFileDescriptor.adoptFd`. toybox `mount` forwards
  unknown `-o` options straight to `mount(2)`, so `-o fd=3,rootmode=40000,...` reaches
  the kernel untouched. AOSP's own vold mounts FUSE exactly this way — `open("/dev/fuse")`
  and then `mount("/dev/fuse", path, "fuse", ...,
  "fd=%i,rootmode=40000,allow_other,user_id=0,group_id=0,")`.
- No SELinux policy is shipped and none is needed: a Magisk root process runs in the
  unconstrained, permissive `magisk` domain, and `genfscon` labels every FUSE mount
  `u:object_r:fuse:s0`, which AOSP's `app.te` already grants every app domain access to.

The rejected alternative is recorded because it is what direction B originally proposed:
a prebuilt `sahlberg/fuse-nfs` on libnfs, cross-compiled with the NDK. libnfs implements
NFSv3 and NFSv4.0 but explicitly NOT NFSv4.1, so it would have been a protocol
regression against the `NfsV4Access` the app already had; it needs libfuse 2.9.x, which
upstream abandoned in 2021; and it costs roughly 0.8 MB of native libraries per ABI.
That is why B was finally built this way — it is history, not a live option.

### What the FUSE view does not do

Every one of these is a deliberate narrowing, not a gap waiting on a fix:

- **Read-only.** Every mutating FUSE opcode answers EROFS.
- **No real ownership or permission bits.** `NodeAttrs` carries only type, size and
  mtime, so the daemon synthesises mode `0555` for directories and `0444` for files,
  owned by uid 0 / gid 0 to match the mount's `user_id`/`group_id`.
- **No symlinks or special files.** `NfsSession.list` does not surface them, so they are
  not listed, and READLINK answers EINVAL.
- **No free-space figures.** `STATFS` reports zero blocks and zero inodes with `namelen`
  255, because `NfsSession` has no FSSTAT.
- **5 s attribute and directory-entry lifetimes.** That TTL is what collapses the
  GETATTR storm behind `ls -l`.
- **Directory listings are snapshotted at OPENDIR**, so a change on the server appears
  on the next open rather than mid-walk.
- **xattrs and locking answer ENOSYS**, which the kernel caches and then stops asking
  about.

### Verification status

Split honestly, because the two halves have very different evidence behind them. The
daemon and the `NfsSession` seam are PROVEN on a Linux host: a real NFS export mounted
inside `unshare -Umr`, reads verified byte-exact by sha256 against the same files read
through the kernel's own NFS client, over both NFSv4.1 and NFSv3, and a clean `umount`.
The end-to-end chain on a rooted Android phone — `su`, the kernel FUSE mount, and the
inherited descriptor surviving `exec app_process` — is NOT verified by this project, and
cannot be here: Waydroid has no `su`, so no emulator available to us can carry that
test. Rung 3 on a real phone is therefore untested end to end; when it fails, read the
diagnosis the ladder reports rather than assuming which half broke.

What follows is the original decision input, kept because it explains the shape of what
was built and which constraints each direction was chosen against.

## How easysshfs works

easysshfs mounts SSH storage on Android with this shape:

- **Root access required.** The app drives a bundled, prebuilt `sshfs` binary through
  `su`; it never implements the filesystem in-process.
- **Bundled binaries come from outside the app build** — the author ships them via a
  separate buildroot-based releases repository, so the APK build itself needs no native
  toolchain.
- **A foreground service carries the mount session**, declared with
  `foregroundServiceType="connectedDevice"`, so the mount survives while its UI is gone.
- **`OnBootReceiver` remounts after `BOOT_COMPLETED`**, so configured mounts return on
  reboot without opening the app.
- **Permissions**: `INTERNET`, network/WIFI state, `POST_NOTIFICATIONS`,
  `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_CONNECTED_DEVICE`. Pure Kotlin, classic
  Views, published on F-Droid and Play under MIT.

The transferable lesson: on stock Android a real POSIX mount means root plus a daemon,
and everything user-visible (foreground notification, boot restore) hangs off a service
lifecycle, not off activity state.

## Design directions — all three shipped

Directions C, A and B are all shipped (see Status). The blockers below are the ones
recorded before implementation, each annotated with how it actually landed: a blocker
that was removed teaches as much as one that held.

### Option A — root + kernel NFS client (`mount -t nfs`)

Run `busybox mount -t nfs ...` through `su`.

- **Blocker, and it held:** most stock kernels ship without `nfs.ko`, and module loading
  is usually blocked (no `/system/lib/modules` entry, locked bootloader, verified boot).
  Whether it works depends entirely on the specific device/kernel, which makes "requires
  root" into "requires root AND a custom kernel" for many users. The blocker is why the
  ladder does not stop at these two rungs.

### Option B — root + userspace daemon over `/dev/fuse`

Mount `/dev/fuse` under `su` and serve the filesystem from userspace, as easysshfs does
with `sshfs`.

- **Shape as first written:** closest to easysshfs — same foreground service, same boot
  receiver, same bundled-binary question (where do prebuilt NFS-client binaries come
  from?).
- **Blocker as first written:** requires root for the FUSE device and the mount syscall;
  and someone must produce and maintain a working Android NFS-client binary, exactly the
  burden easysshfs moved out of its repo.
- **How it landed instead:** the binary half of that blocker was removed by dropping the
  binary. Since v0.5.0 the daemon is the app's own Kotlin, reusing the `NfsSession` the
  SAF provider already speaks, and the root shell does the two things a JVM process
  cannot — open `/dev/fuse` and call `mount(2)`. Root is still required; that half never
  went away. Status above carries why the inversion is necessary, why the
  prebuilt-binary route was rejected rather than deferred, which narrowings the Kotlin
  daemon accepts, and the fact that the rooted-phone chain is unverified.
- **Still not built from this shape:** the foreground service and the boot receiver. The
  mount is started one-shot from the activity, and the daemon is a detached root
  `app_process` rather than a service the app owns, so nothing restarts it after a reboot
  or after the daemon exits.

### Option C — rootless DocumentsProvider

Expose the NFS share through a `DocumentsProvider`; files appear in SAF file pickers.

- **Blocker:** no real POSIX mount. Other apps see only what they explicitly request via
  SAF; no arbitrary path access, no mmap, weaker semantics than a filesystem. In exchange
  it runs without root at all.

## What follows regardless of direction

The easysshfs shape predicted a component set beyond what mammon has built; what is
still open:

- a foreground service owning the mount session (all three rungs are started one-shot
  from the activity instead);
- possibly a boot receiver restoring configured mounts;
- permission declarations beyond today's `INTERNET`.

The bundled-binary question is settled rather than open: mammon ships no native code of
its own and bundles no prebuilt binary for any direction. Status explains why the FUSE
rung needed neither.

Keep this guide's Status section, `routes.md` and `README.md` in step with the code in
the same change — a guide describing a dead option as live is worse than no guide.
