package com.webdav.album

import android.content.Context
import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModelProvider
import java.time.Duration
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = AlbumApplication::class)
class PreferencesAndLockTest {
    private val app get() = RuntimeEnvironment.getApplication() as AlbumApplication

    @Before fun reset() {
        app.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("app_lock", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("connection", Context.MODE_PRIVATE).edit().clear().commit()
        app.connection = null
        app.applyNetwork(NetworkSettings())
    }

    @Test fun defaultFolderStaysInsideRootAndIsScopedToConnection() {
        val root = "https://example.com/dav/".toHttpUrl()
        val selected = "https://example.com/dav/%E7%85%A7%E7%89%87/2026/"
        assertEquals(listOf("https://example.com/dav/%E7%85%A7%E7%89%87/", selected), folderChain(root, selected))
        assertNull(folderChain(root, "https://example.com/dav-other/photo/"))
        assertNull(folderChain(root, "https://evil.example/dav/photo/"))
        assertNull(folderChain(root, "https://example.com/dav/photo/?secret=x"))
        assertNull(folderChain(root, "https://example.com/dav/photo%2F..%2Fprivate/"))
        assertEquals(emptyList<String>(), folderChain(root, root.toString()))
        val prefs = AlbumPreferences(app)
        val first = Connection(root.toString(), "one", "password")
        val second = first.copy(user = "two")
        prefs.setDefaultFolder(first, selected)
        assertEquals(selected, prefs.defaultFolder(first))
        assertNull(prefs.defaultFolder(second))
        prefs.setDefaultFolder(first, null)
        assertNull(prefs.defaultFolder(first))
    }

    @Test fun appearanceAndGridPersistAndRespectBounds() {
        val prefs = AlbumPreferences(app)
        prefs.updateColumns(5)
        prefs.updateFolderColumns(1)
        prefs.updateStyle(prefs.style.copy(mode = "dark", font = 1.2f, seed = 2, glass = true, translucent = false, haptics = false))
        prefs.updatePredictive(true)
        prefs.updateCacheThreshold(1024)
        val reloaded = AlbumPreferences(app)
        assertEquals(5, reloaded.columns)
        assertEquals(1, reloaded.folderColumns)
        assertEquals("dark", reloaded.style.mode)
        assertEquals(1.2f, reloaded.style.font, 0.001f)
        assertEquals(2, reloaded.style.seed)
        assertTrue(reloaded.style.glass)
        assertFalse(reloaded.style.translucent)
        assertFalse(reloaded.style.haptics)
        assertTrue(reloaded.predictive)
        assertEquals(1024, reloaded.cacheThreshold)
        reloaded.updateColumns(99)
        assertEquals(5, reloaded.columns)
    }

    @Test fun musicPreferencesPersistAndOldBackupsHaveSensibleDefaults() {
        val p = AlbumPreferences(app)
        assertTrue(p.musicMiniPlayer)
        assertEquals(MiniPlayerRecovery.MANUAL, p.musicMiniRecovery)
        assertTrue(p.musicKeepBackground)
        assertTrue(p.musicPreloadEnabled)
        assertFalse(p.musicProgressWavy) // default keeps the existing straight slider
        assertEquals(24, p.musicLyricsFontSize)
        assertEquals(MusicKaraokeMode.ALL, p.musicLyricKaraoke)
        p.updateMusicMiniPlayer(false)
        p.updateMusicMiniRecovery(MiniPlayerRecovery.RESUME)
        p.updateMusicKeepBackground(false)
        p.updateMusicPreloadEnabled(false)
        p.updateMusicProgressWavy(true)
        p.updateMusicLyricsFontSize(32)
        p.updateMusicLyricsWeight(700)
        p.updateMusicLyricsCenter(false)
        p.updateMusicLyricsBlur(false)
        p.updateMusicLyricsStagger(false)
        p.updateMusicLyricKaraoke(MusicKaraokeMode.TIMED)
        val backup = p.exportSettings()
        val reloaded = AlbumPreferences(app)
        assertFalse(reloaded.musicMiniPlayer)
        assertEquals(MiniPlayerRecovery.RESUME, reloaded.musicMiniRecovery)
        assertFalse(reloaded.musicKeepBackground)
        assertFalse(reloaded.musicPreloadEnabled)
        assertTrue(reloaded.musicProgressWavy)
        assertEquals(32, reloaded.musicLyricsFontSize)
        assertEquals(700, reloaded.musicLyricsWeight)
        assertEquals(MusicKaraokeMode.TIMED, reloaded.musicLyricKaraoke)
        reloaded.updateMusicMiniPlayer(true)
        reloaded.importSettings(backup)
        assertFalse(reloaded.musicMiniPlayer)
        assertEquals(MiniPlayerRecovery.RESUME, reloaded.musicMiniRecovery)
        assertFalse(reloaded.musicPreloadEnabled)
        assertTrue(reloaded.musicProgressWavy)
        assertFalse(reloaded.musicLyricsCenter)
        assertFalse(reloaded.musicLyricsBlur)
        assertFalse(reloaded.musicLyricsStagger)
        assertEquals(MusicKaraokeMode.TIMED, reloaded.musicLyricKaraoke)
        val legacy = org.json.JSONObject(backup).apply {
            remove("musicMiniPlayer"); remove("musicKeepBackground"); remove("musicMiniRecovery")
            remove("musicPreloadEnabled")
            remove("musicProgressWavy")
            remove("musicLyricsFontSize"); remove("musicLyricsWeight")
            remove("musicLyricsCenter"); remove("musicLyricsBlur")
            remove("musicLyricsStagger"); remove("musicLyricKaraoke")
        }.toString()
        reloaded.importSettings(legacy)
        assertTrue(reloaded.musicMiniPlayer)
        assertEquals(MiniPlayerRecovery.MANUAL, reloaded.musicMiniRecovery)
        assertTrue(reloaded.musicKeepBackground)
        assertTrue(reloaded.musicPreloadEnabled)
        assertFalse(reloaded.musicProgressWavy)
        assertEquals(24, reloaded.musicLyricsFontSize)
        assertEquals(600, reloaded.musicLyricsWeight)
        assertTrue(reloaded.musicLyricsCenter)
        assertTrue(reloaded.musicLyricsBlur)
        assertTrue(reloaded.musicLyricsStagger)
        assertEquals(MusicKaraokeMode.ALL, reloaded.musicLyricKaraoke)
    }

    @Test fun sortingPersistsAndOlderBackupsUseNewestModifiedFirst() {
        val prefs = AlbumPreferences(app)
        assertEquals(AlbumSort.MODIFIED, prefs.sortMode)
        assertTrue(prefs.sortDescending)
        prefs.updateSort(AlbumSort.NAME)
        assertTrue(prefs.sortDescending)
        prefs.updateSort(AlbumSort.NAME)
        assertFalse(prefs.sortDescending)
        val restored = AlbumPreferences(app)
        assertEquals(AlbumSort.NAME, restored.sortMode)
        assertFalse(restored.sortDescending)
        val backup = restored.exportSettings()
        restored.updateSort(AlbumSort.CAPTURE)
        restored.importSettings(backup)
        assertEquals(AlbumSort.NAME, restored.sortMode)
        assertFalse(restored.sortDescending)
        restored.importSettings(org.json.JSONObject(backup).apply { remove("sortMode"); remove("sortDescending") }.toString())
        assertEquals(AlbumSort.MODIFIED, restored.sortMode)
        assertTrue(restored.sortDescending)
    }

    @Test fun networkSettingsPersistAndConfigureNewClients() {
        val prefs = AlbumPreferences(app)
        val direct = NetworkSettings(timeout = 15, mode = "direct")
        prefs.updateNetwork(direct); app.applyNetwork(direct)
        assertEquals(15000, app.client.readTimeoutMillis)
        assertEquals(java.net.Proxy.Type.DIRECT, app.client.proxy?.type())
        val proxy = NetworkSettings(timeout = 60, mode = "http", host = "proxy.example", port = 8080)
        prefs.updateNetwork(proxy); app.applyNetwork(proxy)
        assertEquals(60000, app.client.connectTimeoutMillis)
        assertEquals(java.net.Proxy.Type.HTTP, app.client.proxy?.type())
        assertEquals(proxy, AlbumPreferences(app).network)
        assertFalse(validNetwork(proxy.copy(host = "bad/host")))
        assertFalse(validNetwork(proxy.copy(port = 0)))
        app.applyNetwork(NetworkSettings())
    }

    @Test fun settingsBackupValidatesBeforeRestoringAndPreservesPrivateData() {
        val prefs = AlbumPreferences(app)
        val connection = Connection("https://example.com/dav/", "user", "secret")
        val savedConnection = app.getSharedPreferences("connection", Context.MODE_PRIVATE)
        savedConnection.edit().putString("url", connection.url).putString("password", "encrypted-value").commit()
        prefs.setDefaultFolder(connection, "https://example.com/dav/photos/")
        prefs.updateNetwork(NetworkSettings(mode = "direct"))
        prefs.updateColumns(5)
        prefs.updateFolderColumns(4)
        prefs.updateDownloadTree("content://local/tree/private")
        prefs.updateStyle(prefs.style.copy(mode = "dark", glass = true, translucent = false))
        val lock = AppLock(app); lock.setBiometric(true)
        val backup = prefs.exportSettings()
        assertFalse(backup.contains("secret"))
        assertFalse(backup.contains("example.com"))
        assertFalse(backup.contains("content://local"))
        prefs.updateColumns(2); prefs.updateStyle(prefs.style.copy(mode = "light", glass = false))
        prefs.importSettings(backup)
        assertEquals(5, prefs.columns)
        assertEquals(4, prefs.folderColumns)
        assertEquals("content://local/tree/private", prefs.downloadTree)
        assertEquals("dark", prefs.style.mode)
        assertTrue(prefs.style.glass)
        assertFalse(prefs.style.translucent)
        assertEquals("direct", prefs.network.mode)
        assertEquals("https://example.com/dav/photos/", prefs.defaultFolder(connection))
        assertTrue(lock.biometricEnabled)
        assertEquals(connection.url, savedConnection.getString("url", null))
        assertEquals("encrypted-value", savedConnection.getString("password", null))
        val invalid = org.json.JSONObject(backup).put("columns", 9).toString()
        try { prefs.importSettings(invalid); fail("Invalid settings must fail") } catch (_: IllegalArgumentException) { }
        assertEquals(5, prefs.columns)
        assertEquals("dark", AlbumPreferences(app).style.mode)
        prefs.importSettings(org.json.JSONObject(backup).apply { remove("folderColumns"); remove("translucent") }.toString())
        assertEquals(2, prefs.folderColumns)
        assertTrue(prefs.style.translucent)
    }

    @Test fun mediaNameCardPreferencesPersistAndOldBackupRestoresSafely() {
        val prefs = AlbumPreferences(app)
        assertFalse(prefs.showMediaNames)
        assertFalse(prefs.compactMediaNames)
        prefs.updateShowMediaNames(true)
        prefs.updateCompactMediaNames(true)
        val reloaded = AlbumPreferences(app)
        assertTrue(reloaded.showMediaNames)
        assertTrue(reloaded.compactMediaNames)
        val backup = reloaded.exportSettings()
        assertTrue(org.json.JSONObject(backup).getBoolean("showMediaNames"))
        assertTrue(org.json.JSONObject(backup).getBoolean("compactMediaNames"))
        prefs.updateShowMediaNames(false)
        prefs.updateCompactMediaNames(false)
        prefs.importSettings(backup)
        assertTrue(prefs.showMediaNames)
        assertTrue(prefs.compactMediaNames)
        val legacy = org.json.JSONObject(backup).apply {
            remove("showMediaNames")
            remove("compactMediaNames")
        }.toString()
        prefs.importSettings(legacy)
        assertFalse(prefs.showMediaNames)
        assertFalse(prefs.compactMediaNames)
        prefs.updateShowMediaNames(true)
        prefs.updateColumns(5)
        assertTrue(prefs.showMediaNames) // 4-5 columns hide labels visually, not persistently
    }

    @Test fun iconSwitchKeepsOneLauncherEnabled() {
        val prefs = AlbumPreferences(app)
        val pm = app.packageManager
        fun component(name: String) = ComponentName(app, app.packageName + "." + name)
        prefs.setIcon(app, 1)
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, pm.getComponentEnabledSetting(component("LauncherLight")))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, pm.getComponentEnabledSetting(component("LauncherDefault")))
        assertEquals(1, AlbumPreferences(app).icon)
        prefs.setIcon(app, 0)
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, pm.getComponentEnabledSetting(component("LauncherDefault")))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, pm.getComponentEnabledSetting(component("LauncherLight")))
    }

    @Test fun pinIsSaltedAndFailuresRemainLimitedAfterReload() {
        val lock = AppLock(app)
        lock.setPin("123456")
        val saved = app.getSharedPreferences("app_lock", Context.MODE_PRIVATE).all
        assertFalse(saved.values.any { it == "123456" })
        repeat(5) { assertFalse(lock.verifyPin("999999")) }
        val reloaded = AppLock(app)
        assertTrue(reloaded.remainingSeconds() > 0)
        assertFalse(reloaded.verifyPin("123456"))
        ShadowSystemClock.advanceBy(Duration.ofSeconds(31))
        assertTrue(reloaded.verifyPin("123456"))
        reloaded.setBiometric(true)
        reloaded.disablePin()
        assertFalse(reloaded.pinEnabled)
        assertTrue(reloaded.biometricEnabled)
    }

    @Test fun appStartsLockedAndRelocksAfterBackground() {
        AppLock(app).setPin("2468")
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            val session = ViewModelProvider(activity)[LockSession::class.java]
            assertTrue(session.locked)
            session.locked = false
            controller.pause().stop()
            assertTrue(session.locked)
        }
    }

    @Test
    @Config(sdk = [26, 28])
    fun olderAndroidStartsLockedWithPin() {
        AppLock(app).setPin("2468")
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            assertTrue(ViewModelProvider(controller.get())[LockSession::class.java].locked)
            assertTrue(AppLock(app).verifyPin("2468"))
        }
    }

    @Test fun failedDefaultFolderFallsBackToRootAndKeepsSelection() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setResponseCode(207).setBody("<d:multistatus xmlns:d=\"DAV:\"/>"))
            server.start()
            val connection = Connection(server.url("/dav/").toString(), "user", "secret")
            app.connection = connection
            app.applyNetwork(NetworkSettings())
            val selected = server.url("/dav/photos/").toString()
            val prefs = AlbumPreferences(app); prefs.setDefaultFolder(connection, selected)
            val path = requireNotNull(folderChain(connection.root, selected))
            val listing = DavClient(app.client).listFolder(connection, path, fallback = true)
            assertTrue(listing.usedFallback)
            assertEquals(emptyList<String>(), listing.path)
            assertEquals("/dav/photos/", server.takeRequest(1, java.util.concurrent.TimeUnit.SECONDS)?.path)
            assertEquals("/dav/", server.takeRequest(1, java.util.concurrent.TimeUnit.SECONDS)?.path)
            assertEquals(selected, AlbumPreferences(app).defaultFolder(connection))
            app.connection = null
        }
    }
}
