# Plan: Claude Code GUI — NetBeans Plugin

## Context

Build a new NetBeans plugin **Claude Code GUI** that combines:
- From **JeddictAi**: NetBeans patterns (`OptionsPanelController` settings, toolbar action, TopComponent window, navigation to settings)
- From **IdeaClaudeCodeGui**: launching Claude Code CLI as a subprocess, UI for it, Claude Code icons

The plugin is built incrementally — each stage produces a working, installable NBM.

> **Versioning:** scheme `0.<stage>.<fix>-SNAPSHOT`. First build of a stage: fix = 0. Each reinstall-requiring fix: increment fix. Examples: stage 1 → `0.1.0-SNAPSHOT`, stage 2 → `0.2.0-SNAPSHOT`, second fix of stage 2 → `0.2.1-SNAPSHOT`.

## Source Files (for reuse)

| What | Where |
|------|-------|
| Settings pattern | `submodules/JeddictAi/src/main/java/io/github/jeddict/ai/settings/AIAssistanceOptionsPanelController.java` |
| Toolbar action pattern | `submodules/JeddictAi/src/main/java/io/github/jeddict/ai/actions/ToolbarAction.java` |
| TopComponent pattern | `submodules/JeddictAi/src/main/java/io/github/jeddict/ai/components/AssistantChat.java` |
| Process launch | `submodules/IdeaClaudeCodeGui/src/main/java/com/github/claudecodegui/bridge/ProcessManager.java` |
| Claude icons | `submodules/IdeaClaudeCodeGui/src/main/resources/icons/` (cc-gui-icon.svg, logo-16.png, logo.png) |

---

## Plugin Structure

```
NetbeansClaudeCodePlugin/          ← repository root
├── pom.xml                        # Maven + nbm-maven-plugin
├── submodules/                    # existing submodules
│   ├── JeddictAi/
│   └── IdeaClaudeCodeGui/
└── src/main/
    ├── java/io/github/nbclaudecodegui/
    │   ├── actions/
    │   │   ├── ClaudeCodeAction.java          # Toolbar action
    │   │   ├── OpenWithClaudeAction.java      # Context menu action
    │   │   └── PromptFavoriteAction.java      # Dynamically registered Action per favorite for Keymap API
    │   ├── controller/
    │   │   └── ClaudeSessionController.java   # PTY process lifecycle, screen polling, model/edit-mode switching
    │   ├── model/
    │   │   ├── ClaudeSessionModel.java        # Session state container, listener dispatch on EDT
    │   │   ├── ChoiceMenuModel.java           # Interactive-prompt data
    │   │   ├── PromptHistoryStore.java        # Read/write/trim history in NbPreferences; per-project key
    │   │   ├── PromptFavoritesStore.java      # Global + per-project favorites; JSON; supports ordering
    │   │   ├── HistoryEntry.java              # Record: timestamp + prompt text
    │   │   ├── FavoriteEntry.java             # Record: name, prompt, optional hotkey
    │   │   └── PromptEntry.java               # Shared base for history/favorites entries
    │   ├── settings/
    │   │   ├── ClaudeCodeOptionsPanelController.java
    │   │   ├── ClaudeCodeOptionsPanel.java
    │   │   ├── ClaudeCodePreferences.java
    │   │   ├── ClaudeProfile.java             # POJO: ConnectionType/ProxyMode enums, toEnvVars()
    │   │   ├── ClaudeProfileStore.java        # NbPreferences + Jackson; Default always first
    │   │   ├── ClaudeProfilesPanel.java       # Profiles tab UI
    │   │   ├── ClaudeProjectProperties.java   # Per-project profile assignment
    │   │   ├── ClaudeProjectPropertiesPanel.java
    │   │   ├── ClaudeProjectPropertiesPanelProvider.java
    │   │   ├── CustomModel.java               # Record: id, alias, available
    │   │   └── CustomModelsDialog.java        # Dialog for managing custom models
    │   ├── ui/
    │   │   ├── ClaudeSessionTab.java           # One TC = one session; thin wrapper around ClaudePromptPanel
    │   │   ├── ClaudePromptPanel.java          # Session UI: terminal + top bar + input + status bar + model discovery
    │   │   ├── ChoiceMenuPanel.java            # Interactive choice UI (ChoiceMenuModel → buttons)
    │   │   ├── FileDiffPermissionPanel.java    # [✓ Accept][✗ Reject][reason][Cancel]
    │   │   ├── FileDiffTab.java                # Diff TopComponent + PermissionPanel; shared by hook and MCP
    │   │   ├── HistoryDialog.java              # Popup: history list with Send/Favorite/Delete
    │   │   ├── FavoritesDialog.java            # Popup: favorites list with Send/Move/Rename/Delete/Reorder
    │   │   ├── FavoritesPanel.java             # Reusable panel used inside FavoritesDialog
    │   │   ├── AssignShortcutDialog.java       # Dialog to assign keyboard shortcut to a favorite
    │   │   ├── ClaudeSessionSelectorPanel.java # Session selector panel
    │   │   └── common/
    │   │       ├── AtCompletionPopup.java      # @-triggered path completion popup with directory navigation
    │   │       ├── AtPathHighlighter.java      # Violet foreground highlight for @path tokens in textarea
    │   │       ├── DecoratedTextArea.java      # JTextArea subclass wired to TextComponentDecorator
    │   │       ├── DecoratedTextField.java     # JTextField subclass wired to TextComponentDecorator
    │   │       ├── FileDropHandler.java        # DnD + Ctrl+V → inserts @path tokens at caret
    │   │       ├── MarkdownRenderer.java       # Markdown → HTML
    │   │       ├── RangeHighlightable.java     # Interface: applyHighlights(List<Range>)
    │   │       ├── ShortcutMatcher.java        # Match key events to registered shortcuts; suppress KEY_TYPED after match
    │   │       ├── TextComponentDecorator.java # Wires FileDropHandler + AtCompletionPopup + AtPathHighlighter + TextContextMenu
    │   │       └── TextContextMenu.java        # Right-click context menu (Cut/Copy/Paste + history nav)
    │   └── process/
    │       ├── ClaudeProcess.java              # PTY lifecycle + settings.local.json merge/cleanup
    │       ├── PtyTtyConnector.java            # PTY ↔ JediTerm bridge
    │       └── StreamJsonParser.java           # NDJSON parser
    ├── java/io/github/nbclaudecodegui/mcp/
    │   ├── MCPSseServer.java                   # Jetty: /sse /messages /hook
    │   ├── NetBeansMCPHandler.java             # JSON-RPC dispatcher + PreToolUse hook handler
    │   ├── MCPResponseBuilder.java             # Helper for building MCP JSON-RPC responses
    │   └── tools/
    │       ├── DiffTabTracker.java             # Registry of pending diff tabs
    │       ├── PermissionPromptTool.java       # MCP tool: shows FileDiffTab, async response
    │       ├── OpenDiff.java
    │       ├── OpenFile.java
    │       ├── GetOpenEditors.java
    │       ├── GetCurrentSelection.java
    │       └── GetDiagnostics.java
    ├── java/io/github/nbclaudecodegui/
    │   ├── ClaudeCodeInstaller.java            # Module lifecycle: starts MCPSseServer
    │   └── ...
    ├── java/org/openbeans/claude/netbeans/    ← remaining legacy classes
    │   ├── ClaudeCodeStatusService.java        # Interface: isServerRunning(), getServerPort()
    │   ├── ClaudeCodeStatusLineElement.java
    │   ├── EditorUtils.java
    │   ├── NbUtils.java
    │   ├── ClaudeCodeAction.java               # (legacy, now also in io.github.nbclaudecodegui.actions)
    │   └── tools/
    │       ├── AsyncHandler.java
    │       ├── AsyncResponse.java
    │       ├── CheckDocumentDirty.java
    │       ├── CloseAllDiffTabs.java
    │       ├── CloseTab.java
    │       ├── GetCurrentSelection.java
    │       ├── GetWorkspaceFolders.java
    │       ├── SaveDocument.java
    │       ├── Tool.java
    │       └── params/
    └── resources/io/github/nbclaudecodegui/
        ├── layer.xml
        ├── Bundle.properties
        └── icons/
            ├── claude-icon.png               # 16px (toolbar/tabs)
            └── claude-icon-32.png            # 32px (settings)
```

---

## Claude Code Communication Protocols

### Primary protocol: PTY + native TUI

Claude is launched **without `--print`** via PTY (pty4j); the full TUI renders inside JediTermWidget:

```
claude   (no flags, working directory = project root)
```

- PTY is created via `pty4j`; bridge to JediTerm is `PtyTtyConnector`
- `TERM=xterm-256color` is passed in the process environment
- The user interacts directly through the embedded terminal

`StreamJsonParser`, `MarkdownRenderer`, and `PromptResponsePanel` are retained for a possible hybrid mode in the future.

### MCP (Model Context Protocol) — implemented in stage 10

**MCP does not replace or modify the primary PTY protocol.** It runs alongside it via a separate HTTP server (Jetty, port 28991).

**Three communication channels:**

1. **PTY** — TUI bytes in both directions; `TtyPromptDetector` detects interactive questions → `PromptResponsePanel`
2. **MCP SSE** (`GET /sse` + `POST /messages`) — JSON-RPC 2.0; Claude POSTs requests, responses go over the SSE stream; async tools (`permission_prompt`) respond later via the same SSE
3. **PreToolUse HTTP hook** (`POST /hook`) — Claude POSTs before Edit/Write/MultiEdit; the servlet blocks until the user decides (up to 590 s); response is `allow`/`deny`/`ask`

**settings.local.json** is written to `{workingDir}/.claude/` before the PTY starts using merge logic (not overwrite): existing user keys are preserved. When the session stops, the plugin removes its own keys; if the file becomes empty it is deleted entirely.

---

## Stages

### Stage 1 — Dummy plugin (installable NBM) ✅

**Result:** minimal plugin installs in NetBeans without errors.

---

### Stage 2 — Settings page with Claude icon ✅

**Result:** Tools → Options → "Claude Code" tab with icon and CLI path field.

---

### Stage 3 — Toolbar button opening an empty window ✅

**Result:** toolbar button with Claude icon → click → empty window appears.

---

### Stage 4 — Window with tabs per working directory ✅

**Result:** `ClaudeCodeTopComponent` with `JTabbedPane`; session tabs; "Open with Claude Code" context menu on projects; "+" button; path persistence via `writeExternal/readExternal`.

> **Note:** The JTabbedPane architecture was replaced in stage 7b. All functionality is preserved in the new scheme.

---

### Stage 5 — First working chat with Claude Code ✅

**Result:** subprocess (stream-json + stdin/stdout), multi-line input, configurable send key, slash commands.

---

### Stage 6 — Stream-JSON parsing + formatted output + markdown ✅

**Result:** `StreamJsonParser` with no external dependencies; `MarkdownRenderer` (headings, code, lists, bold/italic); `session_id` → `--resume`; `PromptResponsePanel` for interactive questions.

---

### Stage 7 — Embedded terminal (JediTerm) for full Claude TUI ✅

**Result:** `PtyTtyConnector` + `JediTermWidget` inside `ClaudeSessionPanel`; Claude TUI renders natively including permission prompts and progress indicators.

**Implemented:**
- `PtyTtyConnector.java` — PTY ↔ JediTerm bridge (`TtyConnector` interface)
- `ClaudeProcess.java` simplified: `start(workingDir)` → `PtyProcess`; `claude` command with no flags
- `ClaudeSessionPanel.java` — `showChatUI()` creates `JediTermWidget` instead of pane/area

---

### Stage 8 — Refactor: each session = independent TopComponent ✅

**Goal:** fix three problems of the previous architecture:
1. Toolbar did not dock the window (`dockToRightIfNeeded` was not called)
2. Single `ClaudeCodeTopComponent` with JTabbedPane — sessions cannot be moved independently
3. `componentClosed()` killed the PTY on ResetWindows

**Implemented:**

| File | Action |
|------|--------|
| `ui/ClaudeSessionTab.java` | **created** — one TC = one session |
| `ui/ClaudeSessionPanel.java` | added `detachTerminal`, `reattachTerminal`, `hasLiveProcess`, `autoStart` |
| `ui/ClaudeCodeTopComponent.java` | **removed** |
| `ui/TabHeader.java` | **removed** |
| `actions/ClaudeCodeAction.java` | finds a free TC or creates a new one |
| `actions/OpenWithClaudeAction.java` | finds TC by directory or creates a new one |

**Key decisions:**
- `componentClosed()` → `panel.detachTerminal()` — removes the widget, PTY stays alive
- `canClose()` → `panel.canClose()` — confirmation dialog + `stopProcess()` on confirm
- PTY processes are children of the IDE JVM: they receive SIGHUP on IDE crash, no zombies
- `writeExternal/readExternal` — persists directory path; `componentOpened` restores the session or reattaches a live terminal

**Version:** `0.8.0-SNAPSHOT`

---

### Stage 9 — PromptResponsePanel: response panel for Claude questions ✅

**Result:** `PromptResponsePanel` is shown when Claude waits for a response; fixed panel visibility, flush timer, and ESC on Cancel.

**Version:** `0.9.16-SNAPSHOT`

---

### Stage 10 — MCP SSE server + IDE tools ✅

**Version:** `0.10.x-SNAPSHOT`

**Implementation:**

- `org.openbeans.claude.netbeans.MCPSseServer` — Jetty HTTP server; three endpoints:
  - `GET /sse` — long-lived SSE stream; sends `event:endpoint /messages` on connect; keep-alive pings every 5 s
  - `POST /messages` — receives JSON-RPC 2.0 from Claude, responds `202 Accepted`; responses go via SSE
  - `POST /hook` — PreToolUse HTTP hook; blocks until the user decides (timeout 590 s); returns `allow`/`deny`/`ask` JSON
- `org.openbeans.claude.netbeans.NetBeansMCPHandler` — MCP method dispatcher; supports `initialize`, `tools/list`, `tools/call`, `resources/list`, `resources/read`, `prompts/list`; manages selection tracking (`selection_changed` notifications → SSE)
- `tools/` — `OpenFile`, `GetWorkspaceFolders`, `GetOpenEditors`, `GetCurrentSelection`, `GetDiagnostics`, `CheckDocumentDirty`, `SaveDocument`, `CloseTab`, `CloseAllDiffTabs`, `OpenDiff`, `PermissionPromptTool`
- `tools/DiffTabTracker` — registry of pending diff tabs; separate maps for hook futures and MCP handlers
- `ClaudeCodeInstaller` — starts `MCPSseServer` at IDE startup; default port **28991**, configurable in Tools → Options

**Config written before PTY start:**
```json
{
  "mcpServers": {"netbeans": {"type": "sse", "url": "http://localhost:<PORT>/sse"}},
  "hooks": {"PreToolUse": [{"matcher": "Edit|Write|MultiEdit", "hooks": [{"type": "http", "url": "http://localhost:<PORT>/hook"}]}]}
}
```

**Manual test plan:** [`docs/manual-test-mcp.md`](manual-test-mcp.md)

---

### Stage 11 — Diff viewer: Accept/Reject/Cancel ✅

**Version:** `0.11.x-SNAPSHOT`

**Implementation:** two file-change interception paths share a common UI.

**`ui/PermissionPanel`** — single row `[✓ Accept] [✗ Reject] [Reject reason (Optional)] [Cancel]`
- `ICON_ACCEPT = "\u2713"`, `ICON_REJECT = "\u2717"` — same constants used in `PromptResponsePanel` for Yes/No buttons
- **Accept** → allow; **Reject** → deny + optional reason; **Cancel** → deny + Ctrl+C to PTY (byte `0x03`); **× (close tab)** → hook: ask, MCP: deny

**`ui/FileDiffTab`** — static `open(filePath, before, after, tabName, onAccept, onReject, onCancel, onClose)`:
- Opens a `TopComponent` with the NetBeans Diff API
- `AtomicBoolean decided` — prevents double-firing of `onClose` after a button click
- After any action, activates the `ClaudeSessionTopComponent` for the matching `workingDir`
- `public static cancelCurrentPromptForFile(filePath)` — finds the session TC and calls `cancelCurrentPrompt()`

**Path 1 — PreToolUse hook** (`NetBeansMCPHandler.handlePreToolUse`):
- Claude POSTs to `/hook`; servlet blocks on a `CompletableFuture`
- Decision is recorded via `DiffTabTracker.resolveHook()` → HTTP response goes to Claude

**Path 2 — MCP tool `permission_prompt`** (`PermissionPromptTool`):
- Claude calls the tool via JSON-RPC
- Result is sent via `AsyncHandler.sendResponse()` → SSE

**Dependency:** `org-netbeans-modules-diff`

**before/after computation** (in `NetBeansMCPHandler.handlePreToolUse`):
- `Edit` — reads file, applies `old_string → new_string`
- `Write` — before = current file or `""`, after = `content`
- `MultiEdit` — applies edits sequentially

---

### Stage 11 (fix) — settings.local.json merge + cleanup ✅

**Version:** `0.11.12-SNAPSHOT`

**Problem:** `writeSettingsLocalJson` did a full overwrite (destroying user content); the file was not deleted after the session.

**Implementation in `ClaudeProcess`:**
- `mergeSettingsJson(existingJson, port)` — targeted merge via Jackson: adds/updates `mcpServers.netbeans` and the `PreToolUse[matcher=Edit|Write|MultiEdit]` entry; all other keys are left untouched
- `cleanedSettingsJson(existingJson)` — removes our keys; returns `null` if the file became empty
- `cleanupSettingsLocalJson(workingDir)` — deletes the file on `null` result, otherwise writes it back
- `stop()` triggers cleanup automatically via the stored `workingDir` field

---

### Stage 12 — Prompt panel improvements ✅

**Goal:** improve the UX of the prompt input area and add a session status bar.

**Version:** `0.12.28-SNAPSHOT`

**Implementation:**

- `ClaudeSessionPanel.java` → **deleted**, replaced by `ClaudePromptPanel.java`
- **`ClaudePromptPanel`** — comprehensive session panel:
  - Top bar: project combo, path combo with history, Browse / Open / Settings buttons
  - Terminal: `JediTermWidget` in `JSplitPane` with input area; divider position persisted
  - Input area: multi-line `JTextArea`, Esc → Cancel, Ctrl+Up/Ctrl+Down in-session history (max 100), context menu (Cut/Copy/Paste + Prev/Next Message enabled/disabled)
  - `ChoiceMenuPanel` triggered by `ScreenContentDetector` scanning the rendered terminal
  - Status bar (bottom): edit-mode combo (`plan / default / acceptEdits`), model combo (populated via `ModelDiscovery`), state label, plan label, version label; raised separators between components (`controlShadow` + `controlHighlight`)
- **`SessionLifecycle` enum** — `STARTING / READY / WORKING`; all transitions via `applyState()` on EDT
- **`ModelDiscovery` record** — `parseModelDiscovery(List<String>)`: handles legacy format (cursor glyph ❯ ▶ >) and new numbered-menu format (✔ checkmark); returns model list + currentIndex
- **`ClaudeSessionTopComponent`** — refactored to thin wrapper around `ClaudePromptPanel`; `openNewOrFocus()` / `openForDirectory(File)`; persistence via `writeExternal/readExternal`
- **`ScreenContentDetector`** — improved screen scanning for `ChoiceMenuPanel` detection
- **Tests:** `ClaudePromptPanelParseModelTest` (10 cases), `SessionLifecycleTest` (6 cases)
- **Deleted:** `ClaudeSessionPanel.java`, `ClaudeSessionPanelParseModelTest.java`

---

### Stage 13 — Claude Code profiles ✅ (v0.13.11-SNAPSHOT)

**Goal:** support multiple API keys / proxy settings via named profiles. Each non-Default profile gets an isolated `CLAUDE_CONFIG_DIR` so history, settings, and credentials are fully separated.

#### Fundamental principles

- **Default profile** — built-in, non-deletable, always first; plugin does not set `CLAUDE_CONFIG_DIR` (Claude uses `~/.claude`).
- **Named profiles** — each gets its own `<profilesDir>/<name>/` directory; configurable base dir in Tools → Options (default: `parent(Places.getUserDirectory())/claude-profiles/`).
- **Connection types** — `Claude managed` (no vars), `Subscription` (`CLAUDE_CODE_OAUTH_TOKEN`), `Claude API` (`ANTHROPIC_API_KEY`), `Other API` (`ANTHROPIC_API_KEY` + `ANTHROPIC_BASE_URL`). Computed from stored credentials, not stored separately.
- **Proxy modes** — `System Managed` (inherit env), `No Proxy` (clear `HTTP_PROXY`/`HTTPS_PROXY`/`NO_PROXY`), `Custom` (user-supplied values).
- **Extra env vars** — arbitrary key→value table for Bedrock, Vertex, etc.
- **Session persistence** — profile name written to `writeExternal`/`readExternal`.
- **Project assignment** — per-project profile stored in `NbPreferences`; assignable via Project Properties panel for Maven, Java SE, and Gradle projects.

#### Implementation

| File | Action |
|------|--------|
| `settings/ClaudeProfile.java` | **created** — POJO with `ConnectionType`/`ProxyMode` enums, `toEnvVars()`, `computeConnectionType()` |
| `settings/ClaudeProfileStore.java` | **created** — NbPreferences + Jackson; Default always first; corrupt JSON → only Default |
| `settings/ClaudeProfilesPanel.java` | **created** — Profiles tab UI: combo + New/Copy/Rename/Delete + inline property editor |
| `settings/ClaudeProjectProperties.java` | **created** — per-project profile assignment keyed by project path |
| `settings/ClaudeProjectPropertiesPanel.java` | **created** — panel shown in Project Properties dialog |
| `settings/ClaudeProjectPropertiesPanelProvider.java` | **created** — `CompositeCategoryProvider` for Maven / Java SE / Gradle |
| `settings/ModelAlias.java` | **created** — record for model alias entries (`id`, `alias`, `available`) |
| `settings/ModelAliasesDialog.java` | **created** — dialog for managing model aliases list |
| `settings/ClaudeCodeOptionsPanel.java` | **modified** — restructured into `JTabbedPane`: "General" + "Profiles" tabs |
| `settings/ClaudeCodePreferences.java` | **modified** — added `profilesDir` preference |
| `process/ClaudeProcess.java` | **modified** — `start(workingDir, profile)` + static `buildEnv(profile, profilesDir)` |
| `ui/ClaudePromptPanel.java` | **modified** — `profileCombo` between Browse and Open; passes profile to `start()` |
| `ui/ClaudeSessionTab.java` | **modified** — profile name serialised in `writeExternal`/`readExternal` |
| `actions/OpenWithClaudeAction.java` | **modified** — resolves profile from `ClaudeProjectProperties`; passes to `openForDirectory()` |

#### UI layout — Profiles tab

```
Profiles directory: [/home/user/.netbeans/claude-profiles         ] [Change…]

Profile: [Profile Name              ▼]  [New] [Copy] [Rename] [Delete]

Config directory:  [/home/user/.netbeans/claude-profiles/Profile-Name    ]

Connection Type
  ○ Claude managed  │
  ○ Subscription    │  Token:    [________________________________]
  ● Claude API      │  API Key:  [••••••••••••••]  [Show]
  ○ Other API       │  Base URL: [________________________________]

Proxy Settings
  ● System Managed  │  HTTP Proxy:  [________________________________]
  ○ No Proxy        │  HTTPS Proxy: [________________________________]
  ○ Custom          │  NO_PROXY:    [________________________________]

Extra environment variables
  Variable              │ Value                         │ [+] [-]
```

#### Control bar layout

```
[projectCombo] [pathCombo] [Browse] [Profile ▼] [Open] [⚙]
```

---

### Stage 14 — Prompt history & favorites ✅ (v0.14.5-SNAPSHOT)

**Goal:** persistent per-project prompt history with ability to promote entries to global or per-project favorites; optional hotkey on each favorite.

**Components:**

| File | Role |
|------|------|
| `model/PromptHistoryStore.java` | Read/write/trim history in NbPreferences as JSON array with timestamps; per-project key |
| `model/PromptFavoritesStore.java` | Global + per-project favorites; JSON; supports ordering |
| `model/HistoryEntry.java` | Record: timestamp + prompt text |
| `model/FavoriteEntry.java` | Record: name, prompt, optional hotkey |
| `model/PromptEntry.java` | Shared base for history/favorites entries |
| `ui/HistoryDialog.java` | Popup dialog: history list with Send/Favorite/Delete actions |
| `ui/FavoritesDialog.java` | Popup dialog: favorites list with Send/Move/Rename/Delete/Reorder |
| `ui/FavoritesPanel.java` | Reusable panel used inside FavoritesDialog |
| `ui/PromptListPanel.java` | Tabbed panel "History \| Favorites" |
| `ui/AssignShortcutDialog.java` | Dialog to assign a keyboard shortcut to a favorite |
| `ui/ShortcutMatcher.java` | Utility: match key events to registered shortcuts |
| `actions/PromptFavoriteAction.java` | Dynamically registered Action per favorite for NetBeans Keymap API |

**History features:**
- Store in `NbPreferences` as JSON array with timestamps; max depth (default 200) + TTL configurable in Settings
- Ctrl+Up / Ctrl+Down in `inputArea` — navigate persistent history
- Popup list: time + prompt preview; actions: **Send to input area**, **Add to favorites**, **Delete**

**Favorites features:**
- Two levels: global and per-project
- Actions: **Send**, **Move** (global ↔ per-project), **Rename**, **Delete**, **Reorder** (↑↓)
- Assign hotkey to a specific favorite (NetBeans Keymap API)

**Settings keys added:**

| Key | Type | Default |
|-----|------|---------|
| `historyMaxDepth` | int | `200` |
| `historyTtlDays` | int | `0` (no expiry) |

**Tests:**
- `PromptHistoryStoreTest.java` — add, trim, TTL expiry
- `PromptFavoritesStoreTest.java` — add global, add per-project, reorder, delete

---

### Stage 15 — File attachments in prompt ✅ (v0.15.23-SNAPSHOT)

**Goal:** attach files to a prompt via `@path` tokens inserted directly in the textarea.

**Implementation:**

| File | Role |
|------|------|
| `ui/common/FileDropHandler.java` | DnD files/images and Ctrl+V paste → inserts `@relative` or `@/absolute` path token at caret; clipboard images saved to temp PNG → `@/tmp/…png` |
| `ui/common/AtCompletionPopup.java` | Single-level @-popup triggered on `@` keystroke; lists directory contents on demand with keyboard navigation |
| `ui/common/AtPathHighlighter.java` | Violet foreground highlight for `@…` tokens in the textarea via `Highlighter` API |
| `ui/common/ShortcutMatcher.java` | Matches key events to registered shortcuts; suppresses KEY_TYPED after a match to prevent bleed-through |
| `ui/common/TextComponentDecorator.java` | Wires FileDropHandler + AtCompletionPopup + AtPathHighlighter + TextContextMenu onto any text component |
| `ui/common/DecoratedTextArea.java` | `JTextArea` subclass pre-wired to `TextComponentDecorator` |
| `ui/common/DecoratedTextField.java` | `JTextField` subclass pre-wired to `TextComponentDecorator` |
| `ui/common/TextContextMenu.java` | Right-click context menu: Cut/Copy/Paste + prev/next history navigation |
| `ui/common/RangeHighlightable.java` | Interface: `applyHighlights(List<Range>)` |
| `ui/ClaudePromptPanel.java` | Uses `DecoratedTextArea`; sends textarea text as-is (no chip prepend); Attach button → JFileChooser |

**Tests** (all in `test/ui/common/`): `AtCompletionPopupTest`, `AtPathHighlighterTest`, `FileDropHandlerTest`, `ShortcutMatcherTest`, `MarkdownRendererTest`; `ClaudePromptPanelSendTest` in `test/ui/`

---

### Stage 16 — FileDiff location config ✅

**Goal:** allow the user to choose whether FileDiff appears embedded in the session window or in a separate TopComponent (current behaviour).

**What was added:**
- Boolean preference `openDiffInSeparateTab` (default `false`) in `ClaudeCodePreferences`
- `FileDiffTab.open()` reads the setting and picks inline panel vs. separate TopComponent
- Checkbox in `ClaudeCodeOptionsPanel` ("Open diff in separate tab")

**Files:** `ClaudeCodePreferences.java`, `FileDiffTab.java`, `ClaudeCodeOptionsPanel.java`

---

### Stage 17 — GitHub CI/CD + NBM publishing ✅

**Goal:** automated build and release publishing.

**What to add:**
- `.github/workflows/build.yml`: `mvn package` on push to main; artifact — `.nbm`
- `.github/workflows/release.yml`: triggered by tag `v*.*.*`; GitHub Release + NBM; release notes from `CHANGELOG.md`
- `CHANGELOG.md` — maintained manually
- Badges in README

---

### Stage 18 — Help + user documentation ✅ (v0.18)

**Goal:** built-in help and end-user documentation.

**Implemented:**
- `docs/user-manual.md` — full user manual covering all plugin features (sessions, profiles, history, favorites, file attachments, diff viewer, settings)
- `docs/installation.md` — requirements, installation steps, and build instructions; README links to it
- Documentation artifact (`*-docs.html`) added to CI build and GitHub Release outputs — user manual and installation guide bundled as a single self-contained HTML file with screenshots
- Alpha-status notice added to README

---

### Stage 19 — Extra CLI args + model discovery refactor ✅ (v0.19.x-SNAPSHOT)

**Goal:** allow per-profile and per-session extra command-line arguments to the `claude` process; refactor model menu parsing; fix auto-start and plan-state bugs.

**Extra CLI arguments** (`e93fd18`):
- `ClaudeProfile.extraCliArgs` — stored field; Default profile persisted via `ClaudeCodePreferences.KEY_DEFAULT_EXTRA_CLI_ARGS`
- `ClaudeProcess.start(workingDir, profile, extraCliArgs)` — parses quoted args via `parseArgs(String)`, appends them to the command list
- `ClaudeProfilesPanel` — `extraCliArgsField` (JTextField, 40 cols) after the Extra env vars section
- `ClaudeSessionSelectorPanel` — `extraArgsField` pre-filled from profile; editable per-session
- `ClaudeSessionTab` — persists `extraCliArgs` in `writeExternal`/`readExternal`; restores on IDE restart

**Fixes:**
- `5b70796` — Default profile extra CLI args not persisting; extra CLI args not serialised across IDE restarts
- `c02c830` — Session not transitioning to READY state when a named plan was active
- `bfa9a98` — Auto-start race condition: `autoStart` wrapped in `invokeWhenUIReady`

**Model discovery refactor** (`f2f27df`):
- Extracted `ModelMenuParser` class from `ClaudeSessionController.parseModelDiscovery`

---

### Stage 20 — NetBeans Plugin Portal publication (in progress)

**Goal:** make the plugin discoverable and installable directly from the NetBeans Plugin Manager via the official Plugin Portal.

**Prerequisites (must be done in order):**

1. **Repository migration** — transfer the GitHub repository to the `nbclaudecodegui` organisation (or rename the user repo to match the new group ID). Update the repository URL in `pom.xml` (`<scm>`, `<url>`).

2. **Documentation link updates** — update all hardcoded GitHub links in `README.md`, `docs/installation.md`, `docs/user-manual.md`, and `CHANGELOG.md` to point to the new repository URL. Update CI badge URLs in `README.md`.

3. **License file** — add `LICENSE` (Apache 2.0 full text) to the repository root. Ensure `pom.xml` `<licenses>` section is present and correct. Required by Maven Central.

4. **Maven Central publication** — configure `pom.xml` for Sonatype OSSRH / Central Portal:
   - Add `<developers>`, `<scm>`, `<licenses>` sections if missing
   - Configure `maven-gpg-plugin` for artifact signing
   - Configure `nexus-staging-maven-plugin` or `central-publishing-maven-plugin`
   - Add CI secrets: `MAVEN_GPG_KEY`, `OSSRH_USERNAME`, `OSSRH_PASSWORD` (or Central Portal token)
   - Publish: `mvn deploy -P release` → artifacts land on Maven Central under the new group ID

5. **Plugin Portal upload** — register/claim the plugin entry at [https://plugins.netbeans.apache.org/](https://plugins.netbeans.apache.org/):
   - The Portal can pull NBM artifacts from Maven Central automatically once the group ID / artifact ID are registered
   - Alternatively, upload the `.nbm` manually for each release
   - Fill in plugin metadata: name, description, categories, homepage URL, license

---

### Stage 21 — Saved sessions: resume, new, switch ✅ (v0.21.x-SNAPSHOT)

**Goal:** give users full control over Claude Code sessions — including resuming a previous conversation, starting fresh, and switching between saved sessions within the same project.

**Implementation:**

| Component | Responsibility |
|-----------|---------------|
| `process/ClaudeSessionStore.java` | **created** — reads JSONL session files from `~/.claude/projects/<hashed-path>/`; two-phase read (sort by mtime, parse top candidates); `listSessions()`, `renameSession()`, `deleteSession()` |
| `model/SavedSession.java` | **created** — record: sessionId, createdAt, lastAt, slug, customTitle, firstPrompt; `displayName()` returns customTitle → slug → sessionId |
| `model/SessionMode.java` | **created** — enum: `NEW`, `CONTINUE_LAST`, `RESUME_SPECIFIC`, `CLOSE_ONLY` |
| `ui/SessionModePanel.java` | **created** — reusable radio-button panel for session mode + session table (shown only when Resume Specific selected); Rename button |
| `ui/SaveAndSwitchDialog.java` | **created** — modal dialog: session name field + `SessionModePanel`; opened by the ⏻ button in the status bar |
| `ui/ClaudeSessionSelectorPanel.java` | **modified** — integrated `SessionModePanel`; Rename button hidden unless Resume Specific |
| `process/ClaudeProcess.java` | **modified** — `start(workingDir, profile, extraCliArgs, mode, resumeSessionId)` appends `--continue` / `--resume <id>` based on `SessionMode` |
| `controller/ClaudeSessionController.java` | **modified** — `stopAndRename(name)` stops PTY then appends custom-title JSONL entry |
| `ui/ClaudeSessionTab.java` | **modified** — `componentClosed()` saves path + calls `stopProcess()`; stop button opens `SaveAndSwitchDialog`; "New session" + "Switch to session" in input area context menu; session mode + resumeSessionId persisted in `writeExternal`/`readExternal` |
| `settings/ClaudeCodeOptionsPanel.java` | **modified** — added "Session list limit" spinner |
| `settings/ClaudeCodePreferences.java` | **modified** — added `contextMenuSessionMode` (default `CONTINUE_LAST`) and `sessionListLimit` (default 30) |
| `org.openbeans.claude.netbeans/ClaudeCodeStatusLineElement.java` | **modified** — stop button (⏻) opens `SaveAndSwitchDialog` |

**Tests:** `SavedSessionTest`, `ClaudeSessionStoreTest`, `SessionModePanelTest`; Python integration test `claude-launch-tests/test_session_rename.py`

---

## Plugin Location

`/home/oleg/my-projects/NetbeansClaudeCodePlugin/` — `pom.xml` and `src/` alongside `submodules/`.

## Key NetBeans Dependencies (pom.xml)

```xml
org-openide-util, org-openide-util-lookup        <!-- Stage 1 -->
org-netbeans-modules-options-api                  <!-- Stage 2 -->
org-openide-windows, org-openide-awt              <!-- Stage 3 -->
org-netbeans-modules-projectapi                   <!-- Stage 4 -->
org-openide-filesystems                           <!-- Stage 5 -->
org-netbeans-modules-diff                         <!-- Stage 11: Diff API -->
```

JediTerm / pty4j (stage 7):
```xml
org.jetbrains.jediterm:jediterm-core:3.63
org.jetbrains.jediterm:jediterm-ui:3.63
org.jetbrains.pty4j:pty4j:0.12.4
```

NBM plugin NetBeans version: `RELEASE230` (NetBeans 23)

## Plugin Settings Location

Settings are stored via `NbPreferences.forModule(ClaudeCodePreferences.class)`:

| OS | Path |
|----|------|
| Linux / macOS | `~/.netbeans/28/config/Preferences/io/github/nbclaudecodegui.properties` |
| Windows | `%APPDATA%\NetBeans\28\config\Preferences\io\github\nbclaudecodegui.properties` |

**Settings keys:**

| Key | Type | Default | Stage |
|-----|------|---------|-------|
| `claudeExecutablePath` | String | `""` (search in PATH) | 2 |
| `sendKey` | String | `CTRL_ENTER` | 5 |
| `mcpPort` | int | `28991` | 10 |
| `autoStart` | boolean | `false` | 12 |
| `commandHistory` | String | `""` (JSON array) | 12 |
| `profilesDir` | String | `~/.netbeans/claude-profiles` | 13 |
| `historyMaxDepth` | int | `200` | 14 |
| `historyTtlDays` | int | `0` | 14 |
| `defaultExtraCliArgs` | String | `""` | 19 |
| `contextMenuSessionMode` | String | `CONTINUE_LAST` | 21 |
| `sessionListLimit` | int | `30` | 21 |

---

## Javadoc Policy

- **All public classes and methods** must have Javadoc comments.
- **`maven-javadoc-plugin`** with `-Xdoclint:all` and `failOnError=true` is configured in `pom.xml`.
- Generate: `mvn javadoc:javadoc` → `target/site/apidocs/`

```xml
<!-- pom.xml -->
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-javadoc-plugin</artifactId>
    <version>3.6.3</version>
    <configuration>
        <additionalJOption>-Xdoclint:all</additionalJOption>
        <failOnError>true</failOnError>
    </configuration>
    <executions>
        <execution>
            <id>javadoc-jar</id>
            <phase>package</phase>
            <goals><goal>jar</goal></goals>
        </execution>
    </executions>
</plugin>
```
