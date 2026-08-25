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
| `MainActivity.kt` | Two-card UI (SAF config + root mount), classic Views over `R.layout.activity_main`; saves `Prefs`, probes the share, fires ACTION_VIEW on the provider root, drives Mount/Unmount off the main thread. |
| `Prefs.kt` | SharedPreferences: host, export, port (default 2049), last mountpoint; `spec()` re-parses into an `ExportSpec`. |
| `ExportSpec.kt` | Parses "host[:port]:/export"; IPv6 literals bracketed; port range and traversal rejected. |
| `PathCodec.kt` | documentId <-> export-absolute path; rejects "..", empty segments, leading/trailing slashes. Every provider id passes through it. |
| `NfsAccess.kt` | NFSv3 operations over `com.emc.ecs:nfs-client` — stat, list (one READDIRPLUS loop carrying child attributes; symlinks and special files are not listed), capped read stream. One long-lived session per config; the provider rebuilds it when the config changes. |
| `NfsDocumentsProvider.kt` | SAF root for the configured export, read-only; every NFS call bounded (~15 s) via coroutine timeout; one long-lived connection per config (`nfsInstance`), openDocument streams through a reliable pipe with a 64 MiB cap. |
| `MountsParser.kt` / `RootMount.kt` | `/proc/mounts` line parser; kernel mounts via `su --mount-master -c` (nsenter fallback), state from /proc/1/mounts so the check matches the global namespace the mount landed in. |

Unit tests (`app/src/test/kotlin/app/mammon/`, JUnit4, no Robolectric): `PathCodecTest`,
`ExportSpecTest`, `MountsParserTest`, `RootMountNamespaceTest`, `NfsListFilterTest`.

No services or receivers exist yet.

- Permission: `android.permission.INTERNET` (only).
- `application`: `allowBackup=false`, theme `Theme.Mammon`.
- One exported activity `MainActivity` with the `MAIN`/`LAUNCHER` intent filter.
- One exported DocumentsProvider `app.mammon.nfs` (`NfsDocumentsProvider`),
  `grantUriPermissions=true`, `DOCUMENTS_PROVIDER` intent filter.
- No foreground service, no boot receiver.

### Resources (`app/src/main/res/`)

| Path | Purpose |
|---|---|
| `values/strings.xml` | UI strings and NFS error messages |
| `layout/activity_main.xml` | The whole MainActivity layout: SAF card + root mount card in one scroll view |
| `values/themes.xml` | `Theme.Mammon`, based on `Theme.Material3.DayNight.NoActionBar` |
| `values/colors.xml` | `ic_launcher_background` |
| `values-night/colors.xml` | Dark palette overrides for the M3 DayNight theme |
| `mipmap-anydpi-v26/` | Adaptive launcher icon XMLs referencing the drawable below |
| `drawable/ic_launcher_foreground.xml` | Vector foreground, geometry inside the 72dp safe zone |


## Planned — future, nothing exists yet

Direction C is picked (see `docs/guides/architecture.md` Status); what remains open:

- **Mount service** — foreground service owning a kernel mount session
  (easysshfs-style); today `RootMount` runs one-shot from the activity only.
- **Boot receiver** — restore configured mounts after `BOOT_COMPLETED`.
