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
| `MainActivity.kt` | The whole UI today: an `AppCompatActivity` that builds a centered `TextView` ("mammon") programmatically. No layout XML. Placeholder — no mounting logic. |

No services, receivers, providers, or tests exist yet.

### Manifest (`app/src/main/AndroidManifest.xml`)

- Permission: `android.permission.INTERNET` (only).
- `application`: `allowBackup=false`, theme `Theme.Mammon`.
- One exported activity `MainActivity` with the `MAIN`/`LAUNCHER` intent filter.
- No foreground service, no boot receiver.

### Resources (`app/src/main/res/`)

| Path | Purpose |
|---|---|
| `values/strings.xml` | `app_name` = Mammon |
| `values/themes.xml` | `Theme.Mammon`, based on `Theme.Material3.DayNight.NoActionBar` |
| `values/colors.xml` | `ic_launcher_background` |
| `mipmap-anydpi-v26/` | Adaptive launcher icon XMLs referencing the drawable below |
| `drawable/ic_launcher_foreground.xml` | Vector foreground, geometry inside the 72dp safe zone |

## Planned — undecided, nothing exists yet

The mount design is not chosen; see
[`docs/guides/architecture.md`](./docs/guides/architecture.md) for the three directions
and their blockers. If/when components appear, they slot in here:

- **Mount service** — foreground service owning the mount session; type and permission
  set depend on the direction picked.
- **Boot receiver** — restore configured mounts after `BOOT_COMPLETED`
  (easysshfs-style).
- **DocumentsProvider** — only under the rootless direction; SAF-visible share instead
  of a real mount.
