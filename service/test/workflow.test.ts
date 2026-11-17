import { describe, it, expect, beforeEach } from "vitest";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { execFileSync } from "node:child_process";
import { Workflow, orderTasks, outcomeFor } from "../src/workflow.js";
import { Store } from "../src/store.js";
import type { AppConfig, ProjectConfig } from "../src/config.js";
import type { Bug, FixPlan, FixTask } from "../src/types.js";
import type { BugPoller } from "../src/agents/poller.js";
import type { BugPlanner } from "../src/agents/planner.js";
import type { BugFixer } from "../src/agents/fixer.js";

const bug = (id: number): Bug => ({
  id, title: `Bug ${id}`, url: `https://dev.azure.com/o/p/_workitems/edit/${id}`, state: "New",
  description: "", reproSteps: "", tags: "", areaPath: "", summary: "", suspectedProjects: [],
});
const task = (project: string, order: number, file = "a.txt"): FixTask => ({ order, project, files: [file], instructions: "x", reason: "y" });

function cfgFor(dataDir: string, projects: ProjectConfig[]): AppConfig {
  return {
    mockMode: false, port: 0, dataDir, cron: "0 * * * *", runOnStart: false, plannerEffort: "medium",
    maxBugsPerCycle: 5, maxAttemptsPerBug: 2,
    azure: { org: "o", project: "p", pat: "x", assignedTo: "@Me", states: ["New"], postComments: false },
    projects,
  };
}

let tmp: string;
beforeEach(() => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), "wf-"));
});

function project(name: string, files: Record<string, string>, applyMode: ProjectConfig["applyMode"] = "overlay"): ProjectConfig {
  const p = path.join(tmp, name);
  for (const [f, c] of Object.entries(files)) {
    fs.mkdirSync(path.dirname(path.join(p, f)), { recursive: true });
    fs.writeFileSync(path.join(p, f), c);
  }
  return { name, path: p, description: "", applyMode };
}

const editFixer = (calls: string[]): BugFixer => ({
  async fix(_b, _p, tasks, ws) {
    calls.push(ws.name);
    for (const t of tasks) await ws.editFile(t.files[0], "old", "new");
    return { status: "applied", summary: `edited ${ws.name}` };
  },
});

describe("orderTasks / outcomeFor", () => {
  it("groups by project and orders by lowest task order", () => {
    const g = orderTasks([task("fe", 2), task("be", 1), task("fe", 3), task("db", 1)]);
    expect(g.map((x) => `${x.project}:${x.order}:${x.tasks.length}`)).toEqual(["be:1:1", "db:1:1", "fe:2:2"]);
  });
  it("derives outcomes", () => {
    const plan = { canFix: true, tasks: [task("a", 1)] } as FixPlan;
    expect(outcomeFor({ ...plan, canFix: false }, [])).toBe("needs_human");
    expect(outcomeFor(plan, [{ status: "applied" } as never])).toBe("fixed");
    expect(outcomeFor(plan, [{ status: "applied" } as never, { status: "failed" } as never])).toBe("partially_fixed");
  });
});

describe("Workflow cycle", () => {
  it("polls, plans, fixes in plan order, and does not reprocess handled bugs", async () => {
    const be = project("backend", { "a.txt": "old value" });
    const fe = project("frontend", { "a.txt": "old value" });
    const cfg = cfgFor(path.join(tmp, "data"), [be, fe]);
    const store = new Store(cfg.dataDir);
    const excluded: number[][] = [];
    const poller: BugPoller = { async poll(ex) { excluded.push([...ex]); return { polled: 2, bugs: [bug(1), bug(2)].filter((b) => !ex.has(b.id)) }; } };
    const planner: BugPlanner = {
      async plan(b) {
        return b.id === 1
          ? { bugId: 1, canFix: true, confidence: "high", rootCause: "rc", testingNotes: "t", risks: "", tasks: [task("frontend", 2), task("backend", 1)] }
          : { bugId: 2, canFix: false, confidence: "low", rootCause: "unknown", testingNotes: "t", risks: "", tasks: [] };
      },
    };
    const calls: string[] = [];
    const wf = new Workflow({ cfg, store, poller, planner, fixer: editFixer(calls) });

    const c1 = await wf.runCycle("manual");
    expect(c1.status).toBe("completed");
    expect(calls).toEqual(["backend", "frontend"]); // order 1 before order 2
    expect(c1.bugs.map((b) => b.outcome)).toEqual(["fixed", "needs_human"]);
    // originals untouched, overlay holds the change + notes
    expect(fs.readFileSync(path.join(be.path, "a.txt"), "utf8")).toBe("old value");
    const ov = path.join(cfg.dataDir, "patches", "bug-1", "backend");
    expect(fs.readFileSync(path.join(ov, "a.txt"), "utf8")).toBe("new value");
    expect(fs.existsSync(path.join(ov, "_FIX_NOTES.md"))).toBe(true);

    const c2 = await wf.runCycle("schedule");
    expect(excluded[1].sort()).toEqual([1, 2]);
    expect(c2.newBugs).toBe(0);
    expect(store.cycles()).toHaveLength(2);
  });

  it("retries errored bugs up to maxAttempts, then stops", async () => {
    const cfg = cfgFor(path.join(tmp, "data"), []);
    const store = new Store(cfg.dataDir);
    let planCalls = 0;
    const wf = new Workflow({
      cfg, store,
      poller: { async poll(ex) { return { polled: 1, bugs: [bug(5)].filter((b) => !ex.has(b.id)) }; } },
      planner: { async plan() { planCalls++; throw new Error("rate limited"); } },
      fixer: editFixer([]),
    });
    await wf.runCycle("manual");
    await wf.runCycle("manual");
    await wf.runCycle("manual");
    expect(planCalls).toBe(2);
    expect(store.getBug(5)).toMatchObject({ attempts: 2, lastOutcome: "error" });
    store.resetBug(5);
    await wf.runCycle("manual");
    expect(planCalls).toBe(3);
  });

  it("stops later steps after a failed step", async () => {
    const a = project("a", { "a.txt": "no match here" });
    const b = project("b", { "a.txt": "old" });
    const cfg = cfgFor(path.join(tmp, "data"), [a, b]);
    const calls: string[] = [];
    const fixer: BugFixer = {
      async fix(_b, _p, tasks, ws) {
        calls.push(ws.name);
        try { await ws.editFile(tasks[0].files[0], "old", "new"); } catch { return { status: "blocked", summary: "could not apply" }; }
        return { status: "applied", summary: "ok" };
      },
    };
    const wf = new Workflow({
      cfg, store: new Store(cfg.dataDir), fixer,
      poller: { async poll() { return { polled: 1, bugs: [bug(3)] }; } },
      planner: { async plan() { return { bugId: 3, canFix: true, confidence: "medium", rootCause: "", testingNotes: "", risks: "", tasks: [task("a", 1), task("b", 2)] }; } },
    });
    const c = await wf.runCycle("manual");
    expect(calls).toEqual(["a"]);
    expect(c.bugs[0].fixes.map((f) => f.status)).toEqual(["failed", "skipped"]);
    expect(c.bugs[0].outcome).toBe("needs_human");
  });

  it("shares one in-flight cycle between concurrent triggers", async () => {
    const cfg = cfgFor(path.join(tmp, "data"), []);
    let polls = 0;
    const wf = new Workflow({
      cfg, store: new Store(cfg.dataDir), fixer: editFixer([]),
      planner: { async plan() { throw new Error("unused"); } },
      poller: { async poll() { polls++; await new Promise((r) => setTimeout(r, 50)); return { polled: 0, bugs: [] }; } },
    });
    const [x, y] = await Promise.all([wf.runCycle("schedule"), wf.runCycle("manual")]);
    expect(x).toBe(y);
    expect(polls).toBe(1);
  });

  it("uses a git worktree + branch for git projects and leaves the checkout alone", async () => {
    const repo = path.join(tmp, "repo");
    const sub = path.join(repo, "backend");
    fs.mkdirSync(sub, { recursive: true });
    fs.writeFileSync(path.join(sub, "a.txt"), "old value\n");
    const git = (...args: string[]) => execFileSync("git", args, { cwd: repo, encoding: "utf8" }).trim();
    git("init", "-q", "-b", "main");
    git("add", ".");
    git("-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qm", "init");

    const cfg = cfgFor(path.join(tmp, "data"), [{ name: "backend", path: sub, description: "", applyMode: "auto" }]);
    const wf = new Workflow({
      cfg, store: new Store(cfg.dataDir), fixer: editFixer([]),
      poller: { async poll() { return { polled: 1, bugs: [bug(42)] }; } },
      planner: { async plan() { return { bugId: 42, canFix: true, confidence: "high", rootCause: "rc", testingNotes: "", risks: "", tasks: [task("backend", 1)] }; } },
    });
    const c = await wf.runCycle("manual");
    const fix = c.bugs[0].fixes[0];
    expect(fix).toMatchObject({ status: "applied", mode: "worktree", branch: "ai-fix/bug-42", filesChanged: ["a.txt"] });
    expect(fix.commit).toMatch(/^[0-9a-f]{7,}$/);
    expect(fs.readFileSync(path.join(sub, "a.txt"), "utf8")).toBe("old value\n"); // checkout untouched
    expect(git("branch", "--show-current")).toBe("main");
    expect(git("show", "ai-fix/bug-42:backend/a.txt")).toBe("new value");
    expect(git("log", "-1", "--format=%s", "ai-fix/bug-42")).toBe("fix: bug #42 Bug 42");
  });
});
