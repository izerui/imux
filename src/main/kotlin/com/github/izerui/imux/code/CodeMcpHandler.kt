package com.github.izerui.imux.code

import com.github.izerui.imux.mcp.LocalMcpHandler

class CodeMcpHandler : LocalMcpHandler(CODE_MCP_PATH) {
    private val service get() = CodeMcpService.getInstance()
    override val scope get() = service.scope
    override fun receiver(token: String) = service.protocol(token)?.let { it::receive }
}
