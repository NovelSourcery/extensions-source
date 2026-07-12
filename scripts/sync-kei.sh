#!/usr/bin/env bash
set -euo pipefail

# Pulls shared code/build-system/library changes from upstream keiyoushi
# (remote: kei), excluding extension source we don't want in our history
# (src/, lib-multisrc/) and anything listed in .kei-sync-ignore (one
# repo-relative path per line, '#' comments allowed) -- use that file for
# paths where we've permanently, intentionally diverged from upstream and
# never want kei's version auto-applied again.
#
# Two modes:
#   scripts/sync-kei.sh            Applies each changed file's diff:
#                                     - clean applies are staged
#                                     - conflicting applies fall back to a
#                                       3-way merge, leaving conflict
#                                       markers in the file and the path
#                                       staged as unmerged, for manual
#                                       resolution in place
#                                     - files that can't be applied at all
#                                       (including binary files that
#                                       diverged locally) are left
#                                       untouched, with the raw upstream
#                                       diff (or blob references, for
#                                       binaries) saved under
#                                       .git/kei-sync-pending/
#                                   .kei-sync is committed automatically
#                                   once every file at least applied
#                                   (cleanly or with conflict markers) --
#                                   if some files couldn't be applied at
#                                   all, it's left unadvanced so the next
#                                   run doesn't lose track of them.
#
#   scripts/sync-kei.sh --status   Read-only. For every file that changed
#                                   between .kei-sync and kei/main, checks
#                                   whether the current working tree
#                                   already matches upstream (resolved),
#                                   still matches the old base exactly
#                                   (untouched), or differs from both
#                                   (diverged -- in-progress work, unresolved
#                                   conflict markers, or a fork
#                                   customization layered on top of an
#                                   already-incorporated upstream change,
#                                   e.g. a proto field we added by hand --
#                                   those never leave this bucket, judge by
#                                   the printed diff size, not the label --
#                                   or, better, by the [marked: ...]
#                                   annotation: if .git/kei-sync-decisions.jsonl
#                                   exists (written by the kei-sync-vscode
#                                   helper at the moment you actually
#                                   resolve a conflict) and `jq` is
#                                   installed, that real decision is used
#                                   instead of a diff-size guess.
#                                   If something should never be touched by
#                                   sync again, put it in .kei-sync-ignore.
#                                   Safe to re-run any time; makes no
#                                   changes and exits non-zero while
#                                   anything is still pending.
#
# Both modes also print, up front, every rename kei's history shows for this
# range (using a low 10% similarity threshold -- default git rename detection
# is 50%, which misses heavily-rewritten renames entirely, exactly the ones
# most likely to carry fork customizations someone needs to re-port) and
# every pure deletion (no matching replacement). For a rename whose old path
# has local changes, the fork's own edit to that old file (just our changes,
# not the whole file) is saved to
# .git/kei-sync-pending/<oldpath>.fork-customizations.diff -- a ready-made
# checklist of what to port into the new file, instead of having to
# reconstruct it from memory. --status additionally compares the CURRENT
# working tree (not just the pending range) against kei's tree to catch
# files we're still carrying that no longer exist upstream at all -- usually
# the old half of a rename that never got deleted.
#
# Both modes append a timestamped record (range, branch, per-file buckets)
# to .git/kei-sync.log, so "what commit did I last run this against" is
# always answerable without digging through branch names or reject-dir
# leftovers. The log is untracked/local-only, same as kei-sync-pending.
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

REMOTE=kei
BRANCH=main
SYNC_FILE=.kei-sync
IGNORE_FILE=.kei-sync-ignore
IGNORE_PATHS=('src' 'lib-multisrc')
GIT_DIR="$(git rev-parse --git-dir 2>/dev/null || echo .git)"
REJECT_DIR="$GIT_DIR/kei-sync-pending"
LOG_FILE="$GIT_DIR/kei-sync.log"
DECISIONS_FILE="$GIT_DIR/kei-sync-decisions.jsonl"

print_help() {
    cat <<'EOF'
Usage: scripts/sync-kei.sh [--status]

Pulls shared code/build-system/library changes from upstream keiyoushi
(remote: kei) into this fork, excluding extension source (src/,
lib-multisrc/) and anything listed in .kei-sync-ignore.

  (no args)   Apply mode. Applies each changed file's diff (clean apply,
              3-way conflict markers, or left untouched with a saved
              patch under .git/kei-sync-pending/ if it can't apply at
              all). Commits the .kei-sync bump automatically once every
              file at least applied. Creates a throwaway branch
              kei-sync-<sha>; nothing is pushed.

  --status    Read-only. Reports which pending files already match
              upstream, are untouched, or diverge from both, plus any files
              you're still carrying that no longer exist upstream at all.
              Safe to re-run any time; exits non-zero while anything's
              pending.

  -h, --help  Show this help.

Both modes print detected renames (10% similarity threshold -- catches
heavily-rewritten renames default git detection misses) and pure deletions
up front. For a rename with local changes on the old side, the fork's own
edit to that file is saved separately so it's obvious what needs porting
into the new file, instead of relying on memory.

Files:
  .kei-sync                  Tracked. Last-synced kei/main commit.
  .kei-sync-ignore            Tracked, optional. Paths permanently excluded
                              from sync, one per line, '#' comments allowed.
  .git/kei-sync-pending/      Untracked. Raw diffs (+stderr) for files that
                              couldn't be applied at all, plus
                              <path>.fork-customizations.diff for renames
                              with local changes. Regenerated each run.
  .git/kei-sync.log           Untracked. Timestamped record of every run.
  .git/kei-sync-decisions.jsonl
                              Untracked, optional. Written by the
                              kei-sync-vscode helper; read by --status to
                              annotate Diverged entries with your actual
                              resolution decision instead of guessing from
                              diff size.
EOF
}

cd "$(git rev-parse --show-toplevel)"

MODE=apply
case "${1:-}" in
    "") ;;
    --status) MODE=status ;;
    -h|--help) print_help; exit 0 ;;
    *)
        echo "error: unknown argument '$1' (supported: --status, -h/--help)" >&2
        exit 1
        ;;
esac

if [[ -n "${2:-}" ]]; then
    echo "error: unexpected extra argument '$2'" >&2
    exit 1
fi

if [[ "$MODE" == apply && -n "$(git status --porcelain)" ]]; then
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

if [[ -f "$IGNORE_FILE" ]]; then
    while IFS= read -r line || [[ -n "$line" ]]; do
        line="${line%%#*}"
        line="${line#"${line%%[![:space:]]*}"}"
        line="${line%"${line##*[![:space:]]}"}"
        [[ -z "$line" ]] && continue
        IGNORE_PATHS+=("$line")
    done < "$IGNORE_FILE"
fi

EXCLUDE_PATHSPECS=()
for p in "${IGNORE_PATHS[@]}"; do
    EXCLUDE_PATHSPECS+=(":!$p")
done

regex_escape() {
    printf '%s' "$1" | sed 's/[.[\*^$()+?{}|\/]/\\&/g'
}

# True if "$1" is under (or exactly) one of IGNORE_PATHS.
is_ignored_path() {
    local p="$1" ig
    for ig in "${IGNORE_PATHS[@]}"; do
        [[ "$p" == "$ig" || "$p" == "$ig"/* ]] && return 0
    done
    return 1
}

filter_ignored() {
    local result ig esc
    result=$(cat)
    for ig in "${IGNORE_PATHS[@]}"; do
        esc=$(regex_escape "$ig")
        result=$(grep -vE "^${esc}(/|\$)" <<< "$result" || true)
    done
    printf '%s\n' "$result"
}

ORIGINAL_BRANCH=$(git branch --show-current)
LAST_SYNC=$(<"$SYNC_FILE")
WORKBRANCH=""
APPLIED=()
CONFLICTED=()
FAILED=()
RESOLVED=()
UNTOUCHED=()
DIVERGED=()

write_bucket() {
    local label="$1"
    shift
    if [[ $# -gt 0 ]]; then
        echo "$label ($#):"
        printf '  %s\n' "$@"
    fi
}

write_log() {
    local status="$1"
    {
        echo "=== $(date -u +%Y-%m-%dT%H:%M:%SZ) ==="
        echo "mode: $MODE"
        echo "remote: $REMOTE/$BRANCH"
        echo "range: $LAST_SYNC..$TARGET"
        [[ -n "$WORKBRANCH" ]] && echo "workbranch: $WORKBRANCH"
        echo "status: $status"
        if [[ "$MODE" == status ]]; then
            write_bucket "resolved" "${RESOLVED[@]}"
            write_bucket "untouched" "${UNTOUCHED[@]}"
            write_bucket "diverged" "${DIVERGED[@]}"
        else
            write_bucket "applied" "${APPLIED[@]}"
            write_bucket "conflicted" "${CONFLICTED[@]}"
            write_bucket "failed" "${FAILED[@]}"
        fi
        echo
    } >> "$LOG_FILE"
    echo "Logged to $LOG_FILE"
}

# Renames are the highest-risk case: a low similarity threshold (kei's own
# rename detection defaults to 50%, which misses heavily-rewritten renames
# entirely -- exactly the ones most likely to carry fork customizations that
# need manual porting) so a real rename shows up as a "revert to a bare
# deletion patch" here without this report, easy to silently lose track of.
report_renames_and_deletions() {
    local raw
    raw=$(git diff -M10% --name-status "$LAST_SYNC" "$TARGET" -- . "${EXCLUDE_PATHSPECS[@]}")
    [[ -z "$raw" ]] && return 0

    local renames=() pure_deletions=()
    local rstatus rrest
    while IFS=$'\t' read -r rstatus rrest; do
        case "$rstatus" in
            R*)
                renames+=("$((10#${rstatus#R}))|${rrest%%$'\t'*}|${rrest#*$'\t'}")
                ;;
            D)
                pure_deletions+=("$rrest")
                ;;
        esac
    done <<< "$raw"

    if [[ ${#renames[@]} -gt 0 ]]; then
        echo "Renames detected upstream (${#renames[@]}) -- make sure both sides get handled:"
        local r sim old new
        for r in "${renames[@]}"; do
            sim="${r%%|*}"; r="${r#*|}"
            old="${r%%|*}"; new="${r#*|}"
            if [[ ! -e "$old" ]]; then
                # Already gone locally -- either auto-applied cleanly earlier,
                # or already handled by hand. Nothing to diff; just sanity
                # check the replacement actually exists.
                if [[ -e "$new" ]]; then
                    echo "  $old -> $new (${sim}% similar, already deleted locally)"
                else
                    echo "  $old -> $new (${sim}% similar, already deleted locally -- WARNING: $new doesn't exist either)"
                fi
            elif git diff --quiet "$LAST_SYNC" -- "$old" 2>/dev/null; then
                echo "  $old -> $new (${sim}% similar, no local changes, applies automatically)"
            else
                mkdir -p "$REJECT_DIR/$(dirname "$old")"
                git diff --no-renames "$LAST_SYNC" -- "$old" > "$REJECT_DIR/$old.fork-customizations.diff"
                echo "  $old -> $new (${sim}% similar, HAS local fork customizations)"
                echo "      our changes to the old file: $REJECT_DIR/$old.fork-customizations.diff -- port these into $new before deleting $old"
            fi
        done
        echo
    fi

    if [[ ${#pure_deletions[@]} -gt 0 ]]; then
        echo "Pure deletions upstream (${#pure_deletions[@]}, no matching replacement detected) -- consider deleting your copy too:"
        printf '  %s\n' "${pure_deletions[@]}"
        echo
    fi
}

# Compares our CURRENT working tree (not just the pending sync range) against
# kei's TARGET tree, to catch files we're still carrying that kei doesn't
# have at all -- most often the "old half" of a rename that never got
# deleted. Split into orphaned (existed in kei as of our last sync, so it's
# very likely a leftover) vs. fork-original (never existed upstream, so it's
# probably intentional -- but flagged so that's a conscious call, not an
# accident, and so it can be added to .kei-sync-ignore if it should stay).
report_local_only_files() {
    local ours target last_sync
    ours=$( (git ls-files --cached --others --exclude-standard) | while IFS= read -r f; do
        [[ -e "$f" ]] && printf '%s\n' "$f"
    done | filter_ignored | sort -u)
    target=$(git ls-tree -r --name-only "$TARGET" | sort -u)
    last_sync=$(git ls-tree -r --name-only "$LAST_SYNC" | sort -u)

    local not_in_target orphaned fork_original
    not_in_target=$(comm -23 <(printf '%s\n' "$ours") <(printf '%s\n' "$target"))
    [[ -z "$not_in_target" ]] && return 0

    orphaned=$(comm -12 <(printf '%s\n' "$not_in_target") <(printf '%s\n' "$last_sync"))
    fork_original=$(comm -23 <(printf '%s\n' "$not_in_target") <(printf '%s\n' "$last_sync"))

    if [[ -n "$orphaned" ]]; then
        local -a orphaned_arr
        mapfile -t orphaned_arr <<< "$orphaned"
        echo "Orphaned (existed in kei as of our last sync point, gone from kei's tree now -- likely needs deleting):"
        printf '  %s\n' "${orphaned_arr[@]}"
        echo
    fi
    if [[ -n "$fork_original" ]]; then
        local -a fork_original_arr
        mapfile -t fork_original_arr <<< "$fork_original"
        echo "Fork-original (never existed upstream at all -- confirm intentional, or add to $IGNORE_FILE):"
        printf '  %s\n' "${fork_original_arr[@]}"
        echo
    fi
}

echo "Fetching $REMOTE..."
git fetch "$REMOTE" --quiet

TARGET=$(git rev-parse "$REMOTE/$BRANCH")

if [[ "$LAST_SYNC" == "$TARGET" ]]; then
    echo "Already up to date with $REMOTE/$BRANCH ($TARGET)."
    write_log "up-to-date"
    exit 0
fi

if [[ "$MODE" == status ]]; then
    report_renames_and_deletions

    mapfile -d '' -t FILES < <(git diff -z --name-only --no-renames "$LAST_SYNC" "$TARGET" -- . "${EXCLUDE_PATHSPECS[@]}")

    HAVE_DECISIONS=0
    if [[ -f "$DECISIONS_FILE" ]] && command -v jq >/dev/null 2>&1; then
        HAVE_DECISIONS=1
        STATUS_BRANCH=$(git branch --show-current)
    fi

    decision_annotation() {
        # Prints "[marked: <decision> - <note>]" for the most recent
        # matching-branch decision on path "$1", or nothing if there isn't
        # one. .kei-sync-decisions.jsonl is written by the kei-sync-vscode
        # helper at the moment a conflict is actually resolved -- when
        # present, it's more trustworthy than guessing from diff size.
        [[ "$HAVE_DECISIONS" -eq 1 ]] || return 0
        local line
        line=$(jq -c --arg p "$1" --arg b "$STATUS_BRANCH" \
            'select(.path == $p and .syncBranch == $b)' "$DECISIONS_FILE" 2>/dev/null | tail -n1)
        [[ -n "$line" ]] || return 0
        local dec note
        dec=$(jq -r '.decision' <<<"$line" 2>/dev/null)
        note=$(jq -r '.note' <<<"$line" 2>/dev/null)
        if [[ -n "$note" && "$note" != "null" ]]; then
            echo " [marked: $dec - $note]"
        else
            echo " [marked: $dec]"
        fi
    }

    for f in "${FILES[@]}"; do
        if git diff --quiet "$TARGET" -- "$f" 2>/dev/null; then
            RESOLVED+=("$f")
        elif git diff --quiet "$LAST_SYNC" -- "$f" 2>/dev/null; then
            UNTOUCHED+=("$f")
        elif [[ -f "$f" ]] && grep -q '^<<<<<<< ' -- "$f" 2>/dev/null; then
            DIVERGED+=("$f (unresolved conflict markers)$(decision_annotation "$f")")
        else
            # Small diffs here are often a fork customization living
            # alongside an already-incorporated upstream change (e.g. a
            # proto field we added by hand) -- those will never disappear
            # from this bucket, by design. Large diffs are more likely
            # genuinely unresolved. Eyeball it, don't just trust the count
            # -- or check for a [marked: ...] annotation, which reflects
            # an actual decision instead of a guess.
            STAT=$(git diff --no-renames --numstat "$TARGET" -- "$f" 2>/dev/null | awk '{print "+"$1" -"$2" vs upstream"}')
            DIVERGED+=("$f ($STAT)$(decision_annotation "$f")")
        fi
    done

    echo
    echo "Status for $LAST_SYNC..$TARGET (excluding src/, lib-multisrc/, and $IGNORE_FILE entries):"
    echo
    echo "Resolved (matches upstream already): ${#RESOLVED[@]} file(s)."
    if [[ ${#UNTOUCHED[@]} -gt 0 ]]; then
        echo "Untouched (still exactly as before the sync): ${#UNTOUCHED[@]} file(s):"
        printf '  %s\n' "${UNTOUCHED[@]}"
    fi
    if [[ ${#DIVERGED[@]} -gt 0 ]]; then
        echo "Diverged (differs from both the old base and upstream): ${#DIVERGED[@]} file(s):"
        printf '  %s\n' "${DIVERGED[@]}"
    fi
    echo

    report_local_only_files

    write_log "status-check"

    if [[ ${#UNTOUCHED[@]} -eq 0 && ${#DIVERGED[@]} -eq 0 ]]; then
        echo "Everything in range matches upstream. Safe to advance $SYNC_FILE:"
        echo "  echo $TARGET > $SYNC_FILE && git add $SYNC_FILE && git commit -m 'chore: sync kei to $TARGET' -- $SYNC_FILE && git tag -f kei-sync $TARGET"
        exit 0
    else
        echo
        echo "Add any intentional permanent deviations to $IGNORE_FILE (one repo-relative path per line) so they stop showing up here and never get re-applied by a real sync run."
        exit 1
    fi
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

rm -rf "$REJECT_DIR"

echo
report_renames_and_deletions

echo "Changes to pull in ($LAST_SYNC..$TARGET, excluding src/, lib-multisrc/, and $IGNORE_FILE entries):"
git --no-pager diff --stat --no-renames "$LAST_SYNC" "$TARGET" -- . "${EXCLUDE_PATHSPECS[@]}"
echo

mapfile -d '' -t FILES < <(git diff -z --name-only --no-renames "$LAST_SYNC" "$TARGET" -- . "${EXCLUDE_PATHSPECS[@]}")

if [[ ${#FILES[@]} -eq 0 ]]; then
    echo "No relevant changes outside excluded paths."
    echo "$TARGET" > "$SYNC_FILE"
    git add "$SYNC_FILE"
    git commit -q -m "chore: sync kei to $TARGET (no relevant changes)" -- "$SYNC_FILE"
    git tag -f kei-sync "$TARGET" >/dev/null
    write_log "synced (no relevant changes)"
    exit 0
fi

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

    APPLY_ERR=$(mktemp)
    if git diff --no-renames "$LAST_SYNC" "$TARGET" -- "$f" | git apply --index --3way - 2>"$APPLY_ERR"; then
        APPLIED+=("$f")
        rm -f "$APPLY_ERR"
    elif [[ -n "$(git status --porcelain -- "$f")" ]]; then
        # git apply --3way exits non-zero even on a successful 3-way merge
        # with conflicts -- a dirty status here means it left usable
        # conflict markers in place rather than failing outright.
        CONFLICTED+=("$f")
        rm -f "$APPLY_ERR"
    else
        FAILED+=("$f")
        mkdir -p "$REJECT_DIR/$(dirname "$f")"
        git diff --no-renames "$LAST_SYNC" "$TARGET" -- "$f" > "$REJECT_DIR/$f.patch"
        mv "$APPLY_ERR" "$REJECT_DIR/$f.patch.err" 2>/dev/null || rm -f "$APPLY_ERR"
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
    echo
    write_log "synced"
else
    echo
    echo "Raw upstream diffs (or blob references, for binaries) for unapplied files are saved under $REJECT_DIR/."
    echo "$SYNC_FILE was NOT advanced (still $LAST_SYNC) since not everything applied -- this keeps the unresolved"
    echo "files from silently falling out of scope on the next run."
    echo "Once you've manually reconciled a file, either fix it up and re-add it, or (if you've decided it should"
    echo "permanently diverge from upstream) add it to $IGNORE_FILE so it's excluded from future syncs too."
    echo "Once every file above is dealt with, advance $SYNC_FILE yourself:"
    echo "  echo $TARGET > $SYNC_FILE && git add $SYNC_FILE && git commit -m 'chore: sync kei to $TARGET' -- $SYNC_FILE && git tag -f kei-sync $TARGET"
    echo
    write_log "partial (kei-sync not advanced)"
fi

echo
echo "Run 'scripts/sync-kei.sh --status' any time to check what's resolved vs. still pending, without touching anything."
echo "To abandon this attempt: git checkout $ORIGINAL_BRANCH && git branch -D $WORKBRANCH"
