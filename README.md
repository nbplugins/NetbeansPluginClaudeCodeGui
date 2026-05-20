# Claude Code GUI — NetBeans Plugin

![Build](https://github.com/nbplugins/NetbeansPluginClaudeCodeGui/actions/workflows/build.yml/badge.svg)
[![Release](https://img.shields.io/github/v/release/nbplugins/NetbeansPluginClaudeCodeGui)](https://github.com/nbplugins/NetbeansPluginClaudeCodeGui/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/nbplugins/NetbeansPluginClaudeCodeGui/total)](https://github.com/nbplugins/NetbeansPluginClaudeCodeGui/releases)

![Overview](docs/screenshots/overview.png)

NetBeans Claude Code GUI is a NetBeans IDE plugin that embeds the Claude Code CLI as a full interactive terminal session directly inside the IDE. You type prompts in a dedicated session tab, Claude reads and edits your project files, and the plugin provides:

- **Graphical file diff** — review every proposed file change before it is written to disk; accept, decline (with an optional reason), or interrupt Claude
- **Interactive choice menu** — Claude's Yes/No and multiple-choice prompts appear as a native panel instead of raw terminal text
- **Prompt history and favorites** — recall past prompts with Ctrl+Up/Down; save reusable prompts as favorites with optional keyboard shortcuts
- **File attachments** — attach files via `@path` tokens with auto-completion popup, drag-and-drop, and clipboard paste support
- **Multiple profiles** — run Claude Code under separate accounts or API keys for different projects, each with an isolated config directory, authentication, proxy, and model settings
- **Session management** — start new sessions, continue the last session, or resume a specific past session; sessions persist across IDE restarts
- **Markdown Preview** — live-rendered markdown tab for plan files and MCP-initiated previews; includes a find bar (Ctrl+F) and font zoom (Alt+Scroll)
- **Auto Plan Preview** — when Claude writes a plan file, a live preview tab opens automatically as soon as you accept the diff
- **IDE integration via MCP** — open editors, diagnostics, current selection, and file operations are exposed to Claude via the MCP protocol so it always has full context about your work
- **NetBeans look & feel** — the plugin respects the active NetBeans color theme and font settings, including dark/light mode
- **Flexible UI customization** — per-session terminal font and zoom (Alt+Scroll), configurable diff viewer placement, adjustable session list limit, and keyboard shortcuts for favorite prompts

The plugin code was written entirely by [Claude Code](https://claude.ai/code) using **Claude Sonnet 4.6**, with the author acting as architect and reviewer.

---

## Download

Download the latest `.nbm` file from [GitHub Releases](https://github.com/nbplugins/NetbeansPluginClaudeCodeGui/releases/latest).

Intermediate builds between releases are available as artifacts on the [Actions](https://github.com/nbplugins/NetbeansPluginClaudeCodeGui/actions) page — open the latest successful workflow run and download the `nbm` artifact (delivered as a zip file; extract the `.nbm` before installing).

---

See [Installation & Build](docs/installation.md) for requirements, installation steps, and build instructions.

---

## Usage

See the [User Manual](docs/user-manual.md) for full documentation of all plugin features.

---


## Third-party code

The MCP server integration (package `org.openbeans.claude.netbeans`) is based on
[claude-code-netbeans](https://github.com/emilianbold/claude-code-netbeans)
by Emilian Marius Bold, used under the **ISC License**:

> Copyright (c) 2025 Emilian Marius Bold
>
> Permission to use, copy, modify, and distribute this software for any purpose
> with or without fee is hereby granted, provided that the above copyright notice
> and this permission notice appear in all copies.
>
> THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES WITH
> REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF MERCHANTABILITY AND
> FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY SPECIAL, DIRECT,
> INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING FROM
> LOSS OF USE, DATA OR PROFITS, WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR
> OTHER TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION WITH THE USE OR
> PERFORMANCE OF THIS SOFTWARE.

**Changes made:** updated target NetBeans version from RELEASE190 to RELEASE230;
integrated into the `netbeans-claude-code-gui` plugin build alongside the PTY terminal component.

---

## License

[Apache License 2.0](LICENSE)
