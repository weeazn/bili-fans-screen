const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const { chromium } = require('playwright');
(async () => {
  const browser = await chromium.launch({ executablePath: 'C:/Program Files/Google/Chrome/Application/chrome.exe', headless: true });
  const html = fs.readFileSync(path.join(__dirname, '../index.html'), 'utf8');
  const output = path.join(__dirname, '../dist/layout-check');
  fs.mkdirSync(output, { recursive: true });
  for (const [width, height] of [[224,472],[320,568],[360,640],[393,851],[412,915],[640,360],[851,393]]) {
    const context = await browser.newContext({ viewport: { width, height }, deviceScaleFactor: 1 });
    await context.addInitScript(() => { window.BiliScreen = { pulse() {}, keepScreen() {}, settings() {} }; });
    const page = await context.newPage();
    await page.route('**/*', route => {
      const url = new URL(route.request().url());
      if (url.pathname === '/index.html') return route.fulfill({ contentType:'text/html',body:html });
      let data = { code: 0, data: {} };
      if (url.pathname === '/api/stat') data.data = { follower: 12345678, following: 123 };
      else if (url.pathname === '/api/card') data.data.card = { name: '很长的昵称测试手机排版', sign:'长签名也不能挡住旁边的按钮和文字', face:'' };
      else if (url.pathname === '/api/videos') data.data = { at:Date.now(), videos:[1,2,3].map(i=>({title:`第${i}个视频：这是一个很长的标题，用于检查窄屏省略与播放量布局`,play:12345678})) };
      return route.fulfill({ contentType:'application/json',body:JSON.stringify(data) });
    });
    await page.goto('https://appassets.androidplatform.net/index.html');
    await page.click('#guide-next');
    await page.fill('#guide-uid', '123456');
    await page.click('#guide-next');
    await page.click('#guide-next');
    await page.waitForFunction(() => document.getElementById('count').textContent.includes('12,345,678'));
    await page.waitForTimeout(400);
    const issues = await page.evaluate(() => {
      const issues=[];
      const rect = s=>document.querySelector(s).getBoundingClientRect();
      const overlaps = (a,b)=>a.left < b.right-1 && a.right > b.left+1 && a.top < b.bottom-1 && a.bottom > b.top+1;
      for(const [a,b] of [['.brand','#btn-refresh'],['#chip-status','#btn-refresh'],['.chartbox .cap','.range'],['.count','.delta']])
        if(getComputedStyle(document.querySelector(a)).display !== 'none')
        if(overlaps(rect(a),rect(b))) issues.push(a+' overlaps '+b);
      for(const selector of ['#btn-refresh','#btn-theme','#btn-settings','.count-inner','.range']) {
        const r=rect(selector); if(r.left < -1 || r.right > innerWidth+1) issues.push(selector+' outside screen');
      }
      if(document.documentElement.scrollWidth > innerWidth) issues.push('horizontal overflow');
      const chart=document.querySelector('.chartbox');
      if(chart.scrollHeight > chart.clientHeight+2) issues.push('chart contents clipped');
      return issues;
    });
    await page.screenshot({ path:path.join(output,`${width}x${height}.png`) });
    assert.deepEqual(issues, [], `${width}x${height}`);
    await page.click('#btn-settings');
    await page.locator('#btn-save').scrollIntoViewIfNeeded();
    const settingsIssues = await page.evaluate(() => {
      const selectors=['#btn-close','#btn-save','#btn-demo-once','.native-controls'];
      const rects=selectors.map(s=>document.querySelector(s).getBoundingClientRect());
      return rects.flatMap((a,i)=>rects.slice(i+1).map((b,j)=>
        a.left < b.right-1 && a.right > b.left+1 && a.top < b.bottom-1 && a.bottom > b.top+1
        ? selectors[i]+' overlaps '+selectors[i+j+1] : '').filter(Boolean));
    });
    await page.screenshot({path:path.join(output,`${width}x${height}-settings.png`)});
    assert.deepEqual(settingsIssues, [], 'settings '+width+'x'+height);
    console.log(`PASS ${width}x${height}: dashboard and settings`);
    await context.close();
  }
  await browser.close();
})().catch(error => { console.error(error); process.exit(1); });
