/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

/**
 * The single-page phone remote served at `/remote/ui` by [RemoteRoutes]. Vanilla
 * HTML/CSS/JS (no framework, no build step), matching the hand-rolled page style of
 * [LanSetupServer]. Two views toggled in-page: a PIN-pair screen and the remote itself
 * (Tier-A nav buttons + an app-launcher grid). The session token lives in localStorage;
 * every API call sends it as `Authorization: Bearer …`.
 *
 * No Kotlin `$` templating is used below (the JS does its own string work) so the raw
 * string stays verbatim.
 */
object RemoteHtml {
  val PAGE: String =
      """
<!doctype html><html><head>
<meta charset=utf-8>
<meta name=viewport content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<meta name=apple-mobile-web-app-capable content=yes>
<title>Immortal remote</title>
<style>
  *{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
  body{margin:0;background:#0e0e10;color:#fff;font-family:-apple-system,Roboto,Segoe UI,sans-serif}
  .wrap{max-width:560px;margin:0 auto;padding:18px}
  h1{font-size:22px;margin:6px 0 2px}
  .sub{color:#9a9a9a;font-size:14px;margin:0 0 18px}
  .hide{display:none!important}
  .pin{width:100%;letter-spacing:.4em;text-align:center;font-size:30px;padding:16px;margin:10px 0;
    background:#0e0e10;border:1px solid #3a3a3c;border-radius:12px;color:#fff}
  button{font:inherit;border:0;cursor:pointer}
  .primary{width:100%;padding:16px;font-size:18px;font-weight:600;border-radius:12px;background:#2e6be6;color:#fff}
  .err{color:#e0908a;font-size:14px;min-height:18px;margin-top:8px}
  .nav{display:grid;grid-template-columns:repeat(2,1fr);gap:10px;margin:4px 0 22px}
  .nav button{padding:18px 8px;font-size:15px;background:#1c1c1e;color:#fff;border-radius:14px}
  .nav button:active{background:#2e6be6}
  .presets{display:flex;flex-wrap:wrap;gap:8px;margin:0 0 6px}
  .presets button{padding:12px 16px;font-size:14px;background:#1c1c1e;color:#fff;border-radius:12px}
  .presets button:active{background:#2e6be6}
  .none{color:#6c6c6c;font-size:13px}
  .editlink{background:none;color:#8ab4f8;font-size:13px;margin:0 0 22px;padding:2px}
  .editor{background:#161618;border:1px solid #2a2a2c;border-radius:14px;padding:14px;margin:0 0 22px}
  .editor.hide{display:none}
  .editor h3{font-size:14px;margin:10px 0 4px}
  .editor input,.editor select{padding:10px;font-size:14px;background:#0e0e10;border:1px solid #3a3a3c;border-radius:10px;color:#fff;margin:4px 0}
  .editor input{width:100%}
  .editor .row{display:flex;gap:6px;align-items:center;flex-wrap:wrap}
  .editor .row select,.editor .row input{flex:1;min-width:90px;width:auto}
  .editor .row button{padding:10px 14px;font-size:13px;background:#2a2a2c;color:#fff;border-radius:10px}
  .steprow{font-size:13px;color:#bbb;padding:5px 0;display:flex;justify-content:space-between;align-items:center}
  .delp{background:none;color:#e0908a;font-size:13px}
  .pad{height:240px;background:#161618;border:1px solid #2a2a2c;border-radius:16px;margin:0 0 6px;
    display:flex;align-items:center;justify-content:center;color:#6c6c6c;font-size:13px;
    text-align:center;padding:0 18px;touch-action:none;-webkit-user-select:none;user-select:none}
  .pad.active{border-color:#2e6be6}
  .padhint{color:#7c7c7c;font-size:12px;margin:0 2px 22px;min-height:16px}
  .kbd{display:flex;gap:8px;margin-bottom:8px}
  .kbd input{flex:1;min-width:0;padding:14px;font-size:16px;background:#0e0e10;border:1px solid #3a3a3c;border-radius:12px;color:#fff}
  .kbd button{padding:0 18px;font-size:15px;font-weight:600;background:#2e6be6;color:#fff;border-radius:12px}
  .keyops{display:flex;gap:8px;margin-bottom:22px}
  .keyops button{flex:1;padding:12px;font-size:14px;background:#1c1c1e;color:#fff;border-radius:12px}
  .grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(84px,1fr));gap:6px}
  .tile{display:flex;flex-direction:column;align-items:center;gap:6px;padding:12px 4px;background:none;color:#fff;border-radius:14px}
  .tile:active{background:#1c1c1e}
  .tile img{width:48px;height:48px;border-radius:12px;background:#1c1c1e}
  .tile span{font-size:12px;max-width:80px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
  .label{color:#9a9a9a;font-size:13px;font-weight:600;margin:6px 2px 10px}
  .top{display:flex;align-items:center;justify-content:space-between;gap:8px;margin-bottom:14px}
  .top select{flex:1;min-width:0;padding:10px;font-size:16px;background:#1c1c1e;border:1px solid #3a3a3c;border-radius:10px;color:#fff}
  .link{color:#8ab4f8;font-size:13px;background:none;white-space:nowrap}
  .addpanel{background:#161618;border:1px solid #2a2a2c;border-radius:14px;padding:14px;margin-bottom:18px}
  .discovered{display:flex;flex-wrap:wrap;gap:8px;margin:8px 0}
  .discovered button{padding:10px 14px;font-size:14px;background:#1c1c1e;color:#fff;border-radius:10px}
</style></head><body><div class=wrap>

  <div id=pairView>
    <h1>Pair your remote</h1>
    <p class=sub>Enter the 6-digit code shown on your Portal's Remote screen.</p>
    <input id=pin class=pin inputmode=numeric maxlength=6 placeholder="000000" autocomplete=off>
    <button class=primary onclick=pair()>Pair</button>
    <div id=pairErr class=err></div>
  </div>

  <div id=remoteView class=hide>
    <div class=top>
      <select id=devsel onchange=switchDevice()></select>
      <button class=link onclick=toggleAdd()>+ Device</button>
      <button class=link onclick=forgetDevice()>Forget</button>
    </div>
    <div id=addPanel class="addpanel hide">
      <p class=sub>Add another Portal on your Wi-Fi: pick a discovered device, then enter the PIN from its <b>Settings &rsaquo; Remote</b> screen.</p>
      <div id=discovered class=discovered></div>
      <div id=addPair class=hide>
        <p class=sub id=addPairName></p>
        <input id=addpin class=pin inputmode=numeric maxlength=6 placeholder="000000" autocomplete=off>
        <button class=primary onclick=addPairSubmit()>Pair device</button>
        <div id=addErr class=err></div>
      </div>
    </div>
    <div class=label>Navigation</div>
    <div class=nav>
      <button onclick="key('back')">Back</button>
      <button onclick="key('home')">Home</button>
      <button onclick="key('apps')">Recents</button>
      <button onclick="key('power')">Power</button>
    </div>
    <div class=label>Presets</div>
    <div id=presets class=presets></div>
    <button class=editlink onclick=toggleEditor()>Edit presets</button>
    <div id=editor class="editor hide">
      <div id=existing></div>
      <h3>New preset</h3>
      <input id=pname placeholder="Preset name" autocomplete=off>
      <div class=row>
        <select id=stype onchange=onType()>
          <option value=launch>Launch app</option>
          <option value=key>Nav key</option>
          <option value=text>Type text</option>
          <option value=wait>Wait</option>
          <option value=config>Set screensaver</option>
        </select>
        <span id=sparam></span>
        <button onclick=addStep()>Add step</button>
      </div>
      <div id=draft></div>
      <button class=primary onclick=saveDraft()>Save preset</button>
    </div>
    <div class=label>Touchpad</div>
    <div id=pad class=pad>Drag to move the pointer&nbsp;&middot;&nbsp;tap to click&nbsp;&middot;&nbsp;two fingers to scroll</div>
    <div id=padHint class=padhint></div>
    <div class=label>Keyboard</div>
    <div class=kbd>
      <input id=txt placeholder="Type, then Send to the focused field" autocomplete=off autocapitalize=off autocorrect=off>
      <button onclick="sendText()">Send</button>
    </div>
    <div class=keyops>
      <button onclick="textOp('backspace')">&#9003; Backspace</button>
      <button onclick="textOp('clear')">Clear</button>
    </div>
    <div class=label>Apps</div>
    <div id=grid class=grid></div>
    <div id=remoteErr class=err></div>
  </div>

<script>
  // Multi-device: a roster of paired Portals {name, base, token} kept on the phone; one is active.
  var DKEY='immortal_remote_devices', AKEY='immortal_remote_active', pendingPeer=null;
  function devicesList(){try{return JSON.parse(localStorage.getItem(DKEY)||'[]');}catch(e){return [];}}
  function saveDevices(l){localStorage.setItem(DKEY,JSON.stringify(l));}
  function activeIdx(){var i=parseInt(localStorage.getItem(AKEY)||'0',10),l=devicesList();return (i>=0&&i<l.length)?i:0;}
  function setActive(i){localStorage.setItem(AKEY,String(i));}
  function active(){return devicesList()[activeIdx()]||null;}
  function show(view){
    document.getElementById('pairView').classList.toggle('hide',view!=='pair');
    document.getElementById('remoteView').classList.toggle('hide',view!=='remote');
  }
  function api(path,opts){
    opts=opts||{};opts.headers=opts.headers||{};
    var a=active();
    if(a&&a.token)opts.headers['Authorization']='Bearer '+a.token;
    return fetch((a?a.base:'')+path,opts).then(function(r){
      if(r.status===401){ // creds for this device went stale — forget it and fall back
        var l=devicesList();l.splice(activeIdx(),1);saveDevices(l);setActive(0);
        if(l.length){renderDevSel();loadApps();loadPresets();}else show('pair');
        throw new Error('unauthorized');
      }
      return r.json();
    });
  }
  function pair(){ // pairs THIS Portal (the page's own origin) — the first device
    var pin=document.getElementById('pin').value.trim();
    document.getElementById('pairErr').textContent='';
    fetch('/remote/pair',{method:'POST',body:JSON.stringify({pin:pin})})
      .then(function(r){return r.json();})
      .then(function(d){
        if(d.ok&&d.token){
          var l=devicesList().filter(function(x){return x.base!==location.origin;});
          l.unshift({name:d.name||'Portal',base:location.origin,token:d.token});
          saveDevices(l);setActive(0);startActive();
        } else document.getElementById('pairErr').textContent='That code didn\'t work. Check the Portal and try again.';
      })
      .catch(function(){document.getElementById('pairErr').textContent='Couldn\'t reach the Portal.';});
  }
  function renderDevSel(){
    var sel=document.getElementById('devsel');sel.innerHTML='';
    devicesList().forEach(function(dv,i){var o=document.createElement('option');o.value=i;o.textContent=dv.name;sel.appendChild(o);});
    sel.value=activeIdx();
  }
  function switchDevice(){setActive(parseInt(document.getElementById('devsel').value,10));document.getElementById('addPanel').classList.add('hide');loadApps();loadPresets();}
  function forgetDevice(){var l=devicesList();if(!l.length)return;l.splice(activeIdx(),1);saveDevices(l);setActive(0);if(l.length){renderDevSel();loadApps();loadPresets();}else{location.hash='';show('pair');}}
  function toggleAdd(){var p=document.getElementById('addPanel');p.classList.toggle('hide');document.getElementById('addPair').classList.add('hide');if(!p.classList.contains('hide'))loadDiscovered();}
  function loadDiscovered(){
    api('/remote/devices').then(function(d){
      var c=document.getElementById('discovered');c.innerHTML='';
      var have={};devicesList().forEach(function(x){have[x.base]=1;});
      var peers=(d.devices||[]).filter(function(p){return !have['http://'+p.host+':'+p.port];});
      if(!peers.length){c.innerHTML='<span class=none>No other Portals found yet — make sure they\'re on and on the same Wi-Fi.</span>';return;}
      peers.forEach(function(p){var b=document.createElement('button');b.textContent=p.name;b.onclick=function(){pickPeer(p);};c.appendChild(b);});
    }).catch(function(){});
  }
  function pickPeer(p){
    pendingPeer={name:p.name,base:'http://'+p.host+':'+p.port};
    document.getElementById('addPairName').textContent='Enter the PIN shown on “'+p.name+'” (Settings › Remote).';
    document.getElementById('addErr').textContent='';document.getElementById('addpin').value='';
    document.getElementById('addPair').classList.remove('hide');
  }
  function addPairSubmit(){
    if(!pendingPeer)return;
    var pin=document.getElementById('addpin').value.trim();document.getElementById('addErr').textContent='';
    fetch(pendingPeer.base+'/remote/pair',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({pin:pin})})
      .then(function(r){return r.json();})
      .then(function(d){
        if(d.ok&&d.token){
          var l=devicesList();l.push({name:d.name||pendingPeer.name,base:pendingPeer.base,token:d.token});
          saveDevices(l);setActive(l.length-1);renderDevSel();
          document.getElementById('addPanel').classList.add('hide');pendingPeer=null;loadApps();loadPresets();
        } else document.getElementById('addErr').textContent='That code didn\'t work.';
      })
      .catch(function(){document.getElementById('addErr').textContent='Couldn\'t reach that device.';});
  }
  function key(action){
    api('/remote/key',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({action:action})})
      .catch(function(){});
  }
  function launch(pkg){
    api('/remote/launch',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({packageName:pkg})})
      .catch(function(){});
  }
  function postText(mode,text){
    api('/remote/text',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({mode:mode,text:text||''})})
      .then(function(d){
        document.getElementById('remoteErr').textContent=(d&&d.applied===false)
          ? 'Select a text field on the Portal first, then send.' : '';
      }).catch(function(){});
  }
  function sendText(){postText('set',document.getElementById('txt').value);}
  function textOp(mode){postText(mode,'');}
  // --- presets ---
  var presetsData=[], draftSteps=[], appsCache=[];
  // Curated screensaver config actions for preset steps (the remote×fleet bridge). No
  // credential forms — just the common one-tap settings; albumUrl takes a value.
  var CONFIG_ACTIONS=[
    {label:'Screensaver on',body:{enabled:true}},
    {label:'Screensaver off',body:{enabled:false}},
    {label:'Use default photo feed',body:{source:'default'}},
    {label:'Shuffle on',body:{shuffle:true}},
    {label:'Shuffle off',body:{shuffle:false}},
    {label:'Show now-playing',body:{showNowPlaying:true}},
    {label:'Hide now-playing',body:{showNowPlaying:false}},
    {label:'Set album URL',needsText:'albumUrl'}
  ];
  function loadPresets(){api('/remote/presets').then(function(d){presetsData=d.presets||[];renderPresets();}).catch(function(){});}
  function renderPresets(){
    var c=document.getElementById('presets');c.innerHTML='';
    if(!presetsData.length){c.innerHTML='<span class=none>No presets yet — tap “Edit presets”.</span>';return;}
    presetsData.forEach(function(p){var b=document.createElement('button');b.textContent=p.name||'(unnamed)';b.onclick=function(){runPreset(p.id);};c.appendChild(b);});
  }
  function runPreset(id){api('/remote/preset',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({id:id})}).catch(function(){});}
  function toggleEditor(){var e=document.getElementById('editor');e.classList.toggle('hide');if(!e.classList.contains('hide')){onType();renderExisting();renderDraft();}}
  function renderExisting(){
    var c=document.getElementById('existing');c.innerHTML='';
    presetsData.forEach(function(p){var d=document.createElement('div');d.className='steprow';
      var t=document.createElement('span');t.textContent=(p.name||'(unnamed)')+' · '+((p.steps||[]).length)+' steps';
      var x=document.createElement('button');x.className='delp';x.textContent='Delete';x.onclick=function(){deletePreset(p.id);};
      d.appendChild(t);d.appendChild(x);c.appendChild(d);});
  }
  function onType(){
    var t=document.getElementById('stype').value,s=document.getElementById('sparam');s.innerHTML='';
    if(t==='config'){
      var sel=document.createElement('select');sel.id='spval';
      CONFIG_ACTIONS.forEach(function(a,i){var o=document.createElement('option');o.value=i;o.textContent=a.label;sel.appendChild(o);});
      var tx=document.createElement('input');tx.id='spval2';tx.placeholder='album URL';tx.style.display='none';
      sel.onchange=function(){tx.style.display=CONFIG_ACTIONS[sel.value].needsText?'':'none';};
      s.appendChild(sel);s.appendChild(tx);return;
    }
    var el;
    if(t==='launch'){el=document.createElement('select');appsCache.forEach(function(a){var o=document.createElement('option');o.value=a.packageName;o.textContent=a.label;el.appendChild(o);});}
    else if(t==='key'){el=document.createElement('select');['home','back','recents','power'].forEach(function(k){var o=document.createElement('option');o.value=k;o.textContent=k;el.appendChild(o);});}
    else if(t==='text'){el=document.createElement('input');el.placeholder='text to type';}
    else {el=document.createElement('input');el.type='number';el.value='500';}
    el.id='spval';s.appendChild(el);
  }
  function addStep(){
    var t=document.getElementById('stype').value,v=document.getElementById('spval'),val=v?v.value:'',step={type:t};
    if(t==='launch')step.packageName=val;
    else if(t==='key')step.action=val;
    else if(t==='text'){step.mode='set';step.text=val;}
    else if(t==='config'){
      var a=CONFIG_ACTIONS[val],body;
      if(a.needsText){var tv=(document.getElementById('spval2').value||'').trim();if(!tv)return;body={};body[a.needsText]=tv;step.label=a.label+': '+tv;}
      else{body=JSON.parse(JSON.stringify(a.body));step.label=a.label;}
      step.target='screensaver';step.body=body;
    }
    else step.ms=parseInt(val,10)||500;
    draftSteps.push(step);renderDraft();
  }
  function stepLabel(s){
    if(s.type==='launch'){var a=appsCache.filter(function(x){return x.packageName===s.packageName;})[0];return 'Launch '+((a&&a.label)||s.packageName);}
    if(s.type==='key')return 'Key: '+s.action;
    if(s.type==='text')return 'Type: '+s.text;
    if(s.type==='config')return s.label||'Set screensaver';
    return 'Wait '+s.ms+'ms';
  }
  function renderDraft(){
    var c=document.getElementById('draft');c.innerHTML='';
    draftSteps.forEach(function(s,idx){var d=document.createElement('div');d.className='steprow';
      var t=document.createElement('span');t.textContent=(idx+1)+'. '+stepLabel(s);
      var x=document.createElement('button');x.className='delp';x.textContent='remove';x.onclick=function(){draftSteps.splice(idx,1);renderDraft();};
      d.appendChild(t);d.appendChild(x);c.appendChild(d);});
  }
  function savePresets(then){api('/remote/presets',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({presets:presetsData})}).then(function(d){presetsData=d.presets||presetsData;if(then)then();}).catch(function(){});}
  function saveDraft(){
    var name=document.getElementById('pname').value.trim();if(!name||!draftSteps.length)return;
    presetsData.push({id:'p'+Date.now()+Math.floor(Math.random()*1000),name:name,steps:draftSteps.slice()});
    savePresets(function(){draftSteps=[];document.getElementById('pname').value='';renderPresets();renderExisting();renderDraft();});
  }
  function deletePreset(id){presetsData=presetsData.filter(function(p){return p.id!==id;});savePresets(function(){renderPresets();renderExisting();});}
  function loadApps(){
    api('/remote/apps').then(function(d){
      appsCache=d.apps||[];
      var g=document.getElementById('grid');g.innerHTML='';
      (d.apps||[]).forEach(function(a){
        var b=document.createElement('button');b.className='tile';b.onclick=function(){launch(a.packageName);};
        var img=document.createElement('img');img.src=(active()?active().base:'')+'/remote/icon?pkg='+encodeURIComponent(a.packageName);img.loading='lazy';
        var s=document.createElement('span');s.textContent=a.label;
        b.appendChild(img);b.appendChild(s);g.appendChild(b);
      });
    }).catch(function(){});
  }
  var SENS=2.2, SCROLL=2.0, padReady=false;   // phone-px -> TV-px multipliers
  function padHint(t){document.getElementById('padHint').textContent=t||'';}
  function gestureGone(d){if(d&&d.error==='no_gestures')padHint('Touchpad needs the accessibility service — re-open Settings › Remote on the Portal.');}
  function setupPad(){
    if(padReady)return; padReady=true;
    var pad=document.getElementById('pad');
    var lastX=0,lastY=0,startX=0,startY=0,startT=0,moved=false,mode=0;
    var accDx=0,accDy=0,flush=false;
    function send(){flush=false;if(accDx||accDy){var dx=accDx,dy=accDy;accDx=0;accDy=0;
      api('/remote/cursor',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({dx:dx*SENS,dy:dy*SENS})}).then(gestureGone).catch(function(){});}}
    function queue(dx,dy){accDx+=dx;accDy+=dy;if(!flush){flush=true;setTimeout(send,35);}}
    function scroll(dy){api('/remote/swipe',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({dx:0,dy:dy*SCROLL})}).then(gestureGone).catch(function(){});}
    pad.addEventListener('touchstart',function(e){e.preventDefault();pad.classList.add('active');
      if(e.touches.length===2){mode=2;lastY=(e.touches[0].clientY+e.touches[1].clientY)/2;}
      else{mode=1;var t=e.touches[0];lastX=startX=t.clientX;lastY=startY=t.clientY;startT=Date.now();moved=false;}
    },{passive:false});
    pad.addEventListener('touchmove',function(e){e.preventDefault();
      if(mode===2&&e.touches.length>=2){var my=(e.touches[0].clientY+e.touches[1].clientY)/2;var d=my-lastY;lastY=my;if(d)scroll(d);return;}
      var t=e.touches[0];var dx=t.clientX-lastX,dy=t.clientY-lastY;lastX=t.clientX;lastY=t.clientY;
      if(Math.abs(t.clientX-startX)>8||Math.abs(t.clientY-startY)>8)moved=true;
      queue(dx,dy);
    },{passive:false});
    pad.addEventListener('touchend',function(){pad.classList.remove('active');
      if(mode===1&&!moved&&Date.now()-startT<300){api('/remote/tap',{method:'POST'}).then(gestureGone).catch(function(){});}
      mode=0;
    });
  }
  function startActive(){
    show('remote');renderDevSel();setupPad();loadApps();loadPresets();
  }
  // Scan-to-pair: a QR encodes the URL with #pin=NNNNNN, so the page auto-pairs this Portal.
  (function(){
    var m=location.hash.match(/pin=(\d{6})/);
    if(m){document.getElementById('pin').value=m[1];pair();}
    else if(active())startActive();
    else show('pair');
  })();
</script>
</div></body></html>
      """.trimIndent()
}
