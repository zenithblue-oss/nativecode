# Alpaquita glibc GUI Build-Dependency Audit Report (Pass A)

**Date:** 2026-09-04 16:15:00 UTC  
**Repository:** BellSoft Alpaquita Linux glibc `stream` (core + universe, aarch64)  
**Total Distro Packages:** 5016  

---

## 1. Executive Summary

**VERDICT: PASS — KEEP ALPAQUITA GLIBC**

The Alpaquita glibc AArch64 package repositories **contain all fundamental toolchain, X11, Wayland, and GTK 3.0 development headers and shared libraries** necessary to build XFCE 4.20 and TigerVNC / Xvnc.

Alpaquita uses Alpine-inherited naming for GTK: `gtk+3.0` and `gtk+3.0-dev`.  
The core graphical stack (`cairo`, `pango`, `glib`, `gdk-pixbuf`, `gobject-introspection`, `mesa`, `xorg-server-dev`, `pixman`, `libdrm`) is fully packaged with `-dev` headers.

---

## 2. Dependency Audit Matrix

### Toolchains & Build Systems
| Package | Status | Notes |
| :--- | :--- | :--- |
| `build-base` | **PRESENT** | gcc, g++, make, libc-dev |
| `meson` | **PRESENT** | Modern build system for Xfce / TigerVNC |
| `samurai` | **PRESENT** | C-based Ninja implementation (provides `cmd:ninja`) |
| `pkgconf` | **PRESENT** | Modern `pkg-config` implementation |
| `cmake` | **PRESENT** | Required for TigerVNC cmake build |
| `autoconf`, `automake`, `libtool` | **PRESENT** | Autotools suite |
| `gettext`, `gettext-dev` | **PRESENT** | NLS / localization build tools |

### Core GTK 3 & GNOME Platform Libraries
| Package | Status | Notes |
| :--- | :--- | :--- |
| `gtk+3.0`, `gtk+3.0-dev` | **PRESENT** | Full GTK 3.24 development stack |
| `glib`, `glib-dev` | **PRESENT** | Core application framework |
| `gobject-introspection`, `gobject-introspection-dev` | **PRESENT** | Introspection typelib generator |
| `gdk-pixbuf`, `gdk-pixbuf-dev` | **PRESENT** | Image loading library |
| `cairo`, `cairo-dev` | **PRESENT** | 2D vector graphics library |
| `pango`, `pango-dev` | **PRESENT** | Text rendering / layout engine |

### X11 & Graphics Libraries
| Package | Status | Notes |
| :--- | :--- | :--- |
| `libx11`, `libx11-dev` | **PRESENT** | Core X11 client library |
| `libxext`, `libxext-dev` | **PRESENT** | X11 miscellaneous extensions |
| `libxrandr`, `libxrandr-dev` | **PRESENT** | Resize and rotate extension |
| `libxinerama`, `libxinerama-dev` | **PRESENT** | Multi-monitor extension |
| `libxrender`, `libxrender-dev` | **PRESENT** | Render extension |
| `libxtst`, `libxtst-dev` | **PRESENT** | X11 testing / record extension |
| `libxfixes`, `libxfixes-dev` | **PRESENT** | Workaround fixes extension |
| `libxdamage`, `libxdamage-dev` | **PRESENT** | Damage tracking extension |
| `libxcomposite`, `libxcomposite-dev` | **PRESENT** | Composite window extension |
| `libxcursor`, `libxcursor-dev` | **PRESENT** | Cursor management library |
| `libxi`, `libxi-dev` | **PRESENT** | X11 Input extension |
| `libxfont2`, `libxfont2-dev` | **PRESENT** | X font library |
| `xorg-server-dev` | **PRESENT** | Xorg server headers (for TigerVNC Xvnc module) |
| `xorgproto` | **PRESENT** | Complete X11 protocol headers |
| `pixman`, `pixman-dev` | **PRESENT** | Pixel manipulation library |
| `libxshmfence`, `libxshmfence-dev` | **PRESENT** | Shared memory synchronization |
| `libepoxy`, `libepoxy-dev` | **PRESENT** | OpenGL function pointer management |
| `mesa`, `mesa-gl`, `mesa-dri-gallium` | **PRESENT** | OpenGL / Mesa 3D runtime & drivers |

### Wayland, Input, & Security
| Package | Status | Notes |
| :--- | :--- | :--- |
| `wayland`, `wayland-dev` | **PRESENT** | Wayland display protocol library |
| `wayland-protocols` | **PRESENT** | Extended Wayland protocols |
| `libdrm`, `libdrm-dev` | **PRESENT** | Direct Rendering Manager interface |
| `libxkbcommon`, `libxkbcommon-dev` | **PRESENT** | Keyboard mapping library |
| `gnutls`, `gnutls-dev` | **PRESENT** | Required for TigerVNC TLS encryption |
| `libjpeg-turbo`, `libjpeg-turbo-dev` | **PRESENT** | High-performance JPEG compression |
| `openssl`, `openssl-dev` | **PRESENT** | TLS cryptography library |

---

## 3. NativeCode GUI Bundle Architecture

Because all underlying build dependencies are present, we adopt the recommended single-bundle compilation pipeline:

```text
Alpaquita glibc APK dependencies
   (build-base, gtk+3.0-dev, glib-dev, cairo-dev, pango-dev,
    libx11-dev, libxrandr-dev, xorg-server-dev, pixman-dev, etc.)
               │
               ▼
   NativeCode GUI Build Pipeline
               │
   ├── Build Xfce 4.20 core (libxfce4util, xfconf, libxfce4ui, garcon, exo,
   │                        xfwm4, xfce4-panel, xfce4-session, xfdesktop,
   │                        thunar, xfce4-terminal, xfce4-settings)
   ├── Build TigerVNC (Xvnc / vncpasswd)
   ├── Build Zsh & fastfetch
   └── Package into:
       tools/rootfs/out/nativecode-gui-alpaquita-glibc-aarch64.tar.zst
               │
               ▼
   Final Prebuilt RootFS Assembly:
   Alpaquita minirootfs + nativecode-gui bundle + AI CLIs + Dev Stack
```

**Conclusion:** Keep Alpaquita glibc. Proceed with native build and assembly pipeline.
