#!/system/bin/sh
MODDIR=${0%/*}
. "$MODDIR/fslib.sh"
if [ -z "$(mammon_registered)" ]; then
    mammon_fsloader_main "$MODDIR/load.log"
fi
