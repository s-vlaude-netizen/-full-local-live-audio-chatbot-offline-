package de.localvoice.livechat.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import de.localvoice.livechat.LiveChatApplication
import de.localvoice.livechat.data.AppSettings
import de.localvoice.livechat.data.LocalModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Fortschritt beim Kopieren einer Modelldatei. */
data class ImportProgress(val copiedBytes: Long, val totalBytes: Long) {
    val fraction: Float?
        get() = if (totalBytes > 0) (copiedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else null
}

class MainViewModel(application: Application) : ViewModel() {

    private val container = (application as LiveChatApplication).container

    val session = container.liveSession
    val settings: StateFlow<AppSettings> = container.settings.settings

    private val _models = MutableStateFlow<List<LocalModel>>(emptyList())
    val models: StateFlow<List<LocalModel>> = _models.asStateFlow()

    private val _importProgress = MutableStateFlow<ImportProgress?>(null)
    val importProgress: StateFlow<ImportProgress?> = _importProgress.asStateFlow()

    private val _importError = MutableStateFlow<String?>(null)
    val importError: StateFlow<String?> = _importError.asStateFlow()

    val modelsDirPath: String get() = container.models.modelsDir.absolutePath

    init {
        refreshModels()
    }

    fun refreshModels() {
        viewModelScope.launch {
            _models.value = container.models.list()
        }
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        container.settings.update(transform)
    }

    fun selectModel(name: String?) {
        updateSettings { it.copy(modelFileName = name) }
        session.warmUp()
    }

    fun importModel(uri: Uri) {
        viewModelScope.launch {
            _importError.value = null
            _importProgress.value = ImportProgress(0, -1)
            val result = container.models.import(uri) { copied, total ->
                _importProgress.value = ImportProgress(copied, total)
            }
            _importProgress.value = null
            result
                .onSuccess { file ->
                    refreshModels()
                    selectModel(file.name)
                }
                .onFailure { _importError.value = it.message ?: "Import fehlgeschlagen." }
        }
    }

    fun deleteModel(model: LocalModel) {
        viewModelScope.launch {
            if (container.settings.current.modelFileName == model.name) selectModel(null)
            container.models.delete(model)
            refreshModels()
        }
    }

    fun dismissImportError() {
        _importError.value = null
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as Application
                MainViewModel(app)
            }
        }
    }
}
