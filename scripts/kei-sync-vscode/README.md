# Kei Sync Helper (VSCode)

A small, personal, local-only VSCode extension for the `scripts/sync-kei.sh` workflow (see that script's own `--help` for what kei-sync is). It exists to fix one specific gap: `sync-kei.sh --status` classifies files it can't match to upstream as "Diverged," but it can't tell a genuinely unresolved conflict apart from a file where the fork permanently carries its own content on top of an already-correctly-merged upstream change (its only real signal there is a diff-size guess). This extension records what you *actually* decided at the moment you resolved each conflict, so `--status` can report the real answer instead of guessing.

Not published to the marketplace, not meant for wide use — it's checked into the repo mainly so it isn't lost and so anyone who *does* want it doesn't have to reinvent it.

## What it does

- Sits inert until a sync is actually in progress: it only activates behavior when the current branch matches `kei-sync-<12 hex chars>` (what `sync-kei.sh` names its throwaway branches) or `.git/kei-sync-pending/` exists.
- Watches VSCode's built-in Git extension for files leaving the conflicted set (works whether you resolved via the Merge Editor or by hand-editing markers — there's no way to tell which, and it doesn't matter).
- When a file gets resolved, asks how: **Kept ours / Took upstream / Merged both / Skip**, with an optional free-text note, and appends one line to `.git/kei-sync-decisions.jsonl` (untracked, same as `sync-kei.sh`'s own `.git/kei-sync.log` and `.git/kei-sync-pending/`).
- Shows a status bar item ("Kei Sync: N tagged, M untagged") while a sync is active, so a missed prompt doesn't just silently disappear.
- **Kei Sync: Finalize** command (also reachable by clicking the status bar item): summarizes all decisions for the current branch (grouped by type, plus anything resolved-but-untagged or still-unresolved), copies the summary to your clipboard and opens it as a new document, then — after confirming — backs up and clears the decisions file so the next sync starts fresh.

## Decision record schema

One JSON object per line in `.git/kei-sync-decisions.jsonl`:

```json
{"path":"gradle/kei.versions.toml","decision":"kept-ours","note":"fork-only entries","timestamp":"2026-07-12T03:14:22Z","syncBranch":"kei-sync-89b3b42a4c1e"}
```

`decision` is one of `kept-ours | took-upstream | merged-both | skip`. `sync-kei.sh --status` reads this file directly (via `jq`, if installed) and annotates matching "Diverged" entries with `[marked: <decision> - <note>]`.

This is different from `.kei-sync-ignore`: the ignore file is a **permanent** exclusion (a path is never even attempted by sync again), while a decision record is a **per-attempt** annotation of a judgment call made during one resolution session — it doesn't suppress anything on future syncs.

## Install

No build step — it's plain JS, no TypeScript/bundler. From this directory:

```sh
npx @vscode/vsce package --no-dependencies
code --install-extension kei-sync-helper-0.1.0.vsix
```

Reload VSCode (or just reopen the window) afterward. To update after editing `extension.js`, bump `version` in `package.json`, re-run both commands.

## Usage

1. Run `scripts/sync-kei.sh` as usual; it creates a `kei-sync-<sha>` branch.
2. Resolve conflicts normally (Source Control panel → "Resolve in Merge Editor," or hand-edit markers). Each time a file becomes conflict-free, you'll get a prompt asking how it was resolved.
3. Check the status bar item any time to see how many files are tagged vs. still untagged.
4. When done, run **Kei Sync: Finalize** (Command Palette, or click the status bar item) to get a summary and clear the decisions file for next time.
5. Run `scripts/sync-kei.sh --status` — annotated "Diverged" entries now show your real decision instead of a diff-size guess.
