/**
 * Deterministic stand-ins for the three agents so the full workflow, store,
 * scheduler and dashboard can be previewed without API keys or Azure access.
 * They run against demo/sample-shop and always write in overlay mode.
 */
import type { BugPoller } from "./agents/poller.js";
import type { BugPlanner } from "./agents/planner.js";
import type { BugFixer } from "./agents/fixer.js";
import type { Bug, FixPlan } from "./types.js";

const SAMPLE: Bug[] = [
  {
    id: 4101,
    title: "Cart total ignores item quantity",
    url: "https://dev.azure.com/demo-org/demo-project/_workitems/edit/4101",
    state: "New",
    priority: 1,
    severity: "2 - High",
    description: "When a customer adds 3 of the same item the cart total only charges for one.",
    reproSteps: "1. Add item ($10) with quantity 3\n2. Open cart\nExpected $30, actual $10",
    tags: "cart; checkout",
    areaPath: "Shop\\Checkout",
    summary: "Cart total sums unit prices without multiplying by quantity.",
    suspectedProjects: ["sample-shop"],
  },
  {
    id: 4102,
    title: "Intermittent 502 from payment gateway",
    url: "https://dev.azure.com/demo-org/demo-project/_workitems/edit/4102",
    state: "New",
    priority: 2,
    severity: "3 - Medium",
    description: "Sometimes checkout fails with 502. Happens a few times a day.",
    reproSteps: "Not reproducible locally.",
    tags: "payments",
    areaPath: "Shop\\Payments",
    summary: "Gateway 502s in production; no code path identified from the report.",
    suspectedProjects: [],
  },
];

export class MockPoller implements BugPoller {
  async poll(exclude: Set<number>) {
    return { polled: SAMPLE.length, bugs: SAMPLE.filter((b) => !exclude.has(b.id)) };
  }
}

export class MockPlanner implements BugPlanner {
  async plan(bug: Bug): Promise<FixPlan> {
    await new Promise((r) => setTimeout(r, 400));
    if (bug.id === 4101) {
      return {
        bugId: bug.id,
        canFix: true,
        confidence: "high",
        rootCause: "cartTotal() in src/cart.js adds item.price but never multiplies by item.quantity.",
        tasks: [
          {
            order: 1,
            project: "sample-shop",
            files: ["src/cart.js"],
            instructions: "In cartTotal, change `total += item.price;` to `total += item.price * (item.quantity ?? 1);`",
            reason: "Line items with quantity > 1 are charged once.",
          },
        ],
        testingNotes: "Add an item with quantity 3 and confirm the cart total is 3× the unit price.",
        risks: "None; quantity defaults to 1 when missing.",
      };
    }
    return {
      bugId: bug.id,
      canFix: false,
      confidence: "low",
      rootCause: "No code in the configured projects calls a payment gateway; the 502 likely comes from infrastructure.",
      tasks: [],
      testingNotes: "Check gateway and load-balancer logs at the failure timestamps.",
      risks: "",
    };
  }
}

export class MockFixer implements BugFixer {
  async fix(_bug: Bug, _plan: FixPlan, _tasks: unknown, ws: Parameters<BugFixer["fix"]>[3]) {
    await new Promise((r) => setTimeout(r, 300));
    await ws.editFile("src/cart.js", "total += item.price;", "total += item.price * (item.quantity ?? 1);");
    return { status: "applied" as const, summary: "cartTotal now multiplies price by quantity (defaults to 1)." };
  }
}
