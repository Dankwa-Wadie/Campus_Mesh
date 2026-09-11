package com.campusmesh.android.net

import org.junit.Assert.*
import org.junit.Test
import com.google.gson.Gson

class GithubUpdateCheckerTest {
    @Test
    fun testIsNewerVersion() {
        // Test normal newer versions
        assertTrue(GithubUpdateChecker.isNewerVersion("1.0.1", "1.0.3"))
        assertTrue(GithubUpdateChecker.isNewerVersion("1.0.1", "1.1.0"))
        assertTrue(GithubUpdateChecker.isNewerVersion("1.0.1", "2.0.0"))

        // Test equal versions
        assertFalse(GithubUpdateChecker.isNewerVersion("1.0.1", "1.0.1"))
        assertFalse(GithubUpdateChecker.isNewerVersion("2.1", "2.1"))

        // Test older versions
        assertFalse(GithubUpdateChecker.isNewerVersion("1.0.3", "1.0.1"))
        assertFalse(GithubUpdateChecker.isNewerVersion("1.1.0", "1.0.1"))
        assertFalse(GithubUpdateChecker.isNewerVersion("2.0.0", "1.0.1"))

        // Test empty/invalid versions
        assertFalse(GithubUpdateChecker.isNewerVersion("1.0.1", ""))
        assertFalse(GithubUpdateChecker.isNewerVersion("1.0.1", "invalid"))

        // Test pre-release tag splits
        assertFalse(GithubUpdateChecker.isNewerVersion("1.0.0-beta", "1.0.0-rc"))
        assertTrue(GithubUpdateChecker.isNewerVersion("1.0.0-beta", "1.0.1-rc"))
    }

    @Test
    fun testGsonParsingWithNullTagNameAndNameFallback() {
        val gson = Gson()

        // Case A: normal release
        val jsonA = """{"tag_name":"v1.0.3","name":"1.0.3"}"""
        val releaseA = gson.fromJson(jsonA, GithubRelease::class.java)
        assertEquals("v1.0.3", releaseA.tagName)
        assertEquals("1.0.3", releaseA.name)

        // Case B: tag_name is "v", name is "1.0.3"
        val jsonB = """{"tag_name":"v","name":"1.0.3"}"""
        val releaseB = gson.fromJson(jsonB, GithubRelease::class.java)
        assertEquals("v", releaseB.tagName)
        assertEquals("1.0.3", releaseB.name)

        // Case C: tag_name is missing/null, name is "1.0.3"
        val jsonC = """{"name":"1.0.3"}"""
        val releaseC = gson.fromJson(jsonC, GithubRelease::class.java)
        assertNull(releaseC.tagName)
        assertEquals("1.0.3", releaseC.name)

        // Test fallback logic used in checkForUpdates
        val getLatestVersion = { release: GithubRelease ->
            val rawTag = release.tagName?.trim() ?: ""
            var latestVersion = rawTag.removePrefix("v").removePrefix("V")
            if (latestVersion.isBlank() && !release.name.isNullOrBlank()) {
                latestVersion = release.name.trim().removePrefix("v").removePrefix("V")
            }
            latestVersion
        }

        assertEquals("1.0.3", getLatestVersion(releaseA))
        assertEquals("1.0.3", getLatestVersion(releaseB))
        assertEquals("1.0.3", getLatestVersion(releaseC))
    }

    @Test
    fun testGithubReleaseCheckerParseRelease() {
        // Case A: normal release
        val jsonA = """
            {
              "tag_name": "v1.0.3",
              "name": "1.0.3",
              "assets": [
                {
                  "name": "app-debug.apk",
                  "browser_download_url": "https://github.com/Dankwa-Wadie/Campus_Mesh/releases/download/v1.0.3/app-debug.apk",
                  "size": 12345
                }
              ]
            }
        """.trimIndent()
        val infoA = GithubReleaseChecker.parseRelease(jsonA)
        assertNotNull(infoA)
        assertEquals("v1.0.3", infoA?.tagName)
        assertEquals(103, infoA?.versionCode)
        assertEquals("https://github.com/Dankwa-Wadie/Campus_Mesh/releases/download/v1.0.3/app-debug.apk", infoA?.apkDownloadUrl)

        // Case B: tag_name is "v", name is "1.0.3"
        val jsonB = """
            {
              "tag_name": "v",
              "name": "1.0.3",
              "assets": [
                {
                  "name": "app-debug.apk",
                  "browser_download_url": "https://github.com/Dankwa-Wadie/Campus_Mesh/releases/download/v/app-debug.apk",
                  "size": 12345
                }
              ]
            }
        """.trimIndent()
        val infoB = GithubReleaseChecker.parseRelease(jsonB)
        assertNotNull(infoB)
        // Since tag_name was "v", it should fall back to the release name "1.0.3"
        assertEquals("1.0.3", infoB?.tagName)
        assertEquals(103, infoB?.versionCode)
        assertEquals("https://github.com/Dankwa-Wadie/Campus_Mesh/releases/download/v/app-debug.apk", infoB?.apkDownloadUrl)
    }

    @Test
    fun testParseVersionCode() {
        assertEquals(130, GithubReleaseChecker.parseVersionCode("v1.3.0"))
        assertEquals(214, GithubReleaseChecker.parseVersionCode("v2.1.4"))
        assertEquals(100, GithubReleaseChecker.parseVersionCode("v1"))
        assertEquals(120, GithubReleaseChecker.parseVersionCode("v1.2"))
        assertEquals(0, GithubReleaseChecker.parseVersionCode(""))
        assertEquals(0, GithubReleaseChecker.parseVersionCode("invalid"))
    }
}
