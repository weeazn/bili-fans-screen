package cn.weeazn.bilifans;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Only fixed Bilibili endpoints are reachable through the local page origin. */
final class BiliApi {
    private static final String BASE = "https://api.bilibili.com";
    private static final long VIDEO_TTL = 30 * 60 * 1000L;
    private final SharedPreferences disk;
    private final Map<String, JSONObject> cache = new HashMap<>();
    private final Map<String, Long> times = new HashMap<>();
    private String mixin;
    private long keysAt;
    BiliApi(Context context) { disk = context.getSharedPreferences("bili-api", 0); }

    String route(Uri uri) throws Exception {
        String uid = uri.getQueryParameter("uid");
        if (uid == null || !uid.matches("[0-9]{1,20}")) throw new IOException("UID 不合法");
        switch (uri.getPath()) {
            case "/api/stat": return cached("stat:" + uid, "/x/relation/stat?vmid=" + uid, 10000).toString();
            case "/api/card": return cached("card:" + uid, "/x/web-interface/card?mid=" + uid + "&photo=true", 30 * 60 * 1000L).toString();
            case "/api/videos": return videos(uid).toString();
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
    private JSONObject videoResult(JSONObject data, boolean stale) throws JSONException {
        data.put("stale", stale);
        return new JSONObject().put("code", 0).put("data", data);
    }
    private JSONObject videos(String uid) throws Exception {
        long now = System.currentTimeMillis();
        String key = "videos:" + uid;
        JSONObject saved = new JSONObject(disk.getString(key, "{}"));
        if (now - saved.optLong("at") < VIDEO_TTL) return videoResult(saved, false);
        if (now < disk.getLong("videoRetryAt", 0)) return videoResult(saved, true);
        try {
            TreeMap<String, String> params = new TreeMap<>();
            params.put("host_mid", uid); params.put("offset", ""); params.put("timezone", "8");
            params.put("platform", "web"); params.put("web_location", "333.1387");
            params.put("features", "itemOpusStyle,listOnlyfans,opusBigCover,onlyfansVote");
            JSONObject raw = request("/x/polymer/web-dynamic/v1/feed/space?" + sign(params));
            requireSuccess(raw);
            JSONArray items = raw.getJSONObject("data").optJSONArray("items");
            List<JSONObject> list = new ArrayList<>(); Set<String> seen = new HashSet<>();
            for (int i = 0; items != null && i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                if (!"DYNAMIC_TYPE_AV".equals(item.optString("type"))) continue;
                JSONObject modules = item.optJSONObject("modules");
                if (modules == null) continue;
                JSONObject dynamic = modules.optJSONObject("module_dynamic");
                JSONObject major = dynamic == null ? null : dynamic.optJSONObject("major");
                JSONObject archive = major == null ? null : major.optJSONObject("archive");
                if (archive == null || !seen.add(archive.optString("bvid"))) continue;
                JSONObject stat = archive.optJSONObject("stat");
                JSONObject author = modules.optJSONObject("module_author");
                String play = stat == null ? "0" : stat.optString("play", "0");
                double multiplier = play.contains("亿") ? 100000000 : play.contains("万") ? 10000 : 1;
                double plays = 0;
                try { plays = Double.parseDouble(play.replace("万", "").replace("亿", "")) * multiplier; } catch (NumberFormatException ignored) {}
                list.add(new JSONObject().put("title", archive.optString("title"))
                    .put("bvid", archive.optString("bvid")).put("play", (long) plays)
                    .put("pubTs", author == null ? 0 : author.optLong("pub_ts")));
            }
            Collections.sort(list, (a, b) -> Long.compare(b.optLong("pubTs"), a.optLong("pubTs")));
            JSONArray videos = new JSONArray();
            for (int i = 0; i < Math.min(3, list.size()); i++) videos.put(list.get(i));
            JSONObject data = new JSONObject().put("uid", uid).put("at", now).put("videos", videos);
            disk.edit().putString(key, data.toString()).putInt("videoFailures", 0).putLong("videoRetryAt", 0).apply();
            return videoResult(data, false);
        } catch (Exception e) {
            int failures = Math.min(disk.getInt("videoFailures", 0) + 1, 4);
            long wait = Math.min(2 * 3600 * 1000L, 15 * 60 * 1000L * (1L << (failures - 1)));
            disk.edit().putInt("videoFailures", failures).putLong("videoRetryAt", now + wait).apply();
            if (saved.has("videos")) return videoResult(saved, true);
            throw e;
        }
    }
    private String sign(TreeMap<String, String> params) throws Exception {
        if (mixin == null || System.currentTimeMillis() - keysAt > VIDEO_TTL) {
            JSONObject wbi = request("/x/web-interface/nav").getJSONObject("data").getJSONObject("wbi_img");
            String source = key(wbi.getString("img_url")) + key(wbi.getString("sub_url"));
            int[] order = {46,47,18,2,53,8,23,32,15,50,10,31,58,3,45,35,27,43,5,49,33,9,42,19,29,28,14,39,12,38,41,13};
            StringBuilder mixed = new StringBuilder();
            for (int index : order) mixed.append(source.charAt(index));
            mixin = mixed.toString(); keysAt = System.currentTimeMillis();
        }
        params.put("wts", String.valueOf(System.currentTimeMillis() / 1000));
        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (query.length() > 0) query.append('&');
            query.append(Uri.encode(entry.getKey())).append('=').append(Uri.encode(entry.getValue().replaceAll("[!'()*]", "")));
        }
        byte[] digest = MessageDigest.getInstance("MD5").digest((query.toString() + mixin).getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(); for (byte value : digest) hex.append(String.format(Locale.US, "%02x", value & 255));
        return query + "&w_rid=" + hex;
    }
    private String key(String url) { String file = url.substring(url.lastIndexOf('/') + 1); return file.substring(0, file.indexOf('.')); }
}
