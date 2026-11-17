/**
 * Minimal Azure DevOps Boards REST client (api-version 7.1).
 * Auth: Personal Access Token with "Work Items (Read)" scope
 * (+ "Read & write" only if AZURE_DEVOPS_POST_COMMENTS=true).
 */
export interface AzureClientOptions {
  org: string;
  project: string;
  pat: string;
  fetchImpl?: typeof fetch;
}

export interface WorkItemSummary {
  id: number;
  title: string;
  state: string;
  url: string;
  priority: number | null;
  severity: string | null;
  description: string;
  reproSteps: string;
  tags: string;
  areaPath: string;
  assignedTo: string;
}

const API = "api-version=7.1";

export class AzureDevOpsClient {
  private readonly base: string;
  private readonly fetchImpl: typeof fetch;
  constructor(private readonly opts: AzureClientOptions) {
    this.base = `https://dev.azure.com/${encodeURIComponent(opts.org)}`;
    this.fetchImpl = opts.fetchImpl ?? fetch;
  }

  private headers() {
    const token = Buffer.from(`:${this.opts.pat}`).toString("base64");
    return { Authorization: `Basic ${token}`, "Content-Type": "application/json", Accept: "application/json" };
  }

  private async request<T>(url: string, init: RequestInit = {}): Promise<T> {
    const res = await this.fetchImpl(url, { ...init, headers: { ...this.headers(), ...(init.headers ?? {}) } });
    if (!res.ok) {
      const body = await res.text().catch(() => "");
      throw new Error(`Azure DevOps ${res.status} ${res.statusText} for ${url.split("?")[0]}: ${body.slice(0, 300)}`);
    }
    return (await res.json()) as T;
  }

  bugLink(id: number) {
    return `${this.base}/${encodeURIComponent(this.opts.project)}/_workitems/edit/${id}`;
  }

  static buildWiql(assignedTo: string, states: string[], project: string) {
    const q = (s: string) => `'${s.replace(/'/g, "''")}'`;
    const who = assignedTo.trim() === "@Me" ? "@Me" : q(assignedTo);
    const stateClause = states.map((s) => `[System.State] = ${q(s)}`).join(" OR ");
    return (
      `SELECT [System.Id] FROM WorkItems ` +
      `WHERE [System.TeamProject] = ${q(project)} ` +
      `AND [System.WorkItemType] = 'Bug' ` +
      `AND [System.AssignedTo] = ${who} ` +
      `AND (${stateClause}) ` +
      `ORDER BY [Microsoft.VSTS.Common.Priority] ASC, [System.CreatedDate] ASC`
    );
  }

  /** Returns the IDs of bugs assigned to the user in the given states. */
  async queryBugIds(assignedTo: string, states: string[], top = 50): Promise<number[]> {
    const url = `${this.base}/${encodeURIComponent(this.opts.project)}/_apis/wit/wiql?$top=${top}&${API}`;
    const body = { query: AzureDevOpsClient.buildWiql(assignedTo, states, this.opts.project) };
    const data = await this.request<{ workItems: { id: number }[] }>(url, { method: "POST", body: JSON.stringify(body) });
    return data.workItems.map((w) => w.id);
  }

  async getWorkItems(ids: number[]): Promise<WorkItemSummary[]> {
    if (ids.length === 0) return [];
    const fields = [
      "System.Id",
      "System.Title",
      "System.State",
      "System.Description",
      "System.Tags",
      "System.AreaPath",
      "System.AssignedTo",
      "Microsoft.VSTS.TCM.ReproSteps",
      "Microsoft.VSTS.Common.Priority",
      "Microsoft.VSTS.Common.Severity",
    ].join(",");
    const out: WorkItemSummary[] = [];
    for (let i = 0; i < ids.length; i += 200) {
      const chunk = ids.slice(i, i + 200);
      const url = `${this.base}/_apis/wit/workitems?ids=${chunk.join(",")}&fields=${fields}&errorPolicy=omit&${API}`;
      const data = await this.request<{ value: ({ id: number; fields: Record<string, unknown> } | null)[] }>(url);
      for (const w of data.value) {
        if (!w) continue;
        const f = w.fields;
        const assigned = f["System.AssignedTo"] as { displayName?: string } | string | undefined;
        out.push({
          id: w.id,
          title: String(f["System.Title"] ?? ""),
          state: String(f["System.State"] ?? ""),
          url: this.bugLink(w.id),
          priority: (f["Microsoft.VSTS.Common.Priority"] as number | undefined) ?? null,
          severity: (f["Microsoft.VSTS.Common.Severity"] as string | undefined) ?? null,
          description: stripHtml(String(f["System.Description"] ?? "")),
          reproSteps: stripHtml(String(f["Microsoft.VSTS.TCM.ReproSteps"] ?? "")),
          tags: String(f["System.Tags"] ?? ""),
          areaPath: String(f["System.AreaPath"] ?? ""),
          assignedTo: typeof assigned === "string" ? assigned : (assigned?.displayName ?? ""),
        });
      }
    }
    return out;
  }

  async addComment(id: number, html: string): Promise<void> {
    const url = `${this.base}/${encodeURIComponent(this.opts.project)}/_apis/wit/workItems/${id}/comments?api-version=7.1-preview.4`;
    await this.request(url, { method: "POST", body: JSON.stringify({ text: html }) });
  }
}

export function stripHtml(html: string): string {
  return html
    .replace(/<br\s*\/?>/gi, "\n")
    .replace(/<\/(p|div|li|h\d)>/gi, "\n")
    .replace(/<[^>]+>/g, "")
    .replace(/&nbsp;/g, " ")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&amp;/g, "&")
    .replace(/\n{3,}/g, "\n\n")
    .trim();
}
