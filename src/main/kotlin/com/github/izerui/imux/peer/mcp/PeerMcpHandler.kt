package com.github.izerui.imux.peer.mcp

import com.github.izerui.imux.mcp.LocalMcpHandler

const val SESSION_MCP_PATH = "/imux/session-mcp"

class PeerMcpHandler : LocalMcpHandler(SESSION_MCP_PATH) {
    private val service get() = PeerMcpService.getInstance()
    override val scope get() = service.scope
    override fun receiver(token: String) = service.protocol(token)?.let { it::receive }
}
