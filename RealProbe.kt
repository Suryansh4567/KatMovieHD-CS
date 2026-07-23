import java.io.File
import java.net.URLDecoder

fun main() {
    println("=== REAL NETWORK PROBE START ===")
    val url = "https://photolinx.beauty/download/ndTM1a0dKYz"
    val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    
    // Step 1: GET page and capture cookies
    println("Probing GET $url...")
    val getCmd = arrayOf("curl", "-s", "-i", "-H", "User-Agent: $ua", url)
    val getProcess = Runtime.getRuntime().exec(getCmd)
    val getOutput = getProcess.inputStream.bufferedReader().readText()
    
    val phpsessid = Regex("""PHPSESSID=([^;]+)""").find(getOutput)?.groupValues?.get(1)
    println("Extracted PHPSESSID: $phpsessid")
    
    val token = Regex("""data-token="([^"]+)"""").find(getOutput)?.groupValues?.get(1)
    val uid = Regex("""data-uid="([^"]+)"""").find(getOutput)?.groupValues?.get(1)
    println("Extracted token: $token")
    println("Extracted uid: $uid")
    
    if (phpsessid == null || token == null || uid == null) {
        println("FAILED: Could not extract required data from GET response.")
        return
    }
    
    // Step 2: POST action
    println("Probing POST https://photolinx.beauty/action...")
    val json = """{"type":"DOWNLOAD_GENERATE","payload":{"uid":"$uid","access_token":"$token"}}"""
    val postCmd = arrayOf(
        "curl", "-s", "-X", "POST",
        "-H", "User-Agent: $ua",
        "-H", "Content-Type: application/json",
        "-H", "X-Requested-With: XMLHttpRequest",
        "-H", "Referer: $url",
        "-H", "Cookie: PHPSESSID=$phpsessid",
        "-d", json,
        "https://photolinx.beauty/action"
    )
    val postProcess = Runtime.getRuntime().exec(postCmd)
    val postOutput = postProcess.inputStream.bufferedReader().readText()
    println("POST response: $postOutput")
    
    val downloadUrlMatch = Regex(""""download_url"\s*:\s*"([^"]+)"""").find(postOutput)
    val downloadUrl = downloadUrlMatch?.groupValues?.get(1)?.replace("\\/", "/")
    
    if (downloadUrl != null) {
        println("[RESULT] Successfully extracted real download URL: $downloadUrl")
        println("SUCCESS: Real network verification complete.")
    } else {
        println("FAILED: Server returned error or empty download URL.")
    }
    println("=== REAL NETWORK PROBE END ===")
}
