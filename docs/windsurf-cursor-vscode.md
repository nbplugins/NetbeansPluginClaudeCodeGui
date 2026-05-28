# Using NetBeans as an MCP Server from Windsurf, Cursor, and VS Code

This plugin exposes a full **MCP SSE server** inside NetBeans. Once NetBeans is running with the plugin installed, any MCP-capable IDE (Windsurf, Cursor, VS Code with an MCP extension) can connect to it and use NetBeans tools — open projects, file operations, diagnostics, current selection, diff viewer, and more.

---

## Prerequisites

1. Apache NetBeans 23+ with the **Claude Code GUI** plugin installed and running.
2. The MCP SSE server starts automatically on NetBeans launch (default port **28991**).
3. Verify it is running: **Tools → Claude Code Status** — look for `🟢 MCP SSE Server: Running on port 28991`.
4. You can also verify with: `http://localhost:28991/status`

---

## Windsurf

Edit (or create) `~/.codeium/windsurf/mcp_config.json`:

```json
{
  "mcpServers": {
    "netbeans": {
      "serverUrl": "http://localhost:28991/sse"
    }
  }
}
```

Then **restart Windsurf** (or click **Refresh** in the Cascade MCP panel). The NetBeans tools will appear under the `netbeans` server in the panel.

If you changed the port in **Tools → Options → Claude Code → General → MCP server port**, replace `28991` with your configured port.

---

## Cursor

Edit (or create) `~/.cursor/mcp.json`:

```json
{
  "mcpServers": {
    "netbeans": {
      "url": "http://localhost:28991/sse"
    }
  }
}
```

Or for a workspace-scoped config, place `.cursor/mcp.json` at the root of your project:

```json
{
  "mcpServers": {
    "netbeans": {
      "url": "http://localhost:28991/sse"
    }
  }
}
```

Restart Cursor and enable the `netbeans` server in **Settings → MCP**.

---

## VS Code (with MCP extension)

If you are using the [Continue](https://marketplace.visualstudio.com/items?itemName=Continue.continue) or another MCP-capable extension, add to your VS Code `settings.json`:

```json
{
  "mcpServers": {
    "netbeans": {
      "url": "http://localhost:28991/sse"
    }
  }
}
```

Or place `.vscode/mcp.json` (VS Code 1.99+ native MCP support) in your workspace:

```json
{
  "servers": {
    "netbeans": {
      "type": "sse",
      "url": "http://localhost:28991/sse"
    }
  }
}
```

---

## Available MCP Tools

Once connected, the AI agent in your IDE can call:

| Tool | Description |
|------|-------------|
| `openFile` | Opens a file in the NetBeans editor |
| `getWorkspaceFolders` | Lists all open NetBeans projects |
| `getOpenEditors` | Lists currently open editor tabs |
| `getCurrentSelection` | Returns the selected text in the active editor |
| `getDiagnostics` | Gets error/warning diagnostics for files |
| `checkDocumentDirty` | Checks if a document has unsaved changes |
| `saveDocument` | Saves a document to disk |
| `close_tab` | Closes an open editor tab |
| `closeAllDiffTabs` | Closes all diff viewer tabs |
| `openDiff` | Opens a git diff for a file |
| `permission_prompt` | Shows a file diff and asks Accept/Deny |
| `show_markdown` | Displays markdown in the Markdown Preview tab |
| `show_markdown_file` | Opens a live-updating Markdown Preview for a `.md` file |

---

## Port Configuration

The default port is `28991`. To change it:

1. Open NetBeans → **Tools → Options → Claude Code → General**
2. Change **MCP server port**
3. Restart NetBeans
4. Update the `serverUrl` in your IDE's MCP config to match

---

## Status / Health Check

```
GET http://localhost:28991/status
```

Returns:
```json
{
  "status": "running",
  "port": 28991,
  "transport": "sse",
  "sseEndpoint": "/sse",
  "messagesEndpoint": "/messages",
  "activeSessions": 0
}
```
