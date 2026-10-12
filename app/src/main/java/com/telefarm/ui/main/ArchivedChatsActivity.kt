package com.telefarm.ui.main

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.telefarm.R
import com.telefarm.appGraph
import com.telefarm.core.list.ChatListEntry
import com.telefarm.data.ChatListRepository
import com.telefarm.data.model.ChatUi
import com.telefarm.databinding.ActivityArchivedChatsBinding
import com.telefarm.ui.chat.ChatActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * The chats that have been put away.
 *
 * Archiving a chat takes it off the main list, which is only safe if there is a way back. This is
 * that way: everything that has been put away is here, and each one can be brought back.
 *
 * A chat is either here or on the main list and never in both, so bringing one back is the same
 * call as putting one away - which list it is added to. There is no separate unarchive that could
 * drift out of step with it.
 */
class ArchivedChatsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityArchivedChatsBinding

    private val chats: ChatListRepository get() = appGraph.chats

    private var loaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityArchivedChatsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.stateAction.setOnClickListener { load() }

        binding.archivedList.layoutManager = LinearLayoutManager(this)
        binding.archivedList.adapter = ChatListAdapter(
            loader = appGraph.thumbnails,
            scope = lifecycleScope,
            onChatClick = { chat -> startActivity(ChatActivity.intent(this, chat.id)) },
            onChatLongClick = ::showActions
        )

        load()
    }

    private fun load() {
        loaded = false
        binding.stateContainer.isVisible = true
        binding.stateProgress.isVisible = true
        binding.stateAction.isVisible = false
        binding.stateText.setText(R.string.archived_loading)
        lifecycleScope.launch {
            val archived = try {
                chats.loadArchivedChats()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                binding.stateText.setText(R.string.archived_failed)
                binding.stateProgress.isVisible = false
                binding.stateAction.isVisible = true
                loaded = true
                return@launch
            }
            loaded = true
            (binding.archivedList.adapter as ChatListAdapter).submitList(
                archived.map { ChatListEntry.Item(it) }
            )
            binding.stateProgress.isVisible = false
            binding.stateContainer.isVisible = archived.isEmpty()
            if (archived.isEmpty()) binding.stateText.setText(R.string.archived_empty)
        }
    }

    /** Bringing a chat back is the only thing worth doing to one from here. */
    private fun showActions(chat: ChatUi) {
        MaterialAlertDialogBuilder(this)
            .setTitle(chat.title)
            .setItems(arrayOf(getString(R.string.archived_move_back))) { _, _ ->
                lifecycleScope.launch {
                    chats.setArchived(chat.id, archived = false)
                    // The chat is on the main list now, so it is no longer one of these.
                    if (loaded) load()
                }
            }
            .show()
    }
}
