(function(){
  'use strict';

  var BACKEND=localStorage.getItem('jarvisBackend')||'https://chatgpt-tv2.vercel.app';
  var state={
    installedApps:[],
    currentProvider:'Netflix',
    providerRows:[],
    selectedMedia:null,
    focusIndex:0,
    nowTab:'notifications',
    phoneHost:localStorage.getItem('jarvisPhoneHost')||'',
    phoneToken:localStorage.getItem('jarvisPhoneToken')||'',
    youtubeToken:localStorage.getItem('jarvisYouTubeToken')||'',
    chats:loadChats(),
    currentChatId:localStorage.getItem('jarvisCurrentChat')||'tv-main'
  };

  var APPS=[
    {name:'Netflix',ids:['netflix'],slug:'netflix',description:'Series, películas y novedades de Netflix.'},
    {name:'Prime Video',ids:['amazon'],slug:'primevideo',description:'Películas, series y estrenos incluidos con Prime Video.'},
    {name:'YouTube',ids:['youtube.leanback.v4'],slug:'youtube',description:'Recomendados, subidas recientes, suscripciones y Ver más tarde.'},
    {name:'Disney+',ids:['com.disney.disneyplus-prod'],slug:'disneyplus',description:'Disney, Pixar, Marvel, Star Wars y National Geographic.'},
    {name:'Apple TV',ids:['com.apple.appletv'],slug:'appletv',description:'Apple Originals, películas y series de Apple TV.'},
    {name:'TV',ids:['com.webos.app.livetv','com.webos.service.iepg'],slug:'lg',description:'Televisión en directo, EPG y canales.'}
  ];
  var LOGO_BASE='https://cdn.simpleicons.org/';

  function $(id){return document.getElementById(id);}
  function safe(v){return v===null||typeof v==='undefined'?'':String(v);}
  function esc(v){return safe(v).replace(/[&<>"']/g,function(m){return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[m];});}
  function toast(msg){var el=$('toast');if(!el)return;el.textContent=msg;el.className='toast show';setTimeout(function(){el.className='toast';},2400);}
  function jsonParse(s,f){try{return JSON.parse(s);}catch(e){return f;}}
  function loadChats(){var a=jsonParse(localStorage.getItem('jarvisChats')||'[]',[]);if(!a.length)a=[{id:'tv-main',title:'Jarvis TV',preview:'Conversación principal'}];return a;}
  function saveChats(){localStorage.setItem('jarvisChats',JSON.stringify(state.chats));}

  function timeoutFetch(url,opts,ms){
    return new Promise(function(resolve,reject){
      var done=false,t=setTimeout(function(){if(done)return;done=true;reject(new Error('timeout'));},ms||7000);
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

  function appDef(name){for(var i=0;i<APPS.length;i++)if(APPS[i].name===name)return APPS[i];return null;}
  function findInstalled(name){
    var d=appDef(name),i,a,q=safe(name).toLowerCase();
    for(i=0;i<state.installedApps.length;i++){a=state.installedApps[i];if(a.title.toLowerCase()===q)return a;}
    for(i=0;i<state.installedApps.length;i++){a=state.installedApps[i];if(a.title.toLowerCase().indexOf(q)>=0||a.id.toLowerCase().indexOf(q)>=0)return a;}
    if(d&&d.ids.length)return{id:d.ids[0],title:name,icon:''};
    return null;
  }
  function appLogo(name){
    var found=findInstalled(name);
    if(found&&found.icon)return found.icon;
    var d=appDef(name);
    return d?LOGO_BASE+d.slug:'';
  }

  function loadInstalledApps(){
    return luna('luna://com.webos.applicationManager/listApps',{}).then(function(r){
      state.installedApps=(r.apps||[]).map(function(a){return{id:a.id,title:a.title||a.id,icon:a.largeIcon||a.icon||''};}).filter(function(a){return a.id;});
      state.installedApps.sort(function(a,b){return a.title.localeCompare(b.title);});
    }).catch(function(){state.installedApps=[];});
  }

  function launchById(id,label){
    if(!id){toast('No encuentro '+label+' en esta TV');return;}
    luna('luna://com.webos.applicationManager/launch',{id:id}).then(function(r){if(r&&r.returnValue===false)toast('No se pudo abrir '+label);}).catch(function(){toast('No se pudo abrir '+label);});
  }

  function renderAppCards(){
    $('appCards').innerHTML=APPS.map(function(a){
      var logo=appLogo(a.name);
      return '<article class="app-tile" tabindex="0" data-focusable="true" data-action="provider" data-name="'+esc(a.name)+'">'+
        (logo?'<img src="'+esc(logo)+'" alt="'+esc(a.name)+'">':'<div class="app-logo-fallback">'+esc(a.name.charAt(0))+'</div>')+
        '<div><b>'+esc(a.name)+'</b><span>'+esc(a.description)+'</span></div>'+
      '</article>';
    }).join('');
  }

  function mediaImage(x){return (x&&x.episode&&x.episode.image)||x.image||'';}
  function mediaMeta(x){
    var bits=[];
    if(x&&x.network)bits.push(x.network);
    if(x&&x.channel)bits.push(x.channel);
    if(x&&x.premiered)bits.push(String(x.premiered).slice(0,4));
    if(x&&x.publishedAt)bits.push(String(x.publishedAt).slice(0,10));
    if(x&&x.rating)bits.push('★ '+x.rating);
    if(x&&x.genres&&x.genres.length)bits.push(x.genres.slice(0,2).join(' · '));
    if(x&&x.episode&&x.episode.season)bits.push('T'+x.episode.season+(x.episode.number?' · E'+x.episode.number:''));
    return bits;
  }
  function setHero(x,rowTitle){
    state.selectedMedia=x||null;
    $('heroTitle').textContent=x&&x.title?x.title:'Selecciona algo para ver';
    $('heroProvider').textContent=rowTitle||state.currentProvider||'Jarvis TV';
    $('heroSummary').textContent=(x&&x.summary?x.summary:'Aquí aparecerá la información ampliada de la película, serie o episodio seleccionado.').slice(0,280);
    $('heroMeta').innerHTML=mediaMeta(x||{}).map(function(m){return'<span>'+esc(m)+'</span>';}).join('');
    var img=mediaImage(x||{});
    $('heroBackdrop').style.backgroundImage=img?'url("'+img.replace(/"/g,'%22')+'")':'none';
  }

  function rowCard(x,ri,ci,row){
    var img=mediaImage(x),progress=row.id==='recentplay'?45:0;
    return '<article class="media-card" tabindex="0" data-focusable="true" data-action="media" data-row="'+ri+'" data-index="'+ci+'">'+
      (img?'<img src="'+esc(img)+'" alt="">':'')+
      '<div class="shade"></div><div class="title">'+esc(x.title||'')+'</div>'+
      (progress?'<div class="progress"><i style="width:'+progress+'%"></i></div>':'')+
    '</article>';
  }

  function renderProviderRows(){
    var rows=state.providerRows||[];
    if(!rows.length){
      $('providerRows').innerHTML='<div class="empty-state">No hay contenido disponible para esta sección.</div>';
      return;
    }
    $('providerRows').innerHTML=rows.slice(0,3).map(function(row,ri){
      var items=(row.items||[]);
      var body=items.length?'<div class="provider-track">'+items.slice(0,8).map(function(x,ci){return rowCard(x,ri,ci,row);}).join('')+'</div>':
        '<div class="empty-state">'+esc(row.emptyText||'Sin contenido todavía.')+'</div>';
      return '<div class="provider-row"><div class="provider-row-head"><h4>'+esc(row.title)+'</h4><span>'+esc(row.personal?'Cuenta personal':'')+'</span></div>'+body+'</div>';
    }).join('');
    var first=rows[0]&&rows[0].items&&rows[0].items[0];
    if(first)setHero(first,rows[0].title);
  }

  function splitProviderRows(name,items){
    var arr=items||[];
    var recent=arr.slice().sort(function(a,b){return safe(b.premiered||b.publishedAt).localeCompare(safe(a.premiered||a.publishedAt));});
    var rated=arr.slice().sort(function(a,b){return Number(b.rating||0)-Number(a.rating||0);});
    return [
      {id:'featured',title:'Destacados',items:arr.slice(0,12)},
      {id:'recent',title:'Novedades y recién añadidos',items:recent.slice(0,12)},
      {id:'rated',title:'Más valorados',items:rated.slice(0,12)}
    ];
  }

  function selectProvider(name){
    state.currentProvider=name;
    var d=appDef(name),logo=appLogo(name);
    $('providerName').textContent=name;
    $('providerDescription').textContent=d?d.description:'Contenido del proveedor.';
    $('selectedAppDescription').textContent=d?d.description:'';
    $('providerLogo').style.backgroundImage=logo?'url("'+logo.replace(/"/g,'%22')+'")':'none';
    $('providerKicker').textContent=name==='YouTube'?'YOUTUBE':'CONTENIDO';
    if(name==='YouTube'){loadYouTube();return;}
    if(name==='TV'){
      state.providerRows=[
        {id:'live',title:'En directo',items:[] ,emptyText:'La guía EPG se conectará con los canales de la TV.'},
        {id:'recent',title:'Visto recientemente',items:[],emptyText:'Se mostrará el historial cuando Jarvis TV esté sincronizado.'},
        {id:'recommended',title:'Recomendado para ti',items:[],emptyText:'Jarvis generará recomendaciones con tu historial.'}
      ];
      renderProviderRows();return;
    }
    timeoutFetch(BACKEND+'/api/harvest/catalog?provider='+encodeURIComponent(name),{},9000)
      .then(function(r){if(!r.ok)throw new Error('HTTP '+r.status);return r.json();})
      .then(function(j){state.providerRows=splitProviderRows(name,j.items||[]);renderProviderRows();})
      .catch(function(){state.providerRows=[{title:'Catálogo',items:[],emptyText:'No se pudo cargar el catálogo de '+name+'.'}];renderProviderRows();});
  }

  function loadYouTube(){
    var headers={};if(state.youtubeToken)headers.Authorization='Bearer '+state.youtubeToken;
    timeoutFetch(BACKEND+'/api/harvest/youtube?mode=home',{headers:headers},9000)
      .then(function(r){if(!r.ok)throw new Error('HTTP '+r.status);return r.json();})
      .then(function(j){
        var rows=j.rows||[];
        if(!j.connected){
          rows.unshift({id:'foryou',title:'Pensado para ti',personal:true,items:[],emptyText:'Conecta tu cuenta de YouTube para recomendaciones personales.'});
          rows.push({id:'watchlater',title:'Ver más tarde',personal:true,items:[],emptyText:'Conecta YouTube para mostrar tu lista Ver más tarde.'});
        }
        if(!rows.length)rows=[{title:'YouTube',items:[],emptyText:'Configura YouTube API / OAuth para cargar vídeos.'}];
        state.providerRows=rows;renderProviderRows();
      }).catch(function(){state.providerRows=[{title:'YouTube',items:[],emptyText:'YouTube todavía no está conectado.'}];renderProviderRows();});
  }

  function renderContext(){
    $('contextCards').innerHTML=
      '<div class="context-card"><b>Continuar viendo</b><span>'+(state.phoneToken?'Sincronización disponible desde Jarvis Mobile.':'Sincroniza el teléfono para recuperar actividad reciente.')+'</span></div>'+
      '<div class="context-card"><b>Reproducción reciente</b><span>Se añadirá aquí el último contenido abierto por Jarvis.</span></div>'+
      '<div class="context-card"><b>'+esc(state.currentProvider)+'</b><span>Pulsa OK de nuevo sobre la app para abrirla.</span></div>';
  }

  function phoneUrl(path){if(!state.phoneHost)return'';return'http://'+state.phoneHost+':8765'+path;}
  function rawPhoneFetch(path,auth){
    var url=phoneUrl(path);if(!url)return Promise.reject(new Error('phone_not_configured'));
    var headers={};if(auth!==false&&state.phoneToken)headers.Authorization='Bearer '+state.phoneToken;
    return timeoutFetch(url,{headers:headers},4500).then(function(r){return r.json().catch(function(){return{};}).then(function(j){if(!r.ok)throw new Error(j.error||('HTTP '+r.status));return j;});});
  }
  function phoneFetch(path){return rawPhoneFetch(path,true);}
  function pairPhone(host,pin){
    state.phoneHost=safe(host).trim().replace(/^https?:\/\//,'').replace(/:\d+$/,'');localStorage.setItem('jarvisPhoneHost',state.phoneHost);
    $('syncStatus').textContent='Buscando Jarvis Mobile…';
    return rawPhoneFetch('/ping',false).then(function(){return rawPhoneFetch('/pair?pin='+encodeURIComponent(pin),false);}).then(function(j){if(!j.token)throw new Error('Sin token');state.phoneToken=j.token;localStorage.setItem('jarvisPhoneToken',j.token);return refreshPhoneWidgets();});
  }
  function refreshPhoneWidgets(){
    if(!state.phoneHost||!state.phoneToken){$('syncStatus').textContent='Sin teléfono enlazado';return Promise.resolve();}
    $('syncHost').value=state.phoneHost;
    return Promise.all([
      phoneFetch('/permissions').catch(function(){return{};}),
      phoneFetch('/incoming-call').catch(function(){return{};}),
      phoneFetch('/unread').catch(function(){return{};}),
      phoneFetch('/agenda').catch(function(){return{};}),
      phoneFetch('/domotics').catch(function(){return{}})
    ]).then(function(r){
      var perms=r[0]||{},call=(r[1]&&r[1].call)||{},unread=r[2]||{},agenda=r[3]||{},home=r[4]||{};
      var msgs=unread.items||[],events=agenda.events||[],devices=home.items||home.devices||[];
      $('widgetCall').textContent=Object.keys(call).length?(call.name||call.number||call.state||'Llamada activa'):'Sin llamada activa';
      $('widgetMessages').textContent=msgs.length?msgs.length+' pendientes':'Sin mensajes pendientes';
      if(events.length){var e=events[0],d=e.begin?new Date(e.begin):null;$('widgetCalendar').textContent=(d?d.toLocaleTimeString('es-ES',{hour:'2-digit',minute:'2-digit'})+' · ':'')+(e.title||'Evento');}else $('widgetCalendar').textContent='Sin citas próximas';
      $('widgetHome').textContent=devices.length?devices.length+' dispositivos':'Homey sin datos';
      $('syncStatus').textContent='✓ Jarvis Mobile conectado';
      $('phoneSummary').innerHTML='<div class="phone-chip"><b>Mensajes</b><span>'+msgs.length+' pendientes</span></div><div class="phone-chip"><b>Agenda</b><span>'+events.length+' eventos</span></div><div class="phone-chip"><b>Domótica</b><span>'+devices.length+' dispositivos</span></div><div class="phone-chip"><b>SMS</b><span>'+(perms.readSms?'Permitido':'Sin permiso')+'</span></div>';
      renderContext();
    }).catch(function(){$('syncStatus').textContent='Sin conexión con Jarvis Mobile';});
  }

  function renderNow(tab,data){
    state.nowTab=tab;var html='';
    if(tab==='notifications'){var items=(data&&data.items)||[];html=items.length?items.slice(0,20).map(function(n){return'<div class="now-item"><b>'+esc((n.source||'Aviso')+' · '+(n.from||''))+'</b><span>'+esc(n.text||'')+'</span></div>';}).join(''):'<div class="empty-state">Sin notificaciones.</div>';}
    else if(tab==='agenda'){var ev=(data&&data.events)||[];html=ev.length?ev.slice(0,16).map(function(e){var d=e.begin?new Date(e.begin):null;return'<div class="now-item"><b>'+esc(e.title||'Evento')+'</b><span>'+esc(d?d.toLocaleString('es-ES',{weekday:'short',hour:'2-digit',minute:'2-digit'}):'')+'</span></div>';}).join(''):'<div class="empty-state">Sin agenda.</div>';}
    else html='<div class="empty-state">Las tareas se mostrarán al sincronizarlas.</div>';
    $('nowContent').innerHTML=html;
  }
  function loadNow(tab){if(tab==='notifications')phoneFetch('/unread').then(function(j){renderNow(tab,j);}).catch(function(){renderNow(tab,null);});else if(tab==='agenda')phoneFetch('/agenda').then(function(j){renderNow(tab,j);}).catch(function(){renderNow(tab,null);});else renderNow(tab,null);}

  function renderChats(){$('chatList').innerHTML=state.chats.map(function(c){return'<div tabindex="0" data-focusable="true" data-action="open-chat" data-id="'+esc(c.id)+'" class="chat-item"><b>'+esc(c.title||'Chat')+'</b><span>'+esc(c.preview||'Continuar conversación')+'</span></div>';}).join('');}
  function newChat(){var id='tv-'+Date.now();state.currentChatId=id;state.chats.unshift({id:id,title:'Nuevo chat',preview:'Conversación nueva'});saveChats();localStorage.setItem('jarvisCurrentChat',id);renderChats();showJarvis('Nuevo chat preparado');}

  function renderAppsDrawer(){
    var all=state.installedApps.length?state.installedApps:APPS.map(function(a){return{id:a.ids[0],title:a.name,icon:''};});
    $('appsGrid').innerHTML=all.slice(0,60).map(function(a){return'<button tabindex="0" data-focusable="true" class="installed-app" data-action="launch-id" data-id="'+esc(a.id)+'" data-title="'+esc(a.title)+'">'+(a.icon?'<img class="installed-icon" src="'+esc(a.icon)+'" alt="">':'')+'<b>'+esc(a.title)+'</b><span>'+esc(a.id)+'</span></button>';}).join('');
  }

  function openDrawer(id){closeDrawers();var el=$(id);if(el)el.className=el.className+' open';if(id==='chatDrawer')renderChats();if(id==='appsDrawer')renderAppsDrawer();if(id==='syncDrawer')refreshPhoneWidgets();}
  function closeDrawers(){['chatDrawer','nowDrawer','syncDrawer','appsDrawer','remoteDrawer'].forEach(function(id){var el=$(id);if(el)el.className=el.className.replace(/\sopen/g,'');});}

  function updateClock(){var d=new Date();$('clock').textContent=d.toLocaleTimeString('es-ES',{hour:'2-digit',minute:'2-digit'});$('date').textContent=d.toLocaleDateString('es-ES',{weekday:'short',day:'numeric',month:'short'});}
  function showJarvis(msg){var el=$('jarvisOverlay');el.className='jarvis-overlay show';$('conversation').textContent=msg||'¿Qué necesitas?';setTimeout(function(){el.className='jarvis-overlay';},4200);}

  function focusables(){return Array.prototype.slice.call(document.querySelectorAll('[data-focusable="true"]')).filter(function(el){return el.offsetParent!==null;});}
  function focusAt(index){var list=focusables();if(!list.length)return;state.focusIndex=(index+list.length)%list.length;list[state.focusIndex].focus();}
  function center(el){var r=el.getBoundingClientRect();return{x:r.left+r.width/2,y:r.top+r.height/2};}
  function move(key){
    var list=focusables(),active=document.activeElement;if(list.indexOf(active)<0){focusAt(0);return;}var a=center(active),best=null,bestScore=1e9;
    list.forEach(function(el){if(el===active)return;var b=center(el),dx=b.x-a.x,dy=b.y-a.y,p=0,c=0;if(key==='ArrowRight'){if(dx<=8)return;p=dx;c=Math.abs(dy);}else if(key==='ArrowLeft'){if(dx>=-8)return;p=-dx;c=Math.abs(dy);}else if(key==='ArrowDown'){if(dy<=8)return;p=dy;c=Math.abs(dx);}else if(key==='ArrowUp'){if(dy>=-8)return;p=-dy;c=Math.abs(dx);}else return;var score=p+c*2.4;if(c>p*2.5)score+=c*2;if(score<bestScore){bestScore=score;best=el;}});if(best)best.focus();
  }

  function launchParams(){try{if(window.PalmSystem&&PalmSystem.launchParams)return JSON.parse(PalmSystem.launchParams||'{}');}catch(e){}return{};}
  function activateVoiceMode(){if($('jarvisState'))$('jarvisState').textContent='Escuchando…';showJarvis('Jarvis activado desde el botón Voice');}

  document.addEventListener('focusin',function(e){
    var el=e.target;if(!el||!el.getAttribute)return;var action=el.getAttribute('data-action');
    if(action==='provider'){var name=el.getAttribute('data-name');if(name!==state.currentProvider){selectProvider(name);renderContext();}}
    if(action==='media'){var r=parseInt(el.getAttribute('data-row'),10)||0,i=parseInt(el.getAttribute('data-index'),10)||0,row=state.providerRows[r],item=row&&row.items&&row.items[i];if(item)setHero(item,row.title);}
  });

  document.addEventListener('click',function(e){
    var el=e.target;while(el&&el!==document.body&&(!el.getAttribute||!el.getAttribute('data-action')))el=el.parentNode;if(!el||!el.getAttribute)return;var action=el.getAttribute('data-action');
    if(action==='provider'){var name=el.getAttribute('data-name');if(state.currentProvider===name){var a=findInstalled(name);launchById(a&&a.id,name);}else{selectProvider(name);renderContext();}return;}
    if(action==='play-selected'){var a2=findInstalled(state.currentProvider);launchById(a2&&a2.id,state.currentProvider);return;}
    if(action==='details-selected'){toast(state.selectedMedia?state.selectedMedia.title:'Sin contenido seleccionado');return;}
    if(action==='apps'){openDrawer('appsDrawer');return;}
    if(action==='chats'){openDrawer('chatDrawer');return;}
    if(action==='remote-debug'){openDrawer('remoteDrawer');return;}
    if(action==='remote-close'||action==='apps-close'||action==='drawer-close'||action==='sync-close'){closeDrawers();return;}
    if(action==='sync'){openDrawer('syncDrawer');return;}
    if(action==='pair-phone'){var host=$('syncHost').value,pin=$('syncPin').value;if(!host||!/^\d{4}$/.test(pin)){toast('Introduce IP y PIN');return;}pairPhone(host,pin).then(function(){toast('Jarvis Mobile sincronizado');}).catch(function(e){toast('No se pudo emparejar: '+safe(e.message));});return;}
    if(action==='launch-id'){launchById(el.getAttribute('data-id'),el.getAttribute('data-title'));return;}
    if(action==='drawer'){openDrawer('nowDrawer');loadNow(state.nowTab);return;}
    if(action==='tab'){loadNow(el.getAttribute('data-tab'));return;}
    if(action==='new-chat'){newChat();return;}
    if(action==='open-chat'){state.currentChatId=el.getAttribute('data-id');localStorage.setItem('jarvisCurrentChat',state.currentChatId);showJarvis('Continuando conversación');return;}
    if(action==='voice'){activateVoiceMode();return;}
    if(action==='widget'){var w=el.getAttribute('data-widget');if(w==='messages'){openDrawer('nowDrawer');loadNow('notifications');}else if(w==='calendar'||w==='tasks'){openDrawer('nowDrawer');loadNow(w==='calendar'?'agenda':'tasks');}else if(w==='home'||w==='call'){openDrawer('syncDrawer');}else toast('Widget '+w);return;}
    if(action==='media'){var rr=parseInt(el.getAttribute('data-row'),10)||0,ii=parseInt(el.getAttribute('data-index'),10)||0,row2=state.providerRows[rr],item2=row2&&row2.items&&row2.items[ii];if(item2)setHero(item2,row2.title);}
  });

  document.addEventListener('keydown',function(e){
    if($('remoteKey')){$('remoteKey').textContent=e.key||('Key '+e.keyCode);$('remoteKeyName').textContent=e.key||'—';$('remoteKeyCode').textContent=String(e.keyCode||0);$('remoteWhich').textContent=String(e.which||0);$('remoteTime').textContent=new Date().toLocaleTimeString('es-ES');}
    if(['ArrowLeft','ArrowRight','ArrowUp','ArrowDown'].indexOf(e.key)>=0){e.preventDefault();move(e.key);return;}
    if(e.key==='Enter'){if(document.activeElement&&document.activeElement.click)document.activeElement.click();return;}
    if(e.key==='Escape'||e.keyCode===461){e.preventDefault();if(document.querySelector('.drawer.open'))closeDrawers();else window.close();}
  });

  function init(){
    updateClock();setInterval(updateClock,30000);
    renderChats();if($('syncHost'))$('syncHost').value=state.phoneHost;
    loadInstalledApps().then(function(){renderAppCards();renderAppsDrawer();selectProvider('Netflix');renderContext();focusAt(0);});
    refreshPhoneWidgets();
    var lp=launchParams();if(lp&&lp.jarvisVoice===true)setTimeout(activateVoiceMode,350);
  }
  init();
})();