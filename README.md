# bug-agent

AI agents that take one user story at a time from Azure DevOps. They fix all the New Bugs assigned to you under it, or implement its Tasks, **live in your project folder** on the story's branch, and leave the changes **uncommitted** for you to review in your IDE. Then they write a report outside the project and alert you.

```
 every hour: is the last story reviewed (committed or discarded)?  no → wait, yes ↓
     │
 bug-intake      (Haiku)   New Bugs + Tasks assigned to @Me, with their user story + acceptance criteria
     │                     → picks ONE user story: all its Bugs, or else all its Tasks
     ▼
 bug-planner     (Opus)    per item: root cause (Bug) or design (Task), checked against the criteria
     ▼
 bug-fixer       (Sonnet)  edits your project folder directly on fix/us-<story> or feature/us-<story>
     │                     (you watch it in your IDE) · nothing is committed
     ▼
 change-reviewer (Opus)    reviews the whole diff: ready for staging or not, with issues
     ▼
 report  →  <report folder>/US-<story>-fix-<time>.md + .patch   (outside your projects)
 alert   →  desktop notification after 15 min: ready for staging? reviewed yet?
     ▼
 you review in the IDE → commit (accept) or discard → the next cycle takes the next user story
```

## The review loop

1. **One user story per cycle.**
   - **Bugs:** all New Bugs assigned to you under the story are fixed in the same cycle, on the same `fix/us-<story>` branch.
   - **Tasks:** the story is studied first, then its Tasks are implemented on `feature/us-<story>`.
   - The story with the highest-priority item goes first. Bugs come before Tasks.
2. **Live in your IDE.** In the default `branch_mode` `checkout`, your project folder itself is switched to the story's branch, and the edits appear in the editor you have open. If a project folder has uncommitted changes of your own, bug-agent won't touch it.
3. **Nothing is committed.** The changes stay unstaged for you.
4. **Reviewed before you see it.** An Opus reviewer reads the whole diff against the acceptance criteria and decides whether it's **ready for staging**, listing each issue with its file and line.
5. **Report outside the project.** The report goes to `report_dir`, or by default the plugin's data folder under `reports/`. It covers:
   - each acceptance criterion and how it's addressed;
   - per item: the root cause or implementation, what changed and why, the files, and how to test;
   - the review verdict;
   - where the changes are.

   A `.patch` file holds the full diff.
6. **Alert after 15 minutes** (`review_alert_minutes`). A desktop notification (a Windows toast, macOS Notification Center, or Linux `notify-send`) says whether the changes are ready for staging and whether you've reviewed them yet. The same status is appended to the report and to `alerts.log`.
7. **The next story waits for you.** The next cycle starts new work only when every folder of this story is clean again:
   - commit to accept;
   - discard to reject (`git restore .` and delete the new files).

   Until then it only reports what's waiting. `/bug-agent:status` shows it any time.

Set `auto_commit` on to get the older behaviour: one commit per item and no review gate.

## User stories and branches

bug-agent picks up New **Bugs** (to fix) and **Tasks** (to implement) assigned to you. For each item it follows the **Parent** link up to the user story: a User Story, Product Backlog Item or Requirement, also through an in-between Task. The story's description and **acceptance criteria** go to the planner:
- A bug is fixed so the code meets those criteria, not just so the symptom disappears.
- A task is implemented so the criteria it covers are met.
- If an item and its story disagree, or the criteria are too vague to tell what's correct, the item is marked **needs human** instead of guessed at.

For the story being worked on, in each git project:
1. **Fetch.** bug-agent fetches from the remote.
2. **Find a branch of the right type with the story number.**
   - Tasks use `feature/...` branches, e.g. `feature/us-65001` or `feature/US-65001-refund-ui`.
   - Bugs use `fix/...` branches, e.g. `fix/us-65001` or `fix/65001-totals`.
   - Local branches come first, then remote ones. A bug never lands on a feature branch, or the other way round.
   - The number must stand alone: story `5001` doesn't match `50010`.
3. **Use it if found.** A remote-only branch gets a local tracking branch.
4. **Otherwise create one:** `feature/us-<story>` for tasks, `fix/us-<story>` for bugs.
   - It's cut from the repository's **root branch**, whatever it's called (`main`, `master`, `trunk`, `develop`…). bug-agent fetches first, so it starts from the latest root branch on the server. The root branch is detected per repository, in this order:
     1. the remote's default branch (`origin/HEAD`);
     2. if that isn't recorded locally, the remote is asked;
     3. otherwise `main`, then `master`.
   - The root branch itself is never changed.
   - Optional: set `base_branch` (e.g. `UAT`) to cut from another branch instead. You can also set `sync_branch` (e.g. `master`) to merge that branch into each new one; on a conflict, the new branch is deleted again and the item goes to a human.
5. **Changes stay uncommitted** for your review. With `auto_commit` on, each item is committed instead: `feat: task #<id> …` or `fix: bug #<id> …`.

Items without a user story go on `fix/bug-<id>` or `feature/task-<id>`.

**Where the branch is opened** (`branch_mode`):
- `checkout` (default): your project folder itself is switched to the story branch, so you see the work live in your IDE. bug-agent refuses if the folder has uncommitted changes that aren't this story's.
- `worktree`: the branch opens in a separate folder in the plugin's data folder, and your own checkout stays as it is. You can open that folder in your IDE, or check the branch out with `/bug-agent:review`.
- Your untracked files are never counted as bug-agent's changes.

### Earlier stories' branches

`/bug-agent:review` lists every branch bug-agent created or used, per user story: its items, the projects it touches, and the changes since the root branch. `/bug-agent:review 65001` checks that story's branch out in every project it touches, and `/bug-agent:review back main` switches them back. A folder with uncommitted changes is never switched. To ship a reviewed story, push its branch and open a pull request yourself.

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
| `max_bugs_per_cycle` | `10` (default). Most Bugs (or Tasks) of the one user story handled per cycle |
| `auto_commit` | `false` (default): changes stay uncommitted for your review, and the next story waits until you commit or discard. `true`: one commit per item, no review gate |
| `review_alert_minutes` | `15` (default). When the review-status desktop notification fires after a story is done |
| `report_dir` | Folder for reports and patches, outside your projects. Empty (default) = the plugin's data folder/reports |
| `work_item_types` | `Bug,Task` (default). Set `Bug` to only fix bugs |
| `bug_branch_prefix` | `fix/us-` (default). New bug branch = prefix + story number. Existing branches in the same family (`fix/`) with the story number are reused |
| `task_branch_prefix` | `feature/us-` (default). New task branch = prefix + story number. Existing `feature/` branches with the story number are reused |
| `base_branch` | Empty (default) = new branches are cut from each repository's own root branch (`main`, `master`, …, detected automatically). Set a name (e.g. `UAT`) only to cut from that branch instead |
| `sync_branch` | Empty (default) = no sync. Optional branch merged into every new branch right after it's cut, useful only together with `base_branch` (e.g. base `UAT` + sync `master`) |
| `branch_mode` | `checkout` (default: your project folder switches to the story branch, live in your IDE) or `worktree`; see [User stories and branches](#user-stories-and-branches) |
| `post_comments` | `false` (default). When on, posts root cause + fix branch on the bug. |

Restart Claude Code after changing options, then:

| Command | |
|---|---|
| `/bug-agent:setup` | Checks the Azure connection and lets you pick the project folders (it drafts a description of each; the planner uses them to route bugs) |
| `/bug-agent:cycle` | Runs one cycle now. `/bug-agent:cycle 65369` processes just that bug. |
| `/bug-agent:schedule` | Makes it run hourly: desktop app task, `/loop 1h`, or Windows Task Scheduler / cron via headless `claude -p` (it asks before registering anything) |
| `/bug-agent:review` | Lists the `fix/` and `feature/` branches bug-agent created, per user story, and checks the one you pick out in every project it touches. `/bug-agent:review 65001` checks out that story's branch, and `/bug-agent:review back main` switches back. |
| `/bug-agent:status` | Handled bugs, outcomes, branches and patch folders. `/bug-agent:status reset 65369` re-queues a bug. |

Requirements: Claude Code, a **Java 17+ JDK** on your PATH, and git. The plugin's Azure DevOps MCP server is a single Java source file with no dependencies; `java` compiles and runs it directly, so there's nothing to build. (It needs a JDK, not just a JRE, because the source launcher compiles the file.)

## What it will and won't do

- **You decide what gets committed.** Work lands uncommitted in your project folder, on the story's `fix/` or `feature/` branch. bug-agent never commits your work by default. It switches a folder only when that folder has no uncommitted changes of yours. Non-git folders get changed copies plus `_FIX_NOTES.md`.
- **One story at a time.** Nothing new starts while a story waits for your review.
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
  agents/                         bug-intake (haiku), bug-planner (opus), bug-fixer (sonnet), change-reviewer (opus)
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
