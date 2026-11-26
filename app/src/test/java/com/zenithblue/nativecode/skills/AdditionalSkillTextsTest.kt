package com.zenithblue.nativecode.skills

import java.io.File
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdditionalSkillTextsTest {

    @Test
    fun importCountsAndUniqueIds() {
        val install = AdditionalSkillTexts.install
        val project = AdditionalSkillTexts.project
        assertEquals(13, install.size)
        assertEquals(42, project.size)
        val ids = (install + project).map { it.id }
        assertEquals(55, ids.size)
        assertEquals(ids.size, ids.toSet().size)
        (install + project).forEach { skill ->
            assertTrue(skill.id, skill.body.isNotEmpty())
            // Imported third-party skills name their upstream; our own ones leave it empty.
            if (skill.id !in THIRD_PARTY) assertEquals("", skill.source)
        }
    }

    @Test
    fun catalogBranchesReturnImportedLists() {
        assertEquals(AdditionalSkillTexts.project, SkillsCatalog.skills(SkillCategory.PROJECT))
        assertEquals(AdditionalSkillTexts.install, SkillsCatalog.skills(SkillCategory.INSTALL))
    }

    @Test
    fun everySkillHasAnIconFile() {
        val dir = File("src/main/assets/images/skills").takeIf { it.isDirectory }
            ?: File("app/src/main/assets/images/skills")
        assertTrue(dir.isDirectory)
        val ids = AdditionalSkillTexts.install.map { it.id } +
            AdditionalSkillTexts.project.map { it.id } +
            listOf("agent-bridge.md", "gpu-accel.md", "adreno", "mali", "vpu-mediacodec.md")
        ids.forEach { id ->
            val file = File(dir, SkillIcons.file(id))
            assertTrue("$id -> ${file.name}", file.isFile && file.length() > 200)
        }
    }

    @Test
    fun matchingFindsAcrossImportedSkills() {
        val pool = AdditionalSkillTexts.install + AdditionalSkillTexts.project
        assertEquals(listOf("install-chromium"), SkillsCatalog.matching("chromium", pool).map { it.id })
        assertEquals(listOf("build-android-app"), SkillsCatalog.matching("android-only", pool).map { it.id })
        assertEquals(pool, SkillsCatalog.matching("  ", pool))
    }

    @Test
    fun bodiesMatchAssetBytes() {
        val assets = skillsAssetsDir()
        val files = assets.walkTopDown()
            .filter { it.isFile && it.name == "SKILL.md" }
            .toList()
        assertEquals(55, files.size)
        val byBytes = files.associateBy { it.readBytes().contentHash() }
        val seen = HashSet<String>()
        for (skill in AdditionalSkillTexts.install + AdditionalSkillTexts.project) {
            val bytes = skill.body.toByteArray(StandardCharsets.UTF_8)
            val file = byBytes[bytes.contentHash()]
            assertTrue("${skill.id} body must match an asset SKILL.md", file != null)
            assertTrue(bytes.contentEquals(file!!.readBytes()))
            assertTrue(seen.add(file.canonicalPath))
        }
        assertEquals(55, seen.size)
    }

    @Test
    fun thirdPartySkillsKeepLicenseAndSource() {
        val dir = skillsAssetsDir()
        THIRD_PARTY.forEach { id ->
            val skill = AdditionalSkillTexts.project.first { it.id == id }
            assertTrue(id, skill.source.contains("Apache-2.0"))
            assertTrue(id, File(dir, "$id/LICENSE.txt").readText().contains("Apache License"))
            assertTrue(id, File(dir, "$id/SOURCE.md").readText().contains("Commit:"))
        }
    }

    @Test
    fun bodiesFitTheJvmConstantLimit() {
        (AdditionalSkillTexts.install + AdditionalSkillTexts.project).forEach {
            assertTrue(it.id, it.body.toByteArray(StandardCharsets.UTF_8).size < 60_000)
        }
    }

    private val THIRD_PARTY = setOf("frontend-design", "theme-factory", "taste-design")

    private fun skillsAssetsDir(): File {
        val candidates = listOf(
            File("src/main/assets/skills"),
            File("app/src/main/assets/skills"),
        )
        return candidates.first { it.isDirectory }
    }

    private fun ByteArray.contentHash(): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest(this).joinToString("") { "%02x".format(it) }
    }
}
