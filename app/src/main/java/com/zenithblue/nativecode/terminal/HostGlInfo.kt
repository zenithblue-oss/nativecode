package com.zenithblue.nativecode.terminal

import android.content.Context
import android.content.pm.PackageManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.util.Log

/**
 * Host GPU strings from Android EGL/GLES + PackageManager Vulkan features.
 * No guest scripts. Safe for the Hardware page on Play builds.
 */
object HostGlInfo {

    private const val TAG = "HostGlInfo"

    data class Info(
        val gpuName: String,
        val glVendor: String,
        val glVersion: String,
        val glslVersion: String,
        val vulkanVersion: String
    )

    @Volatile
    private var cached: Info? = null

    fun query(ctx: Context): Info {
        cached?.let { return it }
        val gl = queryGl()
        val info = Info(
            gpuName = gl.renderer.ifBlank { "unknown" },
            glVendor = gl.vendor.ifBlank { "unknown" },
            glVersion = gl.version.ifBlank { "unknown" },
            glslVersion = gl.glsl.ifBlank { "unknown" },
            vulkanVersion = queryVulkanVersion(ctx)
        )
        Log.i(
            TAG,
            "gpuName=${info.gpuName} glVendor=${info.glVendor} gl=${info.glVersion} vk=${info.vulkanVersion}"
        )
        cached = info
        return info
    }

    fun formatVulkanVersion(packed: Int): String {
        val major = packed ushr 22
        val minor = (packed ushr 12) and 0x3ff
        val patch = packed and 0xfff
        return "$major.$minor.$patch"
    }

    fun queryVulkanVersion(ctx: Context): String {
        return try {
            val feat = ctx.packageManager.systemAvailableFeatures
                ?.firstOrNull { it.name == PackageManager.FEATURE_VULKAN_HARDWARE_VERSION }
            if (feat == null || feat.version <= 0) "none" else formatVulkanVersion(feat.version)
        } catch (_: Throwable) {
            "none"
        }
    }

    private data class Gl(
        val renderer: String,
        val vendor: String,
        val version: String,
        val glsl: String
    )

    private fun queryGl(): Gl {
        var display = EGL14.EGL_NO_DISPLAY
        var context = EGL14.EGL_NO_CONTEXT
        var surface = EGL14.EGL_NO_SURFACE
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) return Gl("", "", "", "")
            val eglVer = IntArray(2)
            if (!EGL14.eglInitialize(display, eglVer, 0, eglVer, 1)) return Gl("", "", "", "")
            val attribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) || num[0] < 1) {
                return Gl("", "", "", "")
            }
            val cfg = configs[0] ?: return Gl("", "", "", "")
            context = createEsContext(display, cfg, 3)
            if (context == EGL14.EGL_NO_CONTEXT) context = createEsContext(display, cfg, 2)
            if (context == EGL14.EGL_NO_CONTEXT) return Gl("", "", "", "")
            val pbuf = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
            surface = EGL14.eglCreatePbufferSurface(display, cfg, pbuf, 0)
            if (surface == EGL14.EGL_NO_SURFACE) return Gl("", "", "", "")
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) return Gl("", "", "", "")
            return Gl(
                renderer = GLES20.glGetString(GLES20.GL_RENDERER).orEmpty(),
                vendor = GLES20.glGetString(GLES20.GL_VENDOR).orEmpty(),
                version = GLES20.glGetString(GLES20.GL_VERSION).orEmpty(),
                glsl = GLES20.glGetString(GLES20.GL_SHADING_LANGUAGE_VERSION).orEmpty()
            )
        } catch (t: Throwable) {
            Log.w(TAG, "GL query failed", t)
            return Gl("", "", "", "")
        } finally {
            try {
                if (display != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(
                        display,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT
                    )
                    if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                    if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                    EGL14.eglTerminate(display)
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun createEsContext(
        display: android.opengl.EGLDisplay,
        cfg: EGLConfig,
        esVersion: Int
    ): android.opengl.EGLContext {
        val attr = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, esVersion, EGL14.EGL_NONE)
        return EGL14.eglCreateContext(display, cfg, EGL14.EGL_NO_CONTEXT, attr, 0)
            ?: EGL14.EGL_NO_CONTEXT
    }
}
