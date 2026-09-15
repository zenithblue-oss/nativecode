#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>

#ifndef EGL_PLATFORM_SURFACELESS_MESA
#define EGL_PLATFORM_SURFACELESS_MESA 0x31DD
#endif

static const char *vert =
    "attribute vec2 a;\n"
    "void main(){ gl_Position = vec4(a, 0.0, 1.0); }\n";
static const char *frag =
    "void main(){ gl_FragColor = vec4(0.0, 1.0, 0.0, 1.0); }\n";

static GLuint compile(GLenum type, const char *src)
{
    GLuint s = glCreateShader(type);
    glShaderSource(s, 1, &src, NULL);
    glCompileShader(s);
    GLint ok = 0;
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[512];
        glGetShaderInfoLog(s, sizeof(log), NULL, log);
        fprintf(stderr, "shader compile fail: %s\n", log);
        return 0;
    }
    return s;
}

int main(void)
{
    printf("GALLIUM_DRIVER=%s\n", getenv("GALLIUM_DRIVER") ? getenv("GALLIUM_DRIVER") : "(unset)");
    printf("MESA_LOADER_DRIVER_OVERRIDE=%s\n",
           getenv("MESA_LOADER_DRIVER_OVERRIDE") ? getenv("MESA_LOADER_DRIVER_OVERRIDE") : "(unset)");
    printf("LIBGL_DRIVERS_PATH=%s\n", getenv("LIBGL_DRIVERS_PATH") ? getenv("LIBGL_DRIVERS_PATH") : "(unset)");
    printf("VK_DRIVER_FILES=%s\n", getenv("VK_DRIVER_FILES") ? getenv("VK_DRIVER_FILES") : "(unset)");
    printf("LD_PRELOAD=%s\n", getenv("LD_PRELOAD") ? getenv("LD_PRELOAD") : "(unset)");

    EGLDisplay dpy = EGL_NO_DISPLAY;
    PFNEGLGETPLATFORMDISPLAYEXTPROC getplat =
        (PFNEGLGETPLATFORMDISPLAYEXTPROC)eglGetProcAddress("eglGetPlatformDisplayEXT");
    if (!getplat)
        getplat = (PFNEGLGETPLATFORMDISPLAYEXTPROC)eglGetProcAddress("eglGetPlatformDisplay");
    if (getplat)
        dpy = getplat(EGL_PLATFORM_SURFACELESS_MESA, EGL_DEFAULT_DISPLAY, NULL);
    if (dpy == EGL_NO_DISPLAY)
        dpy = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (dpy == EGL_NO_DISPLAY) {
        printf("eglGetDisplay FAIL err=0x%x\n", eglGetError());
        return 1;
    }

    EGLint major = 0, minor = 0;
    if (!eglInitialize(dpy, &major, &minor)) {
        printf("eglInitialize FAIL err=0x%x\n", eglGetError());
        return 1;
    }
    printf("EGL %d.%d vendor=%s version=%s\n", major, minor,
           eglQueryString(dpy, EGL_VENDOR), eglQueryString(dpy, EGL_VERSION));

    eglBindAPI(EGL_OPENGL_ES_API);
    EGLint cfg_a[] = {
        EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
        EGL_NONE
    };
    EGLConfig cfg;
    EGLint ncfg = 0;
    if (!eglChooseConfig(dpy, cfg_a, &cfg, 1, &ncfg) || ncfg == 0) {
        printf("eglChooseConfig pbuffer FAIL err=0x%x; try surfaceless\n", eglGetError());
        EGLint cfg2[] = {
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
            EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_NONE
        };
        if (!eglChooseConfig(dpy, cfg2, &cfg, 1, &ncfg) || ncfg == 0) {
            printf("eglChooseConfig FAIL err=0x%x\n", eglGetError());
            return 1;
        }
    }

    EGLint pb[] = { EGL_WIDTH, 64, EGL_HEIGHT, 64, EGL_NONE };
    EGLSurface surf = eglCreatePbufferSurface(dpy, cfg, pb);
    int used_pbuffer = 1;
    if (surf == EGL_NO_SURFACE) {
        printf("eglCreatePbufferSurface FAIL err=0x%x; using NO_SURFACE\n", eglGetError());
        surf = EGL_NO_SURFACE;
        used_pbuffer = 0;
    }

    EGLint ctx_a[] = { EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE };
    EGLContext ctx = eglCreateContext(dpy, cfg, EGL_NO_CONTEXT, ctx_a);
    if (ctx == EGL_NO_CONTEXT) {
        printf("eglCreateContext FAIL err=0x%x\n", eglGetError());
        return 1;
    }
    if (!eglMakeCurrent(dpy, surf, surf, ctx)) {
        printf("eglMakeCurrent FAIL err=0x%x\n", eglGetError());
        return 1;
    }

    const char *vendor = (const char *)glGetString(GL_VENDOR);
    const char *renderer = (const char *)glGetString(GL_RENDERER);
    const char *version = (const char *)glGetString(GL_VERSION);
    const char *glsl = (const char *)glGetString(GL_SHADING_LANGUAGE_VERSION);
    printf("GL_VENDOR=%s\n", vendor ? vendor : "(null)");
    printf("GL_RENDERER=%s\n", renderer ? renderer : "(null)");
    printf("GL_VERSION=%s\n", version ? version : "(null)");
    printf("GL_SHADING_LANGUAGE_VERSION=%s\n", glsl ? glsl : "(null)");

    int fake = 0;
    if (!renderer)
        fake = 1;
    else if (strcasestr(renderer, "llvmpipe") || strcasestr(renderer, "softpipe") ||
             strcasestr(renderer, "swrast") || strcasestr(renderer, "VirGL") ||
             strcasestr(renderer, "virpipe") || strcasestr(renderer, "libGLES_mali"))
        fake = 1;
    printf("BACKEND_IDENTITY=%s\n", fake ? "NOT_HARDWARE_OR_FALLBACK" : "CANDIDATE");
    if (renderer && strcasestr(renderer, "zink"))
        printf("BACKEND_KIND=ZINK\n");
    else if (renderer && strcasestr(renderer, "panfrost"))
        printf("BACKEND_KIND=PANFROST\n");
    else
        printf("BACKEND_KIND=OTHER\n");

    if (!used_pbuffer) {
        printf("PIXEL=SKIPPED no pbuffer\n");
        return fake ? 2 : 0;
    }

    GLuint vs = compile(GL_VERTEX_SHADER, vert);
    GLuint fs = compile(GL_FRAGMENT_SHADER, frag);
    if (!vs || !fs)
        return 1;
    GLuint prog = glCreateProgram();
    glAttachShader(prog, vs);
    glAttachShader(prog, fs);
    glBindAttribLocation(prog, 0, "a");
    glLinkProgram(prog);
    GLint ok = 0;
    glGetProgramiv(prog, GL_LINK_STATUS, &ok);
    if (!ok) {
        char log[512];
        glGetProgramInfoLog(prog, sizeof(log), NULL, log);
        fprintf(stderr, "link fail: %s\n", log);
        return 1;
    }
    glUseProgram(prog);
    const GLfloat tri[] = { -1.f, -1.f, 3.f, -1.f, -1.f, 3.f };
    glViewport(0, 0, 64, 64);
    glClearColor(0, 0, 0, 1);
    glClear(GL_COLOR_BUFFER_BIT);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 0, tri);
    glEnableVertexAttribArray(0);
    glDrawArrays(GL_TRIANGLES, 0, 3);
    glFinish();
    unsigned char px[4] = {0};
    glReadPixels(32, 32, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, px);
    printf("PIXEL R=%u G=%u B=%u A=%u\n", px[0], px[1], px[2], px[3]);
    int pass = (px[0] == 0 && px[1] == 255 && px[2] == 0 && px[3] == 255);
    printf("PIXEL_PASS=%s\n", pass ? "YES" : "NO");
    return (pass && !fake) ? 0 : 3;
}
