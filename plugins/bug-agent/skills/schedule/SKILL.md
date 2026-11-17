---
name: schedule
description: Make bug-agent run every hour (desktop scheduled task, /loop, or the OS scheduler running Claude Code headless).
disable-model-invocation: true
argument-hint: "[desktop | loop | os]"
---

# Schedule bug-agent

Arguments: $ARGUMENTS
Plugin data folder (fix worktrees and patches are written here): ${CLAUDE_PLUGIN_DATA}

First call `get_projects` from the bug-agent `ado` MCP server; if no projects are configured, tell the user to run `/bug-agent:setup` and stop.

A cycle reads every project folder and writes only inside the plugin data folder, so an unattended run needs those folders added to its working directories, with edits auto-accepted. Pick the option from the arguments, or ask the user which they want:

## desktop: Claude desktop app scheduled task
Runs while the Claude desktop app is open. If you have a tool for creating scheduled tasks, create one that runs hourly with the prompt `/bug-agent:cycle`, then confirm what you created. If you don't have such a tool, tell the user to create it in the desktop app (Code tab → Scheduled) with that prompt and an hourly schedule.

## loop: inside this session
Tell the user to run `/loop 1h /bug-agent:cycle`. It runs as long as this session stays open; good for trying it out.

## os: Windows Task Scheduler / cron (headless)
Runs `claude -p` with the user's Claude login, no API key, even when no Claude window is open.

1. Find the exact name prefix of the bug-agent `ado` tools as they appear in your own tool list (the part before `check_connection`, for example `mcp__plugin_bug-agent_ado__`).
2. Build the command:
   ```
   claude -p "/bug-agent:cycle" --permission-mode acceptEdits --allowedTools "<prefix>*" "Agent" --add-dir "<data folder>" --add-dir "<project path>" ... 
   ```
   with one `--add-dir` per configured project.
3. Write it to a script in the data folder: `run-cycle.ps1` on Windows (append output to `cycle.log` in the same folder with a timestamp line), or `run-cycle.sh` elsewhere.
4. Show the user the script and the scheduler command, and **ask before registering it**, since it creates a recurring job on their machine:
   - Windows: `schtasks /Create /SC HOURLY /TN "bug-agent" /TR "powershell -NoProfile -ExecutionPolicy Bypass -File \"<script>\"" /F`
   - macOS / Linux: a crontab line `0 * * * * <script>`
5. Only after the user says yes, register it and confirm. Tell them how to remove it (`schtasks /Delete /TN "bug-agent" /F`, or delete the crontab line).

Cloud routines (`/schedule`) also run Claude without an API key, but they work on GitHub repositories rather than local folders, so they only fit if every project is on GitHub.
