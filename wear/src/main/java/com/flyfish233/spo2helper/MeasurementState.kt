package com.flyfish233.spo2helper

import com.flyfish233.spo2helper.shared.Reading
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Progress of the current or last spot check, shared by the service and the UI. */
sealed interface MeasurementState {
    data object Idle : MeasurementState
    data object Measuring : MeasurementState
    data object ReadingSpo2 : MeasurementState
    data object Sending : MeasurementState
    data class Done(val reading: Reading) : MeasurementState
    data class Failed(val message: String) : MeasurementState
}

object MeasurementBus {
    private val _state = MutableStateFlow<MeasurementState>(MeasurementState.Idle)
    val state: StateFlow<MeasurementState> = _state

    fun set(state: MeasurementState) {
        _state.value = state
    }
}
