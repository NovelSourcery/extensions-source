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
#
# Syncs pull up to a PINNED local branch (default: kei), not the live
# kei/main tip -- that remote-tracking ref moves every time anyone fetches,
# which makes "what am I about to pull in" a moving target. Advance the pin
# yourself, on your own schedule:
#   git fetch kei && git branch -f kei kei/main
# Override which local ref to pin against with --branch <ref>.

REMOTE=kei
UPSTREAM_BRANCH=main
PIN_REF=kei
SYNC_FILE=.kei-sync
IGNORE_FILE=.kei-sync-ignore
REWRITE_FILE=.kei-sync-rewrite
IGNORE_PATHS=('src' 'lib-multisrc')
GIT_DIR="$(git rev-parse --git-dir 2>/dev/null || echo .git)"
REJECT_DIR="$GIT_DIR/kei-sync-pending"
LOG_FILE="$GIT_DIR/kei-sync.log"
DECISIONS_FILE="$GIT_DIR/kei-sync-decisions.jsonl"

print_help() {
    cat <<'EOF'
Usage: scripts/sync-kei.sh [--status] [--branch <ref>]

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

  --branch <ref>
              Local ref to pull up to (default: kei). Deliberately NOT
              kei/main -- that remote-tracking ref moves every time anyone
              fetches, which makes it impossible to control when new
              upstream commits become visible to a sync. Advance the pin
              yourself when you're ready:
                git fetch kei && git branch -f kei kei/main

  -h, --help  Show this help.

Both modes print detected renames (10% similarity threshold -- catches
heavily-rewritten renames default git detection misses) and pure deletions
up front. For a rename with local changes on the old side, the fork's own
edit to that file is saved separately so it's obvious what needs porting
into the new file, instead of relying on memory.

Files:
  .kei-sync                  Tracked. Last-synced commit (as of the pinned
                              branch at the time of that sync).
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
while [[ $# -gt 0 ]]; do
    case "$1" in
        --status) MODE=status; shift ;;
        --branch)
            [[ -n "${2:-}" ]] || { echo "error: --branch requires a value" >&2; exit 1; }
            PIN_REF="$2"
            shift 2
            ;;
        -h|--help) print_help; exit 0 ;;
        *)
            echo "error: unknown argument '$1' (supported: --status, --branch <ref>, -h/--help)" >&2
            exit 1
            ;;
    esac
done

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

# Permanent local overrides (repo name, product branding, signing key, CDN
# URLs, ...) that live on the same lines upstream routinely touches for its
# own reasons (e.g. its own version-bump commits). Without this, every such
# commit re-triggers a conflict on a line whose resolution is always "keep
# ours" -- diff-context tricks can't fix it because the *target* line itself
# is what changed on both sides. REWRITE_RULES holds pattern/replacement
# pairs (extended regex, applied in file order -- put longer/more-specific
# patterns first so a shorter pattern doesn't consume part of a longer one's
# match first); RSEP is a delimiter unlikely to appear in a pattern or
# replacement (unlike '/' or '@', which show up in URLs and emails).
REWRITE_RULES=()
RSEP=$'\x01'
if [[ -f "$REWRITE_FILE" ]]; then
    while IFS=$'\t' read -r pattern replacement || [[ -n "$pattern" ]]; do
        [[ -z "$pattern" || "$pattern" == \#* ]] && continue
        REWRITE_RULES+=("$pattern" "$replacement")
    done < "$REWRITE_FILE"
fi

# Applies REWRITE_RULES to stdin, in order. Pass-through if there are none.
# Matching is case-insensitive (GNU sed's `I` flag) -- these patterns target
# specific known phrases/URLs, not generic text, so the overreach risk is
# low, and it avoids a real failure mode: if only one casing of a pattern is
# normalized, a blob that already contains a *different* casing of our own
# replacement text (independent of upstream's wording entirely) is left
# untouched and ends up looking like a real conflict against the freshly
# normalized other side, even though it's the same string.
apply_rewrite() {
    if [[ ${#REWRITE_RULES[@]} -eq 0 ]]; then
        cat
        return
    fi
    local cmd=(sed -E) i=0
    while [[ $i -lt ${#REWRITE_RULES[@]} ]]; do
        cmd+=(-e "s${RSEP}${REWRITE_RULES[$i]}${RSEP}${REWRITE_RULES[$((i+1))]}${RSEP}gI")
        i=$((i+2))
    done
    "${cmd[@]}"
}

# Patch for path "$1" to feed into `git apply`, computed between LAST_SYNC
# and TARGET. When REWRITE_RULES is empty this is just the plain git diff
# (identical to the old behavior). Otherwise both blobs are piped through
# apply_rewrite first, so a hunk that only exists because of a permanently-
# overridden value (see .kei-sync-rewrite) never gets generated at all --
# handles missing-on-either-side (added/deleted) blobs correctly. Prints
# nothing if the rewritten blobs are identical (i.e. the only difference was
# something we've permanently overridden).
build_patch() {
    local f="$1"
    if [[ ${#REWRITE_RULES[@]} -eq 0 ]]; then
        git diff --no-renames "$LAST_SYNC" "$TARGET" -- "$f"
        return
    fi
    local tmp_base tmp_target base_exists=1 target_exists=1
    local base_sha="0000000000000000000000000000000000000000"
    local target_sha="0000000000000000000000000000000000000000"
    tmp_base=$(mktemp)
    tmp_target=$(mktemp)
    if git cat-file -e "$LAST_SYNC:$f" 2>/dev/null; then
        git show "$LAST_SYNC:$f" | apply_rewrite > "$tmp_base"
        base_exists=0
        # --3way needs a real blob it can look up by SHA to use as the merge
        # base -- a bare unified diff (no `index` line) makes git apply fall
        # back to strict context-matched application, which either succeeds
        # outright or fails hard with no conflict markers at all, instead of
        # a proper 3-way merge. Writing the rewritten content as a loose
        # object (harmless, unreferenced by any tree/commit) gives it one.
        base_sha=$(git hash-object -w -- "$tmp_base")
    fi
    if git cat-file -e "$TARGET:$f" 2>/dev/null; then
        git show "$TARGET:$f" | apply_rewrite > "$tmp_target"
        target_exists=0
        target_sha=$(git hash-object -w -- "$tmp_target")
    fi
    if [[ "$base_sha" != "$target_sha" ]]; then
        printf 'diff --git a/%s b/%s\n' "$f" "$f"
        if [[ $base_exists -ne 0 ]]; then
            printf 'new file mode 100644\n'
        elif [[ $target_exists -ne 0 ]]; then
            printf 'deleted file mode 100644\n'
        fi
        printf 'index %s..%s 100644\n' "${base_sha:0:12}" "${target_sha:0:12}"
        if [[ $base_exists -eq 0 ]]; then printf -- '--- a/%s\n' "$f"; else printf -- '--- /dev/null\n'; fi
        if [[ $target_exists -eq 0 ]]; then printf '+++ b/%s\n' "$f"; else printf '+++ /dev/null\n'; fi
        diff -u "$tmp_base" "$tmp_target" | tail -n +3 || true
    fi
    rm -f "$tmp_base" "$tmp_target"
}

regex_escape() {
    printf '%s' "$1" | sed 's/[.[\*^$()+?{}|]/\\&/g'
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

# True if the blob at "$1:$2" is byte-identical to the current working-tree
# file at path "$2" (or both are absent). Deliberately NOT `git diff --quiet
# <rev> -- <path>` -- that form only compares the commit against the INDEX,
# so an untracked file (never `git add`ed -- exactly what a hand-recreated
# rename target looks like right after a failed apply) reads as "doesn't
# exist" even though it's sitting right there on disk with the right
# content, silently misclassifying it.
blob_matches_worktree() {
    local rev="$1" path="$2"
    local in_commit=1 on_disk=1
    git cat-file -e "$rev:$path" 2>/dev/null && in_commit=0
    [[ -e "$path" ]] && on_disk=0
    if [[ $in_commit -ne 0 && $on_disk -ne 0 ]]; then
        return 0
    fi
    if [[ $in_commit -ne 0 || $on_disk -ne 0 ]]; then
        return 1
    fi
    diff -q <(git show "$rev:$path") "$path" >/dev/null 2>&1
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
        echo "pin: $PIN_REF"
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
            elif blob_matches_worktree "$LAST_SYNC" "$old"; then
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

# A branch literally named the same as the remote (our default, "kei") is
# genuinely ambiguous to bare `git rev-parse` -- resolve refs/heads/<ref>
# explicitly so we always mean the local branch, never something under
# refs/remotes/kei/*.
if git rev-parse --verify --quiet "refs/heads/$PIN_REF" >/dev/null; then
    PIN_RESOLVED="refs/heads/$PIN_REF"
elif git rev-parse --verify --quiet "$PIN_REF" >/dev/null 2>/dev/null; then
    PIN_RESOLVED="$PIN_REF"
else
    echo "error: '$PIN_REF' doesn't resolve to anything. It's meant to be a local branch you" >&2
    echo "advance yourself, on your own schedule -- not the live $REMOTE/$UPSTREAM_BRANCH tip," >&2
    echo "which moves every time anyone fetches. Bootstrap it once, then advance it whenever" >&2
    echo "you're actually ready to pull in a new batch:" >&2
    echo "  git branch $PIN_REF $REMOTE/$UPSTREAM_BRANCH" >&2
    echo "  git branch -f $PIN_REF $REMOTE/$UPSTREAM_BRANCH   # to advance it later" >&2
    exit 1
fi

TARGET=$(git rev-parse "$PIN_RESOLVED")

BEHIND=$(git rev-list --count "$PIN_RESOLVED..$REMOTE/$UPSTREAM_BRANCH" 2>/dev/null || echo 0)
if [[ "$BEHIND" -gt 0 ]]; then
    echo "($PIN_REF is $BEHIND commit(s) behind the live $REMOTE/$UPSTREAM_BRANCH tip -- advance it"
    echo " yourself with 'git branch -f $PIN_REF $REMOTE/$UPSTREAM_BRANCH' when you're ready for those.)"
    echo
fi

if [[ "$LAST_SYNC" == "$TARGET" ]]; then
    echo "Already up to date with $PIN_REF ($TARGET)."
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
        if blob_matches_worktree "$TARGET" "$f"; then
            RESOLVED+=("$f")
        elif blob_matches_worktree "$LAST_SYNC" "$f"; then
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
            if [[ -e "$f" ]] && git cat-file -e "$TARGET:$f" 2>/dev/null; then
                # grep -c exits 1 (not an error) when the count is zero, which -- since this
                # sits inside a variable assignment -- would otherwise abort the whole script
                # under set -e the moment a file has zero pure additions or zero pure removals.
                ADD_COUNT=$(diff <(git show "$TARGET:$f") "$f" 2>/dev/null | grep -c '^>' || true)
                DEL_COUNT=$(diff <(git show "$TARGET:$f") "$f" 2>/dev/null | grep -c '^<' || true)
                STAT="+$ADD_COUNT -$DEL_COUNT vs upstream"
            elif [[ -e "$f" ]]; then
                STAT="+$(wc -l < "$f") -0 vs upstream (new to us, not upstream)"
            else
                STAT="-$(git cat-file -p "$TARGET:$f" 2>/dev/null | wc -l) vs upstream (upstream has it, we don't)"
            fi
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

# Collapses any '<<<<<<< ours ... ======= ... >>>>>>> theirs' block in "$1"
# whose two sides are byte-identical. git's 3-way merge can flag a block as
# conflicting purely from how it aligned surrounding hunks -- e.g. a
# repeated boilerplate example appearing more than once nearby in the same
# file -- even when the text actually offered on both sides is identical,
# which just costs a manual resolution for zero information. Real conflicts
# (different content) are left untouched. Always rewrites the file with
# every collapsible block resolved, even if a different block in the same
# file is a genuine conflict left as markers -- no reason to make someone
# re-review a settled block just because a sibling block still needs a
# human. Returns 0 if nothing genuine was left after collapsing, 1 if real
# conflict markers remain.
collapse_identical_conflicts() {
    local f="$1" tmp
    tmp=$(mktemp)
    awk '
        BEGIN { state = 0 }
        /^<<<<<<< ours$/ { state = 1; ours = ""; next }
        state == 1 && /^=======$/ { state = 2; theirs = ""; next }
        state == 1 { ours = ours $0 "\n"; next }
        state == 2 && /^>>>>>>> theirs$/ {
            if (ours == theirs) {
                printf "%s", ours
            } else {
                printf "<<<<<<< ours\n%s=======\n%s>>>>>>> theirs\n", ours, theirs
            }
            state = 0
            next
        }
        state == 2 { theirs = theirs $0 "\n"; next }
        { print }
    ' "$f" > "$tmp"
    mv "$tmp" "$f"
    ! grep -q '^<<<<<<< ' "$f"
}

for f in "${FILES[@]}"; do
    # Binary files can't be 3-way merged textually. If our copy hasn't
    # diverged from kei's last-synced version, just take upstream's version
    # wholesale; otherwise it needs a human to pick between them.
    NUMSTAT_F1=$(git diff --no-renames --numstat "$LAST_SYNC" "$TARGET" -- "$f" | cut -f1)
    if [[ "$NUMSTAT_F1" == "-" ]]; then
        if blob_matches_worktree "$LAST_SYNC" "$f"; then
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

    # A file upstream deleted that we'd *already* deleted locally (e.g. one
    # half of a rename we already hand-ported) has no index entry for
    # --3way to reconstruct "ours" from, so it hard-fails with no useful
    # markers even though there's genuinely nothing left to do here.
    if ! git cat-file -e "$TARGET:$f" 2>/dev/null && [[ ! -e "$f" ]] && [[ -z "$(git status --porcelain -- "$f")" ]]; then
        APPLIED+=("$f (no-op: already deleted locally, matches upstream deletion)")
        continue
    fi

    APPLY_ERR=$(mktemp)
    PATCH_TMP=$(mktemp)
    build_patch "$f" > "$PATCH_TMP"
    if [[ ! -s "$PATCH_TMP" ]]; then
        # The rewrite table (see .kei-sync-rewrite) fully accounted for the
        # only difference in this range -- e.g. a permanently-overridden
        # branding/version line -- so there's nothing left to apply; the
        # worktree is already the correct resolution, whatever it says.
        APPLIED+=("$f (no-op: only difference was a permanent override)")
        rm -f "$APPLY_ERR" "$PATCH_TMP"
        continue
    fi
    if git apply --index --3way - < "$PATCH_TMP" 2>"$APPLY_ERR"; then
        APPLIED+=("$f")
        rm -f "$APPLY_ERR" "$PATCH_TMP"
    elif [[ -n "$(git status --porcelain -- "$f")" ]]; then
        # git apply --3way exits non-zero even on a successful 3-way merge
        # with conflicts -- a dirty status here means it left usable
        # conflict markers in place rather than failing outright.
        if collapse_identical_conflicts "$f"; then
            git add -- "$f"
            APPLIED+=("$f (auto-resolved: both sides identical)")
        else
            CONFLICTED+=("$f")
        fi
        rm -f "$APPLY_ERR" "$PATCH_TMP"
    else
        FAILED+=("$f")
        mkdir -p "$REJECT_DIR/$(dirname "$f")"
        mv "$PATCH_TMP" "$REJECT_DIR/$f.patch"
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
