package com.zenithblue.nativecode.skills

import com.zenithblue.nativecode.terminal.GpuAccelDetector

/**
 * Agent skills shown in Settings → SKILLS.
 * A skill is a markdown prompt an agent pastes into a CLI tool (Claude, Codex, …).
 */
internal data class Skill(
    val id: String,
    val title: String,
    val summary: String,
    val source: String,
    val body: String,
)

internal enum class SkillCategory(val title: String, val subtitle: String) {
    INBUILT("INBUILT", "NativeCode hardware & agent bridge"),
    PROJECT("PROJECT / UTILITY", "Project and utility skills"),
    INSTALL("INSTALL PROGRAMS", "How to install programs"),
}

internal object SkillsCatalog {

    fun skills(category: SkillCategory): List<Skill> = when (category) {
        SkillCategory.INBUILT -> inbuilt()
        SkillCategory.PROJECT -> AdditionalSkillTexts.project
        SkillCategory.INSTALL -> AdditionalSkillTexts.install
    }

    fun all(): List<Skill> = SkillCategory.entries.flatMap { skills(it) }

    fun matching(query: String, pool: List<Skill>): List<Skill> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return pool
        return pool.filter { skill ->
            skill.title.lowercase().contains(q) ||
                skill.summary.lowercase().contains(q) ||
                skill.id.lowercase().contains(q)
        }
    }

    private val byPath = HardwareSkillTexts.files.toMap()

    private fun inbuilt(): List<Skill> {
        val gpu = if (GpuAccelDetector.detect().vendorHint.startsWith("adreno")) adreno else mali
        return listOf(host, gpuAccel, gpu, video)
    }

    private val host = skill(
        "agent-bridge.md",
        "Host Agent",
        "Connect to NativeCode.",
    )

    private val gpuAccel = skill(
        "gpu-accel.md",
        "GPU Acceleration",
        "Use the on-device GPU.",
    )

    private val adreno = merged(
        "adreno",
        "Adreno OpenGL",
        "Desktop OpenGL, OpenGL ES, and Vulkan on this Adreno.",
        listOf(
            "gpu/adreno/opengl_desktop_4_5_4_6.md",
            "gpu/adreno/opengles_3_2.md",
            "gpu/adreno/vulkan_turnip.md",
        ),
    )

    private val mali = merged(
        "mali",
        "Mali OpenGL",
        "OpenGL and Vulkan on this Mali. OpenGL 4.5 and 4.6 do not work.",
        listOf(
            "gpu/mali/OpenGL-3.2-Mali-PanVK-zink.md",
            "gpu/mali/OpenGL-4.5-4.6-Mali-PanVK-zink.md",
            "gpu/mali/Vulkan-PanVK-Mali-0xa8671030.md",
        ),
    )

    private val video = skill(
        "vpu-mediacodec.md",
        "Video Hardware",
        "Encode and decode video on the device.",
    )

    private fun skill(path: String, title: String, summary: String) = Skill(
        id = path,
        title = title,
        summary = summary,
        source = "",
        body = byPath.getValue(path).trimEnd() + "\n",
    )

    private fun merged(id: String, title: String, summary: String, paths: List<String>) = Skill(
        id = id,
        title = title,
        summary = summary,
        source = "",
        body = paths.joinToString("\n\n") { byPath.getValue(it).trimEnd() } + "\n",
    )
}
