package com.webdav.album

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Regression guards for the 1.5.0 brand icon and the compact settings home. */
class AppBrandAndSettings150Test {
    private fun appFile(path: String): File {
        val candidates = listOf(File(path), File("app/$path"), File("../app/$path"))
        return candidates.firstOrNull { it.isFile }
            ?: error("Missing source: $path")
    }

    @Test fun allLauncherAliasesAndAppUseTheSameNewIcon() {
        val xml = appFile("src/main/AndroidManifest.xml").readText()
        assertEquals(4, Regex("android:icon=\"@drawable/webalbum_icon\"").findAll(xml).count())
        assertFalse(xml.contains("@drawable/ic_album"))
        val icon = appFile("src/main/res/drawable-nodpi/webalbum_icon.webp").readBytes()
        assertTrue("WebP resource should be nonempty", icon.size > 1000)
        assertEquals("RIFF", String(icon.copyOfRange(0, 4), Charsets.US_ASCII))
        assertEquals("WEBP", String(icon.copyOfRange(8, 12), Charsets.US_ASCII))
    }

    @Test fun aboutAndInternalPreviewUseNewBrandWithFitNotCrop() {
        val about = appFile("src/main/java/com/webdav/album/AboutWebAlbumPage.kt").readText()
        val settings = appFile("src/main/java/com/webdav/album/SettingsScreens.kt").readText()
        assertTrue(about.contains("painterResource(R.drawable.webalbum_icon)"))
        assertTrue(about.contains("ContentScale.Fit"))
        assertTrue(settings.contains("painter = painterResource(R.drawable.webalbum_icon)"))
        assertTrue(settings.contains("ContentScale.Fit"))
        assertFalse(about.contains("R.drawable.ic_album"))
        assertFalse(settings.contains("R.drawable.ic_album"))
    }

    @Test fun compactSettingsMergesMusicAndPersonalizationAndOmitsNoise() {
        val settings = appFile("src/main/java/com/webdav/album/SettingsScreens.kt").readText()
        val home = settings.substringAfter("fun SettingsHome(")
            .substringBefore("fun StorageSettingsPage(")
        val group = home.substringAfter("LucentSection(\"偏好与功能\")")
            .substringBefore("LucentSection(\"存储与连接\")")
        assertTrue(group.contains("LucentRow(\"个性化\""))
        assertTrue(group.contains("LucentRow(\"音乐播放器\""))
        assertFalse(group.contains("LucentSection(\"音乐播放\")"))
        assertFalse(home.contains("主题、字体与图库布局"))
        assertFalse(home.contains("GreenMusic 风格播放器"))
        assertTrue(settings.contains("verticalArrangement = Arrangement.spacedBy(12.dp)"))
        val primitive = listOf(
            File("src/main/java/dev/chengeeker/lucent/review/SettingsPrimitives.kt"),
            File("lucent-ui/src/main/java/dev/chengeeker/lucent/review/SettingsPrimitives.kt"),
            File("../lucent-ui/src/main/java/dev/chengeeker/lucent/review/SettingsPrimitives.kt"),
        ).first { it.isFile }.readText()
        assertTrue(primitive.contains("bottom = 6.dp"))
        assertTrue(primitive.contains("Spacer(Modifier.height(8.dp))"))
    }

    @Test fun releaseVersionMatchesTheRequestedIdentity() {
        val path = listOf(File("build.gradle.kts"), File("app/build.gradle.kts")).first {
            it.isFile && it.readText().contains("applicationId = \"com.webdav.album\"")
        }
        val build = path.readText()
        assertTrue(build.contains("versionCode = 41; versionName = \"1.5.3\""))
    }
}
