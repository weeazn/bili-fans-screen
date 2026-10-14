const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const { chromium } = require('playwright');
(async () => {
  const browser = await chromium.launch({ executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe', headless:true });
  const page = await browser.newPage({viewport:{width:393,height:851}});
  const errors = [], apiRequests = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.addInitScript(() => {
    window.bridgeCalls = [];
    window.BiliScreen = {
      pulse() {}, keepScreen(value) { bridgeCalls.push(['wake',value]); }, settings() {},
      palette() { return JSON.stringify({supported:true,light:'#6750a4',dark:'#d0bcff'}); },
      accent(value) { bridgeCalls.push(['accent',value]); },
      display(...values) { bridgeCalls.push(['display',...values]); },
      author() { bridgeCalls.push(['author']); }, systemSettings(section) { bridgeCalls.push(['settings',section]); }
    };
  });
  const html = fs.readFileSync(path.join(__dirname,'../index.html'),'utf8');
  await page.route('**/*', route => {
    const url = new URL(route.request().url());
    if(url.pathname === '/index.html') return route.fulfill({contentType:'text/html',body:html});
    apiRequests.push(url.href);
    let data = {};
    if(url.pathname === '/api/stat') data = {follower:456789,following:42};
    if(url.pathname === '/api/card') data = {card:{name:'测试账号',sign:'这是填写 UID 后显示的账号',face:''}};
    if(url.pathname === '/api/videos') data = {at:Date.now(),videos:[]};
    return route.fulfill({contentType:'application/json',body:JSON.stringify({code:0,data})});
  });
  const output = path.join(__dirname,'../dist/release-check');fs.mkdirSync(output,{recursive:true});
  await page.goto('https://appassets.androidplatform.net/index.html');
  await page.waitForSelector('#welcome');
  await page.waitForTimeout(800);
  await page.screenshot({path:path.join(output,'welcome.png')});
  await page.click('#guide-next');
  assert.equal(await page.inputValue('#guide-uid'),'');
  await page.click('#guide-next');
  assert.ok((await page.textContent('#guide-error')).includes('有效'));
  await page.fill('#guide-uid','123abc'); await page.click('#guide-next');
  assert.equal(apiRequests.length,0,'No account requests before valid UID confirmation');
  await page.fill('#guide-uid','123456'); await page.click('#guide-next');
  await page.waitForTimeout(800);
  await page.screenshot({path:path.join(output,'guide-keep-awake.png')});
  await page.click('#guide-next');
  await page.waitForFunction(()=>document.querySelector('#count').textContent.includes('456,789'));
  assert.ok(apiRequests.every(url=>url.includes('uid=123456')));
  await page.click('#btn-settings');
  await page.click('#t-theme');
  assert.equal(await page.getAttribute('html','data-theme'),'oled');
  assert.equal(await page.getAttribute('#t-theme','aria-checked'),'true');
  await page.click('#t-theme');
  assert.equal(await page.getAttribute('html','data-theme'),'dark');
  await page.click('#t-oled');
  assert.equal(await page.getAttribute('#t-theme','aria-checked'),'true');
  await page.click('#t-theme');
  assert.equal(await page.getAttribute('#t-oled','aria-checked'),'false');
  await page.selectOption('#f-color-mode','monet');
  assert.equal(await page.evaluate(()=>getComputedStyle(document.documentElement).getPropertyValue('--accent').trim()),'#d0bcff');
  await page.selectOption('#f-color-mode','custom');
  await page.locator('#f-accent').evaluate(el=>{el.value='#000000';el.dispatchEvent(new Event('input',{bubbles:true}));});
  assert.equal(await page.evaluate(()=>getComputedStyle(document.querySelector('#btn-save')).color),'rgb(255, 255, 255)');
  await page.locator('#f-accent').evaluate(el=>{el.value='#ffffff';el.dispatchEvent(new Event('input',{bubbles:true}));});
  assert.equal(await page.evaluate(()=>getComputedStyle(document.querySelector('#btn-save')).color),'rgb(17, 17, 17)');
  await page.locator('#f-accent').evaluate(el=>{el.value='#37a46b';el.dispatchEvent(new Event('input',{bubbles:true}));});
  await page.selectOption('#f-orientation','landscape');
  assert.ok(await page.evaluate(()=>bridgeCalls.some(c=>c[0]==='display'&&c[1]==='landscape')));
  await page.setViewportSize({width:851,height:393});
  await page.waitForFunction(()=>document.body.dataset.ori==='landscape');
  await page.evaluate(()=>window.dispatchEvent(new Event('native-display-change')));
  assert.ok(await page.locator('.stage').evaluate(el=>el.classList.contains('orientation-enter')));
  await page.locator('#f-safe-margin').evaluate(el=>{el.value='24';el.dispatchEvent(new Event('input',{bubbles:true}));});
  assert.ok(await page.evaluate(()=>bridgeCalls.some(c=>c[0]==='display'&&c[2]===true&&c[3]===24)));
  await page.click('#t-cutout');
  assert.equal(await page.getAttribute('#t-cutout','aria-checked'),'false');
  assert.ok(await page.evaluate(()=>bridgeCalls.some(c=>c[0]==='display'&&c[2]===false)));
  await page.click('#author-home');
  assert.ok(await page.evaluate(()=>bridgeCalls.some(c=>c[0]==='author')));
  await page.getByRole('button',{name:'打开系统显示设置',exact:true}).click();
  assert.ok(await page.evaluate(()=>bridgeCalls.some(c=>c[0]==='settings'&&c[1]==='display')));
  await page.reload();
  await page.waitForFunction(()=>document.querySelector('#count').textContent.includes('456,789'));
  assert.equal(await page.locator('#welcome').count(),0,'Onboarding does not repeat');
  const saved = await page.evaluate(()=>JSON.parse(localStorage.getItem('bili.cfg')));
  assert.deepEqual(saved.uids,['123456']);assert.equal(saved.accent,'#37a46b');assert.equal(saved.orientation,'landscape');assert.equal(saved.avoidCutout,false);assert.equal(saved.safeMargin,24);
  await page.click('#btn-settings');
  await page.selectOption('#f-color-mode','monet');
  await page.evaluate(()=>{BiliScreen.palette=()=>'{"supported":false}';window.dispatchEvent(new Event('native-palette-change'));});
  assert.ok((await page.textContent('#color-hint')).includes('回退'));
  await page.selectOption('#f-orientation','portrait'); await page.setViewportSize({width:393,height:851});
  await page.locator('#f-color-mode').scrollIntoViewIfNeeded();
  await page.waitForTimeout(800);
  await page.screenshot({path:path.join(output,'settings-appearance.png')});
  assert.deepEqual(errors,[]);
  console.log('PASS onboarding / no default UID / OLED switches / Monet & fallback / custom contrast / orientation bridge & animation / cutout controls / author link / help links / persistence');
  await browser.close();
})().catch(error=>{console.error(error);process.exit(1);});
