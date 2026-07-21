# Claude launch tests

Python scripts for testing different claude launch modes.
Each file tests a specific hypothesis about how claude behaves with various
stdin/stdout/env configurations.

Run any script directly:
    python3 <script>.py

All scripts unset CLAUDECODE and CLAUDE_CODE_ENTRYPOINT to avoid the
"nested session" error when running from inside a Claude Code session.

## Scripts

| File | What it tests |
|------|---------------|
| `test_stdin_open_vs_closed.py` | Does claude --print hang when stdin is kept open (no EOF)? |
| `test_term_env.py` | Does presence/absence of TERM affect output buffering? |
| `test_stdin_close_after_output.py` | Can we send a response via stdin if we close it after receiving output? |
| `test_interactive_choices.py` | Can claude ask clarifying questions and receive answers via stdin in --print mode? |
| `test_429_retry_headers.py` | Does a response header stop Claude Code CLI's built-in 429 retry loop? |
| `test_usage_command_endpoints.py` | Does the `/usage` slash command hit a live network endpoint? |
| `test_session_id_header_stability.py` | Does `X-Claude-Code-Session-Id` stay stable across `--continue`? |
| `test_system_prompt_prefix_stability.py` | Does the `system` field stay byte-identical turn-to-turn (needed for cache breakpoints)? |

## Conclusion: interactivity in --print mode

| Scenario | Result |
|----------|--------|
| Clarifying questions + stdin open | Deadlock — claude produces no output until stdin is closed (EOF) |
| AskUserQuestion tool in --print mode | Disabled — returns error immediately, claude never waits for stdin |
| Permission prompts in --print mode | Reported as JSON `permission_denials` in the `result` event, not interactive prompts |
| Long-lived process without --print | Not possible — claude requires `--print` for pipe mode, otherwise prints error |

**Interactive stdin responses are not possible in `--print` mode.** This is an architectural constraint
of the CLI, not a PTY issue. PTY is only needed for permission prompts when running without
`--dangerously-skip-permissions`. For the plugin, `--dangerously-skip-permissions` is an acceptable
default (with a warning in the UI), deferring PTY support to a later optional stage.

## Conclusion: 429 retry suppression

| Response header | Requests before giving up | Wall time |
|---|---|---|
| none (baseline) | 8+ (still retrying at 90s test cap) | 90s+ (would reach ~10min for all 10 attempts) |
| `Retry-After: 0` | 8+ (behaves like baseline) | 90s+ |
| `Retry-After: 999999` | 1 | ~0.1s |
| `X-Should-Retry: false` | 1 | ~0.1s |

**A large `Retry-After` value or an explicit `X-Should-Retry: false` header both make Claude Code
CLI give up immediately** instead of retrying up to 10 times, surfacing
`{"error":"rate_limit","is_error":true,"api_error_status":429,...}` right away. A small
`Retry-After` value does not suppress retries. Applied in
`OpenAIProxyServlet.setRetryAfterIfHardLimit()` — only for genuine hard-quota-exhaustion errors
that carry a `resets_in_seconds` field, not for ordinary transient 429s.
