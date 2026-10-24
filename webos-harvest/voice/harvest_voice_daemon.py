#!/usr/bin/env python3
import json, os, re, subprocess, time, threading

APP_ID="com.jarvis.harvesttv"
LOG="/tmp/harvest-voice.log"
DISABLE_FILE="/tmp/voiceinput_disable_set_input_event"
VOICE_SERVICE="voiceconductor.service"
LOG_PATHS=["/var/log/messages","/var/log/lginput2.log"]

def log(msg):
    try:
        with open(LOG,"a",encoding="utf-8") as f:
            f.write(time.strftime("%H:%M:%S ")+msg+"\n")
    except Exception:
        pass

def sh(args):
    try:
        return subprocess.run(args,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,timeout=6).stdout.strip()
    except Exception as e:
        log("cmd failed: %s"%e); return ""

def suppress_lg():
    try:
        open(DISABLE_FILE,"a").close()
    except Exception as e:
        log("disable-file failed: %s"%e)
    sh(["systemctl","stop",VOICE_SERVICE])

def restore_lg():
    try:
        os.unlink(DISABLE_FILE)
    except FileNotFoundError:
        pass
    except Exception as e:
        log("unlink failed: %s"%e)
    sh(["systemctl","start",VOICE_SERVICE])

def launch_jarvis():
    payload='{"id":"%s","params":{"jarvisVoice":true}}'%APP_ID
    out=sh(["/usr/bin/luna-send-pub","-n","1","-f","luna://com.webos.applicationManager/launch",payload])
    log("launch: "+out[:300])

def pick_log():
    for p in LOG_PATHS:
        if os.path.isfile(p): return p
    return LOG_PATHS[0]

VOICE_RE=re.compile(r'lginput2 NL_BUTTON_CLICK\s+(\{.*\})')

def is_voice(line):
    m=VOICE_RE.search(line)
    if not m: return False
    try:
        d=json.loads(m.group(1))
        return d.get("button_type")=="KEY_VOICE"
    except Exception:
        return False

def run():
    suppress_lg()
    src=pick_log()
    log("watching "+src)
    while True:
        p=subprocess.Popen(["tail","-F","-n","0",src],stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,text=True,bufsize=1)
        try:
            for line in p.stdout:
                if is_voice(line):
                    suppress_lg()
                    launch_jarvis()
                    time.sleep(.7)
        except Exception as e:
            log("watch error: %s"%e)
        finally:
            try:p.terminate()
            except Exception:pass
        time.sleep(.5)

if __name__=="__main__":
    run()
