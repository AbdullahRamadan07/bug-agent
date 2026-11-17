import fs from "node:fs";
import path from "node:path";
import type { BugOutcome, CycleRecord } from "./types.js";

export interface BugState {
  id: number;
  title: string;
  attempts: number;
  lastOutcome: BugOutcome;
  lastRunId: string;
  updatedAt: string;
}

interface StateFile {
  bugs: Record<string, BugState>;
  cycles: CycleRecord[];
}

const MAX_CYCLES = 100;

/** Tiny JSON-file store: remembers which bugs were handled and keeps run history for the dashboard. */
export class Store {
  private state: StateFile;
  private readonly file: string;

  constructor(dataDir: string) {
    fs.mkdirSync(dataDir, { recursive: true });
    this.file = path.join(dataDir, "state.json");
    this.state = fs.existsSync(this.file)
      ? (JSON.parse(fs.readFileSync(this.file, "utf8")) as StateFile)
      : { bugs: {}, cycles: [] };
  }

  private save() {
    const tmp = `${this.file}.tmp`;
    fs.writeFileSync(tmp, JSON.stringify(this.state, null, 2));
    fs.renameSync(tmp, this.file);
  }

  getBug(id: number): BugState | undefined {
    return this.state.bugs[String(id)];
  }

  /** A bug is processed again only if its last attempt errored and it has attempts left. */
  shouldProcess(id: number, maxAttempts: number): boolean {
    const b = this.getBug(id);
    if (!b) return true;
    return b.lastOutcome === "error" && b.attempts < maxAttempts;
  }

  recordBug(id: number, title: string, outcome: BugOutcome, runId: string) {
    const prev = this.getBug(id);
    this.state.bugs[String(id)] = {
      id,
      title,
      attempts: (prev?.attempts ?? 0) + 1,
      lastOutcome: outcome,
      lastRunId: runId,
      updatedAt: new Date().toISOString(),
    };
    this.save();
  }

  /** Forget a bug so the next cycle picks it up again (dashboard "retry"). */
  resetBug(id: number) {
    delete this.state.bugs[String(id)];
    this.save();
  }

  upsertCycle(c: CycleRecord) {
    const i = this.state.cycles.findIndex((x) => x.id === c.id);
    if (i >= 0) this.state.cycles[i] = c;
    else this.state.cycles.unshift(c);
    this.state.cycles = this.state.cycles.slice(0, MAX_CYCLES);
    this.save();
  }

  cycles(): CycleRecord[] {
    return this.state.cycles;
  }

  bugs(): BugState[] {
    return Object.values(this.state.bugs).sort((a, b) => b.updatedAt.localeCompare(a.updatedAt));
  }
}
