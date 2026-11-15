import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {chromium} from 'playwright-core';

const html=await readFile(new URL('../../app/src/main/assets/glass-nav/index.html',import.meta.url),'utf8');
test('bundled GlassNav selects native tabs, accepts native updates, and drags its lens',async()=>{
  const browser=await chromium.launch({executablePath:process.env.CHROMIUM_PATH||'/usr/bin/chromium',headless:true,args:['--no-sandbox']});
  try{
    const page=await browser.newPage({viewport:{width:390,height:104},deviceScaleFactor:1,hasTouch:true,isMobile:true});
    const network=[];page.on('request',r=>{if(/^https?:/.test(r.url()))network.push(r.url());});
    await page.addInitScript(()=>{window.events=[];window.HorizonNav={ready(){window.nativeReady=true;},select(index){window.events.push(index);}};});
    // setContent preserves the document, so install the stand-in native interface explicitly.
    await page.evaluate(()=>{window.events=[];window.HorizonNav={ready(){window.nativeReady=true;},select(index){window.events.push(index);}};});
    await page.setContent(html);
    await page.waitForFunction(()=>window.horizonNav&&window.nativeReady);
    await page.evaluate(()=>window.horizonNav.configure({index:0,labels:['Play','Packs','Settings'],reduced:false}));
    assert.equal(await page.getByRole('tab').count(),3);
    await page.getByRole('tab',{name:'Packs',exact:true}).click();
    await page.waitForFunction(()=>window.events.includes(1));
    assert.equal(await page.getByRole('tab',{name:'Packs',exact:true}).getAttribute('aria-selected'),'true');
    await page.evaluate(()=>{window.events=[];window.horizonNav.configure({index:2,labels:['Play','Packs','Settings'],reduced:false});});
    await page.waitForFunction(()=>document.querySelectorAll('[role=tab]')[2].getAttribute('aria-selected')==='true');
    assert.deepEqual(await page.evaluate(()=>window.events),[],'native synchronization must not emit another navigation event');
    await page.waitForTimeout(350);
    const from=await page.getByRole('tab',{name:'Settings',exact:true}).boundingBox();
    const to=await page.getByRole('tab',{name:'Play',exact:true}).boundingBox();
    await page.mouse.move(from.x+from.width/2,from.y+from.height/2);await page.mouse.down();
    await page.waitForFunction(()=>document.querySelector('.lg-track').hasAttribute('data-lifted'));
    await page.mouse.move(to.x+to.width/2,to.y+to.height/2,{steps:12});await page.mouse.up();
    await page.waitForFunction(()=>window.events.includes(0));
    // Real touch input: hold beyond Android's long-press delay, then drag before lifting.
    const cdp=await page.context().newCDPSession(page);
    await page.evaluate(()=>{window.events=[];window.touchCanceled=0;document.addEventListener('pointercancel',()=>window.touchCanceled++);});
    const touch=async(type,x,y)=>cdp.send('Input.dispatchTouchEvent',{type,touchPoints:type==='touchEnd'||type==='touchCancel'?[]:[{x,y,id:1,radiusX:4,radiusY:4,force:1}]});
    await touch('touchStart',to.x+to.width/2,to.y+to.height/2);
    await page.waitForTimeout(800);
    assert.equal(await page.locator('#root > .lg-track').evaluate(el=>el.hasAttribute('data-lifted')),true);
    const lens=page.locator('#root > .lg-track > .lg-track__lens');
    const startX=(await lens.boundingBox()).x;
    for(let i=1;i<=12;i++) await touch('touchMove',to.x+to.width/2+(from.x-to.x)*i/12,to.y+to.height/2);
    await page.waitForTimeout(200);
    assert.ok((await lens.boundingBox()).x>startX+100,'held lens follows the finger before release');
    await touch('touchEnd');
    await page.waitForFunction(()=>window.events.includes(2));
    assert.equal(await page.evaluate(()=>window.touchCanceled),0,'long press must not cancel touch');
    await touch('touchStart',from.x+from.width/2,from.y+from.height/2);
    await touch('touchMove',to.x+to.width/2,to.y+to.height/2);
    await touch('touchCancel');
    assert.deepEqual(await page.evaluate(()=>window.events),[2],'canceled drag must not select a different page');
    // An Activity interruption can lose pointerup entirely. A fresh down must recover.
    await page.evaluate(() => {
      window.events=[];
      document.querySelector('#root [data-lg-item="2"]').dispatchEvent(new PointerEvent('pointerdown', {
        bubbles:true,pointerId:777,isPrimary:true,pointerType:'touch',button:0,clientX:320,clientY:50,
      }));
    });
    await touch('touchStart',from.x+from.width/2,from.y+from.height/2);
    await touch('touchMove',to.x+to.width/2,to.y+to.height/2);
    await touch('touchEnd');
    await page.waitForFunction(()=>window.events.includes(0));
    // Suspending the Activity mid-drag must release the library's internal gesture too.
    await touch('touchStart',to.x+to.width/2,to.y+to.height/2);
    await page.evaluate(()=>window.horizonNav.suspend());
    await touch('touchEnd');
    await touch('touchStart',to.x+to.width/2,to.y+to.height/2);
    await touch('touchMove',from.x+from.width/2,from.y+from.height/2);
    await touch('touchEnd');
    await page.waitForFunction(()=>window.events.includes(2));
    // Android's native MotionEvent bridge bypasses WebView gesture arbitration.
    await page.evaluate(()=> {
      window.events=[];
      const tabs=[...document.querySelectorAll('#root > .lg-track > [data-lg-item]')];
      window.nativePoint=i=>{const b=tabs[i].getBoundingClientRect();return[(b.x+b.width/2)/innerWidth,(b.y+b.height/2)/innerHeight];};
      window.horizonNav.touch('down',...window.nativePoint(2));
    });
    await page.waitForTimeout(200);
    const nativeStart=(await lens.boundingBox()).x;
    await page.evaluate(()=>window.horizonNav.touch('move',...window.nativePoint(0)));
    await page.waitForTimeout(200);
    assert.ok((await lens.boundingBox()).x<nativeStart-100,'native input must move the pill while the finger is held');
    assert.deepEqual(await page.evaluate(()=>window.events),[],'native drag commits its page only on release');
    await page.evaluate(()=>window.horizonNav.touch('up',...window.nativePoint(0)));
    await page.waitForFunction(()=>window.events.includes(0));
    await page.evaluate(()=> {
      const b=document.querySelector('#root > .lg-track > [data-lg-item="0"]').getBoundingClientRect();
      window.horizonNav.touch('down',(b.x+b.width/2)/innerWidth,(b.y+b.height/2)/innerHeight);
      window.horizonNav.suspend();
    });
    await page.evaluate(()=> {
      const bar=document.querySelector('#root > .lg-track').getBoundingClientRect();
      const tab=document.querySelector('#root > .lg-track > [data-lg-item="2"]').getBoundingClientRect();
      window.horizonNav.touch('down',(bar.left+10)/innerWidth,(bar.top-6)/innerHeight);
      window.horizonNav.touch('move',(tab.x+tab.width/2)/innerWidth,(tab.y+tab.height/2)/innerHeight);
      window.horizonNav.touch('up',(tab.x+tab.width/2)/innerWidth,(tab.y+tab.height/2)/innerHeight);
    });
    await page.waitForFunction(()=>window.events.includes(2));
    await page.evaluate(()=>window.horizonNav.configure({index:1,labels:['Jouer','Packs','Réglages'],reduced:true}));
    await page.waitForFunction(()=>document.documentElement.classList.contains('reduced'));
    assert.equal(await page.getByRole('tab',{name:'Jouer',exact:true}).count(),1);
    assert.equal(await page.locator('#root > .lg-track > .lg-track__glass').evaluate(el=>getComputedStyle(el).backdropFilter),'none');
    await page.evaluate(()=>window.horizonNav.configure({index:99,labels:['Bad'],reduced:false}));
    assert.equal(await page.getByRole('tab',{name:'Jouer',exact:true}).count(),1);
    await page.evaluate(()=>window.horizonNav.configure({index:1,labels:['Play','Packs','Settings'],nativePill:true}));
    await page.waitForFunction(()=>document.documentElement.classList.contains('native-pill'));
    assert.equal(await page.locator('.lg-track__lens').first().evaluate(el=>getComputedStyle(el).visibility),'hidden');
    assert.equal(await page.getByRole('tab',{name:'Packs',exact:true}).count(),1);
    assert.deepEqual(network,[],'the bundled navigation must work without network requests');
    await page.evaluate(()=>window.horizonNav.configure({index:0,labels:['Play','Packs','Settings'],reduced:false}));
    await page.screenshot({path:'/workspace/scratch/horizon-glass-nav.png'});
  }finally{await browser.close();}
});
