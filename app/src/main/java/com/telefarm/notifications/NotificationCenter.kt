package com.telefarm.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.telefarm.R
import com.telefarm.core.prefs.AppPreferences
import com.telefarm.core.td.TdLibClient
import com.telefarm.data.ChatListRepository
import com.telefarm.data.UserRepository
import com.telefarm.data.map.TdMappers
import com.telefarm.data.map.userIdOrNull
import com.telefarm.data.model.MessagePreview
import com.telefarm.ui.common.PreviewText
import com.telefarm.ui.main.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

/**
 * In-application notifications for new messages.
 *
 * Notifications are generated while the application process is alive: Telefarm does not run a
 * background push service, so nothing is promised that cannot be delivered. A notification is
 * skipped when its chat is on screen, when the message was sent by the user and whenever the
 * user turned notifications off.
 */
class NotificationCenter(
    private val context: Context,
    private val td: TdLibClient,
    private val chats: ChatListRepository,
    private val users: UserRepository,
    private val preferences: AppPreferences,
    private val scope: CoroutineScope
) {

    private val openedChats = mutableSetOf<Long>()
    private val shown = mutableSetOf<Long>()

    fun start() {
        createChannel()
        scope.launch {
            td.updates.collect { update ->
                when (update) {
                    is TdApi.UpdateNewMessage -> {
                        val message = update.message
                        if (shouldNotify(message)) show(message)
                    }

                    is TdApi.UpdateDeleteMessages -> Unit

                    else -> Unit
                }
            }
        }
    }

    /** The conversation screen tells the centre which chat is visible. */
    fun onChatOpened(chatId: Long) {
        openedChats.add(chatId)
        dismiss(chatId)
    }

    fun onChatClosed(chatId: Long) {
        openedChats.remove(chatId)
    }

    /** Removes the notification of a chat after it was opened from the list. */
    fun dismiss(chatId: Long) {
        shown.remove(chatId)
        manager()?.cancel(notificationId(chatId))
    }

    fun dismissAll() {
        shown.clear()
        manager()?.cancelAll()
    }

    /** True when notifications can actually be posted. */
    fun hasPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun shouldNotify(message: TdApi.Message): Boolean {
        if (!preferences.notificationsEnabled) return false
        if (message.isOutgoing) return false
        if (message.chatId in openedChats) return false
        val chat = chats.chatOrNull(message.chatId) ?: return false
        val type = chat.type
        if (type is TdApi.ChatTypeSupergroup && type.isChannel) return false
        return true
    }

    private fun show(message: TdApi.Message) {
        if (!hasPermission()) return
        val chat = chats.chatOrNull(message.chatId) ?: return
        val title = chat.title.orEmpty()
        if (title.isEmpty()) return
        val content = preview(message)
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(EXTRA_CHAT_ID, message.chatId)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId(message.chatId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_message)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOnlyAlertOnce(true)
            .build()

        shown.add(message.chatId)
        runCatching { manager()?.notify(notificationId(message.chatId), notification) }
    }

    /** Short text of a message; media is described instead of faked. */
    private fun preview(message: TdApi.Message): CharSequence {
        val senderName = message.senderId.userIdOrNull()?.let(users::cachedDisplayName)
        val body = when (val content = message.content) {
            is TdApi.MessageText -> content.text?.text.orEmpty()
            else -> when (val preview = TdMappers.preview(message)) {
                is MessagePreview.Kind -> PreviewText.of(context, preview.kind)
                is MessagePreview.Text -> preview.text
            }
        }
        return listOfNotNull(senderName, body.takeIf { it.isNotBlank() }).joinToString(": ")
    }

    private fun createChannel() {
        val manager = manager() ?: return
        if (manager.getNotificationChannel(CHANNEL_MESSAGES) != null) return
        val channel = NotificationChannel(
            CHANNEL_MESSAGES,
            context.getString(R.string.notification_channel_messages),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.notification_channel_messages_description)
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
    }

    private fun manager(): NotificationManager? =
        context.getSystemService(NotificationManager::class.java)

    private fun notificationId(chatId: Long): Int = (chatId % Int.MAX_VALUE).toInt()

    companion object {

        const val CHANNEL_MESSAGES = "messages"

        /** Extra of [MainActivity] that opens a conversation directly. */
        const val EXTRA_CHAT_ID = "com.telefarm.extra.CHAT_ID"
    }
}
