package com.zenithblue.nativecode.marketplace

import android.widget.ImageView
import com.zenithblue.nativecode.R

/** Bundled icons only; the Play build performs no remote icon fetch. */
object MarketplaceIcons {
    fun bundledRes(pkgId: String): Int = when (pkgId.lowercase()) {
        "blender" -> R.drawable.mp_ic_blender
        "box64" -> R.drawable.mp_ic_box64
        else -> R.drawable.mp_ic_package
    }
    fun bind(view: ImageView, pkg: MpPackage) = view.setImageResource(bundledRes(pkg.id))
}
