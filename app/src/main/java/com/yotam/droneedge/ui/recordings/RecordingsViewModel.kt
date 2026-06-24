package com.droneedge.app.ui.recordings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.droneedge.app.recording.renameSession
import com.droneedge.app.recording.sanitizeSessionName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class RecordingsViewModel(application: Application) : AndroidViewModel(application) {

    private val _recordings = MutableStateFlow<List<RecordingEntry>>(emptyList())
    val recordings: StateFlow<List<RecordingEntry>> = _recordings.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init { reload() }

    fun reload() {
        viewModelScope.launch {
            _recordings.value = withContext(Dispatchers.IO) {
                queryRecordings(getApplication())
            }
        }
    }

    fun rename(entry: RecordingEntry, newName: String) {
        val sanitized = sanitizeSessionName(newName) ?: return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                renameSession(getApplication(), entry.uri, entry.sessionName, sanitized)
            }
            if (!ok) _error.value = "Rename failed"
            reload()
        }
    }

    fun delete(entry: RecordingEntry) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.delete(entry.uri, null, null)
                    File(getApplication<Application>().getExternalFilesDir(null),
                        "recordings/${entry.sessionName}").deleteRecursively()
                }
            } catch (e: Exception) {
                _error.value = "Delete failed"
            }
            reload()
        }
    }

    fun clearError() { _error.value = null }

    private val _calcSession = MutableStateFlow<String?>(null)
    val calcSession: StateFlow<String?> = _calcSession.asStateFlow()
    private val _calcProgress = MutableStateFlow(0f)
    val calcProgress: StateFlow<Float> = _calcProgress.asStateFlow()

    fun calc(entry: RecordingEntry) {
        if (_calcSession.value != null) return
        _calcSession.value = entry.sessionName
        _calcProgress.value = 0f
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<android.app.Application>()
            runCatching {
                com.droneedge.app.recording.calc.runCalc(ctx, entry) { p -> _calcProgress.value = p }
            }.exceptionOrNull()?.let { _error.value = "Calc failed: ${it.message}" }
            _calcSession.value = null
            reload()
        }
    }
}
