package com.github.izerui.imux.bridge.robot

import com.github.izerui.imux.bridge.core.RobotId
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe

/** 机器人凭据库。实现方必须把内容存在系统凭据存储里，不能落到普通配置文件。 */
interface CredentialVault {
    fun load(robotId: RobotId): Map<String, String>?

    fun save(
        robotId: RobotId,
        values: Map<String, String>,
    )

    fun delete(robotId: RobotId)
}

/** 基于 IDE PasswordSafe（macOS 钥匙串 / Windows 凭据管理器 / KeePass）。可能阻塞，勿在 EDT 调用。 */
class PasswordSafeVault : CredentialVault {
    override fun load(robotId: RobotId): Map<String, String>? =
        PasswordSafe.instance.getPassword(attributes(robotId))?.let(::decodeSecret)

    override fun save(
        robotId: RobotId,
        values: Map<String, String>,
    ) {
        PasswordSafe.instance.setPassword(attributes(robotId), encodeSecret(values))
    }

    override fun delete(robotId: RobotId) {
        PasswordSafe.instance.setPassword(attributes(robotId), null)
    }

    private fun attributes(robotId: RobotId) = CredentialAttributes(generateServiceName("imux bridge", "robot:${robotId.value}"))
}

private val secretType = object : TypeToken<Map<String, String>>() {}.type

internal fun encodeSecret(values: Map<String, String>): String = Gson().toJson(values)

internal fun decodeSecret(text: String): Map<String, String>? =
    runCatching { Gson().fromJson<Map<String, String>>(text, secretType) }.getOrNull()
