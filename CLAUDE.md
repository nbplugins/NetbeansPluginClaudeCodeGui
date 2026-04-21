# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Test Commands

```bash
mvn package                 # Full build with tests
mvn package -DskipTests     # Build without tests
mvn nbm:nbm                 # Package as installable NBM file
mvn test                    # Run all unit tests
mvn test -Dtest=ClassName   # Run a single test class
```

**Version bump is always the first step** before implementing any fix or feature: increment the patch version in `pom.xml`. Running `mvn package` is always the last step after all changes are done.

## Release & Versioning

### Versioning scheme

Build version is computed from git tags by CI:
- Base tag `MAJOR.MINOR` (e.g. `0.17`) + commit count from it → `MAJOR.MINOR.N` (e.g. `0.17.5`)
- `pom.xml` holds `MAJOR.MINOR.0-SNAPSHOT` — only MAJOR.MINOR matters to CI; patch and SNAPSHOT suffix are ignored

### Release cycle

A release has an explicit **start** and **finish**:

**Starting a release** (only this, nothing else):
- Bump `pom.xml` to `MAJOR.MINOR.0-SNAPSHOT` → CI creates base tag `MAJOR.MINOR`, build version becomes `MAJOR.MINOR.0`
- Each subsequent push to main → version `MAJOR.MINOR.N` (N increments automatically)

**During development** — add user-visible changes to `CHANGELOG.md` (see rules below).

**Finishing a release** (only this, nothing else):
- Update `CHANGELOG.md` heading to `# MAJOR.MINOR` or `# MAJOR.MINOR (YYYY-MM-DD)` (matching current `pom.xml`) → CI sees this as the release signal, creates release tag `MAJOR.MINOR.N`, and publishes a GitHub Release. If the date is omitted, CI inserts today's date automatically.

**After a published release** — CI auto-edits `CHANGELOG.md`: the release heading `# MAJOR.MINOR (date)` is replaced with `# MAJOR.MINOR.N (date)`. Development can continue immediately; patch versions increment from the last released N.

Implemented in `build-scripts/autotag.sh` and `.github/workflows/build.yml`.

### Side releases (patch releases on an old branch)

Use when a critical fix must be shipped for an older version while `main` has already moved to a newer `MAJOR.MINOR`.

**Setup (once per side branch):**
```bash
git checkout -b release/0.17 0.18^    # commit just before the next base tag (0.18, 0.19, etc.)
git push origin release/0.17
```
`pom.xml` on this branch already has `0.17.0-SNAPSHOT` — no change needed.

**Development:** push fixes to `release/0.17`; CI builds snapshot versions automatically.
Version numbers continue from the last released patch: if `0.17.3` was the last release, the next builds are `0.17.4`, `0.17.5`, etc.

**Releasing a patch:**
- Add `# 0.17` or `# 0.17 (YYYY-MM-DD)` at the top of `CHANGELOG.md` (together with the fix's bullet). If the date is omitted, CI inserts today's date automatically.
- Push → CI creates tag `0.17.N`, publishes GitHub Release, and auto-edits `CHANGELOG.md` to `# 0.17.N (YYYY-MM-DD)`

**Next patch:** add `# 0.17` or `# 0.17 (YYYY-MM-DD)` again and repeat.

`main` is fully unaffected throughout.

### CHANGELOG.md rules

The heading `# MAJOR.MINOR` or `# MAJOR.MINOR (YYYY-MM-DD)` is the CI release signal — **only add it when finishing a release**. The date is optional; if omitted, CI inserts today's date automatically (e.g. `# 0.18` or `# 0.18 (2026-04-04)`).

During development, add bullet lines at the **very top** of `CHANGELOG.md` (above any existing heading), with no section heading. When finishing a release, add the `# MAJOR.MINOR` or `# MAJOR.MINOR (YYYY-MM-DD)` heading above those bullets.

The changelog within a release is **cumulative**: if a feature was added and later refined or fixed within the same release cycle, **update the existing bullet** rather than adding a new one. Do not add separate "Fixed" entries for bugs in functionality that was introduced in the current (unreleased) version — only the final user-visible state matters.

Every user-visible change **must** add or update a bullet at the **top** of the list (reverse chronological order — newest entries first). "User-visible" means: new feature, changed behavior, bug fix (in a previously released version), UI change, new setting, README update. Internal refactors, test-only changes, CI changes, and fixes to features not yet released do not require an entry.

Entries must describe the change from the **user's perspective** — what the user experiences, not how it was implemented. Implementation details belong in the commit message body.

Each entry must start with a past-tense verb: **Fixed**, **Added**, **Improved**, **Changed**, **Removed**, etc.

Each changelog entry must be committed **together with the code change it describes** — never in a separate commit.

### Commit message rules

Commit messages must also start with a past-tense verb (e.g. "Fixed ...", "Added ..."). The subject line describes *what* was done; the body (if needed) explains *how* or *why*.

When the commit fixes a GitHub issue:
- The CHANGELOG entry must include the full issue URL: e.g. `Fixed ... (https://github.com/nbclaudecodegui/NetbeansClaudeCodeGui/issues/N)`
- The commit subject line should match the CHANGELOG entry text (without the leading `-`)
- The commit body must include `Resolves: #N` followed by implementation explanation

When finishing a release by adding `# MAJOR.MINOR` to CHANGELOG.md, the commit message must be `"Requested release MAJOR.MINOR"` (not `"Released MAJOR.MINOR"`).

## Architecture

A NetBeans IDE plugin that embeds Claude Code CLI as a PTY-based terminal session connected to the IDE via three channels: PTY, MCP SSE, and an HTTP hook.

### Process startup

`ClaudeProcess.start(workingDir, profile, extraCliArgs, mode, resumeSessionId)`:
1. Looks up `ClaudeCodeStatusService` → gets the MCP SSE server port (default **28991**, configurable in Tools → Options).
2. **Merges** (not overwrites) `{workingDir}/.claude/settings.local.json` — only the plugin's own keys are added/updated; all other keys are left untouched:
   ```json
   {
     "mcpServers": {"netbeans": {"type":"sse","url":"http://localhost:{port}/sse"}},
     "hooks": {"PreToolUse": [{"matcher":"Edit|Write|MultiEdit","hooks":[{"type":"http","url":"http://localhost:{port}/hook"}]}]}
   }
   ```
   On session stop, the plugin removes its own keys; if the file becomes empty it is deleted.
3. Appends session flags based on `SessionMode`: `--continue` for CONTINUE_LAST, `--resume <id>` for RESUME_SPECIFIC, nothing for NEW.
4. Launches `claude` (no `--print`, `TERM=xterm-256color`) via `pty4j` `PtyProcessBuilder`.
5. `PtyTtyConnector` bridges the PTY master to JediTerm's `TtyConnector`. The full Claude TUI renders natively.

`MCPSseServer` (Jetty) starts at IDE startup (`ClaudeCodeInstaller`) and is shared across all sessions.

### Communication channels

#### PTY — terminal I/O
- **Claude → plugin:** ANSI TUI bytes rendered by JediTerm. `ScreenContentDetector` (screen-poll timer) detects interactive choice menus → `ClaudeSessionModel.setActiveChoiceMenu()` → `ChoiceMenuPanel` shown.
- **Plugin → Claude:** raw bytes to the PTY master. Text input from the input area sent as-is. Option selected → number + `\r`. Cancel → byte `0x03` (Ctrl+C / SIGINT).

#### MCP SSE — IDE tools (`/sse` + `/messages`)
Claude keeps `GET /sse` open (long-lived SSE stream). JSON-RPC requests arrive as `POST /messages` (HTTP response is always `202 Accepted`). JSON-RPC responses go back **via SSE** (not the POST body — required by the MCP SSE spec).
- **Sync tools** — handler returns result, enqueued to SSE immediately.
- **Async tools** (`permission_prompt`) — handler returns `AsyncResponse`; when the user decides, `sendAsyncToolResponse()` enqueues the JSON-RPC response.
- **Notifications** — `selection_changed` pushed to Claude on every caret move in an editor.
- Keep-alive: `: ping` SSE comments every 5 s when queue is idle.

#### PreToolUse HTTP hook (`/hook`)
Configured via `settings.local.json`. Fires before `Edit`/`Write`/`MultiEdit`.
- Claude POSTs hook JSON → `HookServlet.doPost()` computes before/after content, calls `FileDiffTab.open()`, then **blocks** on `CompletableFuture` (590 s timeout, just under Claude's 600 s limit).
- User action → `DiffTabTracker.resolveHook()` completes the future → HTTP response body:
  - `allow` → `{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"allow"}}`
  - `deny` (+ optional reason) → same with `"permissionDecision":"deny"`
  - `ask` (timeout/close) → Claude falls back to its built-in PTY permission dialog

### Component Map

The plugin follows an MVC structure for session management:

| Package | Responsibility |
|---------|---------------|
| `model/` | `ClaudeSessionModel` — session state container, `SessionLifecycle` enum, `EDIT_MODE_REGISTRY` (cross-thread), listener dispatch on EDT; `ChoiceMenuModel` — interactive-prompt data; `PromptHistoryStore`, `PromptFavoritesStore`, `HistoryEntry`, `FavoriteEntry` — persistent history/favorites; `SessionMode` — NEW / CONTINUE_LAST / RESUME_SPECIFIC / CLOSE_ONLY; `SavedSession` — record (sessionId, createdAt, lastAt, slug, customTitle, firstPrompt) |
| `controller/` | `ClaudeSessionController` — PTY process lifecycle, connector wiring, screen polling, model/edit-mode switching, `parseModelDiscovery`; `stopAndRename(name)` — stops PTY then writes custom-title entry to JSONL |
| `process/` | `ClaudeProcess` — PTY process start/stop/version + `settings.local.json` merge/cleanup; `PtyTtyConnector` — PTY↔JediTerm bridge; `ScreenContentDetector` — screen-poll analysis; `StreamJsonParser` — lightweight NDJSON parser; `ClaudeSessionStore` — reads/writes Claude session JSONL files (`~/.claude/projects/<hash>/`): list, rename, delete |
| `ui/` | `ClaudeSessionTab` — one TC per session; `ClaudePromptPanel` — passive View, implements `ClaudeSessionModelListener`; `ChoiceMenuPanel` — interactive choice UI; `FileDiffTab` + `FileDiffPermissionPanel` — diff viewer with Accept/Reject/Cancel; `MarkdownRenderer` — markdown→HTML; `HistoryDialog`, `FavoritesDialog`, `FavoritesPanel`, `AssignShortcutDialog` — history/favorites UI; `SaveAndSwitchDialog` — modal for stop/rename/switch session; `SessionModePanel` — reusable radio-button panel for session mode + session table; `ClaudeSessionSelectorPanel` — directory/profile selector with integrated `SessionModePanel` |
| `ui/common/` | Shared input components: `AtCompletionPopup` — @-triggered path popup; `AtPathHighlighter` — blue token highlight; `FileDropHandler` — DnD + Ctrl+V → @path insertion; `ShortcutMatcher` — key→shortcut match with KEY_TYPED suppression; `TextComponentDecorator` — wires all the above; `DecoratedTextArea/TextField`, `TextContextMenu`, `RangeHighlightable` |
| `settings/` | `ClaudeCodePreferences` — NbPreferences wrapper (keys: `contextMenuSessionMode` default CONTINUE_LAST, `sessionListLimit` default 30); `ClaudeCodeOptionsPanelController` / `ClaudeCodeOptionsPanel` — Tools→Options (General + Profiles tabs); `ClaudeProfile`, `ClaudeProfileStore` — named profiles with isolated `CLAUDE_CONFIG_DIR`, auth, proxy, extra env vars; `ClaudeProjectProperties` — per-project profile assignment |
| `actions/` | `ClaudeCodeAction` — toolbar button; `OpenWithClaudeAction` — project node context menu; `PromptFavoriteAction` — dynamically registered action per favorite for Keymap API |
| `io.github.nbclaudecodegui.mcp` | `MCPSseServer` — Jetty `/sse` `/messages` `/hook`; `NetBeansMCPHandler` — MCP dispatcher + PreToolUse hook; `tools/` — `OpenDiff`, `PermissionPromptTool`, `DiffTabTracker`, `GetDiagnostics`, `GetOpenEditors`, `GetCurrentSelection`, `OpenFile` |
| `org.openbeans.claude.netbeans` | **Legacy package — do not add new code here and avoid modifying existing code.** Contains: `ClaudeCodeStatusService`, `ClaudeCodeStatusLineElement`, `EditorUtils`, `NbUtils`; `tools/` — `AsyncHandler`, `AsyncResponse`, `GetWorkspaceFolders`, `CheckDocumentDirty`, `SaveDocument`, `CloseTab`, `CloseAllDiffTabs` |

### Session lifecycle
1. User clicks toolbar → `ClaudeSessionTab` opens; user picks a directory and session mode in `ClaudeSessionSelectorPanel` → `ClaudeSessionController.startProcess()` merges `settings.local.json` and launches the PTY.
2. PTY output renders in JediTerm; `ScreenContentDetector` (screen-poll timer) detects interactive choice menus → `ClaudeSessionModel.setActiveChoiceMenu()` → `ClaudePromptPanel.onChoiceMenuChanged()` shows `ChoiceMenuPanel`.
3. Claude connects to `GET /sse`; subsequent tool calls arrive as `POST /messages`; file edits trigger `POST /hook`.
4. Each session = isolated PTY process; closing window confirms if the process is running.
5. Session state (`SessionLifecycle`, `editMode`, model list, choice menu) lives in `ClaudeSessionModel`; changes are dispatched to `ClaudeSessionModelListener` on the EDT.

`SessionLifecycle` state machine:
```
showChatUI()                    → STARTING
detectInputPromptReady() = true → READY   (one-shot, from STARTING only)
sendPrompt() / discoverModels() → WORKING
onClaudeIdle()                  → READY
```

### Session modes

`SessionMode` controls which CLI flags are appended when launching `claude`:

| Mode | CLI flag | When used |
|------|----------|-----------|
| `NEW` | _(none)_ | Start fresh conversation |
| `CONTINUE_LAST` | `--continue` | Resume most recent session |
| `RESUME_SPECIFIC` | `--resume <sessionId>` | Resume a specific saved session |
| `CLOSE_ONLY` | _(stop, no restart)_ | `SaveAndSwitchDialog` stop-only option |

### Tab open/close behavior

**Opening a session:**
- **Toolbar button** (`ClaudeCodeAction`) → finds an idle `ClaudeSessionTab` and focuses it, or creates a new empty tab.
- **Project context menu** (`OpenWithClaudeAction`) → finds an existing session tab for that directory (focuses it), or creates a new tab and calls `autoStart(dir, profileName)` with the mode from `contextMenuSessionMode` preference.
- **IDE restart** → `readExternal` restores path / profile / extraCliArgs / sessionMode / resumeSessionId → `componentOpened` calls `autoStart` with those values.
- **User confirms selector panel** → `ClaudeSessionSelectorPanel` fires `OpenListener.onOpen(dir, profileName, extraCliArgs, mode, resumeId)` → `ClaudeSessionTab.startSession()`.

**Stopping / switching a session:**
- **Stop button (⏻)** in status bar → `openSwitchDialog(SessionMode.CLOSE_ONLY)` — opens `SaveAndSwitchDialog` pre-selecting CLOSE_ONLY; user optionally renames session, then either stops or switches to a new mode.
- **"New session" button** in prompt panel → `onSaveAndSwitch("", SessionMode.NEW, null)` directly.
- **"Resume session" button** in prompt panel → `openSwitchDialog(SessionMode.RESUME_SPECIFIC)`.
- **Tab × close** → `componentClosed()` saves `pathToRestore` and calls `stopProcess()`.
- **`onSaveAndSwitch(name, mode, resumeId)`**: calls `controller.stopAndRename(name)` (stops PTY, appends custom-title to JSONL if name non-empty), then starts a new session with the chosen mode.

### Session persistence (JSONL)

Claude Code stores sessions as JSONL files:
```
~/.claude/projects/<hash>/<session-id>.jsonl
```
Hash = absolute working-dir path with every `/` replaced by `-`.

`ClaudeSessionStore` operations:
- **`listSessions(workingDir, claudeConfigDir, limit)`** — two-phase read for performance: sort all JSONL files by `mtime` descending, take top candidates, parse only those. Results sorted by `lastAt` descending. Sessions with no timestamps excluded.
- **`renameSession()`** — appends a `{"type":"custom-title","customTitle":"...","sessionId":"...","timestamp":"..."}` entry to the JSONL file.
- **`deleteSession()`** — removes the JSONL file.
- **`SavedSession.displayName()`** — returns `customTitle` if set, otherwise `slug`, otherwise `sessionId`.

### File-change permission flow
Claude Code can ask permission before editing files via two paths, both using `FileDiffTab` + `PermissionPanel`:

1. **PreToolUse HTTP hook** (`NetBeansMCPHandler.handlePreToolUse`) — Claude calls the plugin's SSE endpoint before each write; plugin opens a diff tab; response is a CompletableFuture resolved by `DiffTabTracker.resolveHook()`.
2. **`permission_prompt` MCP tool** (`PermissionPromptTool`) — Claude invokes the tool directly; plugin opens the same diff tab; result is returned via `AsyncHandler` stored in `DiffTabTracker`.

Both paths show the same UI: `[✓ Accept] [✗ Reject] [Reject reason (Optional)] [Cancel]`.
- **Accept** — allows the change
- **Reject** — denies the change with an optional reason sent to Claude
- **Cancel** — denies the change and sends Ctrl+C (byte `0x03`) to the PTY, interrupting Claude's running prompt
- **× (close tab)** — treated as Reject (hook returns "ask", MCP returns deny)

After any action the originating `ClaudeSessionTab` is re-activated automatically.

### Settings (Tools → Options → Claude Code → General)

| Setting | Preference key | Default | Description |
|---------|---------------|---------|-------------|
| "Start new session when opening with Claude" checkbox | `contextMenuSessionMode` | `CONTINUE_LAST` | Checked → `SessionMode.NEW`; unchecked → `SessionMode.CONTINUE_LAST`. Controls the mode used by the toolbar button and project context menu. |
| "Session list limit" spinner (1–500) | `sessionListLimit` | 30 | Max number of past sessions shown in the resume list. |

### Registration
`layer.xml` registers the Options category (position 1500) and toolbar action. Icon PNGs are generated at build time from `cc-gui-icon.svg` via the Groovy script in `pom.xml` using Apache Batik.

## Testing
- Unit tests: JUnit 5 in `src/test/java/`
- Integration test: `ClaudeCodePluginIT` uses `NbModuleSuite` for full IDE lifecycle
- Test fixtures (JSON): `src/test/resources/fixtures/`
- `ClaudeProcessTest` skips on Windows; uses a fake `claude` shell script
- Test resources in `src/test/resources/` must mirror the package structure of `src/main/java/`. For example, resources for `io.github.nbclaudecodegui.process.ScreenContentDetector` go in `src/test/resources/io/github/nbclaudecodegui/process/`.

## Bug Fixing Protocol

1. **If the bug is related to PTY interaction with claude-code:**
   - Write Python tests that launch claude-code and verify different interaction approaches
   - Place tests in `claude-launch-tests/`
   - Run the tests and verify which interaction approach is correct
   - Based on test results, choose the correct fix

2. **Bump version** — increment the patch version in `pom.xml`

3. **Write unit tests** that reproduce the bug. Run them and confirm the bug is reproduced

4. **Fix the bug**

5. **Verify with tests** that the bug is fixed (`mvn test`)

6. **Build the package** (`mvn package` or `mvn nbm:nbm`)

7. **Offer to install the new plugin version** and verify the bug is fixed

8. **On successful fix — commit** all changed files, including Python tests and `to-do.md` if the bug was tracked there

## Commit Workflow

After implementing any feature or fix:
1. Build (`mvn package` or `mvn nbm:nbm`)
2. Present a manual test plan to the user
3. Wait for the user to confirm tests passed
4. Only then commit

## Logging

Use `LOG.fine(...)` for debug/diagnostic messages — no `isDebugMode()` guard needed.
Reserve `LOG.info` for important events; wrap with `isDebugMode()` only when using `LOG.info` for debug output.

## Git Workflow

- `origin` — personal fork
- `upstream` — org repo (`nbclaudecodegui/NetbeansClaudeCodeGui`)

**Never push directly to upstream.** Always:
1. Run `git fetch upstream` before creating a branch
2. Base the new branch on `upstream/main` (or `upstream/release/*` for backports)
3. Push to a new branch in `origin`
4. Open a PR from that branch into `upstream/main`

### Branch naming
- `bugfix/<description>` — for bug fixes
- `feature/<description>` — for new features
- Backport branches: `<type>/<version>/<description>` (e.g. `bugfix/0.20/issue-22-scrollbar-disappears`)
