package com.commitwise.idea.services

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe

/**
 * API 密钥存储：只走系统凭据库 / 环境变量，绝不落到配置文件里。
 */
object Secrets {

    private const val SERVICE = "CommitWise"

    private fun attributes(provider: String) =
        CredentialAttributes("$SERVICE/$provider", provider)

    fun get(provider: String): String? {
        val creds = PasswordSafe.instance.get(attributes(provider)) ?: return null
        val value = creds.getPasswordAsString()
        return if (value.isNullOrBlank()) null else value.trim()
    }

    fun set(provider: String, value: String) {
        PasswordSafe.instance.set(attributes(provider), Credentials(provider, value.trim()))
    }

    fun clear(provider: String) {
        PasswordSafe.instance.set(attributes(provider), null)
    }

    /** 先取凭据库，再退回环境变量 */
    fun resolve(provider: String, envName: String): String? {
        get(provider)?.let { return it }
        if (envName.isNotEmpty()) {
            val fromEnv = System.getenv(envName)
            if (!fromEnv.isNullOrBlank()) return fromEnv.trim()
        }
        return null
    }

    fun has(provider: String): Boolean = get(provider) != null
}
