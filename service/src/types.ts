import { z } from "zod";

/** A bug as reported by the Haiku poller. */
export const BugSchema = z.object({
  id: z.number().int(),
  title: z.string(),
  url: z.string(),
  state: z.string(),
  priority: z.number().nullable().optional(),
  severity: z.string().nullable().optional(),
  description: z.string().default(""),
  reproSteps: z.string().default(""),
  tags: z.string().default(""),
  areaPath: z.string().default(""),
  /** Haiku's short triage summary and guess of affected projects (hints only). */
  summary: z.string().default(""),
  suspectedProjects: z.array(z.string()).default([]),
});
export type Bug = z.infer<typeof BugSchema>;

/** One ordered step of the fix, scoped to a single project. */
export const FixTaskSchema = z.object({
  order: z.number().int().describe("Execution order, 1 = first. Lower orders run before higher ones."),
  project: z.string().describe("Exact project name from list_projects"),
  files: z.array(z.string()).describe("Project-relative paths to change or create"),
  instructions: z
    .string()
    .describe("Precise change to make: what to edit, where, and the intended new behaviour. Include code where useful."),
  reason: z.string().describe("Why this change fixes (part of) the bug"),
});
export type FixTask = z.infer<typeof FixTaskSchema>;

export const FixPlanSchema = z.object({
  bugId: z.number().int(),
  canFix: z.boolean().describe("false when the root cause could not be found with confidence or needs a human"),
  confidence: z.enum(["high", "medium", "low"]),
  rootCause: z.string(),
  tasks: z.array(FixTaskSchema),
  testingNotes: z.string().describe("How a human should verify the fix"),
  risks: z.string().default(""),
});
export type FixPlan = z.infer<typeof FixPlanSchema>;

export interface FixResult {
  project: string;
  order: number;
  status: "applied" | "failed" | "skipped";
  mode: "worktree" | "overlay";
  location: string; // worktree path or overlay folder
  branch?: string;
  commit?: string;
  filesChanged: string[];
  summary: string;
  error?: string;
}

export type BugOutcome = "fixed" | "partially_fixed" | "needs_human" | "error";

export interface BugRunRecord {
  bug: Bug;
  plan?: FixPlan;
  fixes: FixResult[];
  outcome: BugOutcome;
  error?: string;
  startedAt: string;
  finishedAt?: string;
}

export interface CycleRecord {
  id: string;
  trigger: "schedule" | "manual" | "startup";
  startedAt: string;
  finishedAt?: string;
  status: "running" | "completed" | "failed";
  polled: number;
  newBugs: number;
  skipped: number;
  bugs: BugRunRecord[];
  error?: string;
  log: string[];
}
