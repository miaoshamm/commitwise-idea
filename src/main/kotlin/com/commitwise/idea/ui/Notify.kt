package com.commitwise.idea.ui

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project

/** 通知封装：统一分组，避免每个调用点都拼一遍 */
object Notify {

    private const val GROUP = "CommitWise"

    private fun notify(project: Project?, type: NotificationType, content: String): Notification {
        val group = NotificationGroupManager.getInstance().getNotificationGroup(GROUP)
        val notification = group.createNotification(content, type)
        project?.let { notification.notify(it) } ?: notification.notify(null)
        return notification
    }

    fun info(project: Project?, content: String): Notification = notify(project, NotificationType.INFORMATION, content)

    fun warn(project: Project?, content: String): Notification = notify(project, NotificationType.WARNING, content)

    fun error(project: Project?, content: String): Notification = notify(project, NotificationType.ERROR, content)

    fun infoWith(project: Project?, content: String, vararg actions: Pair<String, () -> Unit>): Notification {
        val n = notify(project, NotificationType.INFORMATION, content)
        actions.forEach { (title, run) ->
            n.addAction(NotificationAction.createSimple(title) {
                n.expire()
                run()
            })
        }
        return n
    }

    fun errorWith(project: Project?, content: String, vararg actions: Pair<String, () -> Unit>): Notification {
        val n = notify(project, NotificationType.ERROR, content)
        actions.forEach { (title, run) ->
            n.addAction(NotificationAction.createSimple(title) {
                n.expire()
                run()
            })
        }
        return n
    }

    fun openSettings(project: Project, configurableId: String = "commitwise.settings") {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, configurableId)
    }
}
