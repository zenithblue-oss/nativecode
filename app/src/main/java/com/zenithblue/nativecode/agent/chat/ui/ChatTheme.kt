package com.zenithblue.nativecode.agent.chat.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zenithblue.nativecode.NC
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** App palette (DesignTokens.NC) for Compose: obsidian surfaces, green primary, square cyber-brutalist edges. */
internal object C {
    val bg = Color(NC.BG)
    val lowest = Color(NC.SURFACE_LOWEST)
    val low = Color(NC.SURFACE_LOW)
    val container = Color(NC.SURFACE_CONTAINER)
    val high = Color(NC.SURFACE_HIGH)
    val highest = Color(NC.SURFACE_HIGHEST)
    val on = Color(NC.ON_SURFACE)
    val onVar = Color(NC.ON_SURF_VAR)
    val outline = Color(NC.OUTLINE)
    val border = Color(NC.OUTLINE_VAR)
    val borderVar = Color(NC.BORDER_VAR)
    val shadow = Color(NC.SHADOW_DARK)
    val shadowGreen = Color(NC.SHADOW_GREEN)
    val primary = Color(NC.PRIMARY)
    val onPrimary = Color(NC.ON_PRIMARY)
    val tertiary = Color(NC.TERTIARY_CON)
    val error = Color(NC.ERROR)
    val errorCon = Color(NC.ERROR_CON)
    val addBg = Color(0x2243E188)
    val delBg = Color(0x22FFB4AB)
}

/**
 * The one spacing scale (4/8/12/16/24 dp) of the chat screens, public so other Compose chrome (the terminal
 * tab strip) uses the same values. Use the role names where one fits; no other spacing values.
 */
object Space {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp

    /** Left/right inset of every screen, header, list row and message. */
    val edge = l
    /** Padding inside a card, chip row, sheet section or the composer. */
    val inset = m
    /** Between consecutive items in a row or column, and between an icon and its label. */
    val gap = s
    /** Between message blocks (turns, user vs assistant) and between sections. */
    val block = l
}

/** Short alias inside this package. */
internal val S = Space

internal val Mono = FontFamily.Monospace
internal val Square = RoundedCornerShape(0.dp)

/** The one text scale of the chat screens. Sizes in sp, so font scale applies. */
internal object T {
    val title = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.1).sp)
    val heading = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
    val body = TextStyle(fontSize = 15.sp, lineHeight = 22.sp)
    val bodySm = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
    val label = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
    val overline = TextStyle(fontFamily = Mono, fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
    val meta = TextStyle(fontFamily = Mono, fontSize = 11.sp, lineHeight = 14.sp)
    val code = TextStyle(fontFamily = Mono, fontSize = 13.sp, lineHeight = 19.sp)
    val codeSm = TextStyle(fontFamily = Mono, fontSize = 12.sp, lineHeight = 17.sp)
}

@Composable
internal fun ChatTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.primary, onPrimary = C.onPrimary, background = C.bg, onBackground = C.on,
            surface = C.low, onSurface = C.on, surfaceVariant = C.container, onSurfaceVariant = C.onVar,
            outline = C.border, error = C.error, secondary = C.onVar, surfaceContainer = C.container,
            surfaceContainerHigh = C.high, surfaceContainerLow = C.low, surfaceContainerLowest = C.lowest,
        ),
        content = content,
    )
}

/** Small mono label: meta lines, counts, badges. */
@Composable
internal fun Label(text: String, modifier: Modifier = Modifier, color: Color = C.onVar, size: Int = 11, bold: Boolean = false) {
    Text(
        text, modifier = modifier, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis,
        style = TextStyle(fontFamily = Mono, fontSize = size.sp, lineHeight = (size + 4).sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal),
    )
}

/** Uppercase tracked section header. */
@Composable
internal fun Overline(text: String, modifier: Modifier = Modifier, color: Color = C.onVar) =
    Text(text.uppercase(), modifier, color = color, style = T.overline, maxLines = 1, overflow = TextOverflow.Ellipsis)

/** Vector icon; [cd] null = decorative (the parent carries the description). */
@Composable
internal fun NcIcon(icon: ImageVector, cd: String?, modifier: Modifier = Modifier, tint: Color = C.onVar, size: Dp = 18.dp) =
    Icon(icon, cd, modifier.size(size), tint = tint)

/** 48 dp touch target, square ripple, icon-only (TalkBack label required). */
@Composable
internal fun IconBtn(
    icon: ImageVector, cd: String, modifier: Modifier = Modifier, tint: Color = C.on, size: Dp = 20.dp, box: Dp = 48.dp,
    enabled: Boolean = true, onClick: () -> Unit,
) {
    Box(
        modifier.size(box).clickable(enabled = enabled, role = Role.Button, onClickLabel = cd, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, cd, Modifier.size(size), tint = if (enabled) tint else C.outline) }
}

/** Square outlined chip; [on] = primary tint. */
@Composable
internal fun Chip(
    text: String, modifier: Modifier = Modifier, on: Boolean = false, color: Color = C.onVar,
    icon: ImageVector? = null, trailing: ImageVector? = null, onClick: (() -> Unit)? = null,
) {
    val fg = if (on) C.primary else color
    Row(
        modifier
            .widthIn(max = 280.dp) // bounded: the weighted label collapses to 0 inside a horizontalScroll row
            .heightIn(min = 32.dp)
            .background(if (on) C.primary.copy(alpha = 0.14f) else C.low)
            .border(1.dp, if (on) C.primary else C.border)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = S.inset, vertical = S.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(S.gap),
    ) {
        icon?.let { NcIcon(it, null, tint = fg, size = 14.dp) }
        Text(text, color = fg, style = T.codeSm, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        trailing?.let { NcIcon(it, null, tint = fg, size = 14.dp) }
    }
}

internal enum class BtnKind { PRIMARY, OUTLINE, DANGER, GHOST }

/** Square button, mono caps label, optional leading icon; 40 dp min height. */
@Composable
internal fun NcButton(
    text: String, modifier: Modifier = Modifier, primary: Boolean = false, danger: Boolean = false, enabled: Boolean = true,
    icon: ImageVector? = null, kind: BtnKind = when { primary -> BtnKind.PRIMARY; danger -> BtnKind.DANGER; else -> BtnKind.OUTLINE },
    onClick: () -> Unit,
) {
    val (bg, fg, stroke) = when (kind) {
        BtnKind.PRIMARY -> Triple(C.primary, C.onPrimary, C.primary)
        BtnKind.DANGER -> Triple(C.errorCon.copy(alpha = 0.22f), C.error, C.error.copy(alpha = 0.7f))
        BtnKind.OUTLINE -> Triple(C.low, C.on, C.border)
        BtnKind.GHOST -> Triple(Color.Transparent, C.primary, Color.Transparent)
    }
    Row(
        modifier
            .heightIn(min = 40.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f }
            .background(bg)
            .border(1.dp, stroke)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = S.m, vertical = S.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(S.s, Alignment.CenterHorizontally),
    ) {
        icon?.let { NcIcon(it, null, tint = fg, size = 16.dp) }
        Text(text.uppercase(), color = fg, style = T.overline.copy(fontSize = 12.sp, letterSpacing = 0.8.sp), maxLines = 1)
    }
}

@Composable
internal fun Hairline(modifier: Modifier = Modifier, color: Color = C.border) {
    Box(modifier.fillMaxWidth().height(1.dp).background(color))
}

/**
 * App's cyber-brutalist elevation: a hard offset block behind the element (MainActivity.cyberBrutalistBg).
 * Reserves [offset] at end/bottom so the block never overlaps siblings.
 */
internal fun Modifier.brutal(offset: Dp = 4.dp, shadow: Color = C.shadow): Modifier =
    padding(end = offset, bottom = offset).drawBehind {
        val o = offset.toPx()
        drawRect(shadow, topLeft = Offset(o, o), size = size)
    }

/** Card surface: tonal fill + 1 dp border. */
internal fun Modifier.card(fill: Color = C.low, stroke: Color = C.border): Modifier = background(fill).border(1.dp, stroke)

/** Drawn status dot; [pulse] = soft breathing halo (draw phase only, no recomposition). */
@Composable
internal fun StatusDot(color: Color, modifier: Modifier = Modifier, size: Dp = 8.dp, pulse: Boolean = false) {
    if (!pulse) { Box(modifier.size(size).background(color, CircleShape)); return }
    val t = rememberInfiniteTransition(label = "dot")
    val a by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "a")
    Box(
        modifier.size(size).drawBehind {
            val r = size.toPx() / 2f
            drawCircle(color.copy(alpha = (1f - a) * 0.5f), radius = r * (1f + a * 0.9f))
            drawCircle(color, radius = r)
        },
    )
}

/** Hollow drawn circle (pending step, unselected radio). */
@Composable
internal fun Ring(color: Color, modifier: Modifier = Modifier, size: Dp = 14.dp, filled: Boolean = false) {
    Box(modifier.size(size).border(1.5.dp, color, CircleShape), contentAlignment = Alignment.Center) {
        if (filled) Box(Modifier.size(size / 2).background(color, CircleShape))
    }
}

/** Status chip: drawn dot + label; TalkBack reads "Status: <label>". */
@Composable
internal fun StatusChip(text: String, color: Color, modifier: Modifier = Modifier, pulse: Boolean = false) {
    Row(
        modifier.semantics(mergeDescendants = true) { contentDescription = "Status: $text" }
            .background(color.copy(alpha = 0.12f)).padding(horizontal = S.s, vertical = S.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(color, size = 6.dp, pulse = pulse)
        Text(text, Modifier.padding(start = S.gap), color = color, style = T.meta.copy(fontWeight = FontWeight.Bold), maxLines = 1)
    }
}

/** Animated chevron (right when closed, down when open). */
@Composable
internal fun Chevron(open: Boolean, modifier: Modifier = Modifier, tint: Color = C.onVar, size: Dp = 16.dp) {
    val r by animateFloatAsState(if (open) 90f else 0f, label = "chev")
    NcIcon(Ic.ChevronRight, null, modifier.rotate(r), tint = tint, size = size)
}

/** Loading sweep over the content (draw phase only). */
@Composable
internal fun Modifier.shimmer(on: Boolean = true): Modifier {
    if (!on) return this
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(-1f, 2f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "x")
    return graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen).drawWithContent {
        drawContent()
        val w = size.width
        drawRect(
            Brush.linearGradient(
                listOf(Color.Transparent, Color.White.copy(alpha = 0.55f), Color.Transparent),
                start = Offset(w * x - w * 0.4f, 0f), end = Offset(w * x, 0f),
            ),
            blendMode = BlendMode.SrcAtop,
        )
    }
}

/** Skeleton bar for loading placeholders. */
@Composable
internal fun SkeletonBar(width: Dp, modifier: Modifier = Modifier, height: Dp = 10.dp) {
    Box(modifier.width(width).height(height).background(C.high).shimmer())
}

/** Blinking block caret drawn after streaming text (alpha in the draw phase). */
@Composable
internal fun BlinkCaret(modifier: Modifier = Modifier, color: Color = C.primary) {
    val t = rememberInfiniteTransition(label = "caret")
    val a by t.animateFloat(1f, 0f, infiniteRepeatable(tween(530, easing = LinearEasing), RepeatMode.Reverse), label = "a")
    Box(modifier.graphicsLayer { alpha = if (a > 0.5f) 1f else 0.15f }.background(color))
}

/** Menu row with a leading vector icon. */
@Composable
internal fun MItem(label: String, icon: ImageVector? = null, danger: Boolean = false, onClick: () -> Unit) =
    DropdownMenuItem(
        text = { Text(label, style = T.bodySm, color = if (danger) C.error else C.on) },
        onClick = onClick,
        leadingIcon = icon?.let { { NcIcon(it, null, tint = if (danger) C.error else C.onVar, size = 18.dp) } },
    )

/** Inline "+12 -3" (numbers only; minus is the U+2212 text sign). */
@Composable
internal fun RowScope.AddDel(add: Int, del: Int, size: Int = 11) {
    if (add > 0 || del == 0) Label("+$add", color = C.primary, size = size, bold = true)
    if (del > 0) Label("−$del", Modifier.padding(start = S.xs), color = C.error, size = size, bold = true)
}

/** Provider logo the app ships (assets/images/cli, same files as the terminal tool cards). */
@Composable
internal fun ProviderLogo(tool: String, modifier: Modifier = Modifier, size: Dp = 28.dp) {
    val ctx = LocalContext.current
    val logo by produceState(Logos.cache[tool], tool) {
        if (value == null) value = withContext(Dispatchers.IO) { Logos.load(ctx, tool) }
    }
    // A dark logo (grok, opencode) sits on a light tile so it keeps >= 3:1 contrast (WCAG non-text).
    val tile = if (logo?.second == true) C.on else C.lowest
    Box(modifier.size(size).background(tile).border(1.dp, C.border), contentAlignment = Alignment.Center) {
        logo?.let { Image(it.first, null, Modifier.size(size - S.s)) } ?: NcIcon(Ic.Bot, null, tint = C.outline, size = size * 0.6f)
    }
}

/** WCAG contrast of a logo against its tile; pure, unit-tested over the shipped logos. */
internal object LogoContrast {
    private fun ch(c: Int): Double = (c / 255.0).let { if (it <= 0.04045) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
    fun lum(argb: Int): Double = 0.2126 * ch(argb shr 16 and 255) + 0.7152 * ch(argb shr 8 and 255) + 0.0722 * ch(argb and 255)
    fun ratio(a: Double, b: Double): Double = (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    val DARK_TILE = lum(NC_SURFACE_LOWEST)
    val LIGHT_TILE = lum(NC_ON_SURFACE)

    /** Median luminance of the opaque pixels (the logo's ink); null for a blank image. */
    fun ink(px: IntArray): Double? = px.filter { it ushr 24 > 128 }.map(::lum).sorted().let { if (it.isEmpty()) null else it[it.size / 2] }

    /** True when the ink fails 3:1 on the dark tile, so the logo gets the light tile. */
    fun lightTile(px: IntArray): Boolean = ink(px)?.let { ratio(it, DARK_TILE) < 3.0 } ?: false
}

// DesignTokens.NC values as plain ints (NC uses android.graphics.Color.parseColor, not available in JVM tests).
private const val NC_SURFACE_LOWEST = 0xFF0E0E0E.toInt()
private const val NC_ON_SURFACE = 0xFFE5E2E1.toInt()

private object Logos {
    /** Logo bitmap + whether it needs the light tile. */
    val cache = ConcurrentHashMap<String, Pair<ImageBitmap, Boolean>>()
    fun file(tool: String) = when (tool) {
        "qwen-code" -> "qwen-code.webp"
        "shell", "shell-root" -> "shell.png"
        else -> "$tool.png"
    }
    fun load(ctx: Context, tool: String): Pair<ImageBitmap, Boolean>? = cache[tool] ?: runCatching {
        val path = "images/cli/${file(tool)}"
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.assets.open(path).use { BitmapFactory.decodeStream(it, null, o) }
        var s = 1
        while (o.outWidth / (s * 2) >= 96) s *= 2
        ctx.assets.open(path).use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s }) }?.let { b ->
            val px = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
            b.asImageBitmap() to LogoContrast.lightTile(px)
        }
    }.getOrNull()?.also { cache[tool] = it }
}

internal fun copyText(ctx: Context, text: String) {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("chat", text))
    Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
}
