---
name: status
description: Show bug-agent's handled bugs, outcomes, and where each fix was written; optionally reset a bug so it is processed again.
disable-model-invocation: true
argument-hint: "[reset <bug-id>]"
---

# bug-agent status

Arguments: $ARGUMENTS

- If the arguments are `reset <id>`, call `reset_bug` with that id from the bug-agent `ado` MCP server, confirm it, and stop.
- Otherwise call `history` (limit 20) and `get_projects`, then show:
  - Grouped by user story (`userStoryId` / `userStoryTitle`; items without one under "No user story"): a table of work item (`Bug #id` or `Task #id`, linked to its url), title, outcome, attempts, last updated.
  - Under each story, per project: the branch (and whether it was existing or newly created) with each bug's commit, or the patch folder (overlay), and the files changed.
  - For `needs_human` bugs: the root cause or notes.
  - The data folder path, and how to review the work: `git log fix/us-<story>` or `git log feature/us-<story>` (the branch shown above) in the project's repository.
