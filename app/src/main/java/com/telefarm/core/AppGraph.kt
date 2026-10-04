package com.telefarm.core

import android.content.Context
import com.telefarm.BuildConfig
import com.telefarm.core.prefs.AppPreferences
import com.telefarm.core.security.SecureStore
import com.telefarm.core.td.TdLibClient
import com.telefarm.core.td.TdLibConfig
import com.telefarm.core.td.TelefarmLog
import com.telefarm.data.ChatListRepository
import com.telefarm.data.MessageRepository
import com.telefarm.data.SearchRepository
import com.telefarm.data.SettingsRepository
import com.telefarm.data.TdSessionController
import com.telefarm.data.UserRepository
import com.telefarm.data.model.TelegramCredentials
import com.telefarm.media.FileManager
import com.telefarm.media.ThumbnailLoader
import com.telefarm.media.VoicePlayer
import com.telefarm.notifications.NotificationCenter
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File

/**
 * Object graph of the application.
 *
 * One instance exists per process. It wires the TDLib client, the session, the repositories and
 * the caches, and it is the only place where those singletons are constructed, which keeps the
 * dependency direction one way: user interface, repositories, TDLib.
 */
class AppGraph(context: Context) {

    private val appContext: Context = context.applicationContext

    private val exceptionHandler = CoroutineExceptionHandler { _, error ->
        // Nothing is rethrown: a failing background job must not kill the application.
        TelefarmLog.e(TAG, "Background job failed", error)
    }

    /** Scope for long lived work that must outlive any screen. */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + exceptionHandler)

    val secureStore = SecureStore(appContext)

    val preferences = AppPreferences(appContext)

    private val workspaceDirectory = File(appContext.filesDir, WORKSPACE_DIRECTORY)

    private val credentials = TelegramCredentials(
        apiId = BuildConfig.TELEGRAM_API_ID,
        apiHash = BuildConfig.TELEGRAM_API_HASH
    )

    private val tdLibClient = TdLibClient(
        TdLibConfig(
            databaseDirectory = File(workspaceDirectory, "db").absolutePath,
            filesDirectory = File(workspaceDirectory, "files").absolutePath,
            apiId = credentials.apiId,
            apiHash = credentials.apiHash,
            logLevel = if (BuildConfig.DEBUG) LOG_LEVEL_WARNING else LOG_LEVEL_FATAL,
            logToLogcat = BuildConfig.DEBUG
        )
    )

    val session = TdSessionController(
        client = tdLibClient,
        secureStore = secureStore,
        credentials = credentials,
        workspaceDirectory = workspaceDirectory,
        applicationVersion = BuildConfig.VERSION_NAME,
        scope = applicationScope
    )

    val users = UserRepository(tdLibClient, applicationScope) { session.selfUserId.value }

    val chats = ChatListRepository(tdLibClient, users, session, applicationScope)

    val messages = MessageRepository(tdLibClient, chats, users, session, applicationScope)

    val search = SearchRepository(tdLibClient, chats, users, session, applicationScope)

    val settings = SettingsRepository(tdLibClient, preferences, session, users)

    val fileManager = FileManager(tdLibClient, applicationScope)

    val thumbnails = ThumbnailLoader(appContext, fileManager, applicationScope)

    val voicePlayer = VoicePlayer(applicationScope)

    val notifications = NotificationCenter(
        context = appContext,
        td = tdLibClient,
        chats = chats,
        users = users,
        preferences = preferences,
        scope = applicationScope
    )

    private var started = false

    /** Starts TDLib and the background collectors. Called once from the application. */
    fun start() {
        if (started) return
        started = true
        settings.applyTheme()
        tdLibClient.start()
        if (tdLibClient.isUnavailable) {
            session.reportNativeFailure(
                tdLibClient.unavailableError() ?: IllegalStateException("TDLib unavailable")
            )
            return
        }
        users.start()
        chats.start()
        session.start()
        notifications.start()
    }

    /** Releases everything that holds native resources. */
    fun shutdown() {
        if (!started) return
        started = false
        messages.releaseAll()
        chats.close()
        voicePlayer.release()
        notifications.dismissAll()
        thumbnails.clear()
        tdLibClient.stop()
        applicationScope.cancel()
    }

    /** Frees caches when the system asks for memory. */
    fun onLowMemory() {
        thumbnails.clear()
    }

    private companion object {
        const val TAG = "AppGraph"
        const val LOG_LEVEL_FATAL = 0
        const val LOG_LEVEL_WARNING = 2
        const val WORKSPACE_DIRECTORY = "tdlib"
    }
}
