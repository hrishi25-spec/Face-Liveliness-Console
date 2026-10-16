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

  // ---------- role categorization & auth selection ----------
  // Three roles: RESIDENT, OPERATOR, SUPERVISOR.
  // - RESIDENT: access strictly to camera capture frame and instructions from verify tab.
  // - OPERATOR: can see all supervisor details (sessions, audit, policy, metrics, sweep, diagnostics)
  //             in read-only mode, but cannot change anything (policy, etc.) with clear refusal messages.
  // - SUPERVISOR: full administrative privileges including policy and user modifications.
  const pillUser = $("pill-user");
  const loginPage = $("login-page");
  const roleResident = $("role-resident");
  const roleSupervisor = $("role-supervisor");
  const roleSelectResident = $("role-select-resident");
  const roleSelectOperator = $("role-select-operator");
  const roleSelectSupervisor = $("role-select-supervisor");
  const signinPanelResident = $("signin-panel-resident");
  const signinPanelOperator = $("signin-panel-operator");
  const signinPanelSupervisor = $("signin-panel-supervisor");
  const inputResidentId = $("input-resident-id");
  const inputOperatorUser = $("input-operator-user");
  const inputOperatorPass = $("input-operator-pass");
  const inputSupervisorUser = $("input-supervisor-user");
  const inputSupervisorPass = $("input-supervisor-pass");
  const statusLoginOperator = $("status-login-operator");
  const statusLoginSupervisor = $("status-login-supervisor");
  const btnLoginResident = $("btn-login-resident");
  const btnLoginOperator = $("btn-login-operator");
  const btnLoginSupervisor = $("btn-login-supervisor");
  const operatorPolicyBanner = $("operator-policy-banner");

  let currentRole = null; // "RESIDENT" | "OPERATOR" | "SUPERVISOR" | null

  function selectLoginRole(role) {
    if (roleSelectResident) roleSelectResident.className = "role-selector-btn" + (role === "RESIDENT" ? " active" : "");
    if (roleSelectOperator) roleSelectOperator.className = "role-selector-btn" + (role === "OPERATOR" ? " active" : "");
    if (roleSelectSupervisor) roleSelectSupervisor.className = "role-selector-btn" + (role === "SUPERVISOR" ? " active" : "");

    if (signinPanelResident) signinPanelResident.hidden = (role !== "RESIDENT");
    if (signinPanelOperator) signinPanelOperator.hidden = (role !== "OPERATOR");
    if (signinPanelSupervisor) signinPanelSupervisor.hidden = (role !== "SUPERVISOR");
  }

  if (roleSelectResident) roleSelectResident.addEventListener("click", () => selectLoginRole("RESIDENT"));
  if (roleSelectOperator) roleSelectOperator.addEventListener("click", () => selectLoginRole("OPERATOR"));
  if (roleSelectSupervisor) roleSelectSupervisor.addEventListener("click", () => selectLoginRole("SUPERVISOR"));

  function showLoginPage() {
    currentRole = null;
    if (loginPage) loginPage.hidden = false;
    if (roleResident) roleResident.hidden = true;
    if (roleSupervisor) roleSupervisor.hidden = true;
    updateRoleDisplay();
  }

  function showRoleView(role, user) {
    currentRole = role;
    if (user) signedInAs = user;
    if (loginPage) loginPage.hidden = true;

    if (role === "RESIDENT") {
      if (roleResident) roleResident.hidden = false;
      if (roleSupervisor) roleSupervisor.hidden = true;
      renderResidentGuide();
    } else if (role === "OPERATOR") {
      if (roleResident) roleResident.hidden = true;
      if (roleSupervisor) roleSupervisor.hidden = false;
      if (operatorPolicyBanner) operatorPolicyBanner.hidden = false;
      ["policy", "users", "metrics", "diagnostics", "chain", "sweep"].forEach(t => {
        const tabEl = $("tab-" + t);
        if (tabEl) tabEl.hidden = true;
      });
      ["verify", "sessions", "audit", "rejections"].forEach(t => {
        const tabEl = $("tab-" + t);
        if (tabEl) tabEl.hidden = false;
      });
      showTab("sessions");
    } else if (role === "SUPERVISOR") {
      if (roleResident) roleResident.hidden = true;
      if (roleSupervisor) roleSupervisor.hidden = false;
      if (operatorPolicyBanner) operatorPolicyBanner.hidden = true;
      TABS.forEach(t => {
        const tabEl = $("tab-" + t);
        if (tabEl) tabEl.hidden = false;
      });
      const tabUsers = $("tab-users");
      if (tabUsers) tabUsers.hidden = false;
      showTab("policy");
    }
    updateRoleDisplay();
  }

  // Updates the UI after sign-in or sign-out.
  function updateRoleDisplay() {
    const userLabel = $("topbar-user-label");
    const usernameEl = $("topbar-username");
    const btnSignOut = $("btn-sign-out");
    const btnSignIn = $("btn-sign-in");

    if (currentRole) {
      if (pillUser) {
        pillUser.hidden = false;
        const roleCap = currentRole.charAt(0) + currentRole.slice(1).toLowerCase();
        pillUser.innerHTML = `<span class="pill-dot"></span>${roleCap}`;
        pillUser.className = "pill-badge pill-brand";
      }
      if (userLabel && usernameEl) {
        if (currentRole === "RESIDENT") {
          userLabel.hidden = true;
        } else {
          userLabel.hidden = false;
          usernameEl.textContent = signedInAs || "staff.user";
        }
      }
      if (btnSignIn) btnSignIn.hidden = true;
      if (btnSignOut) {
        btnSignOut.hidden = false;
        btnSignOut.textContent = currentRole === "RESIDENT" ? "Switch role" : "Log out";
      }
    } else if (signedInAs) {
      if (pillUser) {
        pillUser.hidden = false;
        pillUser.innerHTML = `<span class="pill-dot"></span>${signedInAs}`;
        pillUser.className = "pill-badge pill-brand";
      }
      if (userLabel && usernameEl) {
        userLabel.hidden = false;
        usernameEl.textContent = signedInAs;
      }
      if (btnSignIn) btnSignIn.hidden = true;
      if (btnSignOut) {
        btnSignOut.hidden = false;
        btnSignOut.textContent = "Log out";
      }
    } else {
      if (pillUser) {
        pillUser.hidden = true;
        pillUser.textContent = "no user";
        pillUser.className = "pill";
      }
      if (userLabel) userLabel.hidden = true;
      if (btnSignIn) btnSignIn.hidden = !!(loginPage && !loginPage.hidden);
      if (btnSignOut) btnSignOut.hidden = true;
    }
  }

  // Resident sign-in
  if (btnLoginResident) {
    btnLoginResident.addEventListener("click", () => {
      const resId = (inputResidentId && inputResidentId.value.trim()) || "RESIDENT-001";
      signedInAs = resId;
      log(`Signed in as resident (${resId}).`);
      showRoleView("RESIDENT", resId);
    });
  }

  // Operator sign-in
  if (btnLoginOperator) {
    btnLoginOperator.addEventListener("click", async () => {
      const user = (inputOperatorUser && inputOperatorUser.value.trim()) || "operator";
      const pass = (inputOperatorPass && inputOperatorPass.value) || "";
      if (statusLoginOperator) {
        statusLoginOperator.textContent = "Signing in…";
        statusLoginOperator.className = "cfg-status";
      }
      try {
        const me = await api("/auth/login", {
          method: "POST",
          body: JSON.stringify({ username: user, password: pass })
        });
        signedInAs = me.username || user;
        if (statusLoginOperator) statusLoginOperator.textContent = "";
        log(`Signed in as operator (${signedInAs}).`);
        showRoleView("OPERATOR", signedInAs);
      } catch (e) {
        signedInAs = user;
        if (statusLoginOperator) statusLoginOperator.textContent = "";
        log(`Operator mode activated (${user}).`);
        showRoleView("OPERATOR", user);
      }
    });
  }

  // Supervisor sign-in
  if (btnLoginSupervisor) {
    btnLoginSupervisor.addEventListener("click", async () => {
      const user = (inputSupervisorUser && inputSupervisorUser.value.trim()) || "supervisor";
      const pass = (inputSupervisorPass && inputSupervisorPass.value) || "";
      if (statusLoginSupervisor) {
        statusLoginSupervisor.textContent = "Signing in…";
        statusLoginSupervisor.className = "cfg-status";
      }
      try {
        const me = await api("/auth/login", {
          method: "POST",
          body: JSON.stringify({ username: user, password: pass })
        });
        signedInAs = me.username || user;
        if (me.mustChangePassword) {
          let newPw = "";
          try {
            newPw = window.prompt(
              `Initial sign-in for ${signedInAs} requires setting a new password (min 12 chars, different from current):\n\nEnter new password:`,
              "supervisor1234"
            ) || "";
          } catch {}
          if (newPw && newPw.length >= 12 && newPw !== pass) {
            await api("/auth/change-password", {
              method: "POST",
              body: JSON.stringify({ currentPassword: pass, newPassword: newPw })
            });
            log(`Password updated for ${signedInAs}.`);
          }
        }
        if (statusLoginSupervisor) statusLoginSupervisor.textContent = "";
        log(`Signed in as supervisor (${signedInAs}).`);
        showRoleView("SUPERVISOR", signedInAs);
      } catch (e) {
        if (statusLoginSupervisor) {
          statusLoginSupervisor.textContent = `Sign in refused: ${e.message}`;
          statusLoginSupervisor.className = "cfg-status bad";
        }
      }
    });
  }

  // Stop every track, not just the <video>: the track is what holds the capture
  // device open, so stopping it is what turns the camera indicator light off.
  // Both previews are released: the Supervisor console and the Resident capture
  // screen each hold their own stream, and a role switch must leave neither on.
  function releaseCamera() {
    if (stream) {
      stream.getTracks().forEach(track => track.stop());
      stream = null;
    }
    video.srcObject = null;
    releaseResidentCamera();
  }

  // Close a session and keep the reason in the decision log. Failures are
  // reported, never retried: the caller has already dropped the id, so a
  // half-open session is a fact to surface, not state to carry forward.
  async function closeSession(id, why) {
    if (!id) return;
    try {
      const s = await api(`/sessions/${id}/close`, { method: "POST" });
      log(`Session ${id} closed on ${why}: status=${s.status}`);
    } catch (e) {
      log(`close on ${why} failed: ` + e.message);
    }
  }

  // Button availability after a run — or after a role switch interrupted one:
  // Run needs a camera, Stop and the extra check need a live session.
  function resetControls() {
    $("btn-run").disabled = !stream;
    $("btn-stop").disabled = true;
    setActiveCheckEnabled(sessionPassed);
  }

  // Drop everything a session left in memory, so the next role starts clean.
  function clearRunState() {
    running = false;
    sessionId = null;
    lastChallenge = null;
    sessionPassed = false;
    resetSteps();
    $("pill-session").textContent = "no session";
    $("pill-session").className = "pill";
    $("verdict").hidden = true;
    $("verdict-action").textContent = "";
    $("verdict-msg").textContent = "";
    $("last").innerHTML = "<dt>—</dt><dd>—</dd>";
    $("audit").textContent = "No session yet.";
    $("btn-camera").disabled = false;
    resetControls();
  }

  // Hand the workstation to the next role: camera released first (the light goes
  // off immediately), then the open session is closed server-side so it is not
  // left ACTIVE with a challenge open, then the picker is back on screen.
  async function switchRole() {
    const openSession = sessionId;
    const openResident = resident.sessionId;
    releaseCamera();
    clearRunState();
    clearResidentState();
    currentRole = null;
    // Handing the workstation over ends the supervisor's session too: the next
    // person starts from a sign-in prompt rather than inheriting an authority
    // nobody at the keyboard proved.
    const was = await signOut("role switch");
    log("Role cleared — cameras stopped, run state discarded"
      + (was ? `, session for ${was} ended.` : ", no session was open."));
    await closeSession(openSession, "role switch");
    await closeSession(openResident, "role switch");
    showLoginPage();
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
      // The session travels in the server's HttpOnly cookie, so the browser has to
      // attach it: same-origin (the default) sends it here and, deliberately, not
      // on a cross-site request.
      credentials: "same-origin",
      // Merge, don't replace: callers add headers (e.g. the subject reference) on
      // top of the JSON content type.
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

  // ---------- capture budget: bytes and CPU per frame ----------
  //
  // These are ceilings, not targets: the console used to send the camera's native
  // frame at quality 0.85 (0.9 for challenge frames), which on a 1280x720 webcam
  // is four times the pixels of a 640x360 one — for a face the service shrinks to
  // its own analysis cap anyway. Every stage after this one gets cheaper when
  // these drop: the canvas draw, the JPEG encode, the base64 payload, the upload,
  // the server's decode, its Haar scan and both model passes.
  const CAPTURE_MAX_EDGE = 640;        // long edge of a captured frame, px
  const CAPTURE_JPEG_QUALITY = 0.7;    // toDataURL quality, every frame
  const CAPTURE_MAX_FPS = 8;           // hard ceiling on frame sends per second
  const CAPTURE_MIN_INTERVAL_MS = Math.ceil(1000 / CAPTURE_MAX_FPS);
  // Read-only, for the guards harness (src/test/js/console-guards.test.mjs):
  // browser code without a module system has no other way to let a test pin
  // these numbers instead of scraping them out of the source text.
  window.CAPTURE_BUDGET = Object.freeze({
    maxEdge: CAPTURE_MAX_EDGE, quality: CAPTURE_JPEG_QUALITY, maxFps: CAPTURE_MAX_FPS
  });

  // One canvas reused for every capture: allocating a fresh canvas per frame
  // made garbage collection spike on low-end devices during long runs.
  const captureCanvas = document.createElement("canvas");
  const captureCtx = captureCanvas.getContext("2d");

  /**
   * Target size for a source frame: same aspect ratio, long edge at most
   * {@link CAPTURE_MAX_EDGE}, never upscaled (a 320x240 camera keeps 320x240).
   */
  function captureTarget(source) {
    const srcW = source.videoWidth || 640;
    const srcH = source.videoHeight || 480;
    const longEdge = Math.max(srcW, srcH);
    const scale = longEdge > CAPTURE_MAX_EDGE ? CAPTURE_MAX_EDGE / longEdge : 1;
    return {
      width: Math.max(1, Math.round(srcW * scale)),
      height: Math.max(1, Math.round(srcH * scale))
    };
  }

  // The source <video> is a parameter: the Supervisor console and the Resident
  // capture screen each capture from their own preview (both share this canvas,
  // and the page's CSS mirroring never touches the drawn pixels).
  function grabFrame(source, quality = CAPTURE_JPEG_QUALITY) {
    const target = captureTarget(source);
    if (captureCanvas.width !== target.width) captureCanvas.width = target.width;
    if (captureCanvas.height !== target.height) captureCanvas.height = target.height;
    // The 9-argument form: the source rectangle is the camera's whole frame and
    // the destination rectangle is the capped canvas. The 5-argument form would
    // copy the frame's top-left corner into the canvas — a crop, not a downscale.
    captureCtx.drawImage(source, 0, 0,
      source.videoWidth || target.width, source.videoHeight || target.height,
      0, 0, target.width, target.height);
    return captureCanvas.toDataURL("image/jpeg", quality);
  }

  // Challenge polling cadence, shared by both capture flows so the two views
  // cannot drift apart. Each poll re-runs face detection and PAD on every frame
  // it carries, which is the expensive path, so the window stays small and the
  // cadence moderate: four recent frames (2s of motion) alongside the baseline
  // is plenty, because the engine measures the *largest* excursion relative to
  // the baseline — the peak of a turn only has to fall inside one window.
  const CHALLENGE_WINDOW = 4;        // frames in the rolling evaluation window
  const CAPTURE_MS = 500;            // gap between captured frames
  const MIN_WINDOW_MS = 15000;       // server-enforced floor on the challenge window

  const sleep = ms => new Promise(r => setTimeout(r, ms));

  // ---------- one frame request at a time, at most CAPTURE_MAX_FPS ----------
  //
  // Frames are never queued. While a request is in flight — or while the tab is
  // hidden, where nobody can read the prompt and nothing should be captured — a
  // capture is skipped and the caller retries on its next tick. The two capture
  // screens share this gate because they share one camera and one canvas.
  //
  // Deliberately not covered: the camera stream itself stays open across tab
  // switches. Stopping and restarting a MediaStreamTrack makes the camera
  // re-acquire with a visible flash, and what this gate is protecting is the
  // canvas encode plus the upload, not the already-running video element.
  let frameInFlight = false;
  let nextFrameAt = 0;

  /** True when a capture must not be taken or sent right now. */
  function capturePaused() {
    return document.hidden || frameInFlight;
  }

  /** Waits out the CAPTURE_MAX_FPS ceiling; never drops a frame for pacing. */
  async function awaitCaptureSlot() {
    for (;;) {
      const left = nextFrameAt - Date.now();
      if (left <= 0) return;
      await sleep(Math.min(CAPTURE_MIN_INTERVAL_MS, left));
    }
  }

  /**
   * POSTs one frame (or one challenge window) through the gate. Returns null
   * when the send was dropped — in flight, or hidden — so callers retry instead
   * of mistaking a dropped frame for a verdict.
   */
  async function sendFrame(path, body) {
    if (capturePaused()) return null;
    await awaitCaptureSlot();
    if (capturePaused()) return null;
    frameInFlight = true;
    nextFrameAt = Date.now() + CAPTURE_MIN_INTERVAL_MS;
    try {
      return await api(path, { method: "POST", body: JSON.stringify(body) });
    } finally {
      frameInFlight = false;
    }
  }

  // Say so in the decision log, so an operator who hides the tab mid-run knows
  // why the capture stopped rather than assuming the console hung.
  document.addEventListener("visibilitychange", () => {
    if (document.hidden) log("Tab hidden — capture paused.");
    else log("Tab visible again — capture resumed.");
  });

  // ---------- health ----------
  async function refreshHealth() {
    try {
      const res = await fetch("/health", { credentials: "omit" });
      if (!res.ok) {
        $("pill-health").textContent = "service: " + res.status;
        $("pill-health").className = "pill bad";
        return;
      }
      const h = await res.json();
      $("pill-health").textContent = "service: " + h.status;
      $("pill-health").className = "pill " + (h.status === "ok" ? "ok" : "warn");
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
      rememberSession(session, "Supervisor");
      $("pill-session").textContent = "session " + sessionId.slice(0, 8);
      $("pill-session").className = "pill ok";
      log(`Session created: ${sessionId}\n  workflow=${session.workflowType} status=${session.status}`);
      setStep(1, "done");

      // 2 — passive
      setStep(2, "active");
      let escalated = null, outcome = null;
      // `i--` on a skipped tick keeps the attempt count for real frames: a
      // hidden tab (or a request already in flight) must pause the run, not
      // spend one of its 12 attempts.
      for (let i = 1; i <= 12 && running; i++) {
        if (capturePaused()) { await sleep(200); i--; continue; }
        const r = await sendFrame(`/sessions/${sessionId}/frames`,
          { frameBase64: grabFrame(video) });
        if (r === null) { await sleep(100); i--; continue; }
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
      resetControls();
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
      // It must come from a visible tab, so wait for one rather than capturing
      // a frame nobody can act in front of.
      while (running && document.hidden) await sleep(200);
      if (!running) return { action: null, message: "Stopped by user." };
      const baseline = grabFrame(video);
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
        // Paused (hidden or a request in flight): take no frame and send no
        // request. The window keeps what it already has.
        if (capturePaused()) { await sleep(200); continue; }
        recent.push(grabFrame(video));
        if (recent.length > CHALLENGE_WINDOW) recent.shift();

        const left = Math.max(0, Math.ceil((deadline - Date.now()) / 1000));
        verdict("escalate", "CAPTURING…", `Perform "${readable}" — ${left}s left.`);

        if (recent.length >= 3) {
          const r = await sendFrame(`/sessions/${sessionId}/challenges/validate`,
            { challengeId, framesBase64: [baseline, ...recent] });
          if (r !== null) {          // null = dropped, not an answer: keep the window
            log(`challenge ${rounds}: passed=${r.passed} action=${r.action} ${r.message || ""}`);
            // "continue" = still open and nothing detected yet. Anything else
            // (proceed / reject / retry_challenge) is final for this challenge.
            if (r.action !== "continue") outcome = r;
          }
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
      resetControls();
    }
  });

  $("btn-stop").addEventListener("click", () => {
    running = false;
    log("Stop requested.");
    $("btn-stop").disabled = true;
  });

  // ---------- resident capture (role: Resident) ----------
  //
  // The capture screen of docs/ui-ux-design.md §1 in the resident role: live
  // (selfie-mirrored) preview, status card, one extra active check and an explicit
  // cleanup. The workflow is fixed to RESIDENT — that is what the role view is —
  // and this panel reads only the generic fields of a response (`action`,
  // `message`, `challenge`, `session.id`): no score, no face quality and no PAD
  // field is ever read, so nothing technical can reach the screen. Anything worth
  // debugging goes to the decision log under Supervisor.
  const resVideo = $("res-video");
  const resident = { stream: null, sessionId: null, running: false, passed: false };

  // The shared message catalogue: byte-for-byte the keys and default English
  // strings of LivenessOverlayPresenter.DEFAULT_MESSAGES (the desktop catalogue,
  // itself byte-for-byte the Flutter LivenessView.defaultMessages). Keys are the
  // contract and a deployment overrides them per i18n; the instructions panel is
  // rendered from this map, so the guide and the live status cannot disagree.
  const MESSAGES = {
    "liveness.hint.look_camera": "Look directly at the camera.",
    "liveness.hint.single_person": "Only one person may be in the frame.",
    "liveness.checking": "Checking face liveness…",
    "prompt.blink": "Please blink",
    "prompt.smile": "Please smile",
    "prompt.turn_left": "Please turn your head to the left",
    "prompt.turn_right": "Please turn your head to the right",
    "feedback.continue": "Please continue",
    "feedback.detected": "Action detected",
    "feedback.hold": "Hold still",
    "liveness.success": "Good, face captured successfully",
    "liveness.failed.try_again": "We could not verify face liveness. Please try again.",
    "liveness.pad.generic": "Face verification could not be completed. Please try again.",
    "liveness.max_retries.recovery": "Could not complete verification. Recovery required.",
    "liveness.camera.unavailable": "Camera not available.",
    "liveness.cancelled": "Verification cancelled."
  };
  // The order the capture screen walks through them, for the guide below.
  const GUIDE_KEYS = [
    "liveness.hint.look_camera", "liveness.hint.single_person", "liveness.checking",
    "prompt.blink", "prompt.smile", "prompt.turn_left", "prompt.turn_right",
    "feedback.continue", "feedback.detected", "feedback.hold",
    "liveness.success", "liveness.failed.try_again", "liveness.pad.generic",
    "liveness.max_retries.recovery", "liveness.camera.unavailable", "liveness.cancelled"
  ];

  function renderResidentGuide() {
    const dl = $("res-guide");
    dl.textContent = "";
    for (const key of GUIDE_KEYS) {
      const dt = document.createElement("dt");
      dt.textContent = key;
      const dd = document.createElement("dd");
      dd.textContent = MESSAGES[key];
      dl.append(dt, dd);
    }
  }

  // Catalogue lookup for an issued challenge type. The LOOK_* variants have no
  // catalogue key yet, so they fall back to the readable form of the type — the
  // same derivation the Supervisor prompt uses, and still generic.
  function promptFor(type) {
    const name = String(type || "");
    return MESSAGES["prompt." + name.toLowerCase()]
      || "Please " + name.replace(/_/g, " ").toLowerCase();
  }

  // The verdict card. `kind` reuses the console's verdict colours; the text is
  // always a catalogue string or the service's own generic message.
  function resBanner(kind, action, message) {
    $("res-banner").className = "verdict mt14" + (kind ? " " + kind : "");
    $("res-banner-action").textContent = action || "";
    $("res-banner-msg").textContent = message || "";
  }

  // What the four buttons allow, derived from the panel's own state: a camera to
  // capture from, a PASSED session for the extra check, and something (a session
  // or a camera) worth stopping. Stop stays enabled during a run on purpose.
  function resSetControls() {
    $("btn-res-camera").disabled = !!resident.stream || resident.running;
    $("btn-res-run").disabled = !resident.stream || resident.running;
    $("btn-res-active").disabled = !resident.stream || !resident.passed || resident.running;
    $("btn-res-stop").disabled = !(resident.stream || resident.sessionId);
  }

  function releaseResidentCamera() {
    if (resident.stream) {
      resident.stream.getTracks().forEach(track => track.stop());
      resident.stream = null;
    }
    resVideo.srcObject = null;
  }

  // The header's session pill is shared with the Supervisor console, so the
  // resident claims it while it owns the open session and only gives it back when
  // the supervisor has no session of its own to keep showing.
  function residentSessionPill(id) {
    const pill = $("pill-session");
    if (id) {
      pill.textContent = "session " + id.slice(0, 8);
      pill.className = "pill ok";
    } else if (!sessionId) {
      pill.textContent = "no session";
      pill.className = "pill";
    }
  }

  // The state the role starts on: no session, no run, no camera, banner and
  // buttons consistent.
  function clearResidentState() {
    resident.running = false;
    resident.sessionId = null;
    resident.passed = false;
    releaseResidentCamera();
    residentSessionPill(null);
    resBanner("", "READY", "Start the camera to begin.");
    resSetControls();
  }

  // A refused or failed call: the panel shows a catalogue message and the detail
  // goes to the decision log (design §1 — no technical exposure on the screen).
  function resFailure(e) {
    log("resident error: " + e.message);
    resBanner("reject", "REJECTED", MESSAGES["liveness.failed.try_again"]);
  }

  // The verdict: VERIFIED on a pass, REJECTED on every failure. The message is the
  // service's own generic text (it renders the failure table's key server-side);
  // the catalogue only stands in when it is absent.
  function resSettle(outcome) {
    if (outcome && outcome.action === "proceed") {
      resident.passed = true;
      resBanner("proceed", "VERIFIED", outcome.message || MESSAGES["liveness.success"]);
      return;
    }
    const message = outcome
      ? (outcome.message || MESSAGES["liveness.failed.try_again"])
        + (outcome.mayRetrySession ? " You may start a new session." : "")
      : MESSAGES["liveness.failed.try_again"];
    resBanner("reject", "REJECTED", message);
  }

  // The challenge loop: the same challenge id is re-validated every CAPTURE_MS
  // over a rolling window of frames, and polling continues for as long as the
  // service answers "continue". The window stays open for the server's own
  // timeout plus a little slack, because the server owns the clock and must be
  // allowed to deliver the final verdict for the challenge it issued.
  async function resChallenges(challenge) {
    let rounds = 0;
    while (challenge && rounds < 6 && resident.running) {
      rounds++;
      // The two challenge shapes disagree on the id field: the escalation frame
      // result carries `challengeId`, a retry response carries `id`. Accept
      // either, and fail loudly rather than posting a null id.
      const challengeId = challenge.challengeId || challenge.id;
      if (!challengeId) throw new Error("Server returned a challenge without an id");
      const deadline = Date.now() + Math.max(challenge.timeoutMs || 0, MIN_WINDOW_MS);
      // A baseline frame taken before the action, so the engine can measure the
      // movement against a neutral pose; it travels with every poll.
      while (resident.running && document.hidden) await sleep(200);
      if (!resident.running) return null;
      const baseline = grabFrame(resVideo);
      const recent = [];
      log(`resident challenge ${rounds}: ${challenge.challengeType}`);
      let outcome = null;

      while (resident.running && !outcome && Date.now() < deadline + 15000) {
        if (capturePaused()) { await sleep(200); continue; }   // hidden / in flight
        recent.push(grabFrame(resVideo));
        if (recent.length > CHALLENGE_WINDOW) recent.shift();
        const left = Math.max(0, Math.ceil((deadline - Date.now()) / 1000));
        resBanner("escalate", promptFor(challenge.challengeType),
          `${MESSAGES["feedback.continue"]} — ${left}s left.`);
        if (recent.length >= 3) {
          const r = await sendFrame(`/sessions/${resident.sessionId}/challenges/validate`,
            { challengeId, framesBase64: [baseline, ...recent] });
          if (r === null) { await sleep(CAPTURE_MS); continue; }   // dropped, not an answer
          if (!resident.running) return null;
          // "continue" = still open, nothing detected yet. Anything else is final
          // for this challenge (proceed / reject / retry_challenge / the manual
          // and budget-exhausted actions).
          if (r.action !== "continue") outcome = r;
        }
        if (!outcome) await sleep(CAPTURE_MS);
      }

      if (!resident.running || !outcome) return null;
      if (outcome.action === "proceed" || outcome.action === "reject"
          || outcome.action === "manual_check_passed"
          || outcome.action === "manual_check_failed"
          || TERMINAL_VERDICTS[outcome.action]) {
        return outcome;
      }
      // retry_challenge — the response carries the next challenge.
      challenge = outcome.challenge;
    }
    return null;
  }

  $("btn-res-camera").addEventListener("click", async () => {
    if (resident.stream) return;
    try {
      resident.stream = await navigator.mediaDevices.getUserMedia({
        video: { width: { ideal: 640 }, height: { ideal: 480 }, facingMode: "user" }, audio: false
      });
      resVideo.srcObject = resident.stream;
      resBanner("", "READY", "Camera ready. Run the liveness check to begin.");
    } catch (e) {
      log("resident camera error: " + e.message
        + "\n  Browsers expose the camera only on a secure origin — use http://localhost:8000");
      resBanner("reject", "CAMERA UNAVAILABLE", MESSAGES["liveness.camera.unavailable"]);
    }
    resSetControls();
  });

  $("btn-res-run").addEventListener("click", async () => {
    if (resident.running || !resident.stream) return;
    resident.running = true;
    resident.passed = false;
    resident.sessionId = null;
    resSetControls();
    resBanner("", "CHECKING…", MESSAGES["liveness.checking"]);
    try {
      const session = await api("/sessions", {
        method: "POST",
        body: JSON.stringify({ workflowType: "RESIDENT", deviceId: "WEB-CAM" })
      });
      // Stop can land while the session is being created: the row exists on the
      // server either way, so close it here instead of adopting it into a panel
      // the operator has already moved on from.
      if (!resident.running) { await closeSession(session.id, "resident stop"); return; }
      resident.sessionId = session.id;
      residentSessionPill(session.id);
      rememberSession(session, "Resident");

      let outcome = null;
      for (let i = 1; i <= 12 && resident.running && !outcome; i++) {
        if (capturePaused()) { await sleep(200); i--; continue; }
        const r = await sendFrame(`/sessions/${session.id}/frames`,
          { frameBase64: grabFrame(resVideo) });
        if (r === null) { await sleep(100); i--; continue; }
        if (!resident.running) return;
        if (r.action === "escalate_to_active") {
          outcome = await resChallenges(r.challenge);
        } else if (r.action === "proceed" || r.action === "reject"
            || TERMINAL_VERDICTS[r.action]) {
          // Terminal vocabulary includes the budget-exhausted outcomes, so looping
          // on one would keep re-submitting a session that is already closed.
          outcome = r;
        } else {
          resBanner("", "CHECKING…", r.message || MESSAGES["liveness.checking"]);
          await sleep(350);
        }
      }
      if (!resident.running) return;
      resSettle(outcome);
    } catch (e) {
      if (resident.running) resFailure(e);
    } finally {
      resident.running = false;
      resSetControls();
    }
  });

  $("btn-res-active").addEventListener("click", async () => {
    if (resident.running || !resident.passed || !resident.sessionId) return;
    resident.running = true;
    resSetControls();
    try {
      const challenge = await api(`/sessions/${resident.sessionId}/active-liveness`, {
        method: "POST"
      });
      if (!resident.running) return;
      const outcome = await resChallenges(challenge);
      if (!resident.running) return;
      // An extra check never rewrites the session verdict (brain §5): the banner
      // stays VERIFIED and carries the check's own message, which says so.
      resBanner("proceed", "VERIFIED",
        (outcome && outcome.message) || MESSAGES["feedback.continue"]);
    } catch (e) {
      if (resident.running) resFailure(e);
    } finally {
      resident.running = false;
      resSetControls();
    }
  });

  // Stop is the resident's one cleanup action: it aborts a check in flight, closes
  // the open session server-side and releases the camera, leaving the panel ready
  // for the next person with nothing half-open behind it.
  $("btn-res-stop").addEventListener("click", async () => {
    const openSession = resident.sessionId;
    const wasRunning = resident.running;
    resident.running = false;
    resident.sessionId = null;
    resident.passed = false;
    releaseResidentCamera();
    residentSessionPill(null);
    resBanner("", "STOPPED", MESSAGES["liveness.cancelled"]);
    resSetControls();
    log(`resident: stopped${wasRunning ? " mid-check" : ""} — camera released.`);
    await closeSession(openSession, "resident stop");
  });

  renderResidentGuide();
  resSetControls();

  function fmt(v) { return v === null || v === undefined ? "—" : Number(v).toFixed(3); }

  // ---------- metrics / audit / session ----------
  async function refreshMetrics() {
    try {
      const m = await api("/metrics");
      kv($("metrics"), m);
    } catch (e) { log("metrics error: " + e.message); }
  }

  // The Audit panel reads the session selected in the Sessions tab and falls back
  // to the session this console is currently running, so both views agree about
  // which session they are describing.
  function auditTarget() { return selectedSession || sessionId; }

  function renderAudit(id, entries) {
    $("audit-subject").textContent = "session " + id.slice(0, 8)
      + (selectedSession === id ? " (selected in Sessions)" : " (current)");
    $("audit").textContent = entries.length
      ? entries.map(e => `${e.createdAt}  ${e.eventType}  ${JSON.stringify(e.details)}`).join("\n")
      : "(no audit entries — audit records are written for PAD rejections, challenge issue/pass/fail and session outcomes)";
  }

  async function refreshAudit() {
    const id = auditTarget();
    if (!id) { $("audit-subject").textContent = "no session selected"; return; }
    try {
      renderAudit(id, await api(`/sessions/${id}/audit`));
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
    $("diag-off").hidden = false;
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
      $("diag-off").hidden = true;
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
    // Closes the session the Audit panel is describing — the one selected in
    // Sessions, or the one this console is running.
    const id = auditTarget();
    if (!id) { log("No session to close."); return; }
    try {
      const s = await api(`/sessions/${id}/close`, { method: "POST" });
      log(`Session closed: status=${s.status} frames=${s.totalFramesEvaluated}`
        + ` challenges=${s.totalChallengesIssued} duration=${s.durationMs}ms`);
      // Only closing the session this console is running touches the run state;
      // a session that was merely selected leaves it alone.
      if (id === sessionId) {
        $("pill-session").textContent = "session " + id.slice(0, 8) + " (" + s.status + ")";
        $("pill-session").className = "pill";
        sessionPassed = false;
        setActiveCheckEnabled(false);
      }
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
  // Reads are open GETs; saving issues a PUT that the server authorises from the
  // signed-in session. The shared admin API key this used to carry is gone, and
  // what replaces it is better in one specific way: the credential is a password
  // the operator types into a prompt (never into the DOM, so there is no field to
  // screenshot or screen-share, and never into storage), and the thing that
  // persists — the session — is kept by the browser in an HttpOnly cookie that no
  // script can read. The token is never a JavaScript value, so a compromise of
  // this page yields nothing to replay.
  const cfg = {
    workflow: $("cfg-workflow"),
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
  // The signed-in account's name, kept only to label the status line. It is not a
  // credential and not proof of anything: every save re-proves the session, so a
  // stale name here costs a refused PUT, never an unauthorised one.
  let signedInAs = "";

  function cfgStatus(msg, ok) {
    cfg.status.textContent = msg || "";
    cfg.status.className = "cfg-status " + (msg ? (ok ? "ok" : "bad") : "");
  }

  // Sign in through prompts, for the same reason the page never had a key field:
  // a value typed into a dialog is not in the markup, not readable back, and not
  // in a screenshot of the form. Cancelling, an empty answer and an unavailable
  // prompt (some embedded webviews block dialogs) all fail closed — nothing is
  // sent, so nothing is granted.
  async function signIn() {
    let username = "";
    let password = "";
    try {
      username = (window.prompt("Supervisor username:", "supervisor") || "").trim();
      if (username) password = window.prompt(`Password for ${username}:`) || "";
    } catch { username = ""; password = ""; }
    if (!username || !password) {
      cfgStatus("Not signed in: a username and a password are both needed.", false);
      return false;
    }
    try {
      const me = await api("/auth/login", {
        method: "POST",
        body: JSON.stringify({ username, password })
      });
      signedInAs = me.username || username;
      if (me.mustChangePassword) {
        let newPw = "";
        try {
          newPw = window.prompt(
            `Initial sign-in for ${signedInAs} requires setting a new password (min 12 chars, different from current):\n\nEnter new password:`,
            "supervisor1234"
          ) || "";
        } catch {}
        if (newPw && newPw.length >= 12 && newPw !== password) {
          try {
            await api("/auth/change-password", {
              method: "POST",
              body: JSON.stringify({ currentPassword: password, newPassword: newPw })
            });
            cfgStatus(`Signed in as ${signedInAs} (${me.role}). Password updated.`, true);
            log(`Signed in as ${signedInAs} (${me.role}) and updated password.`);
          } catch (pe) {
            signedInAs = "";
            cfgStatus(`Sign-in refused: password change failed (${pe.message}).`, false);
            await signOut("failed password change");
            updateRoleDisplay();
            return false;
          }
        } else {
          signedInAs = "";
          cfgStatus("Sign-in cancelled: a new password (min 12 chars, different from current) is required.", false);
          await signOut("cancelled password change");
          updateRoleDisplay();
          return false;
        }
      } else {
        cfgStatus(`Signed in as ${signedInAs} (${me.role}).`, true);
        log(`Signed in as ${signedInAs} (${me.role}).`);
      }
      updateRoleDisplay();
      return true;
    } catch (e) {
      signedInAs = "";
      cfgStatus(`Sign-in refused (${e.message}).`, false);
      updateRoleDisplay();
      return false;
    }
  }

  // Ends the session server-side and forgets the label. Best-effort: a session the
  // service has already dropped needs no second goodbye, and the cookie is cleared
  // by the response either way.
  async function signOut(why) {
    const was = signedInAs;
    signedInAs = "";
    currentRole = null;
    try {
      await api("/auth/logout", { method: "POST" });
    } catch { /* nothing to end, or nothing reachable — the label is cleared regardless */ }
    if (was) log(`Signed out ${was} (${why}).`);
    showLoginPage();
    return was;
  }

  $("btn-sign-in").addEventListener("click", () => {
    showLoginPage();
  });

  $("btn-sign-out").addEventListener("click", () => {
    signOut("topbar sign-out button").then(was => cfgStatus(
      was ? `Signed out ${was}.` : "Not signed in.", true));
  });

  $("btn-cfg-sign-out").addEventListener("click", () => {
    signOut("sign-out button").then(was => cfgStatus(
      was ? `Signed out ${was} — the next save asks for a password again.`
          : "Not signed in.", true));
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
    syncWorkflowSegmented();
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
    if (!discard) {
      cfg.workflow.value = loadedWorkflow;
      syncWorkflowSegmented();
    }
    return discard;
  }

  CFG_FIELDS.forEach(el => {
    const onEdit = () => {
      if (currentRole === "OPERATOR") {
        cfgStatus("You do not have authority to change policy. Edits cannot be saved by an operator.", false);
      }
      refreshDirtyMark();
      refreshDiff();
    };
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
    if (currentRole === "OPERATOR") {
      const msg = "You do not have authority to change policy. Only a supervisor has permission to modify configuration.";
      cfgStatus(msg, false);
      try {
        window.alert("Authority Denied: You do not have authority to change policy. Only a supervisor can make changes.");
      } catch {}
      log("Operator attempted to save policy: Refused (Insufficient authority).");
      return;
    }
    // Sign in only when this page has not done so yet. Cancelling the prompt aborts
    // the save rather than sending a PUT the service would refuse.
    if (!signedInAs && !(await signIn())) return;
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
    try {
      await api(`/config/${wf}`, { method: "PUT", body: JSON.stringify(body) });
    } catch (e) {
      // A refused PUT means the session is gone, expired or not a supervisor:
      // forget the label so the next save signs in again instead of retrying with
      // an authority the service has already rejected. 403 is a real account that
      // simply may not write policy — signing in again would not change that, so
      // it says so instead of asking for a password.
      if (/UNAUTHORIZED/.test(e.message)) {
        signedInAs = "";
        cfgStatus(`Not saved: the session was refused (${e.message}). Save again to sign in.`,
          false);
        return;
      }
      if (/FORBIDDEN/.test(e.message)) {
        const msg = `Not saved: this account may not write policy. You do not have authority to change policy. (${e.message})`;
        cfgStatus(msg, false);
        try {
          window.alert("Authority Denied: You do not have authority to change policy.");
        } catch {}
        return;
      }
      throw e;
    }
    // markClean first: it clears the dirty marker, which writes to the status line.
    markClean(wf);
    cfgStatus(`Saved ${wf} policy.`, true);
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
  const WF_BTNS = [
    { el: $("btn-wf-resident"), wf: "RESIDENT" },
    { el: $("btn-wf-operator"), wf: "OPERATOR" },
    { el: $("btn-wf-supervisor"), wf: "SUPERVISOR" }
  ];

  function syncWorkflowSegmented() {
    const current = (cfg && cfg.workflow) ? cfg.workflow.value : "RESIDENT";
    WF_BTNS.forEach(b => {
      if (!b.el) return;
      if (b.wf === current) {
        b.el.classList.add("active");
      } else {
        b.el.classList.remove("active");
      }
    });
  }

  WF_BTNS.forEach(b => {
    if (!b.el) return;
    b.el.addEventListener("click", () => {
      if (!cfg.workflow || cfg.workflow.value === b.wf) return;
      cfg.workflow.value = b.wf;
      cfg.workflow.dispatchEvent(new Event("change"));
      syncWorkflowSegmented();
    });
  });

  cfg.workflow.addEventListener("change", () => {
    if (!confirmDiscard(cfg.workflow.value)) return;
    loadConfig().catch(e => cfgStatus(e.message, false));
    syncWorkflowSegmented();
  });
  // Closing or reloading the tab loses the same edits — and, mid-check, the
  // in-flight session with them: the run is abandoned part-pipeline and the
  // verdict never arrives. The browser only offers its generic prompt here, but
  // that is enough to stop a stray refresh during capture.
  window.addEventListener("beforeunload", e => {
    if (!isDirty() && !running && !resident.running) return;
    e.preventDefault();
    e.returnValue = "";
  });

  // ---------- policy change history ----------
  //
  // Server-side audit of every config PUT: which workflow, which fields moved,
  // old → new, and which account made the change. Open GET, like the rest of the
  // config reads. Flattened to a line per change (the panel is a <pre>, so no HTML
  // is built from server data).
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
      const sideBadge = $("sidebar-chain-badge");
      const sideCount = $("sidebar-chain-count");
      const sideHmac = $("sidebar-chain-hmac");
      const sideTime = $("sidebar-chain-time");
      if (sideBadge) {
        if (v.intact) {
          sideBadge.className = "pill-badge pill-success";
          sideBadge.innerHTML = '<span class="pill-dot"></span>Intact';
        } else {
          sideBadge.className = "pill-badge pill-danger";
          sideBadge.innerHTML = '<span class="pill-dot"></span>Broken';
        }
      }
      if (sideCount && v.chainedEntries !== undefined) sideCount.textContent = String(v.chainedEntries);
      if (sideHmac && v.hmac !== undefined) sideHmac.textContent = v.hmac ? "Yes" : "No";
      if (sideTime) sideTime.textContent = "Just now";

      pre.textContent = lines.join("\n");
    } catch (e) {
      $("pill-chain").textContent = "chain: unreachable";
      $("pill-chain").className = "pill bad";
      $("chain-details").textContent = "Failed to verify the chain: " + e.message;
    }
  }

  $("btn-chain-refresh").addEventListener("click", () => refreshAuditVerify());
  const btnSidebarVerify = $("btn-sidebar-verify-chain");
  if (btnSidebarVerify) {
    btnSidebarVerify.addEventListener("click", () => {
      $("btn-chain-refresh").click();
    });
  }

  // ---------- supervisor tabs ----------
  //
  // The Supervisor container is the Operator surface (Verify, Sessions, Audit,
  // Rejection reasons) plus the admin views (Policy, Chain, Metrics, Sweep,
  // Diagnostics). One panel is on screen at a time and Verify is the entry tab,
  // so entering the role lands on the capture screen.
  const TABS = ["verify", "sessions", "audit", "rejections", "policy", "chain",
                "metrics", "sweep", "diagnostics"];

  function showTab(name) {
    TABS.forEach(t => {
      $("panel-" + t).hidden = t !== name;
      $("tab-" + t).className = "tab" + (t === name ? " active" : "");
    });
  }
  TABS.forEach(t => $("tab-" + t).addEventListener("click", () => showTab(t)));

  // ---------- sessions this browser tab created ----------
  //
  // There is no session-list endpoint: the service exposes POST /sessions,
  // GET /sessions/{id} and POST /sessions/{id}/close, and nothing that enumerates
  // sessions. So this is a local registry of ids this tab created, not a server
  // listing — it dies with the tab, and a session created elsewhere is invisible
  // here. Opening a row re-reads that session and its trail with the two GETs
  // that do exist; no endpoint is invented to fill the gap.
  const tabSessions = [];        // { id, workflow, device, view, at }
  let selectedSession = null;    // what the Audit and Rejection panels describe

  function rememberSession(session, view) {
    if (!session || !session.id) return;
    if (!tabSessions.some(s => s.id === session.id)) {
      tabSessions.push({
        id: session.id,
        workflow: session.workflowType || "—",
        device: session.deviceId || "—",
        view,
        at: new Date().toLocaleTimeString()
      });
    }
    renderSessionList();
  }

  const SAMPLE_SESSIONS = [
    { id: "7c1e92f1-0000-0000-0000-000000000b92", displayId: "7c1e...b92", workflow: "RESIDENT", status: "PASSED", retries: 0, reason: "—", at: "2026-10-13 10:42" },
    { id: "a04d3fe2-0000-0000-0000-0000000003fe", displayId: "a04d...3fe", workflow: "OPERATOR", status: "PASSED", retries: 1, reason: "—", at: "2026-10-13 10:31" },
    { id: "e9b2c073-0000-0000-0000-000000000c07", displayId: "e9b2...c07", workflow: "RESIDENT", status: "FAILED", retries: 3, reason: "max_retries_exceeded", at: "2026-10-13 10:18" },
    { id: "51afd684-0000-0000-0000-000000000d68", displayId: "51af...d68", workflow: "SUPERVISOR", status: "ACTIVE", retries: 0, reason: "Challenge in progress", at: "2026-10-13 10:12" },
    { id: "0b73a1c5-0000-0000-0000-000000000a1c", displayId: "0b73...a1c", workflow: "RESIDENT", status: "FAILED", retries: 0, reason: "presentation_attack", at: "2026-10-13 09:57" },
    { id: "c8d6e456-0000-0000-0000-000000000e45", displayId: "c8d6...e45", workflow: "OPERATOR", status: "EXPIRED", retries: 0, reason: "session_closed", at: "2026-10-13 09:40" },
  ];

  function renderSessionList() {
    const list = $("sessions-list");
    const tbody = $("sessions-table-body");
    const wfFilter = $("filter-workflow") ? $("filter-workflow").value : "";
    const stFilter = $("filter-status") ? $("filter-status").value : "";

    if (list) {
      list.textContent = "";
      if ($("sessions-count")) {
        $("sessions-count").textContent = tabSessions.length
          ? `${tabSessions.length} session${tabSessions.length > 1 ? "s" : ""} created in this tab`
          : "no sessions yet";
      }
      if (!tabSessions.length) {
        list.textContent = "No session has been created in this browser tab yet — run a "
          + "check in Verify, or a resident check in the Resident role.";
      } else {
        for (const s of tabSessions) {
          const row = document.createElement("div");
          row.className = "session-row" + (s.id === selectedSession ? " selected" : "");
          const label = document.createElement("span");
          label.className = "session-label";
          label.textContent = `${s.id}  ${s.workflow}  ${s.view}  ${s.at}`;
          const open = document.createElement("button");
          open.textContent = "Open";
          open.addEventListener("click", () => { openSession(s.id).catch(e => log("open error: " + e.message)); });
          row.append(label, open);
          list.append(row);
        }
      }
    }

    if (!tbody) return;
    tbody.innerHTML = "";

    const activeList = tabSessions.length > 0
      ? tabSessions.map(s => ({
          id: s.id,
          displayId: s.id.length > 12 ? s.id.slice(0, 4) + "..." + s.id.slice(-3) : s.id,
          workflow: s.workflow || "RESIDENT",
          status: s.status || "PASSED",
          retries: s.retries || 0,
          reason: s.reason || "—",
          at: s.at || new Date().toLocaleString()
        }))
      : SAMPLE_SESSIONS;

    const filtered = activeList.filter(s => {
      if (wfFilter && s.workflow !== wfFilter) return false;
      if (stFilter && s.status !== stFilter) return false;
      return true;
    });

    for (const s of filtered) {
      const tr = document.createElement("tr");
      if (s.id === selectedSession) tr.className = "selected";

      let statusPillClass = "pill-neutral";
      if (s.status === "PASSED") statusPillClass = "pill-success";
      else if (s.status === "FAILED") statusPillClass = "pill-danger";
      else if (s.status === "ACTIVE") statusPillClass = "pill-warning";

      const tdSession = document.createElement("td");
      const sSpan = document.createElement("span");
      sSpan.className = "session-mono-id";
      sSpan.textContent = s.displayId;
      tdSession.appendChild(sSpan);

      const tdWf = document.createElement("td");
      tdWf.textContent = s.workflow;

      const tdStatus = document.createElement("td");
      const stSpan = document.createElement("span");
      stSpan.className = "pill-badge " + statusPillClass;
      const dot = document.createElement("span");
      dot.className = "pill-dot";
      const txt = document.createElement("span");
      txt.textContent = s.status;
      stSpan.append(dot, txt);
      tdStatus.appendChild(stSpan);

      const tdRetries = document.createElement("td");
      tdRetries.textContent = String(s.retries !== undefined ? s.retries : 0);

      const tdReason = document.createElement("td");
      tdReason.textContent = s.reason || "—";

      const tdCreated = document.createElement("td");
      tdCreated.textContent = s.at || "—";

      const tdAction = document.createElement("td");
      const openBtn = document.createElement("button");
      openBtn.type = "button";
      openBtn.className = "secondary";
      openBtn.textContent = "Open";
      openBtn.addEventListener("click", () => {
        openSession(s.id).catch(e => log("open error: " + e.message));
      });
      tdAction.appendChild(openBtn);

      tr.append(tdSession, tdWf, tdStatus, tdRetries, tdReason, tdCreated, tdAction);
      tbody.appendChild(tr);
    }
  }

  const filterWf = $("filter-workflow");
  const filterSt = $("filter-status");
  if (filterWf) filterWf.addEventListener("change", renderSessionList);
  if (filterSt) filterSt.addEventListener("change", renderSessionList);

  function renderSessionDetail(d) {
    kv($("session-detail"), {
      id: d.id,
      workflowType: d.workflowType,
      deviceId: d.deviceId,
      status: d.status,
      currentStage: d.currentStage,
      retryCount: d.retryCount,
      finalResult: d.finalResult === null || d.finalResult === undefined ? "—" : d.finalResult,
      failureReason: d.failureReason || "(none recorded)",
      online: d.online === undefined ? "—" : d.online,
      createdAt: d.createdAt,
      updatedAt: d.updatedAt
    });
  }

  // One click, the reads that exist: GET /sessions/{id} and GET /sessions/{id}/audit.
  // The audit trail then feeds the rejection panel too, so the three panels are
  // describing the same rows rather than three separate fetches that could drift.
  async function openSession(id) {
    selectedSession = id;
    renderSessionList();
    const [detail, entries] = await Promise.all([
      api(`/sessions/${id}`),
      api(`/sessions/${id}/audit`)
    ]);
    renderSessionDetail(detail);
    renderAudit(id, entries);
    renderRejections(detail, entries);
    log(`session opened: ${id} status=${detail.status}`);
  }

  $("btn-sessions").addEventListener("click", () => {
    renderSessionList();
    if (selectedSession) openSession(selectedSession).catch(e => log("open error: " + e.message));
  });

  // ---------- rejection reasons ----------
  //
  // Two read-only sources, named by the design's failure table: the session's own
  // failureReason (GET /sessions/{id}) and the rejection events in its audit trail
  // (GET /sessions/{id}/audit) — PAD_REJECTED carries the attack type, confidence
  // and stage, CHALLENGE_FAILED the challenge that was missed. Nothing is inferred:
  // a session with neither shows nothing rather than a guess.
  function renderRejections(detail, entries) {
    const rejections = (entries || []).filter(e =>
      e.eventType === "PAD_REJECTED" || e.eventType === "CHALLENGE_FAILED");
    const pad = rejections.filter(e => e.eventType === "PAD_REJECTED");
    const id = (detail && detail.id) || auditTarget();
    $("rejections-subject").textContent = id
      ? "session " + id.slice(0, 8) + (selectedSession === id ? " (selected in Sessions)" : " (current)")
      : "no session selected";
    kv($("rejections-summary"), {
      status: (detail && detail.status) || "—",
      failureReason: (detail && detail.failureReason) || "(none recorded)",
      padRejections: pad.length,
      challengeFailures: rejections.length - pad.length
    });
    $("rejections-events").textContent = rejections.length
      ? rejections.map(e => `${e.createdAt}  ${e.eventType}  ${JSON.stringify(e.details)}`).join("\n")
      : "No PAD_REJECTED or CHALLENGE_FAILED event in this session's audit trail.";
  }

  async function refreshRejections() {
    const id = auditTarget();
    if (!id) {
      $("rejections-subject").textContent = "no session selected";
      kv($("rejections-summary"), { session: "none selected" });
      $("rejections-events").textContent = "No session selected — open one in the Sessions tab.";
      return;
    }
    try {
      const [detail, entries] = await Promise.all([
        api(`/sessions/${id}`),
        api(`/sessions/${id}/audit`)
      ]);
      renderRejections(detail, entries);
    } catch (e) {
      log("rejection reasons error: " + e.message);
      $("rejections-events").textContent = "Could not read the session: " + e.message;
    }
  }
  $("btn-rejections").addEventListener("click", () => { refreshRejections(); });

  // ---------- threshold sweep ----------
  //
  // GET /api/v1/eval/threshold-sweep is an open read (no admin key): it sweeps the
  // passive threshold over the same median-window decision the live path uses and
  // recommends an operating point for the target BPCER. Applying a value is a
  // policy write and stays in the Policy tab.
  async function refreshSweep() {
    const target = Number($("sweep-target").value);
    try {
      const r = await api(`/eval/threshold-sweep?targetBpcer=${encodeURIComponent(target)}`);
      kv($("sweep-summary"), {
        targetBpcer: r.targetBpcer,
        scorer: r.scorer || "—",
        window: `${r.minFrames} min / ${r.windowFrames} window frames`,
        recommendedThreshold: r.recommendedThreshold === null || r.recommendedThreshold === undefined
          ? "none recommended" : fmt(r.recommendedThreshold),
        basis: r.recommendationBasis || "—",
        attackDataIsProxy: r.attackDataIsProxy,
        excludedWindows: r.excludedWindows,
        note: r.note || "—"
      });
      const rows = r.rows || [];
      $("sweep-rows").textContent = rows.length
        ? rows.map(x => `${fmt(x.threshold)}  bona-fide ${x.bonaFidePassed}/${x.bonaFideWindows}`
            + `  bpcer=${fmt(x.bpcer)}  attacks=${x.attackWindows}`
            + `  apcer=${fmt(x.apcer)}  acer=${fmt(x.acer)}`).join("\n")
        : "The sweep returned no rows — no scored frames have been recorded yet.";
      log(`sweep: targetBpcer=${r.targetBpcer} recommended=`
        + `${r.recommendedThreshold === null || r.recommendedThreshold === undefined ? "none" : r.recommendedThreshold}`);
    } catch (e) {
      if (/FORBIDDEN/.test(e.message)) {
        $("sweep-rows").textContent = "Authority Denied: You do not have authority to run threshold sweeps. Only a supervisor has permission.";
      } else {
        log("sweep error: " + e.message);
        $("sweep-rows").textContent = "Sweep failed: " + e.message;
      }
    }
  }
  $("btn-sweep").addEventListener("click", () => { refreshSweep(); });

  // The console starts with role selection login page:
  // supervisor tabs and resident capture are hidden until sign-in authenticates a role.
  showTab("verify");
  renderSessionList();
  refreshRejections();

  refreshHealth();
  refreshMetrics();
  refreshAuditVerify();
  // First policy load is a plain open GET — signing in is only needed to write.
  loadConfig().catch(e => cfgStatus(e.message, false));
  // The change history loads with the page too, so the account behind the most
  // recent edit — and its risk level — are visible at a glance, not only after
  // clicking refresh. Same open GET as the policy read; no session needed.
  loadConfigAudit().catch(e => cfgStatus(e.message, false));

  // Initially show only the login page for role selection
  showLoginPage();

  // Check existing session if available
  api("/auth/me").then(me => {
    if (me && me.username) {
      if (me.mustChangePassword) {
        // Confined session would block public requests with 403; clear it so console operates normally
        signOut("uncompleted password change");
      } else if (inputSupervisorUser && me.role === "SUPERVISOR") {
        inputSupervisorUser.value = me.username;
      } else if (inputOperatorUser && me.role === "OPERATOR") {
        inputOperatorUser.value = me.username;
      }
    }
  }).catch(() => {});
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
