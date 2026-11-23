package com.zenithblue.nativecode.agent.chat.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zenithblue.nativecode.R
import com.zenithblue.nativecode.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Decoded preview of one file; [bitmap] null = nothing to show (not previewable kind, or decode failed). */
private class Thumb(val bitmap: ImageBitmap?)

/** Pixel size a preview is decoded at: about two phone widths of detail, bounded by [FileThumbs.sampleSize]. */
private const val THUMB_PX = 720

internal fun fileTypeIcon(t: FileType): ImageVector = when (t) {
    FileType.IMAGE, FileType.SVG -> Ic.Image
    FileType.VIDEO -> Ic.Play
    FileType.AUDIO -> Ic.Music
    FileType.APK -> Ic.Package
    else -> Ic.FileText
}

private fun fileTypeLabel(t: FileType): String = tr(
    when (t) {
        FileType.IMAGE -> R.string.chat_kind_image
        FileType.SVG -> R.string.chat_kind_svg
        FileType.VIDEO -> R.string.chat_kind_video
        FileType.AUDIO -> R.string.chat_kind_audio
        FileType.PDF -> R.string.chat_kind_pdf
        FileType.MARKDOWN -> R.string.chat_kind_markdown
        FileType.HTML -> R.string.chat_kind_html
        FileType.TEXT -> R.string.chat_kind_text
        FileType.OFFICE -> R.string.chat_kind_office
        FileType.APK -> R.string.chat_kind_apk
        FileType.OTHER -> R.string.chat_kind_file
    },
)

/**
 * Inline file card of the timeline: thumbnail (image, video poster, PDF first page) or icon, name, size.
 * [file] null = the path did not resolve (error card). The decode runs off the main thread, is size-bounded and
 * can only fail into the small "Can't preview" line. Tapping calls [onOpen] (full-screen viewer through the host).
 */
@Composable
internal fun FilePreviewCard(file: File?, name: String, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val type = remember(name) { FileKinds.kindOf(name) }
    val thumb by produceState<Thumb?>(null, file, type) {
        value = if (file == null || !FileKinds.hasInlinePreview(type)) Thumb(null) else withContext(Dispatchers.IO) {
            Thumb(
                when (type) {
                    FileType.IMAGE -> FileThumbs.image(file, THUMB_PX)
                    FileType.VIDEO -> FileThumbs.videoPoster(file, THUMB_PX)
                    FileType.PDF -> FileThumbs.pdfPage(file, THUMB_PX)
                    else -> null // SVG and audio keep the icon card (SVG decodes only inside the sandboxed viewer)
                }?.asImageBitmap(),
            )
        }
    }
    val bmp = thumb?.bitmap
    val wantsThumb = type == FileType.IMAGE || type == FileType.VIDEO || type == FileType.PDF
    val failed = file != null && wantsThumb && thumb != null && bmp == null
    val sub = when {
        file == null -> tr(R.string.main_file_not_found)
        failed -> tr(R.string.chat_preview_failed)
        else -> fileTypeLabel(type) + " · " + bridgeSize(file.length())
    }
    Column(
        modifier.fillMaxWidth().card(C.low, if (file == null || failed) C.error.copy(alpha = 0.6f) else C.border)
            .semantics(mergeDescendants = true) { contentDescription = "$name, $sub" }
            .clickable(enabled = file != null, onClickLabel = tr(R.string.chat_open_file), onClick = onOpen),
    ) {
        if (bmp != null) Box(Modifier.fillMaxWidth().background(C.lowest), contentAlignment = Alignment.Center) {
            Image(bmp, null, Modifier.fillMaxWidth().heightIn(max = 240.dp), contentScale = ContentScale.Fit)
            if (type == FileType.VIDEO) Box(Modifier.size(48.dp).background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                NcIcon(Ic.Play, null, tint = Color.White, size = 24.dp)
            }
        }
        Row(Modifier.fillMaxWidth().padding(S.inset), verticalAlignment = Alignment.CenterVertically) {
            NcIcon(if (file == null || failed) Ic.Alert else fileTypeIcon(type), null, tint = if (file == null || failed) C.error else C.onVar, size = 18.dp)
            Column(Modifier.weight(1f).padding(start = S.s)) {
                Text(name, color = C.on, style = T.bodySm, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(sub, color = if (file == null || failed) C.error else C.outline, style = T.meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
