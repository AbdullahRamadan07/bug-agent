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

// The MCP server is a single Java source file run by the JDK source launcher, exactly as .mcp.json does.
const SERVER = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../plugins/bug-agent/server/AdoMcp.java");
const JAVA = process.env.JAVA ?? "java";
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "bug-agent-mcp-"));
const requests = [];
let azure, baseUrl;

// Fake board: bug 101 -> user story 201; bug 102 -> task 301 -> user story 201; bug 103 has no parent.
const parentLink = (id) => ({ rel: "System.LinkTypes.Hierarchy-Reverse", url: `${baseUrl}/acme/_apis/wit/workItems/${id}`, attributes: { name: "Parent" } });
const bugItem = (id, parent) => ({
  id,
  fields: { "System.WorkItemType": "Bug", "System.Title": `Bug ${id}`, "System.State": "New", "Microsoft.VSTS.Common.Priority": 2, "System.Description": "<p>Broken&nbsp;<b>total</b></p>" },
  relations: parent ? [parentLink(parent), { rel: "System.LinkTypes.Related", url: `${baseUrl}/acme/_apis/wit/workItems/999` }] : [],
});
const WORK_ITEMS = {
  get 101() { return bugItem(101, 201); },
  get 102() { return bugItem(102, 301); },
  get 103() { return bugItem(103, null); },
  get 104() { const t = bugItem(104, 201); t.fields["System.WorkItemType"] = "Task"; t.fields["System.Title"] = "Show refund breakdown"; return t; },
  get 201() {
    return { id: 201, fields: { "System.WorkItemType": "User Story", "System.Title": "Refund shows net amount", "System.State": "Active",
      "System.Description": "<div>As an agent I see the refund breakdown</div>",
      "Microsoft.VSTS.Common.AcceptanceCriteria": "<ul><li>Net = supplier refund - charges</li><li>No hidden deductions</li></ul>" }, relations: [] };
  },
  get 301() { return { id: 301, fields: { "System.WorkItemType": "Task", "System.Title": "Refund API" }, relations: [parentLink(201)] }; },
};
before(async () => {
  azure = http.createServer((req, res) => {
    let body = "";
    req.on("data", (c) => (body += c));
    req.on("end", () => {
      requests.push({ method: req.method, url: req.url, auth: req.headers.authorization, body });
      res.setHeader("content-type", "application/json");
      if (req.url.includes("/_apis/wit/wiql")) return res.end(JSON.stringify({ workItems: [{ id: 101 }, { id: 102 }, { id: 103 }, { id: 104 }] }));
      if (req.url.includes("/_apis/wit/workitems?")) {
        const ids = new URL(req.url, "http://x").searchParams.get("ids").split(",").map(Number);
        return res.end(JSON.stringify({ value: ids.map((id) => WORK_ITEMS[id] ?? null) }));
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
  const proc = spawn(JAVA, [SERVER], { env: { ...process.env, ...env }, stdio: ["pipe", "pipe", "inherit"] });
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
  AZURE_DEVOPS_ASSIGNED_TO: "${user_config.assigned_to}", // unset option arrives unsubstituted → default @Me
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
  assert.equal(r1.matching, 4);
  assert.equal(r1.deferred, 2);
  assert.deepEqual(r1.bugs.map((b) => b.id), [101, 102]);
  assert.equal(r1.bugs[0].url, `${baseUrl}/acme/Contoso%20Portal/_workitems/edit/101`);
  assert.equal(r1.bugs[0].description, "Broken total");
  // parent user story with requirements + acceptance criteria
  assert.ok(requests.some((r) => r.url.includes("$expand=relations") && !r.url.includes("fields=")), "uses $expand=relations without fields");
  assert.deepEqual(
    { found: r1.bugs[0].userStory.found, id: r1.bugs[0].userStory.id, type: r1.bugs[0].userStory.type, title: r1.bugs[0].userStory.title },
    { found: true, id: 201, type: "User Story", title: "Refund shows net amount" },
  );
  assert.equal(r1.bugs[0].userStory.description, "As an agent I see the refund breakdown");
  assert.match(r1.bugs[0].userStory.acceptanceCriteria, /Net = supplier refund - charges\nNo hidden deductions/);
  assert.equal(r1.bugs[0].userStory.url, `${baseUrl}/acme/Contoso%20Portal/_workitems/edit/201`);
  assert.deepEqual(r1.bugs[1].userStory.via, ["Task #301"]); // bug -> task -> story
  assert.equal(r1.bugs[1].userStory.id, 201);
  const third = await s.call("get_bug", { id: 103 });
  assert.equal(third.userStory.found, false);
  assert.match(third.userStory.note, /no parent/);
  const wiql = JSON.parse(requests[0].body).query;
  assert.match(wiql, /\[System.AssignedTo\] = @Me/);
  assert.match(wiql, /\[System.WorkItemType\] IN \('Bug', 'Task'\)/);
  assert.equal(r1.bugs[0].type, "Bug");
  assert.match(wiql, /\[System.State\] = 'New'/);
  assert.match(wiql, /\[System.TeamProject\] = 'Contoso Portal'/);
  assert.equal(requests[0].auth, "Basic " + Buffer.from(":secret").toString("base64"));

  await s.call("record_bug", { id: 101, title: "Bug 101", outcome: "fixed" });
  await s.call("record_bug", { id: 102, title: "Bug 102", outcome: "error" });
  const r2 = await s.call("list_new_bugs", { limit: 5 });
  assert.deepEqual(r2.bugs.map((b) => b.id), [102, 103, 104]); // 102 errored once → retried
  await s.call("record_bug", { id: 102, title: "Bug 102", outcome: "error" });
  const r3 = await s.call("list_new_bugs", { limit: 5 });
  assert.deepEqual(r3.bugs.map((b) => b.id), [103, 104]); // 2 attempts used
  await s.call("reset_bug", { id: 101 });
  assert.deepEqual((await s.call("list_new_bugs", { limit: 5 })).bugs.map((b) => b.id), [101, 103, 104]);
  const task = (await s.call("list_new_bugs", { limit: 5 })).bugs.find((b) => b.id === 104);
  assert.equal(task.type, "Task");
  assert.equal(task.userStory.id, 201);
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

test("git mode: item without a story gets fix/bug-<id> in a worktree, commit of changed files only, checkout untouched", async () => {
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
  assert.equal(w.branch, "fix/bug-42");
  fs.writeFileSync(path.join(w.workdir, "a.txt"), "new\n");

  const f = await s.call("finalize_workdir", { bugId: 42, project: "api", title: "Fix a", rootCause: "rc" });
  assert.deepEqual(f.filesChanged, ["backend/a.txt"]);
  assert.match(f.commit, /^[0-9a-f]{7,}$/);
  assert.equal(git("branch", "--show-current"), "main");
  assert.equal(fs.readFileSync(path.join(sub, "a.txt"), "utf8"), "old\n");
  assert.equal(git("show", "fix/bug-42:backend/a.txt"), "new");
  assert.equal(git("log", "-1", "--format=%s", "fix/bug-42"), "fix: bug #42 Fix a");
  s.stop();
});

// ---------------------------------------------------------------- user story branches
function makeRepoWithRemote(name) {
  const root = path.join(tmp, name);
  const remote = path.join(root, "origin.git");
  const repo = path.join(root, "app");
  const other = path.join(root, "teammate");
  const g = (cwd, ...a) => execFileSync("git", ["-c", "user.name=t", "-c", "user.email=t@t", ...a], { cwd, encoding: "utf8" }).trim();
  fs.mkdirSync(root, { recursive: true });
  g(root, "init", "-q", "--bare", "-b", "main", remote);
  g(root, "clone", "-q", remote, other);
  fs.mkdirSync(path.join(other, "api"));
  fs.writeFileSync(path.join(other, "api", "refund.txt"), "net = supplier - charges - 320.80\n");
  g(other, "add", ".");
  g(other, "commit", "-qm", "init");
  g(other, "push", "-q", "origin", "HEAD:main");
  // a teammate already pushed a branch for story 5001
  g(other, "checkout", "-qb", "fix/US-5001-refund");
  fs.writeFileSync(path.join(other, "api", "story.txt"), "work in progress\n");
  g(other, "add", ".");
  g(other, "commit", "-qm", "story work");
  g(other, "push", "-q", "origin", "fix/US-5001-refund");
  g(root, "clone", "-q", remote, repo);
  g(repo, "branch", "fix/50010-other", "origin/main"); // decoy: 50010 must not match 5001
  g(repo, "branch", "feature/us-5001-ui", "origin/main"); // a task branch for the same story: bugs must not use it
  return { repo, sub: path.join(repo, "api"), g: (...a) => g(repo, ...a), push: (...a) => g(other, ...a) };
}

test("user story branches: finds an existing remote branch, else creates one; one branch per story, one commit per bug", async () => {
  const { repo, sub, g, push } = makeRepoWithRemote("stories");
  const data = path.join(tmp, "d6");
  const s = startServer(ENV(data, { BUG_AGENT_BUG_BRANCH_PREFIX: "${user_config.bug_branch_prefix}" })); // unset -> fix/us-
  await s.call("set_projects", { projects: [{ name: "api", path: sub, description: "Refund API" }] });

  // the remote branch appears after bug-agent's fetch
  push("checkout", "-q", "main");
  push("checkout", "-qb", "fix/5009-late");
  push("push", "-q", "origin", "fix/5009-late");

  // story 5001: teammate's remote branch is found and tracked (the 50010 decoy is ignored)
  const a = await s.call("prepare_workdir", { bugId: 11, project: "api", files: ["refund.txt"], userStoryId: 5001 });
  assert.equal(a.mode, "worktree");
  assert.equal(a.branch, "fix/US-5001-refund"); // not feature/us-5001-ui, not fix/50010-other
  assert.match(a.branchSource, /existing remote branch origin\/fix\/US-5001-refund/);
  assert.ok(fs.existsSync(path.join(a.workdir, "story.txt")), "worktree has the story branch's content");
  assert.equal(a.untrackedBefore, undefined);

  // story 5009 only exists on the remote and was pushed after the clone: fetch finds it
  const late = await s.call("prepare_workdir", { bugId: 19, project: "api", files: [], userStoryId: 5009 });
  assert.equal(late.branch, "fix/5009-late");

  // story 5002: no branch anywhere -> new fix/us-5002 from origin/main (no UAT in this repo)
  const b1 = await s.call("prepare_workdir", { bugId: 21, project: "api", files: ["refund.txt"], userStoryId: 5002 });
  assert.equal(b1.branch, "fix/us-5002");
  assert.match(b1.branchSource, /new branch from origin\/main/);
  fs.writeFileSync(path.join(b1.workdir, "refund.txt"), "net = supplier - charges\n");
  const c1 = await s.call("finalize_workdir", { bugId: 21, project: "api", title: "Unexplained deduction", userStoryTitle: "Refund shows net amount", rootCause: "hard-coded fee" });
  assert.deepEqual(c1.filesChanged, ["api/refund.txt"]);

  // second bug of the same story reuses the same branch and folder, and gets its own commit
  const b2 = await s.call("prepare_workdir", { bugId: 22, project: "api", files: ["refund.txt"], userStoryId: 5002 });
  assert.equal(b2.branch, "fix/us-5002");
  assert.equal(b2.workdir, b1.workdir);
  fs.writeFileSync(path.join(b2.workdir, "labels.txt"), "Net refund\n");
  const c2 = await s.call("finalize_workdir", { bugId: 22, project: "api", title: "Missing label", userStoryTitle: "Refund shows net amount" });
  assert.deepEqual(c2.filesChanged, ["api/labels.txt"]);
  assert.deepEqual(g("log", "--format=%s", "-3", "fix/us-5002").split("\n"), ["fix: bug #22 Missing label", "fix: bug #21 Unexplained deduction", "init"]);
  assert.match(g("log", "-1", "--format=%B", "fix/us-5002~1"), /User story #5002: Refund shows net amount/);

  // the developer's checkout never moved
  assert.equal(g("branch", "--show-current"), "main");
  assert.equal(fs.readFileSync(path.join(sub, "refund.txt"), "utf8").replace(/\r\n/g, "\n"), "net = supplier - charges - 320.80\n");
  s.stop();
});

test("user story branch already checked out in the developer's folder: used if clean, refused if dirty, untracked files never committed", async () => {
  const { repo, sub, g } = makeRepoWithRemote("checked-out");
  g("checkout", "-qb", "fix/7003-refund", "origin/main");
  fs.writeFileSync(path.join(sub, "my-notes.txt"), "personal scratch\n"); // untracked, belongs to the developer
  const s = startServer(ENV(path.join(tmp, "d7")));
  await s.call("set_projects", { projects: [{ name: "api", path: sub, description: "Refund API" }] });

  fs.appendFileSync(path.join(sub, "refund.txt"), "dirty\n");
  const refused = await s.call("prepare_workdir", { bugId: 31, project: "api", userStoryId: 7003 });
  assert.match(refused.error, /checked out at .* with uncommitted changes/);
  g("checkout", "--", "api/refund.txt");

  const w = await s.call("prepare_workdir", { bugId: 31, project: "api", userStoryId: 7003 });
  assert.equal(w.mode, "checkout");
  assert.equal(w.branch, "fix/7003-refund");
  assert.equal(path.resolve(w.workdir), path.resolve(sub));
  fs.writeFileSync(path.join(sub, "refund.txt"), "fixed\n");
  const f = await s.call("finalize_workdir", { bugId: 31, project: "api", title: "Fix refund" });
  assert.deepEqual(f.filesChanged, ["api/refund.txt"]);
  assert.equal(g("show", "--name-only", "--format=", "HEAD"), "api/refund.txt");
  assert.ok(fs.existsSync(path.join(sub, "my-notes.txt")), "developer's untracked file left alone");
  assert.match(g("status", "--porcelain"), /\?\? api\/my-notes.txt/);
  s.stop();
});

test("branch_mode=checkout switches the project folder to a new story branch, and refuses when it has uncommitted changes", async () => {
  const { sub, g } = makeRepoWithRemote("checkout-mode");
  const s = startServer(ENV(path.join(tmp, "d8"), { BUG_AGENT_BRANCH_MODE: "checkout", BUG_AGENT_BUG_BRANCH_PREFIX: "fix/US-", BUG_AGENT_BASE_BRANCH: "main" }));
  await s.call("set_projects", { projects: [{ name: "api", path: sub, description: "Refund API" }] });

  fs.appendFileSync(path.join(sub, "refund.txt"), "wip\n");
  const refused = await s.call("prepare_workdir", { bugId: 41, project: "api", userStoryId: 8004 });
  assert.match(refused.error, /uncommitted changes/);
  assert.equal(g("branch", "--show-current"), "main");
  g("checkout", "--", "api/refund.txt");

  const w = await s.call("prepare_workdir", { bugId: 41, project: "api", userStoryId: 8004 });
  assert.equal(w.mode, "checkout");
  assert.equal(w.branch, "fix/US-8004");
  assert.match(w.branchSource, /new branch from origin\/main/);
  assert.equal(g("branch", "--show-current"), "fix/US-8004");
  s.stop();
});

// ---------------------------------------------------------------- tasks vs bugs, UAT base + master sync
function makeUatRepo(name, { conflict = false } = {}) {
  const root = path.join(tmp, name);
  const remote = path.join(root, "origin.git");
  const dev = path.join(root, "dev");
  const repo = path.join(root, "app");
  const g = (cwd, ...a) => execFileSync("git", ["-c", "user.name=t", "-c", "user.email=t@t", ...a], { cwd, encoding: "utf8" }).trim();
  fs.mkdirSync(root, { recursive: true });
  g(root, "init", "-q", "--bare", "-b", "master", remote);
  g(root, "clone", "-q", remote, dev);
  fs.mkdirSync(path.join(dev, "api"));
  fs.writeFileSync(path.join(dev, "api", "fees.txt"), "fee = 10\n");
  g(dev, "add", ".");
  g(dev, "commit", "-qm", "init");
  g(dev, "push", "-q", "origin", "HEAD:master");
  // UAT gets its own commit
  g(dev, "checkout", "-qb", "UAT");
  fs.writeFileSync(path.join(dev, "api", "uat.txt"), "uat only\n");
  if (conflict) fs.writeFileSync(path.join(dev, "api", "fees.txt"), "fee = 20\n");
  g(dev, "add", ".");
  g(dev, "commit", "-qm", "uat work");
  g(dev, "push", "-q", "origin", "UAT");
  // then a hotfix lands on master
  g(dev, "checkout", "-q", "master");
  if (conflict) fs.writeFileSync(path.join(dev, "api", "fees.txt"), "fee = 30\n");
  else fs.writeFileSync(path.join(dev, "api", "hotfix.txt"), "master hotfix\n");
  g(dev, "add", ".");
  g(dev, "commit", "-qm", "master hotfix");
  g(dev, "push", "-q", "origin", "master");
  g(root, "clone", "-q", remote, repo);
  return { repo, sub: path.join(repo, "api"), g: (...a) => g(repo, ...a) };
}

test("tasks go on feature/us-<story>, bugs on fix/us-<story>: new branches start from UAT with master merged in", async () => {
  const { repo, sub, g } = makeUatRepo("uat");
  const uatBefore = g("rev-parse", "origin/UAT");
  const masterBefore = g("rev-parse", "origin/master");
  const s = startServer(ENV(path.join(tmp, "d9"), {
    BUG_AGENT_BASE_BRANCH: "${user_config.base_branch}", // unset -> UAT
    BUG_AGENT_SYNC_BRANCH: "${user_config.sync_branch}", // unset -> master
  }));
  await s.call("set_projects", { projects: [{ name: "api", path: sub, description: "Fees API" }] });

  const t = await s.call("prepare_workdir", { bugId: 61, project: "api", itemType: "Task", userStoryId: 6000, files: [] });
  assert.equal(t.branch, "feature/us-6000");
  assert.match(t.branchSource, /new branch from origin\/UAT, synced with origin\/master/);
  assert.ok(fs.existsSync(path.join(t.workdir, "uat.txt")), "has the UAT commit");
  assert.ok(fs.existsSync(path.join(t.workdir, "hotfix.txt")), "has the master hotfix");
  fs.writeFileSync(path.join(t.workdir, "breakdown.txt"), "net, charges, supplier\n");
  const tf = await s.call("finalize_workdir", { bugId: 61, project: "api", title: "Show refund breakdown", userStoryTitle: "Refunds" });
  assert.deepEqual(tf.filesChanged, ["api/breakdown.txt"]);
  assert.equal(g("log", "-1", "--format=%s", "feature/us-6000"), "feat: task #61 Show refund breakdown");

  // a bug of the same story gets its own fix branch, not the feature branch
  const b = await s.call("prepare_workdir", { bugId: 62, project: "api", itemType: "Bug", userStoryId: 6000, files: [] });
  assert.equal(b.branch, "fix/us-6000");
  assert.notEqual(b.workdir, t.workdir);
  assert.ok(!fs.existsSync(path.join(b.workdir, "breakdown.txt")), "task work is not on the fix branch");
  fs.writeFileSync(path.join(b.workdir, "fees.txt"), "fee = 0\n");
  const bf = await s.call("finalize_workdir", { bugId: 62, project: "api", title: "Wrong fee" });
  assert.equal(g("log", "-1", "--format=%s", "fix/us-6000"), "fix: bug #62 Wrong fee");
  assert.deepEqual(bf.filesChanged, ["api/fees.txt"]);

  // an existing feature/ branch with the story number is reused for tasks only
  g("branch", "feature/US-6100-checkout", "origin/master");
  const t2 = await s.call("prepare_workdir", { bugId: 71, project: "api", itemType: "Task", userStoryId: 6100, files: [] });
  assert.equal(t2.branch, "feature/US-6100-checkout");
  const b2 = await s.call("prepare_workdir", { bugId: 72, project: "api", itemType: "Bug", userStoryId: 6100, files: [] });
  assert.equal(b2.branch, "fix/us-6100");

  // shared branches untouched, developer's checkout untouched
  g("fetch", "-q");
  assert.equal(g("rev-parse", "origin/UAT"), uatBefore);
  assert.equal(g("rev-parse", "origin/master"), masterBefore);
  assert.equal(g("branch", "--show-current"), "master");
  s.stop();
});

test("a sync conflict between UAT and master is refused and leaves no branch behind", async () => {
  const { sub, g } = makeUatRepo("uat-conflict", { conflict: true });
  const data = path.join(tmp, "d10");
  const s = startServer(ENV(data, { BUG_AGENT_BASE_BRANCH: "UAT", BUG_AGENT_SYNC_BRANCH: "master" }));
  await s.call("set_projects", { projects: [{ name: "api", path: sub, description: "Fees API" }] });
  const r = await s.call("prepare_workdir", { bugId: 81, project: "api", itemType: "Bug", userStoryId: 6200, files: [] });
  assert.match(r.error, /new branch fix\/us-6200 was cut from origin\/UAT but merging origin\/master into it conflicts/);
  assert.equal(g("branch", "--list", "fix/us-6200"), "");
  assert.ok(!g("worktree", "list").includes("story-6200"), "worktree removed");
  s.stop();
});
