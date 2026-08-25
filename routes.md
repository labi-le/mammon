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
| `MainActivity.kt` | Two-card UI (SAF config + root mount), classic Views over `R.layout.activity_main`; saves `Prefs`, probes the share, fires ACTION_VIEW on the provider root, drives Mount/Unmount and Install-module off the main thread. Mount hands `RootMount` a `FuseLaunch` built from `applicationInfo.sourceDir` and a log path under `cacheDir`, and the status line names the backing that landed. |
| `ModuleInstall.kt` | The Install-module decision core, Android-free: the su probe script for `/data/adb/modules/mammon_fsloader`, the verdict mapping (a denied or timed-out probe is UNAVAILABLE, never silently absent), the staged-file policy (`cacheDir/mammon-module.zip`, truncated on every press). The zip itself is packed at build time by `packModuleZip` in `app/build.gradle.kts` and rides as an APK asset; installing happens in Magisk after a chooser hand-off, which mammon cannot observe. |
| `Prefs.kt` | SharedPreferences: host, export, port (default 2049), last mountpoint; `spec()` re-parses into an `ExportSpec`. |
| `ExportSpec.kt` | Parses "host[:port]:/export"; IPv6 literals bracketed; port range and traversal rejected. |
| `PathCodec.kt` | documentId <-> export-absolute path; rejects "..", empty segments, leading/trailing slashes; `childPath` builds a directory entry's path and drops dot names. Every provider id passes through it. |
| `NfsSession.kt` | The read-only contract the provider and the FUSE daemon share (`probeRoot`/`stat`/`list`/`openFile`), the protocol-neutral `NodeAttrs`/`ChildEntry`, `NFS_READ_CHUNK`, the shared directories-first ordering, and `NfsSessions.select` — v4.1 first, v3 on failure, the v4 error kept as a suppressed exception. `NfsFile.readAt` is the positional read FUSE needs; `streamFor` is a default adapter over it, so SAF keeps its sequential view without a second read path. |
| `NfsAccess.kt` | The NFSv3 `NfsSession` over `com.emc.ecs:nfs-client` — stat, list (one READDIRPLUS loop carrying child attributes; symlinks and special files are not listed), positional reads through `Nfs3File.read`. Paths stay export-absolute: `Nfs3File` builds its parent chain by trimming separators and never terminates on a relative path. AUTH_SYS via `CredentialUnix`, matching `NfsV4Access`: under AUTH_NONE a stock Linux server accepts the MOUNT and then refuses GETATTR with NFS3ERR_ACCES. One long-lived session per config; the provider rebuilds it when the config changes. |
| `NfsV4Access.kt` | The NFSv4.1 `NfsSession` over `org.dcache:nfs4j-core` XDR + `org.dcache:oncrpc4j-core` RPC: EXCHANGE_ID/CREATE_SESSION/RECLAIM_COMPLETE handshake with hand-built channel attributes, one COMPOUND per operation (SEQUENCE + PUTFH + LOOKUPs + op), READ on the anonymous stateid, AUTH_SYS via the local `AuthSys` credential, and one in-place re-establish on a session the server dropped. `Handle` reads positionally with PUTFH + READ in one COMPOUND, so no OPEN state exists to tie a read to a thread. `Fattr4Codec` decodes the three requested attributes. |
| `NfsDocumentsProvider.kt` | SAF root for the configured export, read-only; every NFS call bounded (~15 s) via coroutine timeout; one long-lived session per config (`nfsInstance`, which also caches the chosen protocol version), openDocument streams through a reliable pipe with a 64 MiB cap. |
| `NfsScanner.kt` | Discovery: expands the current IPv4 subnet into candidates (`addresses`, capped to the baseIp's /24, network+broadcast excluded) and probes TCP 2049 in parallel (`scan`); `currentSubnet` reads the active network's IPv4 LinkAddress. |
| `MountsParser.kt` / `RootMount.kt` | `/proc/mounts` line parser; one Mount button behind a three-rung ladder driven through `su --mount-master -c` (nsenter fallback): kernel `vers=4.2`, kernel `vers=3`, then the FUSE daemon. Each rung best-effort `modprobe`s its filesystem modules first, since Android kernels usually ship nfs/fuse as modules `/proc/filesystems` does not list until loaded. State and the reported fs type come from /proc/1/mounts so the check matches the global namespace the mount landed in, and `nfs`, `nfs4` and `fuse` all count as mounted — the `/proc/filesystems` lookup keeps its own set so a FUSE-only kernel never looks NFS-capable. A ladder that runs out is classified into `MountDiagnosis` (`NO_ROOT`, `KERNEL_LACKS_FUSE`, `MODULE_FILES_PRESENT`, `FUSE_DAEMON_FAILED`, `GENERIC`) from the exit code, stderr, `/proc/filesystems`, an `ls | grep` over `/vendor/lib/modules` and `/system/lib/modules` (module file present but nothing registered ⇒ `MODULE_FILES_PRESENT`) and the daemon's own log line; `MainActivity` maps that to a string resource. |
| `Fuse.kt` | The slice of the FUSE kernel ABI (`linux/fuse.h`) the bridge speaks: opcode numbers, struct sizes and field offsets as constants against a frozen ABI, errno values, `align8`, the `reply` header writer (errno passed positive, stored negated, refusals never carrying a payload) and `refusal`, which decides EROFS / EINVAL / ENOSYS per opcode. |
| `FuseDevice.kt` | The mounted `/dev/fuse` connection as two unbuffered streams over one descriptor. Deliberately not closeable: the descriptor's owner is whoever adopted it. One syscall per message is mandatory, since FUSE frames requests and replies by syscall boundary. |
| `FuseNodes.kt` | `NodeTable` — the nodeid <-> path map, the only place a path exists once mounted, with allocation (`intern`) separated from kernel referencing (`lookedUp`/`countLookup`/`forget`) so a plain READDIR can reuse nodeids as inode numbers without leaking them. `DirRow` + `Dirents.pack` build `fuse_dirent`/`fuse_direntplus` pages: 8-byte-aligned records, cookies one past each row, zeroed padding, and the entry prefix on every record in READDIRPLUS mode. |
| `FuseNfsDaemon.kt` | The opcode loop. Negotiates FUSE_INIT (512 KiB reads via FUSE_MAX_PAGES, READDIRPLUS, 5 s attr/entry TTL), serves LOOKUP/GETATTR/OPENDIR/READDIR/READDIRPLUS/RELEASEDIR/OPEN/READ/RELEASE/STATFS/ACCESS/FORGET/BATCH_FORGET/FLUSH/FSYNC/DESTROY out of an `NfsSession`, refuses the rest per `Fuse.refusal`. READ refills short server replies, because a short FUSE reply means end of file and would zero-fill the caller's page. Several workers read the device concurrently. |
| `FuseDaemon.kt` | Entry point `app.mammon.FuseDaemonKt.main(<fd> <host> <port> <export>)`, started by `RootMount`'s root shell under `app_process`. No Android context exists here. Checks that `/proc/self/fd/<fd>` really is `/dev/fuse` before adopting it with `ParcelFileDescriptor.adoptFd`, then serves until FUSE_DESTROY. Kept from R8 by an explicit rule in `app/proguard-rules.pro`, since nothing in the dex references it. |

Unit tests (`app/src/test/kotlin/app/mammon/`, JUnit4, no Robolectric): `PathCodecTest`,
`ExportSpecTest`, `MountsParserTest`, `RootMountNamespaceTest`, `RootMountDiagnosisTest`,
`NfsListFilterTest`, `NfsListNullAttrsFallbackTest`, `ManifestGuardTest`, `ModuleInstallTest`,
`NfsScannerTest`, `Fattr4CodecTest`, `NfsVersionSelectionTest`, `FuseReplyTest`,
`FuseRefusalTest`, `DirentsTest`, `NodeTableTest`.

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
- **Writes** — every mutating FUSE opcode answers EROFS and the provider is read-only,
  so a writable surface is a change to `NfsSession` first, not to either front end.
