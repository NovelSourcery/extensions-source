#!/usr/bin/env bash
set -euo pipefail

# Pulls shared code/build-system/library changes from upstream keiyoushi
# (remote: kei), excluding extension source we don't want in our history
# (src/, lib-multisrc/).
#
# Does NOT commit anything automatically. It fetches kei and applies each
# changed file's diff independently, staging whatever applies cleanly (or
# with 3-way conflict markers) and leaving genuinely unresolvable files
# untouched, with their raw diff saved for manual merging. .kei-sync is
# only advanced automatically when every file applied; otherwise you
# advance it yourself once the leftovers are dealt with.
#
# The .kei-sync file (tracked in git) is the source of truth for "how far
# we've synced" so it survives clones/fresh machines. The kei-sync/kei-last
# tags are just a local convenience derived from it for `git log`/`git diff`.
#
# Usage: scripts/sync-kei.sh

REMOTE=kei
BRANCH=main
SYNC_FILE=.kei-sync
EXCLUDE_PATHSPECS=(':!src' ':!lib-multisrc')
REJECT_DIR="$(git rev-parse --git-dir 2>/dev/null || echo .git)/kei-sync-pending"

cd "$(git rev-parse --show-toplevel)"

if [[ -n "$(git status --porcelain)" ]]; then
    echo "error: working tree is not clean, commit or stash first." >&2
    exit 1
fi

if [[ ! -f "$SYNC_FILE" ]]; then
    echo "error: $SYNC_FILE not found. Bootstrap it first:" >&2
    echo "  echo <kei-commit-sha> > $SYNC_FILE && git add $SYNC_FILE && git commit -m 'chore: bootstrap kei sync point'" >&2
    exit 1
fi

LAST_SYNC=$(<"$SYNC_FILE")

echo "Fetching $REMOTE..."
git fetch "$REMOTE" --quiet

TARGET=$(git rev-parse "$REMOTE/$BRANCH")

if [[ "$LAST_SYNC" == "$TARGET" ]]; then
    echo "Already up to date with $REMOTE/$BRANCH ($TARGET)."
    exit 0
fi

# Backup: move kei-last to wherever kei-sync currently points, so a botched
# sync can be undone by resetting to kei-last and re-running.
git tag -f kei-last "$LAST_SYNC" >/dev/null
git tag -f kei-sync "$LAST_SYNC" >/dev/null

WORKBRANCH="kei-sync-$(date +%Y%m%d)"
git checkout -b "$WORKBRANCH"

echo
echo "Changes to pull in ($LAST_SYNC..$TARGET, excluding src/ and lib-multisrc/):"
git --no-pager diff --stat --no-renames "$LAST_SYNC" "$TARGET" -- . "${EXCLUDE_PATHSPECS[@]}"
echo

mapfile -d '' -t FILES < <(git diff -z --name-only --no-renames "$LAST_SYNC" "$TARGET" -- . "${EXCLUDE_PATHSPECS[@]}")

if [[ ${#FILES[@]} -eq 0 ]]; then
    echo "No relevant changes outside excluded paths."
    echo "$TARGET" > "$SYNC_FILE"
    git add "$SYNC_FILE"
    git tag -f kei-sync "$TARGET" >/dev/null
    exit 0
fi

rm -rf "$REJECT_DIR"
APPLIED=()
FAILED=()

for f in "${FILES[@]}"; do
    if git diff --no-renames "$LAST_SYNC" "$TARGET" -- "$f" | git apply --index --3way - 2>/dev/null; then
        APPLIED+=("$f")
    else
        FAILED+=("$f")
        mkdir -p "$REJECT_DIR/$(dirname "$f")"
        git diff --no-renames "$LAST_SYNC" "$TARGET" -- "$f" > "$REJECT_DIR/$f.patch"
    fi
done

echo
echo "Applied cleanly or with conflict markers: ${#APPLIED[@]} file(s)."

if [[ ${#FAILED[@]} -eq 0 ]]; then
    echo "$TARGET" > "$SYNC_FILE"
    git add "$SYNC_FILE"
    git tag -f kei-sync "$TARGET" >/dev/null
    echo
    echo "All files applied. Staged on branch $WORKBRANCH, nothing committed."
    echo "Review (check for 3-way conflict markers with: git diff --check), split into commits, then open a PR."
else
    echo "Needs manual merge: ${#FAILED[@]} file(s):"
    printf '  %s\n' "${FAILED[@]}"
    echo
    echo "Raw upstream diffs for these are saved under $REJECT_DIR/ for reference."
    echo "$SYNC_FILE was NOT advanced (still $LAST_SYNC) since not everything applied."
    echo "Once you've manually reconciled the files above, advance it yourself:"
    echo "  echo $TARGET > $SYNC_FILE && git add $SYNC_FILE && git tag -f kei-sync $TARGET"
fi

echo
echo "If this sync needs to be redone: git reset --hard kei-last, delete this branch, and re-run."
