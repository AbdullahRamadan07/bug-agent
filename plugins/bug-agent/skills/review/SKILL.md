---
name: review
description: Review what bug-agent changed - list the fix/ and feature/ branches it created per user story, and check a branch out in every project it touches so you can inspect, build and test the changes.
disable-model-invocation: true
argument-hint: "[user-story-id | branch-name | back <branch>]"
---

# Review bug-agent's branches

Arguments: $ARGUMENTS

Use the bug-agent `ado` MCP tools `review_branches`, `checkout_branch` and `review_gate`. Never commit, push, merge, reset or delete anything here; this command only shows branches and switches project folders to them.

First call `review_gate`. In the default flow (branch mode `checkout`), the current story's project folders are already on its branch, with the changes uncommitted. Say so, point to the report, and don't switch those folders: `checkout_branch` would refuse them anyway because of the uncommitted changes. Use this command to revisit earlier stories' branches, or in `worktree` mode.

## 1. Work out what to check out
- **No arguments:** call `review_branches` and show the overview (step 3). Then ask which branch to check out and offer the most recent one first. Once the user picks one, continue with step 2.
- **A number** (a user story id): call `review_branches` with `userStoryId`. Then:
  - if the story has one branch, check it out;
  - if it has both a `feature/` branch (tasks) and a `fix/` branch (bugs), show both and ask which one. A project folder can only be on one branch at a time.
- **A branch name:** check out that branch.
- **`back <branch>`** (for example `back main`): check that branch out again in the same projects, to switch back after reviewing. Pass every project from the last overview as `projects`.

## 2. Check out
Call `checkout_branch` with the branch, and pass `projects` only when the user named some. Then report, per project:
- the result: checked out, already on it, or refused / failed with the reason;
- the folder and the branch it was on before;
- the commits bug-agent added since the base branch (`commits`) and the files they touched (`files`).

If a project was **refused** because it has uncommitted changes, say so plainly. The user has to commit or stash in that project first. Don't do it for them.

## 3. Overview format (for `review_branches`)
Group by user story. For each branch show:
- the branch name and its type (feature = tasks, fix = bugs);
- the items on it: `Task|Bug #id title — outcome`, the link, and the commit;
- per project: whether the branch exists, where it's checked out, which branch the project folder is on now, and the number of commits and files.

List overlay patch folders (non-git projects) separately. Those changes aren't on a branch; the user copies the files over after reviewing `_FIX_NOTES.md`.

## 4. After checking out
Tell the user how to review:
- look at the changes in their editor, or run `git log` / `git diff <base>...<branch>` in the folder (the base comes from the result);
- build and run the tests as usual;
- to go back, run `/bug-agent:review back <previous branch>`, using the branch each project was on before.

To keep the changes, they push the branch and open a pull request themselves.
