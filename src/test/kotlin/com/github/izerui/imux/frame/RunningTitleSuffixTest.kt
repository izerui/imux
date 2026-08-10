package com.github.izerui.imux.frame

import org.junit.Assert.assertEquals
import org.junit.Test

class RunningTitleSuffixTest {

    @Test
    fun `有运行中会话时项目名后加数量`() {
        assertEquals(
            "imux（2 个运行中）",
            AgentFrameTitleBuilder.decorate("imux", unread = false, runningCount = 2),
        )
    }

    /**
     * 后缀里嵌着会变的数字，简单拼接会得到「（2 个运行中）（3 个运行中）」。
     * 平台会反复重算标题，所以必须先剥离旧后缀再追加。
     */
    @Test
    fun `会话数变化时后缀不叠加`() {
        val two = AgentFrameTitleBuilder.decorate("imux", unread = false, runningCount = 2)

        assertEquals(
            "imux（3 个运行中）",
            AgentFrameTitleBuilder.decorate(two, unread = false, runningCount = 3),
        )
    }

    /** 同一状态下重复装饰必须收敛，否则标题会随重算次数越来越长。 */
    @Test
    fun `重复装饰同一状态结果不变`() {
        val once = AgentFrameTitleBuilder.decorate("imux", unread = true, runningCount = 2)

        assertEquals(
            once,
            AgentFrameTitleBuilder.decorate(once, unread = true, runningCount = 2),
        )
    }

    /** 跑完了要能回到干净标题，不能留下一个「（1 个运行中）」的残影。 */
    @Test
    fun `全部跑完后后缀被移除`() {
        val two = AgentFrameTitleBuilder.decorate("imux", unread = false, runningCount = 2)

        assertEquals(
            "imux",
            AgentFrameTitleBuilder.decorate(two, unread = false, runningCount = 0),
        )
    }

    @Test
    fun `无运行中会话时不出现括号`() {
        assertEquals(
            "imux",
            AgentFrameTitleBuilder.decorate("imux", unread = false, runningCount = 0),
        )
    }

    /** 未读前缀与运行中后缀各管各，同时出现时互不干扰。 */
    @Test
    fun `未读与运行中可同时呈现`() {
        assertEquals(
            "✳ imux（2 个运行中）",
            AgentFrameTitleBuilder.decorate("imux", unread = true, runningCount = 2),
        )
    }
}
