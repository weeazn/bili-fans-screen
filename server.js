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

const PORT = Number(process.argv[2]) || 8787;
const ROOT = __dirname;

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
