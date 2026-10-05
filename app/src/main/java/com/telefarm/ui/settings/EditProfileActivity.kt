package com.telefarm.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.telefarm.R
import com.telefarm.appGraph
import com.telefarm.core.AppGraph
import com.telefarm.core.td.TelefarmLog
import com.telefarm.data.model.AccountUi
import com.telefarm.databinding.ActivityEditProfileBinding
import com.telefarm.ui.common.hideKeyboard
import com.telefarm.ui.common.resolve
import com.telefarm.ui.common.showSnackbar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.telefarm.data.model.UiMessage

/**
 * Editing the signed in profile: first name, last name and biography.
 *
 * Values are written back through TDLib, so what the account shows on every other device
 * changes as well.
 */
class EditProfileActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditProfileBinding

    private val viewModel: EditProfileViewModel by viewModels {
        viewModelFactory { initializer { EditProfileViewModel(appGraph) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.saveButton.setOnClickListener {
            binding.firstNameInput.hideKeyboard()
            viewModel.save(
                firstName = binding.firstNameInput.text?.toString().orEmpty(),
                lastName = binding.lastNameInput.text?.toString().orEmpty(),
                bio = binding.bioInput.text?.toString().orEmpty()
            )
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    binding.saveProgress.isVisible = state.isSaving
                    binding.saveButton.isEnabled = !state.isSaving
                    state.error?.let { error ->
                        binding.root.showSnackbar(error.resolve(this@EditProfileActivity))
                        viewModel.clearError()
                    }
                    if (!state.loaded) return@collect
                    if (binding.firstNameInput.text.isNullOrEmpty()) {
                        binding.firstNameInput.setText(state.firstName)
                        binding.lastNameInput.setText(state.lastName)
                        binding.bioInput.setText(state.bio)
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.saved.collect { saved ->
                    if (saved) {
                        binding.root.showSnackbar(getString(R.string.profile_saved))
                        finish()
                    }
                }
            }
        }
    }

    companion object {
        /** Opens the profile editor of the signed in account. */
        fun intent(context: Context): Intent = Intent(context, EditProfileActivity::class.java)
    }
}

/** State of the profile editor. */
data class EditProfileState(
    val loaded: Boolean = false,
    val firstName: String = "",
    val lastName: String = "",
    val bio: String = "",
    val isSaving: Boolean = false,
    val error: UiMessage? = null
)

/** Loads and saves the account profile. */
class EditProfileViewModel(private val graph: AppGraph) : ViewModel() {

    private val _state = MutableStateFlow(EditProfileState())
    val state: StateFlow<EditProfileState> = _state.asStateFlow()

    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    init {
        viewModelScope.launch {
            val account: AccountUi? = graph.settings.account()
            if (account == null) {
                _state.value = _state.value.copy(error = UiMessage.Res(R.string.profile_error))
                return@launch
            }
            _state.value = _state.value.copy(
                loaded = true,
                firstName = account.firstName,
                lastName = account.lastName,
                bio = account.bio.orEmpty()
            )
        }
    }

    /** Writes the profile back to Telegram. */
    fun save(firstName: String, lastName: String, bio: String) {
        if (firstName.isBlank()) {
            _state.value = _state.value.copy(error = UiMessage.Res(R.string.profile_first_name))
            return
        }
        if (_state.value.isSaving) return
        _state.value = _state.value.copy(isSaving = true, error = null)
        viewModelScope.launch {
            val nameSaved = try {
                graph.settings.updateName(firstName, lastName)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                TelefarmLog.w(TAG, "Name update failed")
                false
            }
            val bioSaved = if (nameSaved && bio.trim() != _state.value.bio) {
                graph.settings.updateBio(bio)
            } else {
                nameSaved
            }
            _state.value = _state.value.copy(isSaving = false)
            if (nameSaved && bioSaved) {
                _saved.value = true
            } else {
                _state.value = _state.value.copy(error = UiMessage.Res(R.string.profile_something_wrong))
            }
        }
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    private companion object {
        const val TAG = "EditProfile"
    }
}
