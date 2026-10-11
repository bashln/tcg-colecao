package app.tcg.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.tcg.app.ui.nav.AppNav
import app.tcg.app.ui.theme.TcgTheme
import app.tcg.app.auth.AuthController
import app.tcg.app.data.sync.SyncLinkRepository
import app.tcg.core.data.repository.CardRepository
import app.tcg.core.data.repository.CollectionRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var cardRepository: CardRepository

    @Inject
    lateinit var collectionRepository: CollectionRepository

    @Inject
    lateinit var authController: AuthController

    @Inject
    lateinit var syncLinkRepository: SyncLinkRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            TcgTheme {
                AppNav(
                    cardRepository = cardRepository,
                    collectionRepository = collectionRepository,
                    authController = authController,
                    syncLinkRepository = syncLinkRepository,
                )
            }
        }
    }
}
