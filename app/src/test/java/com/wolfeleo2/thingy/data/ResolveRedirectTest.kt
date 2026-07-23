package com.wolfeleo2.thingy.data

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress

/** Mocks pin.it's actual redirect behavior (a bare HTTP 308) with a local JDK HttpServer — no
 * real network calls, and reproduces the exact bug: HttpURLConnection.instanceFollowRedirects
 * silently ignores 308, which is why resolveRedirectFollowing308 exists. */
class ResolveRedirectTest {

    private lateinit var server: HttpServer
    private var baseUrl = ""

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("localhost", 0), 0)
        baseUrl = "http://localhost:${server.address.port}"
    }

    @After
    fun stop() {
        server.stop(0)
    }

    private fun redirect(path: String, code: Int, location: String) {
        server.createContext(path) { ex ->
            ex.responseHeaders.add("Location", location)
            ex.sendResponseHeaders(code, -1)
            ex.close()
        }
    }

    private fun terminal(path: String, body: String) {
        server.createContext(path) { ex ->
            ex.sendResponseHeaders(200, body.length.toLong())
            ex.responseBody.use { it.write(body.toByteArray()) }
        }
    }

    @Test
    fun `follows a single 308 to its final url`() {
        redirect("/short", 308, "$baseUrl/pin/319826011047823011/sent/")
        terminal("/pin/319826011047823011/sent/", "ok")
        server.start()

        val resolved = resolveRedirectFollowing308("$baseUrl/short", "test-agent")
        assertEquals("$baseUrl/pin/319826011047823011/sent/", resolved)
    }

    @Test
    fun `follows a chain of redirects`() {
        redirect("/a", 308, "$baseUrl/b")
        redirect("/b", 302, "$baseUrl/c")
        terminal("/c", "ok")
        server.start()

        assertEquals("$baseUrl/c", resolveRedirectFollowing308("$baseUrl/a", "test-agent"))
    }

    @Test
    fun `returns original url when the server errors instead of redirecting`() {
        server.createContext("/broken") { ex -> ex.sendResponseHeaders(500, -1); ex.close() }
        server.start()

        assertEquals("$baseUrl/broken", resolveRedirectFollowing308("$baseUrl/broken", "test-agent"))
    }
}
