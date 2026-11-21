---
name: cycle
description: Run one bug-agent cycle - pull New Azure DevOps Bugs and Tasks assigned to me with their parent user stories, plan each against the story's acceptance criteria with the Opus planner, and apply it with Sonnet fixers on fix/us-<story> (bugs) or feature/us-<story> (tasks) branches.
disable-model-invocation: true
argument-hint: "[work-item-id ...]"
---

# bug-agent cycle

Run one cycle end to end without stopping to ask questions; this often runs unattended on a schedule. Use the bug-agent `ado` MCP tools (`get_projects`, `get_bug`, `prepare_workdir`, `finalize_workdir`, `record_bug`, `add_comment`) and the plugin's three subagents. Do the investigation and editing only through the subagents; your job is to orchestrate and record.

A **work item** below is a Bug (to fix) or a Task (to implement); each has a `type`. Tool parameters named `bugId` take the work item id whatever its type.

Max work items this cycle: ${user_config.max_bugs_per_cycle}
Arguments (optional work item ids to process instead of polling): $ARGUMENTS

## 1. Load projects
Call `get_projects`. If the list is empty, say "No projects configured. Run /bug-agent:setup first." and stop. Note each project's `name`, `path`, `description`, and whether it `exists`; leave out projects that don't exist and mention them in the final report.

## 2. Intake (Haiku)
- If ids were passed as arguments, call `get_bug` for each instead of intake, and use those items (they already include `type` and `userStory`; use empty `summary` and `suspectedProjects`).
- Otherwise delegate to the **bug-agent:bug-intake** subagent with: the limit (max work items above) and the project list as `- name: description` lines. Take the items from the json block at the end of its reply.
- If intake reports an error (for example missing plugin options or a 401), stop and report it with the fix: `claude plugin configure bug-agent`.
- If there are no items, report "No new bugs or tasks" with the `matching` / `alreadyHandled` counts and stop.

## 3. Group by user story
Group the items by `userStory.id` (items where `userStory.found` is false form their own "no user story" group). Process the groups one at a time. Inside a group, do the Tasks first, then the Bugs, each in priority order and one at a time.

Branches, per git project (`prepare_workdir` handles all of this):
- Bugs of a story go on **one fix branch**. It's an existing `fix/...` branch containing the story number if there is one; otherwise a new `fix/us-<story>`.
- Tasks of a story go on **one feature branch**. It's an existing `feature/...` branch containing the story number; otherwise a new `feature/us-<story>`.
- Each item is one commit on its branch, so later items build on earlier ones.
- A new branch is cut from the base branch (UAT by default), and then the sync branch (master by default) is merged into it.
- Items without a story get `fix/bug-<id>` or `feature/task-<id>`.

## 4. For each work item

**a. Plan (Opus).** Delegate to **bug-agent:bug-planner**. Give it:
- the full item: id, **type**, title, link, priority, severity, area path, tags, intake summary, suspected projects, description, repro steps;
- the user story in full: id, type, title, state, link, description (requirements) and acceptance criteria, or the note saying why there is none;
- the project list with each project's absolute `path` and description.

Take the plan from its json block. If the reply has no valid plan, record the item as `error` (step d) and continue with the next one.

**b. Apply (Sonnet), in plan order.** If `canFix` is true and there are steps (`tasks`):
1. Group the steps by project. Order the groups by their lowest `order`; groups with the same order may run in parallel only if they are different projects.
2. For each group:
   - Call `prepare_workdir` with `bugId` (the item id), `project`, `itemType` (`Bug` or `Task`), `files` (every file in that group's steps) and `userStoryId` (leave it out when there is no story). It returns `mode`, `workdir`, `branch`, `branchSource` and `sourcePath`. Note `branchSource`, `otherMatchingBranches`, `warning` and `syncWarning` for the report.
   - If `prepare_workdir` fails, mark this step **failed** with its message. That happens when the branch is checked out somewhere with uncommitted changes, or when merging the sync branch into a new branch conflicts; in that case the new branch has already been removed.
   - Delegate to **bug-agent:bug-fixer** with: item type, workdir, sourcePath, mode, branch, the item title and link, the user story title and acceptance criteria, the plan's rootCause and storyAlignment, and that group's steps verbatim (order, files, instructions, reason).
   - Call `finalize_workdir` with bugId, project, title, url, rootCause, the fixer's summary and `userStoryTitle`. It commits on the branch (`fix:` for bugs, `feat:` for tasks), or writes `_FIX_NOTES.md` (overlay), and returns `filesChanged` and `commit`.
   - The step is **applied** if the fixer reported `applied` and `filesChanged` is not empty; otherwise **failed**.
   - If a step fails, mark the remaining groups for this item as **skipped** (a half-applied cross-project change is worse than none).

**c. Outcome.**
- `fixed`: every step applied. For a Task this means implemented.
- `partially_fixed`: some applied, some failed or skipped.
- `needs_human`: `canFix` false, no steps, or no step applied (including a sync-merge conflict).
- `error`: the planner or a tool failed unexpectedly.

**d. Record.** Call `record_bug` with:
- id, title, url, `itemType`, outcome, rootCause, confidence;
- `userStoryId` and `userStoryTitle`;
- notes: the plan's storyAlignment, testingNotes and risks;
- `fixes`: one object per project, with project, status, mode, branch, branchSource, commit, workdir, filesChanged and summary.

**e. Comment.** Call `add_comment` on the item with the outcome, the user story, the root cause or change, each project's branch and commit (or patch folder), and the testing notes. The tool skips this when comments are turned off; that's expected.

## 5. Report
Finish with a short report grouped by user story:
- A heading per story: `User story #id title` with its link. Under it, per project: the feature branch and the fix branch used, each marked as existing or new. For a new branch, give its base and what was synced into it.
- Under that, one line per item: `Task|Bug #id title — outcome`, then the link and the commit. For `needs_human`, add what a human should check.
- Items without a user story under their own heading, with their `fix/bug-<id>` / `feature/task-<id>` branches.
- Items deferred to the next cycle, projects skipped because their folder is missing, and any fetch or sync warnings.

Never push branches, merge into shared branches, or change a work item's state in Azure DevOps. The only merge allowed is the automatic sync into a newly created branch.
