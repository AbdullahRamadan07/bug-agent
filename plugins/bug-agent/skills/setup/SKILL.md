---
name: setup
description: Set up bug-agent - check the Azure DevOps connection and choose which local project folders the agents investigate and fix.
disable-model-invocation: true
argument-hint: "[folder ...]"
---

# bug-agent setup

Folders passed as arguments: $ARGUMENTS

## 1. Azure DevOps connection
Call the `check_connection` tool from the bug-agent `ado` MCP server.
- If it says plugin options are not set, or the call fails with 401/203: tell the user to run `claude plugin configure bug-agent` in a terminal (or open `/plugin`, pick bug-agent, and configure it), fill in the organization, project and personal access token (scope **Work Items: Read**), then restart Claude Code and run `/bug-agent:setup` again. Stop here. Never ask the user to paste the token into the chat.
- If it works, report the organization, project, query (assigned to / states) and how many bugs currently match.

## 2. Projects
1. Call `get_projects` and show what is configured.
2. Work out the folders to use: the arguments above, or ask the user for the folders that hold the code these bugs live in (backend, frontend, other services). A parent folder is fine; then list its subfolders and propose the ones that look like projects.
3. For each folder, look at its top level and README / build files (package.json, pom.xml, *.csproj, angular.json, docker-compose.yml) to draft a one-line description: language and framework, what it does, and which other project it talks to. The planner uses these descriptions to route bugs, so make them specific.
4. Propose names (letters, digits, `.`, `_`, `-`), paths and descriptions, and let the user correct them. Use `applyMode` `auto` unless the user asks otherwise; in git projects, tasks are then committed on `feature/us-<story>` and bugs on `fix/us-<story>` branches (new ones cut from UAT and synced with master) in a separate worktree, and other folders get reviewable copies under the plugin's data folder.
5. Save with `set_projects` and show the result, including which projects are git repositories.

## 3. Next steps
Tell the user:
- `/bug-agent:cycle` runs one cycle now (they can pass bug ids, for example `/bug-agent:cycle 65369`, to try one bug first).
- `/bug-agent:schedule` sets it to run every hour.
- `/bug-agent:status` shows what has been handled.
