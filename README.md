# mammon

Mount NFS storage on an Android device. Inspired by
[easysshfs](https://github.com/bobrofon/easysshfs).

## Status

v0.5.0: browse NFS shares via SAF (rootless) and optionally mount them for real with
root. The SAF side (`NfsDocumentsProvider`) is the primary surface and speaks both
NFSv4.1 and NFSv3, picking the version per configured export without asking: v4.1
first, since it needs nothing but TCP 2049, then v3 for servers that still run
rpcbind and mountd. One Mount button tries three rungs in order — kernel NFS
`vers=4.2`, kernel NFS `vers=3`, then a FUSE daemon written in the same Kotlin and
serving the same NFS session — and the status line says which backing landed. There is
no native code and no bundled binary: a root shell opens `/dev/fuse`, the app serves
the protocol. The kernel rungs need a kernel with NFS support and a su setup that
mounts into the global namespace; the FUSE view is read-only and synthesises ownership
and permissions. Design history and the full list of narrowings in
`docs/guides/architecture.md`.

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
Release is published with an unsigned APK. Local builds sign automatically when
`keystore.properties` is configured.

Agent-facing documentation lives in [`AGENTS.md`](./AGENTS.md) and [`routes.md`](./routes.md).

## Credits

[easysshfs](https://github.com/bobrofon/easysshfs) — the design reference for mounting
remote storage on rooted Android via a bundled userspace filesystem binary.
