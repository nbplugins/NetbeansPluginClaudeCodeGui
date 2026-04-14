- Fixed MCP connection to localhost being routed through an active HTTP_PROXY/HTTPS_PROXY; NO_PROXY is now automatically extended with localhost and 127.0.0.1 when a proxy is configured
- Fixed terminal scrollbar disappearing when Claude shows a choice/confirmation prompt (https://github.com/nbclaudecodegui/NetbeansClaudeCodeGui/issues/22)
- Fixed Cancel button sending Ctrl+C instead of Esc, making it impossible to exit /usage and similar screens
- Fixed "Open with Claude Code" context menu not applying Default profile settings (API key, proxy, Extra CLI args, etc.) (https://github.com/nbclaudecodegui/NetbeansClaudeCodeGui/issues/23)
- Added alphabetical sorting to the "Select Project" dropdown (https://github.com/nbclaudecodegui/NetbeansClaudeCodeGui/issues/21)
- Fixed Default profile settings (API key, connection type, proxy, etc.) not being saved when edited in Tools → Options → Claude Code → Profiles
- Fixed MCP config not working on Windows due to quote stripping in CreateProcess (https://github.com/nbclaudecodegui/NetbeansClaudeCodeGui/issues/13)
- Improved error panel on process start failure: shows Working Directory and CLAUDE_CONFIG_DIR fields (before Command and Error) for easier diagnosis; all fields have a right-click Copy menu; fixed "Restart Advanced…" not dismissing the error panel
- Added Advanced tab in Tools → Options → Claude Code with Debug mode, Hang timeout, and a new "Enable MCP integration" toggle (disabling it skips the --mcp-config flag while hooks remain active)
- Fixed process start errors and hang timeouts now logged to the IDE log file at SEVERE level
- Added hang detection: configurable timeout (default 60 s) kills the process and shows an error if no PTY output is received after launch; set to 0 to disable
- Fixed authentication for profiles with the "Claude API" connection type: the API key is now correctly recognised by Claude Code on session start
- Added capability of changing the profile storage directory and sharing a single directory with several profiles
- Fixed drag-and-drop into text fields: files are now inserted at the text caret position instead of where the mouse was dropped
- Fixed Accept/Decline and Yes/No button colors to respect the IDE Look and Feel theme (dark themes no longer show clashing hardcoded colors)
- Added session management when opening a session from the toolbar and when closing a tab via the Close button
- Added configurable session mode: choose to resume the last session or start a new one

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
