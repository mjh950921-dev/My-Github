// Runs before the calendar page. Stands in for window.claude inside the Android app:
// - events and goals are kept in the app (mirrored to Android so the widget can read them)
// - "Google Calendar" calls read and write the phone's own calendars (Google + Samsung)
(function () {
  var A = window.Android;
  if (!A) return;
  var KEYS = { "cal.events": 1, "cal.plans": 1 };
  var setItem = Storage.prototype.setItem;
  try {
    for (var k in KEYS) { var v = A.load(k); if (v != null) setItem.call(localStorage, k, v); }
    setItem.call(localStorage, "cal.source", "local");
  } catch (e) {}
  Storage.prototype.setItem = function (k, v) {
    setItem.call(this, k, v);
    if (this === window.localStorage && KEYS[k]) { try { A.save(k, String(v)); } catch (e) {} }
  };

  function fail(code) { var e = new Error(code); e.code = code; return e; }
  function parse(s) { var r = JSON.parse(s); if (r && r.error) throw fail(r.error); return r; }
  var mcp = {
    invalidate: function () { return Promise.resolve(); },
    callTool: function (server, tool, args) {
      return new Promise(function (resolve, reject) {
        setTimeout(function () {
          try {
            if (!A.calPermission()) { A.requestCal(); throw fail("approval_required"); }
            if (tool === "list_events") resolve({ payload: parse(A.listEvents(args.startTime, args.endTime)) });
            else if (tool === "create_event") resolve({ payload: parse(A.createEvent(JSON.stringify(args))) });
            else if (tool === "update_event") resolve({ payload: parse(A.updateEvent(JSON.stringify(args))) });
            else if (tool === "delete_event") resolve({ payload: parse(A.deleteEvent(String(args.eventId))) });
            else throw fail("not_in_manifest");
          } catch (e) { reject(e); }
        }, 0);
      });
    }
  };
  // Inside the app, "Google Calendar" means every calendar on the phone (Google and Samsung), and its events are editable.
  window.__nativeCal = true;
  document.addEventListener("DOMContentLoaded", function () {
    var row = document.querySelector("#gRow span");
    if (row) row.innerHTML = '폰 캘린더에 저장<br><small style="color:var(--muted);font-size:12px">구글·삼성 캘린더에 함께 보이고 알림이 울려요</small>';
  });
  var permissions = { state: function () { return Promise.resolve(A.calPermission() ? "granted" : "prompt"); } };
  window.claude = {
    use: function (name) { return Promise.resolve(name === "mcp" ? mcp : name === "permissions" ? permissions : null); }
  };
})();
