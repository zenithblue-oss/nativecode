package com.zenithblue.nativecode.skills

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.ImageView
import com.zenithblue.nativecode.NC
import com.zenithblue.nativecode.R

internal object SkillIcons {

    private const val DIR = "images/skills/"

    fun file(id: String): String = MAP[id] ?: "nativecode.png"

    fun bind(ctx: Context, view: ImageView, id: String) {
        val name = file(id)
        val bmp = decode(ctx, name) ?: decode(ctx, "nativecode.png")
        if (bmp != null) {
            view.colorFilter = null
            view.setImageBitmap(bmp)
        } else {
            view.setImageResource(R.drawable.logo)
            view.setColorFilter(NC.PRIMARY)
        }
    }

    fun decode(ctx: Context, name: String): Bitmap? {
        return try {
            ctx.assets.open(DIR + name).use { BitmapFactory.decodeStream(it) }
        } catch (_: Exception) {
            null
        }
    }

    private val MAP = mapOf(
        "agent-bridge.md" to "nativecode.png",
        "gpu-accel.md" to "opengl.png",
        "adreno" to "adreno.png",
        "mali" to "mali.png",
        "vpu-mediacodec.md" to "vulkan.png",
        "install-dev-utils" to "git.png",
        "install-node-tools" to "node.png",
        "install-python-tools" to "python.png",
        "install-chromium" to "chrome.png",
        "install-blender" to "blender.png",
        "add-apps" to "linux.png",
        "install-adb" to "android.png",
        "install-hyperframes" to "chrome.png",
        "install-moneyprinter" to "python.png",
        "install-android-tools" to "androidstudio.png",
        "install-rea" to "javascript.png",
        "install-hermes" to "python.png",
        "install-ssh" to "linux.png",
        "build-android-app" to "kotlin.png",
        "build-web-app" to "javascript.png",
        "build-website" to "html5.png",
        "build-full-stack-app" to "typescript.png",
        "build-api" to "python.png",
        "turn-website-into-app" to "android.png",
        "test-website" to "chrome.png",
        "test-android-app" to "android.png",
        "get-library-docs" to "markdown.png",
        "build-python-tool" to "python.png",
        "make-2d-game" to "gamedev.png",
        "make-3d-web-game" to "threejs.png",
        "make-godot-game" to "godot.png",
        "make-game-assets" to "gamedev.png",
        "make-3d-assets" to "blender.png",
        "edit-images" to "gimp.png",
        "make-thumbnail" to "image.png",
        "remove-background" to "image.png",
        "make-vector-art" to "inkscape.png",
        "generate-images" to "image.png",
        "make-motion-video" to "video.png",
        "make-short-video" to "youtube.png",
        "make-react-video" to "react.png",
        "edit-video" to "ffmpeg.png",
        "add-subtitles" to "video.png",
        "make-voiceover" to "audio.png",
        "clean-audio" to "audio.png",
        "make-video-from-slides" to "video.png",
        "make-slides" to "document.png",
        "make-markdown-slides" to "markdown.png",
        "write-document" to "libreoffice.png",
        "make-pdf" to "pdf.png",
        "edit-pdf" to "pdf.png",
        "read-documents" to "document.png",
        "convert-documents" to "document.png",
        "make-spreadsheet" to "libreoffice.png",
        "frontend-design" to "html5.png",
        "theme-factory" to "css3.png",
        "taste-design" to "web.png",
        "design-mobile-compose-ui" to "kotlin.png",
        "design-system-tokens" to "css3.png",
        "make-vanilla-animation" to "javascript.png",
    )
}
