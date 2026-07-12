#!/usr/bin/env bash
set -euo pipefail

# Pulls shared code/build-system/library changes from upstream keiyoushi
# (remote: kei), excluding extension source we don't want in our history
# (src/, lib-multisrc/).
#
# Applies each changed file's diff independently:
#   - clean applies are staged
#   - conflicting applies fall back to a 3-way merge, leaving conflict
#     markers in the file and the path staged as unmerged, for manual
#     resolution in place
#   - files that can't be applied at all (including binary files that
#     diverged locally) are left untouched, with the raw upstream diff (or
#     blob references, for binaries) saved under .git/kei-sync-pending/
#
# .kei-sync is committed automatically as long as every file at least
# applied (cleanly or with conflict markers) -- if some files couldn't be
# applied at all, it's left unadvanced so the next run doesn't lose track
# of them (their raw diffs are the only record of what's still pending).
#
# A bad sync is always cheap to undo: everything happens on a throwaway
# branch, so abandoning it is just switching back and deleting that branch
# (see the message printed at the end -- do NOT `git reset --hard` to the
# kei-last/kei-sync tags, they point into kei's own disjoint history, not
# ours, and would blow away the whole tree).
#
# The .kei-sync file (tracked in git) is the source of truth for "how far
# we've synced" so it survives clones/fresh machines. The kei-sync/kei-last
# tags are just a local convenience derived from it for `git log`/`git diff`
# against kei's own commit graph.
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

if ! git remote get-url "$REMOTE" >/dev/null 2>&1; then
    echo "error: remote '$REMOTE' not configured. Add it first:" >&2
    echo "  git remote add $REMOTE https://github.com/keiyoushi/extensions-source.git" >&2
    exit 1
fi

ORIGINAL_BRANCH=$(git branch --show-current)
LAST_SYNC=$(<"$SYNC_FILE")

echo "Fetching $REMOTE..."
git fetch "$REMOTE" --quiet

TARGET=$(git rev-parse "$REMOTE/$BRANCH")

if [[ "$LAST_SYNC" == "$TARGET" ]]; then
    echo "Already up to date with $REMOTE/$BRANCH ($TARGET)."
    exit 0
fi

# Bookmark of kei's commit we're syncing from, for `git log`/`git diff`
# against kei's own history later. NOT a target for `git reset --hard` --
# it lives in kei's disjoint commit graph, not ours.
git tag -f kei-last "$LAST_SYNC" >/dev/null

WORKBRANCH="kei-sync-${TARGET:0:12}"
if git show-ref --verify --quiet "refs/heads/$WORKBRANCH"; then
    echo "Resuming existing branch $WORKBRANCH."
    git checkout -q "$WORKBRANCH"
else
    git checkout -q -b "$WORKBRANCH"
fi

echo
echo "Changes to pull in ($LAST_SYNC..$TARGET, excluding src/ and lib-multisrc/):"
git --no-pager diff --stat --no-renames "$LAST_SYNC" "$TARGET" -- . "${EXCLUDE_PATHSPECS[@]}"
echo

mapfile -d '' -t FILES < <(git diff -z --name-only --no-renames "$LAST_SYNC" "$TARGET" -- . "${EXCLUDE_PATHSPECS[@]}")

if [[ ${#FILES[@]} -eq 0 ]]; then
    echo "No relevant changes outside excluded paths."
    echo "$TARGET" > "$SYNC_FILE"
    git add "$SYNC_FILE"
    git commit -q -m "chore: sync kei to $TARGET (no relevant changes)" -- "$SYNC_FILE"
    git tag -f kei-sync "$TARGET" >/dev/null
    exit 0
fi

rm -rf "$REJECT_DIR"
APPLIED=()
CONFLICTED=()
FAILED=()

for f in "${FILES[@]}"; do
    # Binary files can't be 3-way merged textually. If our copy hasn't
    # diverged from kei's last-synced version, just take upstream's version
    # wholesale; otherwise it needs a human to pick between them.
    NUMSTAT_F1=$(git diff --no-renames --numstat "$LAST_SYNC" "$TARGET" -- "$f" | cut -f1)
    if [[ "$NUMSTAT_F1" == "-" ]]; then
        if git diff --quiet "$LAST_SYNC" -- "$f" 2>/dev/null; then
            if git cat-file -e "$TARGET:$f" 2>/dev/null; then
                mkdir -p "$(dirname "$f")"
                git show "$TARGET:$f" > "$f"
                git add "$f"
            else
                git rm -q -- "$f"
            fi
            APPLIED+=("$f (binary, took upstream as-is)")
        else
            FAILED+=("$f")
            mkdir -p "$REJECT_DIR/$(dirname "$f")"
            {
                echo "Binary file changed upstream AND diverged locally -- can't auto-merge."
                echo "Base (last sync):  git show $LAST_SYNC:$f"
                echo "Upstream (target): git show $TARGET:$f"
                echo "Local:              already on disk at $f, untouched"
            } > "$REJECT_DIR/$f.binary-conflict.txt"
        fi
        continue
    fi

    if git diff --no-renames "$LAST_SYNC" "$TARGET" -- "$f" | git apply --index --3way - 2>/dev/null; then
        APPLIED+=("$f")
    elif [[ -n "$(git status --porcelain -- "$f")" ]]; then
        # git apply --3way exits non-zero even on a successful 3-way merge
        # with conflicts -- a dirty status here means it left usable
        # conflict markers in place rather than failing outright.
        CONFLICTED+=("$f")
    else
        FAILED+=("$f")
        mkdir -p "$REJECT_DIR/$(dirname "$f")"
        git diff --no-renames "$LAST_SYNC" "$TARGET" -- "$f" > "$REJECT_DIR/$f.patch"
    fi
done

echo
echo "Applied cleanly: ${#APPLIED[@]} file(s)."
if [[ ${#CONFLICTED[@]} -gt 0 ]]; then
    echo "Applied with conflict markers (resolve in place): ${#CONFLICTED[@]} file(s):"
    printf '  %s\n' "${CONFLICTED[@]}"
fi
if [[ ${#FAILED[@]} -gt 0 ]]; then
    echo "Could not apply at all: ${#FAILED[@]} file(s):"
    printf '  %s\n' "${FAILED[@]}"
fi

if [[ ${#FAILED[@]} -eq 0 ]]; then
    echo "$TARGET" > "$SYNC_FILE"
    git add "$SYNC_FILE"
    git commit -q -m "chore: sync kei to $TARGET" -- "$SYNC_FILE"
    git tag -f kei-sync "$TARGET" >/dev/null
    echo
    echo "Synced. .kei-sync bump committed on branch $WORKBRANCH."
    if [[ ${#CONFLICTED[@]} -gt 0 ]]; then
        echo "Resolve the conflict markers (search for <<<<<<< ) in the files above, then git add + commit them separately."
    fi
    echo "Review the applied changes, split into commits as you like, then open a PR."
else
    echo
    echo "Raw upstream diffs (or blob references, for binaries) for unapplied files are saved under $REJECT_DIR/."
    echo "$SYNC_FILE was NOT advanced (still $LAST_SYNC) since not everything applied -- this keeps the unresolved"
    echo "files from silently falling out of scope on the next run."
    echo "Once you've manually reconciled the files above, advance it yourself:"
    echo "  echo $TARGET > $SYNC_FILE && git add $SYNC_FILE && git commit -m 'chore: sync kei to $TARGET' -- $SYNC_FILE && git tag -f kei-sync $TARGET"
fi

echo
echo "To abandon this attempt: git checkout $ORIGINAL_BRANCH && git branch -D $WORKBRANCH"
