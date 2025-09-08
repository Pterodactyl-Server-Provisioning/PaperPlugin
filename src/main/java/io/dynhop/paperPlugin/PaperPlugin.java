package io.dynhop.paperPlugin;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.logging.Level;

public final class PaperPlugin extends JavaPlugin {

    private String prefix = "§7[§cServer Manger§7] > ";

    String PROXY_IP = System.getenv("PROXY_IP");
    String PROXY_PORT = System.getenv("PROXY_PORT");
    String PROXY_KEY = System.getenv("PROXY_KEY");
    String SERVER_NAME = System.getenv("SERVER_NAME");
    String SERVER_IP  = System.getenv("SERVER_IP");
    String SERVER_PORT = System.getenv("SERVER_PORT");
    String SERVER_TYPE_FALLBACK = System.getenv("SERVER_TYPE_FALLBACK");

    private volatile boolean envValid = false;

    @Override
    public void onEnable() {
        getLogger().info(prefix + "Plugin has been enabled!");
        // Validate and normalize environment variables
        envValid = validateEnv();
        if (!envValid) {
            getLogger().severe(prefix + "Missing or invalid environment variables. Registration will be skipped.");
            return;
        }

        boolean isFallback = parseBoolean(SERVER_TYPE_FALLBACK, false);
        String baseUrl = "http://" + PROXY_IP + ":" + PROXY_PORT;
        String path = isFallback ? "/api/register-fallback" : "/api/register";
        // Send parameters as query string rather than POST body
        String postBody = "name=" + enc(SERVER_NAME) + "&host=" + enc(SERVER_IP) + "&port=" + enc(SERVER_PORT);
        String url = baseUrl + path + "?" + postBody;

        // Run registration async to avoid blocking the server thread
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            // Send parameters via query string; no POST body
            boolean ok = httpPostWithRetry(url, null, PROXY_KEY, 2, Duration.ofSeconds(5));
            if (ok) {
                getLogger().info(prefix + "Registered server '" + SERVER_NAME + "' with proxy (fallback=" + isFallback + ").");
            } else {
                getLogger().severe(prefix + "Failed to register server with proxy after retries.");
            }
        });
    }

    @Override
    public void onDisable() {
        // Attempt to unregister on shutdown
        if (!envValid) return;
        String baseUrl = "http://" + PROXY_IP + ":" + PROXY_PORT;
        String postBody = "name=" + enc(Objects.toString(SERVER_NAME, ""));
        String url = baseUrl + "/api/unregister?" + postBody;
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            // Send parameters via query string; no POST body
            boolean ok = httpPostWithRetry(url, null, PROXY_KEY, 1, Duration.ofSeconds(3));
            if (ok) {
                getLogger().info(prefix + "Unregistered server '" + SERVER_NAME + "' from proxy.");
            } else {
                getLogger().log(Level.WARNING, prefix + "Failed to unregister server from proxy.");
            }
        });
    }

    private boolean validateEnv() {
        PROXY_IP = trimToNull(PROXY_IP);
        PROXY_PORT = trimToNull(PROXY_PORT);
        SERVER_NAME = trimToNull(SERVER_NAME);
        SERVER_IP = trimToNull(SERVER_IP);
        SERVER_PORT = trimToNull(SERVER_PORT);
        // PROXY_KEY optional, SERVER_TYPE_FALLBACK optional

        StringBuilder sb = new StringBuilder();
        if (PROXY_IP == null) sb.append(" PROXY_IP");
        if (PROXY_PORT == null) sb.append(" PROXY_PORT");
        if (SERVER_NAME == null) sb.append(" SERVER_NAME");
        if (SERVER_IP == null) sb.append(" SERVER_IP");
        if (SERVER_PORT == null) sb.append(" SERVER_PORT");
        if (sb.length() > 0) {
            getLogger().severe(prefix + "Missing required env vars:" + sb);
            return false;
        }
        // Basic numeric validation for ports
        try { Integer.parseInt(PROXY_PORT); } catch (NumberFormatException e) { getLogger().severe(prefix + "Invalid PROXY_PORT: " + PROXY_PORT); return false; }
        try { Integer.parseInt(SERVER_PORT); } catch (NumberFormatException e) { getLogger().severe(prefix + "Invalid SERVER_PORT: " + SERVER_PORT); return false; }
        return true;
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static boolean parseBoolean(String s, boolean def) {
        if (s == null) return def;
        String v = s.trim().toLowerCase();
        if (v.equals("true") || v.equals("1") || v.equals("yes") || v.equals("y")) return true;
        if (v.equals("false") || v.equals("0") || v.equals("no") || v.equals("n")) return false;
        return def;
    }

    private static String enc(String v) {
        return URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8);
    }

    private boolean httpPostWithRetry(String url, String body, String proxyKey, int retries, Duration timeout) {
        int attempts = Math.max(1, retries);
        for (int i = 1; i <= attempts; i++) {
            try {
                if (httpPost(url, body, proxyKey, timeout)) return true;
            } catch (Exception e) {
                getLogger().log(Level.WARNING, prefix + "HTTP attempt " + i + " failed: " + e.getMessage());
            }
            try { Thread.sleep(300L); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        return false;
    }

    private boolean httpPost(String urlStr, String body, String proxyKey, Duration timeout) throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout((int) Math.min(Integer.MAX_VALUE, timeout.toMillis()));
        conn.setReadTimeout((int) Math.min(Integer.MAX_VALUE, timeout.toMillis()));
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        // Use standard Bearer auth if a proxy key is present
        if (proxyKey != null) {
            String token = proxyKey.trim();
            if (!token.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + token);
            }
        }
        byte[] out = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        if (out.length > 0) {
            conn.getOutputStream().write(out);
            conn.getOutputStream().flush();
            conn.getOutputStream().close();
        }
        int code = conn.getResponseCode();
        String respBody;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                code >= 200 && code < 400 ? conn.getInputStream() : conn.getErrorStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while (reader != null && (line = reader.readLine()) != null) {
                sb.append(line);
            }
            respBody = sb.toString();
        }
        if (code >= 200 && code < 300) {
            getLogger().fine(prefix + "HTTP OK " + code + ": " + respBody);
            return true;
        } else {
            getLogger().warning(prefix + "HTTP " + code + ": " + respBody);
            return false;
        }
    }
}
