/**
 * Runs the real agent classes against a scripted Messages API, checking the
 * model/effort/fallback settings each agent sends and that tool calls are wired up.
 */
import { describe, it, expect } from "vitest";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { sseTurn, scriptedClient } from "./helpers.js";
import { OpusPlanner } from "../src/agents/planner.js";
import { SonnetFixer } from "../src/agents/fixer.js";
import { HaikuBugPoller } from "../src/agents/poller.js";
import { AzureDevOpsClient } from "../src/azure.js";
import { Workspace } from "../src/workspace.js";
import type { Bug, FixPlan } from "../src/types.js";

const bug: Bug = {
  id: 11, title: "Total wrong", url: "https://dev.azure.com/o/p/_workitems/edit/11", state: "New",
  description: "Total ignores quantity", reproSteps: "", tags: "", areaPath: "", summary: "", suspectedProjects: ["shop"],
};

function shopProject() {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "shop-"));
  fs.mkdirSync(path.join(root, "src"));
  fs.writeFileSync(path.join(root, "src/cart.js"), "function total(items){\n  let t=0;\n  for (const i of items) t += i.price;\n  return t;\n}\n");
  return { name: "shop", path: root, description: "storefront", applyMode: "overlay" as const };
}

const plan: FixPlan = {
  bugId: 0, canFix: true, confidence: "high", rootCause: "quantity ignored",
  tasks: [{ order: 1, project: "shop", files: ["src/cart.js"], instructions: "multiply by quantity", reason: "r" }],
  testingNotes: "add qty 3", risks: "",
};

describe("OpusPlanner (main agent)", () => {
  it("investigates with read tools and returns the submitted plan", async () => {
    const shop = shopProject();
    const { client, requests } = scriptedClient([
      sseTurn("claude-opus-5-5", [{ type: "tool_use", name: "search_code", input: { pattern: "price" } }]),
      sseTurn("claude-opus-5-5", [{ type: "tool_use", name: "read_file", input: { project: "shop", path: "src/cart.js" } }]),
      sseTurn("claude-opus-5-5", [{ type: "tool_use", name: "submit_fix_plan", input: plan }]),
      sseTurn("claude-opus-5-5", [{ type: "text", text: "Submitted." }]),
    ]);
    const out = await new OpusPlanner(client, [shop], "medium").plan(bug);

    expect(out).toMatchObject({ bugId: 11, canFix: true, rootCause: "quantity ignored" });
    expect(requests[0]).toMatchObject({
      model: "claude-opus-5-5",
      output_config: { effort: "medium" },
      fallbacks: "default",
      stream: true,
    });
    expect(requests[0].tools.map((t: { name: string }) => t.name)).toEqual(
      ["list_projects", "list_dir", "read_file", "search_code", "submit_fix_plan"],
    );
    // tool results were fed back (the runner uses message threads, so each
    // follow-up request carries only the new tool_result turn)
    expect(JSON.stringify(requests[1].messages)).toContain("src/cart.js:3");
    expect(JSON.stringify(requests[2].messages)).toContain("t += i.price");
  });

  it("rejects plans naming unknown projects so the model can resubmit", async () => {
    const shop = shopProject();
    const bad = { ...plan, tasks: [{ ...plan.tasks[0], project: "nope" }] };
    const { client, requests } = scriptedClient([
      sseTurn("claude-opus-5-5", [{ type: "tool_use", name: "submit_fix_plan", input: bad }]),
      sseTurn("claude-opus-5-5", [{ type: "tool_use", name: "submit_fix_plan", input: plan }]),
      sseTurn("claude-opus-5-5", [{ type: "text", text: "ok" }]),
    ]);
    const out = await new OpusPlanner(client, [shop], "medium").plan(bug);
    expect(out.tasks[0].project).toBe("shop");
    expect(JSON.stringify(requests[1].messages)).toContain("unknown project(s) nope");
  });

  it("fails when no plan is submitted", async () => {
    const { client } = scriptedClient([sseTurn("claude-opus-5-5", [{ type: "text", text: "I give up" }])]);
    await expect(new OpusPlanner(client, [shopProject()], "medium").plan(bug)).rejects.toThrow(/submit_fix_plan/);
  });
});

describe("SonnetFixer (sub-agent)", () => {
  it("edits files in its workspace and reports", async () => {
    const shop = shopProject();
    const overlay = fs.mkdtempSync(path.join(os.tmpdir(), "ov-"));
    const ws = new Workspace("shop", shop.path, { writable: true, overlayDir: overlay });
    const { client, requests } = scriptedClient([
      sseTurn("claude-sonnet-5-5", [{ type: "tool_use", name: "read_file", input: { path: "src/cart.js" } }]),
      sseTurn("claude-sonnet-5-5", [{ type: "tool_use", name: "edit_file", input: { path: "src/cart.js", old_string: "t += i.price;", new_string: "t += i.price * (i.quantity ?? 1);" } }]),
      sseTurn("claude-sonnet-5-5", [{ type: "tool_use", name: "finish", input: { status: "applied", summary: "multiplied by quantity" } }]),
      sseTurn("claude-sonnet-5-5", [{ type: "text", text: "done" }]),
    ]);
    const out = await new SonnetFixer(client).fix(bug, plan, plan.tasks, ws);
    expect(out).toEqual({ status: "applied", summary: "multiplied by quantity" });
    expect(fs.readFileSync(path.join(overlay, "src/cart.js"), "utf8")).toContain("i.price * (i.quantity ?? 1)");
    expect(requests[0]).toMatchObject({ model: "claude-sonnet-5-5", output_config: { effort: "medium" }, fallbacks: "default" });
    expect(requests[0].messages[0].content).toContain("multiply by quantity");
  });
});

describe("HaikuBugPoller (sub-agent)", () => {
  it("queries Azure via its tool, triages, and keeps Azure's own links", async () => {
    const fetchImpl = (async (url: string) =>
      url.includes("/wiql")
        ? Response.json({ workItems: [{ id: 11 }, { id: 12 }] })
        : Response.json({ value: [{ id: 12, fields: { "System.Title": "Login loop", "System.State": "New" } }] })) as typeof fetch;
    const azure = new AzureDevOpsClient({ org: "o", project: "p", pat: "x", fetchImpl });
    const { client, requests } = scriptedClient([
      sseTurn("claude-haiku-5-5", [{ type: "tool_use", name: "search_assigned_bugs", input: {} }]),
      sseTurn("claude-haiku-5-5", [{ type: "tool_use", name: "report_bugs", input: { bugs: [{ id: 12, summary: "Redirect loop on login", suspectedProjects: ["shop", "made-up"] }] } }]),
      sseTurn("claude-haiku-5-5", [{ type: "text", text: "done" }]),
    ]);
    const poller = new HaikuBugPoller(client, azure, { assignedTo: "@Me", states: ["New"] }, [shopProject()]);
    const { polled, bugs } = await poller.poll(new Set([11]));

    expect(polled).toBe(2);
    expect(bugs).toHaveLength(1); // #11 excluded as already handled
    expect(bugs[0]).toMatchObject({
      id: 12, title: "Login loop", url: "https://dev.azure.com/o/p/_workitems/edit/12",
      summary: "Redirect loop on login", suspectedProjects: ["shop"],
    });
    expect(requests[0]).toMatchObject({ model: "claude-haiku-5-5", output_config: { effort: "low" } });
    expect(requests[0].fallbacks).toBeUndefined();
  });
});
