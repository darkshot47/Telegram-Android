package com.telefarm.ui.contacts

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.telefarm.R
import com.telefarm.appGraph
import com.telefarm.data.model.UserUi
import com.telefarm.databinding.ActivityContactsBinding
import com.telefarm.databinding.ItemContactBinding
import com.telefarm.media.ThumbnailLoader
import com.telefarm.ui.common.Avatars
import com.telefarm.ui.common.TimeFormat
import com.telefarm.ui.profile.ProfileActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Contact list.
 *
 * Tapping a contact opens the profile, which is where the conversation starts, so no action is
 * offered twice. The list only ever shows contacts that Telegram actually returned.
 */
class ContactsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityContactsBinding
    private lateinit var adapter: ContactsAdapter

    private val viewModel: ContactsViewModel by viewModels {
        viewModelFactory { initializer { ContactsViewModel(appGraph) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityContactsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = ContactsAdapter(
            loader = appGraph.thumbnails,
            scope = lifecycleScope,
            onContactClick = { user -> startActivity(ProfileActivity.intent(this, user.id, user.displayName)) }
        )
        binding.contactList.layoutManager = LinearLayoutManager(this)
        binding.contactList.adapter = adapter

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.searchInput.doAfterTextChanged { editable ->
            viewModel.onQueryChanged(editable?.toString().orEmpty())
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }
    }

    private fun render(state: ContactsScreenState) {
        adapter.submitList(state.visibleContacts)
        binding.stateContainer.isVisible = state.isLoading || state.error != null || state.isEmpty
        binding.stateProgress.isVisible = state.isLoading
        binding.stateTitle.isVisible = state.error != null || state.isEmpty
        binding.stateMessage.isVisible = state.error == null && state.isEmpty

        when {
            state.error != null -> binding.stateTitle.setText(R.string.contacts_error)
            state.isEmpty -> binding.stateTitle.setText(R.string.contacts_empty_title)
        }
        if (state.error == null && state.isEmpty) {
            binding.stateMessage.setText(R.string.contacts_empty_message)
        } else {
            binding.stateMessage.text = null
        }
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, ContactsActivity::class.java)
    }
}

/** Contact rows. */
private class ContactsAdapter(
    private val loader: ThumbnailLoader,
    private val scope: CoroutineScope,
    private val onContactClick: (UserUi) -> Unit
) : ListAdapter<UserUi, ContactsAdapter.ContactViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContactViewHolder =
        ContactViewHolder(
            ItemContactBinding.inflate(LayoutInflater.from(parent.context), parent, false),
            loader,
            scope,
            onContactClick
        )

    override fun onBindViewHolder(holder: ContactViewHolder, position: Int) = holder.bind(getItem(position))

    override fun onViewRecycled(holder: ContactViewHolder) {
        holder.release()
        super.onViewRecycled(holder)
    }

    class ContactViewHolder(
        private val binding: ItemContactBinding,
        private val loader: ThumbnailLoader,
        private val scope: CoroutineScope,
        private val onClick: (UserUi) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        private var current: UserUi? = null

        init {
            binding.contactRow.setOnClickListener { current?.let(onClick) }
        }

        fun bind(user: UserUi) {
            current = user
            binding.contactName.text = user.displayName
            val context = itemView.context
            val status = if (user.isBot) {
                context.getString(R.string.contact_bot)
            } else {
                TimeFormat.status(context, user.status) ?: context.getString(R.string.contacts_status_offline)
            }
            binding.contactStatus.isVisible = status.isNotBlank()
            binding.contactStatus.text = status

            Avatars.bind(
                image = binding.contactPhoto,
                initialsView = binding.contactInitials,
                name = user.displayName,
                peerId = user.id,
                photo = user.photo,
                loader = loader,
                scope = scope,
                sizePx = itemView.resources.getDimensionPixelSize(R.dimen.avatar_small)
            )
            itemView.contentDescription = listOf(user.displayName, status).joinToString(", ")
        }

        fun release() {
            current = null
            Avatars.cancel(binding.contactPhoto)
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<UserUi>() {
            override fun areItemsTheSame(oldItem: UserUi, newItem: UserUi): Boolean = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: UserUi, newItem: UserUi): Boolean = oldItem == newItem
        }
    }
}
