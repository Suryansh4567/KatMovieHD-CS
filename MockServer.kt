import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.io.File
import java.net.URLDecoder

fun main() {
    val server = HttpServer.create(InetSocketAddress(8080), 0)
    server.createContext("/") { exchange ->
        val path = exchange.requestURI.path
        println("Server received request: $path")
        val response = when {
            path.contains("/movie/251/starman/") -> File("starman.html").readText()
            path.contains("/galaxy/") -> File("galaxy.html").readText()
            path.contains("/unlock-links/") -> File("unlock.html").readText()
            path.contains("/action") -> """{"status":true,"download_url":"https:\/\/mock-worker.dev\/download\/test"}"""
            path.contains("/download/") -> """<section id="generate_url" data-token="mock-token" data-uid="test"></section>"""
            else -> "Not Found"
        }
        
        // Mock Set-Cookie for /download/
        if (path.contains("/download/")) {
            exchange.responseHeaders.add("Set-Cookie", "PHPSESSID=mock-session-id; path=/")
        }

        val bytes = response.toByteArray()
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.getResponseBody().write(bytes)
        exchange.getResponseBody().close()
    }
    server.executor = null
    server.start()
    println("Mock server started on http://localhost:8080")
}
