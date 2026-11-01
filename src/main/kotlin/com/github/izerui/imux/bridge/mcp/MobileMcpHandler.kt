package com.github.izerui.imux.bridge.mcp

import com.github.izerui.imux.mcp.LocalMcpHandler

const val MOBILE_MCP_PATH = "/imux/mobile-mcp"

class MobileMcpHandler : LocalMcpHandler(MOBILE_MCP_PATH) {
    private val service get() = MobileMcpService.getInstance()
    override val scope get() = service.scope
    override fun receiver(token: String) = service.protocol(token)?.let { it::receive }
}
