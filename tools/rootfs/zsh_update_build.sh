#!/bin/sh
# Runs INSIDE the aarch64 chroot (see build_update_overlay.sh). Rebuilds zsh 5.9 exactly like
# build_gui_bundle.sh build_zsh(): modern-ncurses patch, and the two forced configure answers that
# GCC 15 cannot probe (zsh_cv_sys_killesrch, zsh_cv_sys_sigsuspend; without them $(...) hangs).
# In: /tmp/zb/zsh-5.9.tar.xz, /tmp/zb/zsh-5.9-modern-ncurses.patch. Out: /tmp/zb/dest/usr/bin/zsh-5.9
set -eu
cd /tmp/zb
apk add --no-cache ncurses-dev libcap-dev pcre-dev >/dev/null
rm -rf zsh-5.9 dest
tar -o -xf zsh-5.9.tar.xz   # -o: do not chown (single-uid user namespace)
patch -p1 --batch -d zsh-5.9 < zsh-5.9-modern-ncurses.patch
cd zsh-5.9
export zsh_cv_sys_killesrch=yes zsh_cv_sys_sigsuspend=yes
./configure --prefix=/usr --bindir=/usr/bin --sysconfdir=/etc --enable-etcdir=/etc \
  --enable-multibyte --enable-pcre --enable-cap --with-tcsetpgrp > ../configure.log 2>&1
grep -n 'BROKEN_KILL_ESRCH\|BROKEN_POSIX_SIGSUSPEND' config.h || true
# qemu-user makes the timestamp-driven autoconf regeneration loop; keep the generated files current
touch aclocal.m4 configure; sleep 1; touch config.h.in; sleep 1; touch stamp-h.in; sleep 1; touch config.h config.status Makefile
make -j"$(nproc)" > ../make.log 2>&1 || { tail -30 ../make.log; exit 1; }
timeout 60 Src/zsh -fc 'echo $(echo ok)' | grep -qx ok
make DESTDIR=/tmp/zb/dest install > ../install.log 2>&1 || { tail -20 ../install.log; exit 1; }
strip /tmp/zb/dest/usr/bin/zsh-5.9 2>/dev/null || true
echo ZSH_BUILD_OK
