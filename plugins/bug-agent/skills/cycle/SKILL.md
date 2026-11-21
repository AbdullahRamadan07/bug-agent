---
name: cycle
description: Run one bug-agent cycle - take one user story, fix all its New Bugs assigned to me on fix/us-<story> (or implement its Tasks on feature/us-<story>) live in my project folders, leave the changes uncommitted for my review, review them with the Opus reviewer, save a report outside the projects, and alert me in 15 minutes.
disable-model-invocation: true
argument-hint: "[user-story-id]"
---

# bug-agent cycle

Run one cycle end to end without stopping to ask questions; this often runs unattended on a schedule. Use the bug-agent `ado` MCP tools and the plugin's four subagents. Do the investigation, editing and review only through the subagents; your job is to orchestrate, record and report.

**One unit of work per cycle:** one user story and one type. Either all of the story's New Bugs assigned to me, fixed on one `fix/` branch, or all of its New Tasks, implemented on one `feature/` branch. Changes are **never committed**: the developer watches them appear in their IDE, reviews, and commits. The next unit starts only after they've committed or discarded this one.

Max work items in the unit: ${user_config.max_bugs_per_cycle}
Arguments (optional user story id to work on): $ARGUMENTS

## 1. Review gate
Call `review_gate`.
- If `reviewedSinceLastCheck` has units, mention each in one line (committed / discarded per project).
- If `canStartNewWork` is false, stop here. Report the waiting unit(s): user story, branch, per project folder its state (waiting for review / staged, not committed) and file counts, the report path, and "Commit or discard these changes to continue." Do nothing else.

## 2. Projects
Call `get_projects`. If the list is empty, say "No projects configured. Run /bug-agent:setup first." and stop. Leave out projects whose folder doesn't exist, and mention them in the report.

## 3. Intake (Haiku)
Delegate to the **bug-agent:bug-intake** subagent with limit 50 and the project list as `- name: description` lines. Take the items from its json block. If it reports an error (missing plugin options, a 401), stop and report it with the fix: `claude plugin configure bug-agent`. If there are no items, report "No new bugs or tasks" and stop.

## 4. Choose this cycle's unit
Group the items by (`userStory.id`, type). Items without a user story form one group per type.
- If a user story id was passed as an argument, use that story; prefer its Bugs, and take its Tasks only if it has no Bugs.
- Otherwise pick the group that holds the highest-priority item (lowest number). On a tie, take Bugs before Tasks, then the older story (lower id).

Take up to the max items above, in priority order; the rest wait for a later cycle. Call `start_unit` with `userStoryId`, `userStoryTitle`, `itemType` (`Bug` or `Task`) and `items` (id, title, url). If it's refused because work waits for review, report that and stop.

## 5. Work through the items, one at a time

**a. Plan (Opus).** Delegate to **bug-agent:bug-planner**. Give it:
- the item (id, type, title, link, priority, severity, area path, tags, intake summary, suspected projects, description, repro steps);
- the full user story (id, title, state, link, description, acceptance criteria);
- for Tasks, the story's other tasks in this unit;
- what earlier items of this unit changed (files and one-line summaries);
- the project list with absolute paths.

If there's no valid plan, record the item as `error` and continue.

**b. Apply (Sonnet).** If `canFix` is true and there are steps:
1. Group the steps by project and run the groups in `order`.
2. For each group, call `prepare_workdir` with `bugId`, `project`, `itemType`, `userStoryId`, `unitId` and `files`. In checkout mode the project folder itself switches to the story's branch:
   - bugs use the existing `fix/…<story>…` branch, or a new `fix/us-<story>`;
   - tasks use `feature/…`;
   - a new branch is cut from the repository's root branch (main, master, … detected per repository), unless the `base_branch` / `sync_branch` options say otherwise.

   If it fails, mark the step **failed** with its message and skip the item's remaining steps. It fails when the folder has someone else's uncommitted changes, or when the sync merge conflicts.
3. Delegate to **bug-agent:bug-fixer** with: item type, workdir, sourcePath, mode, branch, the item title and link, the story title and acceptance criteria, the plan's rootCause and storyAlignment, and that group's steps verbatim.
4. Call `finalize_workdir` with bugId, project, title, url, rootCause, the fixer's summary and `userStoryTitle`. Nothing is committed; it returns this item's `filesChanged`.
5. A step is **applied** if the fixer said `applied` and `filesChanged` isn't empty; otherwise it **failed**.

**c. Outcome** per item:
- `fixed` (for Tasks: implemented): all steps applied;
- `partially_fixed`: some steps applied;
- `needs_human`: `canFix` false, no steps, or none applied;
- `error`: a failure.

Call `record_bug` with id, title, url, itemType, outcome, rootCause, confidence, userStoryId, userStoryTitle, notes (storyAlignment, testingNotes, risks) and `fixes` (project, status, branch, filesChanged, summary).

## 6. Review (Opus)
If anything was applied:
1. Call `unit_patch` with the unitId.
2. Delegate to **bug-agent:change-reviewer**. Give it:
   - the `patchPath`;
   - the user story with its acceptance criteria;
   - each item with its plan and the fixer's summary;
   - the project folders.

   Take its verdict: `readyForStaging`, `summary`, `issues` and `perItem`.

## 7. Report and alert
Write the report in Markdown. It's saved outside the projects, so write it for the developer reviewing in their IDE:
- **Title:** `User story #<id> <title>`, the story link, the type (bug fixes or task implementation), and the branch.
- **Acceptance criteria:** the list, each marked with how the changes address it, or that they don't touch it.
- **Per item:** `Bug|Task #id title` with its link, the outcome, the root cause or what was implemented, what changed and why, the files, and how to test it.
- **Review:** ready for staging or not, the summary, every issue, and the per-item verdicts.
- **Not done:** items that need a human, items deferred to a later cycle, failures and warnings.

Call `finish_unit` with unitId, `readyForStaging`, `verdictSummary`, `issues` and `reportMarkdown`. If nothing was applied, still call it, with readyForStaging false. It does three things:
- saves `<unitId>.md` and `<unitId>.patch` in the report folder;
- adds where each change sits;
- schedules the desktop notification for the configured number of minutes later (15 by default). The notification says whether the changes are ready for staging and whether the developer has reviewed them yet.

Call `add_comment` on each item; it's skipped when comments are off.

## 8. Final message
Keep it short:
- the user story and branch, and which project folders are now on that branch (open in the IDE) with uncommitted changes;
- one line per item with its outcome;
- the review verdict;
- the report path and when the alert will fire;
- "Review in your IDE, then commit to continue, or discard to drop the changes. The next cycle waits until you do."

Never commit, push, merge (other than the automatic sync of a new branch) or change a work item's state in Azure DevOps.
