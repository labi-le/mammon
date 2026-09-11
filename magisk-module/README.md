# Mammon FS Loader

A Magisk module for [mammon](../../README.md): at every boot it scans the usual
Android kernel-module directories and `insmod`s every `fuse*`/`*nfs*` `.ko` it
finds, so the kernel has `fuse`/`nfs`/`nfs4` registered before you press Mount.
Every step is recorded in `load.log`.

## Install

1. Copy the `mammon-fsloader-v<version>.zip` release asset to the phone.
2. Magisk app -> Modules -> Install from storage -> pick the zip.
3. Reboot. During the flash itself the install console already shows whether
   any candidate modules were found and loaded (the app flash also writes this
   into `load.log`; a custom-recovery flash does not).

## What `load.log` tells you

The log sits next to `module.prop` inside `/data/adb/modules/mammon_fsloader/`.
Each run appends one timestamped block headed by the running kernel release:

- `scan:` — the scan started.
- `candidate: <path>` — one matching `.ko` per line, sorted across all scanned
  directories (`/system*/lib*/modules`, `/vendor*/lib*/modules`,
  `/vendor_dlkm/lib*/modules`, `/odm/lib*/modules`, `/lib/modules`).
- `LOADED: <path>` / `FAILED: <path> (pass N)` — per-file `insmod` results;
  everything that failed beside a success is retried once (two-pass dependency
  handling), because `insmod` resolves no dependencies itself.
- `summary: loaded X of Y candidates after two passes`.
- `registered in /proc/filesystems now:` plus the matching lines — the final
  oracle. Or `(nothing registered)` when nothing matched or nothing stuck.

`action.sh` re-runs the whole scan+load whenever you tap the module's Action
button in the Magisk app; `service.sh` re-runs it once at late_start only if
nothing had registered by then.

## Automount at boot (v1.2)

The module can mount your share automatically at every boot. It is off by
default; to switch it on:

```sh
touch /data/adb/modules/mammon_fsloader/automount
```
At late_start of every boot, when enabled, the module waits inside one bounded
window (~10 minutes) for the conditions the mount needs: mammon's saved settings
to become readable, the server to answer, and — when the mountpoint is inside
emulated storage — the emulated view to exist at all. The settings live in
credential-encrypted storage, so after a reboot they only appear at your FIRST
unlock; with automount enabled the share therefore mounts either immediately
(device unlocked, network up) or right after you unlock post-reboot. If the wait
runs out, the block's final line says which condition never arrived: the spec
(device stayed locked or no share configured), the host (unreachable), or the
emulated view. Once they hold, the module opens `/dev/fuse`, mounts it at the
saved mountpoint and launches the same FUSE daemon the app's own Mount button
uses — so automount works even on kernels with no NFS support at all.
If something is already mounted at the target, the pass logs a clean skip
instead. Everything lands in `load.log`; the final line of each block reads
`MOUNTED:` / `SKIPPED:` / `FAILED:` with the reason.

### A mountpoint inside emulated storage (v1.5)

A saved mountpoint under `/storage/emulated/<user>/<name>`, or the same place
spelled `/sdcard/<name>`, is no longer refused. It usually cannot be mounted where
you typed it: on a stock tree `/storage/emulated` only *receives* mount
propagation, so a mount made there is visible to nothing. Instead the module reads
`/proc/1/mountinfo`, finds the mount that shares that view's propagation peer group
and sends to it — usually `/mnt/user/<user>/emulated`, derived at runtime rather
than assumed — creates `/data/media/<user>/<name>` so the directory exists in the
view, mounts there, and then checks that the kernel really propagated the result
back to `/storage/emulated/<user>/<name>` before reporting it mounted. On a device
whose `/storage/emulated` *sends* propagation rather than only receiving it, the
mount is made in place instead, and there is then nothing to propagate back to.
A foreign filesystem inside that tree is not exotic: it is how `Android/data` gets
there. `load.log` names the target it chose.

Five things stay refused, each with its own line:

- the tree root itself (`/storage/emulated/0`, `/sdcard`) — mount a subdirectory,
  never the whole tree;
- anything under `Android/`, since vold mounts `Android/data` and `Android/obb`
  there itself;
- a path with a `.` or `..` component — `..` can leave shared storage
  altogether, and the module reads the saved settings straight out of the app's
  preferences file, where a hand edit passes none of the checks the app applies
  before saving. This rejection is what stands between such a value and a
  `mkdir` as root outside the tree, and it is no longer the only guard on a
  hand-edited path: before anything is decided the saved value is reduced to
  the one spelling the app decides on — surrounding whitespace dropped,
  repeated slashes collapsed, a trailing slash removed, in that order — so
  neither a doubled slash nor a stray space can carry a path past the refusals
  below. `//data/media/0/nfs` is refused as the `/data/media/0/nfs` it names,
  and `/storage/emulated/0/Android ` earns the `Android/` refusal above instead
  of slipping past it; until that reduction the first was mounted as root below
  the daemon serving the emulated view and the second was mounted, and left
  mounted, at a path the app's Unmount button could never name. What is reduced
  is the decision and never the mount: a mountpoint the routing does not claim
  is created and mounted exactly as you saved it, spaces and all, so the app
  still finds it — and if it is not an absolute path as saved, the pass skips it
  rather than making a directory somewhere else. No guard here reaches past
  Android's own storage tree: any other absolute mountpoint you save is still
  created as root and mounted, which is exactly what the default `/mnt/nas` is;
- a device whose mount table exposes no shared peer for `/storage/emulated` at
  all: such a mount would reach no app, so it is not attempted;
- `/storage` itself, a physical volume such as `/storage/0123-4567/...`, and
  `/data/media/...` typed directly — that last one sits *below* the daemon
  serving the emulated view instead of in the propagating view.

The emulated view is served by MediaProvider, which starts long after
`post-fs-data`. The module waits for it inside the same window rather than racing
it, and a boot where it never appears ends in that `SKIPPED:` line instead of a
hang or a half-made mount. A mountpoint outside emulated storage — the default
`/mnt/nas` — takes none of this path and behaves exactly as before.

The mount accepts writes when the server speaks NFSv4.1, since it runs the
same daemon as the app's own Mount button; against an NFSv3-only export it
stays read-only, and renaming is refused either way. Unmount by rebooting
without the flag file or from the app's Unmount button.

## Honest limitation

This module can only load what already ships on the device. If no matching
`.ko` exists anywhere in the scanned directories, or the kernel rejects the
file — wrong signature, wrong vermagic, module loading disabled — nothing gets
registered, and no userspace trick can change that: only a custom kernel with
`fuse`/`nfs` built in or as loadable modules helps. The module makes that
diagnosis explicit instead of leaving you guessing.
