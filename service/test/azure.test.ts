import { describe, it, expect } from "vitest";
import { AzureDevOpsClient, stripHtml } from "../src/azure.js";

describe("AzureDevOpsClient", () => {
  it("builds a WIQL query for New bugs assigned to @Me", () => {
    const q = AzureDevOpsClient.buildWiql("@Me", ["New"], "My Project");
    expect(q).toContain("[System.WorkItemType] = 'Bug'");
    expect(q).toContain("[System.AssignedTo] = @Me");
    expect(q).toContain("([System.State] = 'New')");
    expect(q).toContain("[System.TeamProject] = 'My Project'");
  });

  it("quotes named users and escapes apostrophes", () => {
    const q = AzureDevOpsClient.buildWiql("Dan O'Neil", ["New", "Active"], "P");
    expect(q).toContain("[System.AssignedTo] = 'Dan O''Neil'");
    expect(q).toContain("[System.State] = 'New' OR [System.State] = 'Active'");
  });

  it("queries ids then fetches details with PAT auth and builds bug links", async () => {
    const calls: { url: string; init?: RequestInit }[] = [];
    const fetchImpl = (async (url: string, init?: RequestInit) => {
      calls.push({ url, init });
      if (url.includes("/wiql")) return Response.json({ workItems: [{ id: 7 }, { id: 9 }] });
      return Response.json({
        value: [
          { id: 7, fields: { "System.Title": "Crash", "System.State": "New", "System.Description": "<p>Boom&nbsp;<b>now</b></p>",
            "Microsoft.VSTS.Common.Priority": 1, "System.AssignedTo": { displayName: "Me" } } },
          null,
        ],
      });
    }) as typeof fetch;
    const c = new AzureDevOpsClient({ org: "acme", project: "Shop App", pat: "secret", fetchImpl });

    const ids = await c.queryBugIds("@Me", ["New"]);
    expect(ids).toEqual([7, 9]);
    expect(calls[0].url).toBe("https://dev.azure.com/acme/Shop%20App/_apis/wit/wiql?$top=50&api-version=7.1");
    expect((calls[0].init!.headers as Record<string, string>).Authorization).toBe(
      "Basic " + Buffer.from(":secret").toString("base64"),
    );

    const items = await c.getWorkItems(ids);
    expect(calls[1].url).toContain("/_apis/wit/workitems?ids=7,9&");
    expect(items).toHaveLength(1);
    expect(items[0]).toMatchObject({
      id: 7, title: "Crash", priority: 1, description: "Boom now", assignedTo: "Me",
      url: "https://dev.azure.com/acme/Shop%20App/_workitems/edit/7",
    });
  });

  it("surfaces HTTP errors with status", async () => {
    const fetchImpl = (async () => new Response("bad token", { status: 401, statusText: "Unauthorized" })) as unknown as typeof fetch;
    const c = new AzureDevOpsClient({ org: "a", project: "b", pat: "x", fetchImpl });
    await expect(c.queryBugIds("@Me", ["New"])).rejects.toThrow(/401 Unauthorized/);
  });
});

describe("stripHtml", () => {
  it("converts Azure rich text to plain text", () => {
    expect(stripHtml("<div>a</div><div>b &amp; c<br>d</div>")).toBe("a\nb & c\nd");
  });
});
