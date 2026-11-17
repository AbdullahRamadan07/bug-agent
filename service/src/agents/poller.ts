import type Anthropic from "@anthropic-ai/sdk";
import { betaZodTool } from "@anthropic-ai/sdk/helpers/beta/zod";
import { z } from "zod";
import { MODELS, type ProjectConfig } from "../config.js";
import type { AzureDevOpsClient, WorkItemSummary } from "../azure.js";
import type { Bug } from "../types.js";
import { runAgent } from "./llm.js";
import { safe } from "./tools.js";

export interface BugPoller {
  poll(excludeIds: Set<number>): Promise<{ polled: number; bugs: Bug[] }>;
}

const SYSTEM = `You are the bug intake agent. Every hour you check Azure DevOps for bugs assigned to the developer that are still in state New, and hand them to the lead engineer agent.

Steps:
1. Call search_assigned_bugs once.
2. Call report_bugs once with one entry per bug it returned (skip none). For each bug write a 1-2 sentence summary of the symptom, and list the project names (from the list you were given) that most likely contain the cause; leave the list empty if you cannot tell.
3. Reply "done".`;

/** Haiku 5.5 sub-agent: pulls New bugs assigned to the user and triages them. */
export class HaikuBugPoller implements BugPoller {
  constructor(
    private readonly client: Anthropic,
    private readonly azure: AzureDevOpsClient,
    private readonly azureCfg: { assignedTo: string; states: string[] },
    private readonly projects: ProjectConfig[],
    private readonly log: (m: string) => void = () => {},
  ) {}

  async poll(excludeIds: Set<number>) {
    let items: WorkItemSummary[] = [];
    let polled = 0;
    const triage = new Map<number, { summary: string; suspectedProjects: string[] }>();

    const search = betaZodTool({
      name: "search_assigned_bugs",
      description: "Query Azure DevOps for bugs assigned to the developer in the configured states (already-handled bugs are filtered out).",
      inputSchema: z.object({}),
      run: safe(async () => {
        const ids = await this.azure.queryBugIds(this.azureCfg.assignedTo, this.azureCfg.states);
        polled = ids.length;
        const fresh = ids.filter((id) => !excludeIds.has(id));
        items = await this.azure.getWorkItems(fresh);
        if (!items.length) return "No new bugs.";
        return JSON.stringify(
          items.map((i) => ({
            id: i.id,
            title: i.title,
            priority: i.priority,
            severity: i.severity,
            areaPath: i.areaPath,
            tags: i.tags,
            description: i.description.slice(0, 1500),
            reproSteps: i.reproSteps.slice(0, 1500),
          })),
          null,
          1,
        );
      }),
    });

    const report = betaZodTool({
      name: "report_bugs",
      description: "Hand the triaged bugs to the lead engineer agent.",
      inputSchema: z.object({
        bugs: z.array(
          z.object({ id: z.number().int(), summary: z.string(), suspectedProjects: z.array(z.string()) }),
        ),
      }),
      run: async ({ bugs }) => {
        for (const b of bugs) triage.set(b.id, { summary: b.summary, suspectedProjects: b.suspectedProjects });
        return `Received ${bugs.length} bug(s).`;
      },
    });

    const projectList = this.projects.map((p) => `- ${p.name}: ${p.description || "(no description)"}`).join("\n");
    await runAgent({
      client: this.client,
      model: MODELS.poller,
      effort: "low",
      system: SYSTEM,
      prompt: `Projects:\n${projectList}\n\nCheck for new bugs now.`,
      tools: [search, report],
      maxIterations: 6,
      log: this.log,
    });

    // Links and fields come straight from Azure, never from model output.
    const known = new Set(this.projects.map((p) => p.name));
    const bugs: Bug[] = items.map((i) => {
      const t = triage.get(i.id);
      return {
        id: i.id,
        title: i.title,
        url: i.url,
        state: i.state,
        priority: i.priority,
        severity: i.severity,
        description: i.description,
        reproSteps: i.reproSteps,
        tags: i.tags,
        areaPath: i.areaPath,
        summary: t?.summary ?? "",
        suspectedProjects: (t?.suspectedProjects ?? []).filter((p) => known.has(p)),
      };
    });
    return { polled, bugs };
  }
}
