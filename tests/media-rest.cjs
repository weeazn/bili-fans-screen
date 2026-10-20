const fs=require('fs'),assert=require('node:assert/strict'),{chromium}=require('playwright');
(async()=>{const browser=await chromium.launch({executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true});try{
 const page=await browser.newPage({viewport:{width:393,height:851}}),errors=[],api=[];
 page.on('pageerror',e=>errors.push(e.message));
 await page.addInitScript(()=>{
  window.media=JSON.parse(localStorage.getItem('test.media')||'{"enabled":false,"interval":600,"imageSeconds":30,"hasMedia":false,"kind":"","bytes":0}');window.calls=[];
  window.BiliScreen={pulse(){},keepScreen(){},settings(){},oled(){},
   mediaStatus(){return JSON.stringify(media)},mediaConfigure(enabled,interval,imageSeconds,animate){Object.assign(media,{enabled,interval,imageSeconds});calls.push(['configure',enabled,interval,imageSeconds,animate]);localStorage.setItem('test.media',JSON.stringify(media));window.dispatchEvent(new Event('native-media-change'))},
   mediaGallery(){calls.push(['gallery'])},mediaMode(value){media.mode=value;localStorage.setItem('test.media',JSON.stringify(media));dispatchEvent(new Event('native-media-change'))},mediaPick(){calls.push(['pick'])},mediaPreview(){calls.push(['preview'])},mediaClear(){calls.push(['clear'])}};
  localStorage.setItem('bili.cfg',JSON.stringify({uids:['67368617'],oled:true,theme:'oled'}));
 });
 await page.route('**/*',r=>{const u=new URL(r.request().url());if(u.pathname==='/index.html')return r.fulfill({contentType:'text/html',body:fs.readFileSync('index.html','utf8')});api.push(u.pathname);return r.fulfill({contentType:'application/json',body:JSON.stringify({code:0,data:{follower:100,card:{name:'测试',face:'',sign:''}}})})});
 await page.goto('https://appassets.androidplatform.net/index.html');await page.waitForFunction(()=>document.querySelector('#count').textContent.includes('100'));
 assert.equal(await page.locator('#videos').count(),0);assert.equal(await page.locator('#t-videos').count(),0);assert.ok(!api.includes('/api/videos'));
 await page.click('#btn-settings');await page.locator('#media-rest-settings').scrollIntoViewIfNeeded();assert.equal(await page.inputValue('#f-media-image-seconds'),'30');
 await page.fill('#f-media-interval','359');await page.click('#btn-media-save');assert.match(await page.textContent('#media-rest-error'),/360/);
 await page.fill('#f-media-interval','7201');await page.click('#btn-media-save');assert.equal(await page.evaluate(()=>media.interval),600);
 await page.fill('#f-media-interval','360');await page.click('#t-media-rest');assert.equal(await page.evaluate(()=>media.enabled),true);assert.equal(await page.evaluate(()=>media.interval),360);
 await page.click('#btn-media-pick');assert.ok(await page.evaluate(()=>calls.some(c=>c[0]==='pick')));
 assert.equal(await page.locator('#btn-media-preview').isDisabled(),true);
 await page.evaluate(()=>{Object.assign(media,{hasMedia:true,kind:'video',bytes:1000000,duration:60000});dispatchEvent(new Event('native-media-change'))});
 await page.click('#btn-media-preview');assert.ok(await page.evaluate(()=>calls.some(c=>c[0]==='preview')));
 assert.equal(await page.locator('#f-media-mode').isDisabled(),true);
 await page.evaluate(()=>{Object.assign(media,{kind:'image',count:10});dispatchEvent(new Event('native-media-change'))});
 await page.selectOption('#f-media-mode','random');assert.equal(await page.evaluate(()=>media.mode),'random');
 await page.click('#btn-media-gallery');assert.ok(await page.evaluate(()=>calls.some(c=>c[0]==='gallery')));
 await page.click('#btn-media-clear');assert.ok(await page.evaluate(()=>calls.some(c=>c[0]==='clear')));
 await page.fill('#f-media-interval','7200');await page.fill('#f-media-image-seconds','45');await page.click('#btn-media-save');await page.reload();await page.click('#btn-settings');assert.equal(await page.inputValue('#f-media-interval'),'7200');assert.equal(await page.inputValue('#f-media-image-seconds'),'45');
 assert.equal(await page.inputValue('#f-media-mode'),'random');await page.setViewportSize({width:851,height:393});
 await page.locator('#media-rest-settings').scrollIntoViewIfNeeded();await page.screenshot({path:'dist/release-check/media-rest-settings.png'});
 assert.deepEqual(errors,[]);console.log('PASS latest-video removal / rest settings validation / native bridge / persistence');
}finally{await browser.close()}})().catch(e=>{console.error(e);process.exit(1)});
