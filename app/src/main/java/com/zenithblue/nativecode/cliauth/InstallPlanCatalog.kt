package com.zenithblue.nativecode.cliauth

import com.zenithblue.nativecode.tr
import com.zenithblue.nativecode.R

/**
 * Inventory lines for onboarding install-plan page (C6).
 * Guest Debian only — not host APK updates.
 */
object InstallPlanCatalog {

    data class InventoryLine(val label: String, val detail: String)

    fun baseInventory(method: String): List<InventoryLine> =
        if (method == "chroot") {
            listOf(
                InventoryLine(tr(R.string.cli_root_access), tr(R.string.cli_device_superuser_provider)),
                InventoryLine(tr(R.string.cli_alpine_linux_chroot_base), "setup_alpine_chroot.sh"),
                InventoryLine(tr(R.string.cli_guest_packages_users), "setup_alpine_family.sh"),
                InventoryLine(tr(R.string.cli_hardware_acceleration), "baked rootfs (Turnip / Venus)")
            )
        } else {
            listOf(
                InventoryLine(tr(R.string.cli_host_bootstrap), "bootstrap.tar + host configs"),
                InventoryLine(tr(R.string.cli_alpine_linux_guest), "flux_install / alpine"),
                InventoryLine(tr(R.string.cli_guest_packages_users), "setup_alpine_family.sh"),
                InventoryLine(tr(R.string.cli_hardware_acceleration), "baked rootfs (Turnip / Venus)")
            )
        }

    fun customizationInventory(): List<InventoryLine> = listOf(
        InventoryLine(tr(R.string.cli_fonts_terminal_polish), "setup_customization_alpine.sh")
    )

    fun guiInventory(): List<InventoryLine> = listOf(
        InventoryLine(tr(R.string.cli_xfce4_desktop_environment), "setup_xfce4.sh"),
        InventoryLine(tr(R.string.cli_xfce_themes_panel_config), "setup_xfce4.sh")
    )

    fun aiCliInventory(): List<InventoryLine> {
        val head = listOf(
            InventoryLine(tr(R.string.cli_node_js_nvm), "setup_cli_tools.sh (guest only)")
        )
        val tools = CliToolCatalog.ALL.map {
            InventoryLine(it.displayName, "bin: ${it.bin}")
        }
        return head + tools
    }
}
