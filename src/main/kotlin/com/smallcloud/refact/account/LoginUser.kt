package com.smallcloud.refact.account

import com.google.gson.JsonObject
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.diagnostic.Logger
import com.smallcloud.refact.PluginState
import com.smallcloud.refact.Resources.defaultLoginUrl
import com.smallcloud.refact.Resources.defaultRecallUrl
import com.smallcloud.refact.Resources.developerModeLoginParameters
import com.smallcloud.refact.UsageStats.Companion.addStatistic
import com.smallcloud.refact.io.ConnectionStatus
import com.smallcloud.refact.io.InferenceGlobalContext
import com.smallcloud.refact.io.sendRequest
import com.smallcloud.refact.struct.LongthinkFunctionEntry
import com.smallcloud.refact.utils.makeGson
import org.apache.http.client.utils.URIBuilder
import java.net.URI
import com.smallcloud.refact.listeners.QuickLongthinkActionsService.Companion.instance as QuickLongthinkActionsServiceInstance
import com.smallcloud.refact.modes.diff.DiffIntentProvider.Companion.instance as DiffIntentProviderInstance
import com.smallcloud.refact.settings.ExtraState.Companion.instance as ExtraState

private fun generateTicket(): String {
    return (Math.random() * 1e16).toLong().toString(36) + "-" + (Math.random() * 1e16).toLong().toString(36)
}

private const val TICKET_PERIOD = 45 * 60 * 1000

fun login() {
    val isLoggedIn = AccountManager.isLoggedIn
    if (isLoggedIn) {
        return
    }

    val now = System.currentTimeMillis()
    if ((AccountManager.ticketCreatedTs == null || (now - AccountManager.ticketCreatedTs!!) > TICKET_PERIOD)) {
        AccountManager.ticket = generateTicket()
    }
    BrowserUtil.browse("https://codify.smallcloud.ai/authentication?token=${AccountManager.ticket}&utm_source=plugin&utm_medium=jetbrains&utm_campaign=login")

    runCounterTask()
}

fun logError(scope: String, msg: String, needChange: Boolean = true) {
    Logger.getInstance("check_login").warn("$scope: $msg")
    val conn = InferenceGlobalContext
    if (needChange) {
        conn.status = ConnectionStatus.ERROR
        conn.lastErrorMsg = msg
    }
}

fun checkLogin(force: Boolean = false): String {
    val acc = AccountManager
    val infC = InferenceGlobalContext
    val isLoggedIn = acc.isLoggedIn
    if (isLoggedIn && !force) {
        return ""
    }

    val streamlinedLoginTicket = acc.ticket
    var token = acc.apiKey
    val headers = mutableMapOf(
        "Content-Type" to "application/json",
        "Authorization" to ""
    )

    if (!streamlinedLoginTicket.isNullOrEmpty() && (token.isNullOrEmpty() || force)) {
        val recallUrl = defaultRecallUrl
        headers["Authorization"] = "codify-${streamlinedLoginTicket}"
        try {
            val result = sendRequest(
                recallUrl, "GET", headers, requestProperties = mapOf(
                    "redirect" to "follow",
                    "cache" to "no-cache",
                    "referrer" to "no-referrer"
                )
            )
            val gson = makeGson()
            val body = gson.fromJson(result.body, JsonObject::class.java)
            val retcode = body.get("retcode").asString
            val humanReadableMessage =
                if (body.has("human_readable_message")) body.get("human_readable_message").asString else ""
            if (retcode == "OK") {
                acc.apiKey = body.get("secret_key").asString
                acc.ticket = null
                infC.status = ConnectionStatus.CONNECTED
                addStatistic(true,  "recall", recallUrl.toString(), "")
            } else if (retcode == "FAILED" && humanReadableMessage.contains("rate limit")) {
                logError("recall", humanReadableMessage, false)
                return "OK"
            } else {
                result.body?.let {
                    addStatistic(false,  "recall (1)", recallUrl.toString(), it)
                    logError("recall", it)
                }
                return ""
            }

        } catch (e: Exception) {
            addStatistic(false,  "recall (2)", recallUrl.toString(), e)
            e.message?.let { logError("recall", it) }
            return ""
        }
    }

    token = acc.apiKey
    if (token.isNullOrEmpty()) {
        return ""
    }

    val urlBuilder = URIBuilder(defaultLoginUrl)
    if (InferenceGlobalContext.developerModeEnabled) {
        developerModeLoginParameters.forEach {
            urlBuilder.addParameter(it.key, it.value)
        }
    }
    val url = urlBuilder.build()

    headers["Authorization"] = "Bearer $token"
    try {
        val result = sendRequest(
            url, "GET", headers, requestProperties = mapOf(
                "redirect" to "follow",
                "cache" to "no-cache",
                "referrer" to "no-referrer"
            )
        )

        val gson = makeGson()
        val body = gson.fromJson(result.body, JsonObject::class.java)
        val retcode = body.get("retcode").asString
        val humanReadableMessage =
            if (body.has("human_readable_message")) body.get("human_readable_message").asString else ""
        if (retcode == "OK") {
            acc.user = body.get("account").asString
            acc.ticket = null
            if (body.get("inference_url") != null) {
                if (body.get("inference_url").asString != "DISABLED") {
                    infC.codifyInferenceUri = URI(body.get("inference_url").asString)
                }
            }

            acc.activePlan = body.get("inference").asString

            if (body.has("tooltip_message") && body.get("tooltip_message").asString.isNotEmpty()) {
                PluginState.instance.tooltipMessage = body.get("tooltip_message").asString
            }
            if (body.has("login_message") && body.get("login_message").asString.isNotEmpty()) {
                PluginState.instance.loginMessage = body.get("login_message").asString
            }

            if (body.has("longthink-functions-today-v2")) {
                val cloudEntries = body.get("longthink-functions-today-v2").asJsonObject.entrySet().map {
                    val elem = gson.fromJson(it.value, LongthinkFunctionEntry::class.java)
                    elem.entryName = it.key
                    return@map elem.mergeLocalInfo(ExtraState.getLocalLongthinkInfo(elem.entryName))
                }
                DiffIntentProviderInstance.defaultThirdPartyFunctions = cloudEntries
                QuickLongthinkActionsServiceInstance.recreateActions()
            }

            if (body.has("metering_balance")) {
                acc.meteringBalance = body.get("metering_balance").asInt
            }

            addStatistic(true,  "login", url.toString(), "")
            return inferenceLogin()
        } else if (retcode == "FAILED" && humanReadableMessage.contains("rate limitrate limit")) {
            logError("login-failed", humanReadableMessage, false)
            addStatistic(false,  "login-failed", url.toString(), humanReadableMessage)
            return "OK"
        } else if (retcode == "FAILED") {
            acc.user = null
            acc.activePlan = null
            logError("login-failed", humanReadableMessage)
            addStatistic(false,  "login-failed", url.toString(), humanReadableMessage)
            return ""
        } else {
            acc.user = null
            acc.activePlan = null
            logError("login-failed", "unrecognized response")
            addStatistic(false,  "login (2)", url.toString(), "unrecognized response")
            return ""
        }
    } catch (e: Exception) {
        e.message?.let { logError("login-fail", it) }
        addStatistic(false,  "login (3)", url.toString(), e)
        return ""
    }
}