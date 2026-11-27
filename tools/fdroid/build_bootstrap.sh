#!/usr/bin/env bash
# F-Droid flavor native build, step 2 (slow, ~30-60 min cold): trimmed termux-packages
# set built from source with TERMUX_APP__PACKAGE_NAME=$APP_ID (prefix
# /data/data/$APP_ID/files/usr, NO_DOCKER), assembled into bootstrap.tar.
# Output: tools/fdroid/out/bootstrap-fdroid.tar (+ jniLibs, see build_native.sh)
#         --install also copies it to app/src/fdroid/assets/bootstrap.tar
# Run build_native.sh first (shares the termux-packages work tree and built packages).
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
INSTALL=0; [ "${1:-}" = "--install" ] && INSTALL=1
resolve_ndk
checkout_termux_packages
apply_overlays
host_env
seed_mirrors

t0=$(date +%s)
( in_sandbox bash -c ". '$FDROID_DIR/lib.sh'; host_env; resolve_ndk; build_list '$FDROID_DIR/package-lists/bootstrap.txt'" ) 2>&1 | tee "$OUT/build_bootstrap.log"
[ "${PIPESTATUS[0]}" = 0 ]
echo "[*] build time $(( $(date +%s) - t0 )) s"
"$FDROID_DIR/build_loader.sh"
python3 -I "$FDROID_DIR/assemble_bootstrap.py" --debs "$TP/output" --app-id "$APP_ID" \
  --list "$FDROID_DIR/package-lists/bootstrap.txt" --stage "$WORK/stage-bootstrap" \
  --jnilibs "$OUT/jniLibs/arm64-v8a" --tar "$OUT/bootstrap-fdroid.tar" --loader "$OUT/loader.apk" --sde "$SOURCE_DATE_EPOCH"
( cd "$OUT" && sha256sum bootstrap-fdroid.tar jniLibs/arm64-v8a/*.so | tee SHA256SUMS )
if [ "$INSTALL" = 1 ]; then
  mkdir -p "$REPO_ROOT/app/src/fdroid/assets"; cp -f "$OUT/bootstrap-fdroid.tar" "$REPO_ROOT/app/src/fdroid/assets/bootstrap.tar"
fi
