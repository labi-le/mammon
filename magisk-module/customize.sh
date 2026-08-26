# shellcheck shell=sh
SKIPUNZIP=0

ui_print "- Mammon FS Loader v1.2"

# Flash-time verdict: the same scan+load the boot scripts run later, so a
# missing-candidate or refused-insmod situation is visible before first reboot.
if [ "$BOOTMODE" = true ]; then
    . "$MODPATH/fslib.sh"
    mammon_fsloader_main "$MODPATH/load.log"
    ui_print "- Scan/load result (also written to \$MODPATH/load.log):"
    while IFS= read -r l; do
        ui_print "  $l"
    done < "$MODPATH/load.log"
else
    ui_print "- Not flashed from the Magisk app: skipping flash-time scan;"
    ui_print "  post-fs-data.sh runs it at next boot."
fi
