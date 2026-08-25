#!/system/bin/sh
MODDIR=${0%/*}
. "$MODDIR/fslib.sh"
mammon_fsloader_main "$MODDIR/load.log"
