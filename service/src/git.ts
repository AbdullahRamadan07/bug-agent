import { execFile } from "node:child_process";
import { promisify } from "node:util";
import { existsSync } from "node:fs";
import fs from "node:fs/promises";
import path from "node:path";

const run = promisify(execFile);

async function git(cwd: string, args: string[]) {
  const { stdout } = await run("git", args, { cwd, maxBuffer: 10 * 1024 * 1024 });
  return stdout.trim();
}

/** Returns the repository root containing `dir`, or null if it is not inside a git repo. */
export async function gitTopLevel(dir: string): Promise<string | null> {
  try {
    return path.resolve(await git(dir, ["rev-parse", "--show-toplevel"]));
  } catch {
    return null;
  }
}

/**
 * Creates (or reuses) a git worktree on a fresh branch so the fixer never
 * touches the developer's checkout. Returns the worktree path and branch.
 */
export async function createWorktree(repo: string, worktreesDir: string, slug: string, branchBase: string) {
  await fs.mkdir(worktreesDir, { recursive: true });
  const wtPath = path.join(worktreesDir, slug);
  let branch = branchBase;
  if (existsSync(wtPath)) {
    branch = await git(wtPath, ["rev-parse", "--abbrev-ref", "HEAD"]);
    return { path: wtPath, branch };
  }
  const exists = await git(repo, ["branch", "--list", branch]);
  if (exists) branch = `${branchBase}-${Date.now().toString(36)}`;
  await git(repo, ["worktree", "add", "-b", branch, wtPath, "HEAD"]);
  return { path: wtPath, branch };
}

/** Commits only the given files in the worktree. Returns the commit sha, or undefined if nothing changed. */
export async function commitFiles(wtPath: string, files: string[], message: string): Promise<string | undefined> {
  if (files.length === 0) return undefined;
  await git(wtPath, ["add", "--", ...files]);
  const staged = await git(wtPath, ["diff", "--cached", "--name-only"]);
  if (!staged) return undefined;
  await git(wtPath, [
    "-c", "user.name=bug-agent", "-c", "user.email=bug-agent@localhost",
    "commit", "-m", message,
  ]);
  return git(wtPath, ["rev-parse", "--short", "HEAD"]);
}
