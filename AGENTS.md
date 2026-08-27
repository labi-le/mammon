# Agent Instructions

## Running commands

Always run project commands via `nix-shell` — the toolchain (JDK 17, Android SDK 35,
Gradle wrapper) is defined in `shell.nix`. Running tools directly may use different
versions or fail.

```sh
nix-shell --run './gradlew :app:assembleDebug'
nix-shell --run './gradlew :app:lintDebug'
```

## Project map

| Path | Purpose |
|---|---|
| `settings.gradle.kts` | Gradle settings; registers the `:app` module |
| `build.gradle.kts` | Root build script; plugin versions |
| `app/` | The single module: application code, manifest, resources |
| `app/src/main/kotlin/app/mammon/` | Kotlin sources: activity UI, SAF documents provider, NFSv4.1 and NFSv3 session implementations behind one interface, the FUSE daemon serving that same interface, root-mount plumbing, prefs/spec/identity parsing |
| `app/src/main/res/` | Resources: M3 theme (`Theme.Mammon`), strings, adaptive launcher icons |
| `app/build.gradle.kts` | Module build config: `applicationId app.mammon`, minSdk 26, compile/target SDK 35 |
| `gradle/wrapper/` | Gradle wrapper (8.14.4); `gradlew` is the entry point |
| `shell.nix` | Dev shell: JDK 17 + Android SDK; every command runs through it |
| `.github/workflows/` | CI: build + release on `v*` tags, dependabot (gradle), stale-issue handling |
| `magisk-module/` | Standalone Magisk module (`mammon_fsloader`): loads fuse/nfs kernel modules at boot, logs to `load.log`; packaged separately as `mammon-fsloader-*.zip`, and into the app as the `mammon-module.zip` asset by `packModuleZip` in `app/build.gradle.kts` (the Install-module button hands that zip to Magisk) |
| `AGENTS.md` | This file — orientation and mandatory workflow summary |
| `routes.md` | Per-package reference for the `:app` module |
| `docs/guides/` | Guides loaded when their trigger fires (see table below) |

## Current intent

mammon is an Android app for reading and writing NFS storage on a device, inspired by
[bobrofon/easysshfs](https://github.com/bobrofon/easysshfs). v0.5.0 implements all three
directions from [`docs/guides/architecture.md`](./docs/guides/architecture.md):

- **Primary — rootless SAF access** (direction C): `NfsDocumentsProvider` exposes the
  configured export to any file manager, readable and, where the backend implements it
  (which means an NFSv4.1 server), writable — create, delete and
  write-mode `openDocument` through `StorageManager.openProxyFileDescriptor`, so a
  server refusal reaches the writer's own `write(2)` instead of a log line after its fd
  is gone. Row flags are optimistic by design and the exception a mutation throws is the
  real contract; rename is the one operation withheld, since the seam has no RENAME. Two
  protocol versions sit behind the
  `NfsSession` interface and are chosen per export with no UI switch — NFSv4.1 over
  `org.dcache:nfs4j-core`/`oncrpc4j-core` first, NFSv3 over `com.emc.ecs:nfs-client`
  as the fallback for servers that still run rpcbind and mountd. Against an NFSv3-only
  export every child row advertises no writes — but the two rows built without a session,
  the SAF root and the export root itself, still carry create, because answering them
  costs no round trip precisely by not asking a backend. A save into the export root
  therefore survives the picker and fails with Unsupported. AUTH_SYS sends a
  configured identity (uid, gid, supplementary gids), because root_squash — the export
  default — maps uid 0 to nobody and refuses every write. There is no "allow writes"
  toggle and never will be: writability is a property of the server, not of a setting.
- **Root option — one Mount button, three rungs** (directions A and B): `RootMount` tries
  kernel `mount -t nfs -o vers=4.2` through `su --mount-master`, then `vers=3`, then a
  pure-Kotlin FUSE daemon serving the same `NfsSession` the provider uses. A root shell
  opens `/dev/fuse` and calls `mount(2)`, so no native code and no bundled binary ship.
  The status line names which backing landed. Each rung best-effort modprobes its
  filesystem's modules first, since Android kernels usually build nfs/fuse as
  loadable modules that /proc/filesystems does not list until loaded. A run that
  exhausts the ladder is separated into no usable `su`, a kernel that cannot give us
  FUSE, module files present but nothing registered, a FUSE mount whose daemon never
  served, and everything else. The FUSE view carries writes — create, write, truncate,
  set-mtime, mkdir, unlink and rmdir — when the backend implements them, which means an
  NFSv4.1 server; against an NFSv3-only export every mutation answers EROFS. Files carry
  uid/gid 0 and synthesised `0755`/`0644` modes, and rename is never served. The daemon
  is proven against a real export on a Linux host, and the end-to-end root chain on a
  phone is verified since v0.6.6 on one real device (Android 16, KernelSU-Next) — see
  the guide's Verification status before treating it as generally working.

Out of scope so far: RENAME at any layer,
Kerberos/RPCSEC_GSS, pNFS layouts, NFSv4 delegations and byte-range locks, foreground
services, boot receivers, caching layers. The `NfsSession` seam is writable, the NFSv4.1
backend implements it (create, write, setattr, remove, mkdir) and both front ends now
consume it. Boot-time automount of the saved
share is owned by the companion module (v1.2, off by default behind a flag file), not
the app.

## Verification expectations

For any change, run:

```sh
nix-instantiate --parse shell.nix
nix-shell --run './gradlew :app:assembleDebug'
nix-shell --run './gradlew :app:lintDebug'
```

Once unit tests exist under `app/src/test/`, also run:

```sh
nix-shell --run './gradlew :app:testDebugUnitTest'
```

**A green suite is not a review result.** It proves the behaviors someone thought to
exercise, nothing more. What a review must do instead is in
[`docs/guides/workflow.md`](./docs/guides/workflow.md) — read it before your first edit;
the workflow section below is the summary, not the argument.

If an LSP server is unavailable, explicitly report that limitation.

## Workflow — mandatory, not advisory

Every change to production code, to a contract (a public API surface, a manifest
declaration, a resource other code references, a produced artifact such as an APK), or
to a document agents act on (`AGENTS.md`, `routes.md`, `README.md`, the guides below)
goes through:

> **implement → review (performance + architecture, as SEPARATE passes) → fix → repeat**

- **Write the numbered agreement down BEFORE the first implement step**, plus what is
  explicitly out of scope. Without it "100% of what was agreed" is unfalsifiable in both
  directions.
- **The loop terminates on two conditions, both required:** a full round returns **zero**
  open findings, and **every numbered point is either implemented or struck by explicit
  agreement**, the strike recorded in the list. Not "the happy path works". Not "tests
  pass".
- **A finding may be REFUTED with evidence, not only fixed.** A wrong finding implemented
  is a regression the process invited. Verify a finding against the code before
  implementing it. When the refutation is itself disputed, it goes to whoever owns the
  numbered list.
- **A green suite is NOT evidence and MUST NEVER be reported as a review result.**
  Mutation is expected: revert the fix, confirm the new test fails, restore.
- **Mutate in a `git archive` export under `/tmp`, never in the shared worktree** —
  concurrent agents are the normal mode here, and a mutation is a deliberately wrong
  tree. Untracked files have no `git diff` to be empty, so there the check is `cmp`
  against a pre-mutation copy.
- **Proportionality: the gate is risk, not diff size.** A docs-only or config-only
  change takes ONE pass, and that pass is the ARCHITECTURE one. The termination
  conditions never relax: a one-line change with an open finding is not done either.
- **Tracked metrics are the binding constraint.** A regression in what this project
  tracks — lint findings (`:app:lintDebug` must pass with ZERO new findings; a plain
  exit-code gate, deliberately no baseline file), APK size once measured, benchmark
  numbers once they exist — is a BLOCKING finding the change must justify and the
  reviewer must accept.
- **Comments and the docs are first-class review targets.** The recurring defect is the
  true-when-written claim. A review that approves the code and ignores its comments — or
  the docs its change falsified — has not finished.

Why each rule exists, and what shipping without it cost elsewhere, is in
[`docs/guides/workflow.md`](./docs/guides/workflow.md).

## Code conventions

Code comments follow one rule: explain a non-obvious constraint or consequence, never
what the next line already says. A stale comment is worse than none — changing behavior
means updating or deleting its comment in the same change.

## Commit convention

Short, lowercase, imperative subjects, no trailing period:

```
add boot receiver
wire mount service
fix icon safe zone
```

Keep the subject to one short line. A scope prefix (`app:`) only when the change really
is confined to one area. Never use Conventional Commits prefixes (`feat:`, `chore:`).
A body is the exception, not the rule: add one only when the diff cannot show *why*,
and keep it to a few lines. Never restate the diff or list touched files.

## Orientation

Before making changes, read [`routes.md`](./routes.md) — it maps the `:app` module as it
exists. After adding, removing, or significantly restructuring a package, or changing
its public surface, update `routes.md` to match.

## Guides — read on demand

This file holds only what applies to every task. Everything else is a guide loaded when
its trigger fires; each states its own trigger at the top.

| Guide | Load it when |
|---|---|
| [`docs/guides/workflow.md`](./docs/guides/workflow.md) | before your FIRST edit to production code, a contract, or a doc agents act on |
| [`docs/guides/architecture.md`](./docs/guides/architecture.md) | anything touching mounting, service/receiver/provider design, permissions, or asking "why is it built this way" |
| [`routes.md`](./routes.md) | orientation: the per-package reference for `:app` — classes, manifest, resources |

Two rules about the guides themselves, both learned the hard way elsewhere:

- **A guide is a surface you FIX, never a source you cite.** Code is the only ground
  truth. If a guide disagrees with the code, the guide is wrong — correct it in the same
  change rather than working around it.
- **Update the guide your change falsified, in that change.** Elsewhere a routes file
  kept pointing at a function a commit had deleted, and survived a dedicated staleness
  pass that rewrote lines around it.
