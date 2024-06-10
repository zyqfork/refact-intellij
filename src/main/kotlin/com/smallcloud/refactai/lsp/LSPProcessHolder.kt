package com.smallcloud.refactai.lsp

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.FileUtil.getTempDirectory
import com.intellij.openapi.util.io.FileUtil.setExecutable
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.messages.MessageBus
import com.intellij.util.messages.Topic
import com.smallcloud.refactai.Resources
import com.smallcloud.refactai.Resources.binPrefix
import com.smallcloud.refactai.account.AccountManagerChangedNotifier
import com.smallcloud.refactai.io.InferenceGlobalContextChangedNotifier
import com.smallcloud.refactai.notifications.emitError
import com.smallcloud.refactai.panes.sharedchat.*
import org.apache.hc.core5.concurrent.ComplexFuture
import java.net.URI
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.io.path.Path
import com.smallcloud.refactai.account.AccountManager.Companion.instance as AccountManager
import com.smallcloud.refactai.io.InferenceGlobalContext.Companion.instance as InferenceGlobalContext


private fun getExeSuffix(): String {
    if (SystemInfo.isWindows) return ".exe"
    return ""
}

interface LSPProcessHolderChangedNotifier {
    fun capabilitiesChanged(newCaps: LSPCapabilities) {}
    companion object {
        val TOPIC = Topic.create(
            "Connection Changed Notifier",
            LSPProcessHolderChangedNotifier::class.java
        )
    }
}

class LSPProcessHolder(val project: Project): Disposable {
    private var process: Process? = null
    private var lastConfig: LSPConfig? = null
    private val logger = Logger.getInstance("LSPProcessHolder")
    private val loggerScheduler = AppExecutorUtil.createBoundedScheduledExecutorService(
            "SMCLSPLoggerScheduler", 1
    )
    private var loggerTask: Future<*>? = null
    private val schedulerCaps = AppExecutorUtil.createBoundedScheduledExecutorService(
        "SMCLSPCapsRequesterScheduler", 1
    )
    private var capsTask: Future<*>? = null
    private val messageBus: MessageBus = ApplicationManager.getApplication().messageBus
    private var isWorking = false
    private val healthCheckerScheduler = AppExecutorUtil.createBoundedScheduledExecutorService(
        "SMCLSHealthCheckerScheduler", 1
    )

    init {
        messageBus
                .connect(this)
                .subscribe(AccountManagerChangedNotifier.TOPIC, object : AccountManagerChangedNotifier {
                    override fun apiKeyChanged(newApiKey: String?) {
                        AppExecutorUtil.getAppScheduledExecutorService().submit {
                            settingsChanged()
                        }
                    }

                    override fun planStatusChanged(newPlan: String?) {
                        AppExecutorUtil.getAppScheduledExecutorService().submit {
                            settingsChanged()
                        }
                    }
                })
        messageBus
                .connect(this)
                .subscribe(InferenceGlobalContextChangedNotifier.TOPIC, object : InferenceGlobalContextChangedNotifier {
                    override fun userInferenceUriChanged(newUrl: String?) {
                        AppExecutorUtil.getAppScheduledExecutorService().submit {
                            settingsChanged()
                        }
                    }
                    override fun astFlagChanged(newValue: Boolean) {
                        AppExecutorUtil.getAppScheduledExecutorService().submit {
                            settingsChanged()
                        }
                    }
                    override fun astFileLimitChanged(newValue: Int) {
                        AppExecutorUtil.getAppScheduledExecutorService().submit {
                            settingsChanged()
                        }
                    }
                    override fun vecdbFlagChanged(newValue: Boolean) {
                        AppExecutorUtil.getAppScheduledExecutorService().submit {
                            settingsChanged()
                        }
                    }

                    override fun xDebugLSPPortChanged(newPort: Int?) {
                        AppExecutorUtil.getAppScheduledExecutorService().submit {
                            settingsChanged()
                        }
                    }
                })

        Companion::class.java.getResourceAsStream(
                "/bin/${binPrefix}/refact-lsp${getExeSuffix()}").use { input ->
            if (input == null) {
                emitError("LSP server is not found for host operating system, please contact support")
            } else {
                for (i in 0..4) {
                    try {
                        val path = Paths.get(BIN_PATH)
                        path.parent.toFile().mkdirs()
                        Files.copy(input, path, StandardCopyOption.REPLACE_EXISTING)
                        setExecutable(path.toFile())
                        break
                    } catch (e: Exception) {
                        logger.warn(e.message)
                    }
                }
            }
        }
        settingsChanged()

        healthCheckerScheduler.scheduleWithFixedDelay({
            if (lastConfig == null) return@scheduleWithFixedDelay
            if (InferenceGlobalContext.xDebugLSPPort != null) return@scheduleWithFixedDelay
            if (process?.isAlive == false) {
                startProcess()
            }
        }, 1, 1, TimeUnit.SECONDS)

        capsTask = schedulerCaps.scheduleWithFixedDelay({
            capabilities = getCaps()
            if (capabilities.cloudName.isNotEmpty()) {
                capsTask?.cancel(true)
                schedulerCaps.scheduleWithFixedDelay({
                    capabilities = getCaps()
                }, 15, 15, TimeUnit.MINUTES)
            }
        }, 0, 3, TimeUnit.SECONDS)
    }


    private fun settingsChanged() {
        synchronized(this) {
            terminate()
            if (InferenceGlobalContext.xDebugLSPPort != null) {
                lspProjectInitialize(this, project)
                return
            }
            startProcess()
        }
    }

    val lspIsWorking: Boolean
        get() = InferenceGlobalContext.xDebugLSPPort != null || isWorking

    var capabilities: LSPCapabilities = LSPCapabilities()
        set(newValue) {
            if (newValue == field) return
            field = newValue
            project
                .messageBus
                .syncPublisher(LSPProcessHolderChangedNotifier.TOPIC)
                .capabilitiesChanged(field)
        }

    private fun startProcess() {
        val address = if (InferenceGlobalContext.inferenceUri == null) "Refact" else
            InferenceGlobalContext.inferenceUri
        val newConfig = LSPConfig(
            address = address,
            apiKey = AccountManager.apiKey,
            port = 0,
            clientVersion = "${Resources.client}-${Resources.version}/${Resources.jbBuildVersion}",
            useTelemetry = true,
            deployment = InferenceGlobalContext.deploymentMode,
            ast = InferenceGlobalContext.astIsEnabled,
            astFileLimit = InferenceGlobalContext.astFileLimit,
            vecdb = InferenceGlobalContext.vecdbIsEnabled,
        )

        val processIsAlive = process?.isAlive == true

        if (newConfig == lastConfig && processIsAlive) return

        capabilities = LSPCapabilities()
        terminate()
        if (!newConfig.isValid) return
        var attempt = 0
        while (attempt < 5) {
            try {
                newConfig.port = (32000..32199).random()
                logger.warn("LSP start_process " + BIN_PATH + " " + newConfig.toArgs())
                process = GeneralCommandLine(listOf(BIN_PATH) + newConfig.toArgs())
                    .withRedirectErrorStream(true)
                    .createProcess()
                process!!.waitFor(5, TimeUnit.SECONDS)
                lastConfig = newConfig
                break
            } catch (e: Exception) {
                attempt++
                logger.warn("LSP start_process didn't start attempt=${attempt}")
                if (attempt == 5) {
                    throw e
                }
            }
        }
        loggerTask = loggerScheduler.submit {
            val reader = process!!.inputStream.bufferedReader()
            var line = reader.readLine()
            while (line != null) {
                logger.warn("\n$line")
                line = reader.readLine()
            }
        }
        process!!.onExit().thenAcceptAsync { process1 ->
            if (process1.exitValue() != 0) {
                logger.warn("LSP bad_things_happened " +
                    process1.inputStream.bufferedReader().use { it.readText() })
            }
        }
        attempt = 0
        while (attempt < 5) {
            try {
                InferenceGlobalContext.connection.ping(url)
                buildInfo = getBuildInfo()
                capabilities = getCaps()
                isWorking = true
                break
            } catch (e: Exception) {
                logger.warn("LSP bad_things_happened " + e.message)
            }
            attempt++
            Thread.sleep(3000)
        }
        lspProjectInitialize(this, project)
    }

    private fun safeTerminate() {
        InferenceGlobalContext.connection.get(URI(
            "http://127.0.0.1:${lastConfig!!.port}/v1/graceful-shutdown")).get().get()
    }

    private fun terminate() {
        process?.let {
            try {
                isWorking = false
                safeTerminate()
                if (it.waitFor(3, TimeUnit.SECONDS)) {
                    logger.info("LSP SIGTERM")
                    it.destroy()
                }
                process = null
            } catch (_: Exception) {}
        }
    }

    companion object {
        private val BIN_PATH = Path(getTempDirectory(),
            ApplicationInfo.getInstance().build.toString().replace(Regex("[^A-Za-z0-9 ]"), "_") +
            "_refact_lsp${getExeSuffix()}").toString()
        // here ?
        @JvmStatic
        fun getInstance(project: Project): LSPProcessHolder = project.service()

        var buildInfo: String = ""
    }

    override fun dispose() {
        terminate()
        loggerScheduler.shutdown()
        schedulerCaps.shutdown()
        healthCheckerScheduler.shutdown()
    }

    private fun getBuildInfo(): String {
        var res = ""
        InferenceGlobalContext.connection.get(url.resolve("/build_info"),
            dataReceiveEnded = {},
            errorDataReceived = {}).also {
            try {
                res = it.get().get() as String
                logger.debug("build_info request finished")
            } catch (e: Exception) {
                logger.debug("build_info ${e.message}")
            }
        }
        return res
    }

    val url: URI
        get() {
            val port = InferenceGlobalContext.xDebugLSPPort?: lastConfig?.port ?: return URI("")

            return URI("http://127.0.0.1:${port}/")
        }
    private fun getCaps(): LSPCapabilities {
        var res = LSPCapabilities()
        InferenceGlobalContext.connection.get(url.resolve("/v1/caps"),
                dataReceiveEnded = {},
                errorDataReceived = {}).also {
            var requestFuture: ComplexFuture<*>?
            try {
                requestFuture = it.get() as ComplexFuture
                val out = requestFuture.get()
                // logger.warn("LSP caps_received " + out)
                val gson = Gson()
                res = gson.fromJson(out as String, LSPCapabilities::class.java)
                logger.debug("caps_received request finished")
            } catch (e: Exception) {
                logger.debug("caps_received ${e.message}")
            }
            return res
        }
    }

    fun fetchCaps(): Future<LSPCapabilities> {
//        // This causes the ide to crash :/
//        if(this.capabilities.codeChatModels.isNotEmpty()) {
//            println("caps_cached")
//            return FutureTask { this.capabilities }
//        }

         val res = InferenceGlobalContext.connection.get(
            url.resolve("/v1/caps"),
            dataReceiveEnded = {},
            errorDataReceived = {}
        )

        return res.thenApply {
            val body = it.get() as String
            val caps = Gson().fromJson(body, LSPCapabilities::class.java)
            caps
        }
    }

    fun fetchSystemPrompts(): Future<SystemPromptMap> {
        val res = InferenceGlobalContext.connection.get(
            url.resolve("/v1/customization"),
            dataReceiveEnded = {},
            errorDataReceived = {}
        )
        val json = res.thenApply {
            val body = it.get() as String
            val prompts = Gson().fromJson<CustomPromptsResponse>(body, CustomPromptsResponse::class.java)
            prompts.systemPrompts
        }

        return json
    }

    fun fetchCommandCompletion(query: String, cursor: Int, count: Int = 5): Future<CommandCompletionResponse> {

        val requestBody = Gson().toJson(mapOf("query" to query, "cursor" to cursor, "top_n" to count))

        val res = InferenceGlobalContext.connection.post(
            url.resolve("/v1/at-command-completion"),
            requestBody,
        )
        // TODO: could this have a detail message?
        val json = res.thenApply {
            val body = it.get() as String
            // handle error
            // if(body.startsWith("detail"))
            Gson().fromJson<CommandCompletionResponse>(body, CommandCompletionResponse::class.java)
        }

        return json
    }

    fun fetchCommandPreview(query: String): Future<Events.AtCommands.Preview.Response> {
        val requestBody = Gson().toJson(mapOf("query" to query))

        val response = InferenceGlobalContext.connection.post(
            url.resolve("/v1/at-command-preview"),
            requestBody
        )

        val json: Future<Events.AtCommands.Preview.Response> = response.thenApply {
            val responseBody = it.get() as String
            if (responseBody.startsWith("detail")) {
                Events.AtCommands.Preview.Response(emptyArray())
            } else {
                Events.gson.fromJson(responseBody, Events.AtCommands.Preview.Response::class.java)
            }
        }

        return json
    }

    fun sendChat(
        id: String,
        messages: ChatMessages,
        model: String,
        onlyDeterministicMessages: Boolean = false,
        takeNote: Boolean = false,
        dataReceived: (String, String) -> Unit,
        dataReceiveEnded: (String) -> Unit,
        errorDataReceived: (JsonObject) -> Unit,
        failedDataReceiveEnded: (Throwable?) -> Unit,
    ): CompletableFuture<Future<*>> {

        val parameters = mapOf("max_new_tokens" to 1000)

        val tools = getTools(takeNote)

        val requestBody = Gson().toJson(mapOf(
            "messages" to messages.map {
                val content = if(it.content is String) { it.content } else { Gson().toJson(it.content) }
                mapOf("role" to it.role, "content" to content)
            },
            "model" to model,
            "parameters" to parameters,
            "stream" to true,
            "tools" to tools,
            "only_deterministic_messages" to onlyDeterministicMessages,
            "chat_id" to id,
        ))

        val headers = mapOf("Authorization" to "Bearer ${AccountManager.apiKey}")
        val request = InferenceGlobalContext.connection.post(
            url.resolve("/v1/chat"),
            requestBody,
            headers = headers,
            dataReceived = dataReceived,
            dataReceiveEnded = dataReceiveEnded,
            errorDataReceived = errorDataReceived,
            failedDataReceiveEnded = failedDataReceiveEnded,
            requestId = id,
        )

         return request

    }



    private fun getAvailableTools(): Future<Array<Tool>> {
        val headers = mapOf("Authorization" to "Bearer ${AccountManager.apiKey}")

        val request = InferenceGlobalContext.connection.get(
            url.resolve("/v1/at-tools-available"),
            headers=headers,
            dataReceiveEnded = {},
            errorDataReceived = {}
        )

        val json = request.thenApply {
            val res = it.get() as String
            Gson().fromJson(res, Array<Tool>::class.java)
        }

        return json

    }

    private fun getTools(takeNote: Boolean = false): List<Tool> {
        return getAvailableTools().get().filter { tool ->
            if(takeNote) {
                tool.function.name == "remember_how_to_use_tools"
            } else {
                tool.function.name != "remember_how_to_use_tools"
            }
        }
    }
}