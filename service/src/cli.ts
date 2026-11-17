/** Run a single cycle and exit — useful for cron/Task Scheduler/CI instead of the long-running server. */
import { loadConfig, validateLiveConfig } from "./config.js";
import { buildWorkflow } from "./app.js";

const { cfg, workflow } = buildWorkflow(loadConfig(), (m) => console.log(m));
const missing = validateLiveConfig(cfg);
if (missing.length) {
  console.error(`missing configuration: ${missing.join(", ")}`);
  process.exit(2);
}
const cycle = await workflow.runCycle("manual");
for (const b of cycle.bugs) {
  console.log(`#${b.bug.id} ${b.outcome} — ${b.bug.url}`);
  for (const f of b.fixes) console.log(`   ${f.project}: ${f.status} ${f.branch ?? f.location}`);
}
process.exit(cycle.status === "completed" ? 0 : 1);
