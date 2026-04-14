# Bugs

- [x] **Tooltip "Stop session" → "Close session"** — `ClaudeSessionTab.java:229`: переименовать tooltip кнопки `stopButton` с "Stop session" на "Close session" (кнопка закрывает сессию, а не останавливает процесс).
- [x] **IDE restore не сохраняет режим Resume specific** — `ClaudeSessionTab.writeExternal()` сохраняет значение настройки `contextMenuSessionMode` вместо фактического режима/resumeId, с которым была открыта сессия. Если сессия была открыта через "Resume specific", после рестарта IDE она восстановится в режиме Continue last (или New), а не возобновит ту же конкретную сессию.
- [ ] Drag and drop in a text field: вставлять в текущую позицию курсора, а не 
сдвигать позицию курсора. Рднако, возможность перемещения позиции курсора кликом должна быть созранена
- [x] Невозможно выйти из /usage по кнопке cancel. Отладить
- [x] Не учитываются цвета Look and Feel для кнопок
- [ ] Previe markdown: не показываются изображения
- [ ] Previe markdown: не работает навигация по линкам внутри файла

# Features
