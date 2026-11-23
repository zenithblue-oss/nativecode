package com.zenithblue.nativecode.agent.chat.ui

/*
 * Icon paths: Lucide (https://lucide.dev), ISC License,
 * Copyright (c) for portions of Lucide are held by Cole Bemis 2013-2022 as part of Feather (MIT);
 * all other copyright (c) for Lucide are held by Lucide Contributors 2022.
 * Permission to use, copy, modify, and/or distribute this software for any purpose with or without fee
 * is hereby granted, provided that the above copyright notice and this permission notice appear in all copies.
 * Paths transcribed by hand into 24x24 stroke ImageVectors (2px, round caps/joins), as Lucide ships them.
 * Item 12 UI rule: every icon is a vector; no glyphs, emoji or unicode symbols as icons.
 */

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import com.zenithblue.nativecode.tr
import com.zenithblue.nativecode.R

internal object Ic {
    val Back = lucide("m12 19-7-7 7-7", "M19 12H5")
    val More = lucide(dot(12f, 5f), dot(12f, 12f), dot(12f, 19f))
    val Plus = lucide("M5 12h14", "M12 5v14")
    val Close = lucide("M18 6 6 18", "m6 6 12 12")
    val Check = lucide("M20 6 9 17l-5-5")
    val ChevronRight = lucide("m9 18 6-6-6-6")
    val ChevronDown = lucide("m6 9 6 6 6-6")
    val ArrowUp = lucide("m5 12 7-7 7 7", "M12 19V5")
    val ArrowDown = lucide("M12 5v14", "m19 12-7 7-7-7")
    val Stop = filled("M7 6h10a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1z")
    val Copy = lucide(rect(8f, 8f, 14f, 14f, 2f), "M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2")
    val Terminal = lucide("m4 17 6-6-6-6", "M12 19h8")
    val Pencil = lucide("M17 3a2.85 2.83 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5Z", "m15 5 4 4")
    val SquarePen = lucide(
        "M12 3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7",
        "M18.375 2.625a1 1 0 0 1 3 3l-9.013 9.014a2 2 0 0 1-.853.505l-2.873.84a.5.5 0 0 1-.62-.62l.84-2.873a2 2 0 0 1 .506-.852z",
    )
    val FileDiff = lucide("M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z", "M9 10h6", "M12 13V7", "M9 17h6")
    val FileText = lucide("M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z", "M14 2v4a2 2 0 0 0 2 2h4", "M10 9H8", "M16 13H8", "M16 17H8")
    val Image = lucide(rect(3f, 3f, 18f, 18f, 2f), circle(9f, 9f, 2f), "m21 15-3.086-3.086a2 2 0 0 0-2.828 0L6 21")
    val Play = lucide("M6 3 20 12 6 21Z")
    val Music = lucide("M9 18V5l12-2v13", circle(6f, 18f, 3f), circle(18f, 16f, 3f))
    val Search = lucide(circle(11f, 11f, 8f), "m21 21-4.3-4.3")
    val Globe = lucide(circle(12f, 12f, 10f), "M12 2a14.5 14.5 0 0 0 0 20 14.5 14.5 0 0 0 0-20", "M2 12h20")
    val Wrench = lucide("M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z")
    val Bot = lucide("M12 8V4H8", rect(4f, 8f, 16f, 12f, 2f), "M2 14h2", "M20 14h2", "M15 13v2", "M9 13v2")
    val ShieldCheck = lucide(SHIELD, "m9 12 2 2 4-4")
    val ShieldAlert = lucide(SHIELD, "M12 8v4", "M12 16h.01")
    val Help = lucide(circle(12f, 12f, 10f), "M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3", "M12 17h.01")
    val Lightbulb = lucide(
        "M15 14c.2-1 .7-1.7 1.5-2.5 1-.9 1.5-2.2 1.5-3.5A6 6 0 0 0 6 8c0 1 .2 2.2 1.5 3.5.7.7 1.3 1.5 1.5 2.5",
        "M9 18h6", "M10 22h4",
    )
    val Alert = lucide("m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3", "M12 9v4", "M12 17h.01")
    val CircleX = lucide(circle(12f, 12f, 10f), "m15 9-6 6", "m9 9 6 6")
    val CircleCheck = lucide(circle(12f, 12f, 10f), "m9 12 2 2 4-4")
    val ListTodo = lucide(rect(3f, 5f, 6f, 6f, 1f), "m3 17 2 2 4-4", "M13 6h8", "M13 12h8", "M13 18h8")
    val Lock = lucide(rect(3f, 11f, 18f, 11f, 2f), "M7 11V7a5 5 0 0 1 10 0v4")
    val Pin = lucide(
        "M12 17v5",
        "M9 10.76a2 2 0 0 1-1.11 1.79l-1.78.9A2 2 0 0 0 5 15.24V16a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-.76a2 2 0 0 0-1.11-1.79l-1.78-.9A2 2 0 0 1 15 10.76V7a1 1 0 0 1 1-1 2 2 0 0 0 0-4H8a2 2 0 0 0 0 4 1 1 0 0 1 1 1z",
    )
    val Archive = lucide(rect(2f, 3f, 20f, 5f, 1f), "M4 8v11a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8", "M10 12h4")
    val Branch = lucide("M6 3v12", circle(18f, 6f, 3f), circle(6f, 18f, 3f), "M18 9a9 9 0 0 1-9 9")
    val Folder = lucide("M20 20a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.69-.9L9.6 3.9A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2Z")
    val Retry = lucide(
        "M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8", "M21 3v5h-5",
        "M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16", "M8 16H3v5",
    )
    val Steer = lucide("m15 10 5 5-5 5", "M4 4v7a4 4 0 0 0 4 4h12")
    val Clock = lucide(circle(12f, 12f, 10f), "M12 6v6l4 2")
    val Compare = lucide(circle(18f, 18f, 3f), circle(6f, 6f, 3f), "M13 6h3a2 2 0 0 1 2 2v7", "M11 18H8a2 2 0 0 1-2-2V9")
    val Sliders = lucide("M21 4h-7", "M10 4H3", "M21 12h-9", "M8 12H3", "M21 20h-5", "M12 20H3", "M14 2v4", "M8 10v4", "M16 18v4")
    val Trash = lucide("M3 6h18", "M19 6v14c0 1-1 2-2 2H7c-1 0-2-1-2-2V6", "M8 6V4c0-1 1-2 2-2h4c1 0 2 1 2 2v2")
    val Sparkles = lucide(
        "M9.937 15.5A2 2 0 0 0 8.5 14.063l-6.135-1.582a.5.5 0 0 1 0-.962L8.5 9.936A2 2 0 0 0 9.937 8.5l1.582-6.135a.5.5 0 0 1 .963 0L14.063 8.5A2 2 0 0 0 15.5 9.937l6.135 1.581a.5.5 0 0 1 0 .964L15.5 14.063a2 2 0 0 0-1.437 1.437l-1.582 6.135a.5.5 0 0 1-.963 0z",
    )
    val ClipboardList = lucide(
        rect(8f, 2f, 8f, 4f, 1f), "M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2",
        "M12 11h4", "M12 16h4", "M8 11h.01", "M8 16h.01",
    )
    val Message = lucide("M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z")
    val Download = lucide("M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4", "m7 10 5 5 5-5", "M12 15V3")
    val Gauge = lucide("m12 14 4-4", "M3.34 19a10 10 0 1 1 17.32 0")
    val AtSign = lucide(circle(12f, 12f, 4f), "M16 8v5a3 3 0 0 0 6 0v-1a10 10 0 1 0-4 8")
    val SquareSlash = lucide(rect(3f, 3f, 18f, 18f, 2f), "M9 15 15 9")
    val BookOpen = lucide("M12 7v14", "M3 18a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1h5a4 4 0 0 1 4 4 4 4 0 0 1 4-4h5a1 1 0 0 1 1 1v13a1 1 0 0 1-1 1h-6a3 3 0 0 0-3 3 3 3 0 0 0-3-3z")
    val Zap = lucide("M4 14a1 1 0 0 1-.78-1.63l9.9-10.2a.5.5 0 0 1 .86.46l-1.92 6.02A1 1 0 0 0 13 10h7a1 1 0 0 1 .78 1.63l-9.9 10.2a.5.5 0 0 1-.86-.46l1.92-6.02A1 1 0 0 0 11 14z")
    val Bell = lucide("M10.268 21a2 2 0 0 0 3.464 0", "M3.262 15.326A1 1 0 0 0 4 17h16a1 1 0 0 0 .74-1.673C19.41 13.956 18 12.499 18 8A6 6 0 0 0 6 8c0 4.499-1.411 5.956-2.738 7.326")
    val ExternalLink = lucide("M15 3h6v6", "M10 14 21 3", "M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6")
    val Package = lucide(
        "M11 21.73a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73z",
        "M12 22V12", "m3.3 7 7.703 4.734a2 2 0 0 0 1.994 0L20.7 7", "m7.5 4.27 9 5.15",
    )
    val Share = lucide(circle(18f, 5f, 3f), circle(6f, 12f, 3f), circle(18f, 19f, 3f), "m8.59 13.51 6.83 3.98", "m15.41 6.51-6.82 3.98")
    val Activity = lucide("M22 12h-2.48a2 2 0 0 0-1.93 1.46l-2.35 8.36a.25.25 0 0 1-.48 0L9.24 2.18a.25.25 0 0 0-.48 0l-2.35 8.36A2 2 0 0 1 4.49 12H2")
    val History = lucide("M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8", "M3 3v5h5", "M12 7v5l4 2")
    val Fold = lucide("M12 22v-6", "M12 8V2", "M4 12H2", "M10 12H8", "M16 12h-2", "M22 12h-2", "m15 19-3-3-3 3", "m15 5-3 3-3-3")
}

private const val SHIELD =
    "M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z"

/** SVG `<circle>` as a path. */
private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0"

/** Lucide's 1-radius "dot" circles (ellipsis). */
private fun dot(cx: Float, cy: Float) = circle(cx, cy, 1f)

/** SVG `<rect x y width height rx>` as a path. */
private fun rect(x: Float, y: Float, w: Float, h: Float, r: Float) =
    if (r == 0f) "M$x ${y}h${w}v${h}h${-w}z"
    else tr(R.string.chat_m_h_a_0_0, x + r, y, w - 2 * r, r, r, r, r, h - 2 * r, r, r, -r, r, -(w - 2 * r), r, r, -r, -r, -(h - 2 * r), r, r, r, -r)

private fun lucide(vararg paths: String): ImageVector =
    ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
        paths.forEach {
            addPath(
                addPathNodes(it), stroke = SolidColor(Color.White), strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()

private fun filled(path: String): ImageVector =
    ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(addPathNodes(path), fill = SolidColor(Color.White)).build()
