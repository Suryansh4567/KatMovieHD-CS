import java.io.*;
import java.util.regex.*;

public class RealProbeJava {
    public static void main(String[] args) throws Exception {
        System.out.println("=== REAL NETWORK PROBE START ===");
        String url = "https://photolinx.beauty/download/ndTM1a0dKYz";
        String ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
        
        System.out.println("Probing GET " + url + "...");
        Process getProcess = new ProcessBuilder("curl", "-s", "-i", "-H", "User-Agent: " + ua, url).start();
        BufferedReader getReader = new BufferedReader(new InputStreamReader(getProcess.getInputStream()));
        StringBuilder getOutput = new StringBuilder();
        String line;
        while ((line = getReader.readLine()) != null) {
            getOutput.append(line).append("\n");
        }
        
        String output = getOutput.toString();
        Matcher sessMatcher = Pattern.compile("PHPSESSID=([^;]+)").matcher(output);
        String phpsessid = sessMatcher.find() ? sessMatcher.group(1) : null;
        System.out.println("Extracted PHPSESSID: " + phpsessid);
        
        Matcher tokenMatcher = Pattern.compile("data-token=\"([^\"]+)\"").matcher(output);
        String token = tokenMatcher.find() ? tokenMatcher.group(1) : null;
        
        Matcher uidMatcher = Pattern.compile("data-uid=\"([^\"]+)\"").matcher(output);
        String uid = uidMatcher.find() ? uidMatcher.group(1) : null;
        
        System.out.println("Extracted token: " + token);
        System.out.println("Extracted uid: " + uid);
        
        if (phpsessid == null || token == null || uid == null) {
            System.out.println("FAILED: Could not extract required data from GET response.");
            return;
        }
        
        System.out.println("Probing POST https://photolinx.beauty/action...");
        String json = "{\"type\":\"DOWNLOAD_GENERATE\",\"payload\":{\"uid\":\"" + uid + "\",\"access_token\":\"" + token + "\"}}";
        Process postProcess = new ProcessBuilder(
            "curl", "-s", "-X", "POST",
            "-H", "User-Agent: " + ua,
            "-H", "Content-Type: application/json",
            "-H", "X-Requested-With: XMLHttpRequest",
            "-H", "Referer: " + url,
            "-H", "Cookie: PHPSESSID=" + phpsessid,
            "-d", json,
            "https://photolinx.beauty/action"
        ).start();
        
        BufferedReader postReader = new BufferedReader(new InputStreamReader(postProcess.getInputStream()));
        StringBuilder postOutput = new StringBuilder();
        while ((line = postReader.readLine()) != null) {
            postOutput.append(line);
        }
        String pOut = postOutput.toString();
        System.out.println("POST response: " + pOut);
        
        Matcher dlMatcher = Pattern.compile("\"download_url\"\\s*:\\s*\"([^\"]+)\"").matcher(pOut);
        if (dlMatcher.find()) {
            String downloadUrl = dlMatcher.group(1).replace("\\/", "/");
            System.out.println("[RESULT] Successfully extracted real download URL: " + downloadUrl);
            System.out.println("SUCCESS: Real network verification complete.");
        } else {
            System.out.println("FAILED: Server returned error or empty download URL.");
        }
        System.out.println("=== REAL NETWORK PROBE END ===");
    }
}
