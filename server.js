#!/usr/bin/env node
/**
 * B站粉丝数看板 —— 可选本地服务（零依赖，Node 18+）
 *
 * 作用：
 *   1. 把 index.html 直接托管出来，手机浏览器访问 http://<电脑IP>:8787 即可打开看板
 *   2. 提供 /api/stat 与 /api/card，服务端去请求 B 站，天然没有跨域问题
 *
 * 用法：
 *   node server.js              # 默认端口 8787
 *   node server.js 9000         # 指定端口
 *
 * 手机端设置：在 index.html 的 ⚙ 面板里把「自建代理地址」填成
 *   http://192.168.x.x:8787
 * （电脑防火墙可能需要在首次运行时允许 Node.js 的入站连接）
 */

const http = require("http");
const fs = require("fs");
const path = require("path");
const os = require("os");
const crypto = require("crypto");

const PORT = Number(process.argv[2]) || 8787;
const ROOT = __dirname;
const VIDEO_CACHE = path.join(ROOT, "cache-videos.json");   // 视频列表缓存（重启不丢）

/* ------------------------------ 工具 ------------------------------ */
const UA =
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
  "(KHTML, like Gecko) Chrome/125.0 Safari/537.36";

function lanIPs() {
  const out = [];
  for (const list of Object.values(os.networkInterfaces())) {
    for (const i of list || []) {
      if (i.family === "IPv4" && !i.internal) out.push(i.address);
    }
  }
  return out;
}

function sendJSON(res, obj, code = 200) {
  const body = JSON.stringify(obj);
  res.writeHead(code, {
    "content-type": "application/json; charset=utf-8",
    "access-control-allow-origin": "*",
    "cache-control": "no-store",
    "content-length": Buffer.byteLength(body)
  });
  res.end(body);
}

/* 请求 B 站（带浏览器头，避免 -412 风控） */
async function bili(url, timeout = 8000) {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), timeout);
  try {
    const r = await fetch(url, {
      signal: ctrl.signal,
      headers: {
        "user-agent": UA,
        referer: "https://space.bilibili.com/",
        accept: "application/json, text/plain, */*",
        "accept-language": "zh-CN,zh;q=0.9"
      }
    });
    if (!r.ok) throw new Error("上游 HTTP " + r.status);
    return await r.json();
  } finally {
    clearTimeout(timer);
  }
}

/* ------------------------------ 缓存 ------------------------------ */
const CACHE_MS = 8000; // 同一 UID 8 秒内复用，避免手机频繁刷新打到 B 站
const cache = new Map(); // uid -> { at, stat, card }
async function getUp(uid) {
  const hit = cache.get(uid);
  if (hit && Date.now() - hit.at < CACHE_MS) return hit;

  const [statRes, cardRes] = await Promise.allSettled([
    bili(`https://api.bilibili.com/x/relation/stat?vmid=${encodeURIComponent(uid)}`),
    bili(`https://api.bilibili.com/x/web-interface/card?mid=${encodeURIComponent(uid)}&photo=true`)
  ]);

  const stat = statRes.status === "fulfilled" ? statRes.value : null;
  const card = cardRes.status === "fulfilled" ? cardRes.value : null;

  if (!stat || stat.code !== 0) {
    const msg =
      (stat && (stat.message || "code=" + stat.code)) ||
      (statRes.status === "rejected" ? String(statRes.reason.message || statRes.reason) : "无响应");
    const err = new Error(msg);
    err.upstream = { stat, card, statErr: String(statRes.reason || ""), cardErr: String(cardRes.reason || "") };
    throw err;
  }

  const c = card && card.code === 0 && card.data && card.data.card ? card.data.card : null;
  const entry = {
    at: Date.now(),
    stat,
    card,
    up: {
      uid: String(uid),
      follower: Number(stat.data.follower) || 0,
      following: Number(stat.data.following) || 0,
      name: c ? c.name || "" : "",
      face: c ? c.face || "" : "",
      sign: c ? c.sign || "" : ""
    }
  };
  cache.set(uid, entry);
  return entry;
}

/* =========================================================================
   最新视频列表（标题 + 播放量）
   为什么这么绕：
     1. space 系列的 arc/search 接口风控极严，实测本机 IP 一请求就 -412 封禁
     2. 可用的替代是「动态」接口，但它要求 WBI 签名（B站 2023 年加的鉴权）
     3. 限流是按 IP 的，所以这里做了多层保护：30 分钟缓存 + 磁盘持久化 +
        命中 412 就退回上次的好数据 + 指数退避，绝不硬刚接口
   ========================================================================= */
/* ---- WBI 签名 ---- */
const MIXIN_KEY_ENC_TAB = [
  46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
  33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40, 61,
  26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36,
  20, 34, 44, 52
];
const md5 = (s) => crypto.createHash("md5").update(s).digest("hex");
function mixinKey(imgKey, subKey) {
  const raw = imgKey + subKey;
  return MIXIN_KEY_ENC_TAB.map((i) => raw[i]).join("").slice(0, 32);
}
function wbiSign(params, imgKey, subKey) {
  const mk = mixinKey(imgKey, subKey);
  const all = { ...params, wts: Math.round(Date.now() / 1000) };
  const query = Object.keys(all).sort()
    .map((k) => encodeURIComponent(k) + "=" + encodeURIComponent(String(all[k]).replace(/[!'()*]/g, "")))
    .join("&");
  return query + "&w_rid=" + md5(query + mk);
}
let wbiCache = { at: 0, imgKey: "", subKey: "" };
async function wbiKeys(force) {
  if (!force && wbiCache.imgKey && Date.now() - wbiCache.at < 30 * 60 * 1000) return wbiCache;
  const r = await bili("https://api.bilibili.com/x/web-interface/nav");
  const wbi = r && r.data && r.data.wbi_img;
  if (!wbi) throw new Error("拿不到 WBI 密钥");
  wbiCache = {
    at: Date.now(),
    imgKey: wbi.img_url.split("/").pop().split(".")[0],
    subKey: wbi.sub_url.split("/").pop().split(".")[0]
  };
  return wbiCache;
}

/* ---- 视频缓存 ---- */
const VIDEO_TTL = 30 * 60 * 1000;      // 30 分钟内有缓存就直接给
let videoCache = { at: 0, uid: "", videos: [] };
let videoCacheLoaded = false;
let bannedUntil = 0;                   // 命中 412 后的退避截止时间
let banCount = 0;
/* 按需从磁盘读取（不要放在模块加载时：那时文件可能还不存在/刚被更新） */
function loadVideoCache() {
  if (videoCacheLoaded) return;
  videoCacheLoaded = true;
  try {
    let raw = fs.readFileSync(VIDEO_CACHE, "utf8");
    if (raw.charCodeAt(0) === 0xfeff) raw = raw.slice(1);   // 去掉 BOM（某些编辑器/Set-Content 会加）
    const saved = JSON.parse(raw);
    if (saved && Array.isArray(saved.videos) && saved.videos.length) {
      videoCache = saved;
      console.log(`  （已载入 ${saved.videos.length} 条视频缓存，采集于 ${new Date(saved.at).toLocaleString("zh-CN")}）`);
    } else {
      console.log("  （视频缓存文件存在但没有内容）");
    }
  } catch (e) {
    let listing = "";
    try { listing = fs.readdirSync(ROOT).join(" | "); } catch (e2) { listing = "目录也读不到: " + e2.message; }
    console.log("  （视频缓存读取失败：" + (e && e.code ? e.code : e && e.message) + "）");
    console.log("  （路径 " + VIDEO_CACHE + "）");
    console.log("  （同目录内容：" + listing + "）");
  }
}
function saveVideoCache() {
  try { fs.writeFileSync(VIDEO_CACHE, JSON.stringify(videoCache, null, 1), "utf8"); } catch (_) {}
}

/* ---- 取视频列表 ---- */
async function getVideos(uid, force) {
  loadVideoCache();
  const sameUid = videoCache.uid === uid;
  const fresh = sameUid && Date.now() - videoCache.at < VIDEO_TTL;
  console.log(`  [视频] uid=${uid} 缓存=${sameUid ? videoCache.videos.length + "条" : "无"} ` +
    `新鲜=${fresh} 退避剩余=${Math.max(0, Math.round((bannedUntil - Date.now()) / 1000))}s`);
  if (!force && fresh) return { videos: videoCache.videos, cached: true, at: videoCache.at };
  if (Date.now() < bannedUntil) {
    // 还在退避期：直接给旧数据，不碰接口（避免把封禁拖长）
    return { videos: sameUid ? videoCache.videos : [], cached: true, stale: true, at: videoCache.at, bannedFor: bannedUntil - Date.now() };
  }

  const keys = await wbiKeys();
  const q = wbiSign({
    host_mid: uid,
    offset: "",
    timezone: 8,
    platform: "web",
    features: "itemOpusStyle,listOnlyfans,opusBigCover,onlyfansVote",
    web_location: 333.1387
  }, keys.imgKey, keys.subKey);

  /* 注意：bili() 遇到非 2xx 是「抛异常」而不是返回 code，所以两种情况都要接住 */
  let raw = null, failCode = "";
  try {
    raw = await bili("https://api.bilibili.com/x/polymer/web-dynamic/v1/feed/space?" + q, 10000);
  } catch (e) {
    failCode = (e && e.message) || String(e);
  }
  if (!raw || raw.code !== 0) {
    const code = raw ? raw.code : failCode;
    if (/41[23]|352/.test(String(code))) {           // 被风控（-412 请求被拒绝 / -352 风控校验失败）
      banCount++;
      const wait = Math.min(2 * 3600 * 1000, 15 * 60 * 1000 * Math.pow(2, banCount - 1));  // 15min → 30 → 60 → 120
      bannedUntil = Date.now() + wait;
      console.log(`  ⚠ 视频接口被限流(${code})，${Math.round(wait / 60000)} 分钟内不再请求，先沿用旧数据`);
    } else {
      console.log(`  ⚠ 视频接口异常：${code}`);
    }
    if (sameUid && videoCache.videos.length) {
      return { videos: videoCache.videos, cached: true, stale: true, at: videoCache.at, error: String(code) };
    }
    throw new Error("视频接口返回 " + code);
  }
  banCount = 0;

  const items = (raw.data && raw.data.items) || [];
  const videos = items
    .filter((i) => i.type === "DYNAMIC_TYPE_AV" && i.modules && i.modules.module_dynamic &&
                   i.modules.module_dynamic.major && i.modules.module_dynamic.major.archive)
    .map((i) => {
      const a = i.modules.module_dynamic.major.archive;
      return {
        bvid: a.bvid || "",
        title: a.title || "",
        play: (a.stat && Number(a.stat.play)) || 0,
        pubTs: (i.modules.module_author && Number(i.modules.module_author.pub_ts)) || 0,
        url: a.bvid ? "https://www.bilibili.com/video/" + a.bvid : ""
      };
    })
    .sort((a, b) => b.pubTs - a.pubTs)
    .slice(0, 3);   // 显示的最新的 3 个

  videoCache = { at: Date.now(), uid: String(uid), videos };
  saveVideoCache();
  return { videos, cached: false, at: videoCache.at };
}

/* ------------------------------ 路由 ------------------------------ */
const MIME = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".png": "image/png",
  ".svg": "image/svg+xml"
};

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, "http://localhost");
  const p = url.pathname;

  /* 健康检查 */
  if (p === "/api/ping") {
    sendJSON(res, { ok: true, service: "bili-fans-screen", time: Date.now() });
    return;
  }

  /* 只取粉丝数（极简，前端 parseStat 直接吃） */
  if (p === "/api/stat") {
    const uid = (url.searchParams.get("uid") || url.searchParams.get("vmid") || "").trim();
    if (!/^\d+$/.test(uid)) { sendJSON(res, { code: -1, message: "uid 参数不合法" }, 400); return; }
    try {
      const { stat } = await getUp(uid);
      sendJSON(res, stat);
    } catch (e) {
      sendJSON(res, { code: -1, message: e.message, detail: e.upstream || null }, 502);
    }
    return;
  }

  /* 粉丝数 + 昵称 + 头像 */
  if (p === "/api/up") {
    const uid = (url.searchParams.get("uid") || "").trim();
    if (!/^\d+$/.test(uid)) { sendJSON(res, { code: -1, message: "uid 参数不合法" }, 400); return; }
    try {
      const { up } = await getUp(uid);
      sendJSON(res, { code: 0, data: { ...up, source: "本地代理" } });
    } catch (e) {
      sendJSON(res, { code: -1, message: e.message, detail: e.upstream || null }, 502);
    }
    return;
  }

  /* 最新 3 个视频（标题 + 播放量） */
  if (p === "/api/videos") {
    const uid = (url.searchParams.get("uid") || "").trim();
    if (!/^\d+$/.test(uid)) { sendJSON(res, { code: -1, message: "uid 参数不合法" }, 400); return; }
    try {
      const r = await getVideos(uid, url.searchParams.get("force") === "1");
      sendJSON(res, {
        code: 0,
        data: {
          uid,
          videos: r.videos,
          cached: !!r.cached,
          stale: !!r.stale,
          at: r.at,
          bannedFor: r.bannedFor || 0,
          error: r.error || null
        }
      });
    } catch (e) {
      sendJSON(res, { code: -1, message: e.message }, 502);
    }
    return;
  }

  /* 兼容前端 /api/card 调用 */
  if (p === "/api/card") {
    const uid = (url.searchParams.get("uid") || url.searchParams.get("mid") || "").trim();
    if (!/^\d+$/.test(uid)) { sendJSON(res, { code: -1, message: "uid 参数不合法" }, 400); return; }
    try {
      const { card } = await getUp(uid);
      sendJSON(res, card || { code: -1, message: "无 card 数据" });
    } catch (e) {
      sendJSON(res, { code: -1, message: e.message }, 502);
    }
    return;
  }

  /* 静态文件：默认 index.html */
  let file = p === "/" ? "/index.html" : p;
  const abs = path.join(ROOT, path.normalize(file).replace(/^(\.\.[/\\])+/, ""));
  if (!abs.startsWith(ROOT) || !fs.existsSync(abs) || fs.statSync(abs).isDirectory()) {
    res.writeHead(404, { "content-type": "text/plain; charset=utf-8" });
    res.end("404 — 没有这个文件");
    return;
  }
  const body = fs.readFileSync(abs);
  res.writeHead(200, {
    "content-type": MIME[path.extname(abs).toLowerCase()] || "application/octet-stream",
    "cache-control": "no-cache",
    "content-length": body.length
  });
  res.end(body);
});

server.listen(PORT, "0.0.0.0", () => {
  const ips = lanIPs();
  console.log("");
  console.log("  B站粉丝数看板 · 本地服务已启动");
  console.log("  ──────────────────────────────────────────");
  console.log("  本机打开：  http://127.0.0.1:" + PORT);
  for (const ip of ips) console.log("  手机打开：  http://" + ip + ":" + PORT);
  console.log("");
  console.log("  手机端「自建代理地址」可填： http://" + (ips[0] || "127.0.0.1") + ":" + PORT);
  console.log("  停止服务：Ctrl + C");
  console.log("");
});
