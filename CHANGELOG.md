- Fixed choice menu not appearing when option descriptions span more than 3 terminal lines
- Fixed disabled models (e.g. `Fable (disabled)`) appearing in the model selector combo with an error message as their name
- Fixed session becoming permanently unresponsive when the working directory is on a git branch whose name contains dots (e.g. `feature/e9.5-refactor`)

# 1.2.20 (2026-06-22)

- Fixed documentation artifact name in GitHub Releases to match the plugin artifact name

# 1.2.17 (2026-06-17)

- Fixed Copy menu item being disabled in the terminal context menu when text was selected by mouse drag while Claude Code's TUI had mouse reporting enabled

# 1.2.14 (2026-06-01)

- Fixed mode switching (e.g. auto → plan) becoming unreliable when Claude Code is actively working: the edit-mode selector now correctly reaches the target mode without overshooting
- Fixed stale model aliases from a "Claude Compatible API" profile still showing in the model selector after switching the profile to "Managed by Claude"
- Fixed ClassNotFoundException on plugin installation/upgrade when the session tab had been previously saved under the old package name (https://github.com/nbplugins/NetbeansPluginClaudeCodeGui/issues/146)
- Fixed a Warning dialog appearing on NetBeans exit when a diff tab had been opened during the session

# 1.2.8 (2026-05-24)

- Added "Auto Mode" to the edit-mode selector (Claude Code 2.1.83+): file edits in auto mode are auto-approved; when auto mode is selected on an older Claude Code version, the plugin falls back to Bypass Permissions or Accept on Edit
- Fixed diff panel appearing for file edits despite bypassPermissions or acceptEdits mode when Claude's working directory was a subdirectory of the session root
- Fixed occasional ConcurrentModificationException when the screen-poll timer reads the terminal buffer concurrently with PTY writes
- Fixed tab title showing "Claude Code" for sessions opened in non-project directories — now shows the directory name
- Added OpenAI-compatible proxy: profiles can now route Claude Code through any OpenAI-compatible API provider (OpenAI, Azure, Groq, DeepSeek, Ollama, local models, etc.)

# 1.1.7 (2026-05-22)

- Fixed profile combo not refreshing when profiles are added or removed after session start
- Added ability to manually add, rename, and sort models in the Model Aliases dialog
- Fixed missing Claude Code icon in Tools → Options dialog

# 1.0.4 (2026-05-18)

- Migrated plugin home to https://github.com/nbplugins/NetbeansPluginClaudeCodeGui; updated Maven coordinates to io.github.nbplugins:netbeans-plugin-claude-code-gui; renamed Java packages to io.github.nbplugins.claudecodegui

# 0.23.10 (2026-05-18)

- Fixed F5 not refreshing the Markdown Preview from disk
- Added font zoom (Alt+Scroll / Ctrl+= / Ctrl+−) for Terminal and Markdown Preview; Ctrl+0 or Alt+Click Wheel resets; right-click "Zoom" submenu
- Added Find bar (Ctrl+F / F3 / Shift+F3) to Markdown Preview
- Added automatic Markdown Preview tab when Claude writes a plan file (configurable in Settings → General)
- Added `show_markdown` MCP tool: displays markdown text in a NetBeans Markdown Preview tab
- Added `show_markdown_file` MCP tool: opens a live-updating Markdown Preview tab for a .md file on disk

# 0.22.40 (2026-05-12)

- Fixed markdown preview rendering fenced code blocks indented inside list items outside the list instead of nested within their list item
- Fixed .md files in the Files panel losing all standard context menu items (Open, Cut, Copy, Delete, etc.) after installing the plugin — only "Preview Markdown" was shown
- Fixed Bypass Permissions edit mode not suppressing the file diff dialog: the edit mode was lost from the registry because it was set before the working directory was assigned at session start

# 0.22.35 (2026-05-07)

- Fixed markdown preview not rendering nested list structure (multi-level indented lists now display with proper nesting)
- Fixed "Continue last" and "Resume specific" staying enabled (and selected) when a directory or profile with no sessions is chosen in the session selector
- Fixed model list showing garbled names (e.g. "Mtoket 4.6 with 1M context") when the /model menu includes 1M-context variants and cost annotations

# 0.22.30 (2026-05-05)

- Fixed digits going into the "Type something." text field instead of selecting the intended numbered option in planning and permission-prompt choice menus
- Fixed "Yes, and tell Claude what to do next" option in bash permission prompts: now shows radio label "Yes" with input hint in the text field (not as a subtitle), and allows sending with empty text
- Fixed session tab showing the old project display name after IDE restart (tab now refreshes its name once all open projects finish loading)
- Fixed model discovery failing when Enter was absorbed as a newline in the prompt instead of submitting the /model command (race condition under load)

# 0.22.24 (2026-04-29)

- Fixed model name detection for new Claude CLI format where the default item has a long label ("Default (recommended)") with only 2-space alignment, and for model names that include a space (e.g. "Sonnet 4")
- Fixed model combo not becoming active when a session starts with a blocking dialog (e.g. "Resuming session" prompt on --continue/--resume startup)
- Improved keyboard navigation in choice menus: arrow keys, Tab, Shift+Tab
- Added terminal font and font size settings (Tools → Options → Claude Code → General); defaults to auto-detection preferring Adwaita Mono for full Unicode coverage of Claude Code TUI symbols (https://github.com/nbclaudecodegui/NetbeansClaudeCodeGui/issues/18)
- Reorganized General and Advanced settings tabs: moved technical settings (hang timeout, MCP port, Enable MCP integration, session list limit, debug mode) to Advanced
- Added a setting to control whether the choice menu grabs keyboard focus (https://github.com/nbclaudecodegui/NetbeansClaudeCodeGui/issues/20)
- Added a global setting to choose the default dock position of the Claude Code session tab (https://github.com/nbclaudecodegui/NetbeansClaudeCodeGui/issues/24)

# 0.21.62 (2026-04-25)

- Removed Claude indicator from the NetBeans status bar (duplicated the stop button in the session tab)
- Fixed diff dialog appearing as a detached tab with disabled Cancel/Accept-All buttons when Claude edits a file outside its current working directory (e.g. after cd into a subdirectory)
- Fixed choice menu not recognizing the Ultraplan "Tell Claude what to change" option as a text input field
- Added "Bypass Permissions" edit mode: automatically detected when Claude is launched with `--dangerously-skip-permissions`; auto-allows all file edits without showing a diff dialog
- Fixed PreToolUse and Stop hook HTTP 502 errors (regression in claude-code 2.1.118)
- Fixed toolbar button focusing an existing session tab instead of opening a new one when all open tabs have active sessions

# 0.21.54 (2026-04-22)

- Fixed prompt area being collapsible to zero height by dragging the divider
- Fixed terminal scrolling to the beginning and locking when a choice/confirmation menu appears (https://github.com/nbclaudecodegui/NetbeansClaudeCodeGui/issues/22)

# 0.21.50 (2026-04-19)

- Fixed diff panel appearing at wrong height (nearly invisible) after Reset Windows or on first use
- Fixed second diff in the same Claude session being invisible (stale card accumulation)
- Fixed terminal scrolling to the beginning when a choice menu or diff panel appears (regression since 0.20.20)

# 0.21.46 (2026-04-17)

- Fixed Maven Central publication being skipped after upload

# 0.21.34 (2026-04-17)

- Added minimum tested Claude Code CLI version (2.1.78) to installation requirements (https://github.com/nbclaudecodegui/NetbeansClaudeCodeGui/issues/29)
- Fixed anchor link clicks (`#section`) in markdown preview not enabling the Back button; Back now returns to the position before the anchor jump
- Fixed markdown preview not rendering images with relative paths (`![alt](path)` syntax)
- Fixed session tab blocking resize below ~600px when docked as a side panel (https://github.com/nbclaudecodegui/NetbeansClaudeCodeGui/issues/19)
- Added alphabetical sorting to the "Select Project" dropdown (https://github.com/nbclaudecodegui/NetbeansClaudeCodeGui/issues/21)
- Improved error panel on process start failure: shows Working Directory and CLAUDE_CONFIG_DIR fields (before Command and Error) for easier diagnosis; all fields have a right-click Copy menu; fixed "Restart Advanced…" not dismissing the error panel
- Added capability of changing the profile storage directory and sharing a single directory with several profiles
- Fixed drag-and-drop into text fields: files are now inserted at the text caret position instead of where the mouse was dropped
- Fixed Accept/Decline and Yes/No button colors to respect the IDE Look and Feel theme (dark themes no longer show clashing hardcoded colors)
- Added session management when opening a session from the toolbar and when closing a tab via the Close button
- Added configurable session mode: choose to resume the last session or start a new one

# 0.20.20 (2026-04-14)

- Fixed terminal appearing blank after dismissing the model selection menu
- Fixed MCP connection to localhost being routed through an active HTTP_PROXY/HTTPS_PROXY; NO_PROXY is now automatically extended with localhost and 127.0.0.1 when a proxy is configured
- Fixed terminal scrollbar disappearing when Claude shows a choice/confirmation prompt (https://github.com/nbclaudecodegui/NetbeansClaudeCodeGui/issues/22)
- Fixed Cancel button sending Ctrl+C instead of Esc, making it impossible to exit /usage and similar screens
- Fixed "Open with Claude Code" context menu not applying Default profile settings (API key, proxy, Extra CLI args, etc.) (https://github.com/nbclaudecodegui/NetbeansClaudeCodeGui/issues/23)
- Fixed Default profile settings (API key, connection type, proxy, etc.) not being saved when edited in Tools → Options → Claude Code → Profiles
- Fixed MCP config not working on Windows due to quote stripping in CreateProcess (https://github.com/nbclaudecodegui/NetbeansClaudeCodeGui/issues/13)
- Added Advanced tab in Tools → Options → Claude Code with Debug mode, Hang timeout, and a new "Enable MCP integration" toggle (disabling it skips the --mcp-config flag while hooks remain active)
- Fixed process start errors and hang timeouts now logged to the IDE log file at SEVERE level
- Added hang detection: configurable timeout (default 60 s) kills the process and shows an error if no PTY output is received after launch; set to 0 to disable
- Fixed authentication for profiles with the "Claude API" connection type: the API key is now correctly recognised by Claude Code on session start

# 0.20 (2026-04-09)

- Added Maven Central publication support: `release` Maven profile with GPG signing and Sonatype Central Portal deployment, plus CI `deploy` job that publishes artifacts on release builds

# 0.19 (2026-04-09)

- Fixed edit mode switch from plan mode to Ask on edit overshooting into acceptEdits/plan again
- Improved discovering model names
- Added extra CLI args support: per-profile "Extra CLI args" field in Options → Profiles, plus a per-session override field in the session selector (pre-filled from the profile)
- Added support for unnumbered choice menus (e.g. /resume session picker): displayed as radio buttons with title and session metadata, submitted via arrow-key navigation
- Fixed session Ready/Working state detection in several edge cases: plan mode, /resume picker, ⌕ character in response, post-response transitions, and named separator line (plan/branch name) above the input prompt
- Fixed choice menu not updating when Claude replaces one menu with another (e.g. multi-select → confirm screen)
- Fixed choice menu items having text in parentheses stripped (e.g. "Bash(find ...)" was shown as "Bash")
- Added right-click context menu ("Copy URL", "Open in Browser") to the Base URL field in Options → Profiles; items are disabled when the Other API connection type is not selected or the field is blank
- Added IDE Tools (MCP) section to the user manual with a full tool reference and example prompts
- Fixed MCP server connection failures that prevented Claude from using IDE tools (affected both Claude Code 2.x and multiple simultaneous sessions)
- Fixed terminal scrolling to the beginning when a choice menu or diff panel appears; the terminal now stays scrolled to the bottom
- Improved JediTerm terminal colors to match the current NetBeans Look &amp; Feel theme (background, foreground, selection color, and empty terminal area)
- Improved Markdown Preview colors to adapt to the current IDE theme (dark/light), including code blocks, tables, and blockquote highlights
- Fixed second choice menu not appearing after the first one is submitted
- Added support for multi-select menus (AskUserQuestion with multiSelect: true): checkboxes now appear instead of radio buttons
- Improved process launch error panel
- Documented status bar in user manual: edit mode selector with all modes, model selector, and right-side labels (session state, active plan, Claude version)
- Added support for multiple custom models in the model combo: models assigned the "custom" alias in Model Aliases appear in the dropdown and are switched via `/model <id>` command
- Added Refresh context menu item to Markdown Preview tab (re-reads file from disk, restores scroll position)
- Improved process launch error display: shows the attempted command and error message in a dedicated panel with a Back button and copyable text fields
- Fixed "Clear Older" in history dialog to include the selected entry in deletion and to show exact date and time in the confirmation prompt

# 0.18 (2026-04-04)

- Added documentation artifact (`*-docs.html`) to build and release outputs — user manual and installation guide bundled as a single self-contained HTML file with screenshots
- Added "Copy URL" context menu item in Markdown Preview when hovering over a hyperlink (copies absolute path for .md links, URL for external links)
- Added hyperlinks to claude.ai and console.anthropic.com next to token/API key fields in profile settings
- Changed default profiles directory from ~/.netbeans/claude-profiles/ to ~/.netbeans/claude-plugin/profiles/
- Renamed labels in Profiles settings: "Profiles directory" → "New Profiles Directory", "Config directory" → "Profile Storage Directory"
- Fixed "Accept All" button label in diff panel (was "AcceptAll")
- Reordered settings tabs: Favorites now appears before Profiles
- Changed plugin display name in Tools → Plugins to "Claude Code GUI"
- Fixed Markdown Preview tabs not restoring after IDE restart; tabs now reopen with correct content and scroll position
- Added hyperlink navigation in Markdown Preview: click to open in same tab, right-click for same tab / new tab / browser; back and forward navigation
- Added "Preview Markdown" context menu item for .md files in Projects/Files trees
- Fixed markdown pin preview tab — scroll position now preserved on updates; file-change tracking no longer lost on re-activation
- Added alpha-status notice to README
- Moved Requirements and Installation/Build instructions to docs/installation.md; README and user manual now link to it
- Added user manual covering all plugin features (docs/user-manual.md)
- README: updated Requirements with links, clarified Claude CLI PATH setup, recommended download from GitHub Releases, added note that plugin is written by Claude Code

# 0.17

Initial public release.

- Stage 17: GitHub CI/CD + NBM publishing
- Stage 16: FileDiff location config (inline vs separate tab)
- Stage 15: File attachments in prompt (DnD, @-completion)
- Stage 14: Prompt history and favorites
- Stage 13: Claude Code profiles with isolated config directories
- Stage 12: Prompt panel improvements
- Stage 11: Diff viewer with Accept/Reject/Cancel + settings.local.json merge
- Stage 10: MCP SSE server + IDE tools
- Stage 9: PromptResponsePanel for Claude interactive questions
- Stage 8: Refactor: each session = independent TopComponent
- Stage 7: Embedded terminal (JediTerm) for full Claude TUI
- Stage 6: Stream-JSON parsing + formatted output + markdown
- Stage 5: First working chat with Claude Code
- Stage 4: Window with tabs per working directory
- Stage 3: Toolbar button opening an empty window
- Stage 2: Settings page with Claude icon
- Stage 1: Dummy plugin (installable NBM)
