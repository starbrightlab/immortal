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
  .grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(84px,1fr));gap:6px}
  .tile{display:flex;flex-direction:column;align-items:center;gap:6px;padding:12px 4px;background:none;color:#fff;border-radius:14px}
  .tile:active{background:#1c1c1e}
  .tile img{width:48px;height:48px;border-radius:12px;background:#1c1c1e}
  .tile span{font-size:12px;max-width:80px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
  .label{color:#9a9a9a;font-size:13px;font-weight:600;margin:6px 2px 10px}
  .top{display:flex;align-items:baseline;justify-content:space-between}
  .link{color:#8ab4f8;font-size:13px;background:none}
</style></head><body><div class=wrap>

  <div id=pairView>
    <h1>Pair your remote</h1>
    <p class=sub>Enter the 6-digit code shown on your Portal's Remote screen.</p>
    <input id=pin class=pin inputmode=numeric maxlength=6 placeholder="000000" autocomplete=off>
    <button class=primary onclick=pair()>Pair</button>
    <div id=pairErr class=err></div>
  </div>

  <div id=remoteView class=hide>
    <div class=top><h1 id=devName>Portal</h1><button class=link onclick=unpair()>Unpair</button></div>
    <div class=label>Navigation</div>
    <div class=nav>
      <button onclick="key('back')">Back</button>
      <button onclick="key('home')">Home</button>
      <button onclick="key('apps')">Recents</button>
      <button onclick="key('power')">Power</button>
    </div>
    <div class=label>Apps</div>
    <div id=grid class=grid></div>
    <div id=remoteErr class=err></div>
  </div>

<script>
  var TKEY='immortal_remote_token';
  function token(){return localStorage.getItem(TKEY);}
  function show(view){
    document.getElementById('pairView').classList.toggle('hide',view!=='pair');
    document.getElementById('remoteView').classList.toggle('hide',view!=='remote');
  }
  function api(path,opts){
    opts=opts||{};opts.headers=opts.headers||{};
    if(token())opts.headers['Authorization']='Bearer '+token();
    return fetch(path,opts).then(function(r){
      if(r.status===401){localStorage.removeItem(TKEY);show('pair');throw new Error('unauthorized');}
      return r.json();
    });
  }
  function pair(){
    var pin=document.getElementById('pin').value.trim();
    document.getElementById('pairErr').textContent='';
    fetch('/remote/pair',{method:'POST',body:JSON.stringify({pin:pin})})
      .then(function(r){return r.json();})
      .then(function(d){
        if(d.ok&&d.token){localStorage.setItem(TKEY,d.token);start(d.name);}
        else document.getElementById('pairErr').textContent='That code didn\'t work. Check the Portal and try again.';
      })
      .catch(function(){document.getElementById('pairErr').textContent='Couldn\'t reach the Portal.';});
  }
  function unpair(){localStorage.removeItem(TKEY);location.hash='';show('pair');}
  function key(action){
    api('/remote/key',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({action:action})})
      .catch(function(){});
  }
  function launch(pkg){
    api('/remote/launch',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({packageName:pkg})})
      .catch(function(){});
  }
  function loadApps(){
    api('/remote/apps').then(function(d){
      var g=document.getElementById('grid');g.innerHTML='';
      (d.apps||[]).forEach(function(a){
        var b=document.createElement('button');b.className='tile';b.onclick=function(){launch(a.packageName);};
        var img=document.createElement('img');img.src='/remote/icon?pkg='+encodeURIComponent(a.packageName);img.loading='lazy';
        var s=document.createElement('span');s.textContent=a.label;
        b.appendChild(img);b.appendChild(s);g.appendChild(b);
      });
    }).catch(function(){});
  }
  function start(name){
    if(name)document.getElementById('devName').textContent=name;
    show('remote');loadApps();
  }
  // Scan-to-pair: a QR encodes the URL with #pin=NNNNNN, so the page auto-pairs.
  (function(){
    var m=location.hash.match(/pin=(\d{6})/);
    if(m){document.getElementById('pin').value=m[1];pair();}
    else if(token())start();
    else show('pair');
  })();
</script>
</div></body></html>
      """.trimIndent()
}
