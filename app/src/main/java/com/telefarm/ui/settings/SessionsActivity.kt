package com.telefarm.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.telefarm.R
import com.telefarm.appGraph
import com.telefarm.core.list.DateBucket
import com.telefarm.core.security.SessionList
import com.telefarm.core.time.CivilDate
import com.telefarm.data.SessionsRepository
import com.telefarm.data.SessionsState
import com.telefarm.data.TerminateAllResult
import com.telefarm.data.model.ActiveSessionUi
import com.telefarm.databinding.ActivitySessionsBinding
import com.telefarm.databinding.ItemSessionBinding
import com.telefarm.ui.common.showSnackbar
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * The devices signed in to this account.
 *
 * This is the screen an account is protected from, so it does two things the rest of the app does
 * not: it names the place every session was last used from, and it refuses to sign this device
 * out. Signing yourself out of the screen that lists your sessions is how an account gets lost,
 * and the logout that is meant for this device already has its own door with its own warning.
 */
class SessionsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySessionsBinding
    private lateinit var adapter: SessionAdapter

    private val sessions: SessionsRepository get() = appGraph.sessions

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySessionsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = SessionAdapter(::showSessionActions)
        binding.sessionList.layoutManager = LinearLayoutManager(this)
        binding.sessionList.adapter = adapter
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_terminate_all) {
                confirmTerminateAll()
                true
            } else {
                false
            }
        }
        binding.stateAction.setOnClickListener { reload() }

        observe()
        reload()
    }

    private fun reload() {
        lifecycleScope.launch { sessions.load() }
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { sessions.state.collect(::render) }
                launch { sessions.isWorking.collect { binding.stateProgress.isVisible = it } }
            }
        }
    }

    private fun render(state: SessionsState) {
        when (state) {
            is SessionsState.Loading -> {
                adapter.submitList(emptyList())
                binding.stateContainer.isVisible = true
                binding.stateProgress.isVisible = true
                binding.stateText.setText(R.string.sessions_loading)
                binding.stateAction.isVisible = false
            }
            is SessionsState.Content -> {
                adapter.submitList(state.sessions)
                binding.stateContainer.isVisible = state.sessions.isEmpty()
                binding.stateProgress.isVisible = false
                binding.stateText.setText(R.string.sessions_empty)
                binding.stateAction.isVisible = false
            }
            is SessionsState.Failed -> {
                adapter.submitList(emptyList())
                binding.stateContainer.isVisible = true
                binding.stateProgress.isVisible = false
                binding.stateText.text = state.message ?: getString(R.string.sessions_error)
                binding.stateAction.isVisible = true
            }
        }
    }

    /** One row: what to do with this device. */
    private fun showSessionActions(session: ActiveSessionUi) {
        if (!SessionList.canTerminate(session)) {
            binding.root.showSnackbar(getString(R.string.sessions_cannot_terminate_self))
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(SessionList.title(session))
            .setMessage(SessionList.subtitle(session))
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.sessions_terminate) { _, _ -> terminate(session) }
            .show()
    }

    private fun terminate(session: ActiveSessionUi) {
        lifecycleScope.launch {
            val done = sessions.terminate(session.id)
            binding.root.showSnackbar(
                getString(if (done) R.string.sessions_terminated else R.string.sessions_terminate_failed)
            )
        }
    }

    private fun confirmTerminateAll() {
        val current = sessions.state.value
        if (current !is SessionsState.Content || !SessionList.hasOtherSessions(current.sessions)) {
            binding.root.showSnackbar(getString(R.string.sessions_no_others))
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sessions_terminate_all)
            .setMessage(R.string.sessions_terminate_all_message)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.sessions_terminate_all_confirm) { _, _ -> terminateAll() }
            .show()
    }

    private fun terminateAll() {
        lifecycleScope.launch {
            val message = when (val result = sessions.terminateAllOthers()) {
                is TerminateAllResult.NothingToDo -> getString(R.string.sessions_no_others)
                is TerminateAllResult.Done -> resources.getQuantityString(
                    R.plurals.sessions_terminated_count,
                    result.count,
                    result.count
                )
                is TerminateAllResult.StoppedAt ->
                    getString(R.string.sessions_terminate_all_stopped, result.title)
            }
            binding.root.showSnackbar(message)
        }
    }

    /** One device in the list. */
    private class SessionAdapter(
        private val onClick: (ActiveSessionUi) -> Unit
    ) : ListAdapter<ActiveSessionUi, SessionAdapter.Holder>(DIFF) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
            ItemSessionBinding.inflate(LayoutInflater.from(parent.context), parent, false),
            onClick
        )

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

        class Holder(
            private val binding: ItemSessionBinding,
            private val onClick: (ActiveSessionUi) -> Unit
        ) : RecyclerView.ViewHolder(binding.root) {

            fun bind(session: ActiveSessionUi) {
                binding.sessionTitle.text = SessionList.title(session)
                binding.sessionDevice.text = SessionList.subtitle(session)

                val place = SessionList.place(session)
                binding.sessionPlace.isVisible = place.isNotEmpty()
                binding.sessionPlace.text = place

                binding.sessionActive.setText(activityLabel(session))
                binding.sessionCurrent.isVisible = session.isCurrent
                binding.root.setOnClickListener { onClick(session) }
                binding.root.contentDescription = listOf(
                    SessionList.title(session),
                    SessionList.subtitle(session),
                    place
                ).filter { it.isNotEmpty() }.joinToString(", ")
            }

            /** How long ago this device was used, in the words of the chat list's own sections. */
            private fun activityLabel(session: ActiveSessionUi): Int = when (
                SessionList.activity(session, todayEpochDay())
            ) {
                DateBucket.TODAY -> R.string.sessions_active_today
                DateBucket.YESTERDAY -> R.string.sessions_active_yesterday
                DateBucket.THIS_WEEK -> R.string.sessions_active_this_week
                DateBucket.THIS_MONTH -> R.string.sessions_active_this_month
                DateBucket.EARLIER -> R.string.sessions_active_earlier
            }

            private fun todayEpochDay(): Long {
                val now = Calendar.getInstance()
                return CivilDate.epochDayOf(
                    now.timeInMillis / MILLIS_PER_SECOND,
                    now.get(Calendar.ZONE_OFFSET) / MILLIS_PER_MINUTE +
                        now.get(Calendar.DST_OFFSET) / MILLIS_PER_MINUTE
                )
            }

            private companion object {
                const val MILLIS_PER_SECOND = 1_000L
                const val MILLIS_PER_MINUTE = 60_000L
            }
        }

        private companion object {
            val DIFF = object : DiffUtil.ItemCallback<ActiveSessionUi>() {
                override fun areItemsTheSame(oldItem: ActiveSessionUi, newItem: ActiveSessionUi): Boolean =
                    oldItem.id == newItem.id

                override fun areContentsTheSame(oldItem: ActiveSessionUi, newItem: ActiveSessionUi): Boolean =
                    oldItem == newItem
            }
        }
    }
}
