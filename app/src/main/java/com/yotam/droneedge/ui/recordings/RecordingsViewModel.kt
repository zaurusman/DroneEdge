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
}
