import path from "node:path";
import fs from "node:fs";
import type { AppConfig, ProjectConfig } from "./config.js";
import type { AzureDevOpsClient } from "./azure.js";
import type { Store } from "./store.js";
import type { BugPoller } from "./agents/poller.js";
import type { BugPlanner } from "./agents/planner.js";
import type { BugFixer } from "./agents/fixer.js";
import type { Bug, BugOutcome, BugRunRecord, CycleRecord, FixPlan, FixResult, FixTask } from "./types.js";
import { Workspace } from "./workspace.js";
import { commitFiles, createWorktree, gitTopLevel } from "./git.js";

export interface WorkflowDeps {
  cfg: AppConfig;
  store: Store;
  poller: BugPoller;
  planner: BugPlanner;
  fixer: BugFixer;
  azure?: AzureDevOpsClient | null;
  log?: (m: string) => void;
}

/** Groups tasks by project and orders the groups by their lowest task order. */
export function orderTasks(tasks: FixTask[]): { project: string; order: number; tasks: FixTask[] }[] {
  const groups = new Map<string, FixTask[]>();
  for (const t of [...tasks].sort((a, b) => a.order - b.order)) {
    groups.set(t.project, [...(groups.get(t.project) ?? []), t]);
  }
  return [...groups.entries()]
    .map(([project, ts]) => ({ project, order: Math.min(...ts.map((t) => t.order)), tasks: ts }))
    .sort((a, b) => a.order - b.order);
}

export function outcomeFor(plan: FixPlan | undefined, fixes: FixResult[]): BugOutcome {
  if (!plan || !plan.canFix || plan.tasks.length === 0) return "needs_human";
  const applied = fixes.filter((f) => f.status === "applied").length;
  if (applied === fixes.length && applied > 0) return "fixed";
  return applied > 0 ? "partially_fixed" : "needs_human";
}

export class Workflow {
  private current: Promise<CycleRecord> | null = null;
  constructor(private readonly d: WorkflowDeps) {}

  get running() {
    return this.current !== null;
  }

  /** Runs one poll → plan → fix cycle. Concurrent calls share the in-flight cycle. */
  runCycle(trigger: CycleRecord["trigger"]): Promise<CycleRecord> {
    if (!this.current) {
      this.current = this.execute(trigger).finally(() => {
        this.current = null;
      });
    }
    return this.current;
  }

  private async execute(trigger: CycleRecord["trigger"]): Promise<CycleRecord> {
    const { cfg, store } = this.d;
    const cycle: CycleRecord = {
      id: new Date().toISOString().replace(/[:.]/g, "-"),
      trigger,
      startedAt: new Date().toISOString(),
      status: "running",
      polled: 0,
      newBugs: 0,
      skipped: 0,
      bugs: [],
      log: [],
    };
    const log = (m: string) => {
      const line = `${new Date().toISOString().slice(11, 19)} ${m}`;
      cycle.log.push(line);
      this.d.log?.(line);
    };
    store.upsertCycle(cycle);

    try {
      const exclude = new Set(
        store.bugs().filter((b) => !store.shouldProcess(b.id, cfg.maxAttemptsPerBug)).map((b) => b.id),
      );
      log(`[haiku] polling Azure DevOps (${exclude.size} bug(s) already handled)`);
      const { polled, bugs } = await this.d.poller.poll(exclude);
      cycle.polled = polled;
      const todo = bugs.filter((b) => store.shouldProcess(b.id, cfg.maxAttemptsPerBug)).slice(0, cfg.maxBugsPerCycle);
      cycle.newBugs = todo.length;
      cycle.skipped = bugs.length - todo.length;
      log(`[haiku] ${polled} assigned bug(s) in state ${cfg.azure.states.join("/")}, ${todo.length} to process now`);
      store.upsertCycle(cycle);

      for (const bug of todo) {
        const rec = await this.handleBug(bug, cycle.id, log);
        cycle.bugs.push(rec);
        store.recordBug(bug.id, bug.title, rec.outcome, cycle.id);
        store.upsertCycle(cycle);
      }
      cycle.status = "completed";
    } catch (e) {
      cycle.status = "failed";
      cycle.error = (e as Error).message;
      log(`cycle failed: ${cycle.error}`);
    }
    cycle.finishedAt = new Date().toISOString();
    store.upsertCycle(cycle);
    log(`cycle ${cycle.status}`);
    return cycle;
  }

  private async handleBug(bug: Bug, runId: string, log: (m: string) => void): Promise<BugRunRecord> {
    const rec: BugRunRecord = { bug, fixes: [], outcome: "error", startedAt: new Date().toISOString() };
    try {
      log(`[opus] investigating bug #${bug.id}: ${bug.title}`);
      const plan = await this.d.planner.plan(bug);
      rec.plan = plan;
      log(`[opus] plan for #${bug.id}: canFix=${plan.canFix} confidence=${plan.confidence} tasks=${plan.tasks.length}`);

      if (plan.canFix && plan.tasks.length) {
        for (const group of orderTasks(plan.tasks)) {
          const project = this.d.cfg.projects.find((p) => p.name === group.project);
          if (!project) {
            rec.fixes.push(skipped(group.project, group.order, "project not configured"));
            continue;
          }
          // A failed earlier step may leave later steps inconsistent; stop the chain.
          if (rec.fixes.some((f) => f.status === "failed")) {
            rec.fixes.push(skipped(group.project, group.order, "an earlier step failed"));
            continue;
          }
          log(`[sonnet] fixing #${bug.id} in ${project.name} (order ${group.order}, ${group.tasks.length} task(s))`);
          rec.fixes.push(await this.applyInProject(bug, plan, group.order, group.tasks, project));
        }
      }
      rec.outcome = outcomeFor(plan, rec.fixes);
      await this.comment(bug, rec, log);
    } catch (e) {
      rec.outcome = "error";
      rec.error = (e as Error).message;
      log(`bug #${bug.id} failed: ${rec.error}`);
    }
    rec.finishedAt = new Date().toISOString();
    log(`bug #${bug.id} → ${rec.outcome}`);
    return rec;
  }

  private async applyInProject(
    bug: Bug,
    plan: FixPlan,
    order: number,
    tasks: FixTask[],
    project: ProjectConfig,
  ): Promise<FixResult> {
    const { dataDir } = this.d.cfg;
    const top = project.applyMode === "overlay" ? null : await gitTopLevel(project.path);
    const useWorktree = project.applyMode === "worktree" || (project.applyMode === "auto" && top !== null);
    const base: FixResult = { project: project.name, order, status: "failed", mode: "overlay", location: "", filesChanged: [], summary: "" };

    try {
      let ws: Workspace;
      let wt: { path: string; branch: string } | undefined;
      let sub = "";
      if (useWorktree) {
        if (!top) throw new Error(`${project.path} is not a git repository (applyMode=worktree)`);
        sub = path.relative(top, project.path);
        const slug = `${safeName(path.basename(top))}-bug-${bug.id}`;
        wt = await createWorktree(top, path.join(dataDir, "worktrees"), slug, `ai-fix/bug-${bug.id}`);
        ws = new Workspace(project.name, path.join(wt.path, sub), { writable: true });
        Object.assign(base, { mode: "worktree", location: ws.root, branch: wt.branch });
      } else {
        const overlayDir = path.join(dataDir, "patches", `bug-${bug.id}`, safeName(project.name));
        fs.mkdirSync(overlayDir, { recursive: true });
        ws = new Workspace(project.name, project.path, { writable: true, overlayDir });
        Object.assign(base, { mode: "overlay", location: overlayDir });
      }

      const out = await this.d.fixer.fix(bug, plan, tasks, ws);
      const files = [...ws.changed];
      base.filesChanged = files;
      base.summary = out.summary;
      if (wt) {
        const repoFiles = files.map((f) => path.posix.join(sub.split(path.sep).join("/"), f));
        base.commit = await commitFiles(wt.path, repoFiles, `fix: bug #${bug.id} ${bug.title}\n\n${plan.rootCause}\n\n${bug.url}`);
      } else if (files.length) {
        fs.writeFileSync(
          path.join(base.location, "_FIX_NOTES.md"),
          `# Bug #${bug.id}: ${bug.title}\n\n${bug.url}\n\nCopy these files into ${project.path} after review.\n\n## Root cause\n${plan.rootCause}\n\n## Changes\n${out.summary}\n\n## Files\n${files.map((f) => `- ${f}`).join("\n")}\n`,
        );
      }
      base.status = out.status === "applied" && files.length > 0 ? "applied" : "failed";
      if (base.status === "failed") base.error = out.status === "blocked" ? "fixer reported blocked" : "no files changed";
      return base;
    } catch (e) {
      return { ...base, status: "failed", error: (e as Error).message };
    }
  }

  private async comment(bug: Bug, rec: BugRunRecord, log: (m: string) => void) {
    const { azure, cfg } = this.d;
    if (!azure || !cfg.azure.postComments || !rec.plan) return;
    const fixes = rec.fixes
      .map((f) => `<li><b>${esc(f.project)}</b>: ${f.status}${f.branch ? ` on branch <code>${esc(f.branch)}</code>` : ""}${f.commit ? ` (${f.commit})` : ""} — ${esc(f.summary || f.error || "")}</li>`)
      .join("");
    const html = `<p><b>bug-agent</b> (${rec.outcome})</p><p><b>Root cause:</b> ${esc(rec.plan.rootCause)}</p>${fixes ? `<ul>${fixes}</ul>` : ""}<p><b>Verify:</b> ${esc(rec.plan.testingNotes)}</p>`;
    try {
      await azure.addComment(bug.id, html);
      log(`posted comment on #${bug.id}`);
    } catch (e) {
      log(`could not comment on #${bug.id}: ${(e as Error).message}`);
    }
  }
}

const skipped = (project: string, order: number, why: string): FixResult => ({
  project, order, status: "skipped", mode: "overlay", location: "", filesChanged: [], summary: "", error: why,
});
const safeName = (s: string) => s.replace(/[^a-zA-Z0-9._-]+/g, "-");
const esc = (s: string) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
