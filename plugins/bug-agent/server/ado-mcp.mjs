#!/usr/bin/env node
/**
 * bug-agent MCP server (stdio, zero dependencies, Node >= 18).
 *
 * Azure DevOps:  check_connection, list_new_bugs, get_bug, add_comment
 * Projects:      get_projects, set_projects
 * Fix workdirs:  prepare_workdir, finalize_workdir   (git worktree or overlay copy)
 * Memory:        record_bug, reset_bug, history
 *
 * Config comes from env vars that plugin.json's userConfig fills in.
 */
import fs from "node:fs";
import path from "node:path";
import os from "node:os";
import { execFileSync } from "node:child_process";
import readline from "node:readline";
import { fileURLToPath } from "node:url";

// ---------- config ----------
const opt = (name, def = "") => {
  const v = (process.env[name] ?? "").trim();
  return !v || v.startsWith("${") ? def : v; // unset userConfig may arrive unsubstituted
};
const CFG = {
  org: opt("AZURE_DEVOPS_ORG"),
  project: opt("AZURE_DEVOPS_PROJECT"),
  pat: opt("AZURE_DEVOPS_PAT"),
  assignedTo: opt("AZURE_DEVOPS_ASSIGNED_TO", "@Me"),
  states: opt("AZURE_DEVOPS_STATES", "New").split(",").map((s) => s.trim()).filter(Boolean),
  postComments: ["true", "1", "yes"].includes(opt("BUG_AGENT_POST_COMMENTS", "false").toLowerCase()),
  maxAttempts: Number(opt("BUG_AGENT_MAX_ATTEMPTS", "3")),
  baseUrl: opt("AZURE_DEVOPS_BASE_URL", "https://dev.azure.com").replace(/\/+$/, ""),
  dataDir: path.resolve(opt("BUG_AGENT_DATA", path.join(os.homedir(), ".bug-agent"))),
};
fs.mkdirSync(CFG.dataDir, { recursive: true });
const STATE_FILE = path.join(CFG.dataDir, "state.json");
const PROJECTS_FILE = path.join(CFG.dataDir, "projects.json");

// ---------- small helpers ----------
const readJson = (f, d) => (fs.existsSync(f) ? JSON.parse(fs.readFileSync(f, "utf8")) : d);
const writeJson = (f, v) => {
  fs.writeFileSync(`${f}.tmp`, JSON.stringify(v, null, 2));
  fs.renameSync(`${f}.tmp`, f);
};
const loadState = () => readJson(STATE_FILE, { bugs: {}, history: [] });
const loadProjects = () => readJson(PROJECTS_FILE, []);
const safeName = (s) => String(s).replace(/[^a-zA-Z0-9._-]+/g, "-");
const git = (cwd, args) => execFileSync("git", args, { cwd, encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] }).trim();
const gitTop = (dir) => {
  try {
    return path.resolve(git(dir, ["rev-parse", "--show-toplevel"]));
  } catch {
    return null;
  }
};
function inside(root, rel) {
  const target = path.resolve(root, String(rel).replace(/\\/g, "/").replace(/^\/+/, ""));
  const r = path.relative(root, target);
  if (r === ".." || r.startsWith(".." + path.sep) || path.isAbsolute(r)) throw new Error(`path escapes the project: ${rel}`);
  return target;
}
export function stripHtml(html) {
  return String(html ?? "")
    .replace(/<br\s*\/?>/gi, "\n").replace(/<\/(p|div|li|h\d)>/gi, "\n").replace(/<[^>]+>/g, "")
    .replace(/&nbsp;/g, " ").replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'").replace(/&amp;/g, "&").replace(/\n{3,}/g, "\n\n").trim();
}
const escHtml = (s) => String(s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");

// ---------- Azure DevOps ----------
function requireAzure() {
  const missing = [["azure_org", CFG.org], ["azure_project", CFG.project], ["azure_pat", CFG.pat]].filter(([, v]) => !v).map(([k]) => k);
  if (missing.length) throw new Error(`plugin option(s) not set: ${missing.join(", ")}. Run: claude plugin configure bug-agent`);
}
async function ado(url, init = {}) {
  requireAzure();
  const res = await fetch(url, {
    ...init,
    headers: {
      Authorization: `Basic ${Buffer.from(`:${CFG.pat}`).toString("base64")}`,
      "Content-Type": "application/json",
      Accept: "application/json",
    },
  });
  if (!res.ok) {
    const body = (await res.text().catch(() => "")).slice(0, 300);
    const hint = res.status === 401 || res.status === 203 ? " (check the PAT and its Work Items scope)" : "";
    throw new Error(`Azure DevOps ${res.status} ${res.statusText}${hint}: ${body}`);
  }
  return res.json();
}
const orgUrl = () => `${CFG.baseUrl}/${encodeURIComponent(CFG.org)}`;
const projUrl = () => `${orgUrl()}/${encodeURIComponent(CFG.project)}`;
const bugLink = (id) => `${projUrl()}/_workitems/edit/${id}`;

export function buildWiql(assignedTo, states, project) {
  const q = (s) => `'${String(s).replace(/'/g, "''")}'`;
  const who = assignedTo.trim() === "@Me" ? "@Me" : q(assignedTo);
  return (
    `SELECT [System.Id] FROM WorkItems WHERE [System.TeamProject] = ${q(project)} ` +
    `AND [System.WorkItemType] = 'Bug' AND [System.AssignedTo] = ${who} ` +
    `AND (${states.map((s) => `[System.State] = ${q(s)}`).join(" OR ")}) ` +
    `ORDER BY [Microsoft.VSTS.Common.Priority] ASC, [System.CreatedDate] ASC`
  );
}
async function queryIds() {
  const data = await ado(`${projUrl()}/_apis/wit/wiql?$top=100&api-version=7.1`, {
    method: "POST",
    body: JSON.stringify({ query: buildWiql(CFG.assignedTo, CFG.states, CFG.project) }),
  });
  return data.workItems.map((w) => w.id);
}
async function getItems(ids) {
  if (!ids.length) return [];
  const fields = [
    "System.Title", "System.State", "System.Description", "System.Tags", "System.AreaPath",
    "Microsoft.VSTS.TCM.ReproSteps", "Microsoft.VSTS.Common.Priority", "Microsoft.VSTS.Common.Severity",
  ].join(",");
  const data = await ado(`${orgUrl()}/_apis/wit/workitems?ids=${ids.join(",")}&fields=${fields}&errorPolicy=omit&api-version=7.1`);
  return data.value.filter(Boolean).map((w) => {
    const f = w.fields;
    return {
      id: w.id,
      title: f["System.Title"] ?? "",
      url: bugLink(w.id),
      state: f["System.State"] ?? "",
      priority: f["Microsoft.VSTS.Common.Priority"] ?? null,
      severity: f["Microsoft.VSTS.Common.Severity"] ?? null,
      areaPath: f["System.AreaPath"] ?? "",
      tags: f["System.Tags"] ?? "",
      description: stripHtml(f["System.Description"]).slice(0, 4000),
      reproSteps: stripHtml(f["Microsoft.VSTS.TCM.ReproSteps"]).slice(0, 4000),
    };
  });
}
const shouldProcess = (state, id) => {
  const b = state.bugs[String(id)];
  return !b || (b.lastOutcome === "error" && b.attempts < CFG.maxAttempts);
};

// ---------- fix workdirs ----------
function projectByName(name) {
  const p = loadProjects().find((x) => x.name === name);
  if (!p) throw new Error(`unknown project "${name}". Configured: ${loadProjects().map((x) => x.name).join(", ") || "(none — run /bug-agent:setup)"}`);
  return p;
}
function prepareWorkdir({ bugId, project, files = [] }) {
  const p = projectByName(project);
  const top = p.applyMode === "overlay" ? null : gitTop(p.path);
  if (p.applyMode === "worktree" && !top) throw new Error(`${p.path} is not a git repository`);
  if (top) {
    const sub = path.relative(top, p.path);
    const wtDir = path.join(CFG.dataDir, "worktrees", `${safeName(path.basename(top))}-bug-${bugId}`);
    let branch = `ai-fix/bug-${bugId}`;
    if (fs.existsSync(wtDir)) branch = git(wtDir, ["rev-parse", "--abbrev-ref", "HEAD"]);
    else {
      fs.mkdirSync(path.dirname(wtDir), { recursive: true });
      if (git(top, ["branch", "--list", branch])) branch = `${branch}-${Date.now().toString(36)}`;
      git(top, ["worktree", "add", "-b", branch, wtDir, "HEAD"]);
    }
    return { mode: "worktree", workdir: path.join(wtDir, sub), branch, sourcePath: p.path };
  }
  const overlay = path.join(CFG.dataDir, "patches", `bug-${bugId}`, safeName(project));
  for (const rel of files) {
    const src = inside(p.path, rel);
    const dst = inside(overlay, rel);
    if (fs.existsSync(src) && !fs.existsSync(dst)) {
      fs.mkdirSync(path.dirname(dst), { recursive: true });
      fs.copyFileSync(src, dst);
    }
  }
  fs.mkdirSync(overlay, { recursive: true });
  return { mode: "overlay", workdir: overlay, sourcePath: p.path };
}
function walk(dir, base = dir, out = []) {
  if (!fs.existsSync(dir)) return out;
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, e.name);
    if (e.isDirectory()) walk(full, base, out);
    else out.push(path.relative(base, full).split(path.sep).join("/"));
  }
  return out;
}
function finalizeWorkdir({ bugId, project, title = "", summary = "", rootCause = "", url = "" }) {
  const p = projectByName(project);
  const w = prepareWorkdir({ bugId, project });
  if (w.mode === "worktree") {
    const wtRoot = gitTop(w.workdir);
    const sub = path.relative(wtRoot, w.workdir).split(path.sep).join("/");
    const scope = ["--", sub || "."];
    const lines = (args) => git(wtRoot, ["-c", "core.quotepath=false", ...args, ...scope]).split("\n").filter(Boolean);
    const changed = [...new Set([...lines(["diff", "--name-only", "HEAD"]), ...lines(["ls-files", "--others", "--exclude-standard"])])];
    if (!changed.length) return { mode: w.mode, branch: w.branch, filesChanged: [], commit: null };
    git(wtRoot, ["add", "--", ...changed]);
    git(wtRoot, ["-c", "user.name=bug-agent", "-c", "user.email=bug-agent@localhost", "commit", "-m",
      `fix: bug #${bugId} ${title}\n\n${rootCause}\n\n${summary}\n\n${url}`.trim()]);
    return { mode: w.mode, workdir: w.workdir, branch: w.branch, commit: git(wtRoot, ["rev-parse", "--short", "HEAD"]), filesChanged: changed };
  }
  // overlay: keep only files that differ from the original, then write notes
  const changed = [];
  for (const rel of walk(w.workdir)) {
    if (rel === "_FIX_NOTES.md") continue;
    const orig = path.join(p.path, rel);
    const copy = path.join(w.workdir, rel);
    if (fs.existsSync(orig) && fs.readFileSync(orig).equals(fs.readFileSync(copy))) fs.rmSync(copy);
    else changed.push(rel);
  }
  if (changed.length) {
    fs.writeFileSync(path.join(w.workdir, "_FIX_NOTES.md"),
      `# Bug #${bugId}: ${title}\n\n${url}\n\nCopy these files into ${p.path} after review.\n\n## Root cause\n${rootCause}\n\n## Changes\n${summary}\n\n## Files\n${changed.map((f) => `- ${f}`).join("\n")}\n`);
  }
  return { mode: w.mode, workdir: w.workdir, filesChanged: changed };
}

// ---------- tools ----------
const S = (props, required = []) => ({ type: "object", properties: props, required, additionalProperties: false });
export const TOOLS = {
  check_connection: {
    description: "Check the Azure DevOps settings and count the bugs that match the query (assigned to me, configured states).",
    inputSchema: S({}),
    run: async () => {
      const ids = await queryIds();
      return { ok: true, org: CFG.org, project: CFG.project, assignedTo: CFG.assignedTo, states: CFG.states, matchingBugs: ids.length, dataDir: CFG.dataDir };
    },
  },
  list_new_bugs: {
    description:
      "List bugs assigned to me in the configured states (default New) that bug-agent has not handled yet, with title, link, priority, description and repro steps. Bugs whose last attempt errored are retried up to the attempt limit.",
    inputSchema: S({ limit: { type: "integer", minimum: 1, maximum: 50, default: 5 } }),
    run: async ({ limit = 5 }) => {
      const ids = await queryIds();
      const state = loadState();
      const fresh = ids.filter((id) => shouldProcess(state, id));
      return { matching: ids.length, alreadyHandled: ids.length - fresh.length, deferred: Math.max(0, fresh.length - limit), bugs: await getItems(fresh.slice(0, limit)) };
    },
  },
  get_bug: {
    description: "Get one bug's details and link by id.",
    inputSchema: S({ id: { type: "integer" } }, ["id"]),
    run: async ({ id }) => (await getItems([id]))[0] ?? { error: `bug ${id} not found` },
  },
  add_comment: {
    description: "Post a comment on a bug. Only works when the post_comments plugin option is enabled.",
    inputSchema: S({ id: { type: "integer" }, text: { type: "string", description: "Plain text; blank lines separate paragraphs" } }, ["id", "text"]),
    run: async ({ id, text }) => {
      if (!CFG.postComments) return { skipped: true, reason: "post_comments option is off" };
      const html = String(text).split(/\n{2,}/).map((p) => `<p>${escHtml(p).replace(/\n/g, "<br>")}</p>`).join("");
      await ado(`${projUrl()}/_apis/wit/workItems/${id}/comments?api-version=7.1-preview.4`, { method: "POST", body: JSON.stringify({ text: html }) });
      return { posted: true };
    },
  },
  get_projects: {
    description: "List the configured projects (name, path, description, applyMode, isGit).",
    inputSchema: S({}),
    run: async () => ({ projects: loadProjects().map((p) => ({ ...p, isGit: !!gitTop(p.path), exists: fs.existsSync(p.path) })), dataDir: CFG.dataDir }),
  },
  set_projects: {
    description: "Replace the project list. Each path must be an existing folder. applyMode: auto (worktree if git, else overlay), worktree, or overlay.",
    inputSchema: S({
      projects: {
        type: "array",
        items: S({
          name: { type: "string", pattern: "^[A-Za-z0-9._-]+$" },
          path: { type: "string" },
          description: { type: "string" },
          applyMode: { type: "string", enum: ["auto", "worktree", "overlay"], default: "auto" },
        }, ["name", "path", "description"]),
      },
    }, ["projects"]),
    run: async ({ projects }) => {
      const out = projects.map((p) => ({ name: p.name, path: path.resolve(p.path), description: p.description, applyMode: p.applyMode ?? "auto" }));
      const bad = out.filter((p) => !fs.existsSync(p.path) || !fs.statSync(p.path).isDirectory());
      if (bad.length) throw new Error(`not a folder: ${bad.map((p) => p.path).join(", ")}`);
      if (new Set(out.map((p) => p.name)).size !== out.length) throw new Error("project names must be unique");
      writeJson(PROJECTS_FILE, out);
      return { saved: out.map((p) => ({ ...p, isGit: !!gitTop(p.path) })) };
    },
  },
  prepare_workdir: {
    description:
      "Create the place a fixer edits for one bug in one project, without touching the developer's checkout. Git projects: a worktree on branch ai-fix/bug-<id>. Others: an overlay folder pre-filled with copies of `files`. Returns workdir.",
    inputSchema: S({ bugId: { type: "integer" }, project: { type: "string" }, files: { type: "array", items: { type: "string" }, description: "Project-relative files the plan touches" } }, ["bugId", "project"]),
    run: async (a) => prepareWorkdir(a),
  },
  finalize_workdir: {
    description: "After the fixer finishes: commit the changed files on the worktree branch (git) or prune unchanged copies and write _FIX_NOTES.md (overlay). Returns filesChanged, branch, commit.",
    inputSchema: S({
      bugId: { type: "integer" }, project: { type: "string" }, title: { type: "string" }, url: { type: "string" },
      rootCause: { type: "string" }, summary: { type: "string" },
    }, ["bugId", "project"]),
    run: async (a) => finalizeWorkdir(a),
  },
  record_bug: {
    description: "Remember the outcome for a bug so later cycles skip it (errors are retried up to the attempt limit).",
    inputSchema: S({
      id: { type: "integer" }, title: { type: "string" }, url: { type: "string" },
      outcome: { type: "string", enum: ["fixed", "partially_fixed", "needs_human", "error"] },
      rootCause: { type: "string" }, confidence: { type: "string" }, notes: { type: "string" },
      fixes: { type: "array", items: { type: "object" } },
    }, ["id", "title", "outcome"]),
    run: async (a) => {
      const state = loadState();
      const prev = state.bugs[String(a.id)];
      const rec = { ...a, attempts: (prev?.attempts ?? 0) + 1, lastOutcome: a.outcome, updatedAt: new Date().toISOString() };
      state.bugs[String(a.id)] = rec;
      state.history.unshift({ id: a.id, title: a.title, outcome: a.outcome, at: rec.updatedAt });
      state.history = state.history.slice(0, 500);
      writeJson(STATE_FILE, state);
      return { saved: true, attempts: rec.attempts };
    },
  },
  reset_bug: {
    description: "Forget a bug so the next cycle processes it again.",
    inputSchema: S({ id: { type: "integer" } }, ["id"]),
    run: async ({ id }) => {
      const state = loadState();
      const existed = delete state.bugs[String(id)];
      writeJson(STATE_FILE, state);
      return { reset: existed };
    },
  },
  history: {
    description: "Show handled bugs with outcome, root cause and where each fix was written.",
    inputSchema: S({ limit: { type: "integer", default: 20 } }),
    run: async ({ limit = 20 }) => {
      const bugs = Object.values(loadState().bugs).sort((a, b) => b.updatedAt.localeCompare(a.updatedAt)).slice(0, limit);
      return { dataDir: CFG.dataDir, bugs };
    },
  },
};

// ---------- MCP stdio transport (newline-delimited JSON-RPC 2.0) ----------
const send = (msg) => process.stdout.write(JSON.stringify({ jsonrpc: "2.0", ...msg }) + "\n");

export async function handle(req) {
  const { id, method, params } = req;
  if (id === undefined) return null; // notification
  try {
    if (method === "initialize")
      return { id, result: { protocolVersion: params?.protocolVersion ?? "2025-06-18", capabilities: { tools: {} }, serverInfo: { name: "bug-agent-ado", version: "1.0.0" } } };
    if (method === "ping") return { id, result: {} };
    if (method === "tools/list")
      return { id, result: { tools: Object.entries(TOOLS).map(([name, t]) => ({ name, description: t.description, inputSchema: t.inputSchema })) } };
    if (method === "tools/call") {
      const tool = TOOLS[params?.name];
      if (!tool) return { id, error: { code: -32602, message: `unknown tool ${params?.name}` } };
      try {
        const out = await tool.run(params.arguments ?? {});
        return { id, result: { content: [{ type: "text", text: JSON.stringify(out, null, 2) }] } };
      } catch (e) {
        return { id, result: { isError: true, content: [{ type: "text", text: `ERROR: ${e.message}` }] } };
      }
    }
    return { id, error: { code: -32601, message: `method not found: ${method}` } };
  } catch (e) {
    return { id, error: { code: -32603, message: e.message } };
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const rl = readline.createInterface({ input: process.stdin });
  rl.on("line", async (line) => {
    line = line.replace(/^﻿/, "");
    if (!line.trim()) return;
    let req;
    try {
      req = JSON.parse(line);
    } catch {
      return send({ id: null, error: { code: -32700, message: "parse error" } });
    }
    const res = await handle(req);
    if (res) send(res);
  });
}
