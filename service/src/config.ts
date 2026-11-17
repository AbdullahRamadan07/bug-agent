import "dotenv/config";
import fs from "node:fs";
import path from "node:path";
import { z } from "zod";

export const MODELS = {
  planner: "claude-opus-5-5", // main agent — diagnoses and plans the fix
  fixer: "claude-sonnet-5-5", // sub-agents — apply the plan in each project
  poller: "claude-haiku-5-5", // sub-agent — checks Azure DevOps for new bugs
} as const;

const ProjectSchema = z.object({
  name: z.string().min(1),
  path: z.string().min(1),
  description: z.string().default(""),
  /**
   * How the fixer writes changes:
   *  - "worktree": git worktree on branch ai-fix/bug-<id> (your working copy is never touched)
   *  - "overlay":  changed files are written to data/patches/bug-<id>/<project>/ for review
   *  - "auto":     worktree if the project is a git repo, overlay otherwise
   */
  applyMode: z.enum(["auto", "worktree", "overlay"]).default("auto"),
});
export type ProjectConfig = z.infer<typeof ProjectSchema>;

const bool = (v: string | undefined, d: boolean) =>
  v === undefined || v === "" ? d : ["1", "true", "yes", "on"].includes(v.toLowerCase());

export interface AppConfig {
  mockMode: boolean;
  port: number;
  dataDir: string;
  cron: string;
  runOnStart: boolean;
  plannerEffort: "low" | "medium" | "high" | "xhigh" | "max";
  maxBugsPerCycle: number;
  maxAttemptsPerBug: number;
  azure: {
    org: string;
    project: string;
    pat: string;
    assignedTo: string; // "@Me" by default
    states: string[]; // ["New"]
    postComments: boolean;
  };
  projects: ProjectConfig[];
}

export function loadProjects(file: string): ProjectConfig[] {
  if (!fs.existsSync(file)) return [];
  const raw = JSON.parse(fs.readFileSync(file, "utf8"));
  return z.array(ProjectSchema).parse(raw).map((p) => ({ ...p, path: path.resolve(p.path) }));
}

export function loadConfig(env = process.env): AppConfig {
  const projectsFile = path.resolve(env.PROJECTS_FILE ?? "config/projects.json");
  return {
    mockMode: bool(env.MOCK_MODE, false),
    port: Number(env.PORT ?? 4000),
    dataDir: path.resolve(env.DATA_DIR ?? "data"),
    cron: env.CRON_SCHEDULE ?? "0 * * * *", // top of every hour
    runOnStart: bool(env.RUN_ON_START, true),
    plannerEffort: (env.PLANNER_EFFORT as AppConfig["plannerEffort"]) ?? "medium",
    maxBugsPerCycle: Number(env.MAX_BUGS_PER_CYCLE ?? 5),
    maxAttemptsPerBug: Number(env.MAX_ATTEMPTS_PER_BUG ?? 3),
    azure: {
      org: env.AZURE_DEVOPS_ORG ?? "",
      project: env.AZURE_DEVOPS_PROJECT ?? "",
      pat: env.AZURE_DEVOPS_PAT ?? "",
      assignedTo: env.AZURE_DEVOPS_ASSIGNED_TO || "@Me",
      states: (env.AZURE_DEVOPS_STATES ?? "New").split(",").map((s) => s.trim()).filter(Boolean),
      postComments: bool(env.AZURE_DEVOPS_POST_COMMENTS, false),
    },
    projects: loadProjects(projectsFile),
  };
}

export function validateLiveConfig(cfg: AppConfig): string[] {
  const missing: string[] = [];
  if (cfg.mockMode) return missing;
  if (!process.env.ANTHROPIC_API_KEY && !process.env.ANTHROPIC_AUTH_TOKEN) missing.push("ANTHROPIC_API_KEY");
  if (!cfg.azure.org) missing.push("AZURE_DEVOPS_ORG");
  if (!cfg.azure.project) missing.push("AZURE_DEVOPS_PROJECT");
  if (!cfg.azure.pat) missing.push("AZURE_DEVOPS_PAT");
  if (cfg.projects.length === 0) missing.push("config/projects.json (no projects)");
  return missing;
}
