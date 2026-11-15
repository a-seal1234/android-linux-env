package com.alw.workstation.core.proot.oci

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OciRegistryClientTest {

    private val client = OciRegistryClient()

    @Test
    fun testNormalizeRepository() {
        assertEquals("library/ubuntu", client.normalizeRepository("ubuntu"))
        assertEquals("library/alpine", client.normalizeRepository("alpine"))
        assertEquals("kalilinux/kali-rolling", client.normalizeRepository("kalilinux/kali-rolling"))
        assertEquals("opensuse/tumbleweed", client.normalizeRepository("opensuse/tumbleweed"))
        assertEquals("myuser/mycustom", client.normalizeRepository("myuser/mycustom"))
    }

    @Test
    fun testArm64PlatformDetection() {
        val arm64 = OciPlatform(architecture = "arm64", os = "linux")
        val aarch64 = OciPlatform(architecture = "aarch64", os = "linux")
        val amd64 = OciPlatform(architecture = "amd64", os = "linux")
        val windowsArm64 = OciPlatform(architecture = "arm64", os = "windows")

        assertTrue(arm64.isArm64())
        assertTrue(aarch64.isArm64())
        assertFalse(amd64.isArm64())
        assertFalse(windowsArm64.isArm64())
    }

    @Test
    fun testMultiArchManifestListParsing() {
        val manifestListJson = JSONObject().apply {
            put("schemaVersion", 2)
            put("mediaType", "application/vnd.docker.distribution.manifest.list.v2+json")
            put("manifests", JSONArray().apply {
                // Entry 1: amd64
                put(JSONObject().apply {
                    put("mediaType", "application/vnd.docker.distribution.manifest.v2+json")
                    put("size", 528)
                    put("digest", "sha256:amd64digest123")
                    put("platform", JSONObject().apply {
                        put("architecture", "amd64")
                        put("os", "linux")
                    })
                })
                // Entry 2: arm64
                put(JSONObject().apply {
                    put("mediaType", "application/vnd.docker.distribution.manifest.v2+json")
                    put("size", 528)
                    put("digest", "sha256:arm64digest456")
                    put("platform", JSONObject().apply {
                        put("architecture", "arm64")
                        put("os", "linux")
                    })
                })
            })
        }

        val manifestsArray = manifestListJson.getJSONArray("manifests")
        var selectedDigest: String? = null
        for (i in 0 until manifestsArray.length()) {
            val m = manifestsArray.getJSONObject(i)
            val p = m.getJSONObject("platform")
            if (p.getString("os") == "linux" && p.getString("architecture") == "arm64") {
                selectedDigest = m.getString("digest")
                break
            }
        }

        assertEquals("sha256:arm64digest456", selectedDigest)
    }

    @Test
    fun testDistroCatalogIntegrity() {
        assertTrue(OciDistroCatalog.ENTRIES.isNotEmpty())
        val ubuntu = OciDistroCatalog.findById("ubuntu")
        val debian = OciDistroCatalog.findById("debian")
        val fedora = OciDistroCatalog.findById("fedora")
        val alpine = OciDistroCatalog.findById("alpine")
        val arch = OciDistroCatalog.findById("archlinux")

        assertEquals("library/ubuntu", ubuntu?.repository)
        assertEquals("library/debian", debian?.repository)
        assertEquals("library/fedora", fedora?.repository)
        assertEquals("library/alpine", alpine?.repository)
        assertEquals("library/archlinux", arch?.repository)
    }
}
