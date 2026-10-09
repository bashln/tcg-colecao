package app.tcg.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import app.tcg.app.ui.nav.AppNav
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                AppNav(
                    cardRepository = cardRepository,
                    collectionRepository = collectionRepository,
                )
            }
        }
    }
}
