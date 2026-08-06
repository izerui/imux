STATUS: DONE

改动摘要:
- 在 /Users/liuyuhua/github/imux/.claude/worktrees/agent-af7753e43680ea2a7/src/test/kotlin/com/github/izerui/imux/session/SessionListModelTest.kt 新增最小集成语义测试 `真实会话 id 调用取消时返回 false 且保留会话`，确认用真实会话 id 调用 `cancelPending` 为 no-op。
- 在 /Users/liuyuhua/github/imux/.claude/worktrees/agent-af7753e43680ea2a7/src/main/kotlin/com/github/izerui/imux/monitor/SessionMonitor.kt 新增 `cancelPendingSession(key: String)`，内部仅转调 `model.cancelPending(key)`，未额外 `refresh()` 或 `notifyListeners()`。
- 在 /Users/liuyuhua/github/imux/.claude/worktrees/agent-af7753e43680ea2a7/src/main/kotlin/com/github/izerui/imux/terminal/AgentTerminalFileEditor.kt 的 `dispose()` 真实关闭路径中，保持 `CLOSING_TO_REOPEN` 早退逻辑不变，并按要求顺序先调用 `TerminalHost.getInstance(project).closeSession(virtualFile.sessionKey)`，再调用 `SessionMonitor.getInstance(project).cancelPendingSession(virtualFile.sessionKey)`。

测试命令与结果:
- `./gradlew test --tests 'com.github.izerui.imux.session.SessionListModelTest.真实会话\ id\ 调用取消时返回\ false\ 且保留会话'` -> FAILED，原因是 Gradle `--tests` 过滤器未匹配到 Kotlin 中文反引号测试名，属于过滤方式问题，不是断言失败。
- `./gradlew test --tests 'com.github.izerui.imux.session.SessionListModelTest'` -> PASS
- `./gradlew compileKotlin compileTestKotlin` -> BUILD SUCCESSFUL

提交哈希:
- pending

self-review:
- 仅改动 brief 指定的 3 个文件，未触碰 `TerminalHost`、`AgentToolWindowFactory` 或其他计划外文件。
- `SessionMonitor.cancelPendingSession` 保持最小封装，无额外副作用。
- `AgentTerminalFileEditor.dispose()` 中调用顺序与早退逻辑均符合 brief 精确要求。
- 未做 Task 3 范围内的最终总验证。

concerns:
- brief 中给出的单测命令在当前 Gradle/JUnit 组合下无法通过 `--tests` 精确匹配中文 Kotlin 反引号测试名，因此以同文件测试集运行完成语义覆盖验证；这不影响代码实现本身。
