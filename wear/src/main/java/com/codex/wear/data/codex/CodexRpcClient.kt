package com.codex.wear.data.codex

import android.util.Log
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.pow
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

data class CodexClientInfo(
    val name: String = "codex_watch",
    val title: String = "Codex for Wear OS",
    val version: String = "1.0",
)

data class CodexClientCapabilities(
    val experimentalApi: Boolean = false,
    val optOutNotificationMethods: Set<String> = emptySet(),
    val requestAttestation: Boolean = false,
    val mcpServerOpenaiFormElicitation: Boolean = false,
)

data class CodexServerInfo(
    val userAgent: String,
    val platformFamily: String,
    val platformOs: String,
    val codexHome: String,
)

sealed interface CodexConnectionState {
    data class Disconnected(val reason: String? = null) : CodexConnectionState

    data class Connecting(val attempt: Int) : CodexConnectionState

    data class Initializing(val connectionGeneration: Long) : CodexConnectionState

    data class Connected(
        val connectionGeneration: Long,
        val serverInfo: CodexServerInfo,
    ) : CodexConnectionState

    data class Reconnecting(
        val attempt: Int,
        val delayMs: Long,
        val reason: String? = null,
    ) : CodexConnectionState

    data object Closed : CodexConnectionState
}

data class CodexRpcNotification(
    val connectionGeneration: Long,
    val method: String,
    /** Usually a JSONObject, but kept open for forward-compatible protocol extensions. */
    val params: Any?,
    val rawMessage: JSONObject,
) {
    val objectParams: JSONObject?
        get() = params as? JSONObject
}

data class CodexRpcServerRequest(
    val handle: CodexServerRequestHandle,
    val method: String,
    /** Usually a JSONObject, but kept open for forward-compatible protocol extensions. */
    val params: Any?,
    val rawMessage: JSONObject,
) {
    val objectParams: JSONObject?
        get() = params as? JSONObject
}

data class CodexProtocolIssue(
    val message: String,
    val rawFrame: String? = null,
    val cause: Throwable? = null,
)

data class CodexReconnectPolicy(
    val initialDelayMs: Long = 1_000L,
    val maxDelayMs: Long = 30_000L,
    val multiplier: Double = 2.0,
    val jitterRatio: Double = 0.2,
) {
    init {
        require(initialDelayMs >= 0L)
        require(maxDelayMs >= initialDelayMs)
        require(multiplier >= 1.0)
        require(jitterRatio in 0.0..1.0)
    }

    internal fun delayForAttempt(attempt: Int): Long {
        val exponent = (attempt - 1).coerceAtLeast(0)
        val uncapped = initialDelayMs.toDouble() * multiplier.pow(exponent.toDouble())
        val capped = uncapped.coerceAtMost(maxDelayMs.toDouble())
        val jitter = ((Random.nextDouble() * 2.0) - 1.0) * jitterRatio
        return (capped * (1.0 + jitter)).toLong().coerceAtLeast(0L)
    }
}

class CodexRpcException(
    val code: Int,
    override val message: String,
    val data: Any? = null,
    val method: String? = null,
) : Exception(message) {
    val isServerOverloaded: Boolean
        get() = code == SERVER_OVERLOADED_CODE

    private companion object {
        const val SERVER_OVERLOADED_CODE = -32001
    }
}

class CodexConnectionException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Long-lived, application-scoped JSON-RPC client for Codex app-server.
 *
 * Call [connect] before collecting server events. Public requests also call it defensively and
 * wait for the initialize/initialized handshake. The client reconnects transport failures, but it
 * deliberately does not retry RPC methods: callers must decide whether an operation is idempotent.
 */
class CodexRpcClient(
    serverUrl: String,
    private val authToken: String,
    parentScope: CoroutineScope,
    private val clientInfo: CodexClientInfo = CodexClientInfo(),
    private val capabilities: CodexClientCapabilities = CodexClientCapabilities(),
    private val reconnectPolicy: CodexReconnectPolicy = CodexReconnectPolicy(),
    private val requestTimeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS,
    httpClient: OkHttpClient? = null,
) : Closeable {

    private val normalizedServerUrl = normalizeWebSocketUrl(serverUrl)
    private val ownsHttpClient = httpClient == null
    private val httpClient = httpClient ?: buildWebSocketClient()
    private val scope =
        CoroutineScope(
            parentScope.coroutineContext +
                SupervisorJob(parentScope.coroutineContext[Job]) +
                CoroutineName("CodexRpcClient"),
        )

    private val desiredConnection = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val nextRequestId = AtomicLong(1L)
    private val nextConnectionGeneration = AtomicLong(1L)
    private val loopLock = Any()
    private val transportLock = Any()
    private val pendingRequests = ConcurrentHashMap<Long, PendingRequest>()

    @Volatile private var connectionLoopJob: Job? = null
    @Volatile private var activeTransport: ActiveTransport? = null

    private val _connectionState =
        MutableStateFlow<CodexConnectionState>(CodexConnectionState.Disconnected())
    val connectionState: StateFlow<CodexConnectionState> = _connectionState.asStateFlow()

    private val _notifications = MutableSharedFlow<CodexRpcNotification>(extraBufferCapacity = 128)
    val notifications: SharedFlow<CodexRpcNotification> = _notifications.asSharedFlow()

    private val _serverRequests = MutableSharedFlow<CodexRpcServerRequest>(extraBufferCapacity = 64)
    val serverRequests: SharedFlow<CodexRpcServerRequest> = _serverRequests.asSharedFlow()

    private val _protocolIssues = MutableSharedFlow<CodexProtocolIssue>(extraBufferCapacity = 32)
    val protocolIssues: SharedFlow<CodexProtocolIssue> = _protocolIssues.asSharedFlow()

    fun connect() {
        check(!closed.get()) { "CodexRpcClient is closed" }
        desiredConnection.set(true)
        synchronized(loopLock) {
            if (connectionLoopJob?.isActive == true) return
            connectionLoopJob = scope.launch { runConnectionLoop() }
        }
    }

    fun disconnect() {
        desiredConnection.set(false)
        synchronized(loopLock) {
            connectionLoopJob?.cancel()
            connectionLoopJob = null
        }
        val transport = synchronized(transportLock) {
            activeTransport.also { activeTransport = null }
        }
        transport?.socket?.close(NORMAL_CLOSE_CODE, "Client disconnected")
        failPendingRequests(
            generation = transport?.generation,
            error = CodexConnectionException("Codex connection closed by client"),
        )
        if (!closed.get()) _connectionState.value = CodexConnectionState.Disconnected()
    }

    /** Sends a JSON-RPC request and returns its unmodified result value. */
    suspend fun request(
        method: String,
        params: Any? = JSONObject(),
        timeoutMs: Long = requestTimeoutMs,
    ): Any? {
        require(method.isNotBlank()) { "method must not be blank" }
        require(timeoutMs > 0L) { "timeoutMs must be positive" }
        connect()
        val transport = awaitInitializedTransport(timeoutMs)
        return sendRequest(transport, method, params, timeoutMs)
    }

    suspend fun requestObject(
        method: String,
        params: Any? = JSONObject(),
        timeoutMs: Long = requestTimeoutMs,
    ): JSONObject =
        request(method, params, timeoutMs) as? JSONObject
            ?: throw CodexConnectionException("$method returned a non-object result")

    suspend fun sendNotification(
        method: String,
        params: JSONObject = JSONObject(),
        timeoutMs: Long = requestTimeoutMs,
    ) {
        require(method.isNotBlank()) { "method must not be blank" }
        connect()
        val transport = awaitInitializedTransport(timeoutMs)
        sendNotification(transport, method, params)
    }

    /** Responds to a server request only while its originating connection is still active. */
    suspend fun respond(
        handle: CodexServerRequestHandle,
        result: Any? = JSONObject(),
    ) {
        val transport = requireRequestTransport(handle)
        val message =
            JSONObject()
                .put("id", handle.requestId.toJsonValue())
                .put("result", result ?: JSONObject.NULL)
        sendFrame(transport, message, "server request response")
    }

    suspend fun respondError(
        handle: CodexServerRequestHandle,
        code: Int,
        message: String,
        data: Any? = null,
    ) {
        val transport = requireRequestTransport(handle)
        val error = JSONObject().put("code", code).put("message", message)
        if (data != null) error.put("data", data)
        val response =
            JSONObject()
                .put("id", handle.requestId.toJsonValue())
                .put("error", error)
        sendFrame(transport, response, "server request error response")
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        disconnect()
        _connectionState.value = CodexConnectionState.Closed
        scope.cancel()
        if (ownsHttpClient) {
            httpClient.connectionPool.evictAll()
            httpClient.dispatcher.executorService.shutdown()
        }
    }

    private suspend fun runConnectionLoop() {
        var reconnectAttempt = 0
        var finalReason: String? = null
        try {
            while (currentCoroutineContext().isActive && desiredConnection.get()) {
                _connectionState.value = CodexConnectionState.Connecting(reconnectAttempt + 1)
                val end = runConnectionSession()
                finalReason = end.error?.message
                if (!desiredConnection.get() || !currentCoroutineContext().isActive) break

                reconnectAttempt = if (end.wasInitialized) 1 else reconnectAttempt + 1
                val reconnectDelayMs = reconnectPolicy.delayForAttempt(reconnectAttempt)
                _connectionState.value =
                    CodexConnectionState.Reconnecting(
                        attempt = reconnectAttempt,
                        delayMs = reconnectDelayMs,
                        reason = finalReason,
                    )
                delay(reconnectDelayMs)
            }
        } finally {
            val completingJob = currentCoroutineContext()[Job]
            synchronized(loopLock) {
                if (connectionLoopJob === completingJob) {
                    connectionLoopJob = null
                }
            }
            if (!closed.get()) {
                _connectionState.value = CodexConnectionState.Disconnected(finalReason)
            }
        }
    }

    private suspend fun runConnectionSession(): SessionEnd {
        val generation = nextConnectionGeneration.getAndIncrement()
        val terminal = CompletableDeferred<Throwable?>()
        val initialized = AtomicBoolean(false)
        val listener =
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    synchronized(transportLock) {
                        activeTransport =
                            ActiveTransport(
                                generation = generation,
                                socket = webSocket,
                                initialized = false,
                            )
                    }
                    _connectionState.value = CodexConnectionState.Initializing(generation)
                    scope.launch {
                        try {
                            val transport =
                                ActiveTransport(
                                    generation = generation,
                                    socket = webSocket,
                                    initialized = false,
                                )
                            val result =
                                sendRequest(
                                    transport = transport,
                                    method = INITIALIZE_METHOD,
                                    params = buildInitializeParams(),
                                    timeoutMs = requestTimeoutMs,
                                )
                            val resultObject = result as? JSONObject ?: JSONObject()
                            sendNotification(transport, INITIALIZED_METHOD, JSONObject())
                            val readyTransport = transport.copy(initialized = true)
                            synchronized(transportLock) {
                                if (activeTransport?.generation != generation) {
                                    throw CodexConnectionException("Codex connection changed during initialization")
                                }
                                activeTransport = readyTransport
                            }
                            initialized.set(true)
                            _connectionState.value =
                                CodexConnectionState.Connected(
                                    connectionGeneration = generation,
                                    serverInfo = resultObject.toServerInfo(),
                                )
                        } catch (error: Throwable) {
                            terminal.complete(error)
                            webSocket.close(INTERNAL_ERROR_CLOSE_CODE, "Initialization failed")
                        }
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleIncomingFrame(generation, text)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    emitProtocolIssue("Ignoring binary app-server frame")
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    terminal.complete(
                        CodexConnectionException("Codex connection closed: $code ${reason.trim()}".trim()),
                    )
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    terminal.complete(CodexConnectionException("Codex WebSocket failed", t))
                }
            }

        val request =
            Request.Builder()
                .url(normalizedServerUrl)
                .apply {
                    if (authToken.isNotBlank()) {
                        header("Authorization", "Bearer ${authToken.trim()}")
                    }
                }
                .build()
        val sessionSocket = httpClient.newWebSocket(request, listener)
        var terminalError: Throwable? = null
        try {
            terminalError = terminal.await()
            return SessionEnd(wasInitialized = initialized.get(), error = terminalError)
        } finally {
            synchronized(transportLock) {
                if (activeTransport?.generation == generation) activeTransport = null
            }
            failPendingRequests(
                generation = generation,
                error = terminalError ?: CodexConnectionException("Codex connection ended"),
            )
            sessionSocket.cancel()
        }
    }

    private suspend fun awaitInitializedTransport(timeoutMs: Long): ActiveTransport =
        withTimeout(timeoutMs) {
            connectionState
                .map { state ->
                    val connected = state as? CodexConnectionState.Connected ?: return@map null
                    synchronized(transportLock) {
                        activeTransport?.takeIf {
                            it.initialized && it.generation == connected.connectionGeneration
                        }
                    }
                }
                .filterNotNull()
                .first()
        }

    private suspend fun requireRequestTransport(handle: CodexServerRequestHandle): ActiveTransport {
        val transport = synchronized(transportLock) { activeTransport }
        if (transport == null || !transport.initialized || transport.generation != handle.connectionGeneration) {
            throw CodexConnectionException(
                "The server request belongs to an expired Codex connection",
            )
        }
        return transport
    }

    private suspend fun sendRequest(
        transport: ActiveTransport,
        method: String,
        params: Any?,
        timeoutMs: Long,
    ): Any? {
        val id = nextRequestId.getAndIncrement()
        val deferred = CompletableDeferred<Any?>()
        pendingRequests[id] =
            PendingRequest(
                generation = transport.generation,
                method = method,
                deferred = deferred,
            )
        val request =
            JSONObject()
                .put("id", id)
                .put("method", method)
                .put("params", params ?: JSONObject.NULL)
        try {
            sendFrame(transport, request, method)
            return withTimeout(timeoutMs) { deferred.await() }
        } finally {
            pendingRequests.remove(id)
        }
    }

    private fun sendNotification(
        transport: ActiveTransport,
        method: String,
        params: JSONObject,
    ) {
        val notification =
            JSONObject()
                .put("method", method)
                .put("params", params)
        sendFrame(transport, notification, method)
    }

    private fun sendFrame(
        transport: ActiveTransport,
        message: JSONObject,
        description: String,
    ) {
        val activeGeneration = synchronized(transportLock) { activeTransport?.generation }
        if (activeGeneration != transport.generation) {
            throw CodexConnectionException("Cannot send $description on an expired connection")
        }
        if (!transport.socket.send(message.toString())) {
            throw CodexConnectionException("Codex rejected the $description frame")
        }
    }

    private fun handleIncomingFrame(connectionGeneration: Long, text: String) {
        val isCurrentConnection =
            synchronized(transportLock) { activeTransport?.generation == connectionGeneration }
        if (!isCurrentConnection) {
            emitProtocolIssue("Ignoring app-server frame from an expired connection")
            return
        }
        val message =
            try {
                JSONObject(text)
            } catch (error: JSONException) {
                emitProtocolIssue("Ignoring malformed app-server JSON", text, error)
                return
            }

        val method = message.optString("method").takeIf { it.isNotBlank() }
        val rpcId = message.opt("id").toRpcIdOrNull()
        when {
            method != null && rpcId != null -> {
                emitServerRequest(
                    CodexRpcServerRequest(
                        handle = CodexServerRequestHandle(connectionGeneration, rpcId),
                        method = method,
                        params = message.optionalValue("params"),
                        rawMessage = message,
                    ),
                )
            }

            rpcId != null -> handleRpcResponse(connectionGeneration, rpcId, message)

            method != null -> {
                emitNotification(
                    CodexRpcNotification(
                        connectionGeneration = connectionGeneration,
                        method = method,
                        params = message.optionalValue("params"),
                        rawMessage = message,
                    ),
                )
            }

            else -> emitProtocolIssue("Ignoring unrecognized app-server frame", text)
        }
    }

    private fun handleRpcResponse(
        connectionGeneration: Long,
        id: CodexRpcId,
        message: JSONObject,
    ) {
        val numericId = (id as? CodexRpcId.NumberValue)?.value
        if (numericId == null) {
            emitProtocolIssue("Ignoring response with an unknown string id", message.toString())
            return
        }
        val pending = pendingRequests[numericId]
        if (pending == null) {
            emitProtocolIssue("Ignoring response for unknown request $numericId", message.toString())
            return
        }
        if (pending.generation != connectionGeneration) {
            emitProtocolIssue("Ignoring response from an expired connection", message.toString())
            return
        }
        if (!pendingRequests.remove(numericId, pending)) return

        val error = message.optJSONObject("error")
        if (error != null) {
            pending.deferred.completeExceptionally(
                CodexRpcException(
                    code = error.optInt("code", UNKNOWN_RPC_ERROR_CODE),
                    message = error.optString("message", "${pending.method} failed"),
                    data = error.optionalValue("data"),
                    method = pending.method,
                ),
            )
        } else {
            pending.deferred.complete(message.optionalValue("result"))
        }
    }

    private fun failPendingRequests(generation: Long?, error: Throwable) {
        pendingRequests.entries.forEach { (id, pending) ->
            if (generation != null && pending.generation != generation) return@forEach
            if (pendingRequests.remove(id, pending)) {
                pending.deferred.completeExceptionally(error)
            }
        }
    }

    private fun emitNotification(notification: CodexRpcNotification) {
        if (!_notifications.tryEmit(notification)) {
            scope.launch { _notifications.emit(notification) }
        }
    }

    private fun emitServerRequest(request: CodexRpcServerRequest) {
        if (!_serverRequests.tryEmit(request)) {
            scope.launch { _serverRequests.emit(request) }
        }
    }

    private fun emitProtocolIssue(
        message: String,
        rawFrame: String? = null,
        cause: Throwable? = null,
    ) {
        Log.w(TAG, message, cause)
        val issue = CodexProtocolIssue(message, rawFrame, cause)
        if (!_protocolIssues.tryEmit(issue)) {
            scope.launch { _protocolIssues.emit(issue) }
        }
    }

    private fun buildInitializeParams(): JSONObject {
        val client =
            JSONObject()
                .put("name", clientInfo.name)
                .put("title", clientInfo.title)
                .put("version", clientInfo.version)
        val capabilityJson = JSONObject().put("experimentalApi", capabilities.experimentalApi)
        if (capabilities.optOutNotificationMethods.isNotEmpty()) {
            capabilityJson.put(
                "optOutNotificationMethods",
                JSONArray(capabilities.optOutNotificationMethods.toList()),
            )
        }
        if (capabilities.requestAttestation) {
            capabilityJson.put("requestAttestation", true)
        }
        if (capabilities.mcpServerOpenaiFormElicitation) {
            capabilityJson.put("mcpServerOpenaiFormElicitation", true)
        }
        return JSONObject().put("clientInfo", client).put("capabilities", capabilityJson)
    }

    private data class ActiveTransport(
        val generation: Long,
        val socket: WebSocket,
        val initialized: Boolean,
    )

    private data class PendingRequest(
        val generation: Long,
        val method: String,
        val deferred: CompletableDeferred<Any?>,
    )

    private data class SessionEnd(
        val wasInitialized: Boolean,
        val error: Throwable?,
    )

    private companion object {
        const val TAG = "CodexRpcClient"
        const val INITIALIZE_METHOD = "initialize"
        const val INITIALIZED_METHOD = "initialized"
        const val NORMAL_CLOSE_CODE = 1000
        const val INTERNAL_ERROR_CLOSE_CODE = 1011
        const val UNKNOWN_RPC_ERROR_CODE = -32_000
        const val DEFAULT_REQUEST_TIMEOUT_MS = 30_000L

        fun normalizeWebSocketUrl(url: String): String {
            val normalized =
                when {
                    url.trim().startsWith("https://") ->
                        "wss://${url.trim().removePrefix("https://")}"
                    url.trim().startsWith("http://") ->
                        "ws://${url.trim().removePrefix("http://")}"
                    else -> url.trim()
                }
            require(normalized.startsWith("ws://") || normalized.startsWith("wss://")) {
                "Codex app-server URL must use ws:// or wss://"
            }
            return normalized
        }

        fun buildWebSocketClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(20L, TimeUnit.SECONDS)
                .readTimeout(0L, TimeUnit.MILLISECONDS)
                .writeTimeout(30L, TimeUnit.SECONDS)
                .pingInterval(25L, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
    }
}

private fun Any?.toRpcIdOrNull(): CodexRpcId? =
    when (this) {
        is Byte, is Short, is Int, is Long ->
            CodexRpcId.NumberValue((this as Number).toLong())
        is String -> CodexRpcId.StringValue(this)
        else -> null
    }

private fun CodexRpcId.toJsonValue(): Any =
    when (this) {
        is CodexRpcId.NumberValue -> value
        is CodexRpcId.StringValue -> value
    }

private fun JSONObject.optionalValue(name: String): Any? =
    if (!has(name) || isNull(name)) null else opt(name)

private fun JSONObject.toServerInfo(): CodexServerInfo =
    CodexServerInfo(
        userAgent = optString("userAgent"),
        platformFamily = optString("platformFamily"),
        platformOs = optString("platformOs"),
        codexHome = optString("codexHome"),
    )
