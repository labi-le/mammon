# shellcheck shell=sh

# MAMMON_MODULE_DIRS and MAMMON_PROC_FILESYSTEMS exist for host dry-runs; on a
# device neither is set and the defaults below apply.
mammon_scan_dirs() {
    if [ -n "${MAMMON_MODULE_DIRS:-}" ]; then
        # shellcheck disable=SC2046 # override is space-separated
        printf '%s\n' ${MAMMON_MODULE_DIRS}
        return 0
    fi
    printf '%s\n' \
        /system/lib/modules \
        /system/lib64/modules \
        /vendor/lib/modules \
        /vendor/lib64/modules \
        /vendor_dlkm/lib/modules \
        /vendor_dlkm/lib64/modules \
        /odm/lib/modules \
        /odm/lib64/modules \
        /lib/modules
}

# Coarse on purpose: a stray nfnetlink-style miss costs one FAILED line, while
# narrowing further risks skipping a vendor's oddly named nfs module.
mammon_is_candidate() {
    lc=$(printf '%s' "${1##*/}" | tr '[:upper:]' '[:lower:]')
    case $lc in
        fuse* | *nfs*) return 0 ;;
        *) return 1 ;;
    esac
}

mammon_find_candidates() {
    mammon_candidates=""
    # shellcheck disable=SC2046 # module dirs carry no spaces
    for d in $(mammon_scan_dirs); do
        [ -d "$d" ] || continue
        for f in "$d"/*.ko; do
            [ -f "$f" ] || continue
            if mammon_is_candidate "$f"; then
                mammon_candidates="${mammon_candidates}${f}
"
            fi
        done
    done
    mammon_candidates=$(printf '%s' "$mammon_candidates" | LC_ALL=C sort)
}

mammon_registered() {
    grep -iE '^(nodev[[:space:]]+)?(fuse|nfs[0-9]?)$' "${MAMMON_PROC_FILESYSTEMS:-/proc/filesystems}" 2>/dev/null || true
}

mammon_log() {
    printf '%s\n' "$2" >> "$1"
}

# Two passes because insmod checks no dependencies: whatever failed beside a
# success gets one retry after the successes, which covers the common
# nfs.ko-needs-nfsv*.ko shape without parsing module metadata.
mammon_load_all() {
    log=$1
    pending=$mammon_candidates
    mammon_loaded=0
    pass=1
    while [ "$pass" -le 2 ]; do
        pending_next=""
        # shellcheck disable=SC2086 # candidate paths from find_candidates
        for f in $pending; do
            if insmod "$f" >/dev/null 2>&1; then
                mammon_log "$log" "LOADED: $f"
                mammon_loaded=$((mammon_loaded + 1))
            else
                mammon_log "$log" "FAILED: $f (pass $pass)"
                pending_next="$pending_next $f"
            fi
        done
        [ -z "$pending_next" ] && break
        pending=$pending_next
        pass=$((pass + 1))
    done
}

mammon_fsloader_main() {
    log=$1
    printf '\n=== %s (%s) ===\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$(uname -r)" >> "$log"
    mammon_log "$log" "scan: collecting fuse*/nfs* .ko candidates"
    mammon_find_candidates
    total=$(printf '%s' "$mammon_candidates" | LC_ALL=C sed '/^$/d' | wc -w)
    if [ "$total" -eq 0 ]; then
        mammon_log "$log" "no candidate .ko found in any scanned directory"
    else
        printf '%s\n' "$mammon_candidates" | while IFS= read -r c; do
            [ -n "$c" ] && printf 'candidate: %s\n' "$c"
        done >> "$log"
        mammon_load_all "$log"
        mammon_log "$log" "summary: loaded $mammon_loaded of $total candidates after two passes"
    fi
    reg=$(mammon_registered)
    if [ -n "$reg" ]; then
        mammon_log "$log" "registered in /proc/filesystems now:"
        printf '%s\n' "$reg" >> "$log"
    else
        mammon_log "$log" "(nothing registered)"
    fi
}

# ---- boot-time automount (v1.2) ----
#
# Borrows clifforama/multi-mount's UX (config-driven boot mounts with network
# wait) but backs it with mammon's FUSE daemon instead of kernel nfs, which the
# target devices do not have. Spec source is the app's own SharedPreferences,
# so there is exactly one place to configure the share. Every override below
# exists for host dry-runs only; on a device the defaults apply.

mammon_xml_unescape() {
    # &amp; last, so entities are decoded exactly once.
    sed 's/&lt;/</g; s/&gt;/>/g; s/&quot;/"/g; s/&#39;/'"'"'/g; s/&apos;/'"'"'/g; s/&amp;/\&/g'
}

mammon_pref_value() {
    # $1 = prefs xml, $2 = key. Prints the decoded value; fails when absent.
    # Magisk's toybox sed lacks -E here, so both shapes are matched separately
    # over optional leading whitespace (Android indents every line).
    file=$1 key=$2
    val=$(sed -n "s|^[[:space:]]*<string name=\"$key\">\\(.*\\)</string>[[:space:]]*\$|\\1|p" "$file" | head -n 1)
    if [ -z "$val" ]; then
        val=$(sed -n "s|^[[:space:]]*<int name=\"$key\" value=\"\\([0-9][0-9]*\\)\"[[:space:]]*/>[[:space:]]*\$|\\1|p" "$file" | head -n 1)
    fi
    [ -n "$val" ] || return 1
    printf '%s\n' "$val" | mammon_xml_unescape
}

mammon_quote_free() {
    # Same single-quote discipline RootMount.quote() implies: a value carrying a
    # quote cannot be embedded safely, so the whole spec is rejected instead.
    case $1 in
        *"'"*) return 1 ;;
        *) return 0 ;;
    esac
}

mammon_mounted_at() {
    # Field comparison over the GLOBAL namespace view; /proc/mounts escapes
    # whitespace as \040, so the target must be escaped the same way. Not named
    # `mp`: the caller's own mountpoint must survive this call.
    esc=$(printf '%s' "$1" | sed 's/ /\\040/g')
    found=0
    while read -r _src tgt fstype _rest; do
        if [ "$tgt" = "$esc" ] && { [ "$fstype" = nfs ] || [ "$fstype" = nfs4 ] || [ "$fstype" = fuse ]; }; then
            found=1
            break
        fi
    done < "${MAMMON_PROC_MOUNTS:-/proc/1/mounts}"
    [ "$found" -eq 1 ]
}

mammon_share_up() {
    # $1 host, $2 port. TCP probe when nc exists, ICMP fallback; -z is
    # unavailable on some toybox builds, so a plain connect is tried too.
    if command -v nc >/dev/null 2>&1; then
        if nc -z -w 2 "$1" "$2" </dev/null >/dev/null 2>&1 ||
            nc -w 2 "$1" "$2" </dev/null >/dev/null 2>&1; then
            return 0
        fi
    elif ping -c 1 -W 2 "$1" >/dev/null 2>&1; then
        return 0
    fi
    return 1
}

# mangle_path (fs/seq_file.c) escapes space, tab, newline and backslash in the root and
# mount-point fields as a backslash plus EXACTLY three octal digits. printf '%b' is not a
# decoder for that: after a leading 0 it consumes up to three more digits and stops at the
# first non-digit, so '\0400' — how '/mnt/user 0/emulated' prints — loses the space and
# eats the 0. Those four escapes are decoded left to right and a decoded \134 is never
# re-read — the non-overlapping order MountsParser.unescape's \\[0-7]{3} regex applies,
# over the only escapes this field can carry.
mammon_unescape_path() {
    esc_rest=$1
    esc_out=
    esc_tab=$(printf '\t')
    # A literal: no escape survives a plain shell assignment.
    esc_nl='
'
    while :; do
        case $esc_rest in
            *\\*) ;;
            *) break ;;
        esac
        esc_out=$esc_out${esc_rest%%\\*}
        esc_rest=${esc_rest#*\\}
        case $esc_rest in
            040*) esc_out="$esc_out "; esc_rest=${esc_rest#040} ;;
            011*) esc_out=$esc_out$esc_tab; esc_rest=${esc_rest#011} ;;
            012*) esc_out=$esc_out$esc_nl; esc_rest=${esc_rest#012} ;;
            134*) esc_out=$esc_out\\; esc_rest=${esc_rest#134} ;;
            *) esc_out=$esc_out\\ ;;
        esac
    done
    printf '%s\n' "$esc_out$esc_rest"
}

# Prints the first */emulated fuse mount whose shared: group is $2, reading $1. FIRST, and
# never the slave view itself: EmulatedMount.mountSite takes firstOrNull over the same
# filter, and one decision in two languages must not answer differently.
mammon_emulated_peer() {
    peer_mi=$1
    peer_want=$2
    # Subshell so `set -f` cannot leak into the caller.
    (
        set -f
        while IFS= read -r line; do
            # shellcheck disable=SC2086 # deliberate field split, globbing off
            set -- $line
            [ "$#" -ge 8 ] || continue
            tgt=$5
            case $tgt in
                /storage/emulated) continue ;;
                *"/emulated") ;;
                *) continue ;;
            esac
            shift 6
            found=
            while [ "$#" -gt 0 ] && [ "$1" != - ]; do
                case $1 in shared:*) found=${1#shared:} ;; esac
                shift
            done
            if [ "$1" != - ] || [ "$2" != fuse ] || [ -z "$found" ]; then continue; fi
            [ "$found" = "$peer_want" ] || continue
            mammon_unescape_path "$tgt"
            exit 0
        done < "$peer_mi"
        exit 1
    )
}

# /storage/emulated is a SLAVE of the peer group its master: tag names, so a mount
# made there propagates to nobody. The shared MASTER of that group — the same fuse
# device under another path — is where the mount must land, and it is derived, never
# written down: a literal froze at the wrong value three times in this project.
#
# propagate_from: wins over master: where a line carries both, and it wins over file
# order too: each group gets its own pass. master: is the IMMEDIATE master's group id,
# which may have no visible line under our root at all, while fs/pnode.c
# get_dominating_id gives the nearest master group that IS reachable here. Keying on
# master: alone makes the app route while this script SKIPs on the same phone: a mount
# that works from the button and vanishes at every boot, which reads as flakiness
# rather than as the refusal it is.
#
# A /storage/emulated carrying a bare shared:N and nothing above it SENDS propagation,
# so a mount made in place reaches the whole group: it is its own master, and printing
# it here routes there rather than refusing a device the route works on.
mammon_emulated_master() {
    mi=${MAMMON_PROC_MOUNTINFO:-/proc/1/mountinfo}
    [ -r "$mi" ] || return 1
    # Subshell so `set -f` cannot leak into the caller.
    (
        set -f
        emu_shared=
        emu_master=
        emu_dom=
        while IFS= read -r line; do
            # shellcheck disable=SC2086 # deliberate field split, globbing off
            set -- $line
            if [ "$#" -lt 8 ] || [ "$5" != /storage/emulated ]; then continue; fi
            shift 6
            try_shared=
            try_master=
            try_dom=
            # The optional fields are a variable-length list closed by "-", so a
            # column-counting parser is wrong on the next device, not this one.
            while [ "$#" -gt 0 ] && [ "$1" != - ]; do
                case $1 in
                    shared:*) try_shared=${1#shared:} ;;
                    master:*) try_master=${1#master:} ;;
                    propagate_from:*) try_dom=${1#propagate_from:} ;;
                esac
                shift
            done
            # The fuse gate and last-line-wins both mirror mountSite's lastOrNull filter.
            if [ "$1" != - ] || [ "$2" != fuse ]; then continue; fi
            emu_shared=$try_shared
            emu_master=$try_master
            emu_dom=$try_dom
        done < "$mi"
        if [ -n "$emu_dom$emu_master" ]; then
            # shellcheck disable=SC2086 # group ids carry no spaces, and either may be empty
            for want in $emu_dom $emu_master; do
                if peer=$(mammon_emulated_peer "$mi" "$want"); then
                    printf '%s\n' "$peer"
                    exit 0
                fi
            done
            exit 1
        fi
        [ -n "$emu_shared" ] || exit 1
        printf '%s\n' /storage/emulated
    )
}

# Built once at source time, not per call: an inline literal would embed a tab, a CR
# and U+2028 in the script text, so the escapes have to be decoded, and mksh forks for
# $( ) around the external printf. Unprefixed — internal, not a tenth documented host
# override, which this assignment would overwrite anyway.
tw_set=$(printf '\011,\012,\013,\014,\015,\034,\035,\036,\037,\040,\302\240,\341\232\200,\342\200\200,\342\200\201,\342\200\202,\342\200\203,\342\200\204,\342\200\205,\342\200\206,\342\200\207,\342\200\210,\342\200\211,\342\200\212,\342\200\250,\342\200\251,\342\200\257,\342\201\237,\343\200\200')

# Sets mammon_trimmed to $1 with MountpointPolicy.normalize's String.trim() applied.
# Kotlin reads Char.isWhitespace as Character.isWhitespace OR isSpaceChar, so the set is
# the ASCII space class, U+001C-U+001F and every Unicode space separator, non-breaking
# ones included; U+0085 is absent because the JVM answers false to both halves.
# [[:space:]] is only the ASCII third of that and is locale-dependent besides.
# Characters are stripped, never bytes: 0x8A ends both U+200A and U+044A, so a
# byte-class trim would bite the tail off a Cyrillic name.
mammon_trim_ws() {
    mammon_trimmed=$1
    tw_prev=
    # A pass can uncover what an earlier entry already walked past, as in "<tab><sp><tab>".
    while [ "$mammon_trimmed" != "$tw_prev" ]; do
        tw_prev=$mammon_trimmed
        tw_rest=$tw_set
        while [ -n "$tw_rest" ]; do
            tw_c=${tw_rest%%,*}
            case $tw_rest in
                *,*) tw_rest=${tw_rest#*,} ;;
                *) tw_rest= ;;
            esac
            while :; do
                case $mammon_trimmed in
                    "$tw_c"*) mammon_trimmed=${mammon_trimmed#"$tw_c"} ;;
                    *) break ;;
                esac
            done
            while :; do
                case $mammon_trimmed in
                    *"$tw_c") mammon_trimmed=${mammon_trimmed%"$tw_c"} ;;
                    *) break ;;
                esac
            done
        done
    done
}

# The device-free half of the decision, as EmulatedMount.recognize is: it sets mid and
# name from the saved spelling alone, so its verdict cannot change while the caller
# waits. 0 recognized, 1 not emulated, 2 tree root, 3 reserved by vold.
mammon_route_recognize() {
    p=$1
    # normalize's three operations in its order: trimming after the collapse would leave
    # "/a/b/ " with the trailing slash the app strips. The guard is a printable-ASCII
    # test rather than [[:space:]] because glibc calls U+00A0 graph, not space.
    case $p in *[!!-~]*) mammon_trim_ws "$p"; p=$mammon_trimmed ;; esac
    case $p in *//*) p=$(printf '%s' "$p" | sed 's|//*|/|g') ;; esac
    p=${p%/}
    case $p in
        /sdcard | /storage/emulated) return 2 ;;
        /sdcard/*) rest=0/${p#/sdcard/} ;;
        /storage/emulated/*) rest=${p#/storage/emulated/} ;;
        /mnt/user/*)
            # * spans slashes in a glob, so the shape is checked instead:
            # EmulatedMount.MASTER_SPELLING refuses /mnt/user/abc/... and
            # /mnt/user/x/y/..., and the app then mounts the typed path literally.
            mnt_user=${p#/mnt/user/}
            mnt_user=${mnt_user%%/*}
            case $mnt_user in '' | *[!0-9]*) return 1 ;; esac
            case ${p#/mnt/user/"$mnt_user"} in
                /emulated) return 2 ;;
                /emulated/*) rest=${p#/mnt/user/"$mnt_user"/emulated/} ;;
                *) return 1 ;;
            esac
            ;;
        *) return 1 ;;
    esac
    mid=${rest%%/*}
    name=${rest#*/}
    case $mid in '' | *[!0-9]*) return 1 ;; esac
    [ "$name" != "$rest" ] || return 2
    # Same rejection, and same verdict, as EmulatedMount.recognize — but here it is the last
    # check before a mkdir -p as root: the app validates before saving, while this script
    # reads a pref file a hand edit can leave a traversal out of the tree in.
    case /$name/ in
        */./* | */../*) return 1 ;;
    esac
    # vold mounts Android/data and Android/obb itself; a mount of ours there would
    # fight it.
    case ${name%%/*} in Android) return 3 ;; esac
    return 0
}

# The half that needs the device, as the tail of EmulatedMount.route() is: the mount
# table is the only input that can answer differently one tick later. $1 mid, $2 name.
# Sets mammon_route_at (where to mount), mammon_route_lower (the directory to create
# first) and mammon_route_visible (where apps will see it); 4 when no shared peer.
mammon_route_site() {
    mammon_route_at=$(mammon_emulated_master) || return 4
    mammon_route_at=$mammon_route_at/$1/$2
    mammon_route_lower=${MAMMON_MEDIA_ROOT:-/data/media}/$1/$2
    mammon_route_visible=/storage/emulated/$1/$2
}

# MediaProvider serves the emulated view and starts long after post-fs-data, so at
# late_start the master can be simply absent: only the site half is polled, inside the
# caller's window ($i of $tries), rather than raced or guessed at with a fixed delay.
# What the split buys is a verdict that cannot drift mid-wait, not saved processes: the
# trim's table is built once at file scope, so re-asking recognition per tick re-forked
# only mammon_route_recognize's //-collapse, and only for a spelling carrying a doubled
# slash — two processes per tick there, and none at all for /storage/emulated/0/nfs.
mammon_route_wait() {
    mammon_route_recognize "$2"
    rc=$?
    [ "$rc" -eq 0 ] || return "$rc"
    mammon_route_site "$mid" "$name" && return 0
    mammon_log "$1" "automount: '$2' is inside emulated storage; waiting for the MediaProvider FUSE view"
    while [ "$i" -lt "$tries" ]; do
        i=$((i + 1))
        sleep "$interval"
        mammon_route_site "$mid" "$name" && return 0
    done
    return 4
}

# Prints the clause the FAILED lines end with. "unmounted" is a claim, and a mount left
# standing at a path nobody is told about is the one nobody goes looking for.
mammon_teardown() {
    if umount -l "$1" 2>/dev/null; then
        printf 'unmounted %s' "$1"
    else
        printf 'STILL MOUNTED at %s, remove it from a root shell' "$1"
    fi
}

mammon_automount_main() {
    log=$1
    [ -f "$MODDIR/automount" ] || return 0
    prefs=${MAMMON_PREFS_FILE:-/data/data/app.mammon/shared_prefs/mammon.xml}
    # The prefs file lives in Credential-Encrypted storage: until the user's
    # FIRST unlock after boot it is absent, so spec and reachability must be
    # polled inside ONE bounded window — on a rebooted-but-locked phone the
    # mount lands right after unlock instead of never.
    tries=${MAMMON_WAIT_TRIES:-60}
    interval=${MAMMON_WAIT_INTERVAL:-10}
    i=0
    while :; do
        # CE storage hides the spec until the user's first unlock; it is the
        # only condition pollable before then, so its ticks come first.
        if [ -s "$prefs" ] && grep -q 'name="host"' "$prefs" 2>/dev/null; then
            break
        fi
        i=$((i + 1))
        if [ "$i" -ge "$tries" ]; then
            mammon_log "$log" "FAILED: spec never became readable (device stayed locked, or share never configured) after $tries x ${interval}s"
            return 0
        fi
        sleep "$interval"
    done
    if ! host=$(mammon_pref_value "$prefs" host) || [ -z "$host" ]; then
        mammon_log "$log" "FAILED: saved spec is unusable (host missing or empty); fix it in the app"
        return 0
    fi
    export_path=$(mammon_pref_value "$prefs" export) || { mammon_log "$log" "SKIPPED: automount enabled but no export saved in the app"; return 0; }
    port=$(mammon_pref_value "$prefs" port) || port=2049
    mp=$(mammon_pref_value "$prefs" mountpoint) || mp=/mnt/nas
    # The daemon takes the identity as an optional 5th argument; an unset pref
    # leaves it off, and the daemon then claims AuthIdentity.DEFAULT itself.
    identity=$(mammon_pref_value "$prefs" identity) || identity=
    # MountpointPolicy.normalize decides for the app, so the same three operations in the
    # same order decide here; without them the refusals below judge a spelling neither
    # normalize nor isInStorageSurface ever sees.
    mp_raw=$mp
    case $mp in *[!!-~]*) mammon_trim_ws "$mp"; mp=$mammon_trimmed ;; esac
    case $mp in *//*) mp=$(printf '%s' "$mp" | sed 's|//*|/|g') ;; esac
    mp=${mp%/}
    if ! mammon_quote_free "$host" || ! mammon_quote_free "$export_path" || ! mammon_quote_free "$mp" || ! mammon_quote_free "$identity"; then
        mammon_log "$log" "SKIPPED: spec contains a single quote and is refused ($host:$port$export_path)"
        return 0
    fi
    # Both spellings, because the test is on the normalized one and ' ', '/', '//' and
    # ' / ' all empty it: naming only that prints one line for four hand edits, and
    # naming only the saved one would call '/' non-absolute, which it is not.
    case $mp in
        /*) : ;;
        *)
            mammon_log "$log" "SKIPPED: mountpoint '$mp_raw' normalizes to '$mp', which is not absolute"
            return 0
            ;;
    esac
    # Before the routing wait: a refusal that cannot change with time must not
    # spend the boot window first.
    case $export_path in
        /*) : ;;
        *) mammon_log "$log" "SKIPPED: export '$export_path' is not absolute"; return 0 ;;
    esac
    # A path inside the emulated tree is mounted on the shared master and seen at
    # $visible; /storage itself is tmpfs and /data/media sits BELOW the FUSE daemon
    # serving that tree, so those two stay refused.
    target=$mp_raw
    visible=$mp_raw
    lower=
    # The saved spelling, not $mp: resolveTarget hands EmulatedMount.route the same
    # untouched string, and normalize is not idempotent — "/a/nfs /" normalizes to
    # "/a/nfs " once and to "/a/nfs" twice.
    mammon_route_wait "$log" "$mp_raw"
    case $? in
        0)
            target=$mammon_route_at
            visible=$mammon_route_visible
            lower=$mammon_route_lower
            mammon_log "$log" "automount: routing $mp to $target, visible at $visible"
            ;;
        2)
            mammon_log "$log" "SKIPPED: mountpoint '$mp' is the emulated tree root; mount a subdirectory instead"
            return 0
            ;;
        3)
            mammon_log "$log" "SKIPPED: mountpoint '$mp' is under Android/, which vold owns"
            return 0
            ;;
        4)
            mammon_log "$log" "SKIPPED: no shared peer for /storage/emulated in this device's mount table, so a mount inside emulated storage would reach no app"
            return 0
            ;;
        *)
            case $mp in
                /storage | /storage/* | /sdcard | /sdcard/* | /data/media | /data/media/*)
                    # The three roots are MountpointPolicy.STORAGE_SURFACE_ROOTS, matched
                    # at a component boundary as isInStorageSurface does — which needs the
                    # normalized $mp above, all three operations of it, since that function
                    # normalizes first. A traversal arrives here as "not emulated" too,
                    # hence the split below.
                    case /$mp/ in
                        */./* | */../*)
                            mammon_log "$log" "SKIPPED: mountpoint '$mp' has a . or .. component; save the path it really names instead"
                            ;;
                        *)
                            mammon_log "$log" "SKIPPED: mountpoint '$mp' is Android's own storage; of that tree only a path inside emulated storage can be routed"
                            ;;
                    esac
                    return 0
                    ;;
            esac
            # Mounted VERBATIM, as RootMount.resolveTarget mounts a non-emulated
            # mountpoint: normalize decides there but never rewrites the target, so a
            # trimmed one would strand the boot mount where Unmount cannot name it.
            # Absoluteness is re-asked because the trim is what can hide a leading space
            # from the check above, and mkdir -p would then run in late_start's cwd.
            case $mp_raw in
                /*) : ;;
                *) mammon_log "$log" "SKIPPED: mountpoint '$mp_raw' is not absolute as saved"; return 0 ;;
            esac
            ;;
    esac
    if mammon_mounted_at "$target"; then
        mammon_log "$log" "SKIPPED: something is already mounted at $target"
        return 0
    fi
    if [ "$visible" != "$target" ] && mammon_mounted_at "$visible"; then
        mammon_log "$log" "SKIPPED: something is already mounted at $visible"
        return 0
    fi
    mammon_log "$log" "automount: waiting for $host:$port"
    # Same overall window: reachability gets whatever budget the spec wait
    # did not spend, so a late unlock still leaves time to connect.
    j=0
    while :; do
        if mammon_share_up "$host" "$port"; then
            break
        fi
        j=$((j + 1))
        if [ "$((i + j))" -ge "$tries" ]; then
            mammon_log "$log" "FAILED: host unreachable after $((i + j)) x ${interval}s"
            return 0
        fi
        sleep "$interval"
    done
    modprobe fuse 2>/dev/null || true
    apk=$(pm path app.mammon 2>/dev/null | head -n 1)
    apk=${apk#package:}
    if [ -z "$apk" ]; then
        mammon_log "$log" "FAILED: app.mammon is not installed, no classpath for the daemon"
        return 0
    fi
    fuse_dev=${MAMMON_FUSE_DEVICE:-/dev/fuse}
    # The fd must ride a GROUP redirection, not `exec 3<>`: Magisk's /system/bin/sh is
    # mksh, which marks `exec`-opened fds >= 3 close-on-exec, so the mount child would
    # lose the device and mount(2) fails EINVAL. A failed group redirection would kill
    # the shell before its body, so openability is probed in a throwaway subshell first.
    # Same shape as RootMount.kt's fuseMountScript.
    if ! (exec 3<>"$fuse_dev") 2>/dev/null; then
        mammon_log "$log" "FAILED: cannot open $fuse_dev"
        return 0
    fi
    {
        # A mount needs a directory that exists in the FUSE view, and inside the
        # emulated tree one exists there as soon as it exists in the lower tree:
        # MediaProvider resolves LOOKUP with lstat(2) on the lower path, with no
        # database row and no scan involved.
        if [ -n "$lower" ] && ! mkdir -p "$lower"; then
            mammon_log "$log" "FAILED: cannot create the lower directory $lower"
            return 0
        fi
        # A routed target is a path in the FUSE view, so creating it THERE would force
        # the daemon's 0775 ownership and need the daemon up; the lower mkdir above has
        # already made it appear. Only a non-routed mountpoint is created directly, and
        # unchecked its failure would surface below as "kernel refused the fuse mount".
        if [ -z "$lower" ] && ! mkdir -p "$target"; then
            mammon_log "$log" "FAILED: cannot create the mountpoint $target"
            return 0
        fi
        mount -t fuse -o fd=3,rootmode=40000,user_id=0,group_id=0,allow_other /dev/fuse "$target" || {
            mammon_log "$log" "FAILED: kernel refused the fuse mount at $target"
            return 0
        }
        if command -v setsid >/dev/null 2>&1; then S=setsid; else S=; fi
        # load.log appends across boots, so readiness reads only what THIS launch
        # writes past the mark — a stale 'serving' line from an earlier boot would
        # otherwise bless a daemon that died instantly.
        mark=$(wc -c <"$log")
        # app_process feeds leading dash-args to ART and parses --nice-name only
        # between "/" and the class; same shape as RootMount.kt's fuseMountScript,
        # which passes the identity in the same trailing position.
        # shellcheck disable=SC2086 # $identity is one pre-validated field or empty
        CLASSPATH=$apk $S app_process / --nice-name=app.mammon:fuse app.mammon.FuseDaemonKt 3 "$host" "$port" "$export_path" $identity </dev/null >>"$log" 2>&1 &
        D=$!
        mammon_log "$log" "automount: daemon pid $D, probing readiness"
        k=0
        while [ "$k" -lt 30 ]; do
            tail -c +"$((mark + 1))" "$log" 2>/dev/null | grep -q 'serving ' && break
            kill -0 "$D" 2>/dev/null || break
            k=$((k + 1))
            sleep 1
        done
        if tail -c +"$((mark + 1))" "$log" 2>/dev/null | grep -q 'serving '; then
            # Propagation is a kernel property of the peer group: if the copy never
            # appeared, the derivation was wrong and calling this a success would
            # hide that from everyone.
            if [ "$visible" != "$target" ] && ! mammon_mounted_at "$visible"; then
                mammon_log "$log" "FAILED: mount at $target never propagated to $visible; $(mammon_teardown "$target")"
                return 0
            fi
            mammon_log "$log" "MOUNTED: $host:$port$export_path at $visible via fuse (pid $D)"
        else
            mammon_log "$log" "FAILED: daemon died or never served; $(mammon_teardown "$target")"
        fi
    } 3<>"$fuse_dev"
}
