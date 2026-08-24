# mammon

Mount NFS storage on an Android device. Inspired by
[easysshfs](https://github.com/bobrofon/easysshfs).

## Status

v0.2.0: browse NFS shares via SAF (rootless) and optionally kernel-mount them with
root. The SAF side (`NfsDocumentsProvider`) is the primary surface; the root mount is
best-effort — it needs a kernel with NFS support and a su setup that mounts into the
global namespace. Design history in `docs/guides/architecture.md`.

## Build

The toolchain (JDK 17, Android SDK 35) comes from the nix shell, so always build
through it:

```sh
nix-instantiate --parse shell.nix
nix-shell --run './gradlew :app:assembleDebug'
nix-shell --run './gradlew :app:lintDebug'
```

The debug APK lands in `app/build/outputs/apk/debug/`. With a device connected:

```sh
nix-shell --run './gradlew :app:installDebug'
```

## Releases

Pushing a `v*` tag (matching the app `versionName`) builds, verifies and signs the
release APK — signing needs the keystore secrets configured; without them a GitHub
Release is published with an unsigned APK.

Agent-facing documentation lives in [`AGENTS.md`](./AGENTS.md) and [`routes.md`](./routes.md).

## Credits

[easysshfs](https://github.com/bobrofon/easysshfs) — the design reference for mounting
remote storage on rooted Android via a bundled userspace filesystem binary.
