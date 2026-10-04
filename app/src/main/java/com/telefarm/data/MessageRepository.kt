package com.telefarm.data

import com.telefarm.core.td.TdLibClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Creates and releases one [MessageStore] per open conversation.
 *
 * The store is dropped as soon as its screen is destroyed, which also stops its update
 * collector and frees the cached messages.
 */
class MessageRepository(
    private val td: TdLibClient,
    private val chats: ChatListRepository,
    private val users: UserRepository,
    private val session: TdSessionController,
    private val scope: CoroutineScope
) {

    private val stores = ConcurrentHashMap<Long, MessageStore>()

    fun store(chatId: Long): MessageStore = stores.getOrPut(chatId) {
        MessageStore(
            chatId = chatId,
            td = td,
            chats = chats,
            users = users,
            session = session,
            scope = scope
        ).also { store -> store.start() }
    }

    fun release(chatId: Long) {
        stores.remove(chatId)?.let { store -> scope.launch { store.close() } }
    }

    fun releaseAll() {
        stores.keys.toList().forEach(::release)
    }
}
