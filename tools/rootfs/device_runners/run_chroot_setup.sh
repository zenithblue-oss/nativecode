#!/system/bin/sh
set -e
CHROOT_DEST="/data/local/tmp/chrootAlpine"
chroot "$CHROOT_DEST" /usr/bin/env -i PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin" /bin/sh /tmp/setup_alpaquita_family.sh alpaquita
chroot "$CHROOT_DEST" /usr/bin/env -i PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin" FLUX_GPU=virgl /bin/bash /tmp/setup_hw_accel_debian.sh
chroot "$CHROOT_DEST" /usr/bin/env -i PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin" FLUX_THEME=dark /bin/bash /tmp/setup_xfce4.sh
chroot "$CHROOT_DEST" /usr/bin/env -i PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin" /bin/sh /tmp/setup_customization_alpine.sh
chroot "$CHROOT_DEST" /usr/bin/env -i PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin" NC_SKIP_AI_CLI=0 /bin/bash /tmp/setup_cli_tools.sh
