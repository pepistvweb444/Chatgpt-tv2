# 1.0.110 Doblaje panel: button in the top bar, panel renders with mocked daemon, D-pad + OK toggles, BACK closes, 0 page errors.
import json, pathlib, sys
from playwright.sync_api import sync_playwright
APP = pathlib.Path(__file__).resolve().parent.parent / 'src/usr/palm/applications/com.jarvis.harvesttv'
STATUS = {"ok": True, "version": "1.0.110", "enabled": False, "running": False, "target": "es", "targetName": "Castellano",
          "langs": {"es": "Castellano", "en": "Inglés", "fr": "Francés", "zh": "Chino", "ja": "Japonés", "ko": "Coreano"},
          "capture": {"mode": "pulse"}, "stats": {"lastLagSec": 0}, "tokenSet": True,
          "last": [{"lang": "en", "speaker": 1, "transcript": "Where are you going?", "translation": "¿Adónde vas?", "voice": "clone", "audio": "doblado", "lagSec": 6.2}]}
CFG = {"backend": "https://ctv2.185-245-182-55.sslip.io", "capture": {"mode": "pulse", "device": ""}, "tokenSet": True}
calls, errors = [], []
with sync_playwright() as p:
    b = p.chromium.launch(executable_path='/usr/bin/google-chrome', headless=True, args=['--no-sandbox']); pg = b.new_page(viewport={"width": 1920, "height": 1080})
    pg.on('pageerror', lambda e: errors.append(str(e)))
    def route(r):
        u = r.request.url; calls.append(u)
        if '/hv/dub/on' in u: STATUS['running'] = True
        body = CFG if '/hv/dub/config' in u else STATUS
        r.fulfill(status=200, content_type='application/json', body=json.dumps(body))
    pg.route('http://127.0.0.1:8768/**', route)
    pg.set_content('<html><body style="background:#000"><div class="top-actions"><button class="voice-btn">🎙</button></div></body></html>')
    pg.add_script_tag(path=str(APP / 'jarvis-dub-1.0.110.js'))
    pg.wait_for_selector('[data-action="dub110"]'); assert pg.inner_text('[data-action="dub110"]') == '🌐 Doblaje'
    pg.click('[data-action="dub110"]'); pg.wait_for_selector('#dub110 [data-c="toggle"]')
    pg.wait_for_function("document.querySelector('#dub110').innerText.indexOf('Adónde vas')>=0")
    txt = pg.inner_text('#dub110'); assert 'Castellano' in txt and 'Probar captura' in txt and 'Tomar muestra 10 s' in txt, txt
    assert 'SECRET' not in pg.content()
    pg.keyboard.press('Enter'); pg.wait_for_function("document.querySelector('#dub110').innerText.indexOf('Activo')>=0")
    assert any('/hv/dub/on' in c for c in calls)
    pg.screenshot(path='/tmp/dub110-panel.png')
    pg.keyboard.press('Escape'); assert pg.evaluate("window.__jv110dub.isOpen()") is False
    assert not errors, errors
    b.close()
print('test_1110_ui: ok')
