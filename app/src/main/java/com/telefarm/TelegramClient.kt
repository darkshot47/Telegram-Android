package com.telefarm

import android.content.Context
import io.github.tdlibandroid.ktx.TdClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

class TelegramClient(context: Context) {

    private val tdlibDirectory =
        "${context.filesDir.absolutePath}/tdlib"

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO
    )

    val client = TdClient(
        filesDir = tdlibDirectory,
        verbosityLevel = 1,
        apiId = BuildConfig.TELEGRAM_API_ID,
        apiHash = BuildConfig.TELEGRAM_API_HASH
    )

    fun start() {
        client.init()
    }

    fun sendPhoneNumber(phoneNumber: String) {
        scope.launch {
            client.send(
                TdApi.SetAuthenticationPhoneNumber(phoneNumber, null)
            )
        }
    }

    fun sendCode(code: String) {
        scope.launch {
            client.send(
                TdApi.CheckAuthenticationCode(code)
            )
        }
    }

    fun sendPassword(password: String) {
        scope.launch {
            client.send(
                TdApi.CheckAuthenticationPassword(password)
            )
        }
    }

    fun getCurrentUser(callback: (TdApi.User?) -> Unit) {
        scope.launch {
            try {
                val user = client.send(TdApi.GetMe())
                callback(user as? TdApi.User)
            } catch (_: Exception) {
                callback(null)
            }
        }
    }

    fun close() {
        scope.cancel()
        client.close()
    }
}
