package com.github.izerui.imux.frame

import com.github.izerui.imux.settings.PluginLanguage
import org.junit.Assert.assertEquals
import org.junit.Test

class StatusTitleSuffixTest {
    @Test
    fun `两种状态都没有时原样返回`() {
        assertEquals(
            "imux",
            AgentFrameTitleBuilder.decorate("imux", unreadCount = 0, runningCount = 0),
        )
    }

    @Test
    fun `只有未读时只显示未读`() {
        assertEquals(
            "imux (1 unread)",
            AgentFrameTitleBuilder.decorate("imux", unreadCount = 1, runningCount = 0),
        )
    }

    @Test
    fun `只有运行中时只显示运行中`() {
        assertEquals(
            "imux (2 running)",
            AgentFrameTitleBuilder.decorate("imux", unreadCount = 0, runningCount = 2),
        )
    }

    /** 未读在前：它是「要你去看」的强提示，运行中是「还在跑、不用管」的弱信息。 */
    @Test
    fun `两种状态并存时未读在前`() {
        assertEquals(
            "imux (1 unread · 2 running)",
            AgentFrameTitleBuilder.decorate("imux", unreadCount = 1, runningCount = 2),
        )
    }

    /**
     * 括号里嵌着会变的数字，简单拼接会叠成「（1 个未读）（2 个未读）」。
     * 平台会反复重算标题，所以必须先剥离旧后缀再追加。
     */
    @Test
    fun `数量变化时后缀不叠加`() {
        val before = AgentFrameTitleBuilder.decorate("imux", unreadCount = 1, runningCount = 2)

        assertEquals(
            "imux (3 unread · 4 running)",
            AgentFrameTitleBuilder.decorate(before, unreadCount = 3, runningCount = 4),
        )
    }

    /** 同一状态下重复装饰必须收敛，否则标题会随重算次数越来越长。 */
    @Test
    fun `重复装饰同一状态结果不变`() {
        val once = AgentFrameTitleBuilder.decorate("imux", unreadCount = 1, runningCount = 2)

        assertEquals(
            once,
            AgentFrameTitleBuilder.decorate(once, unreadCount = 1, runningCount = 2),
        )
    }

    /** 都清零要能回到干净标题，不留残影。 */
    @Test
    fun `状态清零后括号被移除`() {
        val before = AgentFrameTitleBuilder.decorate("imux", unreadCount = 1, runningCount = 2)

        assertEquals(
            "imux",
            AgentFrameTitleBuilder.decorate(before, unreadCount = 0, runningCount = 0),
        )
    }

    @Test
    fun `简体中文状态使用插件语言而不是 IDE 语言`() {
        assertEquals(
            "imux (1 个未读 · 2 个运行中)",
            AgentFrameTitleBuilder.decorate(
                "imux",
                unreadCount = 1,
                runningCount = 2,
                PluginLanguage.SIMPLIFIED_CHINESE,
            ),
        )
    }

    @Test
    fun `切换语言时移除上一种语言的状态后缀`() {
        val chinese =
            AgentFrameTitleBuilder.decorate(
                "imux",
                unreadCount = 1,
                runningCount = 2,
                PluginLanguage.SIMPLIFIED_CHINESE,
            )

        assertEquals(
            "imux (3 unread)",
            AgentFrameTitleBuilder.decorate(
                chinese,
                unreadCount = 3,
                runningCount = 0,
                PluginLanguage.ENGLISH,
            ),
        )
    }

    /**
     * 剥离用的正则必须精确匹配自己生成的三种形态，不能宽泛到「结尾的任意全角括号」——
     * 项目名本身就可能带括号，那会被吃掉。
     */
    @Test
    fun `项目名自带的括号不被当成状态后缀剥掉`() {
        assertEquals(
            "我的项目（旧） (1 unread)",
            AgentFrameTitleBuilder.decorate("我的项目（旧）", unreadCount = 1, runningCount = 0),
        )
    }
}
