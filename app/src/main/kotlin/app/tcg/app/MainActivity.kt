package app.tcg.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import app.tcg.app.ui.nav.AppNav
import app.tcg.app.ui.theme.TcgTheme
import app.tcg.app.auth.AuthController
import app.tcg.app.data.sync.SyncLinkCoordinator
import app.tcg.app.data.sync.SyncLifecycle
import app.tcg.app.data.sync.SyncTriggerBinder
import app.tcg.app.data.sync.SyncLinkRepository
import app.tcg.app.update.UpdateCoordinator
import app.tcg.app.update.UpdateInstaller
import app.tcg.core.data.repository.CardRepository
import app.tcg.core.data.repository.CardListRepository
import app.tcg.core.data.repository.CollectionRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var cardRepository: CardRepository

    @Inject
    lateinit var collectionRepository: CollectionRepository

    @Inject
    lateinit var cardListRepository: CardListRepository

    @Inject
    lateinit var authController: AuthController

    @Inject
    lateinit var syncLinkRepository: SyncLinkRepository

    @Inject
    lateinit var syncLinkCoordinator: SyncLinkCoordinator

    @Inject
    lateinit var syncTriggerBinder: SyncTriggerBinder

    @Inject
    lateinit var syncLifecycle: SyncLifecycle

    @Inject
    lateinit var updateCoordinator: UpdateCoordinator

    @Inject
    lateinit var updateInstaller: UpdateInstaller

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Sync triggers (local writes, foreground, network recovery) live for exactly as
        // long as this activity; cancelling on destroy stops any further automatic sync.
        lifecycleScope.launch { syncTriggerBinder.bind() }
        setContent {
            TcgTheme {
                AppNav(
                    cardRepository = cardRepository,
                    collectionRepository = collectionRepository,
                    cardListRepository = cardListRepository,
                    authController = authController,
                    syncLinkRepository = syncLinkRepository,
                    syncLinkCoordinator = syncLinkCoordinator,
                    syncLifecycle = syncLifecycle,
                    updateCoordinator = updateCoordinator,
                    updateInstaller = updateInstaller,
                )
            }
        }
    }
}
