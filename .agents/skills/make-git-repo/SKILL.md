---
name: make-git-repo
description: Set up or adjust GitHub repository rules, branch protection, GitHub Actions access, and safe Git workflows for this project.
---

# IndexPaint Git repository operations

Read this skill before changing repository settings, branch protection, Actions access, or remote branches.

## `main` branch ruleset

IndexPaint uses the active repository ruleset named `main protection`, copied from `ede73/safe`. It applies to the default branch and `refs/heads/main` and enforces:

- Changes to `main` go through a pull request.
- Branch deletion and force pushes are blocked.
- Linear history is required.
- Conversation threads must be resolved; stale approvals are dismissed, if any.
- Zero approving reviews are required and no status checks are required, matching Safe.
- Copilot review runs on pushes and draft pull requests.
- The repository Admin role has an `always` bypass. Do not remove or narrow this bypass: repository admin `ede73` must retain the ability to push directly to `main` when desired.

Before modifying the ruleset, inspect the live ruleset on `ede73/safe` and the current IndexPaint ruleset through `gh api`. Preserve the Admin bypass. Report any proposed differences from Safe before applying them.

## Git push workflow

- Inspect the current branch, working tree, remotes, and destination before every push.
- Push task work to an explicitly named non-main branch by default.
- `ede73` may explicitly direct a push to remote `main`; the repository ruleset must not block the Admin bypass. When explicitly asked, fetch and inspect `origin/main`, avoid force pushes or history rewrites, and push to the named branch deliberately.
- Never rewrite remote history to conceal a mistake. If a commit reaches `main` accidentally, stop and report the exact state.

## Release workflow boundaries

- Pull request CI uses GitHub-hosted runners and does not use signing secrets.
- Signed builds and Play uploads are manual workflow dispatches, gated to `ede73`, and run on the Mac mini self-hosted runner.
- Do not add automatic build or publish triggers for pushes, merges, or pull requests.
- Read `RELEASE.md` and inspect the live workflow before giving a release command; repository layout and runner placement can change.
