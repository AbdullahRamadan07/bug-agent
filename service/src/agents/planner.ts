import type Anthropic from "@anthropic-ai/sdk";
import { betaZodTool } from "@anthropic-ai/sdk/helpers/beta/zod";
import { MODELS, type AppConfig, type ProjectConfig } from "../config.js";
import { FixPlanSchema, type Bug, type FixPlan } from "../types.js";
import { Workspace } from "../workspace.js";
import { runAgent } from "./llm.js";
import { multiProjectReadTools } from "./tools.js";

export interface BugPlanner {
  plan(bug: Bug): Promise<FixPlan>;
}

const SYSTEM = `You are the lead engineer agent. For each bug you receive you find the root cause in the developer's projects and write a fix plan that other engineer agents will implement, one project at a time, in the order you give.

How to work:
- You have read-only access. Start from the bug's symptoms and the suspected projects, search for the relevant code, and read it. Follow the data flow across projects when the bug crosses a boundary (for example a frontend calling a backend API).
- Confirm the root cause in the code before planning. Every file you name must exist (read it) unless the task creates it.
- Keep the fix as small as the bug allows. No refactors, renames or unrelated cleanup.
- Split the work into tasks, one project per task. Give each task an order: changes that others depend on come first (database or backend contract before the frontend that consumes it). Tasks with the same order may run in any order.
- Write instructions an engineer can apply without re-investigating: the file, the exact place, and the new code or behaviour.
- If you cannot find the cause with reasonable confidence, or the fix needs a product decision, credentials, data migration or infrastructure change, set canFix to false, leave tasks empty, and explain what a human should check.

Finish by calling submit_fix_plan exactly once.`;

/** Opus 5.5 main agent: investigates every project read-only and returns an ordered fix plan. */
export class OpusPlanner implements BugPlanner {
  private readonly workspaces: Map<string, Workspace>;
  constructor(
    private readonly client: Anthropic,
    private readonly projects: ProjectConfig[],
    private readonly effort: AppConfig["plannerEffort"],
    private readonly log: (m: string) => void = () => {},
  ) {
    this.workspaces = new Map(projects.map((p) => [p.name, new Workspace(p.name, p.path)]));
  }

  async plan(bug: Bug): Promise<FixPlan> {
    let submitted: FixPlan | undefined;
    const submit = betaZodTool({
      name: "submit_fix_plan",
      description: "Submit the final fix plan for this bug. Call once, at the end.",
      inputSchema: FixPlanSchema,
      run: async (plan) => {
        const known = new Set(this.projects.map((p) => p.name));
        const unknown = plan.tasks.filter((t) => !known.has(t.project)).map((t) => t.project);
        if (unknown.length) return `ERROR: unknown project(s) ${unknown.join(", ")}. Use names from list_projects and submit again.`;
        submitted = { ...plan, bugId: bug.id };
        return "Plan recorded.";
      },
    });

    const describe = (n: string) => this.projects.find((p) => p.name === n)?.description || "(no description)";
    const prompt = [
      `Bug #${bug.id}: ${bug.title}`,
      `Link: ${bug.url}`,
      `Priority: ${bug.priority ?? "?"}  Severity: ${bug.severity ?? "?"}  Area: ${bug.areaPath}  Tags: ${bug.tags}`,
      bug.summary && `Intake summary: ${bug.summary}`,
      bug.suspectedProjects.length && `Suspected projects: ${bug.suspectedProjects.join(", ")}`,
      `\nDescription:\n${bug.description || "(none)"}`,
      `\nRepro steps:\n${bug.reproSteps || "(none)"}`,
      `\nProjects:\n${this.projects.map((p) => `- ${p.name}: ${describe(p.name)}`).join("\n")}`,
    ]
      .filter(Boolean)
      .join("\n");

    await runAgent({
      client: this.client,
      model: MODELS.planner,
      effort: this.effort,
      system: SYSTEM,
      prompt,
      tools: [...multiProjectReadTools(this.workspaces, describe), submit],
      maxIterations: 60,
      fallback: true,
      log: this.log,
    });

    if (!submitted) throw new Error("planner finished without calling submit_fix_plan");
    return submitted;
  }
}
