import { betaZodTool } from "@anthropic-ai/sdk/helpers/beta/zod";
import { z } from "zod";
import type { Workspace } from "../workspace.js";

/** Wrap a tool body so errors come back to the model as text instead of crashing the run. */
export const safe =
  <A>(fn: (a: A) => Promise<string>) =>
  async (a: A) => {
    try {
      return await fn(a);
    } catch (e) {
      return `ERROR: ${(e as Error).message}`;
    }
  };

/** Read-only tools over several projects (used by the Opus planner). */
export function multiProjectReadTools(workspaces: Map<string, Workspace>, describe: (name: string) => string) {
  const ws = (name: string) => {
    const w = workspaces.get(name);
    if (!w) throw new Error(`unknown project "${name}". Known: ${[...workspaces.keys()].join(", ")}`);
    return w;
  };
  return [
    betaZodTool({
      name: "list_projects",
      description: "List the projects you can inspect, with their descriptions.",
      inputSchema: z.object({}),
      run: safe(async () => [...workspaces.keys()].map((n) => `- ${n}: ${describe(n)}`).join("\n")),
    }),
    betaZodTool({
      name: "list_dir",
      description: "List a directory inside a project (build/vendor folders are hidden).",
      inputSchema: z.object({ project: z.string(), path: z.string().default(".") }),
      run: safe(async ({ project, path }) => ws(project).listDir(path)),
    }),
    betaZodTool({
      name: "read_file",
      description: "Read a file from a project with line numbers (up to 400 lines per call).",
      inputSchema: z.object({
        project: z.string(),
        path: z.string().describe("Project-relative path"),
        start_line: z.number().int().min(1).default(1),
        end_line: z.number().int().optional(),
      }),
      run: safe(async (a) => ws(a.project).readFile(a.path, a.start_line, a.end_line)),
    }),
    betaZodTool({
      name: "search_code",
      description:
        "Regex search across source files. Omit `project` to search every project. Use `glob` like '**/*.ts' or '*.java' to narrow.",
      inputSchema: z.object({
        pattern: z.string(),
        project: z.string().optional(),
        glob: z.string().optional(),
        ignore_case: z.boolean().default(true),
      }),
      run: safe(async (a) => {
        const targets = a.project ? [ws(a.project)] : [...workspaces.values()];
        const parts = await Promise.all(
          targets.map(async (w) => `## ${w.name}\n${await w.search(a.pattern, { glob: a.glob, ignoreCase: a.ignore_case, max: 40 })}`),
        );
        return parts.join("\n\n");
      }),
    }),
  ];
}

/** Read + write tools over one project (used by the Sonnet fixers). */
export function singleProjectTools(w: Workspace) {
  return [
    betaZodTool({
      name: "list_dir",
      description: "List a directory in the project.",
      inputSchema: z.object({ path: z.string().default(".") }),
      run: safe(async ({ path }) => w.listDir(path)),
    }),
    betaZodTool({
      name: "read_file",
      description: "Read a file with line numbers (up to 400 lines per call). Always read before editing.",
      inputSchema: z.object({
        path: z.string(),
        start_line: z.number().int().min(1).default(1),
        end_line: z.number().int().optional(),
      }),
      run: safe(async (a) => w.readFile(a.path, a.start_line, a.end_line)),
    }),
    betaZodTool({
      name: "search_code",
      description: "Regex search in the project's source files.",
      inputSchema: z.object({ pattern: z.string(), glob: z.string().optional(), ignore_case: z.boolean().default(true) }),
      run: safe(async (a) => w.search(a.pattern, { glob: a.glob, ignoreCase: a.ignore_case })),
    }),
    betaZodTool({
      name: "edit_file",
      description:
        "Replace an exact string in a file. old_string must match the file exactly (without the line-number prefix) and be unique unless replace_all is true.",
      inputSchema: z.object({
        path: z.string(),
        old_string: z.string().min(1),
        new_string: z.string(),
        replace_all: z.boolean().default(false),
      }),
      run: safe(async (a) => w.editFile(a.path, a.old_string, a.new_string, a.replace_all)),
    }),
    betaZodTool({
      name: "write_file",
      description: "Create a new file (or fully replace a small one). Prefer edit_file for existing files.",
      inputSchema: z.object({ path: z.string(), content: z.string() }),
      run: safe(async (a) => w.writeFile(a.path, a.content)),
    }),
  ];
}
