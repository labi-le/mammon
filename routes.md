# routes — :app module reference

> **When to read this:** for orientation before any change, and after adding, removing,
> or restructuring a package or changing its public surface — update this file in the
> same change.

Module `:app` is the only Gradle module. Package root: `app.mammon`.
Config: `applicationId app.mammon`, minSdk 26, compile/target SDK 35.

## As it exists

### Kotlin (`app/src/main/kotlin/app/mammon/`)

| File | Contents |
|---|---|
| `MainActivity.kt` | Two-card UI (SAF config + root mount), classic Views over `R.layout.activity_main`; saves `Prefs`, probes the share, fires ACTION_VIEW on the provider root, drives Mount/Unmount and Install-module off the main thread. Save validates every field and writes nothing when any one of them is in error, so a rejected uid or port cannot be silently coerced. Mount hands `RootMount` a `FuseLaunch` built from `applicationInfo.sourceDir`, a log path under `cacheDir` and the saved `AuthIdentity`, and the status line names the backing that landed. |
| `ModuleInstall.kt` | The Install-module decision core, Android-free: the su probe script (one line per source, `modules_update/mammon_fsloader` before `modules/mammon_fsloader`), the verdict mapping over `Probe.PRESENT`/`ABSENT`/`OUTDATED`/`UNAVAILABLE` (an install at or past the bundled versionCode is PRESENT; an older one is OUTDATED and stages the newer zip; a denied, timed-out or unparseable probe is UNAVAILABLE, never silently absent and never silently up to date), `bundledVersionCode` reading `module.prop` out of the packaged asset zip so that number has one home, and the staged-file policy (`cacheDir/mammon-module.zip`, truncated on every press). The zip itself is packed at build time by `packModuleZip` in `app/build.gradle.kts` and rides as an APK asset; installing happens in Magisk after a chooser hand-off, which mammon cannot observe. |
| `Prefs.kt` | SharedPreferences: host, export, port (default 2049), AUTH_SYS identity, last mountpoint; `spec()` re-parses into an `ExportSpec` and `target()` pairs it with the identity as an `NfsTarget`. |
| `ExportSpec.kt` | Parses "host[:port]:/export"; IPv6 literals bracketed; port range and traversal rejected. Location only — the claimed account lives in `AuthIdentity`. |
| `AuthIdentity.kt` | The AUTH_SYS identity as a triple (uid, gid, supplementary gids) with the one parser for its `uid:gid:aux,aux` form, shared by `Prefs` and the FUSE daemon's argv. uid and gid are `unsigned int` on the wire, so the accepted range is 0…4294967295; the list is capped at the `gids<16>` of RFC 5531 appendix A. `DEFAULT` is a starting point, not a fact: root_squash maps uid 0 to nobody, and the owning account differs per server. |
| `PathCodec.kt` | documentId <-> export-absolute path; rejects "..", empty segments, leading/trailing slashes. `componentOf` is the single guard between a foreign display name and one wire component (no separator, no NUL, no dot name), and `childPath` delegates to it. Every provider id passes through this file. |
| `NfsSession.kt` | The contract the provider and the FUSE daemon share: reads (`probeRoot`/`stat`/`list`/`openFile`) plus the mutating half (`createFile`/`makeDirectory`/`remove`/`setAttributes` keyed by parent + name, and `NfsFile.writeAt`), which defaults to refusing so a backend declines by omission rather than by pretending; `implementsWrites` names what the BACKEND implements, never what the export or the identity permits, and `ReadOnlyNfsSession` carries the refusals so a backend cannot declare writes and then leave one member out. `NfsFailure` is the sealed refusal set — `PermissionDenied`, `NotFound`, `AlreadyExists`, `DirectoryNotEmpty`, `OutOfSpace`, `Unsupported`, `Server` — that both frontends map without reading a message. Also `NodeAttrs`/`ChildEntry`, `NfsTarget`, `NFS_READ_CHUNK`/`NFS_WRITE_CHUNK`, the shared directories-first ordering, and `NfsSessions.select` — v4.1 first, v3 on failure, the v4 error kept as a suppressed exception. `NfsFile.readAt` is the positional read FUSE needs; `streamFor` is a default adapter over it. |
| `NfsAccess.kt` | The NFSv3 `NfsSession` over `com.emc.ecs:nfs-client` — stat, list (one READDIRPLUS loop carrying child attributes; symlinks and special files are not listed), positional reads through `Nfs3File.read`. Read-only by type: it implements `ReadOnlyNfsSession`, which carries the mutating half's refusals. Paths stay export-absolute: `Nfs3File` builds its parent chain by trimming separators and never terminates on a relative path. AUTH_SYS via `CredentialUnix` built from the `AuthIdentity`, matching `NfsV4Access`: under AUTH_NONE a stock Linux server accepts the MOUNT and then refuses GETATTR with NFS3ERR_ACCES. One long-lived session per target; the provider rebuilds it when the config or the identity changes. |
| `NfsV4Access.kt` | The NFSv4.1 `NfsSession` over `org.dcache:nfs4j-core` XDR + `org.dcache:oncrpc4j-core` RPC: EXCHANGE_ID/CREATE_SESSION/RECLAIM_COMPLETE handshake with hand-built channel attributes, one COMPOUND per operation (SEQUENCE + PUTFH + LOOKUPs + op + hand-built tail), READ/WRITE/SETATTR on the anonymous stateid, AUTH_SYS via the local `AuthSys` credential, and one in-place re-establish on a session the server dropped. Creation is the only operation taking OPEN state: `OPEN(OPEN4_CREATE, GUARDED4)` + GETFH + CLOSE in one COMPOUND, with the CLOSE retried alone (and the fused form abandoned for the instance) on a server that refuses it there. READ and WRITE payloads come from `grantedChunk`, which honours what CREATE_SESSION granted rather than an app constant. `recoverable` narrows retries for an operation that must not run twice; `sa_cachethis` stays false because every attempt takes a fresh slot and sequence ID. `Fattr4Codec` decodes the three requested attributes and encodes the SETATTR and create attributes. |
| `NfsDocumentsProvider.kt` | SAF root for the configured export, read-only; every NFS call bounded (~15 s) via coroutine timeout; one long-lived session per `NfsTarget` (`nfsInstance`, which also caches the chosen protocol version), openDocument streams through a reliable pipe with a 64 MiB cap. |
| `NfsScanner.kt` | Discovery: expands the current IPv4 subnet into candidates (`addresses`, capped to the baseIp's /24, network+broadcast excluded) and probes TCP 2049 in parallel (`scan`); `currentSubnet` reads the active network's IPv4 LinkAddress. |
| `MountsParser.kt` / `RootMount.kt` | `/proc/mounts` line parser; one Mount button behind a three-rung ladder driven through `su --mount-master -c` (nsenter fallback): kernel `vers=4.2`, kernel `vers=3`, then the FUSE daemon. Each rung best-effort `modprobe`s its filesystem modules first, since Android kernels usually ship nfs/fuse as modules `/proc/filesystems` does not list until loaded. State and the reported fs type come from /proc/1/mounts so the check matches the global namespace the mount landed in, and `nfs`, `nfs4` and `fuse` all count as mounted — the `/proc/filesystems` lookup keeps its own set so a FUSE-only kernel never looks NFS-capable. A ladder that runs out is classified into `MountDiagnosis` (`NO_ROOT`, `KERNEL_LACKS_FUSE`, `MODULE_FILES_PRESENT`, `FUSE_DAEMON_FAILED`, `GENERIC`) from the exit code, stderr, a root-context `/proc/filesystems` dump split off the same su output (a blank list is no evidence — every inferred kernel claim needs actual text, so an unreadable read degrades to `GENERIC`, never to `KERNEL_LACKS_FUSE`; only the observed missing `/dev/fuse` keeps its unconditional verdict), an `ls | grep` over `/vendor/lib/modules` and `/system/lib/modules` (module file present but nothing registered ⇒ `MODULE_FILES_PRESENT`) and the daemon's own log line; `MainActivity` maps that to a string resource. Before any su call, `MountpointPolicy` (a sibling internal object) refuses Android's own emulated-storage mountpoints (`/storage`, `/sdcard`, `/data/media` and their subtrees — tmpfs /storage, the MediaProvider FUSE mount, /sdcard a symlink into it, /data/media its backing) with a field error naming the alternative; fslib.sh's automount mirrors the same set. |
| `Fuse.kt` | The slice of the FUSE kernel ABI (`linux/fuse.h`) the bridge speaks: opcode numbers, struct sizes and field offsets as constants against a frozen ABI (including `CREATE_OUT_SIZE`, the one sum a CREATE reply must hit exactly or every open fails EIO), the FATTR_* SETATTR mask, errno values, `align8`, the `reply` header writer (errno passed positive, stored negated, refusals never carrying a payload) and `refusal`, which is now `READLINK -> EINVAL, else ENOSYS`: on a mount that accepts writes EROFS would be a false claim about the filesystem, and for RENAME2, FALLOCATE and the xattr setters ENOSYS is additionally what the kernel caches to stop asking. |
| `FuseDevice.kt` | The mounted `/dev/fuse` connection as two unbuffered streams over one descriptor. Deliberately not closeable: the descriptor's owner is whoever adopted it. One syscall per message is mandatory, since FUSE frames requests and replies by syscall boundary. |
| `FuseNodes.kt` | `NodeTable` — the nodeid <-> path map, the only place a path exists once mounted, with allocation (`intern`) separated from kernel referencing (`lookedUp`/`countLookup`/`forget`) so a plain READDIR can reuse nodeids as inode numbers without leaking them, and a removed name separated from a forgotten id: `detach` retires the name into a tombstone (`Node.path` becomes null) while the id lives on for the kernel's pending FORGET, so a re-created name gets a fresh nodeid instead of resolving to the dead one. Eviction only unbinds a name that still points at that id, which is the same bug from the other side. `path` is a var so a later subtree re-key is possible; the root is never retired. `DirRow` + `Dirents.pack` build `fuse_dirent`/`fuse_direntplus` pages: 8-byte-aligned records, cookies one past each row, zeroed padding, and the entry prefix on every record in READDIRPLUS mode. |
| `FuseNfsDaemon.kt` | The opcode loop, reads and writes. Negotiates FUSE_INIT (512 KiB reads and writes via FUSE_MAX_PAGES, READDIRPLUS, 5 s attr/entry TTL, and deliberately NOT FUSE_WRITEBACK_CACHE), serves LOOKUP/GETATTR/SETATTR/OPENDIR/READDIR/READDIRPLUS/RELEASEDIR/OPEN/READ/WRITE/RELEASE/CREATE/MKDIR/UNLINK/RMDIR/STATFS/ACCESS/FORGET/BATCH_FORGET/FLUSH/FSYNC/FSYNCDIR/DESTROY out of an `NfsSession`, refuses the rest per `Fuse.refusal`. READ refills short server replies, because a short FUSE reply means end of file and would zero-fill the caller's page; WRITE loops for the same reason in reverse. Write-through: a failure is reported by the WRITE that caused it, which is where `write(2)` sees it, so FLUSH — whose status is `close(2)`'s — has nothing left to report and RELEASE, whose status the VFS discards, is not asked to. `errnoFor` maps the seam's sealed `NfsFailure` onto errnos. Handles carry their access mode, so a WRITE against a read-only open is EBADF. Several workers read the device concurrently. |
| `FuseDaemon.kt` | Entry point `app.mammon.FuseDaemonKt.main(<fd> <host> <port> <export> [uid:gid:aux])`, started by `RootMount`'s root shell under `app_process`. The identity field is optional so the already-released module's four-argument boot automount keeps working; absent, the daemon claims `AuthIdentity.DEFAULT` and says so in the line it logs. Its own `UID`/`GID` constants stay 0 — those are what the local kernel is told owns the files, not the AUTH_SYS identity. No Android context exists here. Checks that `/proc/self/fd/<fd>` really is `/dev/fuse` before adopting it with `ParcelFileDescriptor.adoptFd`, then serves until FUSE_DESTROY. Kept from R8 by an explicit rule in `app/proguard-rules.pro`, since nothing in the dex references it. |

`FuseRefusalTest`, `DirentsTest`, `NodeTableTest`, `MountpointPolicyTest`,
`FslibAutomountFuseFdTest`, `AuthIdentityTest`, `AuthSysCredentialTest`,
`Fattr4SetattrEncodeTest`, `NfsWriteSeamTest`, `FuseWriteReplyTest`,
`NodeTableDetachTest`.

No services or receivers exist yet. The FUSE daemon is not a service: it is a separate
root process under `app_process`, outliving the `su` call that started it and ending
when `umount` makes the kernel send FUSE_DESTROY.

- Permission: `android.permission.INTERNET`, `android.permission.ACCESS_NETWORK_STATE`
  (LinkProperties for discovery).
- `application`: `allowBackup=false`, theme `Theme.Mammon`.
- One exported activity `MainActivity` with the `MAIN`/`LAUNCHER` intent filter.
- One exported DocumentsProvider `app.mammon.nfs` (`NfsDocumentsProvider`) guarded by
  `android:permission="android.permission.MANAGE_DOCUMENTS"` (the AOSP contract —
  `DocumentsProvider.attachInfo` refuses unprotected authorities), `grantUriPermissions=true`,
  `DOCUMENTS_PROVIDER` intent filter; SAF clients reach it through DocumentsUI, which holds
  that signature permission.
- One unexported FileProvider `app.mammon.fileprovider` (cache-path via `res/xml/file_paths.xml`)
  that hands the staged module zip to the user-chosen installer; both provider declarations
  are pinned by `ManifestGuardTest`.
- No foreground service, no boot receiver.

### Resources (`app/src/main/res/`)

| Path | Purpose |
|---|---|
| `xml/file_paths.xml` | FileProvider cache-path for the staged module zip |
| `values/strings.xml` | UI strings and NFS error messages |
| `layout/activity_main.xml` | The whole MainActivity layout: SAF card + root mount card in one scroll view |
| `values/themes.xml` | `Theme.Mammon`, based on `Theme.Material3.DayNight.NoActionBar` |
| `values/colors.xml` | `ic_launcher_background` |
| `values-night/colors.xml` | Dark palette overrides for the M3 DayNight theme |
| `mipmap-anydpi-v26/` | Adaptive launcher icon XMLs referencing the drawable below |
| `drawable/ic_launcher_foreground.xml` | Vector foreground, geometry inside the 72dp safe zone |


## Planned — future, nothing exists yet

All three directions in `docs/guides/architecture.md` are built; what remains open:

- **Mount service** — foreground service owning a mount session (easysshfs-style);
  today `RootMount` runs one-shot from the activity, and the FUSE daemon it starts
  survives on its own rather than under a service lifecycle.
- **Boot receiver** — restore configured mounts after `BOOT_COMPLETED`.
- **Rename** — not in the `NfsSession` seam, so neither front end offers it. FUSE
  answers RENAME with ENOSYS and SAF withholds `FLAG_SUPPORTS_RENAME`. Adding it means
  a backend RENAME operation first; everything else the seam exposes is now consumed.
