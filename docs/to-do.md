# Bugs

- [x] Невозможно выйти из /usage по кнопке cancel. Отладить
- [ ] Улучшить определение моделей: Из списка
    ```
    ❯ /model

    ──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
      Select model
      Switch between Claude models. Applies to this session and future Claude Code sessions. For other/previous model names, specify with --model.

      ❯ 1. Default (recommended) ✔  Sonnet 4.6 · Best for everyday tasks
        2. Opus                     Opus 4.6 · Most capable for complex work
        3. Haiku                    Haiku 4.5 · Fastest for quick answers

      ○ Low effort ← → to adjust

      Enter to confirm · Esc to exit
    ```
    должно выбираться [ "Sonnet 4.6", "Opus 4.6", "Haiku 4.5" ], а из списка
    ```
      Select model
      Switch between Claude models. Applies to this session and future Claude Code sessions. For other/previous model names, specify with --model.

      ❯ 1. Default (recommended) ✔  Use the default model (currently anthropic/claude-sonnet-4.6) · $3/$15 per Mtok
        2. Sonnet (1M context)      Sonnet 4.6 for long sessions · $3/$15 per Mtok
        3. Opus (1M context)        Opus 4.6 with 1M context · Most capable for complex work
        4. Haiku                    Haiku 4.5 · Fastest for quick answers · $1/$5 per Mtok

      ○ Effort not supported for Default (recommended)

      Enter to confirm · Esc to exit
    ```
    [ "anthropic/claude-sonnet-4.6", "Sonnet 4.6 for long sessions", "Opus 4.6 with 1M context", "Haiku 4.5" ]

# Features
