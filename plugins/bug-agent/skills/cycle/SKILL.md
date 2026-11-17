---
name: cycle
description: Run one bug-agent cycle - pull New Azure DevOps bugs assigned to me, find each root cause with the Opus planner, and apply fixes with Sonnet fixers on review branches.
disable-model-invocation: true
argument-hint: "[bug-id ...]"
---

# bug-agent cycle

Run one cycle end to end without stopping to ask questions; this often runs unattended on a schedule. Use the bug-agent `ado` MCP tools (`get_projects`, `prepare_workdir`, `finalize_workdir`, `record_bug`, `add_comment`, `get_bug`) and the plugin's three subagents. Do the investigation and editing only through the subagents; your job is to orchestrate and record.

Max bugs this cycle: ${user_config.max_bugs_per_cycle}
Arguments (optional bug ids to process instead of polling): $ARGUMENTS

## 1. Load projects
Call `get_projects`. If the list is empty, say "No projects configured. Run /bug-agent:setup first." and stop. Note each project's `name`, `path`, `description`, and whether it `exists`; leave out projects that don't exist and mention them in the final report.

## 2. Intake (Haiku)
- If bug ids were passed as arguments, call `get_bug` for each instead of intake, and use those bugs (with empty `summary` and `suspectedProjects`).
- Otherwise delegate to the **bug-agent:bug-intake** subagent with: the limit (max bugs above) and the project list as `- name: description` lines. Take the bugs from the json block at the end of its reply.
- If intake reports an error (for example missing plugin options or a 401), stop and report it with the fix: `claude plugin configure bug-agent`.
- If there are no bugs, report "No new bugs" with the `matching` / `alreadyHandled` counts and stop.

## 3. For each bug, one at a time

**a. Plan (Opus).** Delegate to **bug-agent:bug-planner**. Give it the full bug (id, title, link, priority, severity, area path, tags, intake summary, suspected projects, description, repro steps) and the project list with each project's absolute `path` and description. Take the plan from its json block. If the reply has no valid plan, record the bug as `error` (step d) and continue with the next bug.

**b. Fix (Sonnet), in plan order.** If `canFix` is true and there are tasks:
1. Group tasks by project. Order the groups by their lowest `order`; groups with the same order may run in parallel.
2. For each group:
   - Call `prepare_workdir` with `bugId`, `project`, and `files` (every file listed in that group's tasks). It returns `mode`, `workdir`, `sourcePath` and, for git projects, `branch`.
   - Delegate to **bug-agent:bug-fixer** with: workdir, sourcePath, mode, the bug title and link, the plan's root cause, and that group's tasks verbatim (order, files, instructions, reason).
   - Call `finalize_workdir` with bugId, project, title, url, rootCause and the fixer's summary. It commits on the branch (git) or writes `_FIX_NOTES.md` (overlay) and returns `filesChanged`.
   - The step is **applied** if the fixer reported `applied` and `filesChanged` is not empty; otherwise **failed**.
   - If a step fails, mark the remaining groups for this bug as **skipped** (a half-applied cross-project fix is worse than none).

**c. Outcome.**
- `fixed`: every step applied.
- `partially_fixed`: some applied, some failed or skipped.
- `needs_human`: `canFix` false, no tasks, or no step applied.
- `error`: the planner or a tool failed unexpectedly.

**d. Record.** Call `record_bug` with id, title, url, outcome, rootCause, confidence, notes (the plan's testingNotes and risks), and `fixes` (one object per project: project, status, mode, branch, commit, workdir, filesChanged, summary).

**e. Comment.** Call `add_comment` on the bug with the outcome, root cause, each project's branch or patch folder, and the testing notes. The tool skips this when comments are turned off; that's expected.

## 4. Report
Finish with a short report:
- One line per bug: `#id title — outcome`, then the link.
- For each fix: project, and the branch plus commit (git) or the patch folder (overlay).
- For `needs_human`: the root cause or what a human should check.
- Bugs deferred to the next cycle, and any projects skipped because their folder is missing.

Never push branches, merge, or change a bug's state in Azure DevOps.
