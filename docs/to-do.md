# [ ] Bug 1

Choice menu не распознаёт 4 как вариант для текстового ввода

```
╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌

 Claude has written up a plan and is ready to execute. Would you like to proceed?

 ❯ 1. Yes, auto-accept edits
   2. Yes, manually approve edits
   3. No, refine with Ultraplan on Claude Code on the web
   4. Tell Claude what to change
      shift+tab to approve with this feedback

 ctrl-g to edit in nano · ~/.claude/plans/https-github-com-nbclaudecodegui-netbean-serialized-kazoo.md
```

# [x] Bug 2
 
```                                                                                                                                                                                                                                                           
● Ran 1 stop hook                                                                                                                                                                                                                                           
  ⎿  http://localhost:28991/stop                                                                                                                                                                                                                          
  ⎿  Stop hook error: HTTP 502 from http://localhost:28991/stop                                                                                                                                                                                           
```

# [ ] Feature 3

Добавить возможность навигации стрелками в ChoiceMenuPanel:
- Right, Left
    - между Yes и No
- Right - из checkbox в текстовое поле ввода, соответствующее этому чекбоксу, если оно есть    
- Up, Down
    - С Yes, No - на последний/первый radiobox
    - Из текстового поля ввода - на предыдущий/последующий пункт

# [x] Bug 4

Не поддерживает skip permition mode в комбо.
