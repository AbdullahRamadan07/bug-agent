import type Anthropic from "@anthropic-ai/sdk";
import { betaZodTool } from "@anthropic-ai/sdk/helpers/beta/zod";
import { z } from "zod";
import { MODELS } from "../config.js";
import type { Bug, FixPlan, FixTask } from "../types.js";
import type { Workspace } from "../workspace.js";
import { runAgent } from "./llm.js";
import { singleProjectTools } from "./tools.js";

export interface FixOutcome {
  status: "applied" | "blocked";
  summary: string;
}

export interface BugFixer {
  fix(bug: Bug, plan: FixPlan, tasks: FixTask[], ws: Workspace): Promise<FixOutcome>;
}

const SYSTEM = `You are an engineer agent. The lead engineer has diagnosed a bug and assigned you the part of the fix that lives in one project. Apply it.

How to work:
- Read each file before you edit it, and copy old_string exactly from what you read (without the line-number prefix).
- Make the changes the tasks describe and nothing else. Match the surrounding code style. If the plan's instructions don't match the code (for example the line moved), find the equivalent place and apply the same intent.
- You cannot run builds or tests, so re-read each edited region afterwards to check syntax, imports and types.
- If a task cannot be applied safely, stop and report it as blocked rather than guessing.

Finish by calling finish once with status and a short summary of what you changed.`;

/** Sonnet 5.5 sub-agent: applies the planner's tasks inside one project's workspace. */
export class SonnetFixer implements BugFixer {
  constructor(
    private readonly client: Anthropic,
    private readonly log: (m: string) => void = () => {},
  ) {}

  async fix(bug: Bug, plan: FixPlan, tasks: FixTask[], ws: Workspace): Promise<FixOutcome> {
    let outcome: FixOutcome | undefined;
    const finish = betaZodTool({
      name: "finish",
      description: "Report that you are done.",
      inputSchema: z.object({ status: z.enum(["applied", "blocked"]), summary: z.string() }),
      run: async (o) => {
        outcome = o;
        return "ok";
      },
    });

    const taskText = tasks
      .map(
        (t, i) =>
          `### Task ${i + 1} (order ${t.order})\nFiles: ${t.files.join(", ")}\nWhy: ${t.reason}\nInstructions:\n${t.instructions}`,
      )
      .join("\n\n");
    const prompt = `Project: ${ws.name}
Bug #${bug.id}: ${bug.title}
Root cause (from the lead engineer): ${plan.rootCause}

Your tasks in this project:
${taskText}`;

    await runAgent({
      client: this.client,
      model: MODELS.fixer,
      effort: "medium",
      system: SYSTEM,
      prompt,
      tools: [...singleProjectTools(ws), finish],
      maxIterations: 50,
      fallback: true,
      log: this.log,
    });

    if (!outcome) {
      return ws.changed.size
        ? { status: "applied", summary: "Fixer stopped without a report; files were changed." }
        : { status: "blocked", summary: "Fixer stopped without making changes." };
    }
    return outcome;
  }
}
