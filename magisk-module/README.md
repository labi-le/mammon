# Mammon FS Loader

A Magisk module for [mammon](../../README.md): at every boot it scans the usual
Android kernel-module directories and `insmod`s every `fuse*`/`*nfs*` `.ko` it
finds, so the kernel has `fuse`/`nfs`/`nfs4` registered before you press Mount.
Every step is recorded in `load.log`.

## Install

1. Copy `mammon-fsloader-v1.1.zip` to the phone.
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

## Automount at boot (v1.1)

The module can mount your share automatically at every boot. It is off by
default; to switch it on:

```sh
touch /data/adb/modules/mammon_fsloader/automount
```
At late_start of every boot, when enabled, the module waits inside one bounded
window (~10 minutes) for two things: mammon's saved settings to become readable
and the server to answer. The settings live in credential-encrypted storage,
so after a reboot they only appear at your FIRST unlock — with automount
enabled the share therefore mounts either immediately (device unlocked,
network up) or right after you unlock post-reboot. If the wait runs out, the
block's final `FAILED:` line says which condition never arrived: the spec
(device stayed locked or no share configured) or the host (unreachable).
Once both hold, the module opens `/dev/fuse`, mounts it at the saved
mountpoint and launches the same read-only FUSE daemon the app's own Mount
button uses — so automount works even on kernels with no NFS support at all.
If something is already mounted at the target, the pass logs a clean skip
instead. Everything lands in `load.log`; the final line of each block reads
`MOUNTED:` / `SKIPPED:` / `FAILED:` with the reason.

The mount is read-only like every mammon view; unmount by rebooting without
the flag file or from the app's Unmount button.

## Honest limitation

This module can only load what already ships on the device. If no matching
`.ko` exists anywhere in the scanned directories, or the kernel rejects the
file — wrong signature, wrong vermagic, module loading disabled — nothing gets
registered, and no userspace trick can change that: only a custom kernel with
`fuse`/`nfs` built in or as loadable modules helps. The module makes that
diagnosis explicit instead of leaving you guessing.
