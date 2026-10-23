(() => {
  'use strict';
  const state = { installedApps: [], selectedApp: 'Netflix', focusIndex: 0 };
  const $ = id => document.getElementById(id);
  const esc = s => String(s ?? '').replace(/[&<>"']/g, m => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[m]));
  const toast = msg => { const el=$('toast'); el.textContent=msg; el.classList.add('show'); setTimeout(()=>el.classList.remove('show'),2200); };

  function luna(uri, payload={}) {
    return new Promise((resolve,reject)=>{
      if (!window.PalmServiceBridge) return reject(new Error('Luna API no disponible'));
      const bridge = new PalmServiceBridge();
      bridge.onservicecallback = raw => { try { resolve(JSON.parse(raw)); } catch(e) { reject(e); } };
      bridge.call(uri, JSON.stringify(payload));
    });
  }

  async function loadInstalledApps() {
    try {
      const r = await luna('luna://com.webos.applicationManager/listApps', {});
      state.installedApps = (r.apps || []).map(a => ({id:a.id,title:a.title || a.id}));
    } catch (_) {
      state.installedApps = [];
    }
  }

  function findInstalled(label) {
    const q = String(label).toLowerCase();
    return state.installedApps.find(a => a.title.toLowerCase() === q) ||
           state.installedApps.find(a => a.title.toLowerCase().includes(q)) ||
           state.installedApps.find(a => a.id.toLowerCase().includes(q));
  }

  async function launch(label) {
    const app = findInstalled(label);
    if (!app) return toast('No encuentro '+label+' en esta TV');
    try { await luna('luna://com.webos.applicationManager/launch', {id:app.id}); }
    catch (_) { toast('No se pudo abrir '+label); }
  }

  const heroTiles = [
    {kind:'continue',size:'large',eyebrow:'Continuar viendo',title:'Tu siguiente episodio',body:'Jarvis mantendrá aquí lo que estabas viendo en cualquier dispositivo.',badge:'IA'},
    {kind:'now',eyebrow:'Ahora',title:'Todo al día',body:'2 mensajes · 1 correo importante · próxima cita 19:30',badge:'3 avisos'},
    {kind:'home',eyebrow:'Casa',title:'21,4 °C',body:'Salón confortable · 2 luces encendidas',badge:'Homey'},
    {kind:'live',eyebrow:'TV en directo',title:'Qué ver ahora',body:'EPG y recomendaciones en función de tus favoritos.',badge:'En directo'}
  ];

  const streaming = [
    {name:'Netflix',note:'Continuar · Nuevos episodios'},
    {name:'Prime Video',note:'Recomendado para ti'},
    {name:'YouTube',note:'Suscripciones · Últimos vídeos'},
    {name:'Disney+',note:'Favoritos · Estrenos'},
    {name:'Apple TV',note:'Continuar viendo'},
    {name:'TV',note:'Canales · EPG · Directo'}
  ];

  const homeCards = [
    {name:'Salón',value:'21,4 °C',state:'2 luces · clima automático'},
    {name:'Luces',value:'5 / 12',state:'encendidas'},
    {name:'Persianas',value:'70%',state:'salón'},
    {name:'Cámaras',value:'Todo OK',state:'sin actividad'},
    {name:'Energía',value:'1,8 kW',state:'consumo actual'}
  ];

  const shelfData = {
    'Netflix':['Continuar viendo','Nuevo episodio','Mi lista','Tendencias'],
    'Prime Video':['Seguir viendo','Estrenos','Incluido con Prime','Mi lista'],
    'YouTube':['Suscripciones','Ver más tarde','Noticias','Tecnología'],
    'Disney+':['Seguir viendo','Novedades','Marvel','Star Wars'],
    'Apple TV':['Continuar','Apple Originals','Películas','Mi lista'],
    'TV':['Ahora en TV','Siguiente','Favoritos','Grabaciones']
  };

  function renderHero() {
    $('heroGrid').innerHTML = heroTiles.map((t,i)=>`
      <article class="tile ${t.size||''}" tabindex="0" data-focusable="true" data-action="hero" data-index="${i}">
        <span class="eyebrow">${esc(t.eyebrow)}</span>
        <span class="badge">${esc(t.badge)}</span>
        <h3>${esc(t.title)}</h3>
        <p>${esc(t.body)}</p>
      </article>`).join('');
  }

  function renderApps() {
    $('appsRow').innerHTML = streaming.map((a,i)=>`
      <article class="app-card" tabindex="0" data-focusable="true" data-action="app" data-name="${esc(a.name)}">
        <span class="eyebrow">Streaming</span>
        <strong>${esc(a.name)}</strong>
        <small>${esc(a.note)}</small>
      </article>`).join('');
    renderShelf(state.selectedApp);
  }

  function renderShelf(name) {
    state.selectedApp = name;
    const items = shelfData[name] || ['Información','Recomendaciones','Favoritos','Actividad'];
    $('contextShelf').innerHTML = `
      <div class="shelf-title"><h3>${esc(name)}</h3><span class="muted">Pulsa OK sobre la app para abrirla</span></div>
      <div class="content-row">${items.map((x,i)=>`
        <div class="content-card"><b>${esc(x)}</b><span>${i===0?'Personalizado por Jarvis':'Contenido dinámico'}</span></div>
      `).join('')}</div>`;
  }

  function renderHome() {
    $('homeRow').innerHTML = homeCards.map(c=>`
      <article class="home-card" tabindex="0" data-focusable="true" data-action="home" data-name="${esc(c.name)}">
        <span class="eyebrow">${esc(c.name)}</span>
        <div class="value">${esc(c.value)}</div>
        <div class="state">${esc(c.state)}</div>
      </article>`).join('');
  }

  function updateClock() {
    const d = new Date();
    $('clock').textContent = d.toLocaleTimeString('es-ES',{hour:'2-digit',minute:'2-digit'});
    $('date').textContent = d.toLocaleDateString('es-ES',{weekday:'short',day:'numeric',month:'short'});
    $('greeting').textContent = d.getHours()<12?'Buenos días':d.getHours()<20?'Buenas tardes':'Buenas noches';
  }

  function focusables(){ return [...document.querySelectorAll('[data-focusable="true"]')]; }
  function focusAt(index){
    const list=focusables();
    if(!list.length) return;
    state.focusIndex=(index+list.length)%list.length;
    list[state.focusIndex].focus();
  }

  function move(key){
    const list=focusables(), active=document.activeElement;
    let i=Math.max(0,list.indexOf(active));
    if(key==='ArrowRight') i++;
    if(key==='ArrowLeft') i--;
    if(key==='ArrowDown') i+=3;
    if(key==='ArrowUp') i-=3;
    focusAt(i);
  }

  document.addEventListener('focusin', e=>{
    const el=e.target.closest('[data-action="app"]');
    if(el) renderShelf(el.dataset.name);
  });

  document.addEventListener('click', async e=>{
    const el=e.target.closest('[data-action]');
    if(!el) return;
    const action=el.dataset.action;
    if(action==='app') return launch(el.dataset.name);
    if(action==='voice'){
      $('jarvisState').textContent='Escuchando…';
      $('conversation').textContent='“¿Qué necesitas?”';
      toast('Jarvis listo · la conexión de voz se añadirá al backend común');
      setTimeout(()=>$('jarvisState').textContent='Pulsa el micrófono para hablar',1800);
    }
    if(action==='home') toast(el.dataset.name+' · control Homey pendiente de vincular');
    if(action==='hero') toast('Tile dinámico · Jarvis podrá regenerarlo automáticamente');
  });

  document.addEventListener('keydown', e=>{
    if(['ArrowLeft','ArrowRight','ArrowUp','ArrowDown'].includes(e.key)){ e.preventDefault(); move(e.key); }
    if(e.key==='Enter'){ const el=document.activeElement; if(el && el.click) el.click(); }
    if(e.key==='Escape' || e.keyCode===461){ e.preventDefault(); window.close(); }
  });

  async function init(){
    updateClock(); setInterval(updateClock,30000);
    renderHero(); renderApps(); renderHome();
    await loadInstalledApps();
    $('homeStatus').textContent='Casa · lista para vincular';
    $('notificationStatus').textContent='3 avisos';
    focusAt(0);
  }
  init();
})();