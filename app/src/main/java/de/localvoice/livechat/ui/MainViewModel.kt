package de.localvoice.livechat.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import de.localvoice.livechat.LiveChatApplication
import de.localvoice.livechat.R
import de.localvoice.livechat.data.AppSettings
import de.localvoice.livechat.data.CatalogEntry
import de.localvoice.livechat.data.DownloadError
import de.localvoice.livechat.data.DownloadException
import de.localvoice.livechat.data.DownloadProgress
import de.localvoice.livechat.data.LocalModel
import de.localvoice.livechat.data.ModelDownloader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Fortschritt beim Kopieren einer Modelldatei. */
data class ImportProgress(val copiedBytes: Long, val totalBytes: Long) {
    val fraction: Float?
        get() = if (totalBytes > 0) (copiedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else null
}

class MainViewModel(application: Application) : ViewModel() {

    private val app = application
    private val container = (application as LiveChatApplication).container

    val session = container.liveSession
    val settings: StateFlow<AppSettings> = container.settings.settings

    private val _models = MutableStateFlow<List<LocalModel>>(emptyList())
    val models: StateFlow<List<LocalModel>> = _models.asStateFlow()

    private val _importProgress = MutableStateFlow<ImportProgress?>(null)
    val importProgress: StateFlow<ImportProgress?> = _importProgress.asStateFlow()

    private val _importError = MutableStateFlow<String?>(null)
    val importError: StateFlow<String?> = _importError.asStateFlow()

    private val downloader = container.downloader

    private val _download = MutableStateFlow<DownloadProgress?>(null)
    val download: StateFlow<DownloadProgress?> = _download.asStateFlow()

    /** Gesetzt, wenn das gewaehlte Modell erst nach Lizenzzustimmung laedt. */
    private val _tokenNeededFor = MutableStateFlow<CatalogEntry?>(null)
    val tokenNeededFor: StateFlow<CatalogEntry?> = _tokenNeededFor.asStateFlow()

    private var downloadJob: Job? = null

    val catalog: List<CatalogEntry> = container.catalog.entries

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
                .onFailure { _importError.value = it.message ?: app.getString(R.string.import_failed) }
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

    // ------------------------------------------------------------- Herunterladen

    fun startDownload(entry: CatalogEntry) {
        if (downloadJob?.isActive == true) return
        _importError.value = null
        _tokenNeededFor.value = null
        downloadJob = viewModelScope.launch {
            val token = container.settings.current.huggingFaceToken.takeIf { it.isNotBlank() }
            val result = downloader.download(entry, token) { progress ->
                _download.value = progress
            }
            _download.value = null
            result
                .onSuccess { file ->
                    refreshModels()
                    selectModel(file.name)
                }
                .onFailure { failure ->
                    val error = (failure as? DownloadException)?.error
                    when (error) {
                        is DownloadError.NeedsToken -> _tokenNeededFor.value = error.entry
                        is DownloadError.Message -> _importError.value = error.text
                        null -> _importError.value =
                            failure.message ?: app.getString(R.string.dl_failed_generic)
                    }
                }
        }
    }

    /**
     * Haelt den Download an, wirft das Geladene aber nicht weg - ein erneutes
     * "Herunterladen" setzt an derselben Stelle fort.
     */
    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _download.value = null
    }

    /** Wirft angefangene Bruchstuecke weg und faengt beim naechsten Mal von vorn an. */
    fun discardPartialDownloads() {
        cancelDownload()
        viewModelScope.launch { downloader.discardPartials() }
    }

    // --------------------------------------------------- Weitere Modelle suchen

    private val _onlineCatalog = MutableStateFlow<List<CatalogEntry>>(emptyList())
    val onlineCatalog: StateFlow<List<CatalogEntry>> = _onlineCatalog.asStateFlow()

    private val _catalogLoading = MutableStateFlow(false)
    val catalogLoading: StateFlow<Boolean> = _catalogLoading.asStateFlow()

    fun loadOnlineCatalog() {
        if (_catalogLoading.value) return
        viewModelScope.launch {
            _catalogLoading.value = true
            _importError.value = null
            downloader.fetchOnlineCatalog()
                .onSuccess { entries ->
                    _onlineCatalog.value = entries
                    if (entries.isEmpty()) {
                        _importError.value = app.getString(R.string.dl_no_more_models)
                    }
                }
                .onFailure {
                    _importError.value =
                        it.message ?: app.getString(R.string.dl_list_failed)
                }
            _catalogLoading.value = false
        }
    }

    fun dismissTokenHint() {
        _tokenNeededFor.value = null
    }

    /** Merkt, dass die Startfrage gestellt wurde - sie kommt dann nicht wieder. */
    fun markDownloadAsked() {
        updateSettings { it.copy(downloadAsked = true) }
    }

    fun testSpeech() = session.testSpeech()

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
