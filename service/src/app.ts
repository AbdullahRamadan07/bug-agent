import path from "node:path";
import Anthropic from "@anthropic-ai/sdk";
import type { AppConfig } from "./config.js";
import { AzureDevOpsClient } from "./azure.js";
import { Store } from "./store.js";
import { Workflow } from "./workflow.js";
import { HaikuBugPoller } from "./agents/poller.js";
import { OpusPlanner } from "./agents/planner.js";
import { SonnetFixer } from "./agents/fixer.js";
import { MockFixer, MockPlanner, MockPoller } from "./mock.js";

export function buildWorkflow(cfg: AppConfig, log: (m: string) => void = console.log) {
  const store = new Store(cfg.dataDir);

  if (cfg.mockMode) {
    const mockCfg: AppConfig = {
      ...cfg,
      projects: [
        {
          name: "sample-shop",
          path: path.resolve("demo/sample-shop"),
          description: "Demo storefront (mock mode)",
          applyMode: "overlay",
        },
      ],
    };
    return {
      store,
      cfg: mockCfg,
      workflow: new Workflow({ cfg: mockCfg, store, poller: new MockPoller(), planner: new MockPlanner(), fixer: new MockFixer(), log }),
    };
  }

  const client = new Anthropic({ maxRetries: 4 });
  const azure = new AzureDevOpsClient({ org: cfg.azure.org, project: cfg.azure.project, pat: cfg.azure.pat });
  const workflow = new Workflow({
    cfg,
    store,
    azure,
    log,
    poller: new HaikuBugPoller(client, azure, cfg.azure, cfg.projects, log),
    planner: new OpusPlanner(client, cfg.projects, cfg.plannerEffort, log),
    fixer: new SonnetFixer(client, log),
  });
  return { store, cfg, workflow };
}
