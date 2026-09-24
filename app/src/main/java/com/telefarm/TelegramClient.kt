package com.telefarm

import android.content.Context
import io.github.tdlibandroid.ktx.TdClient
import org.drinkless.tdlib.TdApi

class TelegramClient(context: Context) {

    private val tdlibDirectory =
        context.filesDir.absolutePath + "/tdlib"

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
        client.send(
            TdApi.SetAuthenticationPhoneNumber(phoneNumber, null)
        )
    }

    fun sendCode(code: String) {
        client.send(
            TdApi.CheckAuthenticationCode(code)
        )
    }

    fun sendPassword(password: String) {
        client.send(
            TdApi.CheckAuthenticationPassword(password)
        )
    }

    fun getCurrentUser(
        callback: (TdApi.User?) -> Unit
    ) {
        kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.Dispatchers.IO
        ).launch {
            try {
                val user = client.send(TdApi.GetMe())
                callback(user as? TdApi.User)
            } catch (_: Exception) {
                callback(null)
            }
        }
    }

    fun close() {
        client.close()
    }
}
