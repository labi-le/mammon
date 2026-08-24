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
| `NfsAccess.kt` | NFSv3 session wrapper over `com.emc.ecs:nfs-client` — stat, list, capped read stream. Per-call connect; see class comment for why. |
| `NfsDocumentsProvider.kt` | SAF root for the configured export, read-only; every NFS call bounded (~15 s) via coroutine timeout, openDocument streams through a reliable pipe with a 64 MiB cap. |
| `MountsParser.kt` / `RootMount.kt` | `/proc/mounts` line parser; kernel mounts via `su --mount-master -c` (nsenter fallback), state from /proc/1/mounts so the check matches the global namespace the mount landed in. |

Unit tests (`app/src/test/kotlin/app/mammon/`, JUnit4, no Robolectric): `PathCodecTest`,
`ExportSpecTest`, `MountsParserTest`, `RootMountNamespaceTest`.

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
| `mipmap-anydpi-v26/` | Adaptive launcher icon XMLs referencing the drawable below |
| `drawable/ic_launcher_foreground.xml` | Vector foreground, geometry inside the 72dp safe zone |


## Planned — future, nothing exists yet

Direction C is picked (see `docs/guides/architecture.md` Status); what remains open:

- **Mount service** — foreground service owning a kernel mount session
  (easysshfs-style); today `RootMount` runs one-shot from the activity only.
- **Boot receiver** — restore configured mounts after `BOOT_COMPLETED`.
