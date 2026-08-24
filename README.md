# mammon

Mount NFS storage on an Android device. Inspired by
[easysshfs](https://github.com/bobrofon/easysshfs).

## Status

Early scaffold. The app builds and launches a placeholder screen; nothing mounts yet,
and the design direction (see `docs/guides/architecture.md`) is deliberately undecided.

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

Agent-facing documentation lives in [`AGENTS.md`](./AGENTS.md) and [`routes.md`](./routes.md).

## Credits

[easysshfs](https://github.com/bobrofon/easysshfs) — the design reference for mounting
remote storage on rooted Android via a bundled userspace filesystem binary.
