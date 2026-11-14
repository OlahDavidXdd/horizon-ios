import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {chromium} from 'playwright-core';

const html=await readFile(new URL('../../app/src/main/assets/glass-nav/index.html',import.meta.url),'utf8');
test('bundled GlassNav selects native tabs, accepts native updates, and drags its lens',async()=>{
  const browser=await chromium.launch({executablePath:process.env.CHROMIUM_PATH||'/usr/bin/chromium',headless:true,args:['--no-sandbox']});
  try{
    const page=await browser.newPage({viewport:{width:390,height:104},deviceScaleFactor:1});
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
    await page.evaluate(()=>window.horizonNav.configure({index:1,labels:['Jouer','Packs','Réglages'],reduced:true}));
    await page.waitForFunction(()=>document.documentElement.classList.contains('reduced'));
    assert.equal(await page.getByRole('tab',{name:'Jouer',exact:true}).count(),1);
    assert.equal(await page.locator('#root > .lg-track > .lg-track__glass').evaluate(el=>getComputedStyle(el).backdropFilter),'none');
    await page.evaluate(()=>window.horizonNav.configure({index:99,labels:['Bad'],reduced:false}));
    assert.equal(await page.getByRole('tab',{name:'Jouer',exact:true}).count(),1);
    assert.deepEqual(network,[],'the bundled navigation must work without network requests');
    await page.evaluate(()=>window.horizonNav.configure({index:0,labels:['Play','Packs','Settings'],reduced:false}));
    await page.screenshot({path:'/workspace/scratch/horizon-glass-nav.png'});
  }finally{await browser.close();}
});
