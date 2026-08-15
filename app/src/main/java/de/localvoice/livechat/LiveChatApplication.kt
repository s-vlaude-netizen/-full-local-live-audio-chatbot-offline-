package de.localvoice.livechat

import android.app.Application
import de.localvoice.livechat.data.ModelRepository
import de.localvoice.livechat.data.SettingsStore
import de.localvoice.livechat.session.LiveSessionController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/**
 * Haelt die langlebigen Bausteine.
 *
 * Der Gespraechszustand haengt bewusst an der Anwendung und nicht an der
 * Activity: Bildschirmdrehung, Sperrbildschirm oder ein Wechsel in eine andere
 * App duerfen ein laufendes Gespraech nicht abschneiden.
 */
class AppContainer(application: Application) {
    val applicationScope = CoroutineScope(SupervisorJob())
    val settings = SettingsStore(application)
    val models = ModelRepository(application)
    val liveSession = LiveSessionController(
        context = application,
        settingsStore = settings,
        models = models,
        scope = applicationScope,
    )
}

class LiveChatApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
