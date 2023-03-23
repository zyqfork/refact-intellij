package com.smallcloud.refact.io


import com.intellij.openapi.Disposable
import com.intellij.util.messages.Topic
import com.smallcloud.refact.UsageStats.Companion.addStatistic
import com.smallcloud.refact.account.inferenceLogin
import org.apache.http.HttpHost
import org.apache.http.HttpResponse
import org.apache.http.client.HttpClient
import org.apache.http.client.methods.HttpGet
import org.apache.http.client.methods.HttpPost
import org.apache.http.client.methods.HttpRequestBase
import org.apache.http.client.protocol.HttpClientContext
import org.apache.http.conn.routing.HttpRoute
import org.apache.http.conn.ssl.NoopHostnameVerifier
import org.apache.http.conn.ssl.SSLConnectionSocketFactory
import org.apache.http.conn.ssl.TrustSelfSignedStrategy
import org.apache.http.entity.StringEntity
import org.apache.http.impl.client.DefaultConnectionKeepAliveStrategy
import org.apache.http.impl.client.HttpClients
import org.apache.http.impl.execchain.RequestAbortedException
import org.apache.http.ssl.SSLContexts
import org.apache.http.util.EntityUtils
import java.net.SocketException
import java.net.URI
import java.nio.charset.Charset
import java.util.concurrent.CompletableFuture
import javax.net.ssl.SSLContext

interface ConnectionChangedNotifier {
    fun statusChanged(newStatus: ConnectionStatus) {}
    fun lastErrorMsgChanged(newMsg: String?) {}

    companion object {
        val TOPIC = Topic.create(
            "Connection Changed Notifier",
            ConnectionChangedNotifier::class.java
        )
    }
}

enum class ConnectionStatus {
    CONNECTED,
    PENDING,
    DISCONNECTED,
    ERROR
}

data class RequestJob(var future: CompletableFuture<*>, val request: HttpRequestBase?)

class Connection(uri: URI, isCustomUrl: Boolean = false) : Disposable {
    private val route: HttpRoute = HttpRoute(HttpHost(uri.host, uri.port))
    private var context: HttpClientContext = HttpClientContext.create()
//    private val connManager: PoolingHttpClientConnectionManager = PoolingHttpClientConnectionManager()
//    private val conn: HttpClientConnection = connManager.requestConnection(route, null).get(10, TimeUnit.SECONDS)

    var sslcontext: SSLContext = SSLContexts.custom().loadTrustMaterial(
        null,
        TrustSelfSignedStrategy()
    ).build()
    var sslsf: SSLConnectionSocketFactory =
        SSLConnectionSocketFactory(sslcontext, arrayOf("TLSv1"), null, NoopHostnameVerifier())

    init {
//        connManager.maxTotal = 5
//        connManager.defaultMaxPerRoute = 4
//        connManager.validateAfterInactivity = 5_000
//        connManager.connect(conn, route, 10000, context)
//        connManager.setMaxPerRoute(route, 5)
    }

    private val client: HttpClient = HttpClients.custom()
//        .setConnectionManager(connManager)
        .setKeepAliveStrategy(DefaultConnectionKeepAliveStrategy.INSTANCE).also {
            if (isCustomUrl) {
//                it.setSSLSocketFactory(sslsf)
                it.setSSLContext(SSLContexts.custom().loadTrustMaterial(null, TrustSelfSignedStrategy()).build())
//                it.setSSLHostnameVerifier(NoopHostnameVerifier())
            }
        }
        .build()

    fun get(
        uri: URI,
        headers: Map<String, String>? = null,
        requestProperties: Map<String, String>? = null,
        needVerify: Boolean = false,
        scope: String = ""
    ): RequestJob {
        val get = HttpGet(uri)
        return send(get, headers, requestProperties, needVerify = needVerify, scope = scope)
    }

    fun post(
        uri: URI,
        body: String? = null,
        headers: Map<String, String>? = null,
        requestProperties: Map<String, String>? = null,
        needVerify: Boolean = false,
        scope: String = ""
    ): RequestJob {
        val post = HttpPost(uri)
        post.entity = StringEntity(body, Charset.forName("UTF-16"))
        return send(post, headers, requestProperties, needVerify = needVerify, scope = scope)
    }

    private fun send(
        req: HttpRequestBase,
        headers: Map<String, String>? = null,
        requestProperties: Map<String, String>? = null,
        needVerify: Boolean = false,
        scope: String = ""
    ): RequestJob {
        headers?.forEach {
            req.addHeader(it.key, it.value)
        }

        requestProperties?.forEach {
            req.addHeader(it.key, it.value)
        }

        val future = CompletableFuture.supplyAsync {
            if (needVerify) inferenceLogin()
        }.thenApplyAsync {
            try {
                val response: HttpResponse = client.execute(req)
                val statusCode: Int = response.statusLine.statusCode
                val responseBody: String = EntityUtils.toString(response.entity)
                return@thenApplyAsync responseBody
            } catch (e: SocketException) {
                // request aborted, it's ok for small files
                throw e
            } catch (e: RequestAbortedException) {
                // request aborted, it's ok
                throw e
            } catch (e: Exception) {
                addStatistic(false, scope, req.uri.toString(), e.toString())
                throw e
            } finally {
                req.releaseConnection()
            }
        }
        return RequestJob(future, req)
    }

    override fun dispose() {
//        conn.close()
    }
}