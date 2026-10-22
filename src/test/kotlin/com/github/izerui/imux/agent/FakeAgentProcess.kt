package com.github.izerui.imux.agent

import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * 内存里的假 CLI 进程：[script] 在自己的线程上读 stdin、写 stdout，模拟多轮协议。
 * 脚本返回即进程退出（退出码 [exitCode]）；[destroyForcibly] 让它立刻退出并关掉管道。
 */
internal class FakeAgentProcess(
    private val exitCode: Int = 0,
    private val script: FakeAgentProcess.(stdin: BufferedReader) -> Unit,
) : Process() {
    private val stdinOut = PipedOutputStream()
    private val stdinIn = PipedInputStream(stdinOut, 1 shl 20)
    private val stdoutIn = PipedInputStream(1 shl 20)
    private val stdoutOut = PipedOutputStream(stdoutIn)
    private val exited = CountDownLatch(1)

    /** 脚本读到的 stdin 行，按顺序。 */
    val received = CopyOnWriteArrayList<String>()

    @Volatile var destroyed = false
        private set

    init {
        thread(isDaemon = true, name = "fake-agent-process") {
            try {
                script(stdinIn.bufferedReader())
            } catch (_: Throwable) {
                // 被结束时管道断开
            } finally {
                runCatching { stdoutOut.close() }
                exited.countDown()
            }
        }
    }

    fun out(line: String) {
        stdoutOut.write((line + "\n").toByteArray())
        stdoutOut.flush()
    }

    /** 读一行 stdin 并记下；stdin 关闭时返回 null。 */
    fun BufferedReader.next(): String? = readLine()?.also { received += it }

    /** 一直等到被结束。 */
    fun hang() {
        exited.await()
    }

    override fun getOutputStream(): OutputStream = stdinOut

    override fun getInputStream(): InputStream = stdoutIn

    override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

    override fun waitFor(): Int {
        exited.await()
        return exitCode
    }

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = exited.await(timeout, unit)

    override fun exitValue(): Int {
        if (exited.count > 0) throw IllegalThreadStateException("running")
        return exitCode
    }

    override fun destroy() {
        destroyed = true
        runCatching { stdinIn.close() }
        runCatching { stdoutOut.close() }
        exited.countDown()
    }

    override fun destroyForcibly(): Process {
        destroy()
        return this
    }

    override fun isAlive(): Boolean = exited.count > 0
}
