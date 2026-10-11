/* MOSIP Face Liveness test console — logic (external so the CSP can be
   strict: script-src 'self', no inline scripts or 'unsafe-inline'). */
(() => {
  "use strict";

  const API = "/api/v1";
  const $ = id => document.getElementById(id);
  const video = $("video");

  let stream = null, running = false, sessionId = null, lastChallenge = null;
  // The manual extra check is offered only once the session has passed on the
  // passive stage (above threshold, so the engine issued no challenge).
  let sessionPassed = false;

  // ---------- helpers ----------
  const logEl = $("log");
  // Cap the decision log so long sessions don't grow DOM text without bound
  // (keeps memory flat on low-end devices).
  const LOG_MAX_CHARS = 100000;
  function log(msg) {
    const t = new Date().toLocaleTimeString();
    logEl.textContent += `\n[${t}] ${msg}`;
    if (logEl.textContent.length > LOG_MAX_CHARS) {
      logEl.textContent = logEl.textContent.slice(-LOG_MAX_CHARS / 2);
    }
    logEl.scrollTop = logEl.scrollHeight;
  }
  function setStep(n, cls) {
    const el = $("s" + n);
    el.classList.remove("active", "done");
    if (cls) el.classList.add(cls);
  }
  function resetSteps() { [1,2,3,4].forEach(n => setStep(n, null)); }
  function kv(dl, obj) {
    dl.innerHTML = "";
    for (const [k, v] of Object.entries(obj)) {
      const dt = document.createElement("dt"); dt.textContent = k;
      const dd = document.createElement("dd");
      dd.textContent = renderMetric(v);
      dl.append(dt, dd);
    }
  }

  // A nested object (pipelineTimings) must not be stringified — String({})
  // is "[object Object]". Render it as one indented line per phase instead, so
  // the per-frame cost breakdown is actually readable in the panel.
  function renderMetric(v) {
    if (v === null || v === undefined) return "—";
    if (typeof v !== "object") return String(v);
    const entries = Object.entries(v);
    if (!entries.length) return "(none yet)";
    return entries.map(([phase, t]) => {
      const pct = (t.sharePct > 0) ? ` (${t.sharePct}%)` : "";
      return `${phase}: ${t.meanMs} ms mean · ${t.maxMs} ms max · n=${t.count}${pct}`;
    }).join("\n");
  }
  function verdict(kind, action, message) {
    const box = $("verdict");
    box.hidden = false;
    box.className = "verdict " + (kind || "");
    $("verdict-action").textContent = action || "";
    $("verdict-msg").textContent = message || "";
  }

  // "Run active liveness" is enabled only while the current session has passed.
  function setActiveCheckEnabled(on) {
    $("btn-active").disabled = !on;
  }

  // The three ways a spent retry budget can end a session (docs/configuration.md):
  // which headline to show, and — only for `failed` — that a fresh session is
  // allowed. Keyed by the server's own action, never guessed from the message.
  const TERMINAL_VERDICTS = {
    locked: ["reject", "LOCKED OUT"],
    escalate_to_operator: ["escalate", "ESCALATE TO OPERATOR"],
    failed: ["reject", "FAILED"]
  };

  async function api(path, options) {
    const opts = options || {};
    const res = await fetch(API + path, {
      method: opts.method,
      body: opts.body,
      // Merge, don't replace: callers add headers (e.g. the admin key) on top
      // of the JSON content type.
      headers: Object.assign({ "Content-Type": "application/json" }, opts.headers)
    });
    const text = await res.text();
    let body = null;
    try { body = text ? JSON.parse(text) : null; } catch { body = text; }
    // Note the limiter's budget before throwing, so a 429 still updates it.
    noteRateLimit(res.headers, res.status);
    if (!res.ok) {
      const detail = body && body.error ? `${body.error}: ${body.message}` : `HTTP ${res.status}`;
      throw new Error(detail);
    }
    return body;
  }

  // One canvas reused for every capture: allocating a fresh canvas per frame
  // made garbage collection spike on low-end devices during long runs.
  const captureCanvas = document.createElement("canvas");
  const captureCtx = captureCanvas.getContext("2d");
  function grabFrame(quality = 0.85) {
    const w = video.videoWidth || 640;
    const h = video.videoHeight || 480;
    if (captureCanvas.width !== w) captureCanvas.width = w;
    if (captureCanvas.height !== h) captureCanvas.height = h;
    captureCtx.drawImage(video, 0, 0, w, h);
    return captureCanvas.toDataURL("image/jpeg", quality);
  }

  const sleep = ms => new Promise(r => setTimeout(r, ms));

  // ---------- health ----------
  async function refreshHealth() {
    try {
      const res = await fetch("/health");
      const h = await res.json();
      $("pill-health").textContent = "service: " + h.status;
      $("pill-health").className = "pill ok";
      const available = h.engine === "available";
      $("pill-engine").textContent = "engine: " + h.engine;
      $("pill-engine").className = "pill " + (available ? "ok" : "warn");
      const warn = $("engine-warn");
      if (available) {
        warn.hidden = true;
      } else {
        warn.hidden = false;
        warn.textContent = "The OpenCV native library is unavailable on this platform, so "
          + "frame processing is disabled and every frame request will return 503.";
      }
    } catch {
      $("pill-health").textContent = "service: unreachable";
      $("pill-health").className = "pill bad";
    }
  }

  // ---------- camera ----------
  $("btn-camera").addEventListener("click", async () => {
    try {
      stream = await navigator.mediaDevices.getUserMedia({
        video: { width: { ideal: 640 }, height: { ideal: 480 }, facingMode: "user" }, audio: false
      });
      video.srcObject = stream;
      $("btn-camera").disabled = true;
      $("btn-run").disabled = false;
      log("Camera started.");
    } catch (e) {
      log("Camera error: " + e.message
        + "\n  Browsers expose the camera only on a secure origin — use http://localhost:8000");
    }
  });

  // ---------- the liveness run ----------
  $("btn-run").addEventListener("click", async () => {
    if (running) return;
    running = true;
    $("btn-run").disabled = true;
    $("btn-stop").disabled = false;
    resetSteps();
    sessionPassed = false;
    setActiveCheckEnabled(false);
    $("audit").textContent = "No entries yet.";
    $("last").innerHTML = "<dt>—</dt><dd>—</dd>";

    try {
      // 1 — session
      setStep(1, "active");
      const session = await api("/sessions", {
        method: "POST",
        body: JSON.stringify({
          workflowType: $("workflow").value,
          deviceId: $("deviceId").value || "WEB-CAM"
        })
      });
      sessionId = session.id;
      lastChallenge = null;
      $("pill-session").textContent = "session " + sessionId.slice(0, 8);
      $("pill-session").className = "pill ok";
      log(`Session created: ${sessionId}\n  workflow=${session.workflowType} status=${session.status}`);
      setStep(1, "done");

      // 2 — passive
      setStep(2, "active");
      let escalated = null, outcome = null;
      for (let i = 1; i <= 12 && running; i++) {
        const frame = grabFrame();
        const r = await api(`/sessions/${sessionId}/frames`, {
          method: "POST",
          body: JSON.stringify({ frameBase64: frame })
        });
        kv($("last"), {
          sessionId: r.sessionId, stage: r.stage, faceDetected: r.faceDetected,
          multipleFaces: r.multipleFaces, faceQuality: fmt(r.faceQuality),
          livenessScore: fmt(r.livenessScore), padFlag: r.padFlag,
          padAttackType: r.padAttackType, action: r.action, message: r.message
        });
        log(`frame ${i}: action=${r.action} face=${r.faceDetected}`
          + ` quality=${fmt(r.faceQuality)} score=${fmt(r.livenessScore)}`
          + (r.padAttackType ? ` pad=${r.padAttackType}` : ""));

        if (r.action === "escalate_to_active") { escalated = r; break; }
        // Anything that is not "keep going" is a verdict. The terminal vocabulary
        // includes the budget-exhausted outcomes (locked / escalate_to_operator /
        // failed), so looping on them would re-submit a closed session forever.
        if (r.action !== "retry_passive" && r.action !== "continue") { outcome = r; break; }
        await sleep(350);
      }

      if (!outcome && escalated) {
        setStep(2, "done");
        setStep(3, "active");
        outcome = await runChallenges(escalated);
      } else {
        setStep(2, "done");
      }

      if (running) {
        setStep(3, "done");
        setStep(4, "active");
        if (outcome && outcome.action === "proceed") {
          verdict("proceed", "PROCEED", outcome.message || "Liveness verified.");
          // Passed on the passive stage: the extra active check is now offered.
          sessionPassed = true;
        } else if (outcome && TERMINAL_VERDICTS[outcome.action]) {
          const [kind, headline] = TERMINAL_VERDICTS[outcome.action];
          verdict(kind, headline, (outcome.message || "Check could not be completed.")
            + (outcome.mayRetrySession ? " You may start a new session." : ""));
        } else if (outcome) {
          verdict("reject", "REJECT", outcome.message || "Liveness could not be verified.");
        } else {
          verdict("", "INCOMPLETE", "No terminal verdict was reached.");
        }
        setStep(4, "done");
      }
      await refreshAudit();
      await refreshMetrics();
    } catch (e) {
      log("ERROR: " + e.message);
      verdict("reject", "ERROR", e.message);
    } finally {
      running = false;
      $("btn-run").disabled = false;
      $("btn-stop").disabled = true;
      setActiveCheckEnabled(sessionPassed);
    }
  });

  // Active challenge-response loop.
  //
  // A challenge stays open for a whole window (the server enforces a 15s floor).
  // We keep grabbing frames and re-checking the *same* challenge id every few
  // hundred milliseconds, so capture stops the instant the action is detected.
  // While the window is open and nothing has been seen the server answers
  // "continue"; only after the window elapses does a miss count as a failure.
  async function runChallenges(escalation) {
    let challenge = escalation.challenge;
    let rounds = 0;
    // Each poll re-runs face detection and PAD on every frame it carries, which is
    // the expensive path, so keep the window small and the cadence moderate. Four
    // recent frames (2s of motion) alongside the baseline is plenty: the engine
    // measures the *largest* excursion relative to the baseline, so the peak of a
    // turn only has to fall inside one window, not all of them.
    const WINDOW = 4;        // frames in the rolling evaluation window
    const CAPTURE_MS = 500;  // gap between captured frames
    const MIN_WINDOW_MS = 15000;

    while (challenge && rounds < 6 && running) {
      rounds++;
      lastChallenge = challenge;

      const readable = challenge.challengeType.replace(/_/g, " ").toLowerCase();
      // The two challenge shapes disagree on the id field:
      //   escalation response (FrameProcessResult.ChallengeInfo) -> `challengeId`
      //   retry response      (ChallengeResponse)                -> `id`
      // Accept either, and fail loudly rather than posting a null id.
      const challengeId = challenge.challengeId || challenge.id;
      if (!challengeId) throw new Error("Server returned a challenge without an id");

      const windowMs = Math.max(challenge.timeoutMs || 0, MIN_WINDOW_MS);
      const deadline = Date.now() + windowMs;

      // Baseline frame taken before the action is performed, so the engine can
      // measure movement against a neutral pose; it travels with every poll.
      const baseline = grabFrame(0.9);
      const recent = [];
      log(`challenge ${rounds}: ${challenge.challengeType} — open up to `
        + `${Math.round(windowMs / 1000)}s, stops as soon as it is detected`);
      verdict("escalate", "ACTION REQUIRED",
        (escalation.message || `Please ${readable}.`)
        + " Take your time — the capture stops the moment the action is detected.");

      let outcome = null;
      // A little slack past the nominal deadline: the server owns the clock, so
      // always let it deliver the final verdict for this challenge.
      while (running && !outcome && Date.now() < deadline + 15000) {
        recent.push(grabFrame(0.9));
        if (recent.length > WINDOW) recent.shift();

        const left = Math.max(0, Math.ceil((deadline - Date.now()) / 1000));
        verdict("escalate", "CAPTURING…", `Perform "${readable}" — ${left}s left.`);

        if (recent.length >= 3) {
          const r = await api(`/sessions/${sessionId}/challenges/validate`, {
            method: "POST",
            body: JSON.stringify({ challengeId, framesBase64: [baseline, ...recent] })
          });
          log(`challenge ${rounds}: passed=${r.passed} action=${r.action} ${r.message || ""}`);
          // "continue" = still open and nothing detected yet. Anything else
          // (proceed / reject / retry_challenge) is final for this challenge.
          if (r.action !== "continue") outcome = r;
        }
        if (!outcome) await sleep(CAPTURE_MS);
      }

      if (!running) return { action: null, message: "Stopped by user." };
      if (!outcome) return { action: null, message: "Challenge window elapsed without a verdict." };
      // Terminal for this check: a session verdict, or a manual extra check's
      // own verdict. (The response carries the challenge it just resolved, so
      // falling through would re-validate a FAILED id and get a 409.)
      if (outcome.action === "proceed" || outcome.action === "reject"
          || outcome.action === "manual_check_passed"
          || outcome.action === "manual_check_failed"
          || TERMINAL_VERDICTS[outcome.action]) {
        return { action: outcome.action, message: outcome.message,
                 mayRetrySession: outcome.mayRetrySession === true };
      }
      // retry_challenge — the response carries the next challenge
      challenge = outcome.challenge;
      escalation = outcome;
      if (!challenge) return { action: outcome.action, message: outcome.message };
    }
    return { action: null, message: "Challenge budget exhausted." };
  }

  // ---------- manual active liveness (extra check) ----------
  //
  // Active liveness normally runs only when the passive stage escalates: above
  // the threshold the session passes with no challenge at all. This button asks
  // for one challenge anyway. The server marks it as a manual extra check — it
  // is recorded on its challenge row and in the audit trail, and it never
  // rewrites the session verdict (a missed action leaves the session PASSED).
  $("btn-active").addEventListener("click", async () => {
    if (running || !sessionId || !sessionPassed) return;
    running = true;
    $("btn-active").disabled = true;
    $("btn-run").disabled = true;
    $("btn-stop").disabled = false;

    try {
      setStep(3, "active");
      const challenge = await api(`/sessions/${sessionId}/active-liveness`, {
        method: "POST"
      });
      log(`extra active check requested: ${challenge.challengeType}`
        + ` (challenge ${challenge.id})`);
      const outcome = await runChallenges({
        challenge,
        message: "Extra active check — the session verdict is unchanged either way."
      });
      setStep(3, "done");
      if (outcome.action === "manual_check_passed") {
        verdict("proceed", "EXTRA CHECK PASSED",
          outcome.message || "Action detected.");
      } else if (outcome.action === "manual_check_failed") {
        verdict("escalate", "EXTRA CHECK FAILED",
          outcome.message || "Action not detected.");
      } else {
        verdict("escalate", "EXTRA CHECK INCOMPLETE",
          outcome.message || "No verdict was reached for the extra check.");
      }
      await refreshAudit();
      await refreshMetrics();
    } catch (e) {
      log("extra check error: " + e.message);
      verdict("reject", "ERROR", e.message);
    } finally {
      running = false;
      $("btn-run").disabled = false;
      $("btn-stop").disabled = true;
      setActiveCheckEnabled(sessionPassed);
    }
  });

  $("btn-stop").addEventListener("click", () => {
    running = false;
    log("Stop requested.");
    $("btn-stop").disabled = true;
  });

  function fmt(v) { return v === null || v === undefined ? "—" : Number(v).toFixed(3); }

  // ---------- metrics / audit / session ----------
  async function refreshMetrics() {
    try {
      const m = await api("/metrics");
      kv($("metrics"), m);
    } catch (e) { log("metrics error: " + e.message); }
  }
  async function refreshAudit() {
    if (!sessionId) return;
    try {
      const entries = await api(`/sessions/${sessionId}/audit`);
      $("audit").textContent = entries.length
        ? entries.map(e => `${e.createdAt}  ${e.eventType}  ${JSON.stringify(e.details)}`).join("\n")
        : "(no audit entries — audit records are written for PAD rejections, challenge issue/pass/fail and session outcomes)";
    } catch (e) { log("audit error: " + e.message); }
  }
  $("btn-metrics").addEventListener("click", refreshMetrics);

  // ---------- diagnostics (opt-in, local only) ----------
  // The section stays hidden until GET /api/v1/diagnostics answers 200 — the
  // service has diagnostic mode enabled AND this page is a loopback client.
  // Disabled and remote get the identical 404 server-side, so a probe never
  // announces a mode that is not on. Polling runs only while the panel is up.
  let diagTimer = null;
  function hideDiagnostics() {
    $("diag-section").hidden = true;
    if (diagTimer) { clearInterval(diagTimer); diagTimer = null; }
  }
  function diagNum(v, suffix) {
    if (v === null || v === undefined) return "—";
    return Number(v).toFixed(1) + (suffix || "");
  }
  async function refreshDiagnostics() {
    try {
      const res = await fetch("/api/v1/diagnostics", { cache: "no-store" });
      if (!res.ok) { hideDiagnostics(); return false; }
      const d = await res.json();
      if (!d.enabled) { hideDiagnostics(); return false; }
      $("diag-section").hidden = false;
      kv($("diag"), {
        scorer: d.scorer || "not resolved yet",   // delegate used (spec §10)
        fps: diagNum(d.fps),
        frames: d.frameCount,
        lastScore: fmt(d.lastScore),
        medianScore: fmt(d.medianScore),
        avgFrameMs: diagNum(d.avgFrameMs, " ms"),
        maxFrameMs: diagNum(d.maxFrameMs, " ms"),
        capturedAt: d.capturedAt
      });
      const rows = d.recentFrames || [];
      const pre = $("diag-frames");
      pre.textContent = rows.length
        ? rows.map(f => `${f.at}  score=${fmt(f.score)}  quality=${fmt(f.faceQuality)}  `
            + `${f.frameMs} ms  pad=${f.padFlag}  ${f.action}`).join("\n")
        : "No frames recorded yet.";
      pre.scrollTop = pre.scrollHeight;
      return true;
    } catch {
      hideDiagnostics();
      return false;
    }
  }
  $("btn-diag").addEventListener("click", refreshDiagnostics);
  refreshDiagnostics().then(ok => {
    if (ok && !diagTimer) diagTimer = setInterval(refreshDiagnostics, 2000);
  });
  $("btn-audit").addEventListener("click", refreshAudit);
  $("btn-close").addEventListener("click", async () => {
    if (!sessionId) { log("No session to close."); return; }
    try {
      const s = await api(`/sessions/${sessionId}/close`, { method: "POST" });
      log(`Session closed: status=${s.status} frames=${s.totalFramesEvaluated}`
        + ` challenges=${s.totalChallengesIssued} duration=${s.durationMs}ms`);
      $("pill-session").textContent = "session " + sessionId.slice(0, 8) + " (" + s.status + ")";
      $("pill-session").className = "pill";
      sessionPassed = false;
      setActiveCheckEnabled(false);
      await refreshAudit();
    } catch (e) { log("close error: " + e.message); }
  });

  // ---------- request budget (rate limiting) ----------
  //
  // RateLimitFilter publishes X-RateLimit-{Bucket,Limit,Remaining,Reset} on the
  // two limited write paths, on allowed requests as well as on 429s. Showing
  // the budget means a tester can see the limiter working — the frame budget
  // running down at capture rate, and the retry countdown after a 429 — instead
  // of only discovering it through a failed request.
  const RL_BUCKETS = {
    "session-create": "Session creation — per client IP, 60s window",
    frames: "Frames, challenges + extra checks — per session, 10s window"
  };
  const rlState = new Map();   // bucket -> { limit, remaining, resetAt, limited }
  const rlRows = new Map();    // bucket -> { row, num, fill, sub }

  function noteRateLimit(headers, status) {
    const name = headers.get("X-RateLimit-Bucket");
    const limit = Number(headers.get("X-RateLimit-Limit"));
    if (!name || !Number.isFinite(limit) || limit <= 0) return;
    const remaining = Number(headers.get("X-RateLimit-Remaining"));
    const resetSec = Number(headers.get("X-RateLimit-Reset"));
    rlState.set(name, {
      limit,
      remaining: Number.isFinite(remaining) ? remaining : limit,
      resetAt: Date.now() + (Number.isFinite(resetSec) ? resetSec : 0) * 1000,
      limited: status === 429
    });
    renderBudget();
  }

  function ensureRow(name) {
    let row = rlRows.get(name);
    if (row) return row;
    // First real budget reading: drop the "No limited requests yet." placeholder
    // text node so it doesn't sit next to the meters.
    if (!rlRows.size) $("rl-buckets").textContent = "";
    const el = document.createElement("div");
    el.className = "rl-row";
    const head = document.createElement("div");
    head.className = "rl-head";
    const label = document.createElement("span");
    label.textContent = RL_BUCKETS[name] || name;
    const num = document.createElement("span");
    num.className = "rl-num";
    const track = document.createElement("div");
    track.className = "rl-track";
    const fill = document.createElement("div");
    fill.className = "rl-fill";
    track.append(fill);
    const sub = document.createElement("div");
    sub.className = "rl-sub";
    head.append(label, num);
    el.append(head, track, sub);
    $("rl-buckets").append(el);
    row = { el, num, fill, sub };
    rlRows.set(name, row);
    return row;
  }

  function renderBudget() {
    for (const [name, b] of rlState) {
      const row = ensureRow(name);
      // Local countdown between requests: the server only reports the budget
      // on its limited routes, so the ticker must not imply a live query.
      const secs = Math.max(0, Math.ceil((b.resetAt - Date.now()) / 1000));
      const ratio = Math.max(0, Math.min(1, b.remaining / b.limit));
      row.fill.style.width = (ratio * 100).toFixed(1) + "%";
      row.el.classList.toggle("limited", b.limited);
      row.el.classList.toggle("warn", !b.limited && ratio <= 0.25);
      row.num.textContent = `${b.remaining} / ${b.limit} left`;
      row.sub.textContent = b.limited
        ? `rate limited (429) — retry in ${secs}s`
        : secs > 0
          ? `window resets in ${secs}s`
          : "window elapsed — the next request refills it";
    }
  }

  // ---------- config policy editor ----------
  //
  // Reads are open GETs; saving issues a PUT carrying the admin key
  // (MOSIP_ADMIN_API_KEY — fail-closed on the server). The key is kept in this
  // tab's sessionStorage only, is never written to the decision log, and never
  // stays in the DOM: the field shows a partial mask instead.
  const cfg = {
    workflow: $("cfg-workflow"), key: $("cfg-key"), keyHint: $("cfg-key-hint"),
    threshold: $("cfg-threshold"), timeout: $("cfg-timeout"),
    minChallenges: $("cfg-min-challenges"), retries: $("cfg-retries"),
    failure: $("cfg-failure"),
    liveness: $("cfg-liveness"), active: $("cfg-active"),
    blink: $("cfg-ch-blink"), smile: $("cfg-ch-smile"),
    left: $("cfg-ch-left"), right: $("cfg-ch-right"),
    status: $("cfg-status")
  };
  const CFG_TYPES = [[cfg.blink, "blink"], [cfg.smile, "smile"],
                     [cfg.left, "turn_left"], [cfg.right, "turn_right"]];
  const KEY_STORE = "mosip-admin-key";

  // The secret lives in this tab's sessionStorage and nowhere else. It used to
  // be written straight into the input's value, so a password-masked field was
  // the only thing between the key and a screenshot, a screen share or devtools.
  // Now the field is only ever a typing buffer: it is cleared on blur and after
  // every save, and the hint shows enough of the key to recognise which one is
  // loaded (same idea as the key fingerprint in the audit trail) and no more.
  let storedKey = "";

  function maskKey(key) {
    if (!key) return "";
    const tail = 4;
    // Short keys: mask everything rather than reveal most of a short secret.
    return key.length <= tail ? "•".repeat(key.length) : "•".repeat(12) + key.slice(-tail);
  }

  function cfgStatus(msg, ok) {
    cfg.status.textContent = msg || "";
    cfg.status.className = "cfg-status " + (msg ? (ok ? "ok" : "bad") : "");
  }
  function adminKey() { return storedKey; }

  function persistKey(key) {
    storedKey = (key || "").trim();
    try {
      if (storedKey) sessionStorage.setItem(KEY_STORE, storedKey);
      else sessionStorage.removeItem(KEY_STORE);
    } catch { /* private mode: the key stays in memory for this page only */ }
    if (cfg.keyHint) {
      cfg.keyHint.textContent = storedKey
        ? `Key loaded for this tab: ${maskKey(storedKey)}`
        : "";
      cfg.keyHint.hidden = !storedKey;
    }
  }

  try { persistKey(sessionStorage.getItem(KEY_STORE) || ""); } catch { /* private mode */ }
  cfg.key.addEventListener("input", () => persistKey(cfg.key.value));
  // Leaving the field takes the secret back out of the DOM.
  cfg.key.addEventListener("blur", () => { cfg.key.value = ""; });
  $("btn-cfg-forget-key").addEventListener("click", () => {
    persistKey("");
    cfg.key.value = "";
    cfgStatus("Admin key cleared from this tab.", true);
  });

  // ---------- dirty-form guard ----------
  //
  // Loading a workflow replaces every field, so picking a different workflow (or
  // pressing Load) used to throw away edits with no warning — including a
  // threshold an operator had just tuned. The form is now compared against the
  // last loaded/saved state: a dirty marker shows while edits are pending, and
  // anything that would replace the form asks first.
  const CFG_FIELDS = [cfg.threshold, cfg.timeout, cfg.minChallenges, cfg.retries,
                      cfg.failure, cfg.liveness, cfg.active,
                      ...CFG_TYPES.map(([box]) => box)];
  // The same state seen field by field, in payload names: the diff panel renders
  // these and the baseline captured below is what "discard" restores. One list
  // drives both, so the preview and the discard can never disagree about what
  // is pending. The four challenge checkboxes are one API field
  // (challengeTypes), so they are compared as one row.
  const CFG_COMPARE = [
    { label: "passiveThreshold", get: () => cfg.threshold.value },
    { label: "challengeTimeoutMs", get: () => cfg.timeout.value },
    { label: "minChallengeCount", get: () => cfg.minChallenges.value },
    { label: "maxRetryCount", get: () => cfg.retries.value },
    { label: "onRepeatedFailure", get: () => cfg.failure.value },
    { label: "livenessEnabled", get: () => cfg.liveness.checked },
    { label: "activeLivenessEnabled", get: () => cfg.active.checked },
    { label: "challengeTypes", get: () => CFG_TYPES.filter(([box]) => box.checked)
        .map(([, name]) => name).join(", ") || "(none)" }
  ];
  let baseline = "";
  // Field values as of the last load/save: the restore target for "Discard
  // edits". Captured separately from `baseline` (a joined fingerprint) because
  // restoring needs per-field values, and so does the old side of the diff.
  let baselineValues = null;
  let baselineCompare = null;
  let loadedWorkflow = cfg.workflow.value;
  let dirtyShown = false;

  function formFingerprint() {
    return CFG_FIELDS
      .map(el => (el.type === "checkbox" ? String(el.checked) : el.value))
      .join("\u0000");
  }
  function isDirty() { return baseline !== "" && formFingerprint() !== baseline; }

  function markClean(workflow) {
    baseline = formFingerprint();
    baselineValues = new Map(CFG_FIELDS.map(
        el => [el, el.type === "checkbox" ? el.checked : el.value]));
    baselineCompare = CFG_COMPARE.map(f => f.get());
    loadedWorkflow = workflow || cfg.workflow.value;
    refreshDirtyMark();
    refreshDiff();
  }

  // Both transitions matter: the marker appears on the first edit and clears
  // again when the operator reverts a field by hand.
  function refreshDirtyMark() {
    if (!isDirty()) {
      if (dirtyShown) {
        dirtyShown = false;
        cfgStatus("");
      }
      return;
    }
    if (dirtyShown) return;
    dirtyShown = true;
    cfg.status.textContent =
      `Unsaved changes to the ${loadedWorkflow} policy — Save policy to apply them.`;
    cfg.status.className = "cfg-status dirty";
  }

  // "Unsaved changes" says *that* the form moved, not *what* — and a threshold
  // edit is exactly the kind of change worth re-reading before it decides who
  // passes liveness. Every field that differs from the loaded policy is listed
  // as old → new while edits are pending; the panel disappears the moment the
  // form matches the policy again (reverted by hand, saved, or discarded).
  function refreshDiff() {
    const box = $("cfg-diff");
    box.textContent = "";
    if (!baselineCompare) { box.hidden = true; return; }
    const pending = CFG_COMPARE
        .map((f, i) => ({ label: f.label, from: baselineCompare[i], to: f.get() }))
        .filter(c => String(c.from) !== String(c.to));
    if (!pending.length) { box.hidden = true; return; }

    const title = document.createElement("div");
    title.className = "cfg-diff-title";
    title.textContent = `Pending changes — ${pending.length} field${pending.length > 1 ? "s" : ""} `
        + `${pending.length > 1 ? "differ" : "differs"} from the loaded ${loadedWorkflow} policy`;
    box.append(title);
    for (const c of pending) {
      const row = document.createElement("div");
      row.className = "cfg-diff-row";
      const label = document.createElement("span");
      label.className = "cfg-diff-label";
      label.textContent = c.label;
      const from = document.createElement("span");
      from.className = "cfg-diff-from";
      from.textContent = String(c.from);
      const arrow = document.createElement("span");
      arrow.className = "cfg-diff-arrow";
      arrow.textContent = "→";
      const to = document.createElement("span");
      to.className = "cfg-diff-to";
      to.textContent = String(c.to);
      row.append(label, from, arrow, to);
      box.append(row);
    }
    box.hidden = false;
  }

  // Put every field back to the copy captured at the last load/save. Works
  // offline: discarding must not depend on the reload request succeeding.
  function restoreBaseline() {
    if (!baselineValues) return;
    CFG_FIELDS.forEach(el => {
      const v = baselineValues.get(el);
      if (el.type === "checkbox") el.checked = v;
      else el.value = v;
    });
    markClean(loadedWorkflow);
  }

  // Ask before anything that replaces the form. Returns false to abort, and
  // puts the workflow select back so the UI matches what is actually loaded.
  // Only an explicit "yes" proceeds: if confirm() is unavailable (some embedded
  // webviews block dialogs) the guard fails closed and keeps the edits.
  function confirmDiscard(replacement) {
    if (!isDirty()) return true;
    let discard = false;
    try {
      discard = window.confirm(`The ${loadedWorkflow} policy has unsaved changes.\n\n`
          + `Discard them and load ${replacement} instead?`) === true;
    } catch { discard = false; }
    if (!discard) cfg.workflow.value = loadedWorkflow;
    return discard;
  }

  CFG_FIELDS.forEach(el => {
    const onEdit = () => { refreshDirtyMark(); refreshDiff(); };
    el.addEventListener("input", onEdit);
    el.addEventListener("change", onEdit);
  });

  async function loadConfig() {
    const wf = cfg.workflow.value;
    const c = await api(`/config/${wf}`);
    cfg.threshold.value = c.passiveThreshold;
    cfg.timeout.value = c.challengeTimeoutMs;
    cfg.minChallenges.value = c.minChallengeCount;
    cfg.retries.value = c.maxRetryCount;
    cfg.failure.value = c.onRepeatedFailure;
    cfg.liveness.checked = !!c.livenessEnabled;
    cfg.active.checked = !!c.activeLivenessEnabled;
    const types = new Set(c.challengeTypes || []);
    CFG_TYPES.forEach(([box, name]) => { box.checked = types.has(name); });
    markClean(wf);
    cfgStatus(`Loaded ${wf} policy` + (c.updatedAt ? ` (updated ${c.updatedAt})` : ""), true);
  }

  async function saveConfig() {
    const wf = cfg.workflow.value;
    if (!adminKey()) {
      cfgStatus("Enter the admin API key before saving (MOSIP_ADMIN_API_KEY).", false);
      return;
    }
    const challengeTypes = CFG_TYPES.filter(([box]) => box.checked).map(([, name]) => name);
    if (cfg.active.checked && challengeTypes.length === 0) {
      cfgStatus("Active liveness needs at least one challenge type.", false);
      return;
    }
    const body = {
      passiveThreshold: Number(cfg.threshold.value),
      challengeTimeoutMs: Number(cfg.timeout.value),
      minChallengeCount: Number(cfg.minChallenges.value),
      maxRetryCount: Number(cfg.retries.value),
      onRepeatedFailure: cfg.failure.value,
      livenessEnabled: cfg.liveness.checked,
      activeLivenessEnabled: cfg.active.checked,
      challengeTypes
    };
    if (Object.values(body).some(v => typeof v === "number" && !Number.isFinite(v))) {
      cfgStatus("All numeric fields need a value.", false);
      return;
    }
    await api(`/config/${wf}`, {
      method: "PUT",
      headers: { "X-Admin-API-Key": adminKey() },
      body: JSON.stringify(body)
    });
    // markClean first: it clears the dirty marker, which writes to the status line.
    markClean(wf);
    cfgStatus(`Saved ${wf} policy.`, true);
    // Take the secret back out of the DOM now that it has been used.
    cfg.key.value = "";
    // The audit view is the record of this edit — refresh it so the operator
    // sees the diff they just caused without another click.
    loadConfigAudit().catch(() => { /* history is a convenience, never block the save */ });
    log(`Config updated: ${wf} threshold=${body.passiveThreshold}`
      + ` active=${body.activeLivenessEnabled} retries=${body.maxRetryCount}`
      + ` challenges=[${body.challengeTypes.join(", ")}]`);
  }

  $("btn-cfg-load").addEventListener("click", () => {
    if (!confirmDiscard(`${cfg.workflow.value} (reload)`)) return;
    loadConfig().catch(e => cfgStatus(e.message, false));
  });
  $("btn-cfg-save").addEventListener("click", () =>
    saveConfig().catch(e => cfgStatus(e.message, false)));
  // Explicit "drop my edits": the same reload the workflow dropdown performs,
  // without having to switch workflows (and without the confirm dialog — the
  // click itself is the intent; the confirm exists to catch *unintended*
  // replacements). Re-reads the server's copy; if that fails, the captured copy
  // still restores the form, so discarding works offline.
  $("btn-cfg-discard").addEventListener("click", async () => {
    if (!isDirty()) {
      cfgStatus(`No pending edits — the form matches the loaded ${loadedWorkflow} policy.`, true);
      return;
    }
    try {
      await loadConfig();
      cfgStatus(`Discarded pending edits — reloaded the ${loadedWorkflow} policy.`, true);
    } catch (e) {
      restoreBaseline();
      cfgStatus(`Reload failed (${e.message}); restored the last loaded ${loadedWorkflow} policy.`, false);
    }
  });
  cfg.workflow.addEventListener("change", () => {
    if (!confirmDiscard(cfg.workflow.value)) return;
    loadConfig().catch(e => cfgStatus(e.message, false));
  });
  // Closing or reloading the tab loses the same edits — and, mid-check, the
  // in-flight session with them: the run is abandoned part-pipeline and the
  // verdict never arrives. The browser only offers its generic prompt here, but
  // that is enough to stop a stray refresh during capture.
  window.addEventListener("beforeunload", e => {
    if (!isDirty() && !running) return;
    e.preventDefault();
    e.returnValue = "";
  });

  // ---------- policy change history ----------
  //
  // Server-side audit of every config PUT: which workflow, which fields moved,
  // old → new, and a fingerprint of the admin key. Open GET, like the rest of
  // the config reads. Flattened to a line per change (the panel is a <pre>,
  // so no HTML is built from server data).
  function cfgVal(v) { return v === null || v === undefined ? "—" : String(v); }

  async function loadConfigAudit() {
    // The workflow filter is pushed to the server, not applied here: the audit
    // table grows a row per frame decision, so filtering the returned page
    // client-side would show "no entries" whenever the 20 newest rows happen
    // to be other workflows' — and would need every row to be correct.
    const workflow = $("cfg-audit-workflow").value;
    const query = workflow ? `&workflowType=${encodeURIComponent(workflow)}` : "";
    const entries = await api(`/config/audit?limit=20${query}`);
    if (!entries.length) {
      $("cfg-audit").textContent = workflow
        ? `No policy changes recorded for ${workflow}.`
        : "No policy changes recorded yet.";
      return;
    }
    $("cfg-audit").textContent = entries.map(e => {
      const d = e.details || {};
      const fields = Object.entries(d.changes || {});
      const body = fields.length
        ? fields.map(([k, v]) => `    ${k}: ${cfgVal(v.from)} → ${cfgVal(v.to)}`).join("\n")
        : "    (submitted, but no field value changed)";
      // e.workflowType is the indexed column; details.workflowType is the
      // payload's own copy. Fall back so an entry written before the column
      // existed still shows its workflow.
      const wf = e.workflowType || d.workflowType || "—";
      // The risk classification is the alert: the level plus the named
      // weakening move, so an edit that quietly lowers PAD reads as a
      // warning rather than a diff to be hand-checked. Entries written
      // before the classification existed show an em dash, never a guess.
      const risk = d.risk || {};
      const reasons = (risk.reasons || []).map(r => `    ! ${r}`).join("\n");
      return `${e.createdAt || "—"}  ${wf}  ${d.action || "—"}`
        + `  by ${d.actor || "unknown"}  [risk: ${risk.level || "—"}]`
        + (reasons ? `\n${reasons}` : "")
        + `\n${body}`;
    }).join("\n");
  }

  $("btn-cfg-audit").addEventListener("click", () =>
    loadConfigAudit().catch(e => cfgStatus(e.message, false)));
  // Changing the filter reloads immediately; there is no Apply button to miss.
  $("cfg-audit-workflow").addEventListener("change", () =>
    loadConfigAudit().catch(e => cfgStatus(e.message, false)));

  // ---------- chain integrity ----------
  // Live badge in the header: whether the config audit chain verifies and
  // whether the previous key is still accepted. The details distinguish that
  // setting from historical entries that still need the previous key.
  // GET /config/audit/verify is an open read, so no admin key is needed.
  function truncateHash(h) {
    return h ? h.slice(0, 8) + "…" + h.slice(-8) : "—";
  }

  async function refreshAuditVerify() {
    try {
      const v = await api("/config/audit/verify");
      const pill = $("pill-chain");
      if (v.intact) {
        if (v.rotationWindowOpen) {
          pill.textContent = "chain: intact · rotation window open";
          pill.className = "pill warn";
        } else {
          pill.textContent = "chain: intact";
          pill.className = "pill ok";
        }
      } else {
        pill.textContent = "chain: BROKEN";
        pill.className = "pill bad";
      }
      const pre = $("chain-details");
      const rotation = !v.rotationWindowOpen
        ? "closed"
        : v.retiredKeyHashes
          ? "window OPEN — entries still on the retired key"
          : v.intact
            ? "window OPEN — no entries need the retired key"
            : "window OPEN — historical use is unknown while the chain is broken";
      const lines = [
        "intact:     " + (v.intact ? "yes" : "NO"),
        "hmac:       " + (v.hmac ? "HMAC-SHA-256 (keyed)" : "SHA-256 (unkeyed fallback)"),
        "entries:    " + v.chainedEntries,
        "rotation:   " + rotation,
        "head hash:  " + truncateHash(v.headHash)
      ];
      if (!v.intact && v.breakIndex !== undefined) {
        lines.push("");
        lines.push("break at index " + v.breakIndex + " (id: " + (v.breakEntryId || "—") + "):");
        lines.push("  stored hash:   " + truncateHash(v.storedHash));
        lines.push("  recomputed:    " + truncateHash(v.recomputedHash));
        lines.push("  " + (v.contentChanged
          ? "→ content changed — the row no longer hashes to its stored entry_hash (edited)"
          : "→ linkage broken — the row's prev_hash points at a missing entry (deleted)"));
        lines.push("  expected prev: " + truncateHash(v.expectedPrevHash));
        lines.push("  stored prev:   " + truncateHash(v.storedPrevHash));
      }
      pre.textContent = lines.join("\n");
    } catch (e) {
      $("pill-chain").textContent = "chain: unreachable";
      $("pill-chain").className = "pill bad";
      $("chain-details").textContent = "Failed to verify the chain: " + e.message;
    }
  }

  $("btn-chain-refresh").addEventListener("click", () => refreshAuditVerify());

  refreshHealth();
  refreshMetrics();
  refreshAuditVerify();
  // First policy load is a plain open GET — no key needed.
  loadConfig().catch(e => cfgStatus(e.message, false));
  // The change history loads with the page too, so the fingerprint
  // of the key behind the most recent edit — and its risk level —
  // are visible at a glance, not only after clicking refresh.
  // Same open GET as the policy read; no key needed.
  loadConfigAudit().catch(e => cfgStatus(e.message, false));
  // Skip background polling while the tab is hidden — no reason to spend CPU/battery
  // refreshing a console nobody is looking at.
  setInterval(() => {
    if (!document.hidden) {
      refreshHealth();
      refreshAuditVerify();
    }
  }, 15000);
  // Tick the budget countdown even when idle — no requests needed.
  setInterval(() => { if (!document.hidden && rlState.size) renderBudget(); }, 1000);
})();
