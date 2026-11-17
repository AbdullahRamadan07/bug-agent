import cron from "node-cron";
import { loadConfig, validateLiveConfig } from "./config.js";
import { buildWorkflow } from "./app.js";
import { createServer } from "./server.js";

if (process.argv.includes("--mock")) {
  process.env.MOCK_MODE = "true";
  process.env.DATA_DIR ??= "data/mock"; // keep demo history apart from real runs
}
const baseCfg = loadConfig();
const { cfg, store, workflow } = buildWorkflow(baseCfg, (m) => console.log(m));
const missing = validateLiveConfig(cfg);

if (missing.length) {
  console.warn(`[bug-agent] not running cycles — missing configuration: ${missing.join(", ")}`);
  console.warn("[bug-agent] set them in .env (see .env.example) or start with MOCK_MODE=true for a demo.");
}

if (!cron.validate(cfg.cron)) throw new Error(`invalid CRON_SCHEDULE: ${cfg.cron}`);
const task = cron.schedule(cfg.cron, () => {
  if (missing.length) return;
  void workflow.runCycle("schedule");
});

const app = createServer({
  cfg,
  store,
  workflow,
  missingConfig: missing,
  nextRun: () => task.getNextRun(),
});

const server = app.listen(cfg.port, () => {
  console.log(`[bug-agent] ${cfg.mockMode ? "MOCK MODE — " : ""}dashboard on http://localhost:${cfg.port}`);
  console.log(`[bug-agent] schedule "${cfg.cron}", ${cfg.projects.length} project(s)`);
  if (cfg.runOnStart && !missing.length) void workflow.runCycle("startup");
});

const shutdown = () => {
  console.log("[bug-agent] shutting down");
  task.stop();
  server.close(() => process.exit(0));
  setTimeout(() => process.exit(0), 5000).unref();
};
process.on("SIGINT", shutdown);
process.on("SIGTERM", shutdown);
