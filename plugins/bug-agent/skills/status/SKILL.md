---
name: status
description: Show what waits for my review (uncommitted user-story work, reviewer verdict, report), bug-agent's handled bugs and tasks with outcomes, and optionally reset an item so it is processed again.
disable-model-invocation: true
argument-hint: "[reset <bug-id>]"
---

# bug-agent status

Arguments: $ARGUMENTS

- If the arguments are `reset <id>`, call `reset_bug` with that id from the bug-agent `ado` MCP server, confirm it, and stop.
- Otherwise call `review_gate` first and show what waits for review at the top. For each unit: the user story, its type, and per project folder the branch, the state (waiting for review / staged, not committed), the file counts, the reviewer's verdict (ready for staging or not), and the report path. Then say whether the next cycle can start.
- Then call `history` (limit 20) and `get_projects`, and show:
  - Grouped by user story (`userStoryId` / `userStoryTitle`; items without one under "No user story"): a table of work item (`Bug #id` or `Task #id`, linked to its url), title, outcome, attempts, last updated.
  - Under each story, per project: the branch (and whether it was existing or newly created) with each bug's commit, or the patch folder (overlay), and the files changed.
  - For `needs_human` bugs: the root cause or notes.
  - The data folder path, and how to review the work: `/bug-agent:review <user story id>` checks the branch out in every project it touches (or `git checkout <branch>` in the project).
