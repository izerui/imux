# Terminal Scroll To Bottom Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a scroll-to-bottom icon button inside every imux terminal Editor.

**Architecture:** `AgentTerminalFileEditor` owns a transparent bottom-right Action Toolbar overlay. The action delegates to `TerminalHost`, which resolves the active terminal Editor at invocation time and scrolls its `ScrollingModel` without animation.

**Tech Stack:** Kotlin, IntelliJ Platform 262 Action System, Swing layered panes, JUnit 4 source integration tests

---

### Task 1: Lock The UI Contract

**Files:**
- Modify: `src/test/kotlin/com/github/izerui/imux/TerminalIntegrationSourceTest.kt`

- [x] **Step 1: Write the failing test**

Add a test asserting that `AgentTerminalFileEditor` creates an `ActionToolbar` with `AllIcons.Actions.MoveDown`, the “滚动到底部” tooltip, and a call to `TerminalHost.getInstance(project).scrollToBottom(virtualFile.terminalView)`.

- [x] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew test --tests com.github.izerui.imux.TerminalIntegrationSourceTest
```

Expected: FAIL because the Editor does not yet contain the action button.

### Task 2: Add The Editor Overlay

**Files:**
- Modify: `src/main/kotlin/com/github/izerui/imux/terminal/AgentTerminalFileEditor.kt`
- Modify: `src/main/kotlin/com/github/izerui/imux/terminal/TerminalHost.kt`

- [x] **Step 1: Expose the existing scroll operation**

Change `TerminalHost.scrollToBottom(TerminalView)` from private to internal so both completion notifications and the Editor action use the same implementation.

- [x] **Step 2: Create the action toolbar**

Create one `AnAction("滚动到底部", "滚动到底部", AllIcons.Actions.MoveDown)` in `AgentTerminalFileEditor`. Its `actionPerformed` calls:

```kotlin
TerminalHost.getInstance(project).scrollToBottom(virtualFile.terminalView)
```

- [x] **Step 3: Overlay the toolbar**

Wrap the terminal panel in a `JBLayeredPane` that lays the terminal across the full bounds and positions the mini toolbar at the bottom-right with scaled margins.

- [x] **Step 4: Run the focused test**

Run:

```bash
./gradlew test --tests com.github.izerui.imux.TerminalIntegrationSourceTest
```

Expected: PASS.

### Task 3: Verify The Plugin

**Files:**
- No additional files

- [x] **Step 1: Run all tests**

```bash
./gradlew test
```

Expected: BUILD SUCCESSFUL with zero failed tests.

- [x] **Step 2: Build the plugin**

```bash
./gradlew buildPlugin
```

Expected: BUILD SUCCESSFUL and a plugin ZIP under `build/distributions/`.
