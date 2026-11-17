# bug-agent

AI agents that pick up the Azure DevOps bugs assigned to you, find the root cause across your projects, and write the fix on a branch for you to review.

```
 every hour
     â”‚
     â–¼
 bug-intake   (Haiku)    Azure DevOps: Bugs Â· State=New Â· Assigned to @Me, not handled yet
     â”‚                   â†’ summary + likely projects for each bug
     â–¼
 bug-planner  (Opus, medium effort)   reads your projects (read-only), confirms the root cause,
     â”‚                                writes an ordered plan: one task per project
     â–¼
 bug-fixer    (Sonnet)   applies each project's tasks, in plan order
     â”‚
     â–¼
 git project   â†’ separate worktree, branch ai-fix/bug-<id>, local commit
 other folder  â†’ reviewable copies + _FIX_NOTES.md in the plugin's data folder
```

The repo has two ways to run it:

| | `plugins/bug-agent` (Claude Code plugin) | `service/` (standalone service) |
|---|---|---|
| Pays with | Your Claude subscription login (Pro, Max, Team, Enterprise). **No API key.** | `ANTHROPIC_API_KEY` (API credits) |
| Runs in | Claude Code (terminal, desktop app, IDE) | Node or Docker, with a web dashboard |
| Hourly | Desktop scheduled task, `/loop`, or OS scheduler running `claude -p` | Built-in cron |

## Install the plugin

In Claude Code:
```
/plugin marketplace add AbdullahRamadan07/bug-agent
/plugin install bug-agent@bug-agent
```
(From a local clone: `/plugin marketplace add D:\path\to\bug-agent`.)

During install you're asked for the plugin options. Change them later with:
```bash
claude plugin configure bug-agent
```

| Option | |
|---|---|
| `azure_org` | The part after `dev.azure.com/` in your Azure DevOps URL |
| `azure_project` | Project name as shown in Azure DevOps |
| `azure_pat` | Personal access token, scope **Work Items: Read** (Read & write only for comments). Stored in your OS credential store, never in a file. |
| `assigned_to` | `@Me` (default) or a display name / email |
| `bug_states` | `New` (default), comma-separated |
| `max_bugs_per_cycle` | `3` (default) |
| `post_comments` | `false` (default). When on, posts root cause + fix branch on the bug. |

Restart Claude Code after changing options, then:

| Command | |
|---|---|
| `/bug-agent:setup` | Checks the Azure connection and lets you pick the project folders (it drafts a description of each; the planner uses them to route bugs) |
| `/bug-agent:cycle` | Runs one cycle now. `/bug-agent:cycle 65369` processes just that bug. |
| `/bug-agent:schedule` | Makes it run hourly: desktop app task, `/loop 1h`, or Windows Task Scheduler / cron via headless `claude -p` (it asks before registering anything) |
| `/bug-agent:status` | Handled bugs, outcomes, branches and patch folders. `/bug-agent:status reset 65369` re-queues a bug. |

Requirements: Claude Code, Node.js 18+ (runs the plugin's Azure DevOps MCP server, which has no dependencies), and git.

## What it will and won't do

- **Your checkout is never touched.** Git projects get a separate worktree in the plugin's data folder on branch `ai-fix/bug-<id>`; review it with `git log ai-fix/bug-<id>`, then push and open a PR yourself. Non-git folders get changed copies plus `_FIX_NOTES.md`.
- **No push, merge, or bug state changes.** Commenting is opt-in.
- **Least-privilege agents.** The planner can only read (Read, Grep, Glob). Fixers can read and edit but have no shell, and are told to write only in their work folder. Intake has no file or shell access.
- **Honest outcomes.** When the planner isn't confident, or the fix needs a supplier, data, config or product decision, the bug is marked **needs human** with what to check.
- Each bug is handled once. Errors retry up to 3 times; `/bug-agent:status reset <id>` forces a re-run.
- A failed step stops the remaining steps for that bug, so you never get half a cross-project fix.

## Repository layout

```
.claude-plugin/marketplace.json   makes this repo installable with /plugin marketplace add
plugins/bug-agent/
  .claude-plugin/plugin.json      manifest + install-time options
  .mcp.json                       starts the Azure DevOps / state MCP server
  agents/                         bug-intake (haiku), bug-planner (opus), bug-fixer (sonnet)
  skills/                         setup, cycle, schedule, status
  server/ado-mcp.mjs              MCP server: Azure DevOps REST, project list, worktrees/overlays, history
tests/ado-mcp.test.mjs            MCP server tests (node --test tests/ado-mcp.test.mjs)
service/                          standalone API-key version with dashboard and Docker (see service/README.md)
```

## Develop

```bash
node --test tests/ado-mcp.test.mjs
claude plugin validate ./plugins/bug-agent
claude --plugin-dir ./plugins/bug-agent
```
