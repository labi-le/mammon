# architecture — mounting NFS on Android

> **When to read this:** before anything touching the mount design, a service,
> receiver, or provider, permissions, or when asking "why is it built this way".
> The Status section records what was chosen; what follows it is the original
> decision input.

## Status

Direction C — the rootless DocumentsProvider — was PICKED on 2026-08-24 and is the
primary way mammon exposes NFS storage: the configured export shows up in any SAF file
manager through `NfsDocumentsProvider` (authority `app.mammon.nfs`), read-only.
Since v0.4.0 that provider speaks two protocol versions behind one `NfsSession`
interface, chosen per export with no UI switch: NFSv4.1 (`NfsV4Access`, over
`org.dcache:nfs4j-core` XDR and `org.dcache:oncrpc4j-core` RPC) is tried first because
it needs only TCP 2049, and NFSv3 (`NfsAccess`, over `com.emc.ecs:nfs-client`) is the
fallback for servers that still publish rpcbind and mountd. An NFSv4-only server —
the common modern default — was invisible to mammon before that.
Direction A — kernel NFS via `mount -t nfs` under `su` — is implemented alongside it as
a best-effort option (`RootMount`): it tries `vers=4.2` and then `vers=3`, mirroring the
provider's v4-first order, and works only on devices whose kernel has NFS support and
whose su setup lets the mount land in the global namespace. When it fails it separates
the causes it can actually tell apart, because they need different fixes: no usable
`su`, a kernel with neither `nfs` nor `nfs4` in `/proc/filesystems`, a kernel that has
those types but no loaded module for the versions tried, and everything else. That file
is world-readable, so the distinction costs no root. Direction B remains below as a
documented future option; nothing of it is built.

What follows the status lines is the original decision input, kept because it explains
the shape of what was built and why B is still on the table.

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

## Design directions — C and A chosen, B open

Directions C and A are shipped (see Status); only direction B below remains open as
future work. Blockers are stated so they cannot be rediscovered as surprises
mid-implementation.

### Option A — root + kernel NFS client (`mount -t nfs`)

Run `busybox mount -t nfs ...` through `su`.

- **Blocker:** most stock kernels ship without `nfs.ko`, and module loading is usually
  blocked (no `/system/lib/modules` entry, locked bootloader, verified boot). Whether it
  works depends entirely on the specific device/kernel, which makes "requires root" into
  "requires root AND a custom kernel" for many users.

### Option B — root + userspace daemon over `/dev/fuse`

Ship an NFS client binary (or library) that talks FUSE, drive it through `su` like
easysshfs drives `sshfs`.

- **Shape:** closest to easysshfs — same foreground service, same boot receiver, same
  bundled-binary question (where do prebuilt NFS-client binaries come from?).
- **Blocker:** requires root for the FUSE device and the mount syscall; and someone must
  produce/maintain a working Android NFS-client binary, which is exactly the burden
  easysshfs moved out of its repo.

### Option C — rootless DocumentsProvider

Expose the NFS share through a `DocumentsProvider`; files appear in SAF file pickers.

- **Blocker:** no real POSIX mount. Other apps see only what they explicitly request via
  SAF; no arbitrary path access, no mmap, weaker semantics than a filesystem. In exchange
  it runs without root at all.

## What follows regardless of direction

Whichever direction wins, the easysshfs shape predicted a component set beyond what the
first cut of direction C needed — most of it still future work:

- a foreground service owning the mount session (direction A currently runs one-shot
  from the activity instead);
- possibly a boot receiver restoring configured mounts;
- permission declarations beyond today's `INTERNET`;
- a policy decision on bundled binaries vs. building them in-tree (only bites if B is
  ever picked).

When a direction IS picked, update this guide's Status section and `routes.md`'s Planned
section in the same change — a guide describing a dead option as live is worse than no
guide.
