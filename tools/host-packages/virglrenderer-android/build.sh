TERMUX_PKG_HOMEPAGE=https://virgil3d.github.io/
TERMUX_PKG_DESCRIPTION="Venus/VirGL vtest server for NativeCode (Android Vulkan + GLES)"
TERMUX_PKG_LICENSE="MIT"
TERMUX_PKG_MAINTAINER="@nativecode"
TERMUX_PKG_VERSION="1.3.0"
_LIBEPOXY_VERSION="1.5.10"
TERMUX_PKG_REVISION=5

TERMUX_PKG_SRCURL=(
	https://gitlab.freedesktop.org/virgl/virglrenderer/-/archive/virglrenderer-${TERMUX_PKG_VERSION}/virglrenderer-virglrenderer-${TERMUX_PKG_VERSION}.tar.gz
	https://github.com/anholt/libepoxy/archive/refs/tags/${_LIBEPOXY_VERSION}.tar.gz
)
TERMUX_PKG_SHA256=(
	56170f8caa1bb642a2624b649e3bcca095ec2834814e5c308efc8a85a709e4ce
	a7ced37f4102b745ac86d6a70a9da399cc139ff168ba6b8002b4d8d43c900c15
)
# Venus talks to Android libvulkan.so. VirGL GLES uses system EGL (no ANGLE).
# No TERMUX_PKG_DEPENDS: empty string is parsed as a missing package named "".
TERMUX_PKG_HOSTBUILD=true

termux_step_post_get_source() {
	mv libepoxy-${_LIBEPOXY_VERSION} libepoxy
}

termux_step_host_build() {
	if [[ "$TERMUX_ON_DEVICE_BUILD" == "false" ]]; then
		export PATH="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin:$PATH"
		export CCTERMUX_HOST_PLATFORM="$TERMUX_HOST_PLATFORM$TERMUX_PKG_API_LEVEL"
		if [[ "$TERMUX_ARCH" == "arm" ]]; then
			CCTERMUX_HOST_PLATFORM="armv7a-linux-androideabi$TERMUX_PKG_API_LEVEL"
		fi
	fi

	local _INSTALL_PREFIX=$TERMUX_PREFIX/opt/$TERMUX_PKG_NAME
	local _NDK_SYSROOT="$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot"
	local _API_LIBDIR="$_NDK_SYSROOT/usr/lib/$TERMUX_HOST_PLATFORM/$TERMUX_PKG_API_LEVEL"
	if [[ "$TERMUX_ARCH" == "arm" ]]; then
		_API_LIBDIR="$_NDK_SYSROOT/usr/lib/arm-linux-androideabi/$TERMUX_PKG_API_LEVEL"
	fi

	PKG_CONFIG="$TERMUX_PKG_TMPDIR/host-build-pkg-config"
	local _HOST_PKGCONFIG
	_HOST_PKGCONFIG=$(command -v pkg-config)
	mkdir -p "$TERMUX_PKG_TMPDIR/pkgconfig"
	cat > "$TERMUX_PKG_TMPDIR/pkgconfig/vulkan.pc" <<-EOF
		prefix=$_NDK_SYSROOT/usr
		libdir=$_API_LIBDIR
		includedir=\${prefix}/include
		Name: vulkan
		Description: Android NDK Vulkan
		Version: 1.3.0
		Libs: -L\${libdir} -lvulkan
		Cflags: -I\${includedir}
	EOF
	cat > "$PKG_CONFIG" <<-HERE
		#!/bin/sh
		export PKG_CONFIG_DIR=
		export PKG_CONFIG_LIBDIR=$_INSTALL_PREFIX/lib/pkgconfig:$TERMUX_PKG_TMPDIR/pkgconfig
		exec $_HOST_PKGCONFIG "\$@"
	HERE
	chmod +x "$PKG_CONFIG"

	AR=$(command -v llvm-ar)
	if [[ "$TERMUX_ON_DEVICE_BUILD" == "false" ]]; then
		CC=$(command -v "$CCTERMUX_HOST_PLATFORM-clang")
		CXX=$(command -v "$CCTERMUX_HOST_PLATFORM-clang++")
	else
		CC=$(command -v "$TERMUX_HOST_PLATFORM-clang")
		CXX=$(command -v "$TERMUX_HOST_PLATFORM-clang++")
	fi
	LD=$(command -v ld.lld)
	CFLAGS="-D__TERMUX__ -D__ANDROID__"
	CPPFLAGS="-D__TERMUX__ -D__ANDROID__"
	CXXFLAGS="-D__TERMUX__ -D__ANDROID__"
	LDFLAGS="-Wl,-rpath=$_INSTALL_PREFIX/lib -Wl,--no-as-needed -L$_API_LIBDIR -landroid -lvulkan"
	STRIP=$(command -v llvm-strip)
	termux_setup_meson

	mkdir -p libepoxy-build
	$TERMUX_MESON $TERMUX_PKG_SRCDIR/libepoxy libepoxy-build \
		--cross-file $TERMUX_MESON_CROSSFILE \
		--prefix=$_INSTALL_PREFIX \
		--libdir lib \
		-Degl=yes -Dglx=no -Dx11=false
	ninja -C libepoxy-build install -j $TERMUX_PKG_MAKE_PROCESSES

	# 0011 renames the GBM helper under __TERMUX__; one call site still uses the old name.
	sed -i 's/vkr_gbm_get_fd_info_from_allocation_info/vkr_get_fd_info_from_allocation_info/g' \
		"$TERMUX_PKG_SRCDIR/src/venus/vkr_device_memory.c"

	mkdir -p virglrenderer-build
	$TERMUX_MESON $TERMUX_PKG_SRCDIR virglrenderer-build \
		--cross-file $TERMUX_MESON_CROSSFILE \
		--prefix=$_INSTALL_PREFIX \
		--libdir lib \
		-Dvenus=true \
		-Dplatforms=egl
	ninja -C virglrenderer-build install -j $TERMUX_PKG_MAKE_PROCESSES

	mv $_INSTALL_PREFIX/bin/virgl_test_server $TERMUX_PREFIX/bin/virgl_test_server_android
	# Venus needs this sibling binary (proxy_server execv). Keep libexec.
	if [[ -x $_INSTALL_PREFIX/libexec/virgl_render_server ]]; then
		mkdir -p $TERMUX_PREFIX/libexec
		cp -f $_INSTALL_PREFIX/libexec/virgl_render_server $TERMUX_PREFIX/libexec/virgl_render_server
	fi

	rm -rf $_INSTALL_PREFIX/{bin,include,lib/pkgconfig}
}

termux_step_configure() {
	rm -rf $TERMUX_HOSTBUILD_MARKER
}

termux_step_make() {
	:
}

termux_step_make_install() {
	:
}

termux_step_install_license() {
	mkdir -p $TERMUX_PREFIX/share/doc/$TERMUX_PKG_NAME
	cp $TERMUX_PKG_SRCDIR/COPYING $TERMUX_PREFIX/share/doc/$TERMUX_PKG_NAME/COPYING-virglrenderer
	cp $TERMUX_PKG_SRCDIR/libepoxy/COPYING $TERMUX_PREFIX/share/doc/$TERMUX_PKG_NAME/COPYING-libepoxy
	cp $TERMUX_PKG_BUILDER_DIR/COPYING-gl4es $TERMUX_PREFIX/share/doc/$TERMUX_PKG_NAME/COPYING-gl4es
}
