# bug-agent

AI agents that pick up the Azure DevOps Bugs and Tasks assigned to you, check them against their user story's acceptance criteria, and commit the fix or implementation on the story's branch for you to review.

```
 every hour
     │
     ▼
 bug-intake   (Haiku)    Azure DevOps: Bugs + Tasks · State=New · Assigned to @Me, not handled yet
     │                   + each item's parent user story: requirements and acceptance criteria
     │                   → summary + likely projects for each item
     ▼
 bug-planner  (Opus, medium effort)   reads your projects (read-only); Bug: confirms the root cause,
     │                                Task: finds where the change belongs; checks it against the
     │                                story's acceptance criteria; ordered plan, one step per project
     ▼
 bug-fixer    (Sonnet)   applies each project's steps, in plan order, on the story's branch
     │
     ▼
 git project   → Task: feature/us-<story>   Bug: fix/us-<story>   (existing branch reused), one commit per item
                 new branches start from UAT with master merged in
 other folder  → reviewable copies + _FIX_NOTES.md in the plugin's data folder
```

## User stories and branches

bug-agent picks up New **Bugs** (to fix) and **Tasks** (to implement) assigned to you. For each item it follows the **Parent** link up to the user story: a User Story, Product Backlog Item or Requirement, also through an in-between Task. The story's description and **acceptance criteria** go to the planner:
- A bug is fixed so the code meets those criteria, not just so the symptom disappears.
- A task is implemented so the criteria it covers are met.
- If an item and its story disagree, or the criteria are too vague to tell what's correct, the item is marked **needs human** instead of guessed at.

Work goes story by story, Tasks first, then Bugs. In each git project:
1. **Fetch.** bug-agent fetches from the remote.
2. **Find a branch of the right type with the story number.**
   - Tasks use `feature/...` branches, e.g. `feature/us-65001` or `feature/US-65001-refund-ui`.
   - Bugs use `fix/...` branches, e.g. `fix/us-65001` or `fix/65001-totals`.
   - Local branches come first, then remote ones. A bug never lands on a feature branch, or the other way round.
   - The number must stand alone: story `5001` doesn't match `50010`.
3. **Use it if found.** A remote-only branch gets a local tracking branch.
4. **Otherwise create one:** `feature/us-<story>` for tasks, `fix/us-<story>` for bugs.
   - It's cut from `origin/UAT` (`base_branch`); a repository without UAT uses its default branch.
   - `origin/master` (`sync_branch`) is then merged in, so the new branch starts as UAT plus the latest master. Only the new branch changes; UAT and master are never touched.
   - If that merge conflicts, the new branch is deleted again and the item is reported as **needs human**, so you can sync UAT with master yourself.
5. **One commit per item:** `feat: task #<id> …` or `fix: bug #<id> …`, with the user story in the message.

Items without a user story go on `fix/bug-<id>` or `feature/task-<id>`.

**Where the branch is opened** (`branch_mode`):
- `worktree` (default): the branch opens in a separate folder inside the plugin's data folder, and your own checkout stays exactly as it is.
- `checkout`: your project folder itself is switched to the story branch. bug-agent refuses if the folder has uncommitted changes.
- Either way, if the story branch is already checked out in your project folder and that folder is clean, bug-agent works there. Your untracked files are never committed.

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
| `bug_states` | `New` (default). States of Bugs and Tasks to pick up, comma-separated |
| `max_bugs_per_cycle` | `3` (default). Bugs and Tasks beyond this wait for the next cycle |
| `work_item_types` | `Bug,Task` (default). Set `Bug` to only fix bugs |
| `bug_branch_prefix` | `fix/us-` (default). New bug branch = prefix + story number. Existing branches in the same family (`fix/`) with the story number are reused |
| `task_branch_prefix` | `feature/us-` (default). New task branch = prefix + story number. Existing `feature/` branches with the story number are reused |
| `base_branch` | `UAT` (default). New branches are cut from `origin/UAT`; a repository without it uses its default branch. Empty = the default branch |
| `sync_branch` | `master` (default). Merged into every new branch right after it's cut. On a conflict the branch is removed and the item goes to a human. Empty = no sync |
| `branch_mode` | `worktree` (default) or `checkout`; see [User stories and branches](#user-stories-and-branches) |
| `post_comments` | `false` (default). When on, posts root cause + fix branch on the bug. |

Restart Claude Code after changing options, then:

| Command | |
|---|---|
| `/bug-agent:setup` | Checks the Azure connection and lets you pick the project folders (it drafts a description of each; the planner uses them to route bugs) |
| `/bug-agent:cycle` | Runs one cycle now. `/bug-agent:cycle 65369` processes just that bug. |
| `/bug-agent:schedule` | Makes it run hourly: desktop app task, `/loop 1h`, or Windows Task Scheduler / cron via headless `claude -p` (it asks before registering anything) |
| `/bug-agent:status` | Handled bugs, outcomes, branches and patch folders. `/bug-agent:status reset 65369` re-queues a bug. |

Requirements: Claude Code, a **Java 17+ JDK** on your PATH, and git. The plugin's Azure DevOps MCP server is a single Java source file with no dependencies; `java` compiles and runs it directly, so there's nothing to build. (It needs a JDK, not just a JRE, because the source launcher compiles the file.)

## What it will and won't do

- **Your checkout is left alone by default.** Work is committed on the story's `fix/` or `feature/` branch in a separate worktree. Review them with `git log <story branch>`, then push and open a PR yourself. With `branch_mode` `checkout`, your folder is switched, but only when it has no uncommitted changes. Non-git folders get changed copies plus `_FIX_NOTES.md`.
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
  server/AdoMcp.java              MCP server (Java 17+, no dependencies): Azure DevOps REST, project list,
                                  worktrees/overlays, history
tests/ado-mcp.test.mjs            black-box MCP tests: start AdoMcp.java over stdio against a fake Azure DevOps
service/                          standalone API-key version with dashboard and Docker (see service/README.md)
```

## Develop

```bash
javac --release 17 -d build plugins/bug-agent/server/AdoMcp.java   # compile check
node --test tests/ado-mcp.test.mjs                                   # tests (Node 18+ only needed for the tests)
claude plugin validate ./plugins/bug-agent
claude --plugin-dir ./plugins/bug-agent
```
