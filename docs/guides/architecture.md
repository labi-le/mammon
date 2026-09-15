# architecture — mounting NFS on Android

> **When to read this:** before anything touching the mount design, a service,
> receiver, or provider, permissions, or when asking "why is it built this way".
> The Status section records what was chosen; what follows it is the original
> decision input.

## Status

All three directions below are now shipped. Direction C — the rootless
DocumentsProvider — was PICKED on 2026-08-24 and remains the primary way mammon exposes
NFS storage: the configured export shows up in any SAF file manager through
`NfsDocumentsProvider` (authority `app.mammon.nfs`), readable, and writable over either
protocol version where the server permits the write.
Since v0.4.0 that provider speaks two protocol versions behind one `NfsSession`
interface, chosen per export with no UI switch: NFSv4.1 (`NfsV4Access`, over
`org.dcache:nfs4j-core` XDR and `org.dcache:oncrpc4j-core` RPC) is tried first because
it needs only TCP 2049, and NFSv3 (`NfsAccess`, over `com.emc.ecs:nfs-client`) is the
fallback for servers that still publish rpcbind and mountd. An NFSv4-only server —
the common modern default — was invisible to mammon before that.

`NfsSession` gained a mutating half — create, write, setattr, remove, mkdir — that both
backends implement and both front ends consume. What parts them is mechanism: v4.1 fuses
an operation and its attribute read-back into one COMPOUND and can re-establish a
session or rebuild a whole transport under a call it may repeat, while v3 walks the path
with one LOOKUP per component before it can send the operation at all, and, being
stateless — no session slot, no server-side replay cache — puts CREATE, MKDIR, REMOVE
and RMDIR on the wire exactly once, reporting a lost one as failed rather than risking a
second execution. Its listing carries the same asymmetry: READDIRPLUS is optional in RFC
1813 and real servers refuse it, so `NfsAccess` falls back to READDIR plus a LOOKUP per
child, latched for the session so the refusal costs one call and not one per directory.
What the session does bound is the path walk under all of it, with a filehandle cache:
the 256 most recently used handles, access-ordered, read as well as written, so a path's
prefix is resolved per directory and not per call — a mutation whose whole path is
cached sends no LOOKUP of its own, while a caller that came for attributes off the wire,
a `stat` or an open or a plain read-back, still looks the LEAF up every time, so a hot
cache costs it one LOOKUP and not one per component. A READDIRPLUS listing writes it
too — any `post_op_fh3` the reply carries for a child it keeps, files included — so
descending into a directory just listed costs no LOOKUP of its own, and the harvest
stops at the first children that fit the same 256, so a wide listing cannot evict its
own head or the prefix chain the walk just paid for. Entries carry a monotonic stamp and
answer absent past 5 s, which is a correctness bound and not tuning: a filehandle
survives a foreign rename — it stays valid and merely stops denoting the path it is
keyed under — so no server error can expose the binding, and an unbounded entry would
let a rename elsewhere aim a later mutation at the object that moved away. 5 s is the
entry lifetime the FUSE layer above already advertises and the order a kernel NFS client
bounds the same hazard with (`acdirmin`), so the residual is that window and nothing
wider: inside it a mutation can still land on an object renamed away under its path.
Meanwhile the listing fallback's per-child LOOKUP is neither cached nor bounded: on a
server that refuses READDIRPLUS every listing pays one of them for every entry the name
filter accepts, symlinks and special files included, since an entry's type is known only
once its lookup has answered, and the latch spares only the refused call itself. A handle
the server disowns, NFS3ERR_STALE or NFS3ERR_BADHANDLE, drops that path and everything
under it, and so does a successful removal, since a name that is gone vouches for
nothing below it. Building the seam first is what let them land as separate changes
rather than growing two NFS write paths. RENAME is deliberately
absent from the seam, so neither front end offers it.

The seam's single removal is where that split costs something real. NFSv3 has REMOVE and
RMDIR, so the entry's type picks the call, and `remove`'s optional `isDirectory` is that
type when the caller already holds it: under FUSE the opcode is authoritative — the VFS
resolves the type before the daemon is asked, `do_unlinkat` answering EISDIR itself and
`vfs_rmdir` requiring a directory, off the dentry this daemon filled and the kernel
holds for the 5 s entry TTL — so the hint goes down and v3 sends one call, while SAF
hands a documentId alone and v3 pays a LOOKUP to decide. The hint is TRUSTED, not
corrected: the call it picks goes out first, and only a REFUSAL is re-decided, by
re-reading the type and never by reading "wrong procedure" out of the status, because no
status means that — RFC 1813 §3.3.12 lists no NFS3ERR_ISDIR among REMOVE's errors and
its IMPLEMENTATION section permits REMOVE on a directory, so unfs3 answering RMDIR on a
file with NFS3ERR_STALE and deleting a directory through REMOVE are both conformant.
That second half is the residual: a type flip inside the dentry window aims REMOVE at a
directory, a permissive server carries it out and answers NFS3_OK, and no corrective
read follows a success, so nothing here detects the mismatch. It is the trade Linux's
own v3 client makes, picking the procedure off the syscall the same way; buying the
window back would cost a LOOKUP before every unlink, to narrow a hazard the layer
handing the type down already lives inside. A policy refusal — ACCES, PERM, ROFS,
NOTEMPTY — is the one refusal that is not corrected either: it ends the removal where it
stands, because the server has already said it would refuse this identity, this `ro`
export or this non-empty directory, so a second call could only reach an object that
took the name after the user's own deletion was refused, and never sending a destructive
call the server has already declined is the side to be wrong on.

The v3 read path used to carry a correctness bound instead of a tuning knob: a
61440-byte cap on what a READ asked for and on what a READDIR or READDIRPLUS page was
allowed to cost, so that no reply could be split across RPC record fragments. The cap is
gone, because the two defects it existed to avoid are repaired.
`com.emc.ecs:nfs-client` could not read a reply the server had split, and it could not
for two independent reasons — three, counting the one further down that no server
measured here reaches. `RecordMarkingUtil.removeRecordMarking` consumed each
fragment's four-byte record mark and copied the payload, then advanced its cursor by that
payload's size ALONE, so every fragment after the first was taken four bytes early and
the reassembled record was garbage. And `RPCRecordDecoder`, the netty-3 `FrameDecoder`
feeding it, could not assemble a record whose fragments arrived over more than one socket
read: on a non-last fragment it consumed the mark, skipped the payload, accumulated the
length and returned `null` with the reader index ADVANCED, and
`FrameDecoder.updateCumulation` then dropped exactly that consumed prefix, so the last
fragment's `readerIndex(readerIndex() - _recordLength)` rewound over bytes no longer in
the buffer — index below zero, `IndexOutOfBoundsException` into
`ClientIOHandler.exceptionCaught`, connection closed. Either defect reached the caller as
a connection error on a retried xid and killed the transfer. A single-fragment reply was
immune to both, which is why nothing saw either until a reply grew past one fragment.

The second defect is the cautionary one, and how it was finally found is in the
Verification status section below. Its arithmetic is correct — it counts `4 + fragSize`
per fragment, which is exactly right — so a reading of the class cleared it, and the
first round of this repair deliberately left it in the jar on the strength of that
reading. The bug was never in the arithmetic but in what the class assumes about the
framework under it, and reading the class cannot show that.

A third defect came out of reviewing the reassembly repair itself, and no bug report
upstream names it. `Xdr.putBytes` ends in `skip(len)`, which rounds the destination
offset UP to a four-byte XDR block, and upstream's reassembly calls it once per
fragment: a non-last fragment whose payload is not a multiple of four leaves one to
three zero bytes at the seam and over-counts the reassembled record by exactly that
padding. Nothing throws, nothing reads short — the reply body is simply wrong, which is
the worst way for a client to be wrong. It is unreachable against every server measured
here, because their fragments are 4-aligned, and RFC 1831 requires no such alignment, so
this is an alignment dependency to remove rather than to document: the shadow tracks the
output offset itself instead of resting on a property of servers that merely happens to
hold.

All three defects are corrected in this app by SHADOWING two classes. `RecordMarkingUtil`
and `RPCRecordDecoder` are vendored into
`app/src/main/java/com/emc/ecs/nfsclient/network/` — upstream Apache-2.0 text with its
provenance kept, in the upstream package and at the upstream visibility, so each is the
definition the rest of the library links against. The record-marking shadow walks the
caller's array with an input cursor that counts each record mark and writes at an output
offset it tracks itself, so neither the four-byte-early read nor the padded seam survives
and the whole-buffer clone upstream used as a cursor is gone with them; the decoder's
keeps each fragment's bytes with its record mark as the fragment arrives and assembles
them when the last one lands, instead of trusting a buffer the framework is entitled to
compact underneath it. That accumulator is why the decoder also carries a bound upstream
had no need of: upstream held about one fragment, this one holds every fragment of a
record until the last, so a peer that sent non-last fragments forever would grow the
list without limit, and deleting `UNFRAGMENTED_REPLY_MAX` had already removed the last
bound at any other layer. `MAX_RECORD_LENGTH` is 2 MiB — four times the 512 KiB
`NFS_READ_CHUNK` ceiling that bounds the largest reply this app can ask a server for —
and a record past it fails with netty's `TooLongFrameException` rather than an OOM,
which is a refusal the caller sees instead of a process the platform kills. Two definitions
of one class do not resolve to a first-wins — the dex merge fails on D8's
`Type ... is defined multiple times` — so `stripShadowedNfsClient` repacks the resolved
jar without either class and the module compiles against that. The strip fails the build
both when an entry is absent and when its bytes no longer match a pinned SHA-256, and the
second half is the one that matters most: a version bump that renames the class stops the
build, and so does one that REPAIRS it. Presence alone could not catch a repair — a
repaired class is still there to be found — and our copy would go on shadowing it,
reverting somebody else's fix in silence.

The two alternatives are both worse. Living with the bound cost six READs where one
would do, but the real objection is that it could not be right — 61440 was the largest
4 KiB multiple left under libtirpc's 65532-byte fragment flush once a READ reply's 128
bytes of header and up to three of padding were subtracted, which makes it one RPC
implementation's behaviour standing in for every server's. libtirpc and its ntirpc fork
cover unfs3 and nfs-ganesha; the Linux kernel server writes one record marker per reply,
read off its implementation and not measured here; a server flushing at some other size
would have been misread with the bound in place, which is the kind of guess a client
should not be making. Vendoring the library instead would buy 171 classes of maintenance
— its last release was July 2016 — for four bytes of arithmetic, one buffering
assumption and one padded seam. Shadowing costs two of those 171 files, plus a build step
that fails loudly when upstream touches either of them. How that figure moved is part of
the case and not a footnote to it: the technique was argued for on four bytes of
arithmetic in one class, and each round of work since has found the next thing wrong in
the same path — a second class, a third defect nobody had reported, and a ceiling of our
own to keep the fix from trading one failure for another. Owning a class means owning
everything wrong with it, including what is not yet known, and a reader weighing
shadowing against vendoring or against living with the bound should weigh that growth
with the two files. Making the dependency arrive as a file carries one
further price: the three runtime dependencies the POM declares no longer come in
transitively and are declared here instead, and two of them are NOT at the POM's
versions. `commons-lang3` is 3.20.0 against the POM's 3.12.0 and `slf4j-api` is 2.0.19
against its 1.7.36, because writing the coordinates down is what put them in lint's view,
and this project's lint gate allows zero new findings, so the versions the POM names
could not stay. netty stays at the POM's 3.10.6.Final: that is the API the library is
compiled against.

Read sizing becomes what write sizing already was: `readChunk()` mirrors `writeChunk()`,
taking FSINFO `rtmax` clamped by `NFS_READ_CHUNK` (512 KiB), caching it for the session
and falling back to a fixed ceiling when FSINFO fails, so the server states the size
instead of the app assuming one. Raising `NFS_READ_CHUNK` is no longer a one-line change:
the decoder refuses any record past `MAX_RECORD_LENGTH`, so a read ceiling raised toward
2 MiB spends the factor of four this cap was chosen to keep, and one at or past 2 MiB
fails the unit suite before it can fail every full-size reply, `RPCRecordDecoderTest`
asserting the two constants against each other for that reason. The READDIR and
READDIRPLUS page counts stay where they
were, now as tuning numbers carrying no correctness claim. The write direction never
needed a bound — the library's own record marking is correct, and it splits only above
its 1 MiB MTU — and v4.1 is untouched.

On the SAF side the consequence worth knowing is that a row's `FLAG_*` is advisory by
specification: deriving it honestly would need an NFSv4 ACCESS per listed child, which
is one extra operation in an existing COMPOUND on v4 but a separate RPC per child on v3
— the storm `NfsAccess`'s READDIRPLUS loop avoids, and pays anyway on a server that
refuses READDIRPLUS — and the RFC calls the answer advisory anyway. So flags are
optimistic, gated on `implementsWrites` and nothing else, which both backends now set,
and the exception each mutation throws is the contract: `SafContract.kt` maps
every `NfsFailure` case onto `FileNotFoundException` with a message a user can act on —
the exception the write methods declare, and the only refusal the system UI renders,
since DocumentsUI drops an `UnsupportedOperationException` from New folder and from SAVE
without telling the user anything. `Unsupported` sits in that set and no longer means
the app cannot do it: it means the operation is not available here, and the common case
is now a server refusing a procedure the specification lets it refuse, which the FUSE
side answers with ENOSYS rather than EROFS. Returning normally from a refused mutation
is the one outcome that must never happen, because the system UI updates its model
optimistically on a clean return. A server refusal is not the only way a write-mode open
fails, so
`proxyOpenOutcome` covers the other one: where the framework cannot give this app the
proxy descriptor at all, its `IllegalStateException` becomes the declared
`FileNotFoundException` with a message about the DEVICE, keeping the original as the
cause. Only that one type is translated — a bug of ours in that open path keeps its
type, since a bug reading as a file error is a bug nobody sees. The proxy callback
cannot be caught there at all: it runs on its own looper.

Writing forced a decision reads never did. AUTH_SYS carried a hardcoded uid 0, which is
all a reader needs — `root_squash` is on by default on Linux exports, and a squashed
reader can still read anything world-readable. A squashed writer can write nothing, so
the identity became configuration: `AuthIdentity` is a real triple (uid, gid,
supplementary gids), settable in the SAF card and persisted with host, export and port.
Hardcoding a different number would be exactly as wrong, since the owning account
differs per server, and the supplementary list is load-bearing rather than cosmetic — a
tree owned by a secondary group is reachable only through it, so the credential encodes
the whole `gids<16>` vector RFC 5531 allows instead of one copy of the primary gid.

Directions A and B both sit behind the single Mount button on the root card. Since
v0.5.0 `RootMount` walks one three-rung ladder and stops at the first rung that answers:

1. kernel NFS via `mount -t nfs -o vers=4.2` under `su --mount-master` (direction A);
2. the same kernel mount with `vers=3`, mirroring the provider's v4-first order;
3. a pure-Kotlin FUSE daemon serving the export out of the same `NfsSession` the SAF
   provider uses (direction B).

The status line names which backing landed, because the three are not interchangeable:
the kernel rungs give a full POSIX mount carrying the server's own ownership and
permission bits, while the FUSE rung gives a view with synthesised metadata whose writes
the server alone accepts or refuses (see below). Rungs 1 and 2 work only on devices whose
kernel has NFS support and whose su setup lets the mount land in the global namespace;
rung 3 needs no NFS support in the kernel at all, only `/dev/fuse` and root — that is
the whole reason it exists. One NFS implementation now backs both surfaces: the daemon
carries no second client.

Ahead of the ladder sits one decision that is not a rung: WHERE a mount inside shared
storage has to be made. `/storage/emulated` — the path every app means by shared storage
— is normally a SLAVE of its propagation peer group, so it receives mounts and sends
none: a mount made there is visible only to the namespace that made it, which is why
mammon used to refuse that whole tree outright. But the same fuse device is also mounted
at that group's shared MASTER, `/mnt/user/<u>/emulated` on a stock tree, and the kernel
replicates a mount made there into every slave in the group. That is not a trick found
at the edges: it is how vold's own `Android/data` and `Android/obb` mounts get INSIDE
the emulated tree, so an ext4 mount below the emulated view is stock Android rather than
something new — and also why `Android/` is refused by name: vold mounts there already,
and two owners for one subtree is not a fight worth having. The tree root itself is
refused for the plainer reason that mounting over all of shared storage would hide every
app's files. `EmulatedMount` therefore derives the master from `/proc/1/mountinfo` per
device — never a literal, since that path varies by OEM, by user profile and by release
— mounts there, and reports the `/storage/emulated/<u>/<name>` the user can actually
open. That `<u>` is COPIED out of the request, never interpreted:
`EmulatedMount.recognize` gates the component with `mediaId.any { it !in '0'..'9' }` and
then keeps it VERBATIM, because `mammon_route_recognize` gates the same component with
`case $mid in '' | *[!0-9]*)` and re-emits it unchanged. The class is spelled `'0'..'9'`
rather than `Char.isDigit` deliberately, since that predicate spans Unicode digits the
shell's `[!0-9]` refuses. Parsing the component to an `Int`, which the app did until
this round, made ONE saved pref mean two different mounts on one phone:
`/storage/emulated/00/nfs` routed to `.../0/nfs` from the button and to `.../00/nfs` at
boot, `010` retargeted the app into work-profile 10's storage while the module stayed on
`010`, and `2147483648` overflowed `toIntOrNull` to null, so the app refused a path the
module mounted. The `<u>` of the MASTER path is a different component again — it comes
from the mountinfo line, not from the request — so `/storage/emulated/00/nfs` mounts at
`/mnt/user/0/emulated/00/nfs`. Unmount goes to the master path for the same one-way
reason: a slave does not propagate to its master, so a `umount` inside the `/storage`
view would leave the real mount standing. A path the routing REFUSES is unmounted
literally instead, and that refusal is decided before any su is spawned, so it knows
nothing about what is mounted there: a mount some other entrance left at that path can
still be standing (`RootMount.unmount`). Among the propagation tags the
`propagate_from:` group is tried FIRST and `master:` only as a fallback, never as one
set: `master:` names the IMMEDIATE master's group, which `get_dominating_id` may leave
with no line visible under the reading root at all, while `propagate_from:` names the
nearest master group that is reachable — keying on `master:` alone would refuse on a
device where the route works. Trying them in that order also takes the choice away from
mountinfo line order, which must decide nothing (`EmulatedMount.mountSite`;
`mammon_emulated_master` in `magisk-module/fslib.sh`).

A view carrying a bare `shared:N` and neither tag above it is mounted WHERE IT STANDS
instead (the `MountSite.InPlace` branch of `EmulatedMount.mountSite`; the bare-`shared:`
tail of `mammon_emulated_master`, which prints `/storage/emulated` itself): it is a
sender in group N, so a mount made in `/storage/emulated` itself reaches every peer and
slave of that group. The app tries there rather than refusing because the two answers
are not symmetric — the mount is verified at the app-visible path afterwards and
withdrawn when it did not propagate, so a wrong attempt corrects itself, while a refusal
is terminal and tells the user his phone cannot do something it can. A line carrying
`shared:N` AND `master:M` still takes the master route, deliberately: it sends into N
but receives from M, and a mount made in place would miss everything M feeds. When the
master route finds no candidate the answer is the refusal below, never a fall back to in
place.

Three verdicts come out of this seam and each says something different;
`RootMount.MountDiagnosis` carries the whole set they belong to. A view with no shared
peer is `EMULATED_NO_SHARED_PEER` — no path exists where a mount would become visible,
so there is nothing to try. A mount that landed on the master and never appeared in the
view is torn down through the same su ladder that made it and reported as
`EMULATED_NOT_PROPAGATED` (`RootMount.mounted`): it is NOT left standing with the user
sent to a root shell, which is what the app did and what this guide said. The reason is
not that the path is out of reach — the failure message names the master path and the UI
prints it, and Unmount reaches it, because `RootMount.unmount` resolves the saved
mountpoint through the same `resolveTarget` and umounts what it routes to. The reason is
that the run FAILED, and a failed run that leaves mammon's own mount up — with, on the
FUSE rung, a daemon that only a `umount` stops — bills the user for a path he never got.
A teardown that itself failed then needs its own value, `EMULATED_NOT_PROPAGATED_STUCK`,
and its own message naming the Unmount button, which is honest advice precisely because
that button routes to the same master path and really can clear it — "so it was removed
again" is a claim, and claiming a removal we did not achieve is exactly the
true-when-written defect this project keeps producing. One enum value buys a sentence
that stays true when `umount` loses. The consequence for the shape of the app is that
the root card is no longer a root-only curiosity: a mount it makes now lands where any
file manager can open it, which before was the SAF provider's sole claim. Read
"Verification status" below before trusting any of this on a device: none of it has
executed on Android, and the mount table the derivation was read off came from a
waydroid Android 13 instance with SELinux disabled.

The mountpoint directory for such a mount is created in the LOWER tree,
`/data/media/<u>/<name>`, not through the view. Going through the view would also work —
MediaProvider skips its own permission check for uid 0 and root passes
(`MediaProviderWrapper.cpp:53-55`, `FuseDaemon.cpp:810-812`, AOSP `android-15.0.0_r1`) —
but it forces mode 0775 on what it creates (`FuseDaemon.cpp:1247`), needs the
MediaProvider daemon already up, and lives in a Mainline module that updates out of step
with the platform. Creating the directory below the daemon has none of those costs and
none in latency either: MediaProvider answers a LOOKUP by `lstat(2)` on the lower path
and returns for a directory before it consults JNI or its database
(`FuseDaemon.cpp:552-556`, `577-581`), a miss installs no negative dentry to invalidate
(`FuseDaemon.cpp:957-961`), and readdir falls back to the lower filesystem
(`MediaProviderWrapper.cpp:381-393`). The name is therefore there for the mount
immediately, which is why no scan trigger, no MediaStore poke and no retry loop appears
anywhere in this path — anything of that kind would be cargo.

A failure that exhausts the ladder is separated into the causes that need different
fixes: no usable `su`; a kernel that cannot give us FUSE at all (`/dev/fuse` would not
open, or `/proc/filesystems` provably has no `fuse` line, so no rung is left); a FUSE mount the
kernel accepted whose daemon then failed to serve; and everything else.
The `/proc/filesystems` half of that distinction is read in the same root context as
the mount itself (the scripts dump it after the mounts), because the unprivileged app
process can be denied the same read; when even the root read comes back empty the
verdict degrades to "unknown", never to "unsupported". The three emulated verdicts above
sit outside this split by construction: one is decided before the ladder is entered and
the other two after a rung already succeeded, so none of them is ever inferred from an
exhausted ladder.

Since v0.5.1 every rung first tries to load its filesystem's modules
(`modprobe nfs`, `nfsv3`, `nfsv4` on the kernel rungs; `modprobe fuse` on the FUSE
rung), best effort and silent: Android kernels usually build nfs/fuse as loadable
modules that nothing registers until asked, and `/proc/filesystems` only lists what is
registered, so an unloaded-but-available module used to read exactly like missing
support. If the ladder still exhausts with none of nfs/nfs4/fuse registered, the
module dirs decide between two verdicts: a candidate file under
`/vendor/lib/modules` or `/system/lib/modules` (found by one `ls | grep -i` in the same
root call) means the support ships as a module but would not load — its own message,
since the fix differs from every other cause; an empty listing keeps the old
"neither NFS client nor FUSE" wording for kernels that truly lack both.

The app-side preload has two structural limits, which is why the companion
Magisk module `magisk-module/` (`mammon_fsloader`) exists. It runs only when
the user presses Mount, so nothing loaded it at boot, and it shells out to
`modprobe`, whose default search tree `/lib/modules/$(uname -r)` is empty on
stock Android — there is no depmod database, and the `.ko` files actually live
under `/system*/lib*/modules` and `/vendor*/lib*/modules`, reachable only by
`insmod` with a full path. The module instead scans those directories itself
at every boot, `insmod`s each fuse/nfs candidate directly (no dependency
resolution needed when you control the file list), retries failures once
after successes, and appends every step to its own `load.log`. That log is
the definitive oracle for "does this device ship the support as files": it
lists exactly what was found, what loaded or was refused, and what
`/proc/filesystems` registered afterwards.

Since v1.2 the module can also automount the saved share at boot (off by
default behind a flag file): it borrows clifforama/multi-mount's pattern —
config-driven boot mounts with a bounded network wait — but rides the app's
FUSE daemon instead of kernel nfs, so it works on kernels where nfs does not
exist and fuse does.

Its automount carries the same emulated-storage routing as the app, and must: a share
that comes back at boot is exactly the one a user wants to find in a file manager. The
module needs one thing the app does not — patience. MediaProvider serves the emulated
view and starts long after `post-fs-data`, so at `late_start` the master peer can simply
be absent yet; the derivation is retried inside the SAME bounded window the module
already waits for CE storage and the network in (`mammon_route_wait`, spending
`mammon_automount_main`'s own `$i of $tries` budget rather than opening a second one),
never behind a fixed sleep, so a device that is slow to unlock still gets its mount. It
also needs refusals the app can never exercise: the module reads the saved mountpoint
straight out of `shared_prefs`, which the app validates before writing but a hand edit
does not. A `.` or `..` component is therefore rejected in the shell too, by the `*/./*`
and `*/../*` cases in `mammon_route_recognize` (`fslib.sh:380-382`) — without that,
`/sdcard/../../data/local/nfs` would become a root `mkdir -p` and a FUSE mount outside
shared storage on every boot. The saved spelling is also reduced to ONE spelling before
either decision is taken, by the same three operations `MountpointPolicy.normalize`
applies and in the same order — strip surrounding whitespace, collapse `//` runs, drop a
trailing `/` (`fslib.sh:468-471`). The order is load-bearing: `normalize("/x/nfs/ ")` is
`/x/nfs`, and would be `/x/nfs/` if the whitespace went last. So is each operation.
Without the collapse, `//data/media/0/nfs` left the routing as "not emulated" and missed
the `/data/media` refusal it should have hit, because that refusal matches literal text
while `mammon_route_recognize` normalizes internally. Without the whitespace strip,
`/storage/emulated/0/Android ` is not the component `Android`, so the module routed a
path the app answers `RESERVED_NAME` for — the vold-owned refusal missed by one
character. One saved value yielding two spellings is the same defect class as one media
id yielding two: both entrances have to agree on the exact characters, not on the intent.
What is reduced is the DECISION and never the mount target: a path the routing declines
is mounted at the saved spelling verbatim (`fslib.sh:495-496`), which is what
`RootMount.resolveTarget` does too, handing `EmulatedMount.route` its untouched argument
and building the non-emulated `Target` out of that same unchanged string. That is
precisely why both sides re-ask absoluteness of the SAVED spelling on that branch, and
only on that branch: the module at `fslib.sh:545-548`, the app as
`MountpointPolicy.refusalFor`'s `NOT_ABSOLUTE`. Without it `" /mnt/nas"` reached root as
`mkdir -p ' /mnt/nas'`, resolved in the su shell's own working directory — the trimmed
spelling passes every earlier check, and the untrimmed one is what gets mounted. A
routed path needs no such guard: its target is derived from the reduced spelling, so
how the pref was typed never reaches root, and `" /storage/emulated/0/nfs"` still
routes on both sides. The app additionally refuses a saved `/`, which the module's
`${mp%/}` already reduces to the empty string and rejects. It has to be
once and only once on each side, because `normalize` is not idempotent — `"/x/nfs /"`
reduces to `/x/nfs ` and only a second pass takes it to `/x/nfs`. The comment over that
refusal now claims only what the two sides really share — the roots list, the
component-boundary match, and the reduced spelling `isInStorageSurface` needs because it
normalizes first (`fslib.sh:524-528`); a wider parity claim in a comment would still not
be parity, and neither is one here: the two decisions have been measured against each
other, the numbers live in the review record, and they are not yet identical. And the
module verifies propagation before claiming anything: if the copy never appeared at the
app-visible path, the master-side mount is torn down and the boot is logged as a failure
(`fslib.sh:635-638`, a second `mammon_mounted_at` at the visible path before any MOUNTED
line), because propagation is a kernel property of the peer group — a missing copy means
the derivation was wrong, and a success line would hide that from the only person who
could report it. That log line says "unmounted" only when `umount -l` actually returned
0, and otherwise names the path as still mounted (`mammon_teardown`) — the same split
the app draws between `EMULATED_NOT_PROPAGATED` and `EMULATED_NOT_PROPAGATED_STUCK`. At
boot there is no Unmount button to point at, so a root shell is the honest advice there
rather than the evasion it was on the screen.

Since v0.6.0 the app carries this module inside its own APK: `packModuleZip` in
`app/build.gradle.kts` packs the directory deterministically into an asset, and an
Install-module button stages it and hands it to a system chooser for Magisk to flash —
mammon deliberately stops at that hand-off, because only Magisk completing its own flow
proves an install. Its probe compares versionCodes rather than testing for a directory:
v0.6.2 mapped "the module directory exists" to "already installed", which left a device
carrying v1.0 unable to reach v1.2 through the UI at all. Both numbers are read where
they live — `module.prop` inside the packaged asset zip for the bundled one, `module.prop`
under `modules_update/` or `modules/` for the installed one, the staged copy winning
because that is what the next boot runs — so no second copy of a version number exists to
drift. An install at or past the bundled versionCode reports, an older one is offered the
newer zip, and a denied, timed-out or unparseable probe stays UNAVAILABLE rather than
silently absent or silently up to date.

### Why the FUSE rung is pure Kotlin and ships no binary

The obvious design — a JVM process that mounts `/dev/fuse` itself, or that hands the
device to `/system/bin/mount` — is unavailable on Android in both halves:

- Neither `android.system.Os` nor libcore's internal `Os` exposes `mount(2)` or
  `umount2(2)`, so a JVM process cannot perform the mount.
- libcore's `UNIXProcess_md.c` closes every descriptor above stderr in the child before
  `exec`, so a JVM process cannot pass an open `/dev/fuse` descriptor to a `mount` child
  either.
- Inverting the direction removes the problem. The root SHELL opens `/dev/fuse`, keeps
  that descriptor across the `mount` child and across `exec app_process`, and the Kotlin
  daemon adopts the number with `ParcelFileDescriptor.adoptFd`. toybox `mount` forwards
  unknown `-o` options straight to `mount(2)`, so `-o fd=3,rootmode=40000,...` reaches
  the kernel untouched. AOSP's own vold mounts FUSE exactly this way — `open("/dev/fuse")`
  and then `mount("/dev/fuse", path, "fuse", ...,
  "fd=%i,rootmode=40000,allow_other,user_id=0,group_id=0,")`.
- No SELinux policy is shipped and none is needed: a Magisk root process runs in the
  unconstrained, permissive `magisk` domain, and `genfscon` labels every FUSE mount
  `u:object_r:fuse:s0`, which AOSP's `app.te` already grants every app domain access to.
  That holds for a mount placed inside the emulated tree too, which is the case that
  would have needed new policy if any did: `private/genfs_contexts:321` is the
  `genfscon fuse / u:object_r:fuse:s0` line, `public/file.te:181` types `fuse` as a
  `fusefs_type`, and the mount `neverallow` in `private/domain.te:1923-1939` exempts
  `fusefs_type` explicitly (AOSP `android-15.0.0_r1`). No `.te` file, no context= mount
  option, nothing.

The rejected alternative is recorded because it is what direction B originally proposed:
a prebuilt `sahlberg/fuse-nfs` on libnfs, cross-compiled with the NDK. libnfs implements
NFSv3 and NFSv4.0 but explicitly NOT NFSv4.1, so it would have been a protocol
regression against the `NfsV4Access` the app already had; it needs libfuse 2.9.x, which
upstream abandoned in 2021; and it costs roughly 0.8 MB of native libraries per ABI.
That is why B was finally built this way — it is history, not a live option.

### What the FUSE view does not do

Every one of these is a deliberate narrowing, not a gap waiting on a fix:

- **A refused write is the server's answer, not the mount's.** Both backends implement
  the mutating half, so the daemon never declines a mutation out of its own knowledge of
  the backend; it forwards the status the server returned as the errno naming that case —
  EACCES for `NFS3ERR_ROFS` on an export mounted `ro` or an identity the server squashes,
  ENOTEMPTY for a non-empty rmdir, ENOSPC for a full server. What the mount cannot do is
  a property of the export, never of the protocol version behind it, and the
  `implementsWrites` gate on `open` for write and on `access` stays in place for a
  backend that declares no writes, of which the app now has none.
- **No rename.** RENAME and RENAME2 answer ENOSYS, because the backend seam has no
  rename and emulating one as copy-plus-delete would be neither atomic nor O(1).
  MKNOD, LINK, SYMLINK, FALLOCATE and the xattr setters answer ENOSYS for the same
  reason — not EROFS, which on a mount that does accept writes would be a false
  statement about the filesystem rather than a true one about the operation.
- **No real ownership or permission bits.** `NodeAttrs` carries only type, size and
  mtime, so the daemon synthesises mode `0755` for directories and `0644` for files,
  owned by uid 0 / gid 0 to match the mount's `user_id`/`group_id`. The mount carries no
  `default_permissions`, so the kernel enforces none of it: the bits exist for userspace
  that stats before acting, and the server is the only real authority. A SETATTR carrying
  FATTR_MODE is accepted and ignored, because `cp -p` and every archive extractor send it
  and refusing would break them.
- **Write-through, never write-back.** FUSE_WRITEBACK_CACHE is deliberately not
  negotiated: one `write(2)` becomes one WRITE becomes one committed NFS write, so a
  failure is reported by the WRITE that caused it — which is where `write(2)` sees it —
  and the daemon holds no dirty state to lose. That is also why FLUSH has nothing to
  report, which matters because FLUSH's status is `close(2)`'s while RELEASE's is
  discarded by the VFS.
- **Appends race a remote writer.** The kernel resolves O_APPEND to an absolute offset
  from its cached size, and attributes live for the 5 s TTL below, so a concurrent append
  from another client inside that window is overwritten. Only close-to-open semantics
  closes this.
- **No symlinks or special files.** `NfsSession.list` does not surface them, so they are
  not listed, and READLINK answers EINVAL.
- **No real free-space figures.** `NfsSession` has no FSSTAT, so `STATFS` reports a
  synthetic capacity rather than a measured one. It must not report zero: `cp`, `rsync`
  and several file managers read free space before issuing a single write and would
  refuse a copy the server would have accepted. The truthful answer stays on the write
  path, where the server's own out-of-space arrives as ENOSPC.
- **5 s attribute and directory-entry lifetimes.** That TTL is what collapses the
  GETATTR storm behind `ls -l`.
- **Directory listings are snapshotted at OPENDIR**, so a change on the server appears
  on the next open rather than mid-walk.
- **xattrs and locking answer ENOSYS**, which the kernel caches and then stops asking
  about.

### Verification status

Split honestly, because the two halves have very different evidence behind them. The
daemon and the `NfsSession` seam are PROVEN on a Linux host: a real NFS export mounted
inside `unshare -Umr`, reads verified byte-exact by sha256 against the same files read
through the kernel's own NFS client, over both NFSv4.1 and NFSv3, and a clean `umount`.
The write half of the seam is PROVEN against a real NFSv4.1 export (Linux nfsd,
root_squash) by running the production classes out of this repository: MKDIR refused
with NFS4ERR_ACCESS as uid 0 and accepted as the configured account, a GUARDED4
create refusing a second create instead of truncating, a 2 MiB create/write/read
round trip byte-exact by sha256, SETATTR of size and mtime read back exactly, and
REMOVE of both a file and an empty directory.

The FUSE write surface on top of it is PROVEN the same way, one layer up: the production
daemon mounted through the real kernel FUSE module on a Linux host, inside
`unshare -Umr`, serving the same live export. Over that mount `mkdir`, a shell redirect
creating and writing a file, `cp` of 3 MiB verified byte-exact by sha256 against the
source, `cp -p`, truncate, `touch -d`, `rmdir` of a non-empty directory answering
ENOTEMPTY, `ln -s` answering ENOSYS, and `df` reporting non-zero space all behaved as
specified. The nodeid invalidation was proven there too: after an unlink and a re-create
of the same name, `stat` reported a different inode and the new content, which is exactly
the aliasing the node table's tombstone exists to prevent.

The SAF write path's first runtime evidence stopped short of the bytes. Running the
released 0.7.0 APK in Waydroid (Android 13) against the same live NFSv4.1 export, the
provider itself executed for the first time: DocumentsUI browsed the export root and
listed children with the sizes and `FLAG_*` a foreign client actually sees,
`ACTION_CREATE_DOCUMENT` created a file through the picker's own SAVE, the picker's New
folder and a second app's `createDocument` created directories, `deleteDocument` removed
a file, and `openForRead` streamed 3 MiB byte-exact by sha256 against the server. A
create as a squashed uid 0 was refused with the mapped `FileNotFoundException` and
nothing appeared on the server, while the identical call as the configured account
succeeded — the flag and exception contract behaving as specified against a real client.

The NFSv3 write path has evidence of its own, and it is the first byte-exact write proof
from an Android client and through the SAF front end: a write-capable debug APK of this
work on an Android emulator, driven through DocumentsUI against a real unfs3 export on
the host — patched for the rig to serve READDIRPLUS, which unfs3 as shipped refuses, so
the READDIR fallback is not what these runs exercise — with an RPC relay recording every
call server-side. A 300 KB copy into the export came back md5-identical to the source as
one CREATE, one SETATTR and nine FILE_SYNC WRITEs. New folder onto a name already taken
drew NFS3ERR_EXIST and the retry name ` (2)` landed instead; `rmdir` of a non-empty
directory answered NFS3ERR_NOTEMPTY; a mkdir into an export mounted `ro` answered
NFS3ERR_ROFS and reached the screen as "The server refused: the configured user ID may
not write here." No CREATE, MKDIR, REMOVE or RMDIR appeared on the wire twice in any
run, and the recorder proves it can see a duplicate: its negative control is a CREATE
re-sent as an identical frame under the same xid, which it flags. What those runs do NOT
reach is the case the naked calls exist for — a mutation whose reply the connection
swallowed — because none of them lost one, so no-resend-on-loss stays argued from the
library's retry behaviour rather than measured. The same scenario against the previous
APK put no CREATE, MKDIR, REMOVE or RMDIR on the wire at all, every mutating step of it
refused, which is what makes the pair a comparison. An emulator against unfs3 is neither
a phone nor a NAS.

That run predates the record-marking repair, and its rig's unfs3 had been rebuilt to
advertise `rtmax` 61440, so no READ reply could grow past one fragment. The rebuild
covered only that side: `rtmax` says nothing about a READDIRPLUS page, and the build
still asked for 64 KiB of one, past the 65532-byte threshold — so on the listing side
the defect went unprovoked rather than excluded, the rig's directories being small. The
61440 bound that followed got its own A/B afterwards on the same rig, with unfs3's
transfer sizes back at their stock 524288 for rtmax, rtpref, wtmax and wtpref alike.
There the read-only APK of the time, which predated the bound, asked for a 524288-byte
READ, received one status-0 reply of 307328 bytes split into five record fragments
`[65532, 65532, 65532, 65532, 45200]`, replayed the same xid on a fresh connection and
died with "RPC error: tcp IO error on the connection", having read nothing back — the
read was the one step of the scenario it failed. The build carrying the bound read the
same 300 KB byte-exact in six READs of 61440 at consecutive offsets and wrote it back
byte-exact in one CREATE, one SETATTR and five FILE_SYNC WRITEs — the write side needed
no bound of its own — passing all eleven steps, with not one RPC error line over a
cleared logcat for the read leg. A host-side client that walks record marks correctly
reassembled that identical five-fragment reply byte-exact, which put the fault in the
library rather than in the server — read at the time as its arithmetic alone, which
turned out to be half of it.

Two limits belong with that history. The rig measured libtirpc's 65532-byte flush and
nothing else — the host's kernel nfsd is unreachable from the emulator, so it says
nothing about a kernel server, and nothing here has met a NAS. And what it proved was
the bound, which was a workaround and not a repair: six READs were the price of never
asking for a reply the library would misread.

The repair's own first run on that rig, at unfs3's stock `rtmax = 524288`, FAILED, and
that failure is the most useful measurement in this section. Removing the clamp did
exactly what it was supposed to: one `READ off=0 count=524288` where the bounded build
had sent six of 61440, answered by the same five fragments
`[65532, 65532, 65532, 65532, 45200]`. The app then read back ZERO bytes — not wrong
bytes, none — and `dexdump` on the installed APK showed exactly one definition of
`RecordMarkingUtil`, the shadowed one, so this was not a shadow that failed to link. It
was the second defect: with the reassembly repaired, what remained was `RPCRecordDecoder`,
which cannot assemble a record whose fragments arrive over more than one socket read. It
threw `IndexOutOfBoundsException` on the last fragment's rewind, over bytes `FrameDecoder`
had already dropped, closing the connection, failing the READ `NETWORK_ERROR` and
re-sending the same xid onto a fresh connection to fail identically. That run took 26
minutes and refuted a verdict reached by reading the class, which the whole first round
had rested on; it is why `RPCRecordDecoder` is shadowed too.

The case that decides the repair is the one the bounded build could not ask for: a 300 KB
READ answered as that 307328-byte five-fragment reply, reassembled byte-exact by the app
itself, with the fragments arriving over as many socket reads as the network splits them
into — the condition the first run died on. Until such a run is recorded here, what
stands behind the repair is
`RecordMarkingReassemblyTest`, which fails against the upstream arithmetic,
`RPCRecordDecoderTest`, which fails against the upstream decoder with the exception the
rig saw, and the host-side reassembly above; no device number is claimed for either class.

In the Waydroid image nothing past `openProxyFileDescriptor` executed. That call needs a
per-app mount the framework asks vold for, and a kernel with no active SELinux LSM
rejects it, because the mount options carry SELinux contexts nothing consumes. A second
app calling `openProxyFileDescriptor` directly failed identically, so that was the image
and not mammon. The emulator run above is where the rest of that path finally ran: the
bytes moved through `ProxyFileDescriptorCallback`, the truncate-last ordering produced
the single SETATTR the recorder saw after the proxy fd was already open, and
`createUnique`'s collision retry was driven by the taken name. `NO_PROXY_FD` is the arm
the Waydroid image produces — unit-tested, but its message has never been read off a
screen. `SafContractTest` pins only what needs neither a round trip nor that runtime.
Nothing about writing BYTES is proven on a phone on either front end, and on the SAF side
over NFSv4.1 the JVM seam runs above remain the only evidence for the bytes a write would
move.

The end-to-end chain on a rooted Android phone — `su`, the kernel FUSE mount, and the
inherited descriptor surviving `exec app_process` — is verified since v0.6.6 on one
real device (Android 16, KernelSU-Next), where the daemon served a live export over
NFSv4.1. Waydroid still has no `su`, so that emulator cannot carry the test, and the
proof covers one device only: on any other phone, when rung 3 fails, read the diagnosis
the ladder reports rather than assuming which half broke. The launch line carries a
device-derived constraint: ART feeds every leading dash-arg to the VM and parses
`--nice-name` only between the `/` classpath dir and the class name, so the flag must
sit between them — a leading flag exits before main(), a trailing one leaks into the
daemon's argv, where it is taken for the optional identity field and reported as
unparseable.

The emulated-storage routing is the least-executed thing described in this guide:
NOTHING in it has run on Android. Its whole verdict is JVM tests and shell dry-runs on a
Linux host. `EmulatedMountTest` drives the routing rule against captured mountinfo text
and, since the first review round, the propagation verdict as well: `RootMount.mounted`
is called with a mount table showing the master path only and a teardown function
injected, which is the only way a JVM test can reach that verdict at all — a real
teardown spawns `su`. Three cases carry it, named for what they pin: "a mount that never
propagated is torn down and reported as removed", "a teardown that fails is not reported
as a removal", and "an unreadable mount table leaves a routed mount unknown, never
not-propagated", the last asserting that no teardown is even attempted. The same class
pins the refusal seam from the non-UI side ("a storage-surface path is refused by mount
itself, not only by the field"), and `MountpointPolicyTest` pins the composition it
calls. `MountpointVerbatimTargetTest` pins the other half of that seam, the branch that
hands the saved spelling to root untouched: it drives `RootMount.mount` through the
same injected-`su` seam `unmount` uses and asserts on the SCRIPT, which is where a
relative `mkdir -p` is visible at all, and it runs `fslib.sh` for the module's answer
to every one of those spellings rather than predicting it. `FslibAutomountFuseFdTest` runs the module's real `fslib.sh` under mksh against a
stubbed `mount`, and every shell case is `assumeTrue`-gated on mksh being on PATH. This
repository's `shell.nix` lists mksh for exactly that reason, so under the documented
`nix-shell --run './gradlew ...'` route the cases run; invoke Gradle outside that shell
and they SKIP instead, and a green suite there has proven nothing about the shell half.
Read the skip count before reading a pass as coverage. Beyond what the suite pins, the
routing helpers of that same script were dry-run by hand on a Linux host under mksh,
dash and busybox sh, agreeing in all three on the octal un-escaping, on rejecting a `..`
component, on refusing an empty mount table, on routing in place for a bare `shared:`,
and on preferring the `propagate_from` candidate over the `master` one; only the mksh
run is pinned by a test, the other two leave no artefact in this repository. The
media-id claim above was taken the same way and is worth no more than that: with a
synthetic two-line mountinfo, the module's routing put `00`, `010` and `2147483648`
unchanged into `mammon_route_at`, `mammon_route_lower` and `mammon_route_visible`, and
refused `+0` — under `/bin/sh`, which was bash in sh mode, since that run was taken
outside `nix-shell` and mksh was therefore not on PATH. Nothing in it is an mksh result.
Mutation checks belong to the review loop and are taken in a `/tmp` export, whose
figures deliberately never reach a document — `docs/guides/workflow.md` says why.
Against an Android device the score is zero: no phone, rooted or not, has executed one
line of this path.

The mount table the rule was derived from was read on a WAYDROID Android 13 instance
with SELinux DISABLED, not on a phone: there `/storage/emulated` carried `master:805`
while `/mnt/user/0/emulated` carried `shared:805` over the same fuse device, and vold's
`Android/data` ext4 mount appeared once under each of the two trees. That is evidence
for the SHAPE of the tree, not a measurement of any device: no rooted phone has mounted
anything through this path, no propagated mount has been observed appearing in a running
app's view, the app-visible status line and all three emulated diagnoses have never been
read off a screen, and because SELinux was disabled on that instance even the "no policy
needed" bullet above is source reading rather than observation. The in-place route is
one step further out again: no mount table recorded in this project, waydroid's
included, shows a `/storage/emulated` carrying a bare `shared:` — the only place that
shape exists is a fixture written by hand out of the captured one, so routing there
rather than refusing rests on the reasoning above and not on a sighting. What carries the reasoning instead is AOSP
`android-15.0.0_r1` and Linux 6.6: propagation reaching an ALREADY-RUNNING app
(`Zygote.cpp:2351` marks the app root `MS_SLAVE|MS_REC`, and a later per-app `unshare`
keeps the master link — `fs/namespace.c:1211-1213`), the lower-tree directory being
visible through the view immediately (`FuseDaemon.cpp:552-556`, `577-581`), and the
mountinfo field order the parser depends on (`fs/proc_namespace.c` `show_mountinfo`,
which is also why a line can be skipped entirely and a `parent_id` can reference a line
that is not there). On the first rooted phone this runs on, the diagnosis the mount
reports is the thing to read: `EMULATED_NO_SHARED_PEER`, `EMULATED_NOT_PROPAGATED` and
`EMULATED_NOT_PROPAGATED_STUCK` exist precisely so a wrong derivation names itself
instead of arriving as a generic failure — and the last of the three is the one to hope
never appears, since it is the only arm that leaves a mount standing.

What follows is the original decision input, kept because it explains the shape of what
was built and which constraints each direction was chosen against.

## How easysshfs works

easysshfs mounts SSH storage on Android with this shape:

- **Root access required.** The app drives a bundled, prebuilt `sshfs` binary through
  `su`; it never implements the filesystem in-process.
- **Bundled binaries come from outside the app build** — the author ships them via a
  separate buildroot-based releases repository, so the APK build itself needs no native
  toolchain.
- **A foreground service carries the mount session**, declared with
  `foregroundServiceType="connectedDevice"`, so the mount survives while its UI is gone.
- **`OnBootReceiver` remounts after `BOOT_COMPLETED`**, so configured mounts return on
  reboot without opening the app.
- **Permissions**: `INTERNET`, network/WIFI state, `POST_NOTIFICATIONS`,
  `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_CONNECTED_DEVICE`. Pure Kotlin, classic
  Views, published on F-Droid and Play under MIT.

The transferable lesson: on stock Android a real POSIX mount means root plus a daemon,
and everything user-visible (foreground notification, boot restore) hangs off a service
lifecycle, not off activity state.

## Design directions — all three shipped

Directions C, A and B are all shipped (see Status). The blockers below are the ones
recorded before implementation, each annotated with how it actually landed: a blocker
that was removed teaches as much as one that held.

### Option A — root + kernel NFS client (`mount -t nfs`)

Run `busybox mount -t nfs ...` through `su`.

- **Blocker, and it held:** most stock kernels ship without `nfs.ko`, and module loading
  is usually blocked (no `/system/lib/modules` entry, locked bootloader, verified boot).
  Whether it works depends entirely on the specific device/kernel, which makes "requires
  root" into "requires root AND a custom kernel" for many users. The blocker is why the
  ladder does not stop at these two rungs.

### Option B — root + userspace daemon over `/dev/fuse`

Mount `/dev/fuse` under `su` and serve the filesystem from userspace, as easysshfs does
with `sshfs`.

- **Shape as first written:** closest to easysshfs — same foreground service, same boot
  receiver, same bundled-binary question (where do prebuilt NFS-client binaries come
  from?).
- **Blocker as first written:** requires root for the FUSE device and the mount syscall;
  and someone must produce and maintain a working Android NFS-client binary, exactly the
  burden easysshfs moved out of its repo.
- **How it landed instead:** the binary half of that blocker was removed by dropping the
  binary. Since v0.5.0 the daemon is the app's own Kotlin, reusing the `NfsSession` the
  SAF provider already speaks, and the root shell does the two things a JVM process
  cannot — open `/dev/fuse` and call `mount(2)`. Root is still required; that half never
  went away. Status above carries why the inversion is necessary, why the
  prebuilt-binary route was rejected rather than deferred, which narrowings the Kotlin
  daemon accepts, where a mount inside shared storage is really made and why the path
  the user is shown is usually not the path it was mounted at, and the fact that the
  rooted-phone chain was first proven on a real device in v0.6.6.
- **Still not built from this shape:** the foreground service and the boot receiver. The
  mount is started one-shot from the activity, and the daemon is a detached root
  `app_process` rather than a service the app owns, so nothing restarts it after a reboot
  or after the daemon exits.

### Option C — rootless DocumentsProvider

Expose the NFS share through a `DocumentsProvider`; files appear in SAF file pickers.

- **Blocker:** no real POSIX mount. Other apps see only what they explicitly request via
  SAF; no arbitrary path access, no mmap, weaker semantics than a filesystem. In exchange
  it runs without root at all.

## What follows regardless of direction

The easysshfs shape predicted a component set beyond what mammon has built; what is
still open:

- a foreground service owning the mount session (all three rungs are started one-shot
  from the activity instead);
- possibly a boot receiver restoring configured mounts;
- permission declarations beyond today's `INTERNET`.

The bundled-binary question is settled rather than open: mammon ships no native code of
its own and bundles no prebuilt binary for any direction. Status explains why the FUSE
rung needed neither.

Keep this guide's Status section, `routes.md` and `README.md` in step with the code in
the same change — a guide describing a dead option as live is worse than no guide. Cite
in-repo code by SYMBOL wherever the symbol name is enough to find the construct: seven
of the eleven in-repo line ranges this guide carried into the third review round pointed
at the wrong construct or straddled its boundary, every one of them from edits made
ABOVE the cited lines, so a range is now spent only where the exact lines are the
argument, and then with the construct quoted beside it so the number is checkable at a
glance. Citations into AOSP and the kernel keep their ranges: they are pinned to
`android-15.0.0_r1` and Linux 6.6, which this repository cannot move.
