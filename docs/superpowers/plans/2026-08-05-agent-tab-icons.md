# Agent Tab Icons Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Display the user-provided OpenAI and Claude brand icons on Codex and Claude Code editor terminal tabs.

**Architecture:** Register IntelliJ Platform 262's official `FileIconProvider` extension and return an icon based on `AgentTerminalVirtualFile.agentType`. Keep icon loading in a focused holder object and store theme-aware 16×16/32×32 PNG resources under the existing `icons` resource directory.

**Tech Stack:** Kotlin 2.3, IntelliJ Platform 262 `FileIconProvider`/`IconLoader`, JUnit 4, PNG resources generated with Pillow.

---

## File Structure

- Create `src/main/kotlin/com/github/izerui/imux/terminal/AgentTerminalFileIconProvider.kt`: map imux terminal virtual files to Agent icons through the official platform extension.
- Modify `src/main/resources/META-INF/plugin.xml`: register `com.intellij.fileIconProvider`.
- Create `src/main/resources/icons/codex.png`, `codex_dark.png`, `codex@2x.png`, `codex@2x_dark.png`: transparent OpenAI mark for light/dark themes and standard/HiDPI rendering.
- Create `src/main/resources/icons/claude.png`, `claude@2x.png`: Claude brand tile; one color treatment works in both themes.
- Modify `src/test/kotlin/com/github/izerui/imux/PluginXmlRegistrationTest.kt`: protect the extension registration.
- Modify `src/test/kotlin/com/github/izerui/imux/IconResourceTest.kt`: protect dimensions, transparency, and theme variants.
- Create `src/test/kotlin/com/github/izerui/imux/terminal/AgentTerminalFileIconProviderTest.kt`: verify Agent mapping and that unrelated files are ignored.

### Task 1: Add failing registration and resource tests

**Files:**
- Modify: `src/test/kotlin/com/github/izerui/imux/PluginXmlRegistrationTest.kt`
- Modify: `src/test/kotlin/com/github/izerui/imux/IconResourceTest.kt`

- [ ] **Step 1: Add a failing `fileIconProvider` registration test**

Add this test to `PluginXmlRegistrationTest`:

```kotlin
@Test
fun `注册了终端标签图标提供器`() {
    assertTrue(
        "plugin.xml 未注册 AgentTerminalFileIconProvider，终端标签页不会显示 Agent 图标",
        pluginXml.contains("com.github.izerui.imux.terminal.AgentTerminalFileIconProvider"),
    )
    assertTrue("注册应使用 fileIconProvider 扩展点", pluginXml.contains("<fileIconProvider"))
}
```

- [ ] **Step 2: Add failing PNG resource checks**

Add the following imports and helpers to `IconResourceTest`:

```kotlin
import org.junit.Assert.assertNotEquals
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

private fun rasterIcon(name: String): BufferedImage {
    val file = File("src/main/resources/icons/$name")
    assertTrue("缺少图标资源：${file.absolutePath}", file.exists())
    return ImageIO.read(file)
}
```

Add these tests:

```kotlin
@Test
fun `Agent 标签图标提供标准与高分辨率资源`() {
    listOf("codex.png", "codex_dark.png", "claude.png").forEach { name ->
        val image = rasterIcon(name)
        assertEquals("$name 宽度错误", 16, image.width)
        assertEquals("$name 高度错误", 16, image.height)
    }
    listOf("codex@2x.png", "codex@2x_dark.png", "claude@2x.png").forEach { name ->
        val image = rasterIcon(name)
        assertEquals("$name 宽度错误", 32, image.width)
        assertEquals("$name 高度错误", 32, image.height)
    }
}

@Test
fun `Codex 图标透明且按主题切换线条颜色`() {
    val light = rasterIcon("codex.png")
    val dark = rasterIcon("codex_dark.png")
    assertEquals("Codex 图标四角必须透明", 0, light.getRGB(0, 0).ushr(24))
    assertEquals("深色 Codex 图标四角必须透明", 0, dark.getRGB(0, 0).ushr(24))
    assertNotEquals("浅色与深色 Codex 图标不能完全相同", light.getRGB(8, 2), dark.getRGB(8, 2))
}

@Test
fun `Claude 图标保留透明圆角`() {
    val image = rasterIcon("claude.png")
    assertEquals("Claude 图标四角必须透明", 0, image.getRGB(0, 0).ushr(24))
}
```

- [ ] **Step 3: Run the targeted tests and verify they fail**

Run:

```bash
./gradlew test --tests com.github.izerui.imux.PluginXmlRegistrationTest \
  --tests com.github.izerui.imux.IconResourceTest
```

Expected: tests fail because the provider registration and six Agent icon resources do not exist.

### Task 2: Generate theme-aware icon resources

**Files:**
- Create: `src/main/resources/icons/codex.png`
- Create: `src/main/resources/icons/codex_dark.png`
- Create: `src/main/resources/icons/codex@2x.png`
- Create: `src/main/resources/icons/codex@2x_dark.png`
- Create: `src/main/resources/icons/claude.png`
- Create: `src/main/resources/icons/claude@2x.png`

- [ ] **Step 1: Generate adapted PNG resources from the supplied images**

Run this script from the project root:

```python
from pathlib import Path
from PIL import Image, ImageChops, ImageOps

root = Path("src/main/resources/icons")
codex_source = Image.open("/Users/liuyuhua/Downloads/faviconV2 (1).png").convert("L")
claude_source = Image.open("/Users/liuyuhua/Downloads/claude-ai-icon.png").convert("RGBA")

alpha = ImageOps.invert(codex_source)
bbox = alpha.getbbox()
if bbox is None:
    raise RuntimeError("OpenAI source image contains no visible mark")
alpha = alpha.crop(bbox)

for size, suffix in ((16, ""), (32, "@2x")):
    mark_size = size - (2 if size == 16 else 4)
    mark_alpha = ImageOps.contain(alpha, (mark_size, mark_size), Image.Resampling.LANCZOS)
    offset = ((size - mark_alpha.width) // 2, (size - mark_alpha.height) // 2)
    for dark, color in ((False, 0), (True, 255)):
        canvas = Image.new("RGBA", (size, size), (color, color, color, 0))
        mark = Image.new("RGBA", mark_alpha.size, (color, color, color, 255))
        canvas.alpha_composite(mark, offset)
        placed_alpha = Image.new("L", (size, size), 0)
        placed_alpha.paste(mark_alpha, offset)
        canvas.putalpha(placed_alpha)
        theme = "_dark" if dark else ""
        canvas.save(root / f"codex{suffix}{theme}.png", optimize=True)

    claude = ImageOps.contain(claude_source, (size, size), Image.Resampling.LANCZOS)
    claude_canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    claude_canvas.alpha_composite(
        claude,
        ((size - claude.width) // 2, (size - claude.height) // 2),
    )
    claude_canvas.save(root / f"claude{suffix}.png", optimize=True)
```

- [ ] **Step 2: Run resource tests**

Run:

```bash
./gradlew test --tests com.github.izerui.imux.IconResourceTest
```

Expected: all icon resource tests pass.

- [ ] **Step 3: Commit resource work**

```bash
git add src/main/resources/icons src/test/kotlin/com/github/izerui/imux/IconResourceTest.kt
git commit -m "feat: 添加 Agent 标签页图标资源"
```

### Task 3: Implement and register the official file icon provider

**Files:**
- Create: `src/main/kotlin/com/github/izerui/imux/terminal/AgentTerminalFileIconProvider.kt`
- Create: `src/test/kotlin/com/github/izerui/imux/terminal/AgentTerminalFileIconProviderTest.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`
- Modify: `src/test/kotlin/com/github/izerui/imux/PluginXmlRegistrationTest.kt`

- [ ] **Step 1: Write the failing provider behavior test**

Create `AgentTerminalFileIconProviderTest.kt`:

```kotlin
package com.github.izerui.imux.terminal

import com.github.izerui.imux.model.AgentType
import com.intellij.testFramework.LightVirtualFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Test

class AgentTerminalFileIconProviderTest {

    @Test
    fun `Claude 与 Codex 使用不同的 16 像素图标`() {
        val claude = AgentTerminalIcons.forAgent(AgentType.CLAUDE)
        val codex = AgentTerminalIcons.forAgent(AgentType.CODEX)

        assertNotSame(claude, codex)
        assertEquals(16, claude.iconWidth)
        assertEquals(16, claude.iconHeight)
        assertEquals(16, codex.iconWidth)
        assertEquals(16, codex.iconHeight)
    }

    @Test
    fun `忽略非 imux 虚拟文件`() {
        val icon = AgentTerminalFileIconProvider()
            .getIcon(LightVirtualFile("notes.txt"), 0, null)

        assertNull(icon)
    }
}
```

- [ ] **Step 2: Run the provider test and verify it fails**

Run:

```bash
./gradlew test --tests com.github.izerui.imux.terminal.AgentTerminalFileIconProviderTest
```

Expected: compilation fails because `AgentTerminalIcons` and `AgentTerminalFileIconProvider` do not exist.

- [ ] **Step 3: Implement icon loading and file mapping**

Create `AgentTerminalFileIconProvider.kt`:

```kotlin
package com.github.izerui.imux.terminal

import com.github.izerui.imux.model.AgentType
import com.intellij.ide.FileIconProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.VirtualFile
import javax.swing.Icon

class AgentTerminalFileIconProvider : FileIconProvider {

    override fun getIcon(file: VirtualFile, flags: Int, project: Project?): Icon? {
        val terminalFile = file as? AgentTerminalVirtualFile ?: return null
        return AgentTerminalIcons.forAgent(terminalFile.agentType)
    }
}

internal object AgentTerminalIcons {
    private val claude = IconLoader.getIcon("/icons/claude.png", javaClass)
    private val codex = IconLoader.getIcon("/icons/codex.png", javaClass)

    fun forAgent(agentType: AgentType): Icon = when (agentType) {
        AgentType.CLAUDE -> claude
        AgentType.CODEX -> codex
    }
}
```

- [ ] **Step 4: Register the extension**

Add this entry after `editorTabTitleProvider` in `plugin.xml`:

```xml
<fileIconProvider
    implementation="com.github.izerui.imux.terminal.AgentTerminalFileIconProvider"/>
```

- [ ] **Step 5: Run targeted provider and registration tests**

Run:

```bash
./gradlew test \
  --tests com.github.izerui.imux.terminal.AgentTerminalFileIconProviderTest \
  --tests com.github.izerui.imux.PluginXmlRegistrationTest
```

Expected: all selected tests pass.

- [ ] **Step 6: Commit provider integration**

```bash
git add \
  src/main/kotlin/com/github/izerui/imux/terminal/AgentTerminalFileIconProvider.kt \
  src/main/resources/META-INF/plugin.xml \
  src/test/kotlin/com/github/izerui/imux/PluginXmlRegistrationTest.kt \
  src/test/kotlin/com/github/izerui/imux/terminal/AgentTerminalFileIconProviderTest.kt
git commit -m "feat: 为 Agent 终端标签页显示品牌图标"
```

### Task 4: Full verification

**Files:**
- Verify all changed files.

- [ ] **Step 1: Run the full test suite**

Run:

```bash
./gradlew test
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Build and validate plugin structure**

Run:

```bash
./gradlew buildPlugin verifyPluginStructure
```

Expected: `BUILD SUCCESSFUL`; the plugin ZIP contains the provider class and all six icon resources.

- [ ] **Step 3: Inspect the final diff and repository state**

Run:

```bash
git status --short
git diff HEAD~2 --check
git diff HEAD~2 --stat
```

Expected: no whitespace errors; only the intended icon resources, provider, registration, and tests are included. Preserve the pre-existing untracked `src/main/kotlin/com/github/.DS_Store`.

