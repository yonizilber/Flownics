package com.yonizilber.flownics.feature.data

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yonizilber.flownics.data.transfer.AutoBackup
import com.yonizilber.flownics.data.transfer.AutoBackupState
import com.yonizilber.flownics.data.transfer.DataTransfer
import com.yonizilber.flownics.data.transfer.ExportResult
import com.yonizilber.flownics.data.transfer.ImportPreview
import com.yonizilber.flownics.data.transfer.ImportResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class DataBusy { SAVING, READING, IMPORTING }

sealed interface DataMessage {
    data class Saved(val result: ExportResult) : DataMessage
    data class Imported(val result: ImportResult) : DataMessage
    data class Restored(val result: ImportResult) : DataMessage
    data class Failed(val error: Throwable) : DataMessage
}

data class DataUiState(
    val busy: DataBusy? = null,
    /** A checked file waiting for the user to confirm. */
    val preview: ImportPreview? = null,
    val message: DataMessage? = null,
)

@HiltViewModel
class DataViewModel @Inject constructor(
    private val transfer: DataTransfer,
    private val autoBackup: AutoBackup,
) : ViewModel() {

    private val _state = MutableStateFlow(DataUiState())
    val state: StateFlow<DataUiState> = _state.asStateFlow()

    val auto: StateFlow<AutoBackupState> = autoBackup.state

    fun saveBackup(uri: Uri) = work(DataBusy.SAVING) { DataMessage.Saved(transfer.exportBackupTo(uri)) }

    fun open(uri: Uri) {
        _state.value.preview?.let(transfer::discard)
        _state.update { it.copy(preview = null) }
        work(DataBusy.READING) {
            val preview = transfer.read(uri)
            _state.update { it.copy(preview = preview) }
            null
        }
    }

    fun importShare(reuseExisting: Boolean) {
        val preview = _state.value.preview ?: return
        _state.update { it.copy(preview = null) }
        work(DataBusy.IMPORTING) { DataMessage.Imported(transfer.importShare(preview, reuseExisting)) }
    }

    fun restore() {
        val preview = _state.value.preview ?: return
        _state.update { it.copy(preview = null) }
        work(DataBusy.IMPORTING) { DataMessage.Restored(transfer.restore(preview)) }
    }

    fun cancelPreview() {
        _state.value.preview?.let(transfer::discard)
        _state.update { it.copy(preview = null) }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    fun chooseFolder(tree: Uri) = autoBackup.setFolder(tree)

    fun setAutoEnabled(enabled: Boolean) = autoBackup.setEnabled(enabled)

    fun backUpNow() = autoBackup.runNow()

    /** Runs one task at a time; its outcome, success or failure, is shown until dismissed. */
    private fun work(busy: DataBusy, task: suspend () -> DataMessage?) {
        if (_state.value.busy != null) return
        _state.update { it.copy(busy = busy, message = null) }
        viewModelScope.launch {
            val message = try {
                task()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DataMessage.Failed(e)
            }
            _state.update { it.copy(busy = null, message = message) }
        }
    }

    override fun onCleared() {
        _state.value.preview?.let(transfer::discard)
    }
}
