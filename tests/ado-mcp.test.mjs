// Run with: node --test tests/
// Talks to the real MCP server over stdio, against a fake Azure DevOps API.
import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { spawn, execFileSync } from "node:child_process";
import http from "node:http";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import readline from "node:readline";
import { fileURLToPath } from "node:url";

const SERVER = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../plugins/bug-agent/server/ado-mcp.mjs");
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "bug-agent-mcp-"));
const requests = [];
let azure, baseUrl;

before(async () => {
  azure = http.createServer((req, res) => {
    let body = "";
    req.on("data", (c) => (body += c));
    req.on("end", () => {
      requests.push({ method: req.method, url: req.url, auth: req.headers.authorization, body });
      res.setHeader("content-type", "application/json");
      if (req.url.includes("/_apis/wit/wiql")) return res.end(JSON.stringify({ workItems: [{ id: 101 }, { id: 102 }, { id: 103 }] }));
      if (req.url.includes("/_apis/wit/workitems?")) {
        const ids = new URL(req.url, "http://x").searchParams.get("ids").split(",").map(Number);
        return res.end(JSON.stringify({
          value: ids.map((id) => ({ id, fields: { "System.Title": `Bug ${id}`, "System.State": "New", "Microsoft.VSTS.Common.Priority": 2, "System.Description": "<p>Broken&nbsp;<b>total</b></p>" } })),
        }));
      }
      res.statusCode = 404;
      res.end("{}");
    });
  });
  await new Promise((r) => azure.listen(0, r));
  baseUrl = `http://127.0.0.1:${azure.address().port}`;
});
const procs = [];
after(() => {
  for (const p of procs) p.kill();
  azure.close();
});

function startServer(env) {
  const proc = spawn(process.execPath, [SERVER], { env: { ...process.env, ...env }, stdio: ["pipe", "pipe", "inherit"] });
  procs.push(proc);
  const rl = readline.createInterface({ input: proc.stdout });
  const waiting = new Map();
  rl.on("line", (l) => {
    const msg = JSON.parse(l);
    waiting.get(msg.id)?.(msg);
    waiting.delete(msg.id);
  });
  let n = 0;
  const rpc = (method, params) =>
    new Promise((resolve) => {
      const id = ++n;
      waiting.set(id, resolve);
      proc.stdin.write(JSON.stringify({ jsonrpc: "2.0", id, method, params }) + "\n");
    });
  const call = async (name, args = {}) => {
    const r = await rpc("tools/call", { name, arguments: args });
    const text = r.result.content[0].text;
    return r.result.isError ? { error: text } : JSON.parse(text);
  };
  return { rpc, call, stop: () => proc.kill() };
}

const ENV = (dataDir, extra = {}) => ({
  AZURE_DEVOPS_ORG: "acme",
  AZURE_DEVOPS_PROJECT: "Contoso Portal",
  AZURE_DEVOPS_PAT: "secret",
  AZURE_DEVOPS_ASSIGNED_TO: "${user_config.assigned_to}", // unset option arrives unsubstituted â†’ default @Me
  AZURE_DEVOPS_STATES: "New",
  AZURE_DEVOPS_BASE_URL: baseUrl,
  BUG_AGENT_DATA: dataDir,
  BUG_AGENT_POST_COMMENTS: "false",
  ...extra,
});

test("speaks MCP: initialize and tools/list", async () => {
  const s = startServer(ENV(path.join(tmp, "d1")));
  const init = await s.rpc("initialize", { protocolVersion: "2025-06-18", capabilities: {}, clientInfo: { name: "t", version: "1" } });
  assert.equal(init.result.serverInfo.name, "bug-agent-ado");
  const list = await s.rpc("tools/list", {});
  const names = list.result.tools.map((t) => t.name);
  for (const n of ["check_connection", "list_new_bugs", "get_bug", "add_comment", "get_projects", "set_projects", "prepare_workdir", "finalize_workdir", "record_bug", "reset_bug", "history"])
    assert.ok(names.includes(n), n);
  assert.equal((await s.rpc("nope", {})).error.code, -32601);
  s.stop();
});

test("lists New bugs with links, skips handled ones, retries errors up to the limit", async () => {
  const s = startServer(ENV(path.join(tmp, "d2"), { BUG_AGENT_MAX_ATTEMPTS: "2" }));
  requests.length = 0;
  const r1 = await s.call("list_new_bugs", { limit: 2 });
  assert.equal(r1.matching, 3);
  assert.equal(r1.deferred, 1);
  assert.deepEqual(r1.bugs.map((b) => b.id), [101, 102]);
  assert.equal(r1.bugs[0].url, `${baseUrl}/acme/Contoso%20Portal/_workitems/edit/101`);
  assert.equal(r1.bugs[0].description, "Broken total");
  const wiql = JSON.parse(requests[0].body).query;
  assert.match(wiql, /\[System.AssignedTo\] = @Me/);
  assert.match(wiql, /\[System.State\] = 'New'/);
  assert.match(wiql, /\[System.TeamProject\] = 'Contoso Portal'/);
  assert.equal(requests[0].auth, "Basic " + Buffer.from(":secret").toString("base64"));

  await s.call("record_bug", { id: 101, title: "Bug 101", outcome: "fixed" });
  await s.call("record_bug", { id: 102, title: "Bug 102", outcome: "error" });
  const r2 = await s.call("list_new_bugs", { limit: 5 });
  assert.deepEqual(r2.bugs.map((b) => b.id), [102, 103]); // 102 errored once â†’ retried
  await s.call("record_bug", { id: 102, title: "Bug 102", outcome: "error" });
  const r3 = await s.call("list_new_bugs", { limit: 5 });
  assert.deepEqual(r3.bugs.map((b) => b.id), [103]); // 2 attempts used
  await s.call("reset_bug", { id: 101 });
  assert.deepEqual((await s.call("list_new_bugs", { limit: 5 })).bugs.map((b) => b.id), [101, 103]);
  s.stop();
});

test("reports missing plugin options instead of calling Azure", async () => {
  const s = startServer(ENV(path.join(tmp, "d3"), { AZURE_DEVOPS_PAT: "${user_config.azure_pat}" }));
  const r = await s.call("check_connection");
  assert.match(r.error, /azure_pat/);
  assert.match(r.error, /claude plugin configure bug-agent/);
  const c = await s.call("add_comment", { id: 1, text: "x" });
  assert.equal(c.skipped, true);
  s.stop();
});

test("overlay mode: copies planned files, keeps only changes, writes notes, never touches the project", async () => {
  const proj = path.join(tmp, "web");
  fs.mkdirSync(path.join(proj, "src"), { recursive: true });
  fs.writeFileSync(path.join(proj, "src/cart.ts"), "total += price;\n");
  fs.writeFileSync(path.join(proj, "src/other.ts"), "x\n");
  const data = path.join(tmp, "d4");
  const s = startServer(ENV(data));
  assert.match((await s.call("set_projects", { projects: [{ name: "web", path: path.join(tmp, "missing"), description: "" }] })).error, /not a folder/);
  await s.call("set_projects", { projects: [{ name: "web", path: proj, description: "Angular UI" }] });
  assert.match((await s.call("prepare_workdir", { bugId: 7, project: "web", files: ["../escape.ts"] })).error, /escapes/);

  const w = await s.call("prepare_workdir", { bugId: 7, project: "web", files: ["src/cart.ts", "src/other.ts", "src/new.ts"] });
  assert.equal(w.mode, "overlay");
  assert.equal(w.workdir, path.join(data, "patches", "bug-7", "web"));
  fs.writeFileSync(path.join(w.workdir, "src/cart.ts"), "total += price * qty;\n"); // what the fixer would do
  fs.writeFileSync(path.join(w.workdir, "src/new.ts"), "export {};\n");

  const f = await s.call("finalize_workdir", { bugId: 7, project: "web", title: "Total", rootCause: "qty ignored", summary: "multiply" });
  assert.deepEqual(f.filesChanged.sort(), ["src/cart.ts", "src/new.ts"]);
  assert.ok(!fs.existsSync(path.join(w.workdir, "src/other.ts"))); // unchanged copy pruned
  assert.match(fs.readFileSync(path.join(w.workdir, "_FIX_NOTES.md"), "utf8"), /qty ignored/);
  assert.equal(fs.readFileSync(path.join(proj, "src/cart.ts"), "utf8"), "total += price;\n");
  s.stop();
});

test("git mode: worktree on ai-fix branch, commit of changed files only, checkout untouched", async () => {
  const repo = path.join(tmp, "repo");
  const sub = path.join(repo, "backend");
  fs.mkdirSync(sub, { recursive: true });
  fs.writeFileSync(path.join(sub, "a.txt"), "old\n");
  const git = (...a) => execFileSync("git", a, { cwd: repo, encoding: "utf8" }).trim();
  git("init", "-q", "-b", "main");
  git("add", ".");
  git("-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qm", "init");

  const s = startServer(ENV(path.join(tmp, "d5")));
  const saved = await s.call("set_projects", { projects: [{ name: "api", path: sub, description: "Java API" }] });
  assert.equal(saved.saved[0].isGit, true);
  const w = await s.call("prepare_workdir", { bugId: 42, project: "api", files: ["a.txt"] });
  assert.equal(w.mode, "worktree");
  assert.equal(w.branch, "ai-fix/bug-42");
  fs.writeFileSync(path.join(w.workdir, "a.txt"), "new\n");

  const f = await s.call("finalize_workdir", { bugId: 42, project: "api", title: "Fix a", rootCause: "rc" });
  assert.deepEqual(f.filesChanged, ["backend/a.txt"]);
  assert.match(f.commit, /^[0-9a-f]{7,}$/);
  assert.equal(git("branch", "--show-current"), "main");
  assert.equal(fs.readFileSync(path.join(sub, "a.txt"), "utf8"), "old\n");
  assert.equal(git("show", "ai-fix/bug-42:backend/a.txt"), "new");
  assert.equal(git("log", "-1", "--format=%s", "ai-fix/bug-42"), "fix: bug #42 Fix a");
  s.stop();
});
