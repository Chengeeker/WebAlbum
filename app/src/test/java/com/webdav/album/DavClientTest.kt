package com.webdav.album

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = AlbumApplication::class)
class DavClientTest {
    private val client = DavClient(OkHttpClient())
    private val root = "https://example.com/dav/".toHttpUrl()
    @Test fun deleteOnlyAllowsDirectFilesAndReportsPartialFailure() {
        MockWebServer().use { server ->
            server.start()
            val app = RuntimeEnvironment.getApplication() as AlbumApplication
            app.connection = Connection(server.url("/dav/").toString(), "alice", "secret")
            val root = app.connection!!.root
            val dav = DavClient(app.client)
            val item = DavItem(server.url("/dav/a.jpg").toString(), "a.jpg", false, "\"version-1\"||")
            server.enqueue(MockResponse().setResponseCode(204))
            dav.delete(root, root, item)
            val request = server.takeRequest()
            assertEquals("DELETE", request.method)
            assertEquals("Basic YWxpY2U6c2VjcmV0", request.getHeader("Authorization"))
            assertEquals("\"version-1\"", request.getHeader("If-Match"))
            for (bad in listOf(item.copy(directory = true), item.copy(url = server.url("/dav/sub/a.jpg").toString()),
                item.copy(url = server.url("/dav/hidden%2Ffile.jpg").toString()), item.copy(url = "https://other.example/dav/a.jpg"))) {
                try { dav.delete(root, root, bad); fail("Unsafe delete accepted") } catch (_: IllegalArgumentException) { }
            }
            server.enqueue(MockResponse().setResponseCode(207))
            try { dav.delete(root, root, item); fail("Multi-status must not be reported as success") } catch (_: IllegalStateException) { }
            assertEquals("DELETE", server.takeRequest().method)
            assertNull(server.takeRequest(100, java.util.concurrent.TimeUnit.MILLISECONDS))
        }
    }
    @Test fun videoDataSourceUsesWebDavAuthentication() {
        MockWebServer().use { server ->
            server.start()
            val app = RuntimeEnvironment.getApplication() as AlbumApplication
            app.connection = Connection(server.url("/dav/").toString(), "alice", "secret")
            server.enqueue(MockResponse().setBody("video"))
            val factory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(okhttp3.Call.Factory { app.client.newCall(it) })
            val source = factory.createDataSource()
            try {
                source.open(androidx.media3.datasource.DataSpec(android.net.Uri.parse(server.url("/dav/a.mp4").toString())))
                val bytes = ByteArray(5)
                assertEquals(5, source.read(bytes, 0, bytes.size))
                assertEquals("video", String(bytes))
            } finally { source.close() }
            assertEquals("Basic YWxpY2U6c2VjcmV0", server.takeRequest().getHeader("Authorization"))
            assertTrue(DavItem("", "a.MOV", false, "").video)
            assertFalse(DavItem("", "a.mp4", true, "").video)
        }
    }
    @Test fun onlySuccessfulDirectChildrenFromSameOriginAreReturned() {
        val xml = """<d:multistatus xmlns:d="DAV:">
          <d:response><d:href>/dav/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
          <d:response><d:href>/dav/%E7%85%A7%E7%89%87</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
          <d:response><d:href>/dav/a.jpg</d:href><d:propstat><d:prop><d:getetag>v1</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
          <d:response><d:href>https://evil.example/dav/b.jpg</d:href><d:propstat><d:prop/><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
          <d:response><d:href>/dav/nested/b.jpg</d:href><d:propstat><d:prop/><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
          <d:response><d:href>/dav/missing.jpg</d:href><d:propstat><d:prop/><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat></d:response>
        </d:multistatus>"""
        val items = client.parse(root, root, xml)
        assertEquals(listOf("照片", "a.jpg"), items.map { it.name })
        assertTrue(items.first().url.endsWith("/"))
        assertTrue(items.last().image)
        assertTrue(items.last().version.contains("v1"))
    }
    @Test fun validatesCredentialsAndVersionedCacheIdentity() {
        assertNull(validConnection("https://name:secret@example.com/dav"))
        assertNull(validConnection("https://example.com/dav?token=abc"))
        assertNotNull(validConnection("http://192.168.1.2/dav/"))
        val item = DavItem("https://example.com/dav/a.jpg", "a.jpg", false, "v1")
        val connection = Connection(root.toString(), "user", "password")
        assertNotEquals(item.imageUrl(connection), item.copy(version = "v2").imageUrl(connection))
        assertNotEquals(item.imageUrl(connection), item.imageUrl(connection.copy(password = "changed")))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsDoctype() {
        client.parse(root, root, "<!DOCTYPE x [<!ENTITY x 'unsafe'>]><x/>")
    }
    @Test fun sendsCredentialsOnlyToConfiguredOrigin() {
        MockWebServer().use { server -> MockWebServer().use { other ->
            server.start(); other.start()
            val app = RuntimeEnvironment.getApplication() as AlbumApplication
            app.connection = Connection(server.url("/dav/").toString(), "alice", "secret")
            server.enqueue(MockResponse().setResponseCode(207).setBody("""<d:multistatus xmlns:d="DAV:"><d:response><d:href>/dav/a.jpg</d:href><d:propstat><d:prop/><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>"""))
            assertEquals(1, DavClient(app.client).list(app.connection!!.root, app.connection!!.root).size)
            val first = server.takeRequest()
            assertEquals("PROPFIND", first.method)
            assertEquals("1", first.getHeader("Depth"))
            assertEquals("Basic YWxpY2U6c2VjcmV0", first.getHeader("Authorization"))
            other.enqueue(MockResponse().setBody("ok"))
            app.client.newCall(Request.Builder().url(other.url("/")).build()).execute().close()
            assertNull(other.takeRequest().getHeader("Authorization"))
        } }
    }
    @Test fun activityStartsWithoutAConfiguredServer() {
        (RuntimeEnvironment.getApplication() as AlbumApplication).connection = null
        Robolectric.buildActivity(MainActivity::class.java).setup().use { assertNotNull(it.get()) }
    }
    @Test fun followsSameOriginRedirectButDoesNotSendCredentialsToOtherHttpServers() {
        MockWebServer().use { server -> MockWebServer().use { other ->
            server.start(); other.start()
            val app = RuntimeEnvironment.getApplication() as AlbumApplication
            app.connection = Connection(server.url("/dav/").toString(), "alice", "secret")
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/dav/actual.jpg"))
            server.enqueue(MockResponse().setBody("image"))
            app.client.newCall(Request.Builder().url(server.url("/dav/a.jpg")).build()).execute().use { assertEquals(200, it.code) }
            assertEquals("Basic YWxpY2U6c2VjcmV0", server.takeRequest().getHeader("Authorization"))
            assertEquals("Basic YWxpY2U6c2VjcmV0", server.takeRequest().getHeader("Authorization"))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/leak")))
            app.client.newCall(Request.Builder().url(server.url("/dav/b.jpg")).build()).execute().use { assertEquals(302, it.code) }
            server.takeRequest()
            assertNull(other.takeRequest(200, java.util.concurrent.TimeUnit.MILLISECONDS))
            server.enqueue(MockResponse().setResponseCode(303).setHeader("Location", "/dav/other"))
            val propfind = Request.Builder().url(server.url("/dav/")).method("PROPFIND", okhttp3.RequestBody.create(null, ByteArray(0))).build()
            app.client.newCall(propfind).execute().use { assertEquals(303, it.code) }
            server.takeRequest()
            assertNull(server.takeRequest(200, java.util.concurrent.TimeUnit.MILLISECONDS))
        } }
    }
}
