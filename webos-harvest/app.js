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
    mediaRows:[],
    selectedMedia:null,
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

  function mediaImage(x){return (x&&x.episode&&x.episode.image)||x.image||'';}
  function mediaMeta(x){
    var bits=[];
    if(x&&x.network)bits.push(x.network);
    if(x&&x.rating)bits.push('★ '+x.rating);
    if(x&&x.genres&&x.genres.length)bits.push(x.genres.slice(0,2).join(' · '));
    if(x&&x.episode&&x.episode.season)bits.push('T'+x.episode.season+(x.episode.number?' · E'+x.episode.number:''));
    return bits;
  }
  function renderFeature(x,rowTitle){
    if(!x){$('featureDetail').innerHTML='<div class="empty-state">Selecciona una película o serie para ver su ficha.</div>';return;}
    state.selectedMedia=x;
    var img=mediaImage(x),meta=mediaMeta(x);
    $('featureDetail').innerHTML=
      '<div class="feature-bg" style="background-image:url(\''+esc(img)+'\')"></div>'+
      '<div class="feature-shade"></div>'+
      '<div class="feature-copy">'+
        '<span class="eyebrow">'+esc(rowTitle||x.network||'Contenido')+'</span>'+
        '<h3>'+esc(x.title||'')+'</h3>'+
        '<p>'+esc((x.summary||'Sinopsis no disponible').slice(0,240))+'</p>'+
        '<div class="feature-meta">'+meta.map(function(m){return '<span>'+esc(m)+'</span>';}).join('')+'</div>'+
      '</div>';
  }
  function renderCatalogRows(){
    var rows=state.mediaRows||[];
    if(!rows.length){
      $('catalogRows').innerHTML='<div class="empty-state">Cargando biblioteca multimedia…</div>';
      renderFeature(null,'');
      return;
    }
    $('catalogRows').innerHTML=rows.slice(0,2).map(function(row,ri){
      var cards=(row.items||[]).slice(0,10).map(function(x,ci){
        var img=mediaImage(x);
        var progress=row.kind==='continue'?Math.min(92,18+(ci*11)%70):0;
        return '<article class="media-card" tabindex="0" data-focusable="true" data-action="media" data-row="'+ri+'" data-index="'+ci+'">'+
          (img?'<img src="'+esc(img)+'" alt="">':'')+
          '<div class="shade"></div><div class="title">'+esc(x.title||'')+'</div>'+
          (progress?'<div class="progress"><i style="width:'+progress+'%"></i></div>':'')+
        '</article>';
      }).join('');
      return '<div class="catalog-row"><div class="catalog-row-head"><h4>'+esc(row.title)+'</h4><span>'+esc(row.provider||'')+'</span></div><div class="catalog-track">'+cards+'</div></div>';
    }).join('');
    var first=rows[0]&&rows[0].items&&rows[0].items[0];
    renderFeature(first,rows[0]&&rows[0].title);
  }
  function loadCatalog(){
    timeoutFetch(BACKEND+'/api/harvest/catalog?mode=home',{},9000)
      .then(function(r){if(!r.ok)throw new Error('HTTP '+r.status);return r.json();})
      .then(function(j){
        state.media=j.items||[];
        state.mediaRows=j.rows||[];
        renderCatalogRows();
        if(state.media[0]&&state.media[0].title){
          heroTiles[0].title=state.media[0].title;
          heroTiles[0].body=(state.media[0].summary||'Nuevo contenido disponible').slice(0,170);
          renderHero();
        }
      })
      .catch(function(){
        $('catalogRows').innerHTML='<div class="empty-state">No se pudo cargar el catálogo ahora. Las aplicaciones siguen disponibles.</div>';
        renderFeature(null,'');
      });
  }
  function loadProviderRow(name){
    if(name==='YouTube'){
      state.mediaRows=[{id:'youtube-auth',title:'YouTube',kind:'private',provider:'YouTube',items:[]}];
      $('catalogRows').innerHTML='<div class="catalog-row"><div class="catalog-row-head"><h4>YouTube</h4><span>Cuenta no vinculada</span></div><div class="empty-state">Conecta Google/YouTube para mostrar Suscripciones, Ver más tarde y Últimos vídeos. Harvest no inventará contenido privado.</div></div>';
      renderFeature({title:'YouTube',summary:'Conecta tu cuenta para personalizar esta sección.',network:'YouTube',image:''},'YouTube');
      return;
    }
    timeoutFetch(BACKEND+'/api/harvest/catalog?provider='+encodeURIComponent(name),{},8000)
      .then(function(r){if(!r.ok)throw new Error('HTTP '+r.status);return r.json();})
      .then(function(j){
        var items=j.items||[];
        state.mediaRows=[{id:'provider',title:name+' · Catálogo',kind:'provider',provider:name,items:items}];
        renderCatalogRows();
      }).catch(function(){toast('No se pudo cargar el catálogo de '+name);});
  }
  function renderApps(){
    $('appsRow').innerHTML=streaming.map(function(a){
      var installed=state.appStatus[a.name]===true;
      var found=findInstalled(a.name),icon=found&&found.icon?found.icon:'';
      return '<article tabindex="0" data-focusable="true" data-action="app" data-name="'+esc(a.name)+'" class="app-card '+a.cls+'">'+
        (icon?'<img class="app-icon" src="'+esc(icon)+'" alt="">':'')+
        '<strong>'+esc(a.name)+'</strong>'+
        '<small>'+(installed?'Instalada':'Comprobar')+'</small></article>';
    }).join('');
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
      state.installedApps=apps.map(function(a){return{id:a.id,title:a.title||a.id,icon:a.largeIcon||a.icon||''};}).filter(function(a){return a.id;});
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
        (a.icon?'<img class="installed-icon" src="'+esc(a.icon)+'" alt="">':'')+
        '<b>'+esc(a.title)+'</b><span>'+esc(a.id)+'</span></button>';
    }).join('');
  }

  function phoneUrl(path){if(!state.phoneHost)return'';return'http://'+state.phoneHost+':8765'+path;}
  function rawPhoneFetch(path,auth){
    var url=phoneUrl(path);if(!url)return Promise.reject(new Error('phone_not_configured'));
    var headers={};if(auth!==false&&state.phoneToken)headers.Authorization='Bearer '+state.phoneToken;
    return timeoutFetch(url,{headers:headers},4500).then(function(r){
      return r.json().catch(function(){return{};}).then(function(j){if(!r.ok){var e=new Error(j.error||('phone_http_'+r.status));e.status=r.status;throw e;}return j;});
    });
  }
  function phoneFetch(path){return rawPhoneFetch(path,true);}
  function pairPhone(host,pin){
    state.phoneHost=safe(host).trim().replace(/^https?:\/\//,'').replace(/:\d+$/,'');
    localStorage.setItem('jarvisPhoneHost',state.phoneHost);
    $('syncStatus').textContent='Buscando Jarvis Mobile en '+state.phoneHost+'…';
    return rawPhoneFetch('/ping',false).then(function(){
      return rawPhoneFetch('/pair?pin='+encodeURIComponent(pin),false);
    }).then(function(j){
      if(!j.token)throw new Error('pair_no_token');
      state.phoneToken=j.token;localStorage.setItem('jarvisPhoneToken',j.token);
      $('syncStatus').textContent='✓ Teléfono enlazado con Harvest TV';
      return refreshPhoneSummary();
    });
  }
  function refreshPhoneSummary(){
    if(!state.phoneHost||!state.phoneToken){
      $('syncStatus').textContent='Sin teléfono enlazado';
      $('phoneSummary').innerHTML='';
      return Promise.resolve();
    }
    $('syncHost').value=state.phoneHost;
    $('syncStatus').textContent='Comprobando teléfono…';
    var checks=[
      phoneFetch('/permissions').catch(function(){return{};}),
      phoneFetch('/incoming-call').catch(function(){return{};}),
      phoneFetch('/messages?source=all').catch(function(){return{};}),
      phoneFetch('/agenda').catch(function(){return{unsupported:true};}),
      phoneFetch('/domotics').catch(function(){return{unsupported:true};})
    ];
    return Promise.all(checks).then(function(r){
      var perms=r[0]||{},call=r[1]&&r[1].call||{},msgs=r[2]&&r[2].messages||[],agenda=r[3]||{},home=r[4]||{};
      var callText=(call&&Object.keys(call).length)?(call.name||call.number||call.state||'Llamada activa'):'Sin llamada activa';
      var agendaText=agenda.unsupported?'Requiere actualizar Jarvis Mobile':String((agenda.events||[]).length)+' eventos';
      var homeText=home.unsupported?'Requiere actualizar Jarvis Mobile':String((home.items||home.devices||[]).length)+' dispositivos';
      $('syncStatus').textContent='✓ Jarvis Mobile conectado';
      $('phoneSummary').innerHTML=
        '<div class="phone-chip"><b>Mensajes</b><span>'+msgs.length+' recientes</span></div>'+
        '<div class="phone-chip"><b>Llamadas</b><span>'+esc(callText)+'</span></div>'+
        '<div class="phone-chip"><b>Agenda</b><span>'+esc(agendaText)+'</span></div>'+
        '<div class="phone-chip"><b>Domótica</b><span>'+esc(homeText)+'</span></div>'+
        '<div class="phone-chip"><b>SMS</b><span>'+(perms.readSms?'Permitido':'Sin permiso')+'</span></div>'+
        '<div class="phone-chip"><b>Contactos</b><span>'+(perms.contacts?'Permitido':'Sin permiso')+'</span></div>';
      return r;
    }).catch(function(e){
      $('syncStatus').textContent='No se pudo conectar con el móvil: '+safe(e.message);
      throw e;
    });
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
    loadHome();loadNow('notifications');refreshPhoneSummary();
  }

  function openDrawer(which){
    closeDrawers();
    var el=$(which);if(el)el.className=el.className+' open';
    if(which==='chatDrawer')renderChats();
    if(which==='nowDrawer')loadNow(state.nowTab);
    if(which==='appsDrawer')renderAppsDrawer();
  }
  function closeDrawers(){
    ['chatDrawer','nowDrawer','appsDrawer','syncDrawer'].forEach(function(id){
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

  document.addEventListener('focusin',function(e){var el=e.target;if(el&&el.getAttribute&&el.getAttribute('data-action')==='app')loadProviderRow(el.getAttribute('data-name'));if(el&&el.getAttribute&&el.getAttribute('data-action')==='media'){var r=parseInt(el.getAttribute('data-row'),10)||0,i=parseInt(el.getAttribute('data-index'),10)||0,row=state.mediaRows[r],item=row&&row.items&&row.items[i];if(item)renderFeature(item,row.title);}});
  document.addEventListener('click',function(e){
    var el=e.target;while(el&&el!==document.body&&(!el.getAttribute||!el.getAttribute('data-action')))el=el.parentNode;
    if(!el||!el.getAttribute)return;
    var action=el.getAttribute('data-action');
    if(action==='app'){launch(el.getAttribute('data-name'));return;}
    if(action==='apps'){openDrawer('appsDrawer');return;}
    if(action==='sync'){openDrawer('syncDrawer');refreshPhoneSummary();return;}
    if(action==='sync-close'){closeDrawers();return;}
    if(action==='pair-phone'){
      var host=$('syncHost').value,pin=$('syncPin').value;
      if(!host||!/^\d{4}$/.test(pin)){toast('Introduce la IP del móvil y el PIN de 4 dígitos');return;}
      pairPhone(host,pin).then(function(){loadHome();loadNow('notifications');toast('Jarvis Mobile sincronizado');}).catch(function(e){toast('No se pudo emparejar: '+safe(e.message));});
      return;
    }
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
    if(action==='media'){var r=parseInt(el.getAttribute('data-row'),10)||0,i=parseInt(el.getAttribute('data-index'),10)||0,row=state.mediaRows[r],item=row&&row.items&&row.items[i];if(item)renderFeature(item,row.title);return;}
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
    renderHero();renderCatalogRows();renderApps();renderChats();renderHome([]);
    if($('syncHost'))$('syncHost').value=state.phoneHost;
    loadCatalog();
    loadInstalledApps().then(function(){renderAppsDrawer();return checkKnownApps();}).then(function(){focusAt(0);});
    loadHome();loadNow('notifications');
  }
  init();
})();