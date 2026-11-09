// 일기 tab for the calendar page. Locked with its own password; entries never leave the phone.
// The page shows the tab only when this file loads (the Android app), so the web calendar is unchanged.
(function () {
  "use strict";
  var A = window.Android;
  var $ = function (id) { return document.getElementById(id); };
  var MOODS = ["😄", "🙂", "😐", "😢", "😠"];
  var WD = ["일", "월", "화", "수", "목", "금", "토"];

  // ---------- storage ----------
  // App: a private file through the bridge. Preview: window.__diaryMemory. Browser: localStorage.
  var KEY = "diary.v1";
  function readRaw() {
    if (window.__diaryMemory) return null;
    try { return A && A.diaryLoad ? A.diaryLoad() : localStorage.getItem(KEY); } catch (e) { return null; }
  }
  function load() {
    if (window.__diaryMemory) return window.__diaryMemory;
    try { var d = JSON.parse(readRaw() || "null"); if (d && Array.isArray(d.entries)) return d; } catch (e) {}
    return { pin: null, entries: [] };
  }
  function persist() {
    if (window.__diaryMemory) return;
    var raw = JSON.stringify(data);
    try { if (A && A.diarySave) A.diarySave(raw); else localStorage.setItem(KEY, raw); } catch (e) { toast("저장하지 못했어요"); }
  }
  var data = load();
  var dates = new Set();
  function indexDates() { dates = new Set(data.entries.map(function (e) { return e.date; })); }
  indexDates();

  // ---------- helpers ----------
  var pad = function (n) { return String(n).padStart(2, "0"); };
  var ymd = function (d) { return d.getFullYear() + "-" + pad(d.getMonth() + 1) + "-" + pad(d.getDate()); };
  var today = function () { return ymd(new Date()); };
  var parse = function (s) { var p = s.split("-").map(Number); return new Date(p[0], p[1] - 1, p[2]); };
  var esc = function (s) { return String(s == null ? "" : s).replace(/[&<>"']/g, function (c) { return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]; }); };
  var uid = function () { return Date.now().toString(36) + Math.random().toString(36).slice(2, 6); };
  var toastTimer;
  function toast(msg) { var t = $("toast"); if (!t) return; t.textContent = msg; t.hidden = false; clearTimeout(toastTimer); toastTimer = setTimeout(function () { t.hidden = true; }, 2200); }
  function secure(on) { try { if (A && A.setSecure) A.setSecure(!!on); } catch (e) {} }
  function places() {
    var count = new Map();
    data.entries.slice().sort(function (a, b) { return b.date.localeCompare(a.date); }).forEach(function (e) {
      if (e.place) count.set(e.place, (count.get(e.place) || 0) + 1);
    });
    return [...count.entries()];
  }

  // ---------- markup ----------
  var css = `
.cell.has-diary::after { content: ""; position: absolute; top: 7px; right: 5px; width: 6px; height: 6px; border-radius: 50%; background: var(--c5); }
.diarylink { margin-top: 8px; width: 100%; display: flex; align-items: center; gap: 10px; padding: 12px; border-radius: 12px; border: 1px dashed var(--c5); background: var(--surface); text-align: left; font-size: 14px; }
.diarylink b { font-weight: 500; }
.diarylink small { color: var(--muted); font-size: 12.5px; display: block; }
#diaryView .dsearch { display: flex; gap: 8px; margin-bottom: 8px; }
#diaryView .dsearch .in { padding: 9px 12px; }
#diaryView .dsearch .iconbtn { flex: none; }
.dplaces { display: flex; gap: 6px; overflow-x: auto; padding-bottom: 6px; margin-bottom: 4px; scrollbar-width: none; }
.dplaces button, .dfilter button { flex: none; border: 1px solid var(--line); background: var(--surface); border-radius: 15px; padding: 4px 11px; font-size: 13px; white-space: nowrap; }
.dplaces button small { color: var(--muted); margin-left: 3px; }
.dfilter { display: flex; gap: 6px; flex-wrap: wrap; margin: 2px 0 8px; }
.dfilter button { background: var(--accent-soft); border-color: var(--accent); color: var(--accent); }
.dstats { font-size: 13px; color: var(--muted); margin: 2px 2px 8px; }
.drand { width: 100%; display: flex; gap: 12px; align-items: center; padding: 12px 14px; border-radius: 12px; border: 1px dashed var(--accent); background: var(--accent-soft); text-align: left; margin-bottom: 4px; }
.drand .dice { font-size: 24px; }
.drand b { display: block; font-weight: 600; }
.drand small { color: var(--muted); font-size: 12.5px; }
.dmonth { margin: 16px 2px 8px; font-size: 13px; font-weight: 600; color: var(--muted); }
.dcard { width: 100%; display: grid; grid-template-columns: 40px minmax(0, 1fr); gap: 12px; text-align: left; background: var(--surface); border: 1px solid var(--line); border-radius: 12px; padding: 12px; margin-bottom: 8px; }
.dcard .dd { text-align: center; }
.dcard .dd b { display: block; font-size: 20px; font-weight: 600; font-variant-numeric: tabular-nums; line-height: 1.15; }
.dcard .dd small { font-size: 12px; color: var(--muted); }
.dcard .dd small.sun { color: var(--sun); }
.dcard .dt { font-weight: 500; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.dcard .dp { font-size: 13px; color: var(--muted); display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; margin-top: 2px; overflow-wrap: anywhere; }
.dcard .dpl { font-size: 12px; color: var(--accent); margin-top: 4px; }
.dlocked { text-align: center; color: var(--muted); padding: 60px 16px; line-height: 1.7; }

.dfull { position: fixed; inset: 0; z-index: 25; background: var(--bg); display: flex; flex-direction: column; overflow-y: auto; padding-top: env(safe-area-inset-top, 0px); }
.dfull > .in-wrap { width: 100%; max-width: 720px; margin: 0 auto; flex: 1; display: flex; flex-direction: column;
  padding-left: max(16px, env(safe-area-inset-left, 0px)); padding-right: max(16px, env(safe-area-inset-right, 0px)); padding-block: 0 calc(24px + env(safe-area-inset-bottom, 0px)); }
.dtop { display: flex; align-items: center; gap: 10px; padding-block: 12px; position: sticky; top: 0; background: var(--bg); z-index: 1; }
.dtop h2 { flex: 1; margin: 0; font-family: var(--font-display); font-size: 22px; }
.dtop .save { height: 38px; padding: 0 18px; border-radius: 19px; border: 0; background: var(--accent); color: var(--accent-ink); font-weight: 600; }
.dedit { display: flex; flex-direction: column; gap: 12px; flex: 1; }
.drow { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
.drow input[type=date] { width: auto; }
.dmoods { display: flex; gap: 4px; }
.dmoods button { width: 42px; height: 42px; border-radius: 50%; border: 0; background: none; font-size: 24px; opacity: .4; }
.dmoods button[aria-pressed="true"] { opacity: 1; background: var(--accent-soft); }
.dplace { display: flex; flex-direction: column; gap: 6px; }
.dplace .in { padding: 9px 12px; }
.dpin { display: flex; align-items: center; gap: 8px; font-size: 18px; }
.dsugg { display: flex; gap: 6px; flex-wrap: wrap; }
.dsugg button { border: 1px solid var(--line); background: var(--surface); border-radius: 14px; padding: 3px 10px; font-size: 13px; }
.dedit .ttl { border: 0; border-bottom: 1px solid var(--line); background: none; font-size: 19px; font-weight: 600; padding: 6px 2px; border-radius: 0; color: var(--ink); }
.dedit .ttl:focus { outline: none; border-bottom-color: var(--accent); }
.dedit textarea { flex: 1; min-height: 45vh; border: 0; background: none; resize: none; line-height: 1.8; font-size: 16px; padding: 2px; color: var(--ink); }
.dedit textarea:focus { outline: none; }
.ddel { align-self: flex-start; border: 0; background: none; color: var(--sun); padding: 6px 0; font-size: 14px; }
.dread { display: flex; flex-direction: column; gap: 6px; padding: 8px 2px 110px; }
.dread .when { color: var(--accent); font-weight: 600; font-size: 14px; }
.dread .date { color: var(--muted); font-size: 14px; }
.dread .pl { color: var(--accent); font-size: 14px; }
.dread .mood { font-size: 32px; }
.dread h3 { margin: 8px 0 2px; font-family: var(--font-display); font-size: 22px; line-height: 1.4; text-wrap: balance; }
.dread .text { white-space: pre-wrap; line-height: 1.85; font-size: 16.5px; overflow-wrap: anywhere; }
.dreadbar { position: fixed; left: 0; right: 0; bottom: 0; display: flex; gap: 10px; justify-content: center; z-index: 26;
  padding: 12px 16px calc(14px + env(safe-area-inset-bottom, 0px)); background: linear-gradient(transparent, var(--bg) 35%); }
.dreadbar button { height: 46px; padding: 0 20px; border-radius: 23px; border: 1px solid var(--line); background: var(--surface); font-weight: 600; }
.dreadbar button.primary { background: var(--accent); border-color: var(--accent); color: var(--accent-ink); }

.dlock { position: fixed; inset: 0; z-index: 40; background: var(--bg); display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 6px;
  padding: calc(24px + env(safe-area-inset-top, 0px)) 16px calc(24px + env(safe-area-inset-bottom, 0px)); }
.dlock .logo { font-size: 40px; }
.dlock h2 { margin: 4px 0 0; font-family: var(--font-display); font-size: 22px; }
.dlock .msg { color: var(--muted); min-height: 22px; text-align: center; font-size: 14px; }
.dlock .msg.err { color: var(--sun); }
.ddots { display: flex; gap: 16px; margin: 18px 0 24px; }
.ddots span { width: 15px; height: 15px; border-radius: 50%; border: 2px solid var(--accent); }
.ddots span.on { background: var(--accent); }
.ddots.shake { animation: dshake .35s; }
@keyframes dshake { 25% { transform: translateX(-8px); } 75% { transform: translateX(8px); } }
@media (prefers-reduced-motion: reduce) { .ddots.shake { animation: none; } }
.dpad { display: grid; grid-template-columns: repeat(3, 72px); gap: 12px 22px; }
.dpad button { height: 72px; border-radius: 50%; background: var(--surface); border: 1px solid var(--line); font-size: 25px; }
.dpad button:active { background: var(--accent-soft); }
.dpad button.txt { background: none; border: 0; font-size: 15px; color: var(--muted); }
.dlen { margin-top: 16px; display: flex; gap: 8px; align-items: center; font-size: 13px; color: var(--muted); }
.dlen button, .dimport { border: 1px solid var(--line); background: var(--surface); border-radius: 15px; padding: 4px 12px; font-size: 13px; }
.dlen button[aria-pressed="true"] { background: var(--accent); border-color: var(--accent); color: var(--accent-ink); }
.dimport { margin-top: 12px; color: var(--accent); border-color: var(--accent); padding: 8px 16px; font-size: 14px; }
.dsheet .item2 { width: 100%; text-align: left; padding: 14px 4px; border: 0; border-bottom: 1px solid var(--line); background: none; font-size: 15px; }
.dsheet p { color: var(--muted); font-size: 13px; margin: 12px 4px 0; line-height: 1.6; }
`;
  var style = document.createElement("style"); style.textContent = css; document.head.appendChild(style);

  var view = $("diaryView");
  view.innerHTML = `
    <div id="d-body"></div>`;

  var shell = document.createElement("div");
  shell.innerHTML = `
  <div class="dlock" id="d-lock" hidden>
    <div class="logo" aria-hidden="true">📔</div>
    <h2 id="d-lt">일기</h2>
    <div class="msg" id="d-lm"></div>
    <div class="ddots" id="d-dots"></div>
    <div class="dpad" id="d-pad"></div>
    <div class="dlen" id="d-len" hidden>비밀번호 길이 <button data-len="4" aria-pressed="true">4자리</button><button data-len="6" aria-pressed="false">6자리</button></div>
    <button class="dimport" id="d-import-first" hidden>일기 앱에 쓴 일기 옮겨 오기</button>
  </div>
  <div class="dfull" id="d-editor" hidden><div class="in-wrap">
    <div class="dtop"><button class="iconbtn" id="d-eback" aria-label="뒤로"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M15 5l-7 7 7 7"/></svg></button><h2 id="d-ehead">일기 쓰기</h2><button class="save" id="d-esave">저장</button></div>
    <div class="dedit">
      <div class="drow"><input class="in" type="date" id="d-date" aria-label="날짜"><div class="dmoods" id="d-moods" role="group" aria-label="기분"></div></div>
      <div class="dplace"><label class="dpin"><span aria-hidden="true">📍</span><input class="in" id="d-place" maxlength="40" placeholder="장소 (예: 집, 회사 앞 카페)" autocomplete="off" aria-label="장소"></label><div class="dsugg" id="d-sugg"></div></div>
      <input class="ttl" id="d-title" maxlength="80" placeholder="제목 (없어도 돼요)" autocomplete="off">
      <textarea id="d-text" placeholder="오늘 하루는 어땠나요?"></textarea>
      <button class="ddel" id="d-del" hidden>이 일기 지우기</button>
    </div>
  </div></div>
  <div class="dfull" id="d-reader" hidden><div class="in-wrap">
    <div class="dtop"><button class="iconbtn" id="d-rback" aria-label="뒤로"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M15 5l-7 7 7 7"/></svg></button><h2>그날의 일기</h2></div>
    <article class="dread" id="d-rbody"></article>
  </div>
    <div class="dreadbar"><button id="d-redit">고치기</button><button class="primary" id="d-ragain">🎲 다른 일기</button></div>
  </div>
  <div class="scrim" id="d-set" hidden><div class="sheet dsheet" role="dialog" aria-modal="true" aria-labelledby="d-seth">
    <div class="grab"></div><h3 id="d-seth">일기 설정</h3>
    <button class="item2" id="d-chpin">🔑 비밀번호 바꾸기</button>
    <button class="item2" id="d-import" hidden>📥 일기 앱에 쓴 일기 옮겨 오기</button>
    <button class="item2" id="d-setclose">닫기</button>
    <p>앱을 나가거나 화면이 꺼지면 일기가 자동으로 잠겨요. 비밀번호를 잊으면 일기를 다시 열 수 없으니 꼭 기억해 두세요.</p>
  </div></div>`;
  while (shell.firstElementChild) document.body.appendChild(shell.firstElementChild);

  // ---------- password ----------
  var hex = function (buf) { return [...new Uint8Array(buf)].map(function (b) { return b.toString(16).padStart(2, "0"); }).join(""); };
  async function hashPin(pin, salt) {
    var enc = new TextEncoder();
    var key = await crypto.subtle.importKey("raw", enc.encode(salt + ":" + pin), "PBKDF2", false, ["deriveBits"]);
    var bits = await crypto.subtle.deriveBits({ name: "PBKDF2", hash: "SHA-256", salt: enc.encode(salt), iterations: 100000 }, key, 256);
    return hex(bits);
  }
  var newSalt = function () { return hex(crypto.getRandomValues(new Uint8Array(16))); };

  var unlocked = false, mode = "unlock", typed = "", firstPin = "", pinLen = 4, busy = false, fails = 0, waitUntil = 0, after = null;
  var TEXT = {
    unlock: ["일기", "비밀번호를 입력하세요"],
    setup: ["일기 비밀번호 만들기", "일기를 지킬 비밀번호를 정해 주세요"],
    confirm: ["한 번 더 입력", "확인을 위해 같은 비밀번호를 입력하세요"],
    verifyOld: ["비밀번호 바꾸기", "지금 비밀번호를 입력하세요"],
    changeNew: ["새 비밀번호", "새 비밀번호를 입력하세요"],
    changeConfirm: ["한 번 더 입력", "새 비밀번호를 한 번 더 입력하세요"]
  };
  function setMsg(t, err) { $("d-lm").textContent = t; $("d-lm").classList.toggle("err", !!err); }
  function drawDots() { $("d-dots").innerHTML = Array.from({ length: pinLen }, function (_, i) { return '<span class="' + (i < typed.length ? "on" : "") + '"></span>'; }).join(""); }
  function canImport() { return !!(A && A.diaryImport); }
  function showLock(m) {
    mode = m; typed = "";
    if ((m === "unlock" || m === "verifyOld") && data.pin) pinLen = data.pin.len;
    $("d-lt").textContent = TEXT[m][0]; setMsg(TEXT[m][1]);
    $("d-len").hidden = !(m === "setup" || m === "changeNew");
    [...$("d-len").querySelectorAll("button")].forEach(function (b) { b.setAttribute("aria-pressed", String(+b.dataset.len === pinLen)); });
    $("d-import-first").hidden = !(m === "setup" && canImport());
    drawDots();
    $("d-lock").hidden = false;
  }
  function hideLock() { $("d-lock").hidden = true; }
  function shake(msg) {
    typed = ""; drawDots(); setMsg(msg, true);
    var d = $("d-dots"); d.classList.remove("shake"); void d.offsetWidth; d.classList.add("shake");
  }
  function opened() {
    unlocked = true; fails = 0; hideLock(); secure(true); renderList();
    var f = after; after = null; if (f) f();
  }
  async function complete() {
    var pin = typed; busy = true;
    try {
      if (mode === "unlock" || mode === "verifyOld") {
        if ((await hashPin(pin, data.pin.salt)) === data.pin.hash) {
          if (mode === "unlock") opened(); else showLock("changeNew");
        } else {
          fails++;
          if (fails >= 5) { waitUntil = Date.now() + 30000; fails = 0; shake("5번 틀렸어요. 30초 뒤에 다시 해 주세요"); }
          else shake("비밀번호가 달라요 (" + fails + "/5)");
        }
      } else if (mode === "setup" || mode === "changeNew") {
        firstPin = pin; showLock(mode === "setup" ? "confirm" : "changeConfirm");
      } else {
        if (pin !== firstPin) { var back = mode === "confirm" ? "setup" : "changeNew"; firstPin = ""; showLock(back); shake("두 번 입력한 비밀번호가 달라요. 처음부터 다시 정해 주세요"); return; }
        var salt = newSalt(), wasSetup = mode === "confirm";
        data.pin = { salt: salt, hash: await hashPin(pin, salt), len: pin.length };
        persist(); firstPin = "";
        opened(); toast(wasSetup ? "일기 비밀번호를 만들었어요" : "비밀번호를 바꿨어요");
      }
    } finally { busy = false; }
  }
  function key(k) {
    if (busy) return;
    if (Date.now() < waitUntil) { setMsg(Math.ceil((waitUntil - Date.now()) / 1000) + "초 뒤에 다시 해 주세요", true); return; }
    if (k === "del") { typed = typed.slice(0, -1); drawDots(); return; }
    if (k === "cancel") { cancelLock(); return; }
    if (typed.length >= pinLen) return;
    typed += k; drawDots();
    if (typed.length === pinLen) setTimeout(complete, 120);
  }
  function cancelLock() {
    firstPin = ""; after = null; hideLock();
    if (!unlocked && window.__setCalView) window.__setCalView("month");
  }
  $("d-pad").innerHTML = ["1", "2", "3", "4", "5", "6", "7", "8", "9", "cancel", "0", "del"].map(function (k) {
    if (k === "cancel") return '<button class="txt" data-k="cancel">달력으로</button>';
    if (k === "del") return '<button class="txt" data-k="del" aria-label="지우기">⌫</button>';
    return '<button data-k="' + k + '">' + k + "</button>";
  }).join("");
  $("d-pad").addEventListener("click", function (e) { var b = e.target.closest("button"); if (b) key(b.dataset.k); });
  $("d-len").addEventListener("click", function (e) {
    var b = e.target.closest("button[data-len]"); if (!b) return;
    pinLen = +b.dataset.len; typed = ""; firstPin = "";
    [...$("d-len").querySelectorAll("button")].forEach(function (x) { x.setAttribute("aria-pressed", String(x === b)); });
    drawDots();
  });
  document.addEventListener("keydown", function (e) {
    if ($("d-lock").hidden) return;
    if (/^[0-9]$/.test(e.key)) key(e.key); else if (e.key === "Backspace") key("del"); else if (e.key === "Escape") cancelLock();
  });

  function lock() {
    if (!$("d-editor").hidden && dirty()) saveEntry(true);
    closeAll();
    unlocked = false; firstPin = ""; after = null; secure(false);
    hideLock();
    if (!view.hidden) renderList();
  }
  function requireUnlock(then) {
    if (unlocked) { then && then(); return; }
    after = then || null;
    showLock(data.pin ? "unlock" : "setup");
  }

  // ---------- import from the separate 내 일기 app ----------
  function importFromApp() {
    var raw = "";
    try { raw = A.diaryImport() || ""; } catch (e) {}
    var other = null;
    try { other = JSON.parse(raw); } catch (e) {}
    if (!other || !Array.isArray(other.entries)) { toast("일기 앱을 찾지 못했어요. 일기 앱을 최신 버전으로 설치했는지 확인해 주세요"); return false; }
    var have = new Set(data.entries.map(function (e) { return e.id; })), added = 0;
    other.entries.forEach(function (e) { if (e && e.id && !have.has(e.id)) { data.entries.push(e); added++; } });
    var tookPin = false;
    if (!data.pin && other.pin) { data.pin = other.pin; tookPin = true; }
    persist(); indexDates(); if (window.__calRender) window.__calRender();
    toast(added ? "일기 " + added + "편을 옮겨 왔어요" : "새로 옮길 일기가 없어요");
    return tookPin;
  }
  $("d-import-first").addEventListener("click", function () {
    if (importFromApp()) { showLock("unlock"); setMsg("일기 앱에서 쓰던 비밀번호를 입력하세요"); }
  });
  $("d-import").addEventListener("click", function () { closeSettings(); importFromApp(); renderList(); });

  // ---------- list ----------
  var q = "", placeFilter = "", dateFilter = "";
  function sorted() { return data.entries.slice().sort(function (a, b) { return b.date.localeCompare(a.date) || (b.updated || 0) - (a.updated || 0); }); }
  function renderList() {
    var body = $("d-body");
    $("t-main").textContent = "일기";
    if (!unlocked) {
      $("t-sub").textContent = "잠김";
      body.innerHTML = '<div class="dlocked">🔒 일기가 잠겨 있어요.<br><button class="pill" id="d-open" style="margin-top:12px">비밀번호 입력</button></div>';
      $("d-open").addEventListener("click", function () { requireUnlock(); });
      return;
    }
    $("t-sub").textContent = data.entries.length ? data.entries.length + "편" : "";
    var ql = q.trim().toLowerCase();
    var items = sorted().filter(function (e) {
      if (placeFilter && e.place !== placeFilter) return false;
      if (dateFilter && e.date !== dateFilter) return false;
      return !ql || [e.title, e.body, e.place].join(" ").toLowerCase().includes(ql);
    });
    var month = today().slice(0, 7);
    var thisMonth = data.entries.filter(function (e) { return e.date.startsWith(month); }).length;
    var pl = places();
    var h = '<div class="dsearch"><input class="in" id="d-q" type="search" placeholder="일기 검색 (내용, 장소)" value="' + esc(q) + '">' +
      '<button class="iconbtn" id="d-lockbtn" aria-label="일기 잠그기">🔒</button><button class="iconbtn" id="d-setbtn" aria-label="일기 설정">⚙️</button></div>';
    if (pl.length && !placeFilter) h += '<div class="dplaces" aria-label="장소별 보기">' + pl.map(function (p) { return '<button data-place="' + esc(p[0]) + '">📍 ' + esc(p[0]) + "<small>" + p[1] + "</small></button>"; }).join("") + "</div>";
    if (placeFilter || dateFilter) {
      h += '<div class="dfilter">' + (placeFilter ? '<button data-clear="place">📍 ' + esc(placeFilter) + " ✕</button>" : "") +
        (dateFilter ? '<button data-clear="date">' + (+dateFilter.slice(5, 7)) + "월 " + (+dateFilter.slice(8)) + "일 ✕</button>" : "") + "</div>";
    }
    if (data.entries.length && !placeFilter && !dateFilter && !ql) {
      h += '<div class="dstats">모두 ' + data.entries.length + "편 · 이번 달 " + thisMonth + "편</div>";
      h += '<button class="drand" id="d-rand"><span class="dice" aria-hidden="true">🎲</span><span><b>그날의 일기 꺼내 읽기</b><small>지금까지 쓴 일기 중 하나를 랜덤으로 골라 줘요</small></span></button>';
    }
    if (!items.length) {
      h += '<div class="empty" style="margin-top:12px">' + (data.entries.length ? "찾는 일기가 없어요." : "아직 쓴 일기가 없어요.\n+ 버튼을 눌러 오늘 하루를 적어 보세요.") + "</div>";
    } else {
      var cur = "";
      items.forEach(function (e) {
        var m = e.date.slice(0, 7);
        if (m !== cur) { cur = m; h += '<div class="dmonth">' + m.slice(0, 4) + "년 " + (+m.slice(5)) + "월</div>"; }
        var d = parse(e.date), wd = d.getDay();
        h += '<button class="dcard" data-id="' + esc(e.id) + '"><span class="dd"><b>' + d.getDate() + '</b><small class="' + (wd === 0 ? "sun" : "") + '">' + WD[wd] + "</small></span>" +
          '<span style="min-width:0"><div class="dt">' + (e.mood != null ? MOODS[e.mood] + " " : "") + esc(e.title || "제목 없음") + "</div>" +
          '<div class="dp">' + (esc((e.body || "").slice(0, 200)) || "&nbsp;") + "</div>" + (e.place ? '<div class="dpl">📍 ' + esc(e.place) + "</div>" : "") + "</span></button>";
      });
    }
    body.innerHTML = h;
    var qi = $("d-q");
    qi.addEventListener("input", function () { q = qi.value; var pos = qi.selectionStart; renderList(); var n = $("d-q"); n.focus(); try { n.setSelectionRange(pos, pos); } catch (e) {} });
    $("d-lockbtn").addEventListener("click", function () { lock(); toast("일기를 잠갔어요"); });
    $("d-setbtn").addEventListener("click", openSettings);
    if ($("d-rand")) $("d-rand").addEventListener("click", function () { reading = null; drawRandom(); });
    body.querySelectorAll("[data-place]").forEach(function (b) { b.addEventListener("click", function () { placeFilter = b.dataset.place; renderList(); }); });
    body.querySelectorAll("[data-clear]").forEach(function (b) { b.addEventListener("click", function () { if (b.dataset.clear === "place") placeFilter = ""; else dateFilter = ""; renderList(); }); });
    body.querySelectorAll(".dcard").forEach(function (b) { b.addEventListener("click", function () { openEditor(data.entries.find(function (x) { return x.id === b.dataset.id; })); }); });
  }

  // ---------- editor ----------
  var editing = null, mood = null, snapshot = "";
  $("d-moods").innerHTML = MOODS.map(function (m, i) { return '<button type="button" data-m="' + i + '" aria-pressed="false" aria-label="기분 ' + (i + 1) + '">' + m + "</button>"; }).join("");
  $("d-moods").addEventListener("click", function (e) { var b = e.target.closest("button"); if (!b) return; var i = +b.dataset.m; mood = mood === i ? null : i; drawMoods(); });
  function drawMoods() { [...$("d-moods").children].forEach(function (b, i) { b.setAttribute("aria-pressed", String(i === mood)); }); }
  function drawSugg() {
    var cur = $("d-place").value.trim();
    var list = places().map(function (p) { return p[0]; }).filter(function (p) { return p !== cur && (!cur || p.includes(cur)); }).slice(0, 8);
    $("d-sugg").innerHTML = list.map(function (p) { return '<button type="button" data-p="' + esc(p) + '">📍 ' + esc(p) + "</button>"; }).join("");
  }
  $("d-place").addEventListener("input", drawSugg);
  $("d-sugg").addEventListener("click", function (e) { var b = e.target.closest("button"); if (!b) return; $("d-place").value = b.dataset.p; drawSugg(); });
  function current() { return JSON.stringify([$("d-date").value, mood, $("d-place").value.trim(), $("d-title").value, $("d-text").value]); }
  function dirty() { return current() !== snapshot; }
  function openEditor(entry, date) {
    editing = entry || null;
    $("d-ehead").textContent = entry ? "일기 고치기" : "일기 쓰기";
    $("d-date").value = entry ? entry.date : (date || today());
    $("d-date").max = today();
    mood = entry ? (entry.mood == null ? null : entry.mood) : null; drawMoods();
    $("d-place").value = entry ? entry.place || "" : "";
    $("d-title").value = entry ? entry.title || "" : "";
    $("d-text").value = entry ? entry.body || "" : "";
    $("d-del").hidden = !entry; $("d-del").textContent = "이 일기 지우기"; delete $("d-del").dataset.sure;
    drawSugg();
    snapshot = current();
    $("d-reader").hidden = true; $("d-editor").hidden = false; $("d-editor").scrollTop = 0;
    if (!entry) setTimeout(function () { $("d-text").focus(); }, 60);
  }
  function saveEntry(silent) {
    var title = $("d-title").value.trim(), body = $("d-text").value.replace(/\s+$/, "");
    if (!title && !body.trim()) { if (!silent) toast("내용을 적어 주세요"); return false; }
    var fields = { date: $("d-date").value || today(), mood: mood, place: $("d-place").value.trim(), title: title, body: body, updated: Date.now() };
    if (editing) Object.assign(editing, fields);
    else { editing = Object.assign({ id: uid() }, fields); data.entries.push(editing); }
    persist(); indexDates(); snapshot = current();
    if (window.__calRender) window.__calRender();
    return true;
  }
  function closeEditor() {
    if (dirty() && saveEntry(true)) toast("저장했어요");
    $("d-editor").hidden = true; editing = null; renderList();
  }
  $("d-esave").addEventListener("click", function () { if (saveEntry(false)) { $("d-editor").hidden = true; editing = null; renderList(); toast("저장했어요"); } });
  $("d-eback").addEventListener("click", closeEditor);
  $("d-del").addEventListener("click", function () {
    var b = $("d-del");
    if (!editing) return;
    if (!b.dataset.sure) { b.dataset.sure = "1"; b.textContent = "한 번 더 누르면 지워져요"; setTimeout(function () { delete b.dataset.sure; b.textContent = "이 일기 지우기"; }, 3000); return; }
    var gone = editing;
    data.entries = data.entries.filter(function (x) { return x !== gone; });
    persist(); indexDates(); editing = null; $("d-editor").hidden = true; renderList(); toast("지웠어요");
    if (window.__calRender) window.__calRender();
  });

  // ---------- random reader ----------
  var reading = null;
  function ago(date) {
    var days = Math.round((parse(today()) - parse(date)) / 86400000);
    if (days <= 0) return "오늘";
    if (days === 1) return "어제";
    if (days < 31) return days + "일 전";
    if (days < 365) return Math.floor(days / 30) + "달 전";
    var y = Math.floor(days / 365), m = Math.floor((days % 365) / 30);
    return m ? y + "년 " + m + "달 전" : y + "년 전";
  }
  function drawRandom() {
    var pool = data.entries.length > 1 ? data.entries.filter(function (e) { return e !== reading; }) : data.entries;
    if (!pool.length) return;
    reading = pool[Math.floor(Math.random() * pool.length)];
    var e = reading, d = parse(e.date);
    $("d-rbody").innerHTML = '<div class="when">' + ago(e.date) + '의 나</div><div class="date">' + d.getFullYear() + "년 " + (d.getMonth() + 1) + "월 " + d.getDate() + "일 " + WD[d.getDay()] + "요일</div>" +
      (e.place ? '<div class="pl">📍 ' + esc(e.place) + "</div>" : "") + (e.mood != null ? '<div class="mood">' + MOODS[e.mood] + "</div>" : "") +
      "<h3>" + esc(e.title || "제목 없음") + '</h3><div class="text">' + esc(e.body) + "</div>";
    $("d-ragain").hidden = data.entries.length < 2;
    $("d-reader").hidden = false; $("d-reader").scrollTop = 0;
  }
  $("d-ragain").addEventListener("click", drawRandom);
  $("d-redit").addEventListener("click", function () { if (reading) openEditor(reading); });
  $("d-rback").addEventListener("click", function () { $("d-reader").hidden = true; });

  // ---------- settings ----------
  function openSettings() { $("d-import").hidden = !canImport(); $("d-set").hidden = false; }
  function closeSettings() { $("d-set").hidden = true; }
  $("d-setclose").addEventListener("click", closeSettings);
  $("d-set").addEventListener("click", function (e) { if (e.target.id === "d-set") closeSettings(); });
  $("d-chpin").addEventListener("click", function () { closeSettings(); showLock("verifyOld"); });

  function closeAll() { $("d-editor").hidden = true; $("d-reader").hidden = true; closeSettings(); editing = null; }

  // ---------- what the calendar calls ----------
  window.Diary = {
    has: function (ds) { return dates.has(ds); },
    render: function () { renderList(); if (!unlocked && $("d-lock").hidden && !after) requireUnlock(); },
    add: function () { requireUnlock(function () { openEditor(null); }); },
    lock: lock,
    // From the calendar: open that day's diary, or start one for that day.
    openDate: function (ds) {
      requireUnlock(function () {
        var list = data.entries.filter(function (e) { return e.date === ds; });
        if (list.length === 1) openEditor(list[0]);
        else if (list.length > 1) { dateFilter = ds; placeFilter = ""; q = ""; renderList(); }
        else openEditor(null, ds);
      });
    },
    // Android back button. true when the diary handled it.
    back: function () {
      if (!$("d-lock").hidden) { cancelLock(); return true; }
      if (!$("d-set").hidden) { closeSettings(); return true; }
      if (!$("d-editor").hidden) { closeEditor(); return true; }
      if (!$("d-reader").hidden) { $("d-reader").hidden = true; return true; }
      return false;
    }
  };
  var tab = $("v-diary"); if (tab) tab.hidden = false;
  if (window.__calRender) window.__calRender();
})();
