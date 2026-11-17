# bug-agent

Every hour, this service checks Azure DevOps for **New bugs assigned to you**, finds the root cause across your projects, and writes the fix on a branch for you to review.

```
            every hour (node-cron, CRON_SCHEDULE)
                         │
 ┌───────────────────────▼────────────────────────┐
 │ 1. Intake: claude-haiku-5-5 (effort low)       │  WIQL: Bug · State=New · AssignedTo=@Me
 │    query Azure DevOps → triage → bug list       │  skips bugs already handled
 └───────────────────────┬────────────────────────┘
                         │ bug id, link, details, suspected projects
 ┌───────────────────────▼────────────────────────┐
 │ 2. Main agent: claude-opus-5-5 (effort medium) │  read-only tools over every project:
 │    investigate → root cause → ordered plan      │  list_projects, list_dir, read_file, search_code
 └───────────────────────┬────────────────────────┘
                         │ tasks: [{order, project, files, instructions}]
 ┌───────────────────────▼────────────────────────┐
 │ 3. Fixers: claude-sonnet-5-5 (effort medium)   │  one run per project, in plan order
 │    read → edit_file / write_file → finish       │  (e.g. backend before frontend)
 └───────────────────────┬────────────────────────┘
                         ▼
     git project → worktree + branch ai-fix/bug-<id> + local commit
     other       → data/patches/bug-<id>/<project>/ + _FIX_NOTES.md
```

## What it will and won't do

- **Never touches your working copy.** Git projects get a separate worktree under `data/worktrees/` on branch `ai-fix/bug-<id>`. Non-git projects get changed files written to `data/patches/`, which you copy over after review.
- **Never pushes, merges or changes bug state.** Commits are local. Posting a comment on the bug is off unless you set `AZURE_DEVOPS_POST_COMMENTS=true`.
- **Agents can't run commands.** They only have file tools, and every path is confined to the project root. Writes to `.git`, `.env*` and `node_modules` are refused.
- The planner says `canFix: false` (outcome **needs human**) when it isn't confident, or when the fix needs infrastructure, data or product decisions.
- Each bug is processed once. Errors are retried up to `MAX_ATTEMPTS_PER_BUG` times; the dashboard's **retry** link forces a re-run.
- If a step fails, the later steps for that bug are skipped so you don't get a half-applied cross-project change.

## Setup

1. **Azure DevOps PAT**: User settings → Personal access tokens → New token, scope **Work Items: Read** (Read & write only if you want comments).
2. **Anthropic API key** from the Claude Console.
3. Configure:
   ```bash
   cd bug-agent\service
   copy .env.example .env      # fill in ANTHROPIC_API_KEY, AZURE_DEVOPS_ORG / _PROJECT / _PAT
   npm install
   ```
4. Check `config/projects.json`. It lists `just-fast`, `fieldstone-market-marketplace/marketplace` and `Marketplace FE`. A good `description` helps the agents route bugs, so say what each project is and how projects talk to each other.

## Run

| Command | What it does |
|---|---|
| `npm run preview` | Demo with fake agents and a demo project; no keys needed. Dashboard on http://localhost:4000 |
| `npm run dev` | Live service with hot reload: dashboard + hourly schedule + one cycle at startup |
| `npm run cycle` | One cycle, then exit (for Windows Task Scheduler / cron instead of the long-running service) |
| `npm test` | 25 tests (sandboxing, Azure client, workflow ordering/dedup/retries/worktrees, all three agents against a scripted Claude API) |
| `npm run build && npm start` | Production build |

## Deploy

### Docker (recommended)
```bash
docker compose up -d --build
```
`docker-compose.yml` mounts the three projects at `/projects/...` (matching `config/projects.docker.json`) and keeps state in the `bug-agent-data` volume. `just-fast` is mounted read-write because git worktrees are registered in its `.git`; the overlay projects are read-only. Set `DASHBOARD_USER` / `DASHBOARD_PASSWORD` before exposing port 4000.

Because the agents need your source code, run the container where the repositories live (your machine or a build VM that clones them). To review a git fix:
```bash
git -C D:\Projects\just-fast log ai-fix/bug-1234
```
Then push the branch and open a PR when you're happy with it.

### Without Docker (Windows)
Run `npm run build`, then either keep `node dist/index.js` running (for example with NSSM or pm2), or schedule `node dist/cli.js` hourly in Task Scheduler (it runs one cycle and exits).

## Configuration

| Variable | Default | |
|---|---|---|
| `CRON_SCHEDULE` | `0 * * * *` | Top of every hour |
| `PLANNER_EFFORT` | `medium` | Opus effort level (`low` … `max`) |
| `MAX_BUGS_PER_CYCLE` | `5` | Remaining bugs wait for the next cycle |
| `AZURE_DEVOPS_ASSIGNED_TO` | `@Me` | Or a display name/email |
| `AZURE_DEVOPS_STATES` | `New` | Comma-separated |
| `applyMode` (per project) | `auto` | `worktree`, `overlay`, or `auto` (worktree if git) |

## Notes

- Opus 5.5 and Sonnet 5.5 calls enable server-side refusal fallback (`fallbacks: "default"`), so a rare safety decline is retried on a fallback model instead of failing the bug. Remove it in `src/agents/llm.ts` if you don't want it.
- The SDK tool runner (`@anthropic-ai/sdk` 1.10) uses **message threads**: after the first request it sends only new turns and the API keeps the history briefly. Threads are not eligible for zero data retention.
- Cost scales with how much code the planner reads per bug. Tune with `PLANNER_EFFORT` and `MAX_BUGS_PER_CYCLE`.

## Layout
```
src/agents/poller.ts   Haiku intake agent        src/workflow.ts  cycle orchestration
src/agents/planner.ts  Opus main agent            src/workspace.ts sandboxed file access
src/agents/fixer.ts    Sonnet fixer agents        src/azure.ts     Azure DevOps REST client
src/agents/tools.ts    file tools for agents      src/server.ts    dashboard API
src/mock.ts            preview stand-ins          public/          dashboard UI
```
