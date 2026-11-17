import fs from "node:fs/promises";
import { existsSync } from "node:fs";
import path from "node:path";

const IGNORED_DIRS = new Set([
  ".git", "node_modules", "target", "dist", "build", ".angular", ".idea", ".vscode", ".next",
  "coverage", "out", "bin", "obj", ".gradle", ".mvn", "__pycache__", ".venv", "vendor",
]);
const MAX_FILE_BYTES = 512 * 1024;
const PROTECTED_WRITE = [/^\.git(\/|$)/, /(^|\/)\.env(\..*)?$/, /(^|\/)node_modules\//];

export class PathEscapeError extends Error {}

/** Resolve a model-supplied relative path, rejecting anything that escapes root. */
export function safeResolve(root: string, rel: string): string {
  const cleaned = rel.replace(/\\/g, "/").replace(/^\/+/, "");
  const target = path.resolve(root, cleaned);
  const relative = path.relative(root, target);
  if (relative === ".." || relative.startsWith(".." + path.sep) || path.isAbsolute(relative)) {
    throw new PathEscapeError(`path escapes the project root: ${rel}`);
  }
  return target;
}

const toPosix = (p: string) => p.split(path.sep).join("/");

/**
 * File access for one project. `root` is where reads come from.
 * In overlay mode writes go to `overlayDir` and reads prefer the overlay copy.
 */
export class Workspace {
  readonly changed = new Set<string>();
  constructor(
    readonly name: string,
    readonly root: string,
    readonly opts: { writable: boolean; overlayDir?: string } = { writable: false },
  ) {}

  private overlayPath(rel: string) {
    return this.opts.overlayDir ? safeResolve(this.opts.overlayDir, rel) : undefined;
  }

  async readFile(rel: string, startLine = 1, endLine?: number): Promise<string> {
    const ov = this.overlayPath(rel);
    const file = ov && existsSync(ov) ? ov : safeResolve(this.root, rel);
    const stat = await fs.stat(file).catch(() => null);
    if (!stat) throw new Error(`file not found: ${rel}`);
    if (stat.isDirectory()) throw new Error(`${rel} is a directory; use list_dir`);
    if (stat.size > MAX_FILE_BYTES * 4) throw new Error(`${rel} is too large (${stat.size} bytes)`);
    const lines = (await fs.readFile(file, "utf8")).split(/\r?\n/);
    const s = Math.max(1, startLine);
    const e = Math.min(lines.length, endLine ?? s + 399);
    const body = lines.slice(s - 1, e).map((l, i) => `${String(s + i).padStart(5)}\t${l}`).join("\n");
    const more = e < lines.length ? `\n... (${lines.length - e} more lines; call again with start_line=${e + 1})` : "";
    return `${rel} (lines ${s}-${e} of ${lines.length})\n${body}${more}`;
  }

  async rawRead(rel: string): Promise<string | null> {
    const ov = this.overlayPath(rel);
    const file = ov && existsSync(ov) ? ov : safeResolve(this.root, rel);
    return existsSync(file) ? fs.readFile(file, "utf8") : null;
  }

  async listDir(rel = "."): Promise<string> {
    const dir = safeResolve(this.root, rel);
    const entries = await fs.readdir(dir, { withFileTypes: true }).catch(() => null);
    if (!entries) throw new Error(`directory not found: ${rel}`);
    const rows = entries
      .filter((e) => !(e.isDirectory() && IGNORED_DIRS.has(e.name)))
      .sort((a, b) => Number(b.isDirectory()) - Number(a.isDirectory()) || a.name.localeCompare(b.name))
      .map((e) => (e.isDirectory() ? `${e.name}/` : e.name));
    return rows.length ? rows.join("\n") : "(empty)";
  }

  async search(pattern: string, opts: { glob?: string; ignoreCase?: boolean; max?: number } = {}): Promise<string> {
    let re: RegExp;
    try {
      re = new RegExp(pattern, opts.ignoreCase ? "i" : "");
    } catch {
      re = new RegExp(pattern.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"), opts.ignoreCase ? "i" : "");
    }
    const globRe = opts.glob ? globToRegExp(opts.glob) : null;
    const max = opts.max ?? 80;
    const hits: string[] = [];
    const walk = async (dir: string): Promise<void> => {
      if (hits.length >= max) return;
      const entries = await fs.readdir(dir, { withFileTypes: true }).catch(() => []);
      for (const e of entries) {
        if (hits.length >= max) return;
        const full = path.join(dir, e.name);
        if (e.isDirectory()) {
          if (!IGNORED_DIRS.has(e.name)) await walk(full);
          continue;
        }
        const rel = toPosix(path.relative(this.root, full));
        if (globRe && !globRe.test(rel) && !globRe.test(e.name)) continue;
        const stat = await fs.stat(full).catch(() => null);
        if (!stat || stat.size > MAX_FILE_BYTES) continue;
        const text = (await this.rawRead(rel)) ?? "";
        if (text.includes("\u0000")) continue; // binary
        const lines = text.split(/\r?\n/);
        for (let i = 0; i < lines.length && hits.length < max; i++) {
          if (re.test(lines[i])) hits.push(`${rel}:${i + 1}: ${lines[i].trim().slice(0, 200)}`);
        }
      }
    };
    await walk(this.root);
    if (!hits.length) return "no matches";
    return hits.join("\n") + (hits.length >= max ? `\n... (stopped at ${max} matches; narrow the pattern or glob)` : "");
  }

  private assertWritable(rel: string) {
    if (!this.opts.writable) throw new Error("this workspace is read-only");
    const posix = rel.replace(/\\/g, "/").replace(/^\/+/, "");
    if (PROTECTED_WRITE.some((r) => r.test(posix))) throw new Error(`writing to ${rel} is not allowed`);
  }

  private writeTarget(rel: string) {
    return this.overlayPath(rel) ?? safeResolve(this.root, rel);
  }

  async writeFile(rel: string, content: string): Promise<string> {
    this.assertWritable(rel);
    const target = this.writeTarget(rel);
    await fs.mkdir(path.dirname(target), { recursive: true });
    await fs.writeFile(target, content, "utf8");
    this.changed.add(rel.replace(/\\/g, "/"));
    return `wrote ${rel} (${content.split("\n").length} lines)`;
  }

  /** Exact string replacement; old_string must be unique unless replaceAll. */
  async editFile(rel: string, oldString: string, newString: string, replaceAll = false): Promise<string> {
    this.assertWritable(rel);
    const current = await this.rawRead(rel);
    if (current === null) throw new Error(`file not found: ${rel} (use write_file to create it)`);
    // Tolerate CRLF files when the model sends LF snippets.
    const crlf = current.includes("\r\n");
    const norm = (s: string) => (crlf ? s.replace(/\r?\n/g, "\r\n") : s);
    const oldS = norm(oldString);
    const newS = norm(newString);
    const count = current.split(oldS).length - 1;
    if (count === 0) throw new Error(`old_string not found in ${rel}; read the file again and copy the text exactly`);
    if (count > 1 && !replaceAll) throw new Error(`old_string matches ${count} places in ${rel}; add more context or set replace_all`);
    const updated = replaceAll ? current.split(oldS).join(newS) : current.replace(oldS, () => newS);
    await fs.mkdir(path.dirname(this.writeTarget(rel)), { recursive: true });
    await fs.writeFile(this.writeTarget(rel), updated, "utf8");
    this.changed.add(rel.replace(/\\/g, "/"));
    return `edited ${rel} (${count} replacement${count > 1 ? "s" : ""})`;
  }
}

export function globToRegExp(glob: string): RegExp {
  let re = "";
  for (let i = 0; i < glob.length; i++) {
    const c = glob[i];
    if (c === "*") {
      if (glob[i + 1] === "*") {
        re += ".*";
        i++;
        if (glob[i + 1] === "/") i++;
      } else re += "[^/]*";
    } else if (c === "?") re += "[^/]";
    else if (c === "{") {
      const end = glob.indexOf("}", i);
      re += "(" + glob.slice(i + 1, end).split(",").map((s) => s.replace(/[.+^$()|[\]\\]/g, "\\$&")).join("|") + ")";
      i = end;
    } else re += c.replace(/[.+^$()|[\]\\]/g, "\\$&");
  }
  return new RegExp(`^${re}$`);
}
