#!/usr/bin/env bash
set -euo pipefail

# Pulls shared code/build-system/library changes from upstream keiyoushi
# (remote: kei), excluding extension source we don't want in our history
# (src/, lib-multisrc/).
#
# Does NOT commit anything automatically. It fetches kei, applies the
# relevant diff to the working tree/index, and updates .kei-sync, leaving
# everything staged for manual review/splitting into commits.
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
git diff --stat "$LAST_SYNC" "$TARGET" -- . "${EXCLUDE_PATHSPECS[@]}"
echo

PATCH=$(mktemp)
trap 'rm -f "$PATCH"' EXIT
git diff "$LAST_SYNC" "$TARGET" -- . "${EXCLUDE_PATHSPECS[@]}" > "$PATCH"

if [[ ! -s "$PATCH" ]]; then
    echo "No relevant changes outside excluded paths."
    echo "$TARGET" > "$SYNC_FILE"
    git add "$SYNC_FILE"
    git tag -f kei-sync "$TARGET" >/dev/null
    exit 0
fi

git apply --index --3way "$PATCH"

echo "$TARGET" > "$SYNC_FILE"
git add "$SYNC_FILE"
git tag -f kei-sync "$TARGET" >/dev/null

echo
echo "Applied through $TARGET on branch $WORKBRANCH. Changes are staged, nothing committed."
echo "Review and split into commits as usual, then open a PR."
echo
echo "If this sync needs to be redone: git reset --hard kei-last, delete this branch, and re-run."
