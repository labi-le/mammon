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
| `app/src/main/kotlin/app/mammon/` | Kotlin sources: activity UI, SAF documents provider, NFSv4.1 and NFSv3 session implementations behind one interface, root-mount plumbing, prefs/spec parsing |
| `app/src/main/res/` | Resources: M3 theme (`Theme.Mammon`), strings, adaptive launcher icons |
| `app/build.gradle.kts` | Module build config: `applicationId app.mammon`, minSdk 26, compile/target SDK 35 |
| `gradle/wrapper/` | Gradle wrapper (8.14.4); `gradlew` is the entry point |
| `shell.nix` | Dev shell: JDK 17 + Android SDK; every command runs through it |
| `.github/workflows/` | CI: build + release on `v*` tags, dependabot (gradle), stale-issue handling |
| `AGENTS.md` | This file — orientation and mandatory workflow summary |
| `routes.md` | Per-package reference for the `:app` module |
| `docs/guides/` | Guides loaded when their trigger fires (see table below) |

## Current intent

mammon is an Android app for reading NFS storage on a device, inspired by
[bobrofon/easysshfs](https://github.com/bobrofon/easysshfs). v0.4.0 implements two of
the three directions from [`docs/guides/architecture.md`](./docs/guides/architecture.md):

- **Primary — rootless SAF browsing** (direction C): `NfsDocumentsProvider` exposes the
  configured export to any file manager. Two protocol versions sit behind the
  `NfsSession` interface and are chosen per export with no UI switch — NFSv4.1 over
  `org.dcache:nfs4j-core`/`oncrpc4j-core` first, NFSv3 over `com.emc.ecs:nfs-client`
  as the fallback for servers that still run rpcbind and mountd.
- **Root option — kernel mount** (direction A, best-effort): `RootMount` runs
  `mount -t nfs` through `su --mount-master`, trying `vers=4.2` then `vers=3`; a failure
  is separated into no usable `su`, a kernel without NFS, a kernel whose module for the
  tried versions is not loaded, and everything else. Direction B stays documented as
  future work.

Out of scope so far: provider-side writes/rename/delete, Kerberos/RPCSEC_GSS, pNFS
layouts, NFSv4 delegations and byte-range locks, foreground services, boot receivers,
automount-on-boot, caching layers.

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
