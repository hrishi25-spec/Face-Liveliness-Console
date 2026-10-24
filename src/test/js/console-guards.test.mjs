#!/usr/bin/env node
/* Console guard tests — a lightweight DOM harness for the browser code.
 *
 * The Java suites cannot see src/main/resources/static at all: a green
 * ./mvnw test says nothing about whether the console's guards are still
 * wired after a refactor. This loads the real index.html and executes the
 * real console.js against a minimal DOM (no npm dependencies, so CI needs
 * nothing but a node binary), then drives it the way an operator would:
 *
 *   1. the dirty marker appears on an edit and clears on revert,
 *   2. the per-field diff lists old → new for every pending change,
 *   3. switching workflows with pending edits asks first and aborts on "no",
 *   4. the Discard-edits button reloads the policy and drops the edits,
 *   5. beforeunload warns for a dirty form and for an in-flight session,
 *   6. the change history flags edits that weaken liveness (risk level
 *      and the named reason, per entry),
 *   7. the change history loads with the page, so the key fingerprint
 *      behind the most recent edit is visible at a glance,
 *   8. the manual extra-check button stays disabled until the session passes,
 *      then asks the server for one active challenge and reports that check's
 *      own verdict (never a rewritten session verdict),
 *   9. the Sessions table never invents a column: placeholders are tagged and
 *      unopenable, and a real row's Status/Retries/Reason are read back from
 *      GET /sessions/{id} — never defaulted to PASSED, 0 and "—".
 *
 * Run: node src/test/js/console-guards.test.mjs   (exit 0 = all assertions pass)
 *
 * Element lookup is by id, so removing an id from index.html (or the wiring
 * from console.js) throws during load or fails an assertion below — the test
 * cannot silently pass on a console that no longer has these controls.
 */
import { readFileSync } from "node:fs";
import path from "node:path";
import vm from "node:vm";
import { fileURLToPath } from "node:url";

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../../..");
const STATIC = path.join(ROOT, "src/main/resources/static");
const html = readFileSync(path.join(STATIC, "index.html"), "utf8");
const consoleJs = readFileSync(path.join(STATIC, "console.js"), "utf8");

// ---------- minimal DOM ----------

class EventTargetLike {
  constructor() {
    this._listeners = new Map();
  }
  addEventListener(type, fn) {
    if (!this._listeners.has(type)) this._listeners.set(type, []);
    this._listeners.get(type).push(fn);
  }
  removeEventListener(type, fn) {
    const list = this._listeners.get(type) || [];
    const i = list.indexOf(fn);
    if (i >= 0) list.splice(i, 1);
  }
  dispatch(type, init = {}) {
    const event = {
      type,
      defaultPrevented: false,
      preventDefault() { event.defaultPrevented = true; },
      ...init
    };
    for (const fn of [...(this._listeners.get(type) || [])]) fn(event);
    return event;
  }
}

class ClassList {
  constructor(el) { this.el = el; }
  _read() { return new Set(this.el.className.split(/\s+/).filter(Boolean)); }
  _write(set) { this.el.className = [...set].join(" "); }
  add(...names) { const s = this._read(); names.forEach(n => s.add(n)); this._write(s); }
  remove(...names) { const s = this._read(); names.forEach(n => s.delete(n)); this._write(s); }
  toggle(name, force) {
    const s = this._read();
    const want = force === undefined ? !s.has(name) : Boolean(force);
    want ? s.add(name) : s.delete(name);
    this._write(s);
    return want;
  }
  contains(name) { return this._read().has(name); }
}

class Element extends EventTargetLike {
  constructor(tagName) {
    super();
    this.tagName = tagName.toUpperCase();
    this.id = "";
    this.attrs = {};
    this.children = [];
    this._text = "";
    this.className = "";
    this.classList = new ClassList(this);
    this.style = {};
    this.hidden = false;
    this.disabled = false;
    this.checked = false;
    this._value = "";
    this.type = undefined;
    this.scrollTop = 0;
  }
  // A real input/select always exposes its value as a string; coerce the same
  // way so fingerprint and diff comparisons behave as they do in a browser.
  get value() { return this._value; }
  set value(v) {
    const formControl = this.tagName === "INPUT" || this.tagName === "SELECT"
        || this.tagName === "TEXTAREA";
    this._value = formControl ? String(v) : v;
  }
  get textContent() {
    return this._text + this.children.map(c => c.textContent || "").join("");
  }
  set textContent(v) { this.children = []; this._text = String(v); }
  get innerHTML() { return this._text; }
  set innerHTML(v) { this.children = []; this._text = String(v); }
  get scrollHeight() { return this._text.length; }
  setAttribute(name, value) {
    this.attrs[name] = value;
    if (name === "id") this.id = value;
    if (name === "type") this.type = value;
    if (name === "class") this.className = value;
  }
  getAttribute(name) { return name in this.attrs ? this.attrs[name] : null; }
  append(...nodes) { for (const n of nodes) this.children.push(n); }
  appendChild(node) { this.children.push(node); return node; }
  focus() {}
  // The canvas API console.js touches, recording what the capture path asked
  // for so the guards can assert the capture budget without a real encoder:
  // `lastDraw` is the drawImage argument list, `lastEncode` the size and
  // quality the frame was encoded at.
  getContext() { return { drawImage: (...args) => { this.lastDraw = args; } }; }
  toDataURL(type, quality) {
    this.lastEncode = { type, quality, width: this.width, height: this.height };
    this.encodes = (this.encodes || 0) + 1;
    return "data:image/jpeg;base64,STUB";
  }
}

// Build an element per id="…" in the real markup; selects get the value a
// browser would give them (first option), which is what the guards compare
// against as the "loaded workflow".
function buildDom(source) {
  const byId = new Map();
  const tagRe = /<([A-Za-z][A-Za-z0-9-]*)((?:"[^"]*"|'[^']*'|[^>"'])*)>/g;
  let m;
  while ((m = tagRe.exec(source))) {
    const [, tag, rawAttrs] = m;
    const attrs = {};
    const attrRe = /([A-Za-z-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')/g;
    let a;
    while ((a = attrRe.exec(rawAttrs))) attrs[a[1]] = a[2] ?? a[3];
    if (!attrs.id) continue;
    const el = new Element(tag);
    el.id = attrs.id;
    if (tag === "input") el.type = attrs.type || "text";
    // Honour the markup's own disabled attribute, as a browser would: the run,
    // stop and extra-check buttons all start disabled until the camera/session
    // makes them usable, and an assertion on that starting state is only
    // meaningful if the DOM reflects it. `disabled` is a bare boolean
    // attribute in the markup, so the attribute regex above never sees it.
    if (attrs.disabled !== undefined || /(^|\s)disabled(\s|=|$)/.test(rawAttrs)) {
      el.disabled = true;
    }
    // Same for the bare `hidden` attribute: it is how the markup starts a dialog
    // or a panel closed, and an assertion about that starting state is only
    // meaningful if the DOM reflects it.
    if (attrs.hidden !== undefined || /(^|\s)hidden(\s|=|$)/.test(rawAttrs)) {
      el.hidden = true;
    }
    for (const [k, v] of Object.entries(attrs)) el.attrs[k] = v;
    byId.set(attrs.id, el);
  }
  const selectRe = /<select[^>]*\bid="([^"]+)"[^>]*>([\s\S]*?)<\/select>/g;
  for (const sm of source.matchAll(selectRe)) {
    const el = byId.get(sm[1]);
    const first = sm[2].match(/<option[^>]*\bvalue="([^"]*)"/);
    if (el && first) el.value = first[1];
  }
  return byId;
}

const byId = buildDom(html);
const $id = id => {
  const el = byId.get(id);
  if (!el) throw new Error(`index.html has no element #${id} — the console wiring is incomplete`);
  return el;
};

const createdElements = [];
const document = {
  hidden: false,
  getElementById: id => byId.get(id) || null,
  createElement: tag => {
    const el = new Element(tag);
    createdElements.push(el);
    return el;
  },
  // console.js listens for visibilitychange to pause capture; a bare object
  // here would throw while the page is loading.
  _listeners: new Map(),
  addEventListener(type, fn) {
    if (!this._listeners.has(type)) this._listeners.set(type, []);
    this._listeners.get(type).push(fn);
  },
  dispatch(type) { for (const fn of [...(this._listeners.get(type) || [])]) fn({ type }); }
};
// The one canvas console.js allocates, reused for every frame.
const captureCanvas = () => {
  const el = createdElements.find(e => e.tagName === "CANVAS");
  if (!el) throw new Error("console.js no longer creates its capture canvas");
  return el;
};

// ---------- fetch / storage stubs ----------

const POLICY = {
  passiveThreshold: 0.8,
  challengeTimeoutMs: 20000,
  minChallengeCount: 1,
  maxRetryCount: 3,
  onRepeatedFailure: "ESCALATE",
  livenessEnabled: true,
  activeLivenessEnabled: true,
  challengeTypes: ["blink", "smile"],
  updatedAt: "2026-10-05T00:00:00Z"
};

const fetchCalls = [];
let confirmAnswer = false;
let hangFrames = false;   // when set, frame POSTs answer only when released below
// A frame POST left hanging stays in flight until the test lets it go, which is
// the only way to observe "a second capture is dropped, not queued".
const pendingFrameReleases = [];
const releaseHang = () => { const go = pendingFrameReleases.shift(); if (go) go(); };
// What POST /auth/login answers. The role picker and the credential dialog both
// sign in through it, so a test can ask for either an ordinary session or an
// account the service confines to the change-password route until it sets one.
let loginAnswer = { username: "sup-01", role: "SUPERVISOR", mustChangePassword: false };
let loginFailure = false;
let challengeAction = "proceed";   // action a challenge-validation stub answers with
let frameAction = "proceed";       // action a frame-POST stub answers with
// What GET /sessions/{id} reports — the truth the Sessions table has to show.
let sessionDetailStatus = "ACTIVE";
let sessionDetailRetries = 0;
let chainVerify = {
  intact: true,
  hmac: true,
  chainedEntries: 2,
  rotationWindowOpen: false,
  retiredKeyHashes: false,
  headHash: "a".repeat(64)
};

// Sample policy-change history: one weakening edit (HIGH), one benign
// edit (LOW), and one written before the risk classification existed
// (no risk key — the console must not guess a level for it).
const AUDIT = [
  {
    id: "audit-2",
    eventType: "CONFIG_CHANGED",
    workflowType: "RESIDENT",
    createdAt: "2026-10-05T10:02:00Z",
    details: {
      workflowType: "RESIDENT",
      action: "UPDATED",
      actor: "key:9f2c1a7b4e0d",
      sourceIp: "203.0.113.10",
      changes: { passiveThreshold: { from: 0.8, to: 0.4 } },
      risk: { level: "HIGH", reasons: ["passiveThreshold lowered"] }
    }
  },
  {
    id: "audit-1",
    eventType: "CONFIG_CHANGED",
    workflowType: "OPERATOR",
    createdAt: "2026-10-05T10:01:00Z",
    details: {
      workflowType: "OPERATOR",
      action: "UPDATED",
      actor: "key:1a2b3c4d5e6f",
      sourceIp: "unknown",
      changes: { maxRetryCount: { from: 3, to: 1 } },
      risk: { level: "LOW", reasons: [] }
    }
  },
  {
    id: "audit-0",
    eventType: "CONFIG_CHANGED",
    workflowType: "SUPERVISOR",
    createdAt: "2026-10-04T09:00:00Z",
    details: {
      workflowType: "SUPERVISOR",
      action: "CREATED",
      actor: "key:abcdefabcdef",
      sourceIp: "unknown",
      changes: { passiveThreshold: { from: 0.5, to: 0.82 } }
    }
  }
];

async function fetch(url, options = {}) {
  const method = (options.method || "GET").toUpperCase();
  fetchCalls.push({ url, method, headers: options.headers || {}, body: options.body, at: Date.now() });
  const respond = body => ({
    ok: true,
    status: 200,
    headers: { get: () => null },
    text: async () => JSON.stringify(body),
    json: async () => body
  });
  if (url === "/api/v1/auth/login") {
    if (loginFailure) {
      return {
        ok: false,
        status: 401,
        headers: { get: () => null },
        text: async () => JSON.stringify({ error: "UNAUTHORIZED", message: "bad credentials" }),
        json: async () => ({ error: "UNAUTHORIZED", message: "bad credentials" })
      };
    }
    return respond(loginAnswer);
  }
  if (url === "/api/v1/auth/change-password") return respond({ changed: true });
  if (url === "/api/v1/auth/logout") return respond({ signedOut: true });
  if (url === "/api/v1/auth/me") return respond({});
  if (url === "/health") return respond({ status: "UP", engine: "available" });
  if (url === "/api/v1/metrics") return respond({ framesProcessed: 1 });
  if (url === "/api/v1/config/audit/verify") return respond(chainVerify);
  if (url.startsWith("/api/v1/config/audit")) return respond(AUDIT);
  if (url.startsWith("/api/v1/config/")) {
    if (method === "PUT") return respond({ saved: true });
    return respond(POLICY);
  }
  if (url === "/api/v1/sessions" && method === "POST") {
    return respond({ id: "sess-00000000-0000-0000-0000-000000000001",
                     workflowType: JSON.parse(options.body).workflowType,
                     status: "ACTIVE" });
  }
  // GET /sessions/{id}: the only endpoint that reports a session's own status,
  // and the one the Sessions table must read its Status/Retries/Reason columns
  // from. The creation response above deliberately says ACTIVE for every
  // session, so a table that echoed it (or defaulted to PASSED) cannot pass the
  // assertions on the service's answer below.
  const detail = url.match(/^\/api\/v1\/sessions\/([A-Za-z0-9-]+)$/);
  if (detail && method === "GET") {
    return respond({
      id: detail[1],
      workflowType: "RESIDENT",
      status: sessionDetailStatus,
      retryCount: sessionDetailRetries,
      failureReason: sessionDetailStatus === "FAILED" ? "max_retries_exceeded" : "—"
    });
  }
  if (url.includes("/frames")) {
    if (hangFrames) {
      return new Promise(resolve => pendingFrameReleases.push(
        () => resolve(respond({ action: frameAction, message: "stub" }))));
    }
    // `escalate_to_active` has to carry the challenge the client then validates.
    return respond({
      action: frameAction,
      message: "stub",
      challenge: frameAction === "escalate_to_active"
        ? { challengeId: "challenge-1", challengeType: "BLINK", timeoutMs: 15000, attemptNumber: 1 }
        : undefined
    });
  }
  if (url.includes("/active-liveness")) {
    return respond({ id: "challenge-manual-1", challengeType: "BLINK",
                     timeoutMs: 15000, manual: true });
  }
  if (url.includes("/challenges/")) {
    // Mirrors the server: the response always carries the challenge it just
    // resolved, which is why the console's loop has to treat a terminal verdict
    // as terminal instead of retrying that (now FAILED) id. The budget-exhausted
    // actions carry the service's own message and retryability.
    const answers = {
      locked: ["Face verification failed. Please contact an operator for assistance.", false],
      escalate_to_operator: ["Face verification could not be completed. An operator will assist you.", false],
      failed: ["We could not verify face liveness. Please try again.", true],
      manual_check_passed: ["Action detected. Extra check passed — the session result is unchanged.", undefined],
      manual_check_failed: ["Action not detected. Extra check failed — the session result is unchanged.", undefined]
    };
    const [message, mayRetrySession] = answers[challengeAction] || ["stub", undefined];
    return respond({ passed: challengeAction === "manual_check_passed",
                     action: challengeAction,
                     mayRetrySession,
                     message,
                     challenge: { id: "challenge-manual-1", challengeType: "BLINK",
                                  status: "FAILED", timeoutMs: 15000 } });
  }
  if (url.includes("/close")) {
    return respond({ status: "CLOSED", totalFramesEvaluated: 1,
                     totalChallengesIssued: 0, durationMs: 1 });
  }
  if (url.includes("/audit")) return respond([]);
  throw new Error(`unexpected fetch in test: ${method} ${url}`);
}

function makeStorage() {
  const map = new Map();
  return {
    getItem: k => (map.has(k) ? map.get(k) : null),
    setItem: (k, v) => map.set(k, String(v)),
    removeItem: k => map.delete(k)
  };
}

const windowTarget = new EventTargetLike();
windowTarget.confirm = () => confirmAnswer;
// Both camera buttons need getUserMedia to resolve. The stream object is opaque
// to the code under test (it is only handed to a <video>), so a stand-in is
// enough to drive the resident capture screen.
const navigator = { mediaDevices: { getUserMedia: async () => ({ id: "stub-stream" }) } };
const intervals = [];   // recorded, never scheduled: the harness must be able to exit

const sandbox = {
  document,
  navigator,
  window: windowTarget,
  sessionStorage: makeStorage(),
  fetch,
  console,
  setTimeout,
  clearTimeout,
  clearInterval: () => {},
  setInterval: (fn, ms) => { intervals.push({ fn, ms }); return intervals.length; }
};
vm.createContext(sandbox);
vm.runInContext(consoleJs, sandbox, { filename: "console.js" });

// The cameras report 720p unless a test says otherwise. That is the case the
// capture cap exists for: getUserMedia asks for 640x480 as an *ideal* and
// devices are free to ignore it.
for (const [id, w, h] of [["video", 1280, 720], ["res-video", 1280, 720]]) {
  $id(id).videoWidth = w;
  $id(id).videoHeight = h;
}

// Let the fire-and-forget loads at the bottom of console.js settle.
const settle = async (rounds = 25) => {
  for (let i = 0; i < rounds; i++) await new Promise(r => setImmediate(r));
};

// ---------- assertions ----------

let passed = 0;
let failed = 0;
function section(name) { console.log(`\n${name}`); }
function assert(cond, msg) {
  if (cond) { passed++; console.log(`  ok - ${msg}`); }
  else { failed++; console.log(`  FAIL - ${msg}`); }
}
const status = () => $id("cfg-status");
const diff = () => $id("cfg-diff");
const diffRows = () => diff().children.filter(c => c.className === "cfg-diff-row");
const isDirtyShown = () => status().className.includes("dirty");
const edit = (el, value) => {
  if (typeof el.checked === "boolean" && typeof value === "boolean") el.checked = value;
  else el.value = value;
  el.dispatch("input");
  el.dispatch("change");
};
const getFetch = suffix => fetchCalls.filter(c => c.method === "GET" && c.url.endsWith(suffix));
const discardBtn = () => $id("btn-cfg-discard");
const beforeunload = () => windowTarget.dispatch("beforeunload");

await settle();

section("initial load");
assert(status().textContent.includes("Loaded RESIDENT policy"),
    "the RESIDENT policy loads on start");
assert(!isDirtyShown(), "a freshly loaded form is not dirty");
assert(diff().hidden === true, "the diff panel starts hidden");
assert($id("cfg-workflow").value === "RESIDENT", "the workflow select starts on RESIDENT");

section("sign-in role picker");
// The picker is the only entry point for the three roles, so losing one of the
// cards or its sign-in controls has to fail here rather than in a browser
// nobody re-checked after a theme or layout change.
const pickerHtml = html.slice(html.indexOf('id="login-page"'),
    html.indexOf("SCREEN 2: RESIDENT VIEW"));
assert((pickerHtml.match(/class="role-card"/g) || []).length === 3,
    "the sign-in page renders exactly three role cards");
for (const role of ["Resident", "Operator", "Supervisor"]) {
  assert(pickerHtml.includes(`<h3 class="role-title">${role}</h3>`),
      `the ${role} card is on the sign-in page`);
}
for (const id of ["input-resident-id", "btn-login-resident",
                  "input-operator-user", "input-operator-pass", "btn-login-operator",
                  "input-supervisor-user", "input-supervisor-pass", "btn-login-supervisor"]) {
  assert(pickerHtml.includes(`id="${id}"`),
      `the sign-in page keeps #${id}`);
}
assert(/id="btn-theme-toggle"[^>]*data-mode="auto"/.test(html),
    "the theme control starts in auto mode, so the page follows the browser");

// A refused operator login stays on the picker. The console used to fall into
// the operator view anyway, which made a missing demo account look signed in.
loginFailure = true;
$id("input-operator-pass").value = "operator1234";
$id("btn-login-operator").dispatch("click");
await settle();
assert($id("login-page").hidden === false,
    "a refused operator login leaves the role picker on screen");
assert($id("status-login-operator").textContent.includes("Sign in refused"),
    "the operator card reports the refusal instead of pretending success");
assert($id("pill-user").hidden === true,
    "a refused login shows no user in the header");
loginFailure = false;

section("sessions table — placeholders are labelled, never passed off as real");
// Nothing has created a session in this tab yet, and the service exposes no
// session-list endpoint: the table lays the design out with sample rows, and
// every one of them has to say so and be unusable.
const sessionRows = () => $id("sessions-table-body").children;
const openButtons = () => sessionRows().map(tr => tr.children[6].children[0]);
const sampleTags = () => sessionRows().map(tr => tr.children[0].children
    .filter(c => c.className === "sample-tag"));
assert(sessionRows().length === 6,
    `the table renders the design's row count before any real session (got ${sessionRows().length})`);
assert(sessionRows().every(tr => tr.className.includes("is-sample")),
    "every placeholder row is styled as sample data");
assert(sampleTags().every(tags => tags.length === 1 && tags[0].textContent === "sample"),
    "each placeholder carries a SAMPLE tag in its Session cell");
assert(openButtons().every(btn => btn.disabled === true),
    "a placeholder row's Open action is disabled — there is nothing to open");
assert($id("sessions-note").hidden === false
    && $id("sessions-note").textContent.includes("Sample rows"),
    "the table says why the rows are placeholders");

section("config audit chain badge");
const chainPill = () => $id("pill-chain");
const chainDetails = () => $id("chain-details").textContent;
assert(getFetch("/api/v1/config/audit/verify").length === 1,
    "the console loads chain verification on startup");
assert(chainPill().textContent === "chain: intact" && chainPill().className.includes("ok"),
    "an intact chain gets a green header badge");
assert(chainDetails().includes("rotation:   closed"),
    "the details panel shows a closed rotation window");

chainVerify = { ...chainVerify, rotationWindowOpen: true };
$id("btn-chain-refresh").dispatch("click");
await settle();
assert(chainPill().textContent.includes("rotation window open")
    && chainPill().className.includes("warn"),
    "an open rotation window turns the badge amber");
assert(chainDetails().includes("window OPEN — no entries need the retired key"),
    "the details distinguish an unused previous key from a closed window");

chainVerify = { ...chainVerify, retiredKeyHashes: true };
$id("btn-chain-refresh").dispatch("click");
await settle();
assert(chainDetails().includes("window OPEN — entries still on the retired key"),
    "the details explain that entries still need the retired key");

chainVerify = { ...chainVerify, intact: false, retiredKeyHashes: false, breakIndex: 0,
  breakEntryId: "audit-1", storedHash: "b".repeat(64), recomputedHash: "c".repeat(64),
  storedPrevHash: "GENESIS", expectedPrevHash: "GENESIS", contentChanged: true };
$id("btn-chain-refresh").dispatch("click");
await settle();
assert(chainPill().textContent === "chain: BROKEN" && chainPill().className.includes("bad"),
    "a broken chain gets a red header badge");
assert(chainDetails().includes("break at index 0") && chainDetails().includes("content changed"),
    "the details panel identifies the first broken entry");
assert(chainDetails().includes("historical use is unknown while the chain is broken"),
    "a broken chain does not claim that the previous key is unused");

section("change history loads with the page (item 24)");
// No click on #btn-cfg-audit has happened yet: the fingerprint of the
// key behind the newest edit must already be rendered, next to the
// entry it belongs to, so an operator confirms at a glance which key
// made the most recent change.
const auditPanel = () => $id("cfg-audit").textContent;
assert(auditPanel().includes("RESIDENT  UPDATED  by key:9f2c1a7b4e0d"),
    "the newest edit shows which key made it, without clicking refresh");
assert(auditPanel().includes("[risk: HIGH]"),
    "the newest edit's risk level is visible at a glance too");
assert(auditPanel().includes("key:1a2b3c4d5e6f") && auditPanel().includes("key:abcdefabcdef"),
    "every entry in the page carries its own key fingerprint");

section("per-field diff (item 1)");
edit($id("cfg-threshold"), "0.55");
assert(isDirtyShown(), "editing a field turns the dirty marker on");
assert(diff().hidden === false, "the diff panel appears with a pending edit");
assert(diffRows().length === 1, "one changed field shows one diff row");
assert(diff().textContent.includes("passiveThreshold"), "the row names the payload field");
assert(diff().textContent.includes("0.8") && diff().textContent.includes("0.55"),
    "the row shows loaded → pending values");
assert(diff().textContent.includes("1 field differs from the loaded RESIDENT policy"),
    "the diff title is grammatical for a single field");

edit($id("cfg-ch-left"), true);
assert(diffRows().length === 2, "a second changed field adds a second row");
assert(diff().textContent.includes("2 fields differ from the loaded RESIDENT policy"),
    "the diff title pluralises when several fields differ");
assert(diff().textContent.includes("challengeTypes"),
    "the four challenge checkboxes diff as their one payload field");
assert(diff().textContent.includes("blink, smile, turn_left"),
    "the pending challenge list is shown");

edit($id("cfg-retries"), "5");
assert(diffRows().length === 3, "a third changed field adds a third row");
assert(diff().textContent.includes("maxRetryCount"),
    "the retries field is listed under its payload name");

section("reverting by hand clears the marker and the diff");
edit($id("cfg-threshold"), "0.8");
edit($id("cfg-ch-left"), false);
edit($id("cfg-retries"), "3");
assert(!isDirtyShown(), "reverting every field turns the dirty marker off");
assert(diff().hidden === true, "the diff panel hides again when nothing differs");

section("workflow switch guard");
edit($id("cfg-threshold"), "0.7");
confirmAnswer = false;
$id("cfg-workflow").value = "OPERATOR";
$id("cfg-workflow").dispatch("change");
assert($id("cfg-workflow").value === "RESIDENT",
    "answering 'no' puts the workflow select back on the loaded policy");
assert(isDirtyShown(), "answering 'no' keeps the pending edits");
assert(diff().hidden === false, "the diff still shows what would be lost");

confirmAnswer = true;
$id("cfg-workflow").value = "OPERATOR";
$id("cfg-workflow").dispatch("change");
await settle();
assert($id("cfg-workflow").value === "OPERATOR", "answering 'yes' switches workflows");
assert(getFetch("/api/v1/config/OPERATOR").length > 0, "the new workflow's policy is fetched");
assert(status().textContent.includes("Loaded OPERATOR policy"), "the form reloads");
assert(!isDirtyShown() && diff().hidden === true, "a reload clears the dirty marker and diff");

section("discard-and-reload button (item 2)");
edit($id("cfg-threshold"), "0.66");
assert(isDirtyShown() && diff().hidden === false, "the form is dirty before discarding");
const fetchesBefore = fetchCalls.length;
discardBtn().dispatch("click");
await settle();
assert(fetchCalls.slice(fetchesBefore).some(c => c.url === "/api/v1/config/OPERATOR"),
    "discard re-reads the loaded policy from the server");
assert(status().textContent.includes("Discarded pending edits"),
    "the status line confirms the discard");
assert(!isDirtyShown(), "discard turns the dirty marker off");
assert(diff().hidden === true, "discard empties the diff panel");
assert($id("cfg-threshold").value === String(POLICY.passiveThreshold),
    "the edited field is back on the loaded value");
discardBtn().dispatch("click");
await settle();
assert(status().textContent.includes("No pending edits"),
    "discarding a clean form is a no-op that says so");

section("manual active liveness — extra check after a passive pass");
assert($id("btn-active").disabled === true,
    "the extra-check button is disabled while no session has passed");
$id("btn-run").dispatch("click");
await settle();
assert($id("verdict-action").textContent === "PROCEED",
    "the passive pass ends the run with a PROCEED verdict");
assert($id("btn-active").disabled === false,
    "a pass on the passive stage enables the extra-check button");

challengeAction = "manual_check_passed";
const activeFetches = fetchCalls.length;
$id("btn-active").dispatch("click");
await settle(4);
assert(fetchCalls.slice(activeFetches).some(c => c.method === "POST"
        && c.url === "/api/v1/sessions/sess-00000000-0000-0000-0000-000000000001/active-liveness"),
    "the button asks the server for one manual active challenge");
// The challenge loop captures a short frame window (~1s) before validating.
await new Promise(r => setTimeout(r, 1300));
await settle();
assert($id("verdict-action").textContent === "EXTRA CHECK PASSED",
    "the extra check reports its own verdict");
assert(fetchCalls.filter(c => c.url.includes("/challenges/validate")).length === 1,
    "the extra check stops on its verdict instead of re-validating the resolved challenge");
assert(!$id("verdict-msg").textContent.includes("HTTP 409"),
    "a resolved manual challenge is never re-submitted");
assert($id("verdict-msg").textContent.includes("Extra check passed"),
    "the message is the extra check's, not a rewritten session verdict");
assert($id("verdict-msg").textContent.includes("unchanged"),
    "the console says the session verdict is unchanged");
assert($id("btn-active").disabled === false,
    "another extra check can be requested afterwards");
challengeAction = "proceed";

section("budget-exhausted outcomes — locked / failed");
// The server's terminal vocabulary for a spent retry budget (docs/configuration.md):
// the console must end the run on the server's own action and neither re-validate
// the resolved challenge nor present a lock-out as retryable.
frameAction = "escalate_to_active";
challengeAction = "locked";
let validateCalls = fetchCalls.filter(c => c.url.includes("/challenges/validate")).length;
$id("btn-run").dispatch("click");
await new Promise(r => setTimeout(r, 1300));   // the challenge loop captures before validating
await settle();
assert($id("verdict-action").textContent === "LOCKED OUT",
    "a LOCK_OUT budget exhaustion reports LOCKED OUT, not REJECT");
assert($id("verdict-msg").textContent.includes("contact an operator"),
    "the message is the server's own, not a generic failure line");
assert(!$id("verdict-msg").textContent.includes("new session"),
    "a lock-out is never presented as retryable");
const afterLocked = fetchCalls.filter(c => c.url.includes("/challenges/validate")).length;
assert(afterLocked === validateCalls + 1,
    "the locked verdict is terminal: the resolved challenge is not re-submitted");
assert(!$id("verdict-msg").textContent.includes("HTTP 409"),
    "and the closed session is never re-validated");

challengeAction = "failed";
validateCalls = fetchCalls.filter(c => c.url.includes("/challenges/validate")).length;
$id("btn-run").dispatch("click");
await new Promise(r => setTimeout(r, 1300));
await settle();
assert($id("verdict-action").textContent === "FAILED",
    "a FALLBACK budget exhaustion reports FAILED");
assert($id("verdict-msg").textContent.includes("new session"),
    "only the retryable outcome says a fresh session is allowed");
assert(fetchCalls.filter(c => c.url.includes("/challenges/validate")).length === validateCalls + 1,
    "the failed verdict is terminal too");
frameAction = "proceed";
challengeAction = "proceed";

section("capture budget — 640px long edge at quality 0.7 (task 1)");
assert(windowTarget.CAPTURE_BUDGET
    && windowTarget.CAPTURE_BUDGET.maxEdge === 640
    && windowTarget.CAPTURE_BUDGET.quality === 0.7
    && windowTarget.CAPTURE_BUDGET.maxFps === 8,
    "the capture budget is the documented 640px / 0.7 / 8fps");
frameAction = "proceed";
let framesBeforeCapture = fetchCalls.filter(c => c.url.includes("/frames")).length;
$id("btn-run").dispatch("click");
await new Promise(r => setTimeout(r, 500));
await settle();
assert(fetchCalls.filter(c => c.url.includes("/frames")).length === framesBeforeCapture + 1,
    "the run sends one frame and stops on its verdict");
const enc = captureCanvas().lastEncode;
assert(enc.width === 640 && enc.height === 360,
    `a 1280x720 camera frame is captured at 640x360, not its native size (got ${enc.width}x${enc.height})`);
assert(enc.type === "image/jpeg" && enc.quality === 0.7,
    `frames are JPEG at quality 0.7, not the old 0.85/0.9 (got ${enc.quality})`);
assert(enc.width / enc.height === 16 / 9, "the aspect ratio survives the downscale");
assert(captureCanvas().lastDraw[3] === 1280 && captureCanvas().lastDraw[4] === 720,
    "the whole 1280x720 source rectangle is drawn — the cap scales, it never crops");
assert(createdElements.filter(e => e.tagName === "CANVAS").length === 1,
    "one canvas is allocated per page and reused for every frame, not one per capture");
assert(captureCanvas().encodes > 1, "the reused canvas really is what encodes the frames");

// A small camera must not be upscaled into a larger, more expensive frame.
$id("video").videoWidth = 320;
$id("video").videoHeight = 240;
$id("btn-run").dispatch("click");
await new Promise(r => setTimeout(r, 500));
await settle();
assert(captureCanvas().lastEncode.width === 320 && captureCanvas().lastEncode.height === 240,
    "a 320x240 camera keeps its own size: the cap is a ceiling, never an upscale");
$id("video").videoWidth = 1280;
$id("video").videoHeight = 720;

section("capture pauses while the tab is hidden (task 1)");
frameAction = "retry_passive";   // keep the passive loop iterating while hidden
const framesBeforeHide = fetchCalls.filter(c => c.url.includes("/frames")).length;
const sessionsBeforeHide =
    fetchCalls.filter(c => c.method === "POST" && c.url === "/api/v1/sessions").length;
document.hidden = true;
document.dispatch("visibilitychange");
$id("btn-run").dispatch("click");
await new Promise(r => setTimeout(r, 800));
await settle();
assert(fetchCalls.filter(c => c.method === "POST" && c.url === "/api/v1/sessions").length
        === sessionsBeforeHide + 1,
    "the session is still created while hidden — only the capture pauses");
assert(fetchCalls.filter(c => c.url.includes("/frames")).length === framesBeforeHide,
    "no frame is captured or sent while the tab is hidden");
assert($id("log").textContent.includes("Tab hidden — capture paused"),
    "the decision log says why the capture stopped");
document.hidden = false;
frameAction = "proceed";
document.dispatch("visibilitychange");
await new Promise(r => setTimeout(r, 700));
await settle();
assert(fetchCalls.filter(c => c.url.includes("/frames")).length > framesBeforeHide,
    "capture resumes when the tab is visible again");
assert($id("verdict-action").textContent === "PROCEED",
    "the paused run reaches its normal verdict once visible");
assert($id("log").textContent.includes("capture resumed"),
    "the log records the resume too");

section("challenge burst — frames per request (task 3)");
// The composition is deliberately unchanged (baseline + up to CHALLENGE_WINDOW
// recent frames): the engine measures an excursion relative to the baseline, so
// trimming the window would change what it evaluates. What the guards pin is
// that the burst stays that size and does not grow as the loop polls.
const bursts = fetchCalls.filter(c => c.url.includes("/challenges/validate"))
    .map(c => JSON.parse(c.body).framesBase64.length);
assert(bursts.length > 0, `challenge validations ran (${bursts.length} of them)`);
assert(bursts[0] === 4, `the first burst carries baseline + 3 recent frames (got ${bursts[0]})`);
assert(bursts.every(n => n === 4 || n === 5),
    `no burst exceeds baseline + the 4-frame window (got ${bursts.join(", ")})`);

section("beforeunload — dirty form (item 3)");
edit($id("cfg-threshold"), "0.9");
assert(beforeunload().defaultPrevented === true, "closing with pending edits is intercepted");
discardBtn().dispatch("click");
await settle();
assert(beforeunload().defaultPrevented === false, "closing a clean form is not intercepted");

section("beforeunload — in-flight session (item 3)");
hangFrames = true;
$id("btn-run").dispatch("click");
await settle();
assert(fetchCalls.some(c => c.method === "POST" && c.url === "/api/v1/sessions"),
    "the run created a session before blocking on frames");
assert(beforeunload().defaultPrevented === true,
    "closing or refreshing mid-check is intercepted while the session is in flight");

section("policy change history — risk classification (item 23)");
$id("btn-cfg-audit").dispatch("click");
await settle();
const history = () => $id("cfg-audit").textContent;
assert(history().includes("RESIDENT  UPDATED  by key:9f2c1a7b4e0d  [risk: HIGH]"),
    "a weakening edit shows its level on the entry line");
assert(history().includes("! passiveThreshold lowered"),
    "the reason names the weakening move, above the field diff");
assert(history().includes("passiveThreshold: 0.8 → 0.4"),
    "the field diff still renders under the risk line");
assert(history().includes("OPERATOR  UPDATED  by key:1a2b3c4d5e6f  [risk: LOW]"),
    "a benign edit shows LOW");
assert(history().includes("SUPERVISOR  CREATED  by key:abcdefabcdef  [risk: —]"),
    "an entry from before the classification shows an em dash, never a guessed level");

section("never queue — a capture is dropped while one request is in flight (task 1)");
// hangFrames is still set from the section above: the supervisor run is blocked
// on its frame POST, so the gate is closed. The resident screen shares the same
// camera and the same gate, so its capture must be dropped — not stored up
// behind the blocked request.
const framesWhileHung = fetchCalls.filter(c => c.url.includes("/frames")).length;
const sessionsWhileHung = fetchCalls.filter(c => c.method === "POST" && c.url === "/api/v1/sessions").length;
$id("btn-res-camera").dispatch("click");
await settle();
assert($id("btn-res-camera").disabled === true,
    "the resident camera starts once its stream is open");
$id("btn-res-run").dispatch("click");
await new Promise(r => setTimeout(r, 700));
await settle();
assert(fetchCalls.filter(c => c.method === "POST" && c.url === "/api/v1/sessions").length
        === sessionsWhileHung + 1,
    "the resident session request still goes out — only frames are gated");
assert(fetchCalls.filter(c => c.url.includes("/frames")).length === framesWhileHung,
    "no frame is captured or queued behind the in-flight request");

// Release the blocked request: the dropped capture is retried on the next tick.
// A queue would have released a backlog here; a drop releases exactly one frame.
hangFrames = false;
releaseHang();
await new Promise(r => setTimeout(r, 700));
await settle();
assert(fetchCalls.filter(c => c.url.includes("/frames")).length > framesWhileHung,
    "the dropped capture is retried once the pipe is free — a drop, not a stall");
assert(fetchCalls.filter(c => c.url.includes("/frames")).length <= framesWhileHung + 2,
    "and nothing was stored up behind it: at most the one dropped frame goes out");

section("session columns report the service's answer, not a default");
// Two sessions exist now (the supervisor run and the resident one), so the
// placeholders are gone and every row is a real id. The stub's GET /sessions/{id}
// says FAILED with 3 retries; the creation responses said ACTIVE with no
// retries, so a table that showed PASSED, 0 or "—" here would be inventing it.
sessionDetailStatus = "FAILED";
sessionDetailRetries = 3;
$id("btn-sessions").dispatch("click");
await settle();
const realRows = () => sessionRows().filter(tr => !tr.className.includes("is-sample"));
assert(realRows().length > 0 && sessionRows().every(tr => !tr.className.includes("is-sample")),
    `real sessions replace the placeholders (${realRows().length} rows)`);
assert(fetchCalls.some(c => c.method === "GET" && /\/api\/v1\/sessions\/[^/]+$/.test(c.url)),
    "the refresh reads each session back from GET /sessions/{id}");
assert(realRows().every(tr => tr.children[2].textContent.includes("FAILED")),
    "the Status column shows the service's status for every row");
assert(realRows().every(tr => tr.children[3].textContent === "3"),
    "the Retries column shows the service's retry count");
assert(realRows().every(tr => tr.children[4].textContent.includes("max_retries_exceeded")),
    "the Reason column shows the service's failure reason");
assert(!sessionRows().some(tr => tr.children[2].textContent.includes("PASSED")),
    "no row claims a pass while the service reports a failure");

// And the table follows the service when the answer changes: a session that
// later passes is shown as passed, from the same read.
sessionDetailStatus = "PASSED";
sessionDetailRetries = 0;
$id("btn-sessions").dispatch("click");
await settle();
assert(realRows().every(tr => tr.children[2].textContent.includes("PASSED")),
    "a changed status on the service is what the table shows");
assert($id("sessions-note").hidden === true,
    "the placeholder note is gone once the rows are real");
sessionDetailStatus = "ACTIVE";
sessionDetailRetries = 0;

section("frame pacing — the 8/s ceiling holds for the shared gate (task 1)");
// Both capture screens share one gate, so the ceiling is on the combined rate:
// this is what stops two open screens (supervisor + resident) from sending
// 2x8 frames per second at a 2-core box.
const gated = fetchCalls
    .filter(c => c.url.includes("/frames") || c.url.includes("/challenges/validate"))
    .map(c => c.at);
const gaps = gated.slice(1).map((t, i) => t - gated[i]);
const minGap = gaps.length ? Math.min(...gaps) : Infinity;
const ceiling = 1000 / windowTarget.CAPTURE_BUDGET.maxFps;
assert(gated.length > 10, `the run produced enough gated sends to measure (${gated.length})`);
assert(minGap >= ceiling - 1,
    `every send is at least ${ceiling}ms after the previous one (min gap ${minGap}ms over ${gated.length} sends)`);

section("credential dialog — no platform prompt, and no request until it is answered");
// Every credential the console asks for goes through the page's own dialog.
// The platform prompt it replaced could not say what it was asking for, told
// "cancelled" and "typed nothing" apart only by an empty string, and — because
// some embedded webviews suppress it entirely — could leave a first-time
// supervisor being asked nothing and then told to sign in again.
// Comments are allowed to name the dialog that was removed; a call is not.
const consoleCode = consoleJs
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .replace(/^[ \t]*\/\/.*$/gm, "");
assert(!/window\.prompt/.test(consoleCode),
    "console.js makes no window.prompt call");
assert($id("auth-modal").hidden === true,
    "the credential dialog starts hidden in the markup");
assert($id("auth-modal-pass").value === "",
    "the dialog's password field starts empty — nothing is remembered from a previous open");

const modal = () => $id("auth-modal");
const modalError = () => $id("auth-modal-error");
const openModalBySaving = () => $id("btn-cfg-save").dispatch("click");
const submitted = () => $id("auth-modal-form").dispatch("submit", { preventDefault() {} });
const posts = () => fetchCalls.filter(c => (c.method || "GET") !== "GET");

// The operator path that needs a credential: Save policy with no session yet.
let beforeOpen = fetchCalls.length;
openModalBySaving();
await settle();
assert(modal().hidden === false,
    "saving with no session opens the dialog instead of a platform prompt");
assert($id("auth-modal-title").textContent.includes("Sign in to continue"),
    "the dialog says what the credential is authorising");
assert($id("auth-modal-pass-field").hidden === false
    && $id("auth-modal-new-field").hidden === true,
    "authorising offers the password field and not the new-password one");
assert($id("auth-modal-role-field").hidden === false,
    "the dialog offers the role choice, not only a supervisor credential");
for (const role of ["resident", "operator", "supervisor"]) {
  assert($id(`auth-modal-role-${role}`).getAttribute("data-role") === role.toUpperCase(),
      `the dialog keeps the ${role} option`);
}
assert($id("auth-modal-role-supervisor").className.includes("active")
    && $id("auth-modal-role-supervisor").getAttribute("aria-pressed") === "true",
    "the policy-saving dialog starts on Supervisor");
assert($id("auth-modal-user").value === "supervisor"
    && $id("auth-modal-pass").value === "supervisor123",
    "the default supervisor credential is filled in already");

// The role tabs swap the prefilled credentials and the verb on the submit
// button; Resident has no credential to ask for, so its fields are hidden.
$id("auth-modal-role-operator").dispatch("click");
assert($id("auth-modal-user").value === "operator"
    && $id("auth-modal-pass").value === "operator1234",
    "choosing Operator fills the operator default credential");
assert($id("auth-modal-submit").textContent === "Sign in as Operator",
    "the submit button names the chosen role");
$id("auth-modal-role-resident").dispatch("click");
assert($id("auth-modal-user-field").hidden === true
    && $id("auth-modal-pass-field").hidden === true,
    "Resident hides the credential fields");
assert($id("auth-modal-submit").textContent === "Continue as Resident",
    "Resident's submit button continues without a credential");
$id("auth-modal-role-supervisor").dispatch("click");
assert($id("auth-modal-user-field").hidden === false
    && $id("auth-modal-pass-field").hidden === false
    && $id("auth-modal-user").value === "supervisor"
    && $id("auth-modal-pass").value === "supervisor123",
    "switching back to Supervisor restores its fields and default credential");
assert(fetchCalls.length === beforeOpen,
    "an open dialog sends nothing — the save waits for an answer");

// Cancel is an answer: nothing is sent, and the save does not proceed.
beforeOpen = fetchCalls.length;
$id("auth-modal-cancel").dispatch("click");
await settle();
assert(modal().hidden === true, "Cancel closes the dialog");
assert(fetchCalls.length === beforeOpen, "Cancel sends nothing at all");
assert(!fetchCalls.slice(beforeOpen).some(c => c.url.endsWith("/auth/login")),
    "a cancelled dialog never signs in");
assert(status().textContent.includes("a username and a password are both needed"),
    "the status line says why the save did not happen");

// Answering it: exactly what was typed goes out, and the password does not
// outlive the dialog anywhere in the page.
openModalBySaving();
await settle();
$id("auth-modal-user").value = "sup-01";
$id("auth-modal-pass").value = "correct-horse-battery-staple";
submitted();
await settle();
const logins = fetchCalls.slice(beforeOpen).filter(c => c.url.endsWith("/auth/login"));
assert(logins.length === 1, `answering the dialog signs in exactly once (got ${logins.length})`);
assert(JSON.parse(logins[0].body).username === "sup-01"
    && JSON.parse(logins[0].body).password === "correct-horse-battery-staple",
    "the sign-in carries what was typed into the dialog");
assert(modal().hidden === true && $id("auth-modal-pass").value === "",
    "the dialog closes and clears the password field");
assert(![...byId.values()].some(el => el.textContent.includes("correct-horse-battery-staple")),
    "the password appears nowhere in the page's text afterwards");
assert(posts().some(c => c.method === "PUT" && c.url.includes("/api/v1/config/")),
    "the save the dialog authorised then proceeds");

section("credential dialog — a first sign-in sets the password in the dialog");
// An account the service confines to the change-password route: signing in is
// the way in, and the new password is the answer to the dialog that follows.
loginAnswer = { username: "supervisor", role: "SUPERVISOR", mustChangePassword: true };
$id("input-supervisor-user").value = "supervisor";
$id("input-supervisor-pass").value = "first-password";
$id("btn-login-supervisor").dispatch("click");
await settle();
assert(modal().hidden === false,
    "a first sign-in opens the dialog rather than asking through a prompt");
assert($id("auth-modal-new-field").hidden === false
    && $id("auth-modal-pass-field").hidden === true,
    "it asks for a new password and does not ask for the current one again");
assert($id("auth-modal-role-field").hidden === true,
    "setting a first password does not offer a role switch");
assert($id("auth-modal-user").value === "supervisor"
    && $id("auth-modal-user").readOnly === true,
    "it names the account, which cannot be swapped mid-dialog");

// A too-short password is answered in the dialog, before any request.
let beforeShort = fetchCalls.length;
$id("auth-modal-new").value = "short";
submitted();
await settle();
assert(modal().hidden === false && modalError().hidden === false
    && modalError().textContent.includes("at least 12"),
    "a too-short new password is refused in the dialog, not by the service");
assert(fetchCalls.length === beforeShort, "the refusal costs no request");

// The password just used is sent as the current one, and the new one is used.
$id("auth-modal-new").value = "second-longer-password";
submitted();
await settle();
const changes = posts().filter(c => c.url.endsWith("/auth/change-password"));
assert(changes.length === 1,
    `setting it sends exactly one change-password request (got ${changes.length})`);
assert(JSON.parse(changes[0].body).currentPassword === "first-password"
    && JSON.parse(changes[0].body).newPassword === "second-longer-password",
    "the change carries the password just used as the current one");
assert(modal().hidden === true && $id("auth-modal-new").value === "",
    "the dialog closes and clears the new password");
loginAnswer = { username: "sup-01", role: "SUPERVISOR", mustChangePassword: false };

section("credential dialog — Operator and Resident roles do not write policy");
// The role tabs are not decoration: choosing Operator signs in for the read-only
// views, and Resident opens capture without a request. Neither may follow the
// sign-in with the policy PUT the supervisor flow authorises.
loginAnswer = { username: "operator", role: "OPERATOR", mustChangePassword: false };
$id("btn-cfg-sign-out").dispatch("click");
await settle();
assert($id("login-page").hidden === false, "signing out returns to the role picker");
let beforeOperator = fetchCalls.length;
openModalBySaving();
await settle();
$id("auth-modal-role-operator").dispatch("click");
submitted();
await settle();
const operatorLogin = posts().filter(c => c.url.endsWith("/auth/login")).slice(-1)[0];
assert(operatorLogin && JSON.parse(operatorLogin.body).username === "operator"
    && JSON.parse(operatorLogin.body).password === "operator1234",
    "the Operator role signs in with the operator default credential");
assert($id("operator-policy-banner").hidden === false
    && $id("tab-policy").hidden === true,
    "Operator gets the read-only view with its policy notice");
assert(!fetchCalls.slice(beforeOperator)
    .some(c => c.method === "PUT" && c.url.includes("/api/v1/config/")),
    "choosing Operator does not send the policy write");

// Resident has no credential at all: the dialog closes into capture, and no
// sign-in or policy request is made.
$id("btn-cfg-sign-out").dispatch("click");
await settle();
let beforeResident = fetchCalls.length;
openModalBySaving();
await settle();
$id("auth-modal-role-resident").dispatch("click");
submitted();
await settle();
assert($id("role-resident").hidden === false,
    "choosing Resident opens the capture view");
assert(!fetchCalls.slice(beforeResident)
    .some(c => c.url.endsWith("/auth/login") || c.method === "PUT"),
    "Resident sends neither a sign-in nor a policy write");

console.log(`\n${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
