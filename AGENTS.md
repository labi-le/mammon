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
| `app/src/main/java/com/emc/ecs/nfsclient/network/` | Two vendored classes, in their upstream package so they shadow the library's: `RecordMarkingUtil`, Apache-2.0 text with the record-mark arithmetic corrected and the reassembly's output offset tracked rather than left to `Xdr.skip` to pad, and `RPCRecordDecoder`, the same text holding each fragment's bytes itself instead of trusting netty's cumulation buffer to still hold them, under a 2 MiB record cap. AGP compiles this root beside the Kotlin one with no source-set configuration; `stripShadowedNfsClient` in `app/build.gradle.kts` removes both copies from the jar and pins both by SHA-256 |
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
  configured export to any file manager, readable and writable on either backend —
  create, delete and write-mode `openDocument` through
  `StorageManager.openProxyFileDescriptor`, so a server refusal reaches the writer's
  own `write(2)` instead of a log line after its fd is gone. The v4.1 session also
  survives an idle period: the connection is rebuilt transparently on the next call,
  and that recovery never re-sends a create, MKDIR or
  REMOVE, so a mutation is reported failed rather than silently performed twice — the one
  case measured end to end. A server restart rebuilds the connection the same way, but
  only metadata comes back with it: the restarted server answers READ and WRITE with
  `NFS4ERR_GRACE` for its grace period (90 s by default on Linux nfsd), and that status
  buys a single retry 500 ms later, so reads and writes keep failing until grace
  lifts. Row flags are optimistic by design and the exception a
  mutation throws is the real contract; rename is the one operation withheld, since the
  seam has no RENAME. Two protocol versions sit behind the
  `NfsSession` interface and are chosen per export with no UI switch — NFSv4.1 over
  `org.dcache:nfs4j-core`/`oncrpc4j-core` first, NFSv3 over `com.emc.ecs:nfs-client`
  as the fallback for servers that still run rpcbind and mountd. Both backends implement
  the mutating half; what parts them is mechanism. Neither re-sends a mutation a dead
  connection swallowed — v4.1 narrows its recovery to statuses that prove the operation
  never ran, and v3, stateless with no session slot and no server-side replay cache to
  catch a duplicate, sends CREATE, MKDIR, REMOVE and RMDIR through the library's one-shot
  calls so a lost one is reported failed rather than risked twice. What v4.1 has and v3
  does not is recovery for the calls it may repeat: a session re-established in place, a
  transport rebuilt mid-operation, where v3 has only the library's retry count. v3 also
  walks the path with one LOOKUP per component ahead of the operation, where v4.1 fuses
  the walk, the operation and its attribute read-back into a single COMPOUND — but it
  walks a directory once and not once per call, since the session both writes and READS a
  cache of the 256 most recently used filehandles, so what a cached path still pays is
  the one LOOKUP a caller wanting attributes off the wire spends on the leaf; an entry
  answers absent past 5 s, which bounds how long a rename elsewhere can leave a handle
  denoting the wrong object, and a path is dropped together with everything under it when
  the server rejects a handle or a removal there succeeds; its listing falls back from
  READDIRPLUS — optional in RFC 1813, and refused outright by real servers such as
  unfs3 — to READDIR plus a LOOKUP per child, latched for the session so the refusal
  costs one call and not one per directory. A v3 READ is sized off FSINFO `rtmax` capped
  at 512 KiB, the way a v3 WRITE is already sized off `wtmax`, and the listing page
  counts are tuning numbers, because the two library defects that used to bound them are
  repaired here rather than avoided: `RecordMarkingUtil` advanced its cursor by a
  fragment's payload size and not by the four-byte record mark plus the payload, so it
  read every fragment after the first four bytes early, and `RPCRecordDecoder` — whose
  arithmetic is right — returned from `decode` with netty's cumulation buffer advanced
  past a fragment it had consumed, which `FrameDecoder` then discarded, so the rewind over
  the finished record threw and killed the connection. A third defect came out of
  reviewing the reassembly repair and is reported by nobody upstream: it copied each
  fragment through `Xdr.putBytes`, whose closing `skip` rounds the destination offset up
  to a four-byte XDR block, so a non-last fragment whose payload is not a multiple of
  four left one to three zero bytes at the seam and over-counted the record, with no
  exception and no short read — unreachable only because every server measured here
  sends 4-aligned fragments, which RFC 1831 does not require. The app now shadows both
  classes, under `app/src/main/java/com/emc/ecs/nfsclient/network/` with upstream text
  and the minimum change each needs, and strips both from the dependency jar at build
  time so D8 links one definition of each; the strip pins the stripped entries by
  SHA-256, so an upstream repair fails the build instead of being reverted by our copy.
  The decoder now holds every fragment until the last one, where upstream held about
  one, so it also bounds a record at `MAX_RECORD_LENGTH` (2 MiB, four times the 512 KiB
  read ceiling) and fails past it with `TooLongFrameException`. The guide says
  why two classes and not the whole library. The two rows built without a session, the
  SAF root and the export root itself, carry create because answering them costs no round
  trip precisely by not asking a backend; now that both backends write, what they
  advertise is what a save into the export root does.
  AUTH_SYS sends a configured identity (uid, gid, supplementary gids), because
  root_squash — the export default — maps uid 0 to nobody and refuses every write.
  There is no "allow writes" toggle and never will be: writability is a property of
  the server, not of a setting.
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
  set-mtime, mkdir, unlink and rmdir — on either backend, since both implement the
  mutating half; what an export mounted `ro` or a squashed identity refuses stays
  refused. Files carry uid/gid 0 and synthesised `0755`/`0644` modes, and rename is
  never served. The daemon is proven against a real export on a Linux host, and the
  end-to-end root chain on a phone is verified since v0.6.6 on one real device
  (Android 16, KernelSU-Next) — see
  the guide's Verification status before treating it as generally working.

Out of scope so far: RENAME at any layer,
Kerberos/RPCSEC_GSS, pNFS layouts, NFSv4 delegations and byte-range locks, foreground
services, boot receivers, caching layers. The `NfsSession` seam is writable, both the
NFSv4.1 and the NFSv3 backend implement it (create, write, setattr, remove, mkdir) and
both front ends now consume it. Boot-time automount of the saved
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

**And a clean source read is not one either.** The NFSv3 record-marking repair was
agreed with `RPCRecordDecoder` exempted from shadowing, because its accumulation
arithmetic reads as correct — and it is correct. The class was broken anyway: upstream's
`decode` returned with the cumulation buffer's reader index advanced past a consumed
fragment, netty-3's `FrameDecoder` discards exactly those bytes, and the last fragment's
rewind over the whole record therefore went below zero and threw. One
rig run disproved the exemption: a single READ for 524288 bytes, the server answering
five fragments `[65532, 65532, 65532, 65532, 45200]`, and the app reading back ZERO bytes
with the connection closed under it. A defect that lives in a class's interaction with
its framework cannot be seen by reading that class, however carefully; only running it
shows it. Read a verdict reached by reading as a hypothesis, and measure it.

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
