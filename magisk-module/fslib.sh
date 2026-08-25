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
