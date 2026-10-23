(function(){
  'use strict';

  var BACKEND=localStorage.getItem('jarvisBackend')||'https://chatgpt-tv2.vercel.app';
  var state={
    installedApps:[],
    appStatus:{},
    selectedApp:'Netflix',
    focusIndex:0,
    nowTab:'notifications',
    media:[],
    chats:loadChats(),
    currentChatId:localStorage.getItem('jarvisCurrentChat')||'tv-main',
    phoneHost:localStorage.getItem('jarvisPhoneHost')||'',
    phoneToken:localStorage.getItem('jarvisPhoneToken')||''
  };

  var APP_IDS={
    'Netflix':['netflix'],
    'Prime Video':['amazon'],
    'YouTube':['youtube.leanback.v4'],
    'Disney+':['com.disney.disneyplus-prod'],
    'Apple TV':['com.apple.appletv'],
    'TV':['com.webos.app.livetv','com.webos.service.iepg']
  };

  function $(id){return document.getElementById(id);}
  function safe(v){return v===null||typeof v==='undefined'?'':String(v);}
  function esc(v){return safe(v).replace(/[&<>"']/g,function(m){return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[m];});}
  function toast(msg){var el=$('toast');el.textContent=msg;el.className='toast show';setTimeout(function(){el.className='toast';},2400);}
  function jsonParse(s,fallback){try{return JSON.parse(s);}catch(e){return fallback;}}
  function saveChats(){localStorage.setItem('jarvisChats',JSON.stringify(state.chats));}
  function loadChats(){
    var a=jsonParse(localStorage.getItem('jarvisChats')||'[]',[]);
    if(!a.length)a=[{id:'tv-main',title:'Jarvis TV',preview:'Conversación principal',updated:Date.now()}];
    return a;
  }
  function timeoutFetch(url,opts,ms){
    return new Promise(function(resolve,reject){
      var done=false,t=setTimeout(function(){if(done)return;done=true;reject(new Error('timeout'));},ms||5000);
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

  var heroTiles=[
    {cls:'hero-primary',kicker:'Continuar viendo',title:'Tu siguiente episodio',body:'Jarvis reunirá aquí lo que estabas viendo en cualquier dispositivo.',badge:'Sincronizado',meta:'Retomar en 1 toque'},
    {cls:'',kicker:'Ahora',title:'Notificaciones',body:'Mensajes, correo y alertas del móvil.',badge:'Now',metric:'0'},
    {cls:'',kicker:'Agenda',title:'Próximas citas',body:'Calendario, recordatorios y tareas.',badge:'Hoy',metric:'—'},
    {cls:'hero-wide',kicker:'TV en directo',title:'Qué ver ahora',body:'Programas, episodios y recomendaciones con imágenes reales.',badge:'En directo',meta:'TVmaze + Jarvis'}
  ];

  var streaming=[
    {name:'Netflix',cls:'app-netflix'},
    {name:'Prime Video',cls:'app-prime'},
    {name:'YouTube',cls:'app-youtube'},
    {name:'Disney+',cls:'app-disney'},
    {name:'Apple TV',cls:'app-apple'},
    {name:'TV',cls:'app-tv'}
  ];

  var shelfData={
    'Netflix':['Continuar viendo','Nuevo episodio','Mi lista','Tendencias'],
    'Prime Video':['Seguir viendo','Estrenos','Incluido con Prime','Mi lista'],
    'YouTube':['Suscripciones','Ver más tarde','Últimos vídeos','Noticias'],
    'Disney+':['Seguir viendo','Novedades','Marvel','Star Wars'],
    'Apple TV':['Continuar','Apple Originals','Películas','Mi lista'],
    'TV':['Ahora en TV','Siguiente','Favoritos','Grabaciones']
  };

  function renderHero(){
    $('heroGrid').innerHTML=heroTiles.map(function(t,i){
      return '<article tabindex="0" data-focusable="true" data-action="hero" data-index="'+i+'" class="tile '+t.cls+'">'+
        '<span class="kicker">'+esc(t.kicker)+'</span>'+
        '<span class="badge">'+esc(t.badge)+'</span>'+
        '<h3>'+esc(t.title)+'</h3><p>'+esc(t.body)+'</p>'+
        (t.meta?'<div class="meta"><span class="badge">'+esc(t.meta)+'</span></div>':'')+
        (t.metric?'<div class="metric" id="heroMetric'+i+'">'+esc(t.metric)+'</div>':'')+
        '<span class="glow"></span></article>';
    }).join('');
  }

  function renderMedia(){
    if(!state.media.length){
      $('mediaRail').innerHTML='<div class="empty-state">Cargando carátulas y episodios…</div>';
      return;
    }
    $('mediaRail').innerHTML=state.media.slice(0,12).map(function(x){
      var ep=x.episode&&x.episode.season?'T'+x.episode.season+(x.episode.number?' · E'+x.episode.number:''):'';
      var img=(x.episode&&x.episode.image)||x.image||'';
      return '<article class="poster-card" tabindex="0" data-focusable="true" data-action="media" data-title="'+esc(x.title)+'">'+
        (img?'<img src="'+esc(img)+'" alt="">':'')+
        '<div class="poster-fade"></div>'+
        (ep?'<span class="poster-episode">'+esc(ep)+'</span>':'')+
        '<div class="poster-title">'+esc(x.title)+'</div></article>';
    }).join('');
  }

  function loadCatalog(){
    timeoutFetch(BACKEND+'/api/harvest/catalog?mode=schedule&country=US',{},6000)
      .then(function(r){if(!r.ok)throw new Error('HTTP '+r.status);return r.json();})
      .then(function(j){state.media=j.items||[];renderMedia(); if(state.media[0]&&state.media[0].title){heroTiles[0].title=state.media[0].title;heroTiles[0].body=(state.media[0].summary||'Nuevo contenido disponible').slice(0,170);renderHero();}})
      .catch(function(){$('mediaRail').innerHTML='<div class="empty-state">No se pudo cargar el catálogo ahora. Harvest seguirá funcionando con las apps instaladas.</div>';});
  }

  function renderApps(){
    $('appsRow').innerHTML=streaming.map(function(a){
      var installed=state.appStatus[a.name]===true;
      return '<article tabindex="0" data-focusable="true" data-action="app" data-name="'+esc(a.name)+'" class="app-card '+a.cls+'">'+
        '<strong>'+esc(a.name)+'</strong>'+
        '<small>'+(installed?'Instalada':'Comprobar')+'</small></article>';
    }).join('');
    renderShelf(state.selectedApp);
  }

  function renderShelf(name){
    state.selectedApp=name;
    var items=shelfData[name]||['Información','Recomendaciones','Favoritos','Actividad'];
    $('contextShelf').innerHTML='<div class="shelf-title"><h3>'+esc(name)+'</h3><span>OK abre la app · contenido contextual debajo</span></div>'+
      '<div class="content-row">'+items.map(function(x,i){return '<div class="content-card"><b>'+esc(x)+'</b><span>'+(i===0?'Personalizado por Jarvis':'Contenido dinámico')+'</span></div>';}).join('')+'</div>';
  }

  function getKnownInstalled(label){
    var ids=APP_IDS[label]||[],i;
    for(i=0;i<ids.length;i++)if(state.appStatus[label]===true)return ids[i];
    return null;
  }

  function checkKnownApps(){
    var jobs=[];
    streaming.forEach(function(a){
      var ids=APP_IDS[a.name]||[];
      if(!ids.length)return;
      jobs.push((function(name,list){
        function next(i){
          if(i>=list.length){state.appStatus[name]=false;return Promise.resolve();}
          return luna('luna://com.webos.applicationManager/getAppLoadStatus',{appId:list[i]}).then(function(r){
            if(r&&r.returnValue!==false&&(r.exist===true||r.status==='loaded'||r.status==='notLoaded'||typeof r.exist==='undefined')){
              state.appStatus[name]=true;return;
            }
            return next(i+1);
          }).catch(function(){return next(i+1);});
        }
        return next(0);
      })(a.name,ids));
    });
    return Promise.all(jobs).then(renderApps);
  }

  function loadInstalledApps(){
    return luna('luna://com.webos.applicationManager/listApps',{}).then(function(r){
      var apps=r.apps||[];
      state.installedApps=apps.map(function(a){return{id:a.id,title:a.title||a.id};}).filter(function(a){return a.id;});
      state.installedApps.sort(function(a,b){return a.title.localeCompare(b.title);});
    }).catch(function(){state.installedApps=[];});
  }

  function findInstalled(label){
    var q=safe(label).toLowerCase(),i,a,known=getKnownInstalled(label);
    if(known)return{id:known,title:label};
    for(i=0;i<state.installedApps.length;i++){a=state.installedApps[i];if(a.title.toLowerCase()===q)return a;}
    for(i=0;i<state.installedApps.length;i++){a=state.installedApps[i];if(a.title.toLowerCase().indexOf(q)>=0||a.id.toLowerCase().indexOf(q)>=0)return a;}
    return null;
  }

  function launchById(id,label){
    luna('luna://com.webos.applicationManager/launch',{id:id}).then(function(r){
      if(r&&r.returnValue===false)toast('No se pudo abrir '+label);
    }).catch(function(){toast('No se pudo abrir '+label);});
  }
  function launch(label){
    var app=findInstalled(label);
    if(app){launchById(app.id,label);return;}
    var ids=APP_IDS[label]||[];
    if(ids.length){launchById(ids[0],label);return;}
    toast('No encuentro '+label+' en esta TV');
  }

  function renderAppsDrawer(){
    var all=state.installedApps.slice();
    if(!all.length){
      all=[
        {id:'netflix',title:'Netflix'},{id:'amazon',title:'Prime Video'},{id:'youtube.leanback.v4',title:'YouTube'},
        {id:'com.disney.disneyplus-prod',title:'Disney+'},{id:'com.apple.appletv',title:'Apple TV'},
        {id:'com.webos.app.browser',title:'Navegador'},{id:'com.palm.app.settings',title:'Ajustes'}
      ];
    }
    $('appsGrid').innerHTML=all.slice(0,60).map(function(a){
      return '<button tabindex="0" data-focusable="true" class="installed-app" data-action="launch-id" data-id="'+esc(a.id)+'" data-title="'+esc(a.title)+'">'+
        esc(a.title)+'<span>'+esc(a.id)+'</span></button>';
    }).join('');
  }

  function phoneUrl(path){if(!state.phoneHost)return'';return'http://'+state.phoneHost+':8765'+path;}
  function phoneFetch(path){
    var url=phoneUrl(path);if(!url)return Promise.reject(new Error('phone_not_configured'));
    var headers={};if(state.phoneToken)headers.Authorization='Bearer '+state.phoneToken;
    return timeoutFetch(url,{headers:headers},3500).then(function(r){if(!r.ok)throw new Error('phone_http_'+r.status);return r.json();});
  }

  function renderHome(items){
    if(!items||!items.length){
      $('homeRow').innerHTML='<article tabindex="0" data-focusable="true" data-action="settings-phone" class="home-card"><span class="eyebrow">Homey</span><div class="value">Conectar</div><div class="state">Usar Jarvis Mobile como puente seguro</div></article>';
      $('homeProviderLabel').textContent='Homey · móvil no conectado';
      return;
    }
    var chosen=items.slice(0,6);
    $('homeRow').innerHTML=chosen.map(function(d){
      var s=d.state||{},value='',st='';
      if(typeof s.measure_temperature==='number')value=s.measure_temperature.toFixed(1)+' °C';
      else if(typeof s.temperature==='number')value=s.temperature.toFixed(1)+' °C';
      else if(typeof s.onoff==='boolean')value=s.onoff?'Encendido':'Apagado';
      else if(typeof s.on==='boolean')value=s.on?'Encendido':'Apagado';
      else value='Disponible';
      st=d.room||d.zone||d.type||d.provider||'Homey';
      return '<article tabindex="0" data-focusable="true" data-action="home-device" data-provider="'+esc(d.provider||'homey')+'" data-id="'+esc(d.id||'')+'" class="home-card">'+
        '<span class="eyebrow">'+esc(d.name||'Dispositivo')+'</span><div class="value">'+esc(value)+'</div><div class="state">'+esc(st)+'</div></article>';
    }).join('');
    $('homeProviderLabel').textContent='Homey · estado real desde Jarvis Mobile';
  }

  function loadHome(){
    phoneFetch('/domotics').then(function(j){
      var items=j.items||j.devices||[];renderHome(items);
    }).catch(function(){renderHome([]);});
  }

  function renderNow(tab,data){
    state.nowTab=tab;
    var html='';
    if(tab==='notifications'){
      var items=(data&&data.items)||[];
      html=items.length?items.slice(0,20).map(function(n){
        return '<div class="now-item"><b>'+esc((n.source||'Aviso')+' · '+(n.from||''))+'</b><span>'+esc(n.text||'')+'</span></div>';
      }).join(''):'<div class="empty-state">Sin notificaciones disponibles. Conecta Jarvis Mobile para mostrar WhatsApp, SMS, correo y otras alertas.</div>';
      $('notificationStatus').textContent=safe((data&&data.unreadCount)||items.length||0);
      if($('heroMetric1'))$('heroMetric1').textContent=safe((data&&data.unreadCount)||items.length||0);
    }else if(tab==='agenda'){
      var ev=(data&&data.events)||[],rem=(data&&data.reminders)||[];
      html=ev.slice(0,14).map(function(e){
        var d=e.begin?new Date(e.begin):null;
        return '<div class="now-item"><b>'+esc(e.title||'Evento')+'</b><span>'+esc(d?d.toLocaleString('es-ES',{weekday:'short',hour:'2-digit',minute:'2-digit'}):'')+(e.location?' · '+esc(e.location):'')+'</span></div>';
      }).join('');
      html+=rem.slice(0,8).map(function(r){return '<div class="now-item"><b>⏰ '+esc(r.title||'Recordatorio')+'</b><span>'+esc(r.text||'')+'</span></div>';}).join('');
      if(!html)html='<div class="empty-state">No hay agenda sincronizada. Conecta Jarvis Mobile y concede acceso al calendario.</div>';
      if($('heroMetric2'))$('heroMetric2').textContent=ev.length?String(ev.length):'—';
    }else{
      var tasks=(data&&data.tasks)||[];
      html=tasks.length?tasks.map(function(t){return '<div class="now-item"><b>'+esc(t.title||'Tarea')+'</b><span>'+esc(t.detail||'Pendiente')+'</span></div>';}).join(''):'<div class="empty-state">Las tareas aparecerán aquí desde Jarvis Mobile/Calendar cuando estén vinculadas.</div>';
    }
    $('nowContent').innerHTML=html;
  }

  function loadNow(tab){
    if(tab==='notifications')phoneFetch('/unread').then(function(j){renderNow(tab,j);}).catch(function(){renderNow(tab,null);});
    else if(tab==='agenda')phoneFetch('/agenda').then(function(j){renderNow(tab,j);}).catch(function(){renderNow(tab,null);});
    else renderNow(tab,null);
  }

  function renderChats(){
    $('chatList').innerHTML=state.chats.map(function(c){
      return '<div tabindex="0" data-focusable="true" data-action="open-chat" data-id="'+esc(c.id)+'" class="chat-item '+(c.id===state.currentChatId?'active':'')+'">'+
        '<b>'+esc(c.title||'Chat')+'</b><span>'+esc(c.preview||'Continuar conversación')+'</span></div>';
    }).join('');
  }
  function newChat(){
    var id='tv-'+Date.now();
    state.currentChatId=id;
    state.chats.unshift({id:id,title:'Nuevo chat',preview:'Conversación nueva',updated:Date.now()});
    saveChats();localStorage.setItem('jarvisCurrentChat',id);renderChats();showJarvis('Nuevo chat preparado');
  }

  function configurePhone(){
    var current=state.phoneHost||'';
    var host=window.prompt('IP o nombre de Jarvis Mobile (sin http://)',current);
    if(host===null)return;
    host=String(host).trim().replace(/^https?:\/\//,'').replace(/:\d+$/,'');
    state.phoneHost=host;localStorage.setItem('jarvisPhoneHost',host);
    toast(host?'Móvil configurado: '+host:'Móvil desconectado');
    loadHome();loadNow('notifications');
  }

  function openDrawer(which){
    closeDrawers();
    var el=$(which);if(el)el.className=el.className+' open';
    if(which==='chatDrawer')renderChats();
    if(which==='nowDrawer')loadNow(state.nowTab);
    if(which==='appsDrawer')renderAppsDrawer();
  }
  function closeDrawers(){
    ['chatDrawer','nowDrawer','appsDrawer'].forEach(function(id){
      var el=$(id);if(el)el.className=el.className.replace(/\sopen/g,'');
    });
  }

  function updateClock(){
    var d=new Date();
    $('clock').textContent=d.toLocaleTimeString('es-ES',{hour:'2-digit',minute:'2-digit'});
    $('date').textContent=d.toLocaleDateString('es-ES',{weekday:'short',day:'numeric',month:'short'});
    $('greeting').textContent=d.getHours()<12?'Buenos días':(d.getHours()<20?'Buenas tardes':'Buenas noches');
  }

  function focusables(){return Array.prototype.slice.call(document.querySelectorAll('[data-focusable="true"]')).filter(function(el){return el.offsetParent!==null;});}
  function focusAt(index){var list=focusables();if(!list.length)return;state.focusIndex=(index+list.length)%list.length;list[state.focusIndex].focus();}
  function move(key){var list=focusables(),active=document.activeElement,i=list.indexOf(active);if(i<0)i=0;if(key==='ArrowRight')i+=1;if(key==='ArrowLeft')i-=1;if(key==='ArrowDown')i+=3;if(key==='ArrowUp')i-=3;focusAt(i);}

  function showJarvis(msg){
    var overlay=$('jarvisOverlay');overlay.className='jarvis-overlay show';
    $('conversation').textContent=msg||'¿Qué necesitas?';
    setTimeout(function(){overlay.className='jarvis-overlay';},4200);
  }

  document.addEventListener('focusin',function(e){var el=e.target;if(el&&el.getAttribute&&el.getAttribute('data-action')==='app')renderShelf(el.getAttribute('data-name'));});
  document.addEventListener('click',function(e){
    var el=e.target;while(el&&el!==document.body&&(!el.getAttribute||!el.getAttribute('data-action')))el=el.parentNode;
    if(!el||!el.getAttribute)return;
    var action=el.getAttribute('data-action');
    if(action==='app'){launch(el.getAttribute('data-name'));return;}
    if(action==='apps'){openDrawer('appsDrawer');return;}
    if(action==='apps-close'||action==='drawer-close'){closeDrawers();return;}
    if(action==='launch-id'){launchById(el.getAttribute('data-id'),el.getAttribute('data-title'));return;}
    if(action==='drawer'){openDrawer('nowDrawer');return;}
    if(action==='tab'){loadNow(el.getAttribute('data-tab'));return;}
    if(action==='chats'){openDrawer('chatDrawer');return;}
    if(action==='new-chat'){newChat();return;}
    if(action==='open-chat'){state.currentChatId=el.getAttribute('data-id');localStorage.setItem('jarvisCurrentChat',state.currentChatId);renderChats();showJarvis('Continuando '+(el.textContent||'conversación'));return;}
    if(action==='voice'){$('jarvisState').textContent='Esperando audio del botón Voice…';showJarvis('Jarvis está listo.');return;}
    if(action==='home-device'){toast((el.querySelector('.eyebrow')||{}).textContent+' · control disponible vía Homey/Jarvis Mobile');return;}
    if(action==='settings-phone'||action==='home-summary'){configurePhone();return;}
    if(action==='media'){toast('Seleccionado: '+el.getAttribute('data-title'));return;}
    if(action==='hero'){if(el.getAttribute('data-index')==='1')openDrawer('nowDrawer');else if(el.getAttribute('data-index')==='2'){openDrawer('nowDrawer');loadNow('agenda');}else toast('Tile dinámico de Jarvis');return;}
  });

  document.addEventListener('keydown',function(e){
    var k=e.key;
    if(k==='ArrowLeft'||k==='ArrowRight'||k==='ArrowUp'||k==='ArrowDown'){e.preventDefault();move(k);return;}
    if(k==='Enter'){if(document.activeElement&&document.activeElement.click)document.activeElement.click();return;}
    if(k==='Escape'||e.keyCode===461){e.preventDefault();if(document.querySelector('.side-drawer.open'))closeDrawers();else window.close();}
  });

  function init(){
    updateClock();setInterval(updateClock,30000);
    renderHero();renderMedia();renderApps();renderChats();renderHome([]);
    loadCatalog();
    loadInstalledApps().then(function(){renderAppsDrawer();return checkKnownApps();}).then(function(){focusAt(0);});
    loadHome();loadNow('notifications');
  }
  init();
})();