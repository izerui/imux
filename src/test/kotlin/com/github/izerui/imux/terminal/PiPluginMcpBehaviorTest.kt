package com.github.izerui.imux.terminal

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.TimeUnit

class PiPluginMcpBehaviorTest {
    @Test fun `实际扩展注册手机工具且重载后仍有同一凭证`() {
        val result = run("""
            extension(api);
            extension(api);
            console.log(JSON.stringify({registrations, remaining: process.env.IMUX_MOBILE_TOKEN, hooks}));
        """)
        val registrations = result.getAsJsonArray("registrations")
        assertEquals(2, registrations.size())
        assertEquals(registrations[0], registrations[1])
        val registration = registrations[0].asJsonObject
        assertEquals("imux_mobile", registration["name"].asString)
        val config = registration.getAsJsonObject("config")
        assertEquals(180, config["timeout"].asInt)
        assertEquals("direct", config["exposure"].asString)
        assertEquals("Bearer test-token", config.getAsJsonObject("headers")["Authorization"].asString)
        assertFalse(result.has("remaining"))
        assertEquals(0, result["hooks"].asInt)
    }

    @Test fun `注册出错时扩展提示降级而不中断执行`() {
        val result = run("""
            extension({...api, registerMcpServer(){throw new Error("failure")}});
            console.log(JSON.stringify({continued:true, registrations}));
        """)
        assertTrue(result["continued"].asBoolean)
        assertEquals(0, result.getAsJsonArray("registrations").size())
    }

    @Test fun `会话服务与手机服务各用自己的凭证注册且只设一个时只注册一个`() {
        val both = run("""
            process.env.IMUX_SESSION_URL = "http://127.0.0.1:63342/imux/session-mcp";
            process.env.IMUX_SESSION_TOKEN = "session-token";
            extension(api);
            console.log(JSON.stringify({registrations, remaining: process.env.IMUX_SESSION_TOKEN}));
        """)
        val registrations = both.getAsJsonArray("registrations").map { it.asJsonObject }
        assertEquals(listOf("imux_mobile", "imux_session"), registrations.map { it["name"].asString })
        assertEquals("Bearer session-token", registrations[1].getAsJsonObject("config").getAsJsonObject("headers")["Authorization"].asString)
        assertFalse(both.has("remaining"))

        val sessionOnly = run("""
            process.env.IMUX_SESSION_URL = "http://127.0.0.1:63342/imux/session-mcp";
            process.env.IMUX_SESSION_TOKEN = "session-token";
            extension(api);
            console.log(JSON.stringify({registrations}));
        """, mobile = false)
        assertEquals(listOf("imux_session"), sessionOnly.getAsJsonArray("registrations").map { it.asJsonObject["name"].asString })
    }

    private fun run(body: String, mobile: Boolean = true): com.google.gson.JsonObject {
        val module = Base64.getEncoder().encodeToString(Files.readAllBytes(Path.of("src/main/js/pi-imux-mcp.js")))
        val mobileEnv = if (mobile) {
            """
            process.env.IMUX_MOBILE_URL = "http://127.0.0.1:63342/imux/mobile-mcp";
            process.env.IMUX_MOBILE_TOKEN = "test-token";
            """
        } else ""
        val script = """
            import extension from "data:text/javascript;base64,$module";
            $mobileEnv
            const registrations = [];
            let hooks = 0;
            const api = {
              registerMcpServer(name,config){registrations.push({name,config})},
              on(){hooks++}
            };
            $body
        """.trimIndent()
        val process = ProcessBuilder("node", "--input-type=module", "-e", script).start()
        assertTrue("Node extension harness timed out", process.waitFor(10, TimeUnit.SECONDS))
        val output = process.inputStream.bufferedReader().readText()
        val error = process.errorStream.bufferedReader().readText()
        assertEquals(error, 0, process.exitValue())
        return JsonParser.parseString(output).asJsonObject
    }
}
