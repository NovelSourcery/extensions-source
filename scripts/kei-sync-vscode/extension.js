// Local-only helper for extensions-source's scripts/sync-kei.sh workflow.
//
// While a sync is in progress (current branch matches kei-sync-<sha>, or
// .git/kei-sync-pending/ exists), watches the built-in Git extension's
// merge-conflict state and, whenever a file leaves the conflicted set --
// resolved via the Merge Editor or by hand, we can't tell which and don't
// need to -- asks how it was resolved and appends a record to
// .git/kei-sync-decisions.jsonl. scripts/sync-kei.sh --status reads that
// file to annotate its "Diverged" bucket with the real decision instead of
// guessing from diff size. Fully inert outside a sync (see isGateOpen).
//
// See README.md for the on-disk schema and how to build/install this.

const vscode = require('vscode');
const fs = require('fs');
const path = require('path');

const SYNC_BRANCH_RE = /^kei-sync-[0-9a-f]{12}$/;
const DECISION_BUTTONS = {
    'Kept ours': 'kept-ours',
    'Took upstream': 'took-upstream',
    'Merged both': 'merged-both',
};

/** @type {Map<vscode.SourceControl['rootUri']['fsPath'], RepoState>} */
const repoStates = new Map();

/**
 * @typedef {Object} RepoState
 * @property {Set<string>} prevMergeSet     snapshot of conflicted paths as of the last onDidChange
 * @property {Set<string>} allSeenConflicted every path ever seen conflicted this session (never shrinks)
 * @property {vscode.StatusBarItem} statusBar
 * @property {NodeJS.Timeout | undefined} debounce
 */

function activate(context) {
    const gitExt = vscode.extensions.getExtension('vscode.git');
    if (!gitExt) return;
    // extensionDependencies in package.json guarantees vscode.git is already active by now.
    const git = gitExt.exports.getAPI(1);

    for (const repo of git.repositories) watchRepo(repo, context);
    context.subscriptions.push(git.onDidOpenRepository((repo) => watchRepo(repo, context)));

    context.subscriptions.push(
        vscode.commands.registerCommand('keiSync.finalize', () => finalize(git)),
    );
}

function gitDir(repo) {
    return path.join(repo.rootUri.fsPath, '.git');
}

function isGateOpen(repo) {
    const branch = repo.state.HEAD && repo.state.HEAD.name;
    if (branch && SYNC_BRANCH_RE.test(branch)) return true;
    return fs.existsSync(path.join(gitDir(repo), 'kei-sync-pending'));
}

function relPath(repo, uri) {
    return path.relative(repo.rootUri.fsPath, uri.fsPath).split(path.sep).join('/');
}

function decisionsFile(repo) {
    return path.join(gitDir(repo), 'kei-sync-decisions.jsonl');
}

function watchRepo(repo, context) {
    const state = {
        prevMergeSet: new Set(),
        allSeenConflicted: new Set(),
        statusBar: vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 100),
        debounce: undefined,
    };
    repoStates.set(repo.rootUri.fsPath, state);
    context.subscriptions.push(state.statusBar);

    const onChange = () => {
        clearTimeout(state.debounce);
        state.debounce = setTimeout(() => handleChange(repo, state), 50);
    };
    context.subscriptions.push(repo.state.onDidChange(onChange));
    onChange();
}

function handleChange(repo, state) {
    if (!isGateOpen(repo)) {
        state.statusBar.hide();
        state.prevMergeSet = new Set();
        return;
    }

    const currentSet = new Set(repo.state.mergeChanges.map((c) => relPath(repo, c.uri)));
    for (const p of currentSet) state.allSeenConflicted.add(p);

    const justResolved = [...state.prevMergeSet].filter((p) => !currentSet.has(p));
    state.prevMergeSet = currentSet;

    for (const p of justResolved) promptDecision(repo, p);

    refreshStatusBar(repo, state);
}

function promptDecision(repo, relativePath) {
    vscode.window
        .showInformationMessage(`Kei Sync: how was "${relativePath}" resolved?`, 'Kept ours', 'Took upstream', 'Merged both', 'Skip')
        .then((choice) => {
            if (!choice) return; // dismissed -- leave untagged, finalize() will surface it
            const decision = choice === 'Skip' ? 'skip' : DECISION_BUTTONS[choice];
            if (choice === 'Skip') {
                appendDecision(repo, relativePath, decision, '');
                return;
            }
            vscode.window
                .showInputBox({ prompt: `Optional note for ${relativePath}`, placeHolder: 'why (optional)' })
                .then((note) => appendDecision(repo, relativePath, decision, note || ''));
        });
}

function appendDecision(repo, relativePath, decision, note) {
    const record = {
        path: relativePath,
        decision,
        note,
        timestamp: new Date().toISOString(),
        syncBranch: (repo.state.HEAD && repo.state.HEAD.name) || '',
    };
    fs.appendFileSync(decisionsFile(repo), JSON.stringify(record) + '\n', 'utf8');
    const state = repoStates.get(repo.rootUri.fsPath);
    if (state) refreshStatusBar(repo, state);
}

function readDecisions(repo) {
    const file = decisionsFile(repo);
    if (!fs.existsSync(file)) return [];
    const branch = (repo.state.HEAD && repo.state.HEAD.name) || '';
    return fs
        .readFileSync(file, 'utf8')
        .split('\n')
        .filter(Boolean)
        .map((line) => {
            try {
                return JSON.parse(line);
            } catch {
                return null;
            }
        })
        .filter((r) => r && r.syncBranch === branch);
}

function refreshStatusBar(repo, state) {
    const decisions = readDecisions(repo);
    const decidedPaths = new Set(decisions.map((d) => d.path));
    const undecided = [...state.allSeenConflicted].filter(
        (p) => !state.prevMergeSet.has(p) && !decidedPaths.has(p),
    );
    state.statusBar.text = `$(git-merge) Kei Sync: ${decisions.length} tagged, ${undecided.length} untagged`;
    state.statusBar.tooltip = 'Click to finalize this kei-sync\'s decisions';
    state.statusBar.command = 'keiSync.finalize';
    state.statusBar.show();
}

async function finalize(git) {
    const repo = git.repositories.find((r) => isGateOpen(r));
    if (!repo) {
        vscode.window.showInformationMessage('Kei Sync: no sync in progress in any open repository.');
        return;
    }
    const state = repoStates.get(repo.rootUri.fsPath);
    const allSeenConflicted = state ? state.allSeenConflicted : new Set();
    const stillConflicted = new Set(repo.state.mergeChanges.map((c) => relPath(repo, c.uri)));
    const decisions = readDecisions(repo);
    const decidedPaths = new Set(decisions.map((d) => d.path));
    const undecided = [...allSeenConflicted].filter(
        (p) => !stillConflicted.has(p) && !decidedPaths.has(p),
    );

    const groups = { 'kept-ours': [], 'took-upstream': [], 'merged-both': [], skip: [] };
    for (const d of decisions) (groups[d.decision] || (groups[d.decision] = [])).push(d);

    const lines = [`# Kei Sync decisions -- ${repo.state.HEAD && repo.state.HEAD.name}`, ''];
    for (const [label, items] of Object.entries(groups)) {
        if (!items.length) continue;
        lines.push(`## ${label} (${items.length})`, '');
        for (const d of items) lines.push(`- \`${d.path}\`${d.note ? ` -- ${d.note}` : ''}`);
        lines.push('');
    }
    if (undecided.length) {
        lines.push(`## untagged -- resolved without a recorded decision (${undecided.length})`, '');
        for (const p of undecided) lines.push(`- \`${p}\``);
        lines.push('');
    }
    if (stillConflicted.size) {
        lines.push(`## still unresolved -- not conflict-free yet (${stillConflicted.size})`, '');
        for (const p of stillConflicted) lines.push(`- \`${p}\``);
        lines.push('');
    }
    const summary = lines.join('\n');

    await vscode.env.clipboard.writeText(summary);
    const doc = await vscode.workspace.openTextDocument({ content: summary, language: 'markdown' });
    await vscode.window.showTextDocument(doc);

    const file = decisionsFile(repo);
    if (!fs.existsSync(file)) return;
    const clear = await vscode.window.showWarningMessage(
        `Clear ${path.basename(file)} for this branch? A backup is kept alongside it.`,
        { modal: true },
        'Clear',
    );
    if (clear !== 'Clear') return;
    const branch = (repo.state.HEAD && repo.state.HEAD.name) || 'unknown';
    fs.copyFileSync(file, path.join(gitDir(repo), `kei-sync-decisions.${branch}.jsonl.bak`));
    fs.rmSync(file);
    if (state) {
        state.allSeenConflicted = new Set();
        state.prevMergeSet = new Set();
        refreshStatusBar(repo, state);
    }
}

function deactivate() {}

module.exports = { activate, deactivate };
