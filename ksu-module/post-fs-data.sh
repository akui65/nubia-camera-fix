#!/system/bin/sh
# Morpho expiry bypass fallback: bind-mount only this one file.
LOG=/data/local/tmp/morpho_mount.log
TARGET=/vendor/lib64/libmorpho_video_stabilizer.so
PATCHED=2fc1ad46d96323e8c04553745069ae251dff9259abb8b4d0c6f877d265f9c554
MODDIR=${MODDIR:-${0%/*}}
[ -d "$MODDIR/system/vendor/lib64" ] || MODDIR=/data/adb/modules/morpho_expiry_bypass
SRC="$MODDIR/system/vendor/lib64/libmorpho_video_stabilizer.so"
log() { echo "$(date '+%Y-%m-%d %H:%M:%S') $*" >> "$LOG"; }
set +e
log "start; moddir=$MODDIR"
i=0
while [ $i -lt 30 ]; do
  [ -r "$TARGET" ] && break
  sleep 1
  i=$((i+1))
done
if [ ! -r "$TARGET" ]; then log "target not accessible after ${i}s; abort"; exit 0; fi
log "target accessible"
CUR=$(sha256sum "$TARGET" 2>/dev/null | awk '{print $1}')
log "current=$CUR"
if [ "$CUR" = "$PATCHED" ]; then log "already patched; skip"; exit 0; fi
if [ ! -r "$SRC" ]; then log "source missing: $SRC; abort"; exit 0; fi
log "bind $SRC -> $TARGET"
mount --bind "$SRC" "$TARGET" 2>>"$LOG"
RC=$?
log "mount rc=$RC"
if [ $RC -ne 0 ]; then log "mount failed; abort"; exit 0; fi
chcon u:object_r:vendor_file:s0 "$TARGET" 2>>"$LOG"
log "chcon rc=$?"
AFTER=$(sha256sum "$TARGET" 2>/dev/null | awk '{print $1}')
log "after=$AFTER"
if [ "$AFTER" = "$PATCHED" ]; then log "done ok"; else log "done hash mismatch"; fi
exit 0
