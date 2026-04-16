# Installation & Build

## Requirements

| Requirement | Minimum version |
|-------------|-----------------|
| [Apache NetBeans IDE](https://netbeans.apache.org/front/main/download/) | 23 (RELEASE230) |
| Java | 17 |
| [Claude Code CLI](https://docs.anthropic.com/en/docs/claude-code/getting-started) (`claude`) | 2.1.78 |

> **Note:** The plugin has been tested with Claude Code CLI 2.1.78 and newer. Older versions may not work correctly.

The `claude` executable must be on your system `PATH` **or** its absolute path must be configured in **Tools → Options → Claude Code → General → Claude CLI path** after installing the plugin.

---

## Installation

### Recommended: download from GitHub Releases

Download the latest `.nbm` file from the [Releases page](https://github.com/nbclaudecodegui/NetbeansClaudeCodeGui/releases/latest).

### Intermediate builds

Builds between releases are available as artifacts on the [Actions](https://github.com/nbclaudecodegui/NetbeansClaudeCodeGui/actions) page — open the latest successful workflow run and download the `nbm` artifact (delivered as a zip file; extract the `.nbm` before installing).

### Install into NetBeans

1. Open NetBeans → **Tools → Plugins**
2. Switch to the **Downloaded** tab
3. Click **Add Plugins…** and select the `.nbm` file
4. Click **Install** and follow the wizard
5. Restart NetBeans when prompted

### Uninstall

1. Open NetBeans → **Tools → Plugins**
2. Switch to the **Installed** tab
3. Make sure **Show details** is checked — otherwise only group entries are shown and individual plugins cannot be selected
4. Check the checkbox next to **Claude Code GUI** in the **Select** column
5. Click **Uninstall**
6. Restart NetBeans when prompted

---

## Build from Source

```bash
mvn nbm:nbm
```

The installable plugin file is created at:

```
target/netbeans-claude-code-gui-*.nbm
```

### Other build commands

```bash
mvn package              # Full build with tests
mvn package -DskipTests  # Build without tests
mvn test                 # Run all unit tests
```
