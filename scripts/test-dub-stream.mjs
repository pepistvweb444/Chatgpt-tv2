// node scripts/test-dub-stream.mjs  — offline test of api/dub-stream.js (all providers mocked, no network, no cost)
import assert from 'node:assert';
process.env.DUB_TOKEN = 'test-token-only';
const M = await import('../api/dub-stream.js');
const H = M.default, P = M.P;
let ok = 0; const t = async (n, f) => { await f(); ok++; console.log('ok', n); };
function voice(sec, f0, tilt, seed = 1) {          // harmonic "voice": f0 + formant-ish tilt, syllable envelope, small noise
  const n = Math.round(sec * 16000), b = Buffer.alloc(n * 2); let r = seed;
  let ph = 0; for (let i = 0; i < n; i++) { const tt = i / 16000; let v = 0; const vib = f0 * (1 + 0.02 * Math.sin(2 * Math.PI * 5 * tt)); ph += 2 * Math.PI * vib / 16000;
    for (let h = 1; h * vib < 7000; h++) v += Math.sin(h * ph) / Math.pow(h, tilt) * (h * vib > 900 && h * vib < 1400 ? 2.2 : 1);
    const env = 0.35 + 0.65 * Math.max(0, Math.sin(2 * Math.PI * 3 * tt)); r = (r * 1103515245 + 12345) & 0x7fffffff;
    b.writeInt16LE(Math.max(-32767, Math.min(32767, Math.round((v * 0.12 * env + (r / 0x7fffffff - 0.5) * 0.004) * 32767))), i * 2); }
  return b;
}
function call(method, query, body, headers = {}) {
  return new Promise(resolve => { const res = { code: 0, hdr: {}, status(c) { this.code = c; return this; }, setHeader(k, v) { this.hdr[k] = v; }, json(o) { resolve({ code: this.code, body: o }); } };
    H({ method, query, headers: { 'x-dub-token': 'test-token-only', ...headers }, rawBody: body || Buffer.alloc(0) }, res); });
}
const enrolls = [], synths = [];
P.sttGroq = async (w) => ({ text: 'Hello, how are you today?', lang: 'en', provider: 'groq' });
P.sttGemini = async () => { throw new Error('should not be called'); };
P.trGemini = async (text, from, target) => { assert.equal(from, 'en'); assert.equal(target, 'es'); return 'Hola, ¿qué tal estás hoy?'; };
P.ovEnroll = async (profile, pcm) => { enrolls.push([profile, pcm.length / 32000]); };
P.ovSynth = async (profile, text, lang) => { synths.push([profile, text, lang]); return Buffer.from('RIFFfakewav'); };

await t('health GET needs no token and shows no secrets', async () => { const r = await call('GET', {}, null, { 'x-dub-token': '' }); assert.equal(r.code, 200); assert.equal(r.body.configured, true); assert(!JSON.stringify(r.body).includes('test-token')); assert.deepEqual(r.body.targets, ['es', 'en', 'fr', 'zh', 'ja', 'ko']); });
await t('wrong / missing token -> 401', async () => { assert.equal((await call('POST', { session: 'tv1234' }, voice(2, 120, 1), { 'x-dub-token': 'nope' })).code, 401); assert.equal((await call('POST', { session: 'tv1234' }, voice(2, 120, 1), { 'x-dub-token': '' })).code, 401); });
await t('bad session / unsupported target', async () => { assert.equal((await call('POST', { session: '../x' }, voice(2, 120, 1))).code, 400); assert.equal((await call('POST', { session: 'tv1234', target: 'de' }, voice(2, 120, 1))).code, 422); });
await t('silence is skipped without calling STT', async () => { const r = await call('POST', { session: 'tvsil1', target: 'es' }, Buffer.alloc(32000 * 2)); assert.equal(r.body.skip, 'silence'); });
const A1 = voice(4, 115, 1.1, 3), A2 = voice(4, 118, 1.1, 7), A3 = voice(3.5, 116, 1.1, 9), B1 = voice(4, 230, 2.2, 5);
await t('fingerprints: same voice similar, different voice not', async () => { const fa = M.fingerprint(A1), fa2 = M.fingerprint(A2), fb = M.fingerprint(B1); const sAA = M.similarity(fa, fa2), sAB = M.similarity(fa, fb); console.log('   sim A~A', sAA.toFixed(3), 'A~B', sAB.toFixed(3)); assert(sAA > 0.86); assert(sAB < 0.86); });
let seg1;
await t('1st phrase of speaker A: detected en, translated to es-ES, voice enrolled provisionally from the video audio (>=3 s)', async () => {
  const r = await call('POST', { session: 'tvabc1', target: 'es-ES' }, A1); console.log('   ', JSON.stringify(r.body)); assert.equal(r.code, 200, JSON.stringify(r.body)); const b = r.body;
  assert.equal(b.lang, 'en'); assert.equal(b.translation, 'Hola, ¿qué tal estás hoy?'); assert.equal(b.speaker, 1); assert.equal(b.voice, 'clone-provisional'); assert.equal(enrolls.length, 1); assert.equal(enrolls[0][0], 'dub_tvabc1_1'); seg1 = b.seg; });
await t('tts uses that cloned profile in ES', async () => { const r = await call('POST', { session: 'tvabc1', action: 'tts', seg: String(seg1) }); assert.equal(r.code, 200); assert.equal(synths[0][0], 'dub_tvabc1_1'); assert.equal(synths[0][2], 'es'); assert(r.body.audioB64); });
await t('same speaker keeps id; sample grows to ~10 s -> re-enroll as full clone', async () => {
  const r2 = await call('POST', { session: 'tvabc1', target: 'es' }, A2); assert.equal(r2.body.speaker, 1);
  const r3 = await call('POST', { session: 'tvabc1', target: 'es' }, A3); assert.equal(r3.body.speaker, 1); assert.equal(r3.body.voice, 'clone'); assert.equal(enrolls.length, 2); assert(enrolls[1][1] >= 9.5, 'enrolled ' + enrolls[1][1]); });
await t('speaker change -> new speaker, new sample, own profile', async () => { const r = await call('POST', { session: 'tvabc1', target: 'es' }, B1); assert.equal(r.body.speaker, 2); assert.equal(r.body.voice, 'clone-provisional'); assert.equal(enrolls[2][0], 'dub_tvabc1_2');
  const back = await call('POST', { session: 'tvabc1', target: 'es' }, voice(3, 117, 1.1, 11)); assert.equal(back.body.speaker, 1, 'A comes back -> A again'); });
await t('same language as target -> no dubbing', async () => { P.sttGroq = async () => ({ text: 'Hola a todos', lang: 'es', provider: 'groq' }); const r = await call('POST', { session: 'tvabc1', target: 'es' }, A1); assert.equal(r.body.skip, 'same_language'); });
await t('Groq down -> Gemini STT; Gemini translate down -> Groq LLM; OpenAI off by default', async () => {
  P.sttGroq = async () => { throw new Error('429'); }; P.sttGemini = async () => ({ text: 'Bonjour', lang: 'fr', provider: 'gemini' });
  P.trGemini = async () => { throw new Error('quota'); }; P.trGroq = async () => 'Buenos días';
  const r = await call('POST', { session: 'tvabc2', target: 'es' }, A1); assert.equal(r.body.translation, 'Buenos días'); assert.equal(r.body.provider, 'gemini'); assert(r.body.warnings.length >= 2); });
await t('manual 10 s sample from TV -> enroll now', async () => { const r = await call('POST', { session: 'tvabc3', action: 'sample' }, voice(10, 130, 1.3, 4)); assert.equal(r.code, 200); assert.equal(r.body.voice, 'clone'); assert.equal(enrolls[enrolls.length - 1][0], 'dub_tvabc3_1'); });
await t('tts before any voice -> 409 voice_pending; reset', async () => { assert.equal((await call('POST', { session: 'tvabc9', action: 'tts', seg: '1' })).code, 404); assert.equal((await call('POST', { session: 'tvabc1', action: 'reset' })).body.ok, true); });
await t('DUB_TOKEN unset -> 503 for every POST', async () => { delete process.env.DUB_TOKEN; assert.equal((await call('POST', { session: 'tvabc1' }, A1)).code, 503); process.env.DUB_TOKEN = 'test-token-only'; });
console.log('dub-stream: ' + ok + ' ok');
