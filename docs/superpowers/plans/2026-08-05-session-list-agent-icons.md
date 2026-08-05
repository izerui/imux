# Session List Agent Icons Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show Claude/OpenAI brand icons in the conversation tree without replacing or shrinking the existing running and unread markers.

**Architecture:** Move Agent icon loading into a shared `AgentIcons` holder used by both editor tabs and the tool-window tree. Compose each session row with IntelliJ Platform 262's native `RowIcon`, reserving one 16×16 slot for the brand and one for the unchanged status icon.

**Tech Stack:** Kotlin 2.3, IntelliJ Platform 262 `RowIcon`/`EmptyIcon`/`AllIcons`, JUnit 4.

---

## File Structure

- Create `src/main/kotlin/com/github/izerui/imux/icons/AgentIcons.kt`: shared Claude/Codex icon holder.
- Modify `src/main/kotlin/com/github/izerui/imux/terminal/AgentTerminalFileIconProvider.kt`: consume the shared holder.
- Modify `src/test/kotlin/com/github/izerui/imux/terminal/AgentTerminalFileIconProviderTest.kt`: verify the shared holder.
- Modify `src/main/kotlin/com/github/izerui/imux/toolwindow/AgentSessionTree.kt`: compose brand and status icons in the renderer.
- Create `src/test/kotlin/com/github/izerui/imux/toolwindow/AgentSessionTreeIconTest.kt`: verify the two icon slots and idle placeholder.

### Task 1: Extract a shared Agent icon holder

**Files:**
- Create: `src/main/kotlin/com/github/izerui/imux/icons/AgentIcons.kt`
- Modify: `src/main/kotlin/com/github/izerui/imux/terminal/AgentTerminalFileIconProvider.kt`
- Modify: `src/test/kotlin/com/github/izerui/imux/terminal/AgentTerminalFileIconProviderTest.kt`

- [ ] **Step 1: Change the existing icon test to require the shared holder**

Add this import to `AgentTerminalFileIconProviderTest.kt`:

```kotlin
import com.github.izerui.imux.icons.AgentIcons
```

Replace both `AgentTerminalIcons.forAgent(...)` calls with `AgentIcons.forAgent(...)`.

- [ ] **Step 2: Run the test and verify it fails**

Run:

```bash
./gradlew test --tests com.github.izerui.imux.terminal.AgentTerminalFileIconProviderTest
```

Expected: compilation fails because `com.github.izerui.imux.icons.AgentIcons` does not exist.

- [ ] **Step 3: Create the shared icon holder**

Create `AgentIcons.kt`:

```kotlin
package com.github.izerui.imux.icons

import com.github.izerui.imux.model.AgentType
import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/**
 * `AllIcons` 没有 OpenAI / Claude 品牌标志，无法用语义匹配的平台图标替代；
 * 资源仍交给 [IconLoader] 选择主题与 HiDPI 变体。
 */
internal object AgentIcons {
    private val claude = IconLoader.getIcon("/icons/claude.png", javaClass)
    private val codex = IconLoader.getIcon("/icons/codex.png", javaClass)

    fun forAgent(agentType: AgentType): Icon = when (agentType) {
        AgentType.CLAUDE -> claude
        AgentType.CODEX -> codex
    }
}
```

- [ ] **Step 4: Update the file icon provider**

Import `AgentIcons`, call it from `getIcon`, and remove the old `AgentTerminalIcons` object:

```kotlin
import com.github.izerui.imux.icons.AgentIcons

class AgentTerminalFileIconProvider : FileIconProvider {

    override fun getIcon(file: VirtualFile, flags: Int, project: Project?): Icon? {
        val terminalFile = file as? AgentTerminalVirtualFile ?: return null
        return AgentIcons.forAgent(terminalFile.agentType)
    }
}
```

- [ ] **Step 5: Run the provider test**

Run:

```bash
./gradlew test --tests com.github.izerui.imux.terminal.AgentTerminalFileIconProviderTest
```

Expected: all selected tests pass.

- [ ] **Step 6: Commit the shared holder**

```bash
git add \
  src/main/kotlin/com/github/izerui/imux/icons/AgentIcons.kt \
  src/main/kotlin/com/github/izerui/imux/terminal/AgentTerminalFileIconProvider.kt \
  src/test/kotlin/com/github/izerui/imux/terminal/AgentTerminalFileIconProviderTest.kt
git commit -m "refactor: 共享 Agent 品牌图标"
```

### Task 2: Compose brand and status icons in session rows

**Files:**
- Modify: `src/main/kotlin/com/github/izerui/imux/toolwindow/AgentSessionTree.kt`
- Create: `src/test/kotlin/com/github/izerui/imux/toolwindow/AgentSessionTreeIconTest.kt`

- [ ] **Step 1: Write failing tests for the two icon slots**

Create `AgentSessionTreeIconTest.kt`:

```kotlin
package com.github.izerui.imux.toolwindow

import com.github.izerui.imux.icons.AgentIcons
import com.github.izerui.imux.model.AgentType
import com.intellij.icons.AllIcons
import com.intellij.util.ui.EmptyIcon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSessionTreeIconTest {

    @Test
    fun `运行中会话同时保留品牌与状态图标`() {
        val icon = sessionRowIcon(AgentType.CLAUDE, AllIcons.Nodes.RunnableMark)

        assertEquals(2, icon.iconCount)
        assertSame(AgentIcons.forAgent(AgentType.CLAUDE), icon.getIcon(0))
        assertSame(AllIcons.Nodes.RunnableMark, icon.getIcon(1))
    }

    @Test
    fun `普通会话为空状态预留固定图标槽位`() {
        val icon = sessionRowIcon(AgentType.CODEX, null)

        assertEquals(2, icon.iconCount)
        assertSame(AgentIcons.forAgent(AgentType.CODEX), icon.getIcon(0))
        assertTrue(icon.getIcon(1) is EmptyIcon)
        assertEquals(32, icon.iconWidth)
        assertEquals(16, icon.iconHeight)
    }
}
```

- [ ] **Step 2: Run the test and verify it fails**

Run:

```bash
./gradlew test --tests com.github.izerui.imux.toolwindow.AgentSessionTreeIconTest
```

Expected: compilation fails because `sessionRowIcon` does not exist.

- [ ] **Step 3: Add the native icon composition helper**

Add imports to `AgentSessionTree.kt`:

```kotlin
import com.github.izerui.imux.icons.AgentIcons
import com.intellij.ui.RowIcon
import com.intellij.util.ui.EmptyIcon
import javax.swing.Icon
```

Add this top-level helper next to the existing tree helpers:

```kotlin
internal fun sessionRowIcon(agentType: AgentType, statusIcon: Icon?): RowIcon =
    RowIcon(AgentIcons.forAgent(agentType), statusIcon ?: EmptyIcon.ICON_16)
```

- [ ] **Step 4: Update the renderer without changing status precedence**

Replace the renderer's icon/text status block with:

```kotlin
val statusIcon = when {
    session?.running == true -> AllIcons.Nodes.RunnableMark
    session?.unread == true -> AllIcons.General.Modified
    else -> null
}

icon = when (data) {
    is NodeData.Group -> AgentIcons.forAgent(data.agentType)
    is NodeData.Session -> sessionRowIcon(data.agentType, statusIcon)
    is NodeData.PendingSession -> sessionRowIcon(data.agentType, null)
    else -> null
}

when {
    session?.running == true -> append(text, SimpleTextAttributes.REGULAR_ATTRIBUTES)
    session?.unread == true -> append(text, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
    else -> append(text, SimpleTextAttributes.REGULAR_ATTRIBUTES)
}
```

This keeps running ahead of unread exactly as before and preserves the original platform status icon instances.

- [ ] **Step 5: Run targeted tree and platform-alignment tests**

Run:

```bash
./gradlew test \
  --tests com.github.izerui.imux.toolwindow.AgentSessionTreeIconTest \
  --tests com.github.izerui.imux.PlatformApiAlignmentSourceTest
```

Expected: all selected tests pass.

- [ ] **Step 6: Commit session-tree integration**

```bash
git add \
  src/main/kotlin/com/github/izerui/imux/toolwindow/AgentSessionTree.kt \
  src/test/kotlin/com/github/izerui/imux/toolwindow/AgentSessionTreeIconTest.kt
git commit -m "feat: 在会话列表显示 Agent 品牌图标"
```

### Task 3: Full verification

**Files:**
- Verify all changed files.

- [ ] **Step 1: Run a clean full test and plugin build**

Run:

```bash
./gradlew clean test buildPlugin verifyPluginStructure
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Inspect the packaged plugin**

Verify the built JAR contains `AgentIcons`, the session-tree changes, and the existing icon resources:

```bash
ZIP=$(find "$PWD/build/distributions" -maxdepth 1 -name '*.zip' -print | head -1)
TMP=$(mktemp -d /tmp/imux-list-icons-check.XXXXXX)
cd "$TMP"
unzip -q "$ZIP"
JAR=$(find . -name 'imux-*.jar' -print | head -1)
jar tf "$JAR" | rg 'AgentIcons|AgentSessionTree|icons/(codex|claude)'
```

Expected: the shared holder, tree classes, and all Agent icon resources are packaged.

- [ ] **Step 3: Check final repository state**

Run:

```bash
git diff b7c8845..HEAD --check
git status --short
```

Expected: no whitespace errors. Preserve the pre-existing untracked `src/main/kotlin/com/github/.DS_Store`.
