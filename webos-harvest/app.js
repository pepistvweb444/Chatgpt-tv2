(function(){
  'use strict';

  var state = { installedApps: [], selectedApp: 'Netflix', focusIndex: 0 };
  function byId(id){ return document.getElementById(id); }
  function safe(v){ return v === null || typeof v === 'undefined' ? '' : String(v); }
  function esc(v){
    return safe(v).replace(/[&<>"']/g,function(m){
      return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[m];
    });
  }
  function toast(msg){
    var el=byId('toast');
    el.textContent=msg;
    el.className='toast show';
    setTimeout(function(){ el.className='toast'; },2200);
  }
  function luna(uri,payload){
    payload=payload||{};
    return new Promise(function(resolve,reject){
      if(!window.PalmServiceBridge){ reject(new Error('Luna API no disponible')); return; }
      var bridge=new PalmServiceBridge();
      bridge.onservicecallback=function(raw){
        try{ resolve(JSON.parse(raw)); }catch(e){ reject(e); }
      };
      bridge.call(uri,JSON.stringify(payload));
    });
  }

  var heroTiles=[
    {cls:'hero-primary',kicker:'Continuar viendo',title:'Tu siguiente episodio',body:'Jarvis reunirá aquí lo que estabas viendo en Netflix, Prime, Disney+, YouTube y TV.',badge:'Sincronizado',meta:'Retomar en 1 toque'},
    {cls:'',kicker:'Ahora',title:'3 cosas importantes',body:'2 mensajes · 1 correo importante',badge:'Now',metric:'3'},
    {cls:'',kicker:'Casa',title:'21,4 °C',body:'Salón · clima confortable',badge:'Homey',metric:'2 luces'},
    {cls:'hero-wide',kicker:'TV en directo',title:'Qué ver ahora',body:'Programas, partidos, noticias y recomendaciones según tus favoritos.',badge:'En directo',meta:'EPG inteligente'}
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

  var homeCards=[
    {name:'Salón',value:'21,4 °C',state:'2 luces · clima automático'},
    {name:'Luces',value:'5 / 12',state:'encendidas'},
    {name:'Persianas',value:'70%',state:'salón'},
    {name:'Cámaras',value:'Todo OK',state:'sin actividad'},
    {name:'Energía',value:'1,8 kW',state:'consumo actual'}
  ];

  function renderHero(){
    byId('heroGrid').innerHTML=heroTiles.map(function(t,i){
      return '<article tabindex="0" data-focusable="true" data-action="hero" data-index="'+i+'" class="tile '+t.cls+'">'+
        '<span class="kicker">'+esc(t.kicker)+'</span>'+
        '<span class="badge">'+esc(t.badge)+'</span>'+
        '<h3>'+esc(t.title)+'</h3>'+
        '<p>'+esc(t.body)+'</p>'+
        (t.meta?'<div class="meta"><span class="badge">'+esc(t.meta)+'</span></div>':'')+
        (t.metric?'<div class="metric">'+esc(t.metric)+'</div>':'')+
        '<span class="glow"></span></article>';
    }).join('');
  }

  function renderApps(){
    byId('appsRow').innerHTML=streaming.map(function(a){
      return '<article tabindex="0" data-focusable="true" data-action="app" data-name="'+esc(a.name)+'" class="app-card '+a.cls+'">'+
        '<strong>'+esc(a.name)+'</strong></article>';
    }).join('');
    renderShelf(state.selectedApp);
  }

  function renderShelf(name){
    state.selectedApp=name;
    var items=shelfData[name]||['Información','Recomendaciones','Favoritos','Actividad'];
    byId('contextShelf').innerHTML=
      '<div class="shelf-title"><h3>'+esc(name)+'</h3><span>OK abre la app · abajo: contenido contextual</span></div>'+
      '<div class="content-row">'+items.map(function(x,i){
        return '<div class="content-card"><b>'+esc(x)+'</b><span>'+(i===0?'Personalizado por Jarvis':'Se actualizará dinámicamente')+'</span></div>';
      }).join('')+'</div>';
  }

  function renderHome(){
    byId('homeRow').innerHTML=homeCards.map(function(c){
      return '<article tabindex="0" data-focusable="true" data-action="home" data-name="'+esc(c.name)+'" class="home-card">'+
        '<span class="eyebrow">'+esc(c.name)+'</span>'+
        '<div class="value">'+esc(c.value)+'</div>'+
        '<div class="state">'+esc(c.state)+'</div></article>';
    }).join('');
  }

  function updateClock(){
    var d=new Date();
    byId('clock').textContent=d.toLocaleTimeString('es-ES',{hour:'2-digit',minute:'2-digit'});
    byId('date').textContent=d.toLocaleDateString('es-ES',{weekday:'short',day:'numeric',month:'short'});
    byId('greeting').textContent=d.getHours()<12?'Buenos días':(d.getHours()<20?'Buenas tardes':'Buenas noches');
  }

  function loadInstalledApps(){
    return luna('luna://com.webos.applicationManager/listApps',{}).then(function(r){
      var apps=r.apps||[];
      state.installedApps=apps.map(function(a){ return {id:a.id,title:a.title||a.id}; });
    }).catch(function(){ state.installedApps=[]; });
  }

  function findInstalled(label){
    var q=safe(label).toLowerCase(), i, a;
    for(i=0;i<state.installedApps.length;i++){
      a=state.installedApps[i];
      if(a.title.toLowerCase()===q) return a;
    }
    for(i=0;i<state.installedApps.length;i++){
      a=state.installedApps[i];
      if(a.title.toLowerCase().indexOf(q)>=0 || a.id.toLowerCase().indexOf(q)>=0) return a;
    }
    return null;
  }

  function launch(label){
    var app=findInstalled(label);
    if(!app){ toast('No encuentro '+label+' en esta TV'); return; }
    luna('luna://com.webos.applicationManager/launch',{id:app.id}).catch(function(){
      toast('No se pudo abrir '+label);
    });
  }

  function focusables(){
    return Array.prototype.slice.call(document.querySelectorAll('[data-focusable="true"]'));
  }
  function focusAt(index){
    var list=focusables();
    if(!list.length) return;
    state.focusIndex=(index+list.length)%list.length;
    list[state.focusIndex].focus();
  }
  function move(key){
    var list=focusables();
    var active=document.activeElement;
    var i=list.indexOf(active);
    if(i<0) i=0;
    if(key==='ArrowRight') i+=1;
    if(key==='ArrowLeft') i-=1;
    if(key==='ArrowDown') i+=3;
    if(key==='ArrowUp') i-=3;
    focusAt(i);
  }

  function showJarvis(msg){
    var overlay=byId('jarvisOverlay');
    overlay.className='jarvis-overlay show';
    byId('conversation').textContent=msg||'¿Qué necesitas?';
    setTimeout(function(){ overlay.className='jarvis-overlay'; },4200);
  }

  document.addEventListener('focusin',function(e){
    var el=e.target;
    if(el && el.getAttribute && el.getAttribute('data-action')==='app'){
      renderShelf(el.getAttribute('data-name'));
    }
  });

  document.addEventListener('click',function(e){
    var el=e.target;
    while(el && el!==document.body && !el.getAttribute('data-action')) el=el.parentNode;
    if(!el || !el.getAttribute) return;
    var action=el.getAttribute('data-action');
    if(action==='app'){ launch(el.getAttribute('data-name')); return; }
    if(action==='voice'){
      byId('jarvisState').textContent='Esperando audio del botón Voice…';
      showJarvis('Jarvis está listo. El botón físico se conectará con LG Input Hook.');
      return;
    }
    if(action==='home'){ toast(el.getAttribute('data-name')+' · Homey pendiente de enlazar'); return; }
    if(action==='hero'){ toast('Tile dinámico de Jarvis'); return; }
    if(action==='settings'){ toast('Ajustes de Harvest TV'); return; }
    if(action==='dock'){ toast('Sección '+el.getAttribute('data-target')); }
  });

  document.addEventListener('keydown',function(e){
    var k=e.key;
    if(k==='ArrowLeft'||k==='ArrowRight'||k==='ArrowUp'||k==='ArrowDown'){
      e.preventDefault(); move(k); return;
    }
    if(k==='Enter'){
      if(document.activeElement && document.activeElement.click) document.activeElement.click();
      return;
    }
    if(k==='Escape'||e.keyCode===461){
      e.preventDefault(); window.close();
    }
  });

  function init(){
    updateClock();
    setInterval(updateClock,30000);
    renderHero();
    renderApps();
    renderHome();
    loadInstalledApps().then(function(){
      byId('homeStatus').textContent='Casa lista';
      byId('notificationStatus').textContent='3';
      focusAt(0);
    });
  }
  init();
})();