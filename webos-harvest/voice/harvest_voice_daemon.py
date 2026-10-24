#!/usr/bin/env python3
import audioop, http.client, json, os, re, socket, subprocess, time, wave
from io import BytesIO
from urllib.parse import urlparse

APP_ID="com.jarvis.harvesttv"
BACKEND="https://chatgpt-tv2.vercel.app"
LOG="/tmp/harvest-voice.log"
DISABLE_FILE="/tmp/voiceinput_disable_set_input_event"
VOICECONDUCTOR="voiceconductor.service"
LOG_PATHS=["/var/log/messages","/var/log/lginput2.log"]
VOICE_SOCKET="/var/voiceinput/voiceinput_remote1.soc"
START_URI="luna://com.webos.service.voiceinput/startStreaming"
STOP_URI="luna://com.webos.service.voiceinput/stopStreaming"
START_PARAMS='{"deviceType":"remote","keyType":"thinQtv","subscribe":true}'
STOP_PARAMS='{"deviceType":"remote"}'
RATE=16000
CHUNK=3200
SPEECH_RMS=140
SILENCE_SEC=0.95
MAX_SEC=8.0

def log(msg):
    try:
        with open(LOG,"a",encoding="utf-8") as f:
            f.write(time.strftime("%H:%M:%S ")+msg+"\n")
    except Exception:
        pass

def run_cmd(args,timeout=8):
    try:
        return subprocess.run(args,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,timeout=timeout).stdout.strip()
    except Exception as e:
        log("cmd failed %r: %s"%(args,e)); return ""

def suppress_lg():
    try: open(DISABLE_FILE,"a").close()
    except Exception as e: log("disable marker: %s"%e)
    run_cmd(["systemctl","stop",VOICECONDUCTOR],4)

def restore_lg():
    try: os.unlink(DISABLE_FILE)
    except FileNotFoundError: pass
    except Exception as e: log("unlink marker: %s"%e)
    run_cmd(["systemctl","start",VOICECONDUCTOR],4)

def launch_jarvis(params):
    payload={"id":APP_ID,"params":params}
    out=run_cmd(["/usr/bin/luna-send-pub","-n","1","-f","luna://com.webos.applicationManager/launch",json.dumps(payload,separators=(",",":"))],6)
    log("launch "+out[:180])

def pick_log():
    for p in LOG_PATHS:
        if os.path.isfile(p): return p
    return LOG_PATHS[0]

VOICE_RE=re.compile(r'lginput2 NL_BUTTON_CLICK\s+(\{.*\})')

def parse_voice(line):
    m=VOICE_RE.search(line)
    if not m: return None
    try:
        d=json.loads(m.group(1))
        if d.get("button_type")!="KEY_VOICE": return None
        h=str(d.get("button_history") or d.get("history") or d.get("state") or "").lower()
        if "release" in h or h in ("up","released"): return "release"
        return "press"
    except Exception:
        return None

def http_json(method,url,obj=None,headers=None,raw=None,timeout=45):
    u=urlparse(url)
    conn=http.client.HTTPSConnection(u.hostname,u.port or 443,timeout=timeout) if u.scheme=="https" else http.client.HTTPConnection(u.hostname,u.port or 80,timeout=timeout)
    hdr=dict(headers or {})
    body=raw
    if obj is not None:
        body=json.dumps(obj).encode()
        hdr["Content-Type"]="application/json"
    conn.request(method,u.path+("?" + u.query if u.query else ""),body=body,headers=hdr)
    r=conn.getresponse()
    data=r.read()
    ctype=r.getheader("content-type","")
    if r.status<200 or r.status>=300:
        raise RuntimeError("HTTP %s %s"%(r.status,data[:200]))
    if "json" in ctype:
        return json.loads(data.decode("utf-8","ignore") or "{}")
    return data

def wav_bytes(pcm):
    bio=BytesIO()
    with wave.open(bio,"wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(RATE); w.writeframes(pcm)
    return bio.getvalue()

def start_remote_stream():
    suppress_lg()
    proc=subprocess.Popen(
        ["/usr/bin/luna-send-pub","-i",START_URI,START_PARAMS],
        stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True
    )
    deadline=time.time()+3.0
    while time.time()<deadline and not os.path.exists(VOICE_SOCKET):
        time.sleep(.05)
    return proc

def stop_remote_stream(proc):
    run_cmd(["/usr/bin/luna-send-pub","-n","1","-f",STOP_URI,STOP_PARAMS],4)
    try: proc.terminate()
    except Exception: pass

def capture_pcm():
    proc=start_remote_stream()
    s=None
    try:
        deadline=time.time()+3.0
        while time.time()<deadline:
            if os.path.exists(VOICE_SOCKET):
                try:
                    s=socket.socket(socket.AF_UNIX,socket.SOCK_STREAM)
                    s.settimeout(.5)
                    s.connect(VOICE_SOCKET)
                    break
                except Exception:
                    if s:
                        try:s.close()
                        except Exception:pass
                    s=None
            time.sleep(.06)
        if s is None:
            raise RuntimeError("voice socket unavailable")
        chunks=[]; heard=False; speech_frames=0; last_voice=time.time(); started=time.time()
        while time.time()-started<MAX_SEC:
            try: data=s.recv(CHUNK)
            except socket.timeout:
                data=b""
            if data:
                chunks.append(data)
                try: rms=audioop.rms(data,2)
                except Exception: rms=0
                if rms>=SPEECH_RMS:
                    speech_frames+=1
                    if speech_frames>=2: heard=True
                    last_voice=time.time()
                elif heard and time.time()-last_voice>=SILENCE_SEC:
                    break
            elif heard and time.time()-last_voice>=SILENCE_SEC:
                break
        pcm=b"".join(chunks)
        log("capture bytes=%d heard=%s"%(len(pcm),heard))
        if not heard or len(pcm)<3200:
            return b""
        return pcm
    finally:
        if s:
            try:s.close()
            except Exception:pass
        stop_remote_stream(proc)

def ask_jarvis():
    launch_jarvis({"jarvisVoice":True,"voiceState":"listening"})
    pcm=capture_pcm()
    if not pcm:
        launch_jarvis({"jarvisVoice":True,"voiceState":"error","reply":"No he podido oírte."})
        return
    wav=wav_bytes(pcm)
    tr=http_json("POST",BACKEND+"/api/transcribe",headers={"Content-Type":"audio/wav","X-Filename":"harvest-voice.wav"},raw=wav)
    text=str(tr.get("text","")).strip()
    if not text:
        launch_jarvis({"jarvisVoice":True,"voiceState":"error","reply":"No he entendido el audio."})
        return
    log("transcript "+text[:160])
    chat=http_json("POST",BACKEND+"/api/chat",obj={
        "message":text,
        "assistantName":"Jarvis",
        "conversationId":"harvest-tv-voice",
        "client":"harvest-tv",
        "history":[]
    },timeout=60)
    reply=str(chat.get("reply","")).strip()
    launch_jarvis({"jarvisVoice":True,"voiceState":"answer","transcript":text,"reply":reply})

def run():
    suppress_lg()
    src=pick_log()
    log("watching "+src)
    busy=False
    while True:
        p=subprocess.Popen(["tail","-F","-n","0",src],stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,text=True,bufsize=1)
        try:
            for line in p.stdout:
                ev=parse_voice(line)
                if ev=="press" and not busy:
                    busy=True
                    try: ask_jarvis()
                    except Exception as e:
                        log("voice error: %s"%e)
                        launch_jarvis({"jarvisVoice":True,"voiceState":"error","reply":"Ha ocurrido un error con la voz."})
                    finally:
                        busy=False
        except Exception as e:
            log("watch error: %s"%e)
        finally:
            try:p.terminate()
            except Exception:pass
        time.sleep(.4)

if __name__=="__main__":
    run()
