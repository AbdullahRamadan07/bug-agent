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
  - A table: bug (`#id` linked to its url), title, outcome, attempts, last updated.
  - Under it, for each bug with fixes: project, then branch + commit (git) or patch folder (overlay), and the files changed.
  - For `needs_human` bugs: the root cause or notes.
  - The data folder path, and how to review a git fix: `git log ai-fix/bug-<id>` in the project's repository.
