/* Harvest TV 1.0.110 «🌐 Doblaje» panel: on/off, target language (default castellano), 10 s voice sample of whoever is speaking
   in the video, capture source (pulse / arecord hw:X,Y / command) with a 5 s capture test, last dubbed phrases and delay.
   Talks only to the local daemon (127.0.0.1:8768/hv/dub/*). Classic + Future Vision. ES5. */
(function(){'use strict';
if(window.__jv110dub)return;
var D='http://127.0.0.1:8768',VER='1.0.110',ov=null,sel=0,st=null,cfg=null,msg='',tmr=null;
function $(s,r){return (r||document).querySelector(s);}
function $$(s,r){return Array.prototype.slice.call((r||document).querySelectorAll(s));}
function esc(s){return String(s==null?'':s).replace(/[&<>"']/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c];});}
function xhr(m,p,b,cb,t){try{var x=new XMLHttpRequest();x.open(m,D+p,true);x.timeout=t||12000;if(b!=null)x.setRequestHeader('Content-Type','text/plain;charset=UTF-8');x.onload=function(){var j=null;try{j=JSON.parse(x.responseText);}catch(e){}cb&&cb(j,x.status);};x.onerror=x.ontimeout=function(){cb&&cb(null,0);};x.send(b!=null?JSON.stringify(b):null);}catch(e){cb&&cb(null,0);}}
function css(){if($('#dub110-css'))return;var c=document.createElement('style');c.id='dub110-css';c.textContent=
 '#dub110{position:fixed;left:6%;right:6%;top:5%;bottom:5%;z-index:99981;display:none;background:rgba(9,13,22,.97);color:#eef;border-radius:24px;box-shadow:0 0 0 2px #34507a;font:22px/1.4 sans-serif;padding:24px 34px;box-sizing:border-box;overflow:hidden}'+
 '#dub110 h2{margin:0 0 4px;font-size:34px}#dub110 .sub{color:#9ab;font-size:19px;margin-bottom:10px}#dub110 .row{margin:10px 0}#dub110 .lbl{color:#9ab;font-size:18px;margin-right:10px}'+
 '#dub110 .b{display:inline-block;margin:4px 8px 4px 0;padding:8px 16px;border-radius:12px;background:#263247;color:#fff;font-size:20px;border:3px solid transparent}#dub110 .b.act{background:#1f5f3a}'+
 '#dub110 [data-c].on{border-color:#fff!important;background:#33558f!important}#dub110 input{font-size:20px;padding:6px 10px;border-radius:10px;border:3px solid #34507a;background:#0b1220;color:#fff;width:260px}#dub110 input.on{border-color:#fff}'+
 '#dub110 .ok{color:#7ee787}#dub110 .no{color:#ff9b8a}#dub110 .q{color:#fbcf5b}#dub110 .log{background:#111a2c;border-radius:14px;padding:8px 14px;font-size:18px;max-height:30%;overflow:hidden}#dub110 .log div{margin:4px 0}'+
 'html.fv #dub110{background:rgba(6,10,20,.9);backdrop-filter:blur(18px);box-shadow:0 0 0 2px rgba(120,170,255,.35)}';document.head.appendChild(c);}
function items(){return ov?$$('[data-c]',ov):[];}
function mark(){var it=items();if(sel>=it.length)sel=it.length-1;if(sel<0)sel=0;it.forEach(function(x,i){x.classList.toggle('on',i===sel);if(i===sel&&x.tagName==='INPUT')try{x.focus();}catch(e){}});}
function render(){if(!ov)return;var s=st||{},c=cfg||{},cap=(c.capture||{}),L=s.langs||{es:'Castellano',en:'Inglés',fr:'Francés',zh:'Chino',ja:'Japonés',ko:'Coreano'},h='';
  h+='<h2>🌐 Doblaje con la voz original</h2><div class="sub">Detecta el idioma, traduce y habla con la voz clonada de quien sale en pantalla (unos 10 s de muestra de su propia voz). Va con retraso; no hay sincronía labial.</div>';
  h+='<div class="row"><span class="lbl">Estado</span>'+(s.running?'<b class="ok">● Activo</b>':'<b class="no">○ Apagado</b>')+(s.sampling!=null?' · <span class="q">🎙 tomando muestra '+s.sampling+' s / 10 s</span>':'')+(s.stats&&s.stats.lastLagSec?' · retraso último '+s.stats.lastLagSec+' s':'')+(s.tokenSet?'':' · <span class="no">falta token del servidor</span>')+'</div>';
  h+='<div class="row"><span class="b'+(s.running?' act':'')+'" data-c="toggle">'+(s.running?'⏹ Desactivar':'▶ Activar')+'</span><span class="b" data-c="sample">🎙 Tomar muestra 10 s</span><span class="b" data-c="reset">♻ Olvidar voces</span></div>';
  h+='<div class="row"><span class="lbl">Doblar al</span>';Object.keys(L).forEach(function(k){h+='<span class="b'+(s.target===k?' act':'')+'" data-c="lang:'+k+'">'+esc(L[k])+'</span>';});h+='</div>';
  h+='<div class="row"><span class="lbl">Captura</span>';[['pulse','PulseAudio (monitor)'],['arecord','ALSA hw:X,Y'],['cmd','Comando']].forEach(function(m){h+='<span class="b'+(cap.mode===m[0]?' act':'')+'" data-c="cap:'+m[0]+'">'+m[1]+'</span>';});
  h+='<input data-c="dev" id="dub110dev" placeholder="'+(cap.mode==='arecord'?'hw:0,13':'@DEFAULT_MONITOR@')+'" value="'+esc(cap.device||'')+'"><span class="b" data-c="test">🔊 Probar captura 5 s</span></div>';
  if(cap.mode==='arecord'||cap.mode==='cmd')h+='<div class="row"><span class="lbl">Formato</span><span class="b'+(cap.rate===48000?' act':'')+'" data-c="fmt:48000:2">48 kHz estéreo</span><span class="b'+(cap.rate===16000?' act':'')+'" data-c="fmt:16000:1">16 kHz mono</span></div>';
  h+='<div class="row"><span class="lbl">Servidor</span><span style="font-size:18px">'+esc(c.backend||'')+'</span> <input data-c="tok" id="dub110tok" type="password" placeholder="'+(c.tokenSet?'token guardado ✓ (escribe para cambiarlo)':'pega aquí el token')+'"><span class="b" data-c="savetok">Guardar token</span></div>';
  if(msg)h+='<div class="row q">'+esc(msg)+'</div>';
  h+='<div class="log">'+((s.last||[]).length?(s.last||[]).map(function(r){return '<div>'+(r.skip?'⏭ '+esc(r.skip)+' · '+esc(r.transcript||''):'🗣 '+esc(r.lang||'?')+' P'+esc(r.speaker)+' «'+esc(String(r.transcript||'').slice(0,90))+'» → <b>«'+esc(String(r.translation||'').slice(0,110))+'»</b> <span class="lbl">'+esc(r.voice||'')+' · '+esc(r.audio||'…')+(r.lagSec?' · '+r.lagSec+' s':'')+'</span>')+'</div>';}).join(''):'<div class="lbl">Aquí verás las frases detectadas, la traducción y el retraso.</div>')+'</div>';
  h+='<div class="sub" style="margin-top:8px">⟵ Atrás para cerrar · Voz: «Jarvis, dobla al castellano», «quita el doblaje», «toma muestra de la voz»'+(s.stats&&s.stats.lastError?' · <span class="no">'+esc(s.stats.lastError)+'</span>':'')+'</div>';
  ov.innerHTML=h;mark();}
function refresh(){xhr('GET','/hv/dub/status',null,function(j){if(j)st=j;xhr('GET','/hv/dub/config',null,function(c){if(c)cfg=c;render();});});}
function setCfg(p,after){xhr('POST','/hv/dub/config',p,function(j){if(j&&j.cfg)cfg=j.cfg;after&&after(j);refresh();});}
function act(a){msg='';
  if(a==='toggle')return xhr('GET',st&&st.running?'/hv/dub/off':'/hv/dub/on',null,function(j){msg=j&&j.ok===false?j.error:'';refresh();});
  if(a==='sample')return xhr('GET','/hv/dub/sample',null,function(j){msg=(j&&(j.say||j.error))||'';refresh();});
  if(a==='reset')return xhr('GET','/hv/dub/reset',null,function(){msg='Voces olvidadas: la próxima persona que hable crea una muestra nueva.';refresh();});
  if(a==='test'){msg='Probando la captura 5 s… pon algo con sonido (Netflix, TV).';render();return xhr('GET','/hv/dub/test-capture',null,function(j){msg=j?(j.hint+' (nivel '+j.rms+', pico '+j.peak+(j.error?', '+j.error:'')+')'):'Sin respuesta.';refresh();},15000);}
  if(a==='savetok'){var t=($('#dub110tok')||{}).value||'';if(!t)return;return setCfg({token:t},function(){msg='Token guardado.';});}
  if(a==='dev'||a==='tok')return;
  if(/^lang:/.test(a))return xhr('GET','/hv/dub/target?lang='+a.slice(5),null,function(){refresh();});
  if(/^cap:/.test(a)){var dev=($('#dub110dev')||{}).value||'',m=a.slice(4),p={capture:{mode:m,device:dev}};if(m==='arecord'&&!(cfg&&cfg.capture&&cfg.capture.rate===16000)){p.capture.rate=48000;p.capture.channels=2;}if(m==='pulse'){p.capture.rate=16000;p.capture.channels=1;}return setCfg(p);}
  if(/^fmt:/.test(a)){var f=a.split(':');return setCfg({capture:{rate:+f[1],channels:+f[2]}});}}
function open(){css();if(!ov){ov=document.createElement('div');ov.id='dub110';document.body.appendChild(ov);ov.addEventListener('click',function(e){var t=e.target&&e.target.closest?e.target.closest('[data-c]'):null;if(t){sel=items().indexOf(t);mark();act(t.getAttribute('data-c'));}});
    ov.addEventListener('change',function(e){if(e.target&&e.target.id==='dub110dev')setCfg({capture:{device:e.target.value}});});}
  ov.style.display='block';window.__hvDubOpen=true;sel=0;render();refresh();clearInterval(tmr);tmr=setInterval(function(){if(isOpen())refresh();},2000);}
function close(){if(ov)ov.style.display='none';window.__hvDubOpen=false;clearInterval(tmr);}
function isOpen(){return !!(ov&&ov.style.display==='block');}
window.addEventListener('keydown',function(e){if(!isOpen())return;var k=e.keyCode,it=items(),cur=it[sel];
  if(cur&&cur.tagName==='INPUT'&&k!==38&&k!==40&&k!==461&&k!==27&&k!==10009&&k!==13)return;/* typing */
  e.preventDefault();e.stopPropagation();
  if(k===37||k===38){sel=Math.max(0,sel-1);mark();}else if(k===39||k===40){sel=Math.min(it.length-1,sel+1);mark();}
  else if(k===13){if(cur){if(cur.tagName==='INPUT'){if(cur.id==='dub110dev')setCfg({capture:{device:cur.value}});else act('savetok');}else act(cur.getAttribute('data-c'));}}
  else if(k===8||k===461||k===27||k===10009)close();},true);
function addBtn(){var ta=$('.top-actions');if(!ta||ta.querySelector('[data-action="dub110"]'))return;var v=ta.querySelector('.voice-btn'),b=document.createElement('button');b.setAttribute('tabindex','0');b.setAttribute('data-focusable','true');b.setAttribute('data-action','dub110');b.className='cr107btn';b.textContent='🌐 Doblaje';
  b.addEventListener('click',function(e){e.preventDefault();e.stopPropagation();if(isOpen())close();else open();});ta.insertBefore(b,v||null);}
addBtn();setInterval(addBtn,3000);
window.__jv110dub={open:open,close:close,isOpen:isOpen,render:render,version:VER};
})();
