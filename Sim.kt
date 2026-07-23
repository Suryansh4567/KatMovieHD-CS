import org.jsoup.Jsoup
import java.net.URLDecoder

// Minimal mock of ExtractorLink
data class Link(val name: String, val source: String, val url: String)

fun main() {
    println("=== Manual Logic Simulation ===")
    
    // Simulate Step 5: Extract links from unlock.html
    val html = java.io.File("unlock.html").readText()
    val doc = Jsoup.parse(html)
    val depisodeLinks = doc.select("a[href*=/depisode/]")
    println("Found ${depisodeLinks.size} depisode links")

    val results = mutableListOf<Link>()

    for (el in depisodeLinks) {
        val href = el.attr("href")
        if (!href.contains("url=")) continue
        val finalUrl = URLDecoder.decode(href.substringAfter("url="), "UTF-8")
        
        // Simulate shouldSkip (only for Photolinx)
        if (finalUrl.contains("photolinx.beauty")) {
            // Simulate resolveUrl -> Photolinx().getUrl
            val phUid = Regex("""/download/([A-Za-z0-9_-]+)""").find(finalUrl)?.groupValues?.get(1)
            if (phUid != null) {
                // Simulate Photolinx extractor logic
                val phResultUrl = "https://mock-worker.dev/download/$phUid"
                
                // Simulate relabel()
                // Standard label format: {Brand} • {res} • {print} ...
                val summaryText = el.parents()
                    .firstOrNull { it.tagName() == "details" }
                    ?.selectFirst("summary")
                    ?.text()
                    ?.trim()
                
                // Mock parseGroupSummary
                val res = if (summaryText?.contains("1080p") == true) "1080p" 
                          else if (summaryText?.contains("720p") == true) "720p"
                          else "480p"
                
                val label = "TheNextPlanet [Photolinx] • $res"
                results.add(Link(label, label, phResultUrl))
                println("[RESULT] name='$label' source='$label' url='$phResultUrl'")
            }
        }
    }
    
    val photonCount = results.count { it.source.contains("[Photolinx]") }
    println("=== Simulation Finished: found $photonCount Photolinx links ===")
}
