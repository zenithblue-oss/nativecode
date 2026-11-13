package com.zenithblue.nativecode.cliauth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64

class CliCredentialProbeTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun home(): File = tmp.newFolder("home")

    private fun File.put(rel: String, text: String): File =
        File(this, rel).also { it.parentFile!!.mkdirs(); it.writeText(text) }

    private fun probe(id: String, h: File, kiro: Boolean = false) =
        CliCredentialProbe.probe(id, h) { kiro }

    private fun jwt(payload: String) =
        "h." + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray()) + ".s"

    @Test fun unreadableHomeIsUnknown() {
        assertEquals(AuthState.UNKNOWN, probe("claude", File(tmp.root, "missing")).state)
    }

    @Test fun emptyHomeIsSignedOutForEveryTool() {
        val h = home()
        listOf("claude", "codex", "opencode", "qwen", "grok", "kiro", "gh").forEach {
            assertEquals(it, AuthState.SIGNED_OUT, probe(it, h).state)
        }
        assertEquals(AuthState.SIGNED_OUT, probe("agy", h).state)
    }

    @Test fun claudeCredentialsFileAndAccount() {
        val h = home()
        h.put(".claude/.credentials.json",
            """{"claudeAiOauth":{"accessToken":"sk-ant-oat01-SECRET","refreshToken":"r","subscriptionType":"max"}}""")
        h.put(".claude.json", """{"oauthAccount":{"emailAddress":"me@example.com"}}""")
        val i = probe("claude", h)
        assertEquals(AuthState.SIGNED_IN, i.state)
        assertEquals("me@example.com", i.account)
        assertEquals("max", i.detail)
        assertFalse(i.toString().contains("SECRET"))
    }

    @Test fun claudeEmptyCredentialsIsSignedOut() {
        val h = home()
        h.put(".claude/.credentials.json", "{}")
        assertEquals(AuthState.SIGNED_OUT, probe("claude", h).state)
    }

    @Test fun claudeEnvTokenCountsAsSignedIn() {
        val h = home()
        h.put(CliGuestCommands.AUTH_ENV_REL, "# m\nexport CLAUDE_CODE_OAUTH_TOKEN='abc'\n")
        assertEquals(AuthState.SIGNED_IN, probe("claude", h).state)
    }

    @Test fun codexChatGptReadsEmailFromJwt() {
        val h = home()
        val id = jwt("{\"email\":\"a@b.co\"}")
        h.put(".codex/auth.json",
            """{"auth_mode":"chatgpt","OPENAI_API_KEY":null,"tokens":{"id_token":"$id","access_token":"AT","refresh_token":"RT"}}""")
        val i = probe("codex", h)
        assertEquals(AuthState.SIGNED_IN, i.state)
        assertEquals("a@b.co", i.account)
        assertEquals("ChatGPT", i.detail)
    }

    @Test fun codexApiKeyAndNullKey() {
        val h = home()
        h.put(".codex/auth.json", """{"OPENAI_API_KEY":"sk-x"}""")
        assertEquals("API key", probe("codex", h).detail)
        h.put(".codex/auth.json", """{"OPENAI_API_KEY":null,"tokens":null}""")
        assertEquals(AuthState.SIGNED_OUT, probe("codex", h).state)
    }

    @Test fun opencodeListsProviders() {
        val h = home()
        h.put(".local/share/opencode/auth.json",
            """{"anthropic":{"type":"oauth","refresh":"R","access":"A","expires":1},"openai":{"type":"api","key":"K"}}""")
        val i = probe("opencode", h)
        assertEquals(AuthState.SIGNED_IN, i.state)
        assertEquals("anthropic, openai", i.detail)
        h.put(".local/share/opencode/auth.json", "{}")
        assertEquals(AuthState.SIGNED_OUT, probe("opencode", h).state)
        h.put(".local/share/opencode/opencode.db", "x")
        assertEquals(AuthState.UNKNOWN, probe("opencode", h).state)
    }

    @Test fun qwenKeyBlankedOnClear() {
        val h = home()
        h.put(".qwen/settings.json", """{"model":{"name":"m"},"env":{"BAILIAN_CODING_PLAN_API_KEY":"k123"}}""")
        assertEquals(AuthState.SIGNED_IN, probe("qwen", h).state)
        assertEquals(1, CliCredentialProbe.clearFiles("qwen", h))
        assertEquals(AuthState.SIGNED_OUT, probe("qwen", h).state)
        assertTrue(File(h, ".qwen/settings.json").readText().contains("\"model\""))
    }

    @Test fun grokEnvAndFile() {
        val h = home()
        h.put(CliGuestCommands.AUTH_ENV_REL, "export XAI_API_KEY='xai-1'\n")
        assertEquals(AuthState.SIGNED_IN, probe("grok", h).state)
        val h2 = tmp.newFolder("h2")
        h2.put(".grok/auth.json", """{"access_token":"t"}""")
        assertEquals(AuthState.SIGNED_IN, probe("grok", h2).state)
    }

    @Test fun kiroUsesTokenCheckOnDb() {
        val h = home()
        h.put(".local/share/kiro-cli/data.sqlite3", "x")
        assertEquals(AuthState.SIGNED_IN, probe("kiro", h, kiro = true).state)
        assertEquals(AuthState.SIGNED_OUT, probe("kiro", h, kiro = false).state)
    }

    @Test fun agyDirWithoutCredentialIsUnknown() {
        val h = home()
        h.put(".gemini/antigravity-cli/AGENTS.md", "x")
        assertEquals(AuthState.SIGNED_OUT, probe("agy", h).state)
        h.put(".gemini/antigravity-cli/conversations/c1", "x")
        assertEquals(AuthState.UNKNOWN, probe("agy", h).state)
        h.put(".gemini/antigravity-cli/oauth_creds.json", "{}")
        assertEquals(AuthState.SIGNED_IN, probe("agy", h).state)
    }

    @Test fun ghHostsYml() {
        val h = home()
        h.put(".config/gh/hosts.yml", "github.com:\n    user: octo\n    oauth_token: gho_x\n    git_protocol: https\n")
        val i = probe("gh", h)
        assertEquals(AuthState.SIGNED_IN, i.state)
        assertEquals("octo", i.account)
        h.put(".config/gh/hosts.yml", "# empty\n")
        assertEquals(AuthState.SIGNED_OUT, probe("gh", h).state)
    }

    @Test fun clearFilesRemovesOnlyOwnAuthFile() {
        val h = home()
        h.put(".codex/auth.json", """{"OPENAI_API_KEY":"k"}""")
        val config = h.put(".codex/config.toml", "model='x'")
        h.put(".claude/.credentials.json", """{"accessToken":"t"}""")
        h.put(".local/share/opencode/auth.json", """{"a":{"type":"api"}}""")
        assertEquals(1, CliCredentialProbe.clearFiles("codex", h))
        assertEquals(AuthState.SIGNED_OUT, probe("codex", h).state)
        assertTrue(config.isFile)
        assertEquals(AuthState.SIGNED_IN, probe("claude", h).state)
        assertEquals(1, CliCredentialProbe.clearFiles("opencode", h))
        assertEquals(1, CliCredentialProbe.clearFiles("claude", h))
        assertEquals(0, CliCredentialProbe.clearFiles("claude", h))
    }

    @Test fun agyUsesExactFileNamesOnly() {
        val h = home()
        val other = h.put(".gemini/antigravity-cli/token_cache_notes.txt", "x")
        assertEquals(AuthState.UNKNOWN, probe("agy", h).state)
        h.put(".gemini/antigravity-cli/oauth_creds.json", """{"access_token":"t"}""")
        assertEquals(1, CliCredentialProbe.clearFiles("agy", h))
        assertTrue(other.isFile)
    }

    @Test fun clearRefusesSymlinkEscapingHome() {
        val h = home()
        val outside = tmp.newFolder("outside")
        val victim = outside.put("auth.json", """{"OPENAI_API_KEY":"k"}""")
        java.nio.file.Files.createSymbolicLink(File(h, ".codex").toPath(), outside.toPath())
        assertEquals(0, CliCredentialProbe.clearFiles("codex", h))
        assertTrue(victim.isFile)
    }

    @Test fun envKeyWithEmptyValueDoesNotReadNextLine() {
        val h = home()
        h.put(CliGuestCommands.AUTH_ENV_REL, "export XAI_API_KEY=\nexport OTHER='x'\n")
        assertEquals(AuthState.SIGNED_OUT, probe("grok", h).state)
    }

    @Test fun logoutOnlySignedOutIsSuccess() {
        assertTrue(logoutOutcome(AuthState.SIGNED_OUT, "X").first)
        assertFalse(logoutOutcome(AuthState.SIGNED_IN, "X").first)
        assertFalse(logoutOutcome(AuthState.UNKNOWN, "X").first)
    }

    @Test fun extractAuthUrlSkipsNonAuthLinks() {
        assertNull(extractAuthUrl("Update available: https://github.com/x/y/releases"))
        assertEquals("https://auth.openai.com/codex/device",
            extractAuthUrl("see https://docs.rs/x then https://auth.openai.com/codex/device."))
        assertNull(extractAuthUrl("no url here"))
    }

    @Test fun jwtEmailRejectsGarbage() {
        assertNull(CliCredentialProbe.jwtEmail("nope"))
        assertNull(CliCredentialProbe.jwtEmail("a.!!!.c"))
    }
}
