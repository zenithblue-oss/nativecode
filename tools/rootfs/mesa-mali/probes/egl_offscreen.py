#!/usr/bin/env python3
import ctypes
import os
import sys

EGL_DEFAULT_DISPLAY = 0
EGL_NO_DISPLAY = 0
EGL_NO_CONTEXT = 0
EGL_NO_SURFACE = 0
EGL_VENDOR = 0x3053
EGL_VERSION = 0x3054
EGL_EXTENSIONS = 0x3055
EGL_OPENGL_ES_API = 0x30A0
EGL_OPENGL_API = 0x30A2
EGL_SURFACE_TYPE = 0x3033
EGL_PBUFFER_BIT = 0x0001
EGL_RENDERABLE_TYPE = 0x3040
EGL_OPENGL_ES2_BIT = 0x0004
EGL_OPENGL_BIT = 0x0008
EGL_RED_SIZE = 0x3024
EGL_GREEN_SIZE = 0x3023
EGL_BLUE_SIZE = 0x3022
EGL_ALPHA_SIZE = 0x3021
EGL_NONE = 0x3038
EGL_WIDTH = 0x3057
EGL_HEIGHT = 0x3056
EGL_CONTEXT_CLIENT_VERSION = 0x3098
EGL_PLATFORM_SURFACELESS_MESA = 0x31DD
GL_VENDOR = 0x1F00
GL_RENDERER = 0x1F01
GL_VERSION = 0x1F02
GL_SHADING_LANGUAGE_VERSION = 0x8B8C
GL_FRAGMENT_SHADER = 0x8B30
GL_VERTEX_SHADER = 0x8B31
GL_COMPILE_STATUS = 0x8B81
GL_LINK_STATUS = 0x8B82
GL_COLOR_BUFFER_BIT = 0x00004000
GL_TRIANGLES = 0x0004
GL_FLOAT = 0x1406
GL_FALSE = 0
GL_RGBA = 0x1908
GL_UNSIGNED_BYTE = 0x1401
GL_FRAMEBUFFER = 0x8D40
GL_RENDERBUFFER = 0x8D41
GL_COLOR_ATTACHMENT0 = 0x8CE0
GL_FRAMEBUFFER_COMPLETE = 0x8CD5
GL_RGBA8 = 0x8058

def env():
    for k in ("GALLIUM_DRIVER", "MESA_LOADER_DRIVER_OVERRIDE", "LIBGL_DRIVERS_PATH",
              "VK_DRIVER_FILES", "LD_PRELOAD", "LD_LIBRARY_PATH"):
        print(f"{k}={os.environ.get(k, '(unset)')}")

def load():
    egl = ctypes.CDLL("libEGL.so.1", mode=os.RTLD_GLOBAL)
    gles = ctypes.CDLL("libGLESv2.so.2", mode=os.RTLD_GLOBAL)
    egl.eglGetError.restype = ctypes.c_int
    egl.eglGetDisplay.restype = ctypes.c_void_p
    egl.eglGetDisplay.argtypes = [ctypes.c_void_p]
    egl.eglInitialize.restype = ctypes.c_uint
    egl.eglInitialize.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_int), ctypes.POINTER(ctypes.c_int)]
    egl.eglQueryString.restype = ctypes.c_char_p
    egl.eglQueryString.argtypes = [ctypes.c_void_p, ctypes.c_int]
    egl.eglBindAPI.restype = ctypes.c_uint
    egl.eglChooseConfig.restype = ctypes.c_uint
    egl.eglCreatePbufferSurface.restype = ctypes.c_void_p
    egl.eglCreateContext.restype = ctypes.c_void_p
    egl.eglMakeCurrent.restype = ctypes.c_uint
    egl.eglGetProcAddress.restype = ctypes.c_void_p
    egl.eglGetProcAddress.argtypes = [ctypes.c_char_p]
    return egl, gles

def get_dpy(egl):
    getplat = egl.eglGetProcAddress(b"eglGetPlatformDisplayEXT") or egl.eglGetProcAddress(b"eglGetPlatformDisplay")
    dpy = None
    if getplat:
        fn = ctypes.CFUNCTYPE(ctypes.c_void_p, ctypes.c_uint, ctypes.c_void_p, ctypes.POINTER(ctypes.c_int))(getplat)
        dpy = fn(EGL_PLATFORM_SURFACELESS_MESA, None, None)
    if not dpy:
        dpy = egl.eglGetDisplay(None)
    return dpy

def c_int_array(vals):
    return (ctypes.c_int * len(vals))(*vals)

def main():
    env()
    egl, gles = load()
    dpy = get_dpy(egl)
    print("Display:", hex(dpy) if dpy else None)
    if not dpy:
        print("eglGetDisplay FAIL err=0x%x" % egl.eglGetError())
        return 1
    maj, mn = ctypes.c_int(), ctypes.c_int()
    if not egl.eglInitialize(dpy, ctypes.byref(maj), ctypes.byref(mn)):
        print("eglInitialize FAIL err=0x%x" % egl.eglGetError())
        return 1
    vendor = egl.eglQueryString(dpy, EGL_VENDOR)
    ver = egl.eglQueryString(dpy, EGL_VERSION)
    print("EGL %d.%d vendor=%s version=%s" % (
        maj.value, mn.value,
        vendor.decode() if vendor else None,
        ver.decode() if ver else None))
    egl.eglBindAPI(EGL_OPENGL_ES_API)
    egl.eglCreateContext.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p, ctypes.POINTER(ctypes.c_int)]
    egl.eglMakeCurrent.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p]
    egl.eglCreatePbufferSurface.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.POINTER(ctypes.c_int)]
    ctx_a = c_int_array([EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE])
    cfg = ctypes.c_void_p()
    ncfg = ctypes.c_int()
    attribs = c_int_array([
        EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
        EGL_NONE])
    ok = egl.eglChooseConfig(dpy, attribs, ctypes.byref(cfg), 1, ctypes.byref(ncfg))
    surf = None
    used_pbuffer = False
    used_fbo = False
    if ok and ncfg.value:
        pb = c_int_array([EGL_WIDTH, 64, EGL_HEIGHT, 64, EGL_NONE])
        surf = egl.eglCreatePbufferSurface(dpy, cfg, pb)
        used_pbuffer = bool(surf)
        if not surf:
            print("eglCreatePbufferSurface FAIL err=0x%x; using NO_SURFACE" % egl.eglGetError())
        ctx = egl.eglCreateContext(dpy, cfg, None, ctx_a)
    else:
        print("eglChooseConfig FAIL err=0x%x n=%d; configless+surfaceless" % (
            egl.eglGetError(), ncfg.value))
        ctx = egl.eglCreateContext(dpy, None, None, ctx_a)
    if not ctx:
        print("eglCreateContext FAIL err=0x%x" % egl.eglGetError())
        return 1
    if not egl.eglMakeCurrent(dpy, surf, surf, ctx):
        print("eglMakeCurrent FAIL err=0x%x" % egl.eglGetError())
        return 1
    gles.glGetString.restype = ctypes.c_char_p
    gles.glGetString.argtypes = [ctypes.c_uint]
    def gs(e):
        s = gles.glGetString(e)
        return s.decode() if s else None
    vendor_gl, renderer, version, glsl = gs(GL_VENDOR), gs(GL_RENDERER), gs(GL_VERSION), gs(GL_SHADING_LANGUAGE_VERSION)
    print("GL_VENDOR=%s" % vendor_gl)
    print("GL_RENDERER=%s" % renderer)
    print("GL_VERSION=%s" % version)
    print("GL_SHADING_LANGUAGE_VERSION=%s" % glsl)
    low = (renderer or "").lower()
    fake = (not renderer) or any(x in low for x in (
        "llvmpipe", "softpipe", "swrast", "virgl", "virpipe", "libgles_mali"))
    print("BACKEND_IDENTITY=%s" % ("NOT_HARDWARE_OR_FALLBACK" if fake else "CANDIDATE"))
    if "zink" in low:
        print("BACKEND_KIND=ZINK")
    elif "panfrost" in low:
        print("BACKEND_KIND=PANFROST")
    else:
        print("BACKEND_KIND=OTHER")
    if not used_pbuffer:
        gles.glGenRenderbuffers.argtypes = [ctypes.c_int, ctypes.POINTER(ctypes.c_uint)]
        gles.glBindRenderbuffer.argtypes = [ctypes.c_uint, ctypes.c_uint]
        gles.glRenderbufferStorage.argtypes = [ctypes.c_uint, ctypes.c_uint, ctypes.c_int, ctypes.c_int]
        gles.glGenFramebuffers.argtypes = [ctypes.c_int, ctypes.POINTER(ctypes.c_uint)]
        gles.glBindFramebuffer.argtypes = [ctypes.c_uint, ctypes.c_uint]
        gles.glFramebufferRenderbuffer.argtypes = [ctypes.c_uint, ctypes.c_uint, ctypes.c_uint, ctypes.c_uint]
        gles.glCheckFramebufferStatus.restype = ctypes.c_uint
        rb = ctypes.c_uint()
        fb = ctypes.c_uint()
        gles.glGenRenderbuffers(1, ctypes.byref(rb))
        gles.glBindRenderbuffer(GL_RENDERBUFFER, rb)
        gles.glRenderbufferStorage(GL_RENDERBUFFER, GL_RGBA8, 64, 64)
        gles.glGenFramebuffers(1, ctypes.byref(fb))
        gles.glBindFramebuffer(GL_FRAMEBUFFER, fb)
        gles.glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, rb)
        st = gles.glCheckFramebufferStatus(GL_FRAMEBUFFER)
        print("FBO status=0x%x" % st)
        if st != GL_FRAMEBUFFER_COMPLETE:
            print("PIXEL=SKIPPED no pbuffer/FBO")
            return 2 if fake else 0
        used_fbo = True

    def compile_shader(kind, src):
        gles.glCreateShader.restype = ctypes.c_uint
        s = gles.glCreateShader(kind)
        p = ctypes.c_char_p(src.encode())
        gles.glShaderSource(s, 1, ctypes.byref(p), None)
        gles.glCompileShader(s)
        okv = ctypes.c_int()
        gles.glGetShaderiv(s, GL_COMPILE_STATUS, ctypes.byref(okv))
        if not okv.value:
            buf = ctypes.create_string_buffer(512)
            gles.glGetShaderInfoLog(s, 512, None, buf)
            print("shader fail", buf.value)
            return 0
        return s

    vs = compile_shader(GL_VERTEX_SHADER,
                        "attribute vec2 a; void main(){ gl_Position = vec4(a,0.0,1.0); }")
    fs = compile_shader(GL_FRAGMENT_SHADER,
                        "void main(){ gl_FragColor = vec4(0.0,1.0,0.0,1.0); }")
    if not vs or not fs:
        return 1
    gles.glCreateProgram.restype = ctypes.c_uint
    prog = gles.glCreateProgram()
    gles.glAttachShader(prog, vs)
    gles.glAttachShader(prog, fs)
    gles.glBindAttribLocation(prog, 0, b"a")
    gles.glLinkProgram(prog)
    okv = ctypes.c_int()
    gles.glGetProgramiv(prog, GL_LINK_STATUS, ctypes.byref(okv))
    if not okv.value:
        buf = ctypes.create_string_buffer(512)
        gles.glGetProgramInfoLog(prog, 512, None, buf)
        print("link fail", buf.value)
        return 1
    gles.glUseProgram(prog)
    tri = (ctypes.c_float * 6)(-1.0, -1.0, 3.0, -1.0, -1.0, 3.0)
    gles.glViewport(0, 0, 64, 64)
    gles.glClearColor(0, 0, 0, 1)
    gles.glClear(GL_COLOR_BUFFER_BIT)
    gles.glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 0, tri)
    gles.glEnableVertexAttribArray(0)
    gles.glDrawArrays(GL_TRIANGLES, 0, 3)
    gles.glFinish()
    px = (ctypes.c_ubyte * 4)()
    gles.glReadPixels(32, 32, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, px)
    print("PIXEL R=%u G=%u B=%u A=%u" % (px[0], px[1], px[2], px[3]))
    pass_px = px[0] == 0 and px[1] == 255 and px[2] == 0 and px[3] == 255
    print("PIXEL_PASS=%s" % ("YES" if pass_px else "NO"))
    return 0 if pass_px and not fake else 3

if __name__ == "__main__":
    sys.exit(main())
