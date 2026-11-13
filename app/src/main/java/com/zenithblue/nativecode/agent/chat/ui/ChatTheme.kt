package com.zenithblue.nativecode.agent.chat.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zenithblue.nativecode.NC

/** App palette (DesignTokens.NC) for Compose: obsidian surfaces, green primary, square cyber-brutalist edges. */
internal object C {
    val bg = Color(NC.BG)
    val lowest = Color(NC.SURFACE_LOWEST)
    val low = Color(NC.SURFACE_LOW)
    val container = Color(NC.SURFACE_CONTAINER)
    val high = Color(NC.SURFACE_HIGH)
    val on = Color(NC.ON_SURFACE)
    val onVar = Color(NC.ON_SURF_VAR)
    val outline = Color(NC.OUTLINE)
    val border = Color(NC.OUTLINE_VAR)
    val primary = Color(NC.PRIMARY)
    val onPrimary = Color(NC.ON_PRIMARY)
    val tertiary = Color(NC.TERTIARY_CON)
    val error = Color(NC.ERROR)
    val errorCon = Color(NC.ERROR_CON)
    val addBg = Color(0x2243E188)
    val delBg = Color(0x22FFB4AB)
}

internal val Mono = FontFamily.Monospace
internal val Square = RoundedCornerShape(0.dp)

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

/** Small mono label: section headers, meta lines. */
@Composable
internal fun Label(text: String, modifier: Modifier = Modifier, color: Color = C.onVar, size: Int = 11, bold: Boolean = false) {
    Text(
        text, modifier = modifier, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis,
        style = TextStyle(fontFamily = Mono, fontSize = size.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal),
    )
}

/** Square outlined chip; [on] = filled primary tint. */
@Composable
internal fun Chip(text: String, modifier: Modifier = Modifier, on: Boolean = false, color: Color = C.onVar, onClick: (() -> Unit)? = null) {
    Box(
        modifier
            .heightIn(min = 32.dp)
            .background(if (on) C.primary.copy(alpha = 0.18f) else Color.Transparent)
            .border(1.dp, if (on) C.primary else C.border)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) { Label(text, color = if (on) C.primary else color, size = 12) }
}

@Composable
internal fun NcButton(text: String, modifier: Modifier = Modifier, primary: Boolean = false, danger: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val fg = when { danger -> C.error; primary -> C.onPrimary; else -> C.on }
    OutlinedButton(
        onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 40.dp), shape = Square,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        border = BorderStroke(1.dp, if (danger) C.error else if (primary) C.primary else C.border),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = if (primary) C.primary else Color.Transparent, contentColor = fg),
    ) { Text(text.uppercase(), fontFamily = Mono, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
}

@Composable
internal fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(C.border))
}

internal fun copyText(ctx: Context, text: String) {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("chat", text))
    Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
}
