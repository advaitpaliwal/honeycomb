package org.honeycomb.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdaterTest {

    @Test fun semverParsingAndComparison() {
        assertEquals(Triple(0, 6, 0), AppUpdater.parseSemver("v0.6.0"))
        assertEquals(Triple(0, 6, 0), AppUpdater.parseSemver("0.6"))
        assertEquals(Triple(1, 2, 3), AppUpdater.parseSemver("V1.2.3-rc1+42"))
        assertNull(AppUpdater.parseSemver("invalid"))
        assertNull(AppUpdater.parseSemver("1"))

        assertTrue(AppUpdater.isNewerVersion("0.5.9", "0.6.0"))
        assertTrue(AppUpdater.isNewerVersion("0.6.0", "0.6.1"))
        assertTrue(AppUpdater.isNewerVersion("0.9.9", "1.0.0"))
        assertTrue(AppUpdater.isNewerVersion("0.1", "0.6.0"))

        assertFalse(AppUpdater.isNewerVersion("0.6.0", "0.6.0"))
        assertFalse(AppUpdater.isNewerVersion("0.6.1", "0.6.0"))
        assertFalse(AppUpdater.isNewerVersion("1.0.0", "0.9.9"))
    }

    @Test fun hostAllowlistRejectsUntrustedOrHttpUrls() {
        assertTrue(AppUpdater.isAllowedUrl("https://api.github.com/repos/advaitpaliwal/honeycomb/releases/latest"))
        assertTrue(AppUpdater.isAllowedUrl("https://github.com/advaitpaliwal/honeycomb/releases/download/v0.6.0/honeycomb-0.6.0.apk"))
        assertTrue(AppUpdater.isAllowedUrl("https://objects.githubusercontent.com/github-production-release-asset-123/test.apk"))
        assertTrue(AppUpdater.isAllowedUrl("https://release-assets.githubusercontent.com/github-production-release-asset-123/test.apk"))

        assertFalse(AppUpdater.isAllowedUrl("http://github.com/advaitpaliwal/honeycomb/releases/download/v0.6.0/honeycomb-0.6.0.apk"))
        assertFalse(AppUpdater.isAllowedUrl("https://evil.example.com/honeycomb-0.6.0.apk"))
        assertFalse(AppUpdater.isAllowedUrl("https://github.com.evil.example.com/honeycomb-0.6.0.apk"))
        assertFalse(AppUpdater.isAllowedUrl("file:///sdcard/honeycomb.apk"))
    }

    @Test fun sha256TextParsing() {
        val digest = "341395e89944cae9ede496c6fe9df0aa5b1c23aaa9623776d16f4dfe7c0c3715"
        assertEquals(digest, AppUpdater.parseSha256Text("$digest  honeycomb-0.6.0.apk\n"))
        assertEquals(digest, AppUpdater.parseSha256Text(digest.uppercase()))
        assertNull(AppUpdater.parseSha256Text("not-a-hash"))
        assertNull(AppUpdater.parseSha256Text(digest.dropLast(1)))
    }

    @Test fun parseReleaseJsonExtractsApkAndChecksum() {
        val payload = """
            {
              "tag_name": "v0.6.1",
              "draft": false,
              "prerelease": false,
              "body": "Built-in updates and fixes.",
              "assets": [
                {
                  "name": "honeycomb-0.6.1.apk",
                  "browser_download_url": "https://github.com/advaitpaliwal/honeycomb/releases/download/v0.6.1/honeycomb-0.6.1.apk"
                },
                {
                  "name": "honeycomb-0.6.1.apk.sha256",
                  "browser_download_url": "https://github.com/advaitpaliwal/honeycomb/releases/download/v0.6.1/honeycomb-0.6.1.apk.sha256"
                }
              ]
            }
        """.trimIndent()
        val parsed = AppUpdater.parseReleaseJson(payload)
        assertNotNull(parsed)
        assertEquals("v0.6.1", parsed!!.tag)
        assertEquals("0.6.1", parsed.version)
        assertEquals(
            "https://github.com/advaitpaliwal/honeycomb/releases/download/v0.6.1/honeycomb-0.6.1.apk",
            parsed.apkUrl,
        )
        assertEquals(
            "https://github.com/advaitpaliwal/honeycomb/releases/download/v0.6.1/honeycomb-0.6.1.apk.sha256",
            parsed.sha256Url,
        )
        assertEquals("Built-in updates and fixes.", parsed.notes)
    }

    @Test fun parseReleaseJsonRejectsDraftPrereleaseAndUntrustedAssets() {
        val draft = """
            {
              "tag_name": "v0.6.1",
              "draft": true,
              "prerelease": false,
              "assets": [
                {"name": "honeycomb-0.6.1.apk", "browser_download_url": "https://github.com/advaitpaliwal/honeycomb/releases/download/v0.6.1/honeycomb-0.6.1.apk"}
              ]
            }
        """.trimIndent()
        assertNull(AppUpdater.parseReleaseJson(draft))

        val untrusted = """
            {
              "tag_name": "v0.6.1",
              "draft": false,
              "prerelease": false,
              "assets": [
                {"name": "honeycomb-0.6.1.apk", "browser_download_url": "https://evil.example.com/honeycomb-0.6.1.apk"}
              ]
            }
        """.trimIndent()
        assertNull(AppUpdater.parseReleaseJson(untrusted))
    }
}
