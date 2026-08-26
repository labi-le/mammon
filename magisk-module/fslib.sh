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

# ---- boot-time automount (v1.1) ----
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
    # whitespace as \040, so the target must be escaped the same way.
    mp=$(printf '%s' "$1" | sed 's/ /\\040/g')
    found=0
    while read -r _src tgt fstype _rest; do
        if [ "$tgt" = "$mp" ] && { [ "$fstype" = nfs ] || [ "$fstype" = nfs4 ] || [ "$fstype" = fuse ]; }; then
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
    mp=${mp%/}
    if ! mammon_quote_free "$host" || ! mammon_quote_free "$export_path" || ! mammon_quote_free "$mp"; then
        mammon_log "$log" "SKIPPED: spec contains a single quote and is refused ($host:$port$export_path)"
        return 0
    fi
    case $mp in
        /*) : ;;
        *) mammon_log "$log" "SKIPPED: mountpoint '$mp' is not absolute"; return 0 ;;
    esac
    case $export_path in
        /*) : ;;
        *) mammon_log "$log" "SKIPPED: export '$export_path' is not absolute"; return 0 ;;
    esac
    if mammon_mounted_at "$mp"; then
        mammon_log "$log" "SKIPPED: something is already mounted at $mp"
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
        mkdir -p "$mp"
        mount -t fuse -o fd=3,rootmode=40000,user_id=0,group_id=0,allow_other /dev/fuse "$mp" || {
            mammon_log "$log" "FAILED: kernel refused the fuse mount at $mp"
            return 0
        }
        if command -v setsid >/dev/null 2>&1; then S=setsid; else S=; fi
        # load.log appends across boots, so readiness reads only what THIS launch
        # writes past the mark — a stale 'serving' line from an earlier boot would
        # otherwise bless a daemon that died instantly.
        mark=$(wc -c <"$log")
        CLASSPATH=$apk $S app_process --nice-name=app.mammon:fuse / app.mammon.FuseDaemonKt 3 "$host" "$port" "$export_path" </dev/null >>"$log" 2>&1 &
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
            mammon_log "$log" "MOUNTED: $host:$port$export_path at $mp via fuse (pid $D)"
        else
            umount -l "$mp" 2>/dev/null
            mammon_log "$log" "FAILED: daemon died or never served; unmounted $mp"
        fi
    } 3<>"$fuse_dev"
}
