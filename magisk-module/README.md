# Mammon FS Loader

A Magisk module for [mammon](../../README.md): at every boot it scans the usual
Android kernel-module directories and `insmod`s every `fuse*`/`*nfs*` `.ko` it
finds, so the kernel has `fuse`/`nfs`/`nfs4` registered before you press Mount.
Every step is recorded in `load.log`.

## Install

1. Copy `mammon-fsloader-v1.0.zip` to the phone.
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

## Honest limitation

This module can only load what already ships on the device. If no matching
`.ko` exists anywhere in the scanned directories, or the kernel rejects the
file — wrong signature, wrong vermagic, module loading disabled — nothing gets
registered, and no userspace trick can change that: only a custom kernel with
`fuse`/`nfs` built in or as loadable modules helps. The module makes that
diagnosis explicit instead of leaving you guessing.
