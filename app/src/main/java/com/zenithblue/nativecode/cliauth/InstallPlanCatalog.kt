package com.zenithblue.nativecode.cliauth

/**
 * Inventory lines for onboarding install-plan page (C6).
 * Guest Debian only — not host APK updates.
 */
object InstallPlanCatalog {

    data class InventoryLine(val label: String, val detail: String)

    fun baseInventory(method: String): List<InventoryLine> =
        if (method == "chroot") {
            listOf(
                InventoryLine("Root access", "Device superuser provider"),
                InventoryLine("Alpine Linux chroot base", "setup_alpine_chroot.sh"),
                InventoryLine("Guest packages / users", "setup_alpine_family.sh"),
                InventoryLine("Hardware acceleration", "baked rootfs (Turnip / Venus)")
            )
        } else {
            listOf(
                InventoryLine("Host bootstrap", "bootstrap.tar + host configs"),
                InventoryLine("Alpine Linux guest", "flux_install / alpine"),
                InventoryLine("Guest packages / users", "setup_alpine_family.sh"),
                InventoryLine("Hardware acceleration", "baked rootfs (Turnip / Venus)")
            )
        }

    fun customizationInventory(): List<InventoryLine> = listOf(
        InventoryLine("Fonts & terminal polish", "setup_customization_alpine.sh")
    )

    fun guiInventory(): List<InventoryLine> = listOf(
        InventoryLine("XFCE4 desktop environment", "setup_xfce4.sh"),
        InventoryLine("XFCE themes & panel config", "setup_xfce4.sh")
    )

    fun aiCliInventory(): List<InventoryLine> {
        val head = listOf(
            InventoryLine("Node.js / NVM", "setup_cli_tools.sh (guest only)")
        )
        val tools = CliToolCatalog.ALL.map {
            InventoryLine(it.displayName, "bin: ${it.bin}")
        }
        return head + tools
    }
}
