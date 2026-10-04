package com.telefarm.core.td

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max

/**
 * Configuration of a TDLib client instance.
 *
 * Credentials are read from the build configuration at runtime; they are never written to
 * preferences, logs or crash reports.
 */
data class TdLibConfig(
    val databaseDirectory: String,
    val filesDirectory: String,
    val apiId: Int,
    val apiHash: String,
    val logLevel: Int,
    val logToLogcat: Boolean
)

/**
 * Thin, coroutine friendly wrapper around the native TDLib client.
 *
 * One native client belongs to one [TdLibClient]: it is created by [start], every update is
 * forwarded to [updates] and every request becomes a suspending call. TDLib types do not
 * leave the data layer.
 */
class TdLibClient(private val config: TdLibConfig) {

    private val _updates = MutableSharedFlow<TdApi.Update>(
        replay = 0,
        extraBufferCapacity = UPDATE_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** Live update stream. Collection starts before the first request is sent. */
    val updates: SharedFlow<TdApi.Update> = _updates.asSharedFlow()

    private val pending = ConcurrentHashMap<Long, PendingRequest>()
    private val nextRequestId = AtomicLong(0)

    @Volatile
    private var client: Client? = null

    @Volatile
    private var closed = false

    @Volatile
    private var lastError: Throwable? = null

    /** True when the native client could not be created, for example on an unsupported ABI. */
    val isUnavailable: Boolean get() = lastError != null

    /** Error that prevented the native library from loading, if any. */
    fun unavailableError(): Throwable? = lastError

    /** Creates the native client. Safe to call more than once; later calls are ignored. */
    fun start() {
        if (client != null || closed) return
        try {
            // TDLib runs all callbacks on its own threads; nothing here touches the UI.
            client = Client.create(
                Client.ResultHandler { update ->
                    if (update is TdApi.Update) {
                        _updates.tryEmit(update)
                    }
                },
                Client.ExceptionHandler { error ->
                    TelefarmLog.w(TAG, "TDLib update error: ${error.javaClass.simpleName}")
                },
                Client.ExceptionHandler { error ->
                    TelefarmLog.w(TAG, "TDLib internal error: ${error.javaClass.simpleName}")
                }
            )
            applyLogConfiguration()
        } catch (error: Throwable) {
            // UnsatisfiedLinkError and ExceptionInInitializerError are both possible here.
            lastError = error
            TelefarmLog.e(TAG, "TDLib native library could not be loaded")
        }
    }

    /**
     * Sends a request and suspends until TDLib answers.
     *
     * @throws TdLibException when TDLib reports an error or the client is not available.
     */
    suspend fun <T : TdApi.Object> send(function: TdApi.Function<T>): T {
        val nativeClient = client ?: throw (lastError?.let { TdLibException.NativeLibraryUnavailable(it) }
            ?: TdLibException.ClientNotStarted())

        return suspendCancellableCoroutine { continuation ->
            val requestId = nextRequestId.incrementAndGet()
            val request = PendingRequest(
                resultHandler = { result ->
                    pending.remove(requestId)
                    if (continuation.isActive) {
                        @Suppress("UNCHECKED_CAST")
                        continuation.resume(result as T)
                    }
                },
                errorHandler = { error ->
                    pending.remove(requestId)
                    if (continuation.isActive) {
                        continuation.resumeWithException(TdLibException.RequestFailed(function, error))
                    }
                }
            )
            pending[requestId] = request
            continuation.invokeOnCancellation { pending.remove(requestId) }

            try {
                nativeClient.send(function, request, request)
            } catch (error: Throwable) {
                pending.remove(requestId)
                if (continuation.isActive) {
                    continuation.resumeWithException(TdLibException.RequestFailed(function, error))
                }
            }
        }
    }

    /** Release builds keep TDLib silent: request dumps can contain private data. */
    private fun applyLogConfiguration() {
        execute(TdApi.SetLogVerbosityLevel().apply { newVerbosityLevel = max(0, config.logLevel) })
        if (!config.logToLogcat) {
            execute(TdApi.SetLogStream().apply { logStream = TdApi.LogStreamEmpty() })
        }
    }

    /** Executes a synchronous TDLib request; used for log configuration only. */
    private fun execute(function: TdApi.Function<*>) {
        try {
            Client.execute(function)
        } catch (error: Throwable) {
            TelefarmLog.w(TAG, "TDLib request could not be executed: ${function.javaClass.simpleName}")
        }
    }

    /** Closes the native client and releases its threads. */
    fun stop() {
        if (closed) return
        closed = true
        pending.clear()
        val nativeClient = client
        client = null
        runCatching { nativeClient?.close() }
    }

    private companion object {
        const val TAG = "TdLibClient"
        const val UPDATE_BUFFER = 1024
    }
}

/** One in-flight request; implements both TDLib callback interfaces. */
private class PendingRequest(
    private val resultHandler: (TdApi.Object) -> Unit,
    private val errorHandler: (Throwable) -> Unit
) : Client.ResultHandler, Client.ExceptionHandler {

    override fun onResult(result: TdApi.Object) = resultHandler.invoke(result)

    override fun onException(exception: Throwable) = errorHandler.invoke(exception)
}
