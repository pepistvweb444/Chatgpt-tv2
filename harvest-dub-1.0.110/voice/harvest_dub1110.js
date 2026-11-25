/* Harvest TV 1.0.110 «🌐 Doblaje»: the TV's own audio (Netflix, Prime, YouTube, TV en directo…) -> backend /api/dub-stream
   (language detected automatically, translated to the chosen language, default es-ES) -> spoken back through the TV speakers
   with the cloned voice of the person talking in the video (same OpenVoice cloner as Jarvis Mobile; ~10 s sample per speaker,
   taken from the TV audio itself, a new sample when the speaker changes). The original is turned down while the dub speaks.
   Capture source is configurable because each LG firmware exposes something different:
     pulse   : parec/pacat from a PulseAudio source (default @DEFAULT_MONITOR@)
     arecord : ALSA capture device, e.g. hw:0,13 / plughw:0,12 (Realtek «MARS CAPTURE_TEST/DSNOOP» taps)
     cmd     : any shell command that writes raw s16le PCM to stdout (rate/channels in the config)
   Config: /media/developer/harvest-data/dub.json (0600). The token is write-only (never returned by /hv/dub/config, never logged).
   Nothing runs until «Activar» (enabled:false by default). ES5 / Node 8. */
'use strict';
var fs=require('fs'),path=require('path');
var CFG_FILE='/media/developer/harvest-data/dub.json';
var DEF={enabled:false,backend:'https://ctv2.185-245-182-55.sslip.io',token:'',session:'',target:'es',
  capture:{mode:'pulse',device:'',cmd:'',rate:16000,channels:1,pulseServer:''},
  vad:{minRms:0.008,ratio:2.5,endSilenceMs:650,minSpeechMs:900,maxSegMs:8000,prerollMs:250},
  duck:{mode:'volume',ratio:0.35,sinkInputPct:25},
  play:{mode:'paplay',device:'',cmd:'',gain:1.6},
  subs:true,maxLagSec:25,gateWhilePlaying:true,sampleSec:10};
var LANGS={es:'Castellano',en:'Inglés',fr:'Francés',zh:'Chino',ja:'Japonés',ko:'Coreano'};
function merge(a,b){var o={},k;for(k in a)o[k]=(a[k]&&typeof a[k]==='object'&&!Array.isArray(a[k]))?merge(a[k],(b&&b[k])||{}):a[k];if(b)for(k in b)if(!(k in a))o[k]=b[k];else if(!(a[k]&&typeof a[k]==='object')&&b[k]!==undefined)o[k]=b[k];return o;}
function rid(){var s='',c='abcdefghijklmnopqrstuvwxyz0123456789';for(var i=0;i<12;i++)s+=c[Math.floor(Math.random()*c.length)];return s;}
function rms(buf){var n=buf.length>>1,s=0;for(var i=0;i<n;i++){var v=buf.readInt16LE(i*2)/32768;s+=v*v;}return n?Math.sqrt(s/n):0;}
function wavHeader(len,rate){var h=Buffer.alloc(44);h.write('RIFF',0);h.writeUInt32LE(36+len,4);h.write('WAVE',8);h.write('fmt ',12);h.writeUInt32LE(16,16);h.writeUInt16LE(1,20);h.writeUInt16LE(1,22);h.writeUInt32LE(rate,24);h.writeUInt32LE(rate*2,28);h.writeUInt16LE(2,32);h.writeUInt16LE(16,34);h.write('data',36);h.writeUInt32LE(len,40);return h;}
/* gain with soft clip on a 16-bit PCM wav (header kept) */
function gainWav(buf,g){if(!g||g===1||buf.length<44)return buf;var o=Buffer.from(buf),start=44;var di=buf.indexOf('data');if(di>=12&&di<200)start=di+8;
  for(var i=start;i+1<o.length;i+=2){var v=o.readInt16LE(i)/32768*g;v=v>1?1:v<-1?-1:v;v=v-(v*v*v)/6*(Math.abs(v)>0.7?1:0);o.writeInt16LE(Math.max(-32767,Math.min(32767,Math.round(v*32767))),i);}return o;}
/* streaming downmix + linear resample to 16 kHz mono s16le */
function Conv(rate,ch){this.rate=rate||16000;this.ch=ch||1;this.pos=0;this.prev=0;this.rest=Buffer.alloc(0);}
Conv.prototype.push=function(chunk){var b=this.rest.length?Buffer.concat([this.rest,chunk]):chunk,fs_=2*this.ch,n=Math.floor(b.length/fs_);this.rest=b.slice(n*fs_);
  if(this.rate===16000&&this.ch===1)return b.slice(0,n*2);
  var mono=new Float32Array(n),i,c;for(i=0;i<n;i++){var s=0;for(c=0;c<this.ch;c++)s+=b.readInt16LE(i*fs_+c*2);mono[i]=s/this.ch;}
  var step=this.rate/16000,out=[],p=this.pos;while(p<n){var i0=Math.floor(p),f=p-i0,a=mono[i0],bb=i0+1<n?mono[i0+1]:mono[i0];out.push(a+(bb-a)*f);p+=step;}
  this.pos=p-n;this.prev=n?mono[n-1]:this.prev;var ob=Buffer.alloc(out.length*2);for(i=0;i<out.length;i++)ob.writeInt16LE(Math.max(-32768,Math.min(32767,Math.round(out[i]))),i*2);return ob;};
/* energy VAD on 20 ms frames, adaptive floor; emits phrases (speech + short preroll) */
function Vad(cfg){this.c=cfg;this.floor=0.004;this.cur=null;this.pre=[];this.sil=0;this.sp=0;this.level=0;}
Vad.prototype.frame=function(fr,emit){var c=this.c,e=rms(fr);this.level=e;if(e<this.floor*1.5||!this.cur)this.floor=this.floor*0.995+Math.min(e,this.floor*3)*0.005;if(this.floor<0.0005)this.floor=0.0005;
  var speech=e>Math.max(c.minRms,this.floor*c.ratio)&&e<0.995;
  if(!this.cur){this.pre.push(fr);if(this.pre.length>Math.round(c.prerollMs/20))this.pre.shift();if(speech){this.cur=this.pre.slice();this.pre=[];this.sp=20;this.sil=0;}return;}
  this.cur.push(fr);if(speech){this.sp+=20;this.sil=0;}else this.sil+=20;var dur=this.cur.length*20;
  if(this.sil>=c.endSilenceMs||dur>=c.maxSegMs){var seg=this.cur,sp=this.sp;this.cur=null;this.sil=0;this.sp=0;if(sp>=c.minSpeechMs)emit(Buffer.concat(seg),sp);}};
Vad.prototype.reset=function(){this.cur=null;this.pre=[];this.sil=0;this.sp=0;};

function Dub(o){this.o=o||{};this.log=this.o.log||function(){};this.now=this.o.now||function(){return Date.now();};this.cfgFile=this.o.cfgFile||CFG_FILE;this.tmp=this.o.tmp||'/tmp';
  this.cap=null;this.vad=null;this.conv=null;this.q=[];this.busy=false;this.playQ=[];this.player=null;this.playing=false;this.ducked=null;this.unduckT=null;this.sampling=null;this.n=0;
  this.st={segments:0,dubbed:0,dropped:0,skipped:0,errors:0,lastLagSec:0,lastError:'',bytes:0,startedAt:0};this.last=[];this._cfg=null;this._cfgAt=0;}
Dub.prototype.cfg=function(){var t=this.now();if(this._cfg&&t-this._cfgAt<3000)return this._cfg;var raw={};try{raw=JSON.parse(fs.readFileSync(this.cfgFile,'utf8'))||{};}catch(e){}
  var c=merge(DEF,raw);if(!/^[a-z0-9]{4,24}$/.test(c.session||'')){c.session=rid();this.saveCfg({session:c.session},true);}this._cfg=c;this._cfgAt=t;return c;};
Dub.prototype.saveCfg=function(patch,quiet){var raw={};try{raw=JSON.parse(fs.readFileSync(this.cfgFile,'utf8'))||{};}catch(e){}var k;for(k in patch){if(patch[k]&&typeof patch[k]==='object'&&!Array.isArray(patch[k])){raw[k]=raw[k]||{};for(var j in patch[k])raw[k][j]=patch[k][j];}else raw[k]=patch[k];}
  try{try{fs.mkdirSync(path.dirname(path.dirname(this.cfgFile)));}catch(e){}try{fs.mkdirSync(path.dirname(this.cfgFile));}catch(e){}fs.writeFileSync(this.cfgFile,JSON.stringify(raw,null,1),{mode:384});try{fs.chmodSync(this.cfgFile,384);}catch(e){}}catch(e){if(!quiet)this.log('[1.0.110 dub] save '+e.message);}this._cfg=null;};
Dub.prototype.publicCfg=function(){var c=this.cfg(),o=JSON.parse(JSON.stringify(c));o.tokenSet=!!c.token;delete o.token;return o;};
Dub.prototype.captureArgs=function(c){var cp_=c.capture,m=cp_.mode;
  if(m==='arecord')return {bin:'/usr/bin/arecord',args:['-q','-D',cp_.device||'default','-f','S16_LE','-r',String(cp_.rate||48000),'-c',String(cp_.channels||2),'-t','raw'],rate:cp_.rate||48000,ch:cp_.channels||2};
  if(m==='cmd')return cp_.cmd?{bin:'/bin/sh',args:['-c',cp_.cmd],rate:cp_.rate||16000,ch:cp_.channels||1}:null;
  var bin=fs.existsSync('/usr/bin/parec')?'/usr/bin/parec':'/usr/bin/pacat',a=bin==='/usr/bin/pacat'?['-r']:[];
  return {bin:bin,args:a.concat(['--raw','--format=s16le','--rate=16000','--channels=1','--client-name=harvest-dub-capture','-d',cp_.device||'@DEFAULT_MONITOR@']),rate:16000,ch:1,env:cp_.pulseServer?{PULSE_SERVER:cp_.pulseServer}:null};};
Dub.prototype.running=function(){return !!this.cap;};
Dub.prototype.start=function(){var self=this,c=this.cfg();if(this.cap)return {ok:true,already:true};if(!c.token)return {ok:false,error:'Falta el token del servidor de doblaje (Ajustes de Doblaje).'};
  var a=this.captureArgs(c);if(!a)return {ok:false,error:'Fuente de captura «cmd» sin comando.'};
  var env=null;if(a.env){env={};for(var k in process.env)env[k]=process.env[k];for(k in a.env)env[k]=a.env[k];}
  var p;try{p=this.o.spawn(a.bin,a.args,env);}catch(e){return {ok:false,error:'No puedo lanzar '+a.bin+': '+e.message};}
  this.cap=p;this.conv=new Conv(a.rate,a.ch);this.vad=new Vad(c.vad);this.st.startedAt=this.now();this.st.lastError='';var rest=Buffer.alloc(0);
  p.stdout.on('data',function(d){if(self.cap!==p)return;self.st.bytes+=d.length;var b=self.conv.push(d);if(!b.length)return;b=rest.length?Buffer.concat([rest,b]):b;var i=0;for(;i+640<=b.length;i+=640)self.frame(b.slice(i,i+640));rest=b.slice(i);});
  var died=function(code){if(self.cap!==p)return;self.cap=null;self.st.lastError='La captura se ha parado ('+a.bin.split('/').pop()+' código '+code+'). Revisa la fuente en Ajustes de Doblaje.';self.log('[1.0.110 dub] capture exit '+code);self.unduck(true);};
  p.on('close',died);p.on('error',function(e){self.st.lastError='Captura: '+e.message;died(-1);});
  if(p.stderr)p.stderr.on('data',function(d){self.st.capErr=String(d).slice(-200);});
  this.log('[1.0.110 dub] capture '+c.capture.mode+' '+(c.capture.device||'')+' -> '+c.target);return {ok:true};};
Dub.prototype.stop=function(){var p=this.cap;this.cap=null;if(p)try{p.kill('SIGTERM');}catch(e){}this.q=[];this.playQ=[];if(this.player)try{this.player.kill('SIGTERM');}catch(e){}this.sampling=null;if(this.vad)this.vad.reset();this.unduck(true);return {ok:true};};
Dub.prototype.frame=function(fr){var self=this,c=this.cfg();
  if(this.sampling){var e=rms(fr);if(e>Math.max(c.vad.minRms,(this.vad?this.vad.floor:0.004)*c.vad.ratio)){this.sampling.bufs.push(fr);this.sampling.ms+=20;}
    if(this.sampling.ms>=c.sampleSec*1000||this.now()-this.sampling.at>60000){var s=this.sampling;this.sampling=null;if(s.ms<3000){s.cb&&s.cb({ok:false,say:'No he oído a nadie hablar lo bastante en el último minuto.'});return;}
      this.post('sample',{},Buffer.concat(s.bufs),function(e,j){var ok=!e&&j&&j.ok;s.cb&&s.cb({ok:ok,say:ok?'Muestra de voz tomada ('+Math.round(j.voicedSec)+' s). Ya doblo con la voz de esa persona.':'No he podido crear la voz: '+(e?e.message:(j&&j.error)||'error')});});}
    return;}
  if(this.playing&&c.gateWhilePlaying){if(this.vad)this.vad.reset();return;}
  this.vad.frame(fr,function(seg,spMs){self.enqueue(seg,spMs);});};
Dub.prototype.enqueue=function(seg,spMs){this.st.segments++;if(this.q.length>=3){this.q.shift();this.st.dropped++;}this.q.push({pcm:seg,at:this.now(),sp:spMs});this.pump();};
Dub.prototype.post=function(action,query,body,cb){var self=this,c=this.cfg(),q=['action='+action,'session='+encodeURIComponent(c.session)],k;for(k in query)q.push(k+'='+encodeURIComponent(query[k]));
  var id=(++this.n),f=this.tmp+'/hv-dub-'+id+'.pcm',k_=this.tmp+'/hv-dub-'+id+'.cfg';
  try{fs.writeFileSync(f,body||Buffer.alloc(0));fs.writeFileSync(k_,'header = "x-dub-token: '+String(c.token).replace(/["\\\r\n]/g,'')+'"\n',{mode:384});}catch(e){return cb(e);}
  var url=String(c.backend).replace(/\/$/,'')+'/api/dub-stream?'+q.join('&');
  this.o.exec('/usr/bin/curl',['-sS','--max-time',action==='tts'?'150':'60','-K',k_,'-H','Content-Type: application/octet-stream','--data-binary','@'+f,url],action==='tts'?155000:65000,function(e,out){try{fs.unlinkSync(f);}catch(x){}try{fs.unlinkSync(k_);}catch(x){}
    if(e)return cb(new Error(String(e.message||e).replace(/x-dub-token[^\s]*/gi,'').slice(0,160)));var j=null;try{j=JSON.parse(out);}catch(x){return cb(new Error('respuesta no válida del servidor'));}cb(null,j);});};
Dub.prototype.note=function(x){this.last.unshift(x);if(this.last.length>8)this.last.pop();};
Dub.prototype.pump=function(){var self=this;if(this.busy||!this.q.length)return;var it=this.q.shift(),c=this.cfg();this.busy=true;
  var done=function(){self.busy=false;setTimeout(function(){self.pump();},0);};
  this.post('segment',{target:c.target},it.pcm,function(e,j){
    if(e||!j||!j.ok){self.st.errors++;self.st.lastError=e?e.message:((j&&j.error)||'error');self.log('[1.0.110 dub] segment '+self.st.lastError);return done();}
    if(j.skip){self.st.skipped++;if(j.skip==='same_language')self.note({t:self.now(),skip:'mismo idioma',lang:j.lang,transcript:j.transcript});return done();}
    var row={t:self.now(),lang:j.lang,transcript:j.transcript,translation:j.translation,speaker:j.speaker,voice:j.voice,stt:j.ms&&j.ms.total};self.note(row);
    if(c.subs&&self.o.toast)self.o.toast('🌐 '+j.translation);
    if(j.voice==='pending'){row.audio='sin voz aún';return done();}
    self.post('tts',{seg:j.seg},null,function(e2,a){var lag=(self.now()-it.at)/1000;self.st.lastLagSec=Math.round(lag*10)/10;row.lagSec=self.st.lastLagSec;
      if(e2||!a||!a.ok||!a.audioB64){self.st.errors++;self.st.lastError=e2?e2.message:((a&&a.error)||'tts');row.audio='error';return done();}
      if(lag>c.maxLagSec){self.st.dropped++;row.audio='descartado (llega con '+Math.round(lag)+' s de retraso)';return done();}
      var f=self.tmp+'/hv-dub-play-'+(++self.n)+'.wav';try{fs.writeFileSync(f,gainWav(Buffer.from(a.audioB64,'base64'),c.play.gain));}catch(x){return done();}
      row.audio='doblado';self.st.dubbed++;self.playQ.push(f);self.playNext();done();});});};
Dub.prototype.playArgs=function(c,f){var p=c.play;if(p.mode==='aplay')return {bin:'/usr/bin/aplay',args:(p.device?['-q','-D',p.device]:['-q']).concat([f])};if(p.mode==='cmd'&&p.cmd)return {bin:'/bin/sh',args:['-c',p.cmd.replace(/\{file\}/g,"'"+f+"'")]};
  return {bin:fs.existsSync('/usr/bin/paplay')?'/usr/bin/paplay':'/usr/bin/pacat',args:(fs.existsSync('/usr/bin/paplay')?[]:['-p','--file-format=wav']).concat(['--client-name=harvest-dub',f])};};
Dub.prototype.playNext=function(){var self=this;if(this.player||!this.playQ.length)return;var f=this.playQ.shift(),c=this.cfg();clearTimeout(this.unduckT);this.playing=true;
  this.duck(function(){var a=self.playArgs(c,f),p;try{p=self.o.spawn(a.bin,a.args,null);}catch(e){self.player=null;self.playing=false;return;}self.player=p;
    var fin=function(){if(self.player!==p)return;self.player=null;try{fs.unlinkSync(f);}catch(x){}if(self.playQ.length)return self.playNext();self.unduckT=setTimeout(function(){self.playing=false;self.unduck();},700);};
    p.on('close',fin);p.on('error',fin);});};
Dub.prototype.duck=function(cb){var self=this,c=this.cfg(),d=c.duck;if(this.ducked||d.mode==='none')return cb();
  if(d.mode==='sinkinputs'&&this.o.exec){return this.o.exec('/usr/bin/pactl',['list','sink-inputs'],4000,function(e,out){var ids=[];String(out||'').split(/Sink Input #/).slice(1).forEach(function(b){var id=parseInt(b,10);if(!/application\.name = "harvest-dub/.test(b)&&id>=0)ids.push(id);});
      self.ducked={mode:'sinkinputs',ids:ids};ids.forEach(function(id){self.o.exec('/usr/bin/pactl',['set-sink-input-volume',String(id),d.sinkInputPct+'%'],3000,function(){});});cb();});}
  if(!this.o.volGet)return cb();this.o.volGet(function(v){if(v==null)return cb();self.ducked={mode:'volume',vol:v};self.o.volSet(Math.round(v*d.ratio),function(){cb();});});};
Dub.prototype.unduck=function(force){var self=this,d=this.ducked;if(!d)return;if(!force&&(this.player||this.playQ.length))return;this.ducked=null;this.playing=false;
  if(d.mode==='volume'&&this.o.volSet)this.o.volSet(d.vol,function(){});if(d.mode==='sinkinputs')d.ids.forEach(function(id){self.o.exec('/usr/bin/pactl',['set-sink-input-volume',String(id),'100%'],3000,function(){});});};
Dub.prototype.sample=function(cb){if(!this.cap)return cb({ok:false,say:'Activa primero el doblaje.'});this.sampling={bufs:[],ms:0,at:this.now(),cb:cb};return null;};
Dub.prototype.status=function(){var c=this.cfg();return {ok:true,version:'1.0.110',enabled:!!c.enabled,running:this.running(),target:c.target,targetName:LANGS[c.target]||c.target,langs:LANGS,
  capture:{mode:c.capture.mode,device:c.capture.device,level:this.vad?Math.round(this.vad.level*1000)/1000:0,floor:this.vad?Math.round(this.vad.floor*10000)/10000:0,bytes:this.st.bytes,err:this.st.capErr||''},
  duck:c.duck.mode,play:c.play.mode,sampling:this.sampling?Math.round(this.sampling.ms/100)/10:null,playing:this.playing,queue:this.q.length,stats:this.st,last:this.last,tokenSet:!!c.token,backend:c.backend};};
Dub.prototype.setEnabled=function(on){this.saveCfg({enabled:!!on});return on?this.start():this.stop();};
Dub.prototype.setTarget=function(l){l=String(l||'').toLowerCase().split(/[-_]/)[0];if(!LANGS[l])return {ok:false,error:'Idioma no disponible con este clonador: '+l};this.saveCfg({target:l});return {ok:true,target:l,name:LANGS[l]};};
Dub.prototype.reset=function(cb){var self=this;this.post('reset',{},null,function(e,j){self.last=[];cb({ok:!e&&j&&j.ok});});};
/* 5 s level test of the configured capture (does not need the backend) */
Dub.prototype.testCapture=function(cb){var self=this,c=this.cfg(),a=this.captureArgs(c);if(!a)return cb({ok:false,error:'sin comando'});if(this.cap)return cb({ok:false,error:'Desactiva el doblaje para probar la captura.'});
  var env=null;if(a.env){env={};for(var k in process.env)env[k]=process.env[k];for(k in a.env)env[k]=a.env[k];}var p,cv=new Conv(a.rate,a.ch),bytes=0,peak=0,sum=0,n=0,err='';
  try{p=this.o.spawn(a.bin,a.args,env);}catch(e){return cb({ok:false,error:e.message});}
  p.stdout.on('data',function(d){bytes+=d.length;var b=cv.push(d);for(var i=0;i+1<b.length;i+=2){var v=Math.abs(b.readInt16LE(i))/32768;if(v>peak)peak=v;sum+=v*v;n++;}});if(p.stderr)p.stderr.on('data',function(d){err+=String(d);});
  var fin=false,end=function(code){if(fin)return;fin=true;var r=n?Math.sqrt(sum/n):0;cb({ok:bytes>0&&r>0.002,bytes:bytes,rms:Math.round(r*10000)/10000,peak:Math.round(peak*1000)/1000,exit:code,stderr:err.slice(-200),
    hint:bytes===0?'La fuente no da audio (no existe o no hay permiso).':r<=0.002?'Llega audio pero en silencio: esta fuente no lleva el sonido de la app (típico con Netflix por hardware). Prueba otra fuente.':'Esta fuente sí lleva el sonido.'});};
  p.on('close',end);setTimeout(function(){try{p.kill('SIGTERM');}catch(e){}setTimeout(function(){end('timeout');},800);},5000);};
/* voice */
function fold(t){var s=String(t||'').toLowerCase();try{if(s.normalize)return s.normalize('NFD').replace(/[\u0300-\u036f]/g,'');}catch(e){}return s.replace(/[áàä]/g,'a').replace(/[éèë]/g,'e').replace(/[íìï]/g,'i').replace(/[óòö]/g,'o').replace(/[úùü]/g,'u');}
function parse(t){var s=fold(t);
  if(/\b(desactiva|quita|para|deten|apaga)\b.*\bdoblaje\b|\bsin doblaje\b/.test(s))return {op:'off'};
  if(/\b(muestra|captura|coge|toma|aprende)\b.*\bvoz\b/.test(s)&&/\b(doblaje|dobla|persona|actor|actriz|habla|video|tele)\b/.test(s))return {op:'sample'};
  var m=/\b(?:dobla(?:me|lo|la)?|doblaje|traduce(?:me)?(?: la voz| el audio)?)\b.*?\b(?:al|en|a)\s+(castellano|espanol|ingles|frances|chino|japones|coreano)\b/.exec(s);
  if(m)return {op:'on',lang:{castellano:'es',espanol:'es',ingles:'en',frances:'fr',chino:'zh',japones:'ja',coreano:'ko'}[m[1]]};
  if(/\b(activa|pon|enciende|empieza)\b.*\bdoblaje\b|\bdobla (esto|la peli|la pelicula|la serie|el video)\b/.test(s))return {op:'on'};
  if(/\bestado del doblaje\b|\bcomo va el doblaje\b/.test(s))return {op:'status'};
  return null;}
Dub.prototype.run=function(q,cb){var r;if(q.op==='off'){this.setEnabled(false);return cb('Doblaje desactivado.');}
  if(q.op==='on'){if(q.lang)this.setTarget(q.lang);r=this.setEnabled(true);var c=this.cfg();return cb(r.ok?'Doblaje activado al '+(LANGS[c.target]||c.target).toLowerCase()+'. Tomo unos diez segundos de la voz de quien habla y después hablo con su voz. Va con varios segundos de retraso.':'No puedo activar el doblaje: '+r.error);}
  if(q.op==='sample'){var x=this.sample(function(res){cb(res.say);});if(x)cb(x.say);return;}
  var s=this.status();cb(s.running?'El doblaje está activo al '+s.targetName.toLowerCase()+'. Frases dobladas: '+s.stats.dubbed+', último retraso '+s.stats.lastLagSec+' segundos.':'El doblaje está desactivado.');};
function json(res,code,o){res.statusCode=code;res.setHeader('Content-Type','application/json; charset=utf-8');res.setHeader('Cache-Control','no-store');res.end(JSON.stringify(o));return true;}
Dub.prototype.local=function(req,res){var u=String(req.url||''),self=this;if(!/^\/hv\/dub\//.test(u))return false;var o=req.headers&&req.headers.origin;if(o&&o!=='null'&&!/^file:/.test(o))return json(res,403,{ok:false});
  var qs=require('url').parse(u,true).query||{};
  if(/^\/hv\/dub\/status/.test(u))return json(res,200,this.status());
  if(/^\/hv\/dub\/on/.test(u)){if(qs.lang)this.setTarget(qs.lang);return json(res,200,this.setEnabled(true));}
  if(/^\/hv\/dub\/off/.test(u))return json(res,200,this.setEnabled(false));
  if(/^\/hv\/dub\/target/.test(u))return json(res,200,this.setTarget(qs.lang));
  if(/^\/hv\/dub\/sample/.test(u)){var x=this.sample(function(){});return json(res,200,x||{ok:true,sampling:true,say:'Escuchando la voz de quien habla (10 s)…'});}
  if(/^\/hv\/dub\/reset/.test(u)){this.reset(function(r){json(res,200,r);});return true;}
  if(/^\/hv\/dub\/test-capture/.test(u)){this.testCapture(function(r){json(res,200,r);});return true;}
  if(/^\/hv\/dub\/config/.test(u)){if(req.method!=='POST')return json(res,200,this.publicCfg());var body='';req.on('data',function(d){body+=d;if(body.length>8192)req.destroy();});
    req.on('end',function(){var p=null;try{p=JSON.parse(body);}catch(e){}if(!p||typeof p!=='object')return json(res,400,{ok:false});var allow={backend:1,token:1,target:1,capture:1,vad:1,duck:1,play:1,subs:1,maxLagSec:1,gateWhilePlaying:1,sampleSec:1},clean={};
      for(var k in p)if(allow[k])clean[k]=p[k];if(clean.backend&&!/^https?:\/\/[^\s"']+$/.test(clean.backend))return json(res,400,{ok:false,error:'backend'});if(clean.capture&&clean.capture.mode&&!/^(pulse|arecord|cmd)$/.test(clean.capture.mode))return json(res,400,{ok:false,error:'capture.mode'});
      var was=self.running();if(was)self.stop();self.saveCfg(clean);if(was)self.start();json(res,200,{ok:true,cfg:self.publicCfg()});});return true;}
  return json(res,404,{ok:false});};
module.exports={Dub:Dub,Vad:Vad,Conv:Conv,parse:parse,gainWav:gainWav,wavHeader:wavHeader,LANGS:LANGS,DEF:DEF};
