package io.github.hazymorning.klangbild.setup

import io.github.hazymorning.klangbild.config.OwnValues
import io.github.hazymorning.klangbild.config.QuickActions
import io.github.hazymorning.klangbild.config.Settings
import io.github.hazymorning.klangbild.domain.EntityId
import io.github.hazymorning.klangbild.text.Strings
import io.github.hazymorning.klangbild.text.Texts
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * The phone's setup page, served by the TV itself, in the language the TV shows. Self-contained
 * (no external resources) and as quiet as the app: one sans-serif face (the phone's own),
 * sentence case, warm near-black or paper as the phone is set.
 */
object SetupPage {
    fun html(t: Texts, prefilledCode: String?): String {
        val code = prefilledCode.orEmpty()
        val words = TEXTS.associate { (key, id) -> key to t[id] }
        // Words for the page's script, as one JSON object (escaped, so no text can end the script).
        val script = buildJsonObject { for ((key, id) in SCRIPT_TEXTS) put(key, JsonPrimitive(t[id])) }.toString().replace("</", "<\\/")
        var page = TEMPLATE
            .replace("__LANG__", t.locale.language)
            .replace("__CODE__", escape(code))
            .replace("__CODE_HIDDEN__", if (code.isNotEmpty()) "hidden" else "")
            .replace("__MAX_ACTIONS__", QuickActions.MAX.toString())
            .replace("__MAX_NAME__", QuickActions.MAX_NAME.toString())
            .replace("__MAX_VALUES__", OwnValues.MAX.toString())
            .replace("__MAX_VALUE_NAME__", OwnValues.MAX_NAME.toString())
            .replace("__MAX_SLEEP__", Settings.MAX_SLEEP_MINUTES.toString())
            .replace("__THIS_TV__", EntityId.THIS_TV)
            .replace("__SCRIPT_TEXTS__", script)
        for ((key, value) in words) page = page.replace("{{$key}}", escape(value))
        return page
    }

    /** [s] safe in HTML text and attribute values. */
    private fun escape(s: String): String = buildString(s.length) {
        for (c in s) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(c)
            }
        }
    }

    /** The page's fixed words: `{{key}}` in the template. */
    private val TEXTS = listOf(
        "title" to Strings.pageTitle,
        "subtitle" to Strings.pageSubtitle,
        "codeHeading" to Strings.pageCodeHeading,
        "codeLabel" to Strings.pageCodeLabel,
        "haHeading" to Strings.pageHaHeading,
        "address" to Strings.pageAddress,
        "signIn" to Strings.pageSignIn,
        "signInHint" to Strings.pageSignInHint,
        "tokenToggle" to Strings.pageTokenToggle,
        "tokenLabel" to Strings.pageTokenLabel,
        "tokenHint" to Strings.pageTokenHint,
        "find" to Strings.pageFind,
        "speakerHeading" to Strings.pageSpeakerHeading,
        "speakersEmpty" to Strings.pageSpeakersEmpty,
        "speakersTwice" to Strings.pageSpeakersTwice,
        "entityLabel" to Strings.pageEntityLabel,
        "entityPlaceholder" to Strings.pageEntityPlaceholder,
        "generalHeading" to Strings.pageGeneralHeading,
        "sleepBefore" to Strings.pageSleepBefore,
        "sleepAfter" to Strings.pageSleepAfter,
        "release" to Strings.pageRelease,
        "close" to Strings.pageClose,
        "clock" to Strings.pageClock,
        "remote" to Strings.pageRemote,
        "maHeading" to Strings.pageMaHeading,
        "maHint" to Strings.pageMaHint,
        "maTokenPlaceholder" to Strings.pageMaTokenPlaceholder,
        "maTokenHint" to Strings.pageMaTokenHint,
        "tvSpeaker" to Strings.pageTvSpeaker,
        "tvSpeakerHint" to Strings.pageTvSpeakerHint,
        "pairToggle" to Strings.pagePairToggle,
        "pairHint" to Strings.pagePairHint,
        "lightsHeading" to Strings.pageLightsHeading,
        "lightsHint" to Strings.pageLightsHint,
        "lightsEmpty" to Strings.pageLightsEmpty,
        "actionsHeading" to Strings.pageActionsHeading,
        "actionsHint" to Strings.pageActionsHint,
        "actionsEmpty" to Strings.pageActionsEmpty,
        "actionAdd" to Strings.pageActionAdd,
        "valuesHeading" to Strings.pageValuesHeading,
        "valuesHint" to Strings.pageValuesHint,
        "valuesEmpty" to Strings.pageValuesEmpty,
        "valueAdd" to Strings.pageValueAdd,
        "save" to Strings.pageSave,
    )

    /** Words the page's script shows as it goes. */
    private val SCRIPT_TEXTS = listOf(
        "noSpeakers" to Strings.pageNoSpeakers,
        "thisTv" to Strings.pageThisTv,
        "thisTvMeta" to Strings.pageThisTvMeta,
        "noLights" to Strings.pageNoLights,
        "left" to Strings.pageLightLeft,
        "center" to Strings.pageLightCenter,
        "right" to Strings.pageLightRight,
        "none" to Strings.pageNone,
        "connecting" to Strings.pageConnecting,
        "failed" to Strings.pageFailed,
        "connected" to Strings.pageConnected,
        "tvUnreachable" to Strings.pageTvUnreachable,
        "saved" to Strings.pageSaved,
        "tokenKept" to Strings.pageTokenKept,
        "signingIn" to Strings.pageSigningIn,
        "signedIn" to Strings.pageSignedIn,
        "signInFailed" to Strings.pageSigninFailed,
        "withToken" to Strings.pageWithToken,
        "noActions" to Strings.pageNoActions,
        "actionName" to Strings.pageActionName,
        "actionWhat" to Strings.pageActionWhat,
        "actionRemove" to Strings.pageActionRemove,
        "search" to Strings.pageSearch,
        "searchNone" to Strings.pageSearchNone,
        "searchMore" to Strings.pageSearchMore,
        "variables" to Strings.pageVariables,
        "variablesHint" to Strings.pageVariablesHint,
        "variablesExample" to Strings.pageVariablesExample,
        "moreVariables" to Strings.pageMoreVariables,
        "show" to Strings.pageShow,
        "showAlways" to Strings.pageShowAlways,
        "showWhen" to Strings.pageShowWhen,
        "entity" to Strings.pageEntity,
        "state" to Strings.pageState,
        "testIs" to Strings.pageTestIs,
        "testIsNot" to Strings.pageTestIsNot,
        "testAbove" to Strings.pageTestAbove,
        "testBelow" to Strings.pageTestBelow,
        "conditionHint" to Strings.pageConditionHint,
        "conditionNumber" to Strings.pageConditionNumber,
        "now" to Strings.pageNow,
        "noEntities" to Strings.pageNoEntities,
        "valueName" to Strings.pageValueName,
        "valueWhat" to Strings.pageValueWhat,
    )

    private val TEMPLATE = """
<!doctype html>
<html lang="__LANG__">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta name="color-scheme" content="dark light">
<title>{{title}}</title>
<style>
  :root {
    --wall: #0f0d0c; --card: #171412; --line: #2c2723; --ink: #ebe3d8; --muted: #b3a89b;
    --faint: #8a8076; --accent: #ebe3d8; --on-accent: #141110; --bad: #e0876f;
  }
  @media (prefers-color-scheme: light) {
    :root {
      --wall: #ebe6df; --card: #f6f2ec; --line: #d6cec4; --ink: #2b2622; --muted: #5e554d;
      --faint: #7a7067; --accent: #2b2622; --on-accent: #f6f2ec; --bad: #a8442e;
    }
  }
  * { box-sizing: border-box; }
  html, body { margin: 0; background: var(--wall); color: var(--ink); }
  body { font: 17px/1.5 system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
         padding: max(28px, env(safe-area-inset-top)) 22px 48px; max-width: 560px; margin: 0 auto; }
  h1 { font-size: 36px; font-weight: 500; line-height: 1.1; margin: 8px 0 4px; letter-spacing: -0.01em; }
  .sub { color: var(--muted); margin: 0 0 28px; }
  section { border-top: 1px solid var(--line); padding: 22px 0 8px; }
  h2 { font-size: 17px; font-weight: 600; color: var(--ink); margin: 0 0 6px; }
  label { display: block; color: var(--muted); font-size: 15px; margin: 14px 0 6px; }
  input[type=text], input[type=url], input[type=number], textarea, select {
    width: 100%; background: var(--card); color: var(--ink); border: 1px solid var(--line); border-radius: 10px;
    padding: 12px 14px; font: inherit; outline: none; }
  input:focus, textarea:focus { border-color: var(--accent); }
  textarea { min-height: 92px; resize: none; font-size: 14px; overflow-wrap: anywhere; }
  .hint { color: var(--faint); font-size: 15px; margin: 8px 0 0; }
  button { appearance: none; border: 1px solid var(--line); background: transparent; color: var(--ink);
    border-radius: 10px; padding: 12px 20px; font: 600 15px system-ui, sans-serif; margin-top: 18px; }
  button.primary { background: var(--accent); color: var(--on-accent); border-color: var(--accent); width: 100%; padding: 15px; font-size: 16px; }
  button:disabled { opacity: .45; }
  details { margin-top: 18px; }
  summary { list-style: none; cursor: pointer; color: var(--muted); font-size: 15px; text-decoration: underline;
    text-underline-offset: 3px; text-decoration-color: var(--line); }
  summary::-webkit-details-marker { display: none; }
  .speakers { display: grid; gap: 10px; }
  .speaker { display: flex; gap: 12px; align-items: center; background: var(--card); border: 1px solid var(--line);
    border-radius: 12px; padding: 12px 14px; margin: 0; color: var(--ink); }
  .speaker:has(input:checked) { border-color: var(--accent); }
  .speaker input { accent-color: var(--accent); width: 20px; height: 20px; }
  .speaker .name { font-size: 17px; }
  .speaker .meta { color: var(--faint); font-size: 14px; }
  .row { display: flex; gap: 12px; align-items: center; margin: 12px 0; color: var(--muted); }
  .row input[type=checkbox], .row input[type=radio] { accent-color: var(--accent); width: 20px; height: 20px; flex: none; }
  .inline { display: flex; gap: 10px; align-items: center; color: var(--muted); }
  .inline input { width: 90px; }
  .status { min-height: 1.5em; margin-top: 16px; }
  .status.bad { color: var(--bad); } .status.good { color: var(--ink); }
  .action { border-top: 1px solid var(--line); margin-top: 18px; padding-top: 4px; }
  .action:first-child { border-top: 0; margin-top: 0; padding-top: 0; }
  button.quiet { border: 0; padding: 10px 0 0; margin-top: 6px; color: var(--muted); font-weight: 500; text-decoration: underline;
    text-underline-offset: 3px; text-decoration-color: var(--line); }
  .chosen { color: var(--faint); font-size: 14px; margin: 6px 0 0; overflow-wrap: anywhere; }
  .chosen:empty { display: none; }
  .results { margin-top: 6px; border: 1px solid var(--line); border-radius: 10px; overflow: hidden; }
  .results button { display: block; width: 100%; margin: 0; padding: 10px 14px; border: 0; border-top: 1px solid var(--line);
    border-radius: 0; background: var(--card); color: var(--ink); font: 400 16px/1.35 system-ui, sans-serif; text-align: left; }
  .results button:first-child { border-top: 0; }
  .results .meta { display: block; color: var(--faint); font-size: 14px; overflow-wrap: anywhere; }
  .results p { margin: 0; padding: 10px 14px; color: var(--faint); font-size: 15px; }
  .compare { margin-top: 10px; }
  .pair { display: grid; grid-template-columns: auto 1fr; gap: 8px; margin-top: 8px; }
  textarea.vars { min-height: 64px; font-size: 15px; }
  .hidden { display: none; }
</style>
</head>
<body>
  <h1>Klangbild</h1>
  <p class="sub">{{subtitle}}</p>

  <section id="codeBox" class="__CODE_HIDDEN__">
    <h2>{{codeHeading}}</h2>
    <label for="code">{{codeLabel}}</label>
    <input id="code" type="text" inputmode="numeric" autocomplete="one-time-code" maxlength="6" value="__CODE__">
  </section>

  <section>
    <h2>{{haHeading}}</h2>
    <label for="url">{{address}}</label>
    <input id="url" type="url" inputmode="url" placeholder="http://192.168.1.10:8123" autocapitalize="off" autocorrect="off" spellcheck="false">
    <button id="signIn">{{signIn}}</button>
    <p class="hint">{{signInHint}}</p>
    <p class="status" id="haStatus"></p>
    <details id="tokenBox">
      <summary>{{tokenToggle}}</summary>
      <label for="token">{{tokenLabel}}</label>
      <textarea id="token" placeholder="eyJ0eXAiOiJKV1QiLCJhbGciOi…" autocapitalize="off" autocorrect="off" spellcheck="false"></textarea>
      <p class="hint">{{tokenHint}}</p>
      <button id="find">{{find}}</button>
    </details>
  </section>

  <section>
    <h2>{{speakerHeading}}</h2>
    <p class="hint" id="speakersTwice" hidden>{{speakersTwice}}</p>
    <div class="speakers" id="speakers"><p class="hint">{{speakersEmpty}}</p></div>
    <label for="entity">{{entityLabel}}</label>
    <input id="entity" type="text" placeholder="{{entityPlaceholder}}" autocapitalize="off" autocorrect="off" spellcheck="false">
  </section>

  <section>
    <h2>{{generalHeading}}</h2>
    <div class="inline">{{sleepBefore}} <input id="sleep" type="number" min="1" max="__MAX_SLEEP__" value="10"> {{sleepAfter}}</div>
    <label class="row"><input type="radio" name="action" value="release" checked> {{release}}</label>
    <label class="row"><input type="radio" name="action" value="close"> {{close}}</label>
    <label class="row"><input id="clock" type="checkbox" checked> {{clock}}</label>
    <label class="row"><input id="remote" type="checkbox" checked> {{remote}}</label>
  </section>

  <section>
    <h2>{{maHeading}}</h2>
    <p class="hint">{{maHint}}</p>
    <label for="maUrl">{{address}}</label>
    <input id="maUrl" type="url" inputmode="url" placeholder="http://192.168.1.10:8095" autocapitalize="off" autocorrect="off" spellcheck="false">
    <label for="maToken">{{tokenLabel}}</label>
    <textarea id="maToken" placeholder="{{maTokenPlaceholder}}" autocapitalize="off" autocorrect="off" spellcheck="false"></textarea>
    <p class="hint">{{maTokenHint}}</p>
    <label class="row"><input id="tvSpeaker" type="checkbox"> {{tvSpeaker}}</label>
    <p class="hint">{{tvSpeakerHint}}</p>
    <details id="pairBox" class="hidden">
      <summary>{{pairToggle}}</summary>
      <textarea id="pairCode" readonly aria-label="{{pairToggle}}"></textarea>
      <p class="hint">{{pairHint}}</p>
    </details>
  </section>

  <section>
    <h2>{{lightsHeading}}</h2>
    <p class="hint">{{lightsHint}}</p>
    <div id="lights"><p class="hint">{{lightsEmpty}}</p></div>
  </section>

  <section>
    <h2>{{actionsHeading}}</h2>
    <p class="hint">{{actionsHint}}</p>
    <div id="actions"><p class="hint">{{actionsEmpty}}</p></div>
    <button id="addAction" class="hidden">{{actionAdd}}</button>
  </section>

  <section>
    <h2>{{valuesHeading}}</h2>
    <p class="hint">{{valuesHint}}</p>
    <div id="values"><p class="hint">{{valuesEmpty}}</p></div>
    <button id="addValue" class="hidden">{{valueAdd}}</button>
  </section>

  <section>
    <button class="primary" id="save">{{save}}</button>
    <p class="status" id="status"></p>
  </section>

<script>
  const T = __SCRIPT_TEXTS__;
  const ${'$'} = (id) => document.getElementById(id);
  const params = new URLSearchParams(location.search);
  let hasToken = false;
  // The speakers Home Assistant offers, once found; "This TV" goes before them while the TV plays for Music Assistant.
  let speakers = null;
  const THIS_TV = '__THIS_TV__';
  let lights = { lightsLeft: '', lightsCenter: '', lightsRight: '' };
  let lightsShown = false;
  // The quick settings' own buttons and the values in the status line: as stored, what Home
  // Assistant offers for them, and whether the page has shown them.
  let actions = [];
  let actionOptions = [];
  let actionsShown = false;
  let values = [];
  let entityOptions = [];
  let valuesShown = false;
  const MAX_ACTIONS = __MAX_ACTIONS__;
  const MAX_VALUES = __MAX_VALUES__;
  const MAX_RESULTS = 30;
  const TESTS = [['is', T.testIs], ['is_not', T.testIsNot], ['above', T.testAbove], ['below', T.testBelow]];
  // The code survives the trip to Home Assistant's sign-in page and back in this tab.
  if (!${'$'}('code').value) ${'$'}('code').value = sessionStorage.getItem('klangbild-code') || '';
  function code() { return ${'$'}('code').value.trim(); }
  function show(id, text, kind) { const s = ${'$'}(id); s.textContent = text || ''; s.className = 'status ' + (kind || ''); }
  function status(text, kind) { show('status', text, kind); }
  async function api(path, body) {
    const res = await fetch(path, { method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(Object.assign({ code: code() }, body || {})) });
    let data = {};
    try { data = await res.json(); } catch (e) {}
    if (!res.ok && data.ok === undefined) data.ok = false;
    if (res.status === 403) { ${'$'}('codeBox').classList.remove('hidden'); }
    return data;
  }
  function renderSpeakers(selected) {
    const box = ${'$'}('speakers'); box.textContent = '';
    // This TV is a speaker to choose once Music Assistant is there; choosing it turns playing here on.
    const tv = ${'$'}('tvSpeaker').checked || ${'$'}('maUrl').value.trim() ? [{ id: THIS_TV, name: T.thisTv, meta: T.thisTvMeta }] : [];
    const list = tv.concat(speakers || []);
    // A speaker Music Assistant plays on is mostly there twice: its own entity and Music Assistant's.
    ${'$'}('speakersTwice').hidden = !(speakers || []).some((sp) => sp.ma);
    if (!list.length) { const p = document.createElement('p'); p.className = 'hint'; p.textContent = T.noSpeakers; box.append(p); return; }
    list.forEach((sp) => {
      const row = document.createElement('label'); row.className = 'speaker';
      const r = document.createElement('input'); r.type = 'radio'; r.name = 'speaker'; r.value = sp.id;
      r.checked = sp.id === selected;
      r.onchange = () => { ${'$'}('entity').value = sp.id; if (sp.id === THIS_TV) ${'$'}('tvSpeaker').checked = true; };
      const text = document.createElement('div');
      const name = document.createElement('div'); name.className = 'name'; name.textContent = sp.name;
      const meta = document.createElement('div'); meta.className = 'meta';
      meta.textContent = sp.meta || (sp.id + (sp.ma ? ' · Music Assistant' : '') + ' · ' + sp.state);
      text.append(name, meta); row.append(r, text); box.append(row);
    });
  }
  function renderLights(list) {
    const box = ${'$'}('lights'); box.textContent = '';
    if (!list.length) { const p = document.createElement('p'); p.className = 'hint'; p.textContent = T.noLights; box.append(p); return; }
    [['lightsLeft', T.left], ['lightsCenter', T.center], ['lightsRight', T.right]].forEach(([key, label]) => {
      const l = document.createElement('label'); l.htmlFor = key; l.textContent = label;
      const sel = document.createElement('select'); sel.id = key;
      const none = document.createElement('option'); none.value = ''; none.textContent = T.none; sel.append(none);
      list.forEach((li) => {
        const o = document.createElement('option'); o.value = li.id; o.textContent = li.name + ' · ' + li.state;
        o.selected = li.id === lights[key]; sel.append(o);
      });
      box.append(l, sel);
    });
    lightsShown = true;
  }
  function optionName(id) { const o = actionOptions.find((a) => a.id === id); return o ? o.name : ''; }
  function entityOf(id) { return entityOptions.find((e) => e.id === id); }
  // Lower case without accents, for searching.
  function fold(s) { return (s || '').normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase(); }
  function label(forId, text) { const l = document.createElement('label'); l.htmlFor = forId; l.textContent = text; return l; }
  function hint(text) { const p = document.createElement('p'); p.className = 'hint'; p.textContent = text; return p; }
  function choice(id, pairs, value) {
    const sel = document.createElement('select'); sel.id = id;
    pairs.forEach(([v, text]) => { const o = document.createElement('option'); o.value = v; o.textContent = text; sel.append(o); });
    sel.value = value; return sel;
  }
  // A search field over [options] ({id, name, meta}): typing narrows the list, a tap chooses.
  function picker(id, options, selected, onPick) {
    const box = document.createElement('div');
    const input = document.createElement('input'); input.type = 'text'; input.id = id; input.placeholder = T.search;
    input.autocomplete = 'off'; input.spellcheck = false; input.setAttribute('autocapitalize', 'off'); input.setAttribute('autocorrect', 'off');
    const chosen = document.createElement('p'); chosen.className = 'chosen';
    const list = document.createElement('div'); list.className = 'results hidden';
    let current = selected || '';
    const nameOf = (v) => { const o = options.find((x) => x.id === v); return o ? o.name : v; };
    function settle() { input.value = current ? nameOf(current) : ''; chosen.textContent = current; list.classList.add('hidden'); }
    function render() {
      list.textContent = '';
      const query = input.value === nameOf(current) ? '' : input.value;
      const words = fold(query).split(/\s+/).filter(Boolean);
      const found = options.filter((o) => { o.key = o.key || fold(o.name + ' ' + o.id); return words.every((w) => o.key.includes(w)); });
      found.slice(0, MAX_RESULTS).forEach((o) => {
        const b = document.createElement('button'); b.type = 'button';
        const name = document.createElement('span'); name.textContent = o.name;
        const meta = document.createElement('span'); meta.className = 'meta'; meta.textContent = o.meta || o.id;
        b.append(name, meta);
        // The field keeps the focus until the choice is made.
        b.onmousedown = (e) => e.preventDefault();
        b.onclick = () => { current = o.id; settle(); input.blur(); onPick(o.id); };
        list.append(b);
      });
      if (!found.length) list.append(hint(T.searchNone));
      else if (found.length > MAX_RESULTS) list.append(hint(T.searchMore));
      list.classList.remove('hidden');
    }
    input.onfocus = () => { input.select(); render(); };
    input.oninput = render;
    input.onblur = () => setTimeout(settle, 200);
    settle();
    box.append(input, chosen, list);
    return box;
  }
  function entityChoices(domains) {
    return entityOptions
      .filter((e) => !domains || !domains.length || domains.includes(e.id.split('.')[0]))
      .map((e) => ({ id: e.id, name: e.name, meta: e.id + ' · ' + e.state + (e.unit ? ' ' + e.unit : '') }));
  }
  // What an entity shows now: its state, or one of its attributes (a list by its count).
  function nowOf(e, attribute) {
    if (!e) return '';
    if (!attribute) return e.state + (e.unit ? ' ' + e.unit : '');
    if (e.attrs && attribute in e.attrs) return e.attrs[attribute];
    if (e.lists && attribute in e.lists) return String(e.lists[attribute]);
    return '';
  }
  function attributeChoices(e, lists) {
    const out = [['', T.state]];
    if (e && e.attrs) Object.keys(e.attrs).forEach((k) => out.push([k, k]));
    if (e && e.lists && lists) Object.keys(e.lists).forEach((k) => out.push([k, k + ' (' + e.lists[k] + ')']));
    return out;
  }
  // A value written on the page: a number, true or false, a list or an object as JSON, else text.
  function valueOf(text) { try { return JSON.parse(text); } catch (e) { return text; } }
  function textOf(v) { return typeof v === 'string' ? v : JSON.stringify(v); }
  // A script's variables: the fields it declares, then any others, one per line as name: value.
  function variablesBox(a, i) {
    const box = document.createElement('div');
    if (!a.entity.startsWith('script.')) return box;
    const opt = actionOptions.find((o) => o.id === a.entity);
    const fields = ((opt && opt.fields) || []).filter((f) => f.key !== 'player');
    fields.forEach((f, j) => {
      const id = 'field' + i + '_' + j;
      if (!(f.key in a.variables) && f.default !== undefined) a.variables[f.key] = f.default;
      const set = (v) => { if (v === '' || v === undefined) delete a.variables[f.key]; else a.variables[f.key] = v; };
      const v = a.variables[f.key];
      if (f.type === 'boolean') {
        const row = document.createElement('label'); row.className = 'row';
        const c = document.createElement('input'); c.type = 'checkbox'; c.id = id; c.checked = v === true;
        c.onchange = () => { a.variables[f.key] = c.checked; };
        row.append(c, document.createTextNode(f.name)); box.append(row);
      } else if (f.type === 'select') {
        const pairs = (f.required ? [] : [['', T.none]]).concat(f.options.map((o) => [o.value, o.label]));
        const sel = choice(id, pairs, v === undefined ? '' : textOf(v)); sel.onchange = () => set(sel.value);
        box.append(label(id, f.name), sel);
      } else if (f.type === 'entity') {
        box.append(label(id, f.name), picker(id, entityChoices(f.domains), typeof v === 'string' ? v : '', (picked) => set(picked)));
      } else {
        const input = document.createElement('input'); input.id = id;
        input.type = f.type === 'number' ? 'number' : 'text'; if (f.type === 'number') input.step = 'any';
        input.value = v === undefined ? '' : textOf(v); input.placeholder = f.example || '';
        input.oninput = () => set(f.type === 'number' ? (input.value === '' ? '' : Number(input.value)) : input.value);
        box.append(label(id, f.name), input);
      }
      if (f.description) box.append(hint(f.description));
    });
    const declared = new Set(fields.map((f) => f.key));
    const area = document.createElement('textarea'); area.id = 'variables' + i; area.className = 'vars'; area.placeholder = T.variablesExample;
    area.setAttribute('autocapitalize', 'off'); area.setAttribute('autocorrect', 'off'); area.spellcheck = false;
    area.value = Object.keys(a.variables).filter((k) => !declared.has(k)).map((k) => k + ': ' + textOf(a.variables[k])).join('\n');
    area.oninput = () => {
      Object.keys(a.variables).forEach((k) => { if (!declared.has(k)) delete a.variables[k]; });
      area.value.split('\n').forEach((line) => {
        const m = line.match(/^\s*([A-Za-z_][A-Za-z0-9_]*)\s*:\s*(.*?)\s*${'$'}/);
        if (m && !declared.has(m[1])) a.variables[m[1]] = valueOf(m[2]);
      });
    };
    box.append(label(area.id, fields.length ? T.moreVariables : T.variables), area, hint(T.variablesHint));
    return box;
  }
  // When a button shows: always, or only while an entity's state or attribute is, is not, is above or below a value.
  function conditionBox(a, i) {
    const box = document.createElement('div');
    const show = choice('show' + i, [['', T.showAlways], ['when', T.showWhen]], a.when ? 'when' : '');
    const detail = document.createElement('div');
    show.onchange = () => { a.when = show.value ? (a.when || { entity: '', attribute: '', test: 'is', value: '' }) : null; draw(); };
    function draw() {
      detail.textContent = '';
      const w = a.when;
      if (!w) return;
      const e = entityOf(w.entity);
      const pick = picker('whenEntity' + i, entityChoices(), w.entity, (id) => { w.entity = id; w.attribute = ''; draw(); });
      const row = document.createElement('div'); row.className = 'pair';
      const what = choice('whenWhat' + i, attributeChoices(e, true), w.attribute || ''); what.className = 'compare';
      what.onchange = () => { w.attribute = what.value; draw(); };
      const test = choice('whenTest' + i, TESTS, w.test); test.onchange = () => { w.test = test.value; };
      const value = document.createElement('input'); value.type = 'text'; value.id = 'whenValue' + i; value.value = w.value;
      value.placeholder = nowOf(e, w.attribute); value.setAttribute('aria-label', T.showWhen);
      value.oninput = () => { w.value = value.value; };
      row.append(test, value);
      detail.append(label('whenEntity' + i, T.entity), pick, what, row);
      if (e && w.attribute && e.lists && w.attribute in e.lists) detail.append(hint(T.conditionHint));
    }
    draw();
    box.append(label('show' + i, T.show), show, detail);
    return box;
  }
  function renderActions() {
    const box = ${'$'}('actions'); box.textContent = '';
    actionsShown = true;
    if (!actionOptions.length && !actions.length) {
      box.append(hint(T.noActions));
      ${'$'}('addAction').classList.add('hidden');
      return;
    }
    const choices = actionOptions.map((o) => ({ id: o.id, name: o.name, meta: o.id }));
    actions.forEach((a, i) => {
      const row = document.createElement('div'); row.className = 'action';
      const name = document.createElement('input'); name.type = 'text'; name.id = 'actionName' + i; name.maxLength = __MAX_NAME__;
      name.value = a.name || ''; name.placeholder = optionName(a.entity);
      name.oninput = () => { a.name = name.value; };
      const what = picker('actionWhat' + i, choices, a.entity, (id) => { a.entity = id; renderActions(); });
      const remove = document.createElement('button'); remove.type = 'button'; remove.className = 'quiet'; remove.textContent = T.actionRemove;
      remove.onclick = () => { actions.splice(i, 1); renderActions(); };
      row.append(label(name.id, T.actionName), name, label('actionWhat' + i, T.actionWhat), what, variablesBox(a, i), conditionBox(a, i), remove);
      box.append(row);
    });
    ${'$'}('addAction').classList.toggle('hidden', actions.length >= MAX_ACTIONS);
  }
  function addAction() {
    if (actions.length >= MAX_ACTIONS) return;
    actions.push({ name: '', entity: '', variables: {}, when: null });
    renderActions();
    const last = ${'$'}('actionWhat' + (actions.length - 1)); if (last) last.focus();
  }
  function renderValues() {
    const box = ${'$'}('values'); box.textContent = '';
    valuesShown = true;
    if (!entityOptions.length && !values.length) {
      box.append(hint(T.noEntities));
      ${'$'}('addValue').classList.add('hidden');
      return;
    }
    const choices = entityChoices();
    values.forEach((v, i) => {
      const row = document.createElement('div'); row.className = 'action';
      const name = document.createElement('input'); name.type = 'text'; name.id = 'valueName' + i; name.maxLength = __MAX_VALUE_NAME__;
      name.value = v.name || ''; name.oninput = () => { v.name = name.value; };
      const e = entityOf(v.entity);
      const pick = picker('valueEntity' + i, choices, v.entity, (id) => { v.entity = id; v.attribute = ''; renderValues(); });
      const what = choice('valueWhat' + i, attributeChoices(e, false), v.attribute || '');
      const now = hint(e ? T.now.replace('%s', nowOf(e, v.attribute)) : '');
      what.onchange = () => { v.attribute = what.value; now.textContent = e ? T.now.replace('%s', nowOf(e, v.attribute)) : ''; };
      const remove = document.createElement('button'); remove.type = 'button'; remove.className = 'quiet'; remove.textContent = T.actionRemove;
      remove.onclick = () => { values.splice(i, 1); renderValues(); };
      row.append(label(name.id, T.valueName), name, label('valueEntity' + i, T.entity), pick, label(what.id, T.valueWhat), what, now, remove);
      box.append(row);
    });
    ${'$'}('addValue').classList.toggle('hidden', values.length >= MAX_VALUES);
  }
  function addValue() {
    if (values.length >= MAX_VALUES) return;
    values.push({ name: '', entity: '', attribute: '' });
    renderValues();
    const last = ${'$'}('valueEntity' + (values.length - 1)); if (last) last.focus();
  }
  async function load() {
    if (code().length !== 6) return;
    sessionStorage.setItem('klangbild-code', code());
    let s;
    try { s = await api('/api/state'); } catch (e) { status(T.tvUnreachable, 'bad'); return; }
    if (s.ok === false) { status(s.message, 'bad'); return; }
    ${'$'}('url').value = s.url || '';
    hasToken = s.auth === 'token';
    if (hasToken) ${'$'}('token').placeholder = T.tokenKept;
    ${'$'}('entity').value = s.entity || '';
    ${'$'}('sleep').value = s.sleepMinutes || 10;
    document.querySelectorAll('input[name=action]').forEach((r) => r.checked = (r.value === s.sleepAction));
    ${'$'}('clock').checked = !!s.clock; ${'$'}('remote').checked = !!s.remote;
    ${'$'}('maUrl').value = s.maUrl || '';
    lights = { lightsLeft: s.lightsLeft || '', lightsCenter: s.lightsCenter || '', lightsRight: s.lightsRight || '' };
    actions = (s.actions || []).map((a) => ({ name: a.name || '', entity: a.entity || '', variables: a.variables || {}, when: a.when || null }));
    values = (s.values || []).map((v) => ({ name: v.name || '', entity: v.entity || '', attribute: v.attribute || '' }));
    if (s.hasMaToken) ${'$'}('maToken').placeholder = T.tokenKept;
    ${'$'}('tvSpeaker').checked = !!s.tvSpeaker;
    if (s.pairingToken) { ${'$'}('pairCode').value = s.pairingToken; ${'$'}('pairBox').classList.remove('hidden'); }
    if (params.get('signin') === 'failed') show('haStatus', T.signInFailed, 'bad');
    else if (s.auth === 'signin') show('haStatus', T.signedIn, 'good');
    else if (hasToken) show('haStatus', T.withToken, 'good');
    if (s.url && s.auth !== 'none') find();
  }
  async function signIn() {
    ${'$'}('signIn').disabled = true;
    try {
      const r = await api('/api/signin', { url: ${'$'}('url').value });
      if (!r.ok) { show('haStatus', r.message || T.failed, 'bad'); return; }
      show('haStatus', T.signingIn, 'good');
      location.href = r.authorize;
    } catch (e) { show('haStatus', T.tvUnreachable, 'bad'); }
    finally { ${'$'}('signIn').disabled = false; }
  }
  async function find() {
    status(T.connecting);
    ${'$'}('find').disabled = true;
    try {
      const r = await api('/api/discover', { url: ${'$'}('url').value, token: ${'$'}('token').value });
      if (!r.ok) { status(r.message || T.failed, 'bad'); return; }
      ${'$'}('url').value = r.url;
      speakers = r.speakers;
      renderSpeakers(${'$'}('entity').value);
      renderLights(r.lights || []);
      actionOptions = r.actions || [];
      entityOptions = r.entities || [];
      renderActions();
      renderValues();
      status(T.connected, 'good');
    } catch (e) { status(T.tvUnreachable, 'bad'); }
    finally { ${'$'}('find').disabled = false; }
  }
  async function save() {
    ${'$'}('save').disabled = true;
    try {
      const action = document.querySelector('input[name=action]:checked').value;
      const body = {
        url: ${'$'}('url').value, token: ${'$'}('token').value, entity: ${'$'}('entity').value.trim(),
        sleepMinutes: parseInt(${'$'}('sleep').value, 10), sleepAction: action,
        clock: ${'$'}('clock').checked, remote: ${'$'}('remote').checked,
        maUrl: ${'$'}('maUrl').value, maToken: ${'$'}('maToken').value, tvSpeaker: ${'$'}('tvSpeaker').checked };
      // The lights only once the page has shown them; otherwise they stay as they are.
      if (lightsShown) ['lightsLeft', 'lightsCenter', 'lightsRight'].forEach((k) => body[k] = ${'$'}(k).value);
      // The buttons too, in their order; one without an entity is left out, one without a name takes the entity's.
      if (actionsShown) {
        const ready = actions.filter((a) => a.entity);
        const loose = ready.find((a) => a.when && a.when.entity && (a.when.test === 'above' || a.when.test === 'below') && isNaN(parseFloat(String(a.when.value).replace(',', '.'))));
        if (loose) { status(T.conditionNumber, 'bad'); return; }
        body.actions = ready.map((a) => ({
          name: a.name.trim() || optionName(a.entity), entity: a.entity,
          variables: a.entity.startsWith('script.') && Object.keys(a.variables).length ? a.variables : undefined,
          when: a.when && a.when.entity ? { entity: a.when.entity, attribute: a.when.attribute || undefined, test: a.when.test, value: String(a.when.value) } : undefined }));
      }
      // The values in the status line, likewise.
      if (valuesShown) body.values = values.filter((v) => v.entity).map((v) => ({ name: v.name.trim(), entity: v.entity, attribute: v.attribute || undefined }));
      const r = await api('/api/save', body);
      status(r.message || (r.ok ? T.saved : T.failed), r.ok ? 'good' : 'bad');
      if (r.ok) sessionStorage.removeItem('klangbild-code');
    } catch (e) { status(T.tvUnreachable, 'bad'); }
    finally { ${'$'}('save').disabled = false; }
  }
  ${'$'}('signIn').onclick = signIn;
  ${'$'}('find').onclick = find;
  ${'$'}('save').onclick = save;
  ${'$'}('addAction').onclick = addAction;
  ${'$'}('addValue').onclick = addValue;
  ${'$'}('tvSpeaker').onchange = () => {
    if (!${'$'}('tvSpeaker').checked && ${'$'}('entity').value.trim() === THIS_TV) ${'$'}('entity').value = '';
    if (speakers) renderSpeakers(${'$'}('entity').value);
  };
  ${'$'}('maUrl').oninput = () => { if (speakers) renderSpeakers(${'$'}('entity').value); };
  ${'$'}('code').oninput = () => { if (code().length === 6) load(); };
  if (params.has('signin')) history.replaceState(null, '', '/');
  load();
</script>
</body>
</html>
""".trimIndent()
}
