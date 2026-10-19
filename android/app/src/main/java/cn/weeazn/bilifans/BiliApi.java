package cn.weeazn.bilifans;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import org.json.*;
import java.io.*;
import java.net.*;
import java.util.*;

/** Only fixed Bilibili endpoints are reachable through the local page origin. */
final class BiliApi {
    private static final String BASE = "https://api.bilibili.com";
    private final SharedPreferences disk;
    private final Map<String, JSONObject> cache = new HashMap<>();
    private final Map<String, Long> times = new HashMap<>();
    BiliApi(Context context) {
        disk = context.getSharedPreferences("bili-api", 0);
        // Retire persisted video data and cooldown state from older versions.
        SharedPreferences.Editor edit = disk.edit();
        for (String key : disk.getAll().keySet()) if (key.startsWith("video")) edit.remove(key);
        edit.apply();
    }

    String route(Uri uri) throws Exception {
        String uid = uri.getQueryParameter("uid");
        if (uid == null || !uid.matches("[0-9]{1,20}")) throw new IOException("UID 不合法");
        switch (uri.getPath()) {
            case "/api/stat": return cached("stat:" + uid, "/x/relation/stat?vmid=" + uid, 10000).toString();
            case "/api/card": return cached("card:" + uid, "/x/web-interface/card?mid=" + uid + "&photo=true", 30 * 60 * 1000L).toString();
            default: throw new IOException("未知接口");
        }
    }
    private synchronized JSONObject cached(String key, String path, long ttl) throws Exception {
        if (cache.containsKey(key) && System.currentTimeMillis() - times.get(key) < ttl) return cache.get(key);
        JSONObject result = request(path);
        requireSuccess(result);
        cache.put(key, result); times.put(key, System.currentTimeMillis());
        return result;
    }
    private JSONObject request(String path) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(BASE + path).openConnection();
        connection.setConnectTimeout(5000); connection.setReadTimeout(5000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36");
        connection.setRequestProperty("Referer", "https://space.bilibili.com/");
        connection.setRequestProperty("Accept", "application/json");
        try {
            int status = connection.getResponseCode();
            if (status != 200) throw new IOException("B站 HTTP " + status);
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                    if (output.size() > 4 * 1024 * 1024) throw new IOException("响应过大");
                }
                return new JSONObject(output.toString("UTF-8"));
            }
        } finally { connection.disconnect(); }
    }
    private void requireSuccess(JSONObject json) throws IOException {
        if (json.optInt("code", -1) != 0)
            throw new IOException("B站 " + json.optInt("code") + "：" + json.optString("message"));
    }
}
