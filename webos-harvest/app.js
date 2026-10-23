(function(){
  'use strict';

  var BACKEND=localStorage.getItem('jarvisBackend')||'https://chatgpt-tv2.vercel.app';
  var state={
    installedApps:[],
    appStatus:{},
    focusIndex:0,
    nowTab:'notifications',
    mediaRows:[],
    selectedMedia:null,
    selectedProvider:'',
    chats:loadChats(),
    currentChatId:localStorage.getItem('jarvisCurrentChat')||'tv-main',
    phoneHost:localStorage.getItem('jarvisPhoneHost')||'',
    phoneToken:localStorage.getItem('jarvisPhoneToken')||''
  };

  var STREAMING=[
    {name:'Netflix',ids:['netflix']},
    {name:'Prime Video',ids:['amazon']},
    {name:'YouTube',ids:['youtube.leanback.v4']},
    {name:'Disney+',ids:['com.disney.disneyplus-prod']},
    {name:'Apple TV',ids:['com.apple.appletv']},
    {name:'TV',ids:['com.webos.app.livetv','com.webos.service.iepg']}
  ];

  function $(id){return document.getElementById(id);}
  function safe(v){return v===null||typeof v==='undefined'?'':String(v);}
  function esc(v){return safe(v).replace(/[&<>"']/g,function(m){return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[m];});}
  function toast(msg){var el=$('toast');if(!el)return;el.textContent=msg;el.className='toast show';setTimeout(function(){el.className='toast';},2400);}
  function jsonParse(s,f){try{return JSON.parse(s);}catch(e){return f;}}
  function loadChats(){var a=jsonParse(localStorage.getItem('jarvisChats')||'[]',[]);if(!a.length)a=[{id:'tv-main',title:'Jarvis TV',preview:'Conversación principal',updated:Date.now()}];return a;}
  function saveChats(){localStorage.setItem('jarvisChats',JSON.stringify(state.chats));}

  function timeoutFetch(url,opts,ms){
    return new Promise(function(resolve,reject){
      var done=false,t=setTimeout(function(){if(done)return;done=true;reject(new Error('timeout'));},ms||6000);
      fetch(url,opts||{}).then(function(r){if(done)return;done=true;clearTimeout(t);resolve(r);}).catch(function(e){if(done)return;done=true;clearTimeout(t);reject(e);});
    });
  }

  function luna(uri,payload){
    payload=payload||{};
    return new Promise(function(resolve,reject){
      if(!window.PalmServiceBridge){reject(new Error('Luna API no disponible'));return;}
      var bridge=new PalmServiceBridge();
      bridge.onservicecallback=function(raw){try{resolve(JSON.parse(raw));}catch(e){reject(e);}};
      bridge.call(uri,JSON.stringify(payload));
    });
  }

  function mediaImage(x){return (x&&x.episode&&x.episode.image)||x.image||'';}
  function mediaMeta(x){
    var bits=[];
    if(x&&x.network)bits.push(x.network);
    if(x&&x.premiered)bits.push(String(x.premiered).slice(0,4));
    if(x&&x.rating)bits.push('★ '+x.rating);
    if(x&&x.genres&&x.genres.length)bits.push(x.genres.slice(0,2).join(' · '));
    if(x&&x.episode&&x.episode.season)bits.push('T'+x.episode.season+(x.episode.number?' · E'+x.episode.number:''));
    return bits;
  }

  function setHero(x,rowTitle){
    state.selectedMedia=x||null;
    var title=x&&x.title?x.title:'Selecciona una película o serie';
    var summary=x&&x.summary?x.summary:'La ficha del contenido aparecerá aquí con imagen, sinopsis y progreso.';
    var provider=rowTitle||(x&&x.network)||'Jarvis TV';
    var img=mediaImage(x||{});
    $('heroTitle').textContent=title;
    $('heroSummary').textContent=summary.slice(0,280);
    $('heroProvider').textContent=provider;
    $('heroMeta').innerHTML=mediaMeta(x||{}).map(function(m){return '<span>'+esc(m)+'</span>';}).join('');
    $('heroBackdrop').style.backgroundImage=img?'url("'+img.replace(/"/g,'%22')+'")':'none';
  }

  function renderRows(){
    var rows=state.mediaRows||[];
    if(!rows.length){
      $('catalogRows').innerHTML='<div class="empty-state">Cargando biblioteca multimedia…</div>';
      setHero(null,'');
      return;
    }
    $('catalogRows').innerHTML=rows.slice(0,3).map(function(row,ri){
      var cards=(row.items||[]).slice(0,8).map(function(x,ci){
        var img=mediaImage(x),progress=row.kind==='continue'?Math.min(94,24+(ci*13)%62):0;
        return '<article class="media-card" tabindex="0" data-focusable="true" data-action="media" data-row="'+ri+'" data-index="'+ci+'">'+
          (img?'<img src="'+esc(img)+'" alt="">':'')+
          '<div class="shade"></div><div class="title">'+esc(x.title||'')+'</div>'+
          (progress?'<div class="progress"><i style="width:'+progress+'%"></i></div>':'')+
        '</article>';
      }).join('');
      return '<div class="catalog-row"><div class="catalog-row-head"><h4>'+esc(row.title)+'</h4><span>'+esc(row.provider||'')+'</span></div><div class="catalog-track">'+cards+'</div></div>';
    }).join('');
    var first=rows[0]&&rows[0].items&&rows[0].items[0];
    if(first)setHero(first,rows[0].title);
  }

  function loadCatalog(){
    timeoutFetch(BACKEND+'/api/harvest/catalog?mode=home',{},9000)
      .then(function(r){if(!r.ok)throw new Error('HTTP '+r.status);return r.json();})
      .then(function(j){state.mediaRows=j.rows||[];renderRows();})
      .catch(function(){$('catalogRows').innerHTML='<div class="empty-state">No se pudo cargar el catálogo ahora.</div>';});
  }

  function loadProvider(name){
    state.selectedProvider=name;
    if(name==='YouTube'){
      state.mediaRows=[{title:'YouTube · Cuenta',provider:'YouTube',kind:'private',items:[]}];
      $('catalogRows').innerHTML='<div class="catalog-row"><div class="catalog-row-head"><h4>YouTube</h4><span>Cuenta no vinculada</span></div><div class="empty-state">Conecta Google/YouTube para mostrar Suscripciones, Ver más tarde y Últimos vídeos.</div></div>';
      setHero({title:'YouTube',summary:'Conecta tu cuenta para personalizar esta sección.',network:'YouTube',image:''},'YouTube');
      return;
    }
    timeoutFetch(BACKEND+'/api/harvest/catalog?provider='+encodeURIComponent(name),{},8000)
      .then(function(r){if(!r.ok)throw new Error('HTTP '+r.status);return r.json();})
      .then(function(j){state.mediaRows=[{title:name+' · Catálogo',provider:name,kind:'provider',items:j.items||[]}];renderRows();})
      .catch(function(){toast('No se pudo cargar '+name);});
  }

  function loadInstalledApps(){
    return luna('luna://com.webos.applicationManager/listApps',{}).then(function(r){
      state.installedApps=(r.apps||[]).map(function(a){return{id:a.id,title:a.title||a.id,icon:a.largeIcon||a.icon||''};}).filter(function(a){return a.id;});
      state.installedApps.sort(function(a,b){return a.title.localeCompare(b.title);});
    }).catch(function(){state.installedApps=[];});
  }

  function findInstalled(name){
    var q=safe(name).toLowerCase(),i,a;
    for(i=0;i<state.installedApps.length;i++){a=state.installedApps[i];if(a.title.toLowerCase()===q)return a;}
    for(i=0;i<state.installedApps.length;i++){a=state.installedApps[i];if(a.title.toLowerCase().indexOf(q)>=0||a.id.toLowerCase().indexOf(q)>=0)return a;}
    var def=STREAMING.filter(function(x){return x.name===name;})[0];
    if(def&&def.ids&&def.ids.length)return{id:def.ids[0],title:name,icon:''};
    return null;
  }

  function renderQuickApps(){
    $('quickApps').innerHTML=STREAMING.map(function(a){
      var found=findInstalled(a.name),icon=found&&found.icon?found.icon:'';
      return '<button class="quick-app" tabindex="0" data-focusable="true" data-action="provider" data-name="'+esc(a.name)+'" data-id="'+esc(found?found.id:'')+'">'+
        (icon?'<img src="'+esc(icon)+'" alt="'+esc(a.name)+'">':esc(a.name))+
      '</button>';
    }).join('');
  }

  function launchById(id,label){
    if(!id){toast('No encuentro '+label+' en esta TV');return;}
    luna('luna://com.webos.applicationManager/launch',{id:id}).then(function(r){
      if(r&&r.returnValue===false)toast('No se pudo abrir '+label);
    }).catch(function(){toast('No se pudo abrir '+label);});
  }

  function renderAppsDrawer(){
    var all=state.installedApps.length?state.installedApps:[
      {id:'netflix',title:'Netflix'},{id:'amazon',title:'Prime Video'},{id:'youtube.leanback.v4',title:'YouTube'},
      {id:'com.disney.disneyplus-prod',title:'Disney+'},{id:'com.apple.appletv',title:'Apple TV'},
      {id:'com.webos.app.browser',title:'Navegador'}
    ];
    $('appsGrid').innerHTML=all.slice(0,60).map(function(a){
      return '<button tabindex="0" data-focusable="true" class="installed-app" data-action="launch-id" data-id="'+esc(a.id)+'" data-title="'+esc(a.title)+'">'+
        (a.icon?'<img class="installed-icon" src="'+esc(a.icon)+'" alt="">':'')+
        '<b>'+esc(a.title)+'</b><span>'+esc(a.id)+'</span></button>';
    }).join('');
  }

  function phoneUrl(path){if(!state.phoneHost)return'';return'http://'+state.phoneHost+':8765'+path;}
  function rawPhoneFetch(path,auth){
    var url=phoneUrl(path);if(!url)return Promise.reject(new Error('phone_not_configured'));
    var headers={};if(auth!==false&&state.phoneToken)headers.Authorization='Bearer '+state.phoneToken;
    return timeoutFetch(url,{headers:headers},4500).then(function(r){
      return r.json().catch(function(){return{};}).then(function(j){if(!r.ok){throw new Error(j.error||('HTTP '+r.status));}return j;});
    });
  }
  function phoneFetch(path){return rawPhoneFetch(path,true);}

  function pairPhone(host,pin){
    state.phoneHost=safe(host).trim().replace(/^https?:\/\//,'').replace(/:\d+$/,'');
    localStorage.setItem('jarvisPhoneHost',state.phoneHost);
    $('syncStatus').textContent='Buscando Jarvis Mobile…';
    return rawPhoneFetch('/ping',false)
      .then(function(){return rawPhoneFetch('/pair?pin='+encodeURIComponent(pin),false);})
      .then(function(j){
        if(!j.token)throw new Error('No llegó token de emparejamiento');
        state.phoneToken=j.token;localStorage.setItem('jarvisPhoneToken',j.token);
        return refreshPhoneWidgets();
      });
  }

  function refreshPhoneWidgets(){
    if(!state.phoneHost||!state.phoneToken){
      $('syncStatus').textContent='Sin teléfono enlazado';
      $('phoneSummary').innerHTML='';
      $('widgetMessages').textContent='Sin sincronizar';
      $('widgetCalendar').textContent='Sin sincronizar';
      $('widgetHome').textContent='Homey sin sincronizar';
      return Promise.resolve();
    }
    $('syncHost').value=state.phoneHost;
    $('syncStatus').textContent='Comprobando teléfono…';
    return Promise.all([
      phoneFetch('/permissions').catch(function(){return{};}),
      phoneFetch('/incoming-call').catch(function(){return{};}),
      phoneFetch('/unread').catch(function(){return{};}),
      phoneFetch('/agenda').catch(function(){return{};}),
      phoneFetch('/domotics').catch(function(){return{};})
    ]).then(function(r){
      var perms=r[0]||{},call=(r[1]&&r[1].call)||{},unread=r[2]||{},agenda=r[3]||{},home=r[4]||{};
      var messages=(unread.items||[]),events=(agenda.events||[]),devices=(home.items||home.devices||[]);
      $('widgetCall').textContent=Object.keys(call).length?(call.name||call.number||call.state||'Llamada activa'):'Sin llamada activa';
      $('widgetMessages').textContent=messages.length?messages.length+' pendientes':'Sin mensajes pendientes';
      if(events.length){
        var e=events[0],d=e.begin?new Date(e.begin):null;
        $('widgetCalendar').textContent=(d?d.toLocaleTimeString('es-ES',{hour:'2-digit',minute:'2-digit'})+' · ':'')+(e.title||'Evento');
      }else $('widgetCalendar').textContent='Sin citas próximas';
      $('widgetHome').textContent=devices.length?devices.length+' dispositivos Homey':'Homey sin datos';
      $('syncStatus').textContent='✓ Jarvis Mobile conectado';
      $('phoneSummary').innerHTML=
        '<div class="phone-chip"><b>Mensajes</b><span>'+messages.length+' pendientes</span></div>'+
        '<div class="phone-chip"><b>Llamadas</b><span>'+esc($('widgetCall').textContent)+'</span></div>'+
        '<div class="phone-chip"><b>Agenda</b><span>'+events.length+' próximos</span></div>'+
        '<div class="phone-chip"><b>Domótica</b><span>'+devices.length+' dispositivos</span></div>'+
        '<div class="phone-chip"><b>SMS</b><span>'+(perms.readSms?'Permitido':'Sin permiso')+'</span></div>'+
        '<div class="phone-chip"><b>Contactos</b><span>'+(perms.contacts?'Permitido':'Sin permiso')+'</span></div>';
    }).catch(function(e){$('syncStatus').textContent='Sin conexión con Jarvis Mobile';throw e;});
  }

  function renderNow(tab,data){
    state.nowTab=tab;
    var html='';
    if(tab==='notifications'){
      var items=(data&&data.items)||[];
      html=items.length?items.slice(0,20).map(function(n){return '<div class="now-item"><b>'+esc((n.source||'Aviso')+' · '+(n.from||''))+'</b><span>'+esc(n.text||'')+'</span></div>';}).join(''):'<div class="empty-state">Sin notificaciones disponibles.</div>';
    }else if(tab==='agenda'){
      var ev=(data&&data.events)||[];
      html=ev.length?ev.slice(0,16).map(function(e){var d=e.begin?new Date(e.begin):null;return '<div class="now-item"><b>'+esc(e.title||'Evento')+'</b><span>'+esc(d?d.toLocaleString('es-ES',{weekday:'short',hour:'2-digit',minute:'2-digit'}):'')+'</span></div>';}).join(''):'<div class="empty-state">Sin agenda sincronizada.</div>';
    }else html='<div class="empty-state">Las tareas se mostrarán aquí cuando estén sincronizadas.</div>';
    $('nowContent').innerHTML=html;
  }
  function loadNow(tab){
    if(tab==='notifications')phoneFetch('/unread').then(function(j){renderNow(tab,j);}).catch(function(){renderNow(tab,null);});
    else if(tab==='agenda')phoneFetch('/agenda').then(function(j){renderNow(tab,j);}).catch(function(){renderNow(tab,null);});
    else renderNow(tab,null);
  }

  function renderChats(){
    $('chatList').innerHTML=state.chats.map(function(c){return '<div tabindex="0" data-focusable="true" data-action="open-chat" data-id="'+esc(c.id)+'" class="chat-item '+(c.id===state.currentChatId?'active':'')+'"><b>'+esc(c.title||'Chat')+'</b><span>'+esc(c.preview||'Continuar conversación')+'</span></div>';}).join('');
  }
  function newChat(){
    var id='tv-'+Date.now();state.currentChatId=id;
    state.chats.unshift({id:id,title:'Nuevo chat',preview:'Conversación nueva',updated:Date.now()});
    saveChats();localStorage.setItem('jarvisCurrentChat',id);renderChats();showJarvis('Nuevo chat preparado');
  }

  function openDrawer(id){
    closeDrawers();var el=$(id);if(el)el.className=el.className+' open';
    if(id==='chatDrawer')renderChats();
    if(id==='nowDrawer')loadNow(state.nowTab);
    if(id==='appsDrawer')renderAppsDrawer();
    if(id==='syncDrawer')refreshPhoneWidgets();
  }
  function closeDrawers(){
    ['chatDrawer','nowDrawer','syncDrawer','appsDrawer'].forEach(function(id){var el=$(id);if(el)el.className=el.className.replace(/\sopen/g,'');});
  }

  function updateClock(){
    var d=new Date();
    $('clock').textContent=d.toLocaleTimeString('es-ES',{hour:'2-digit',minute:'2-digit'});
    $('date').textContent=d.toLocaleDateString('es-ES',{weekday:'short',day:'numeric',month:'short'});
  }

  function showJarvis(msg){
    var overlay=$('jarvisOverlay');overlay.className='jarvis-overlay show';$('conversation').textContent=msg||'¿Qué necesitas?';
    setTimeout(function(){overlay.className='jarvis-overlay';},4200);
  }

  function focusables(){return Array.prototype.slice.call(document.querySelectorAll('[data-focusable="true"]')).filter(function(el){return el.offsetParent!==null;});}
  function focusAt(index){var list=focusables();if(!list.length)return;state.focusIndex=(index+list.length)%list.length;list[state.focusIndex].focus();}
  function move(key){var list=focusables(),i=list.indexOf(document.activeElement);if(i<0)i=0;if(key==='ArrowRight')i++;if(key==='ArrowLeft')i--;if(key==='ArrowDown')i+=4;if(key==='ArrowUp')i-=4;focusAt(i);}

  document.addEventListener('focusin',function(e){
    var el=e.target;
    if(!el||!el.getAttribute)return;
    var action=el.getAttribute('data-action');
    if(action==='provider')loadProvider(el.getAttribute('data-name'));
    if(action==='media'){
      var r=parseInt(el.getAttribute('data-row'),10)||0,i=parseInt(el.getAttribute('data-index'),10)||0,row=state.mediaRows[r],item=row&&row.items&&row.items[i];
      if(item)setHero(item,row.title);
    }
  });

  document.addEventListener('click',function(e){
    var el=e.target;while(el&&el!==document.body&&(!el.getAttribute||!el.getAttribute('data-action')))el=el.parentNode;
    if(!el||!el.getAttribute)return;
    var action=el.getAttribute('data-action');

    if(action==='provider'){
      var name=el.getAttribute('data-name'),id=el.getAttribute('data-id');
      if(state.selectedProvider===name){launchById(id,name);}else{loadProvider(name);}
      return;
    }
    if(action==='play-selected'){
      if(state.selectedProvider){var a=findInstalled(state.selectedProvider);launchById(a&&a.id,state.selectedProvider);}else toast('Selecciona primero un proveedor');
      return;
    }
    if(action==='details-selected'){toast(state.selectedMedia?state.selectedMedia.title:'Sin contenido seleccionado');return;}
    if(action==='apps'){openDrawer('appsDrawer');return;}
    if(action==='sync'){openDrawer('syncDrawer');return;}
    if(action==='sync-close'||action==='apps-close'||action==='drawer-close'){closeDrawers();return;}
    if(action==='pair-phone'){
      var host=$('syncHost').value,pin=$('syncPin').value;
      if(!host||!/^\d{4}$/.test(pin)){toast('Introduce IP y PIN de 4 dígitos');return;}
      pairPhone(host,pin).then(function(){toast('Jarvis Mobile sincronizado');}).catch(function(e){toast('No se pudo emparejar: '+safe(e.message));});return;
    }
    if(action==='launch-id'){launchById(el.getAttribute('data-id'),el.getAttribute('data-title'));return;}
    if(action==='drawer'){openDrawer('nowDrawer');return;}
    if(action==='tab'){loadNow(el.getAttribute('data-tab'));return;}
    if(action==='chats'){openDrawer('chatDrawer');return;}
    if(action==='new-chat'){newChat();return;}
    if(action==='open-chat'){state.currentChatId=el.getAttribute('data-id');localStorage.setItem('jarvisCurrentChat',state.currentChatId);renderChats();showJarvis('Continuando conversación');return;}
    if(action==='voice'){$('jarvisState').textContent='Escuchando…';showJarvis('Jarvis listo para escuchar');return;}
    if(action==='widget'){
      var w=el.getAttribute('data-widget');
      if(w==='messages'){openDrawer('nowDrawer');loadNow('notifications');}
      else if(w==='calendar'||w==='tasks'){openDrawer('nowDrawer');loadNow(w==='calendar'?'agenda':'tasks');}
      else if(w==='home'||w==='call'){openDrawer('syncDrawer');}
      else toast('Widget '+w);
      return;
    }
    if(action==='media'){
      var rr=parseInt(el.getAttribute('data-row'),10)||0,ii=parseInt(el.getAttribute('data-index'),10)||0,row2=state.mediaRows[rr],item2=row2&&row2.items&&row2.items[ii];
      if(item2)setHero(item2,row2.title);
    }
  });

  document.addEventListener('keydown',function(e){
    if(['ArrowLeft','ArrowRight','ArrowUp','ArrowDown'].indexOf(e.key)>=0){e.preventDefault();move(e.key);return;}
    if(e.key==='Enter'){if(document.activeElement&&document.activeElement.click)document.activeElement.click();return;}
    if(e.key==='Escape'||e.keyCode===461){e.preventDefault();if(document.querySelector('.side-drawer.open'))closeDrawers();else window.close();}
  });

  function init(){
    updateClock();setInterval(updateClock,30000);
    renderRows();renderChats();
    if($('syncHost'))$('syncHost').value=state.phoneHost;
    loadCatalog();
    loadInstalledApps().then(function(){renderQuickApps();renderAppsDrawer();focusAt(0);});
    refreshPhoneWidgets();
  }

  init();
})();