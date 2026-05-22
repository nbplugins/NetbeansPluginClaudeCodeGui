# Testing OpenAI Compatible Proxy with OpenCode Zen

OpenCode Zen provides free OpenAI-compatible models that can be used to test the proxy feature without spending money.

## 1. Registration and API Key

1. Go to https://opencode.ai/auth
2. Sign in with Google or GitHub
3. Add billing details (a card is required even for free models — no charges for free-tier usage)
4. Copy your API key from the account dashboard

## 2. Plugin Profile Setup

Open **Tools → Options → Claude Code → Profiles**, create a new profile:

| Field | Value |
|---|---|
| Connection type | OpenAI Compatible Proxy |
| Base URL | `https://opencode.ai/zen` |
| API Key | your key from step 1 |

> **Note:** do not add `/v1` to the Base URL — the plugin appends it automatically when routing requests (`/v1/chat/completions`) and fetching models (`/v1/models`).

## 3. Free Models

Click **Model Aliases** in the profile editor. Use **Fetch** to load the current model list, or add manually:

| Model name | Model ID |
|---|---|
| Big Pickle | `opencode/big-pickle` |
| DeepSeek V4 Flash Free | `opencode/deepseek-v4-flash-free` |
| Nemotron 3 Super Free | `opencode/nemotron-3-super-free` |

All three models are $0 for input and output, context window 200K+. The list is time-limited while model teams collect feedback — check https://opencode.ai/docs/zen/ for the current list.

Map Claude model tiers to OpenCode models, for example:

| Alias (Claude tier) | Target model |
|---|---|
| `claude-sonnet-4-5` | `opencode/big-pickle` |
| `claude-haiku-4-5` | `opencode/deepseek-v4-flash-free` |
| `claude-opus-4-7` | `opencode/nemotron-3-super-free` |

Claude Code sends requests using names like `claude-sonnet-4-5` — Model Aliases redirects them to the actual provider model. Without an alias the request will fail because OpenCode does not recognize Claude model names.

## 4. Start a Session

Start a Claude Code session with this profile. The plugin will:
- Set `ANTHROPIC_BASE_URL` to the internal proxy (`http://127.0.0.1:<mcp-port>/openai-proxy/<uuid>`)
- Translate Anthropic Messages API requests → OpenAI Chat Completions
- Translate responses back, including streaming SSE and tool use

## 5. What to Verify

- **Basic conversation** — send a simple question, verify a response arrives
- **Streaming** — response should appear incrementally, not all at once
- **Tool use** — ask Claude to read or edit a file, verify the tool_use → tool_result round-trip works
- **Debug logging** — enable debug mode in preferences, then check `~/.netbeans/28/var/log/messages.log` for lines showing request/response metadata (headers, model, token counts) with no message content
- **Two simultaneous sessions** — open two sessions with the same profile, verify both work independently
- **Profile edit during session** — change the API key while a session is running, verify the running session is unaffected; restart to apply the new key
