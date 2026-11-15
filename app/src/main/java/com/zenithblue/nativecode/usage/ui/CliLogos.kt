package com.zenithblue.nativecode.usage.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.zenithblue.nativecode.NC
import com.zenithblue.nativecode.agent.chat.ui.LogoContrast
import com.zenithblue.nativecode.usage.UsageModel
import java.util.concurrent.ConcurrentHashMap

/**
 * The app's real CLI logos (`assets/images/cli/<tool>.png`, the ones the chat new-thread sheet shows) as View tiles.
 * A dark logo (grok, opencode) sits on the light tile so it keeps >= 3:1 contrast, decided by the same [LogoContrast]
 * the chat uses. [agent] is a ccusage name (claude, codex, opencode, grok, antigravity).
 */
internal object CliLogos {
    private class Logo(val bmp: Bitmap, val light: Boolean)

    private val cache = ConcurrentHashMap<String, Logo>()
    private val main = Handler(Looper.getMainLooper())

    private fun file(tool: String) = if (tool == "qwen-code") "qwen-code.webp" else "$tool.png"

    private fun load(ctx: Context, tool: String): Logo? = cache[tool] ?: runCatching {
        val path = "images/cli/${file(tool)}"
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.assets.open(path).use { BitmapFactory.decodeStream(it, null, o) }
        var s = 1
        while (o.outWidth / (s * 2) >= 96) s *= 2
        val b = ctx.assets.open(path).use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s }) }!!
        val px = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
        Logo(b, LogoContrast.lightTile(px))
    }.getOrNull()?.also { cache[tool] = it }

    private fun dp(ctx: Context, v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()

    /** Square tile of [sizeDp] holding the logo of [agent]; decodes off the main thread the first time. */
    fun tile(ctx: Context, agent: String, sizeDp: Int): View {
        val app = ctx.applicationContext
        val tool = UsageModel.toolId(agent)
        val pad = dp(ctx, sizeDp) / 6
        val img = ImageView(app).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setPadding(pad, pad, pad, pad) }
        fun apply(l: Logo) {
            img.setImageBitmap(l.bmp)
            img.background = GradientDrawable().apply {
                setColor(if (l.light) NC.ON_SURFACE else NC.SURFACE_LOWEST); setStroke(dp(ctx, 1), NC.BORDER); cornerRadius = dp(ctx, 8).toFloat()
            }
        }
        img.background = GradientDrawable().apply { setColor(NC.SURFACE_LOWEST); setStroke(dp(ctx, 1), NC.BORDER); cornerRadius = dp(ctx, 8).toFloat() }
        val hit = cache[tool]
        if (hit != null) apply(hit) else Thread { load(app, tool)?.let { l -> main.post { apply(l) } } }.start()
        img.contentDescription = UsageModel.agentLabel(agent)
        return FrameLayout(app).apply {
            layoutParams = LinearLayout.LayoutParams(dp(ctx, sizeDp), dp(ctx, sizeDp))
            addView(img, FrameLayout.LayoutParams(-1, -1))
        }
    }

    /** Row of overlapping-free small logos, one per agent, for a card that lists the CLIs in play. */
    fun row(ctx: Context, agents: List<String>, sizeDp: Int = 22): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        agents.forEachIndexed { i, a ->
            addView(tile(ctx, a, sizeDp).apply { (layoutParams as LinearLayout.LayoutParams).leftMargin = if (i == 0) 0 else dp(ctx, 6) })
        }
    }
}
