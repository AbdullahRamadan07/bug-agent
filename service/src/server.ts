import express, { type NextFunction, type Request, type Response } from "express";
import path from "node:path";
import { timingSafeEqual } from "node:crypto";
import { MODELS, type AppConfig } from "./config.js";
import type { Store } from "./store.js";
import type { Workflow } from "./workflow.js";

export interface ServerDeps {
  cfg: AppConfig;
  store: Store;
  workflow: Workflow;
  nextRun: () => Date | null;
  missingConfig: string[];
}

function basicAuth(user: string, pass: string) {
  const expected = Buffer.from(`Basic ${Buffer.from(`${user}:${pass}`).toString("base64")}`);
  return (req: Request, res: Response, next: NextFunction) => {
    if (req.path === "/health") return next();
    const got = Buffer.from(req.headers.authorization ?? "");
    if (got.length === expected.length && timingSafeEqual(got, expected)) return next();
    res.set("WWW-Authenticate", 'Basic realm="bug-agent"').status(401).send("Authentication required");
  };
}

export function createServer(d: ServerDeps) {
  const app = express();
  app.use(express.json());
  const { DASHBOARD_USER, DASHBOARD_PASSWORD } = process.env;
  if (DASHBOARD_USER && DASHBOARD_PASSWORD) app.use(basicAuth(DASHBOARD_USER, DASHBOARD_PASSWORD));

  app.get("/health", (_req, res) => {
    res.json({ ok: true, running: d.workflow.running });
  });

  app.get("/api/status", (_req, res) => {
    res.json({
      mockMode: d.cfg.mockMode,
      running: d.workflow.running,
      schedule: d.cfg.cron,
      nextRun: d.nextRun()?.toISOString() ?? null,
      models: { main: `${MODELS.planner} (effort ${d.cfg.plannerEffort})`, fixer: MODELS.fixer, poller: MODELS.poller },
      azure: {
        org: d.cfg.azure.org || null,
        project: d.cfg.azure.project || null,
        states: d.cfg.azure.states,
        assignedTo: d.cfg.azure.assignedTo,
        postComments: d.cfg.azure.postComments,
      },
      projects: d.cfg.projects.map((p) => ({ name: p.name, path: p.path, applyMode: p.applyMode, description: p.description })),
      missingConfig: d.missingConfig,
    });
  });

  app.get("/api/cycles", (_req, res) => {
    res.json(d.store.cycles());
  });
  app.get("/api/bugs", (_req, res) => {
    res.json(d.store.bugs());
  });

  app.post("/api/run", (_req, res) => {
    if (d.missingConfig.length) {
      res.status(400).json({ error: `missing configuration: ${d.missingConfig.join(", ")}` });
      return;
    }
    const already = d.workflow.running;
    void d.workflow.runCycle("manual");
    res.status(202).json({ started: !already, running: true });
  });

  app.post("/api/bugs/:id/reset", (req, res) => {
    d.store.resetBug(Number(req.params.id));
    res.json({ ok: true });
  });

  app.use(express.static(path.resolve("public")));
  return app;
}
