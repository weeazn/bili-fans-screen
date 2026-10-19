const fs=require('fs'),assert=require('node:assert/strict'),{chromium}=require('playwright');
(async()=>{
 const browser=await chromium.launch({executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true});
 try {
 const page=await browser.newPage({viewport:{width:393,height:851}}),errors=[];
 page.on('pageerror',e=>errors.push(e.message));
 await page.addInitScript(()=>{
  window.oledCalls=[];
  window.BiliScreen={pulse(){},keepScreen(){},settings(){},oled(...args){oledCalls.push(args)}};
  if(!localStorage.getItem('bili.cfg')) localStorage.setItem('bili.cfg',JSON.stringify({uids:['123'],demo:true,oled:true,theme:'oled',dim:.15}));
 });
 await page.route('**/*',r=>r.fulfill({contentType:'text/html',body:fs.readFileSync('index.html','utf8')}));
 await page.goto('https://appassets.androidplatform.net/index.html');
 const last=()=>page.evaluate(()=>oledCalls.at(-1));
 assert.deepEqual(await last(),[true,.15,45,false]);
 assert.equal(await page.evaluate(()=>pixelShiftTimer),null);
 assert.equal(await page.locator('.stage').evaluate(e=>e.style.filter),'');
 assert.equal(await page.locator('.stage').evaluate(e=>e.style.transform),'');
 await page.click('#btn-settings');await page.waitForFunction(()=>oledCalls.at(-1)[3]);
 await page.click('#btn-profile');await page.evaluate(()=>handleAppBack());assert.equal((await last())[3],true);
 await page.evaluate(()=>handleAppBack());await page.waitForFunction(()=>!oledCalls.at(-1)[3]);
 await page.evaluate(()=>showGuide(true));await page.waitForFunction(()=>oledCalls.at(-1)[3]);
 await page.click('#guide-cancel');await page.waitForFunction(()=>!oledCalls.at(-1)[3]);
 await page.click('#btn-settings');await page.click('#t-oled');assert.equal((await last())[0],false);
 await page.click('#t-oled');assert.equal((await last())[0],true);
 await page.evaluate(()=>handleAppBack());
 await page.setViewportSize({width:851,height:393});await page.waitForTimeout(650);
 assert.equal(await page.locator('.stage').evaluate(e=>e.style.transform),'');
 const calls=await page.evaluate(()=>oledCalls.length);await page.waitForTimeout(1100);
 assert.equal(await page.evaluate(()=>oledCalls.length),calls,'no repeated bridge calls on demo ticks');
 await page.reload();assert.deepEqual(await last(),[true,.15,45,false]);
 assert.deepEqual(errors,[]);
 console.log('PASS native OLED bridge / no CSS double dim or shift / panels pause / reload / rotation');
 } finally {await browser.close()}
})().catch(e=>{console.error(e);process.exit(1)});
