package com.smallcloud.refact

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.ErrorReportSubmitter
import com.intellij.openapi.diagnostic.IdeaLoggingEvent
import com.intellij.openapi.diagnostic.SubmittedReportInfo
import com.intellij.util.Consumer
import com.smallcloud.refact.io.ConnectionStatus
import com.smallcloud.refact.io.InferenceGlobalContext
import java.awt.Component


class PluginErrorReportSubmitter : ErrorReportSubmitter(), Disposable {
    private val stats: UsageStats
        get() = ApplicationManager.getApplication().getService(UsageStats::class.java)

    override fun submit(
        events: Array<out IdeaLoggingEvent>,
        additionalInfo: String?,
        parentComponent: Component,
        consumer: Consumer<in SubmittedReportInfo>
    ): Boolean {
        for (event in events) {
            InferenceGlobalContext.status = ConnectionStatus.ERROR
            InferenceGlobalContext.lastErrorMsg = events.firstOrNull()?.message
            stats.addStatistic(
                false, "uncaught exceptions", "none",
                event.throwable.toString()
            )
        }

        return true
    }
    override fun getReportActionText(): String {
        return "Report error to plugin vendor"
    }

    override fun dispose() {}
}