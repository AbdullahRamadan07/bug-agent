import { describe, it, expect, beforeEach } from "vitest";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { Workspace, safeResolve, globToRegExp } from "../src/workspace.js";

let root: string;
beforeEach(() => {
  root = fs.mkdtempSync(path.join(os.tmpdir(), "ws-"));
  fs.mkdirSync(path.join(root, "src"));
  fs.mkdirSync(path.join(root, "node_modules/lib"), { recursive: true });
  fs.writeFileSync(path.join(root, "src/a.ts"), "export const a = 1;\r\nexport const b = 2;\r\n");
  fs.writeFileSync(path.join(root, "node_modules/lib/x.ts"), "export const a = 1;");
});

describe("safeResolve", () => {
  it("rejects paths that escape the root", () => {
    expect(() => safeResolve(root, "../secret")).toThrow(/escapes/);
    expect(() => safeResolve(root, "src/../../x")).toThrow(/escapes/);
  });
  it("treats leading slashes as project-relative", () => {
    expect(safeResolve(root, "/src/a.ts")).toBe(path.join(root, "src/a.ts"));
  });
});

describe("Workspace", () => {
  it("is read-only unless writable", async () => {
    const ws = new Workspace("p", root);
    await expect(ws.writeFile("x.txt", "hi")).rejects.toThrow(/read-only/);
  });

  it("reads with line numbers and skips ignored dirs in search", async () => {
    const ws = new Workspace("p", root);
    expect(await ws.readFile("src/a.ts")).toContain("    1\texport const a = 1;");
    const hits = await ws.search("const a");
    expect(hits).toContain("src/a.ts:1");
    expect(hits).not.toContain("node_modules");
    expect(await ws.listDir(".")).toBe("src/");
  });

  it("edits CRLF files with LF snippets and requires unique matches", async () => {
    const ws = new Workspace("p", root, { writable: true });
    await ws.editFile("src/a.ts", "export const a = 1;\nexport const b = 2;", "export const a = 10;\nexport const b = 2;");
    expect(fs.readFileSync(path.join(root, "src/a.ts"), "utf8")).toBe("export const a = 10;\r\nexport const b = 2;\r\n");
    await expect(ws.editFile("src/a.ts", "export", "x")).rejects.toThrow(/matches 2 places/);
    await expect(ws.editFile("src/a.ts", "nope", "x")).rejects.toThrow(/not found/);
    expect([...ws.changed]).toEqual(["src/a.ts"]);
  });

  it("overlay mode never touches the original project", async () => {
    const overlay = fs.mkdtempSync(path.join(os.tmpdir(), "ov-"));
    const ws = new Workspace("p", root, { writable: true, overlayDir: overlay });
    await ws.editFile("src/a.ts", "const a = 1", "const a = 99");
    expect(fs.readFileSync(path.join(root, "src/a.ts"), "utf8")).toContain("const a = 1;");
    expect(fs.readFileSync(path.join(overlay, "src/a.ts"), "utf8")).toContain("const a = 99;");
    expect(await ws.readFile("src/a.ts")).toContain("const a = 99"); // reads see the overlay
  });

  it("blocks writes to .git, .env and node_modules", async () => {
    const ws = new Workspace("p", root, { writable: true });
    await expect(ws.writeFile(".env", "X=1")).rejects.toThrow(/not allowed/);
    await expect(ws.writeFile(".git/config", "")).rejects.toThrow(/not allowed/);
    await expect(ws.writeFile("node_modules/lib/x.ts", "")).rejects.toThrow(/not allowed/);
    await expect(ws.writeFile("../escape.txt", "")).rejects.toThrow(/escapes/);
  });
});

describe("globToRegExp", () => {
  it("handles ** and braces", () => {
    expect(globToRegExp("**/*.ts").test("src/deep/a.ts")).toBe(true);
    expect(globToRegExp("*.{ts,java}").test("A.java")).toBe(true);
    expect(globToRegExp("*.ts").test("src/a.ts")).toBe(false);
  });
});
