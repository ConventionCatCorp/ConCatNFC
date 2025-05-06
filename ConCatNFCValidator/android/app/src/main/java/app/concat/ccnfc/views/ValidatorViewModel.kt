package app.concat.ccnfc.views

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel

const val VALIDATOR_UI_STATE = "VALIDATOR_UI_STATE"

class ValidatorViewModel(private val savedStateHandle: SavedStateHandle): ViewModel() {
    var validatorState: ValidatorUIState
        get() = savedStateHandle[VALIDATOR_UI_STATE] ?: ValidatorUIState.PENDING
        set(value) = savedStateHandle.set(VALIDATOR_UI_STATE, value)

    companion object {
        enum class ValidatorUIState {
            PENDING,
            VALID,
            INVALID,
        }
    }
}