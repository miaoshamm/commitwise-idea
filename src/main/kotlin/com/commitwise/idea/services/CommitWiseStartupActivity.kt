package com.commitwise.idea.services

import com.commitwise.idea.infra.config.ProviderPresets
import com.commitwise.idea.ui.Notify
import com.commitwise.idea.ui.QuickSetupFlow
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity

/**
 * 首次使用引导：只提示一次。
 *
 * 判断依据是「有密钥 / 不需要密钥」，不弹第二次靠 onboarded 标记。
 */
class CommitWiseStartupActivity : StartupActivity.DumbAware {

    override fun runActivity(project: Project) {
        val settings = CommitWiseSettings.getInstance()
        if (settings.state.onboarded) return
        settings.state.onboarded = true

        val raw = settings.toRawConfig()
        val preset = ProviderPresets.of(raw.provider)
        val apiKey = Secrets.resolve(raw.provider, raw.apiKeyEnv)
        if (apiKey != null || !preset.requiresApiKey) {
            return
        }

        Notify.infoWith(
            project,
            "CommitWise 还没配置模型。可以用「快速配置」一次完成：选服务商 → 填密钥 → 获取模型列表。",
            "快速配置" to { QuickSetupFlow.run(project) },
        )
    }
}
