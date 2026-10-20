const fs=require('fs'),assert=require('node:assert/strict'),{chromium}=require('playwright');
(async()=>{const b=await chromium.launch({executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true});try{
for(const [width,height] of [[224,472],[393,851],[640,360],[851,393]]){
const p=await b.newPage({viewport:{width,height}});await p.addInitScript(()=>{localStorage.setItem('bili.cfg',JSON.stringify({uids:['67368617'],oled:true,theme:'oled',animations:false}));window.BiliScreen={pulse(){},keepScreen(){},settings(){},oled(){}};});
await p.route('**/*',r=>r.fulfill({contentType:r.request().url().endsWith('index.html')?'text/html':'application/json',body:r.request().url().endsWith('index.html')?fs.readFileSync('index.html','utf8'):JSON.stringify({code:0,data:{follower:12345678,card:{name:'测试账号',sign:'简介',face:''}}})}));
await p.goto('https://appassets.androidplatform.net/index.html');await p.waitForFunction(()=>document.querySelector('#count').textContent.includes('12,345,678'));
for(let phase=0;phase<4;phase++){
await p.evaluate(n=>{oledLayoutPhase=n;document.body.dataset.oledLayout=String(n);dispatchEvent(new Event('resize'));},phase);await p.waitForTimeout(120);
const r=await p.evaluate(()=>{const box=s=>{const r=document.querySelector(s).getBoundingClientRect();return {x:r.x,y:r.y,right:r.right,bottom:r.bottom}};return {count:box('.count-wrap'),chart:box('.chartbox'),header:box('.topbar'),main:box('.main'),overflow:document.documentElement.scrollWidth>innerWidth}});
assert.equal(r.overflow,false);const a=r.count,c=r.chart;assert.ok(!(a.x<c.right-1&&a.right>c.x+1&&a.y<c.bottom-1&&a.bottom>c.y+1),JSON.stringify({width,height,phase,r}));
if(width>height)assert.equal(a.x<c.x,phase===1||phase===2);else assert.equal(a.y>c.y,phase===1||phase===2);
if(phase===1)await p.screenshot({path:`dist/release-check/oled-swap-${width}.png`});
}
await p.evaluate(()=>{oledLayoutElapsed=59000;oledLayoutLast=performance.now()-1500;updateOledLayout();});assert.equal(await p.getAttribute('body','data-oled-layout'),'0');
await p.click('#btn-settings');await p.evaluate(()=>{oledLayoutElapsed=59000;oledLayoutLast=performance.now()-1500;updateOledLayout();});assert.equal(await p.getAttribute('body','data-oled-layout'),'0');
await p.click('#t-oled');assert.equal(await p.getAttribute('body','data-oled-layout'),null);await p.close();console.log('PASS OLED phases / pause / reset',width,height);
}
}finally{await b.close()}})().catch(e=>{console.error(e);process.exit(1)});
