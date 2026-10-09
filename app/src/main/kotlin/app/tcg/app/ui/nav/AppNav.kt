package app.tcg.app.ui.nav

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.tcg.app.R
import app.tcg.app.ui.card.CardDetailScreen
import app.tcg.app.ui.card.CardDetailViewModel
import app.tcg.app.ui.collection.CollectionScreen
import app.tcg.app.ui.collection.CollectionViewModel
import app.tcg.app.ui.components.EmptyState
import app.tcg.app.ui.components.LoadingState
import app.tcg.app.ui.scan.ScanScreen
import app.tcg.app.ui.scan.ScanViewModel
import app.tcg.core.data.repository.CardRepository
import app.tcg.core.data.repository.CollectionRepository
import app.tcg.core.domain.model.Card

private object Routes {
    const val SCAN = "scan"
    const val COLLECTION = "collection"
    const val CARD_ID = "cardId"
    const val CARD_DETAIL = "card/{$CARD_ID}"

    fun cardDetail(cardId: String) = "card/${Uri.encode(cardId)}"
}

@Composable
fun AppNav(
    cardRepository: CardRepository,
    collectionRepository: CollectionRepository,
    navController: NavHostController = rememberNavController(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val topLevelRoute = currentRoute == Routes.SCAN || currentRoute == Routes.COLLECTION

    Scaffold(
        bottomBar = {
            if (topLevelRoute) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 3.dp,
                ) {
                    NavigationBarItem(
                        selected = currentRoute == Routes.SCAN,
                        onClick = { navController.navigateToTopLevel(Routes.SCAN) },
                        icon = { Icon(Icons.Outlined.QrCodeScanner, contentDescription = null) },
                        label = { Text(stringResource(R.string.nav_scan)) },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                    )
                    NavigationBarItem(
                        selected = currentRoute == Routes.COLLECTION,
                        onClick = { navController.navigateToTopLevel(Routes.COLLECTION) },
                        icon = { Icon(Icons.Outlined.CollectionsBookmark, contentDescription = null) },
                        label = { Text(stringResource(R.string.nav_collection)) },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.SCAN,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.SCAN) {
                val viewModel: ScanViewModel = viewModel(
                    factory = viewModelFactory { initializer { ScanViewModel(cardRepository) } },
                )
                ScanScreen(
                    viewModel = viewModel,
                    onCardSelected = { card -> navController.navigate(Routes.cardDetail(card.id)) },
                )
            }
            composable(Routes.COLLECTION) {
                val viewModel: CollectionViewModel = viewModel(
                    factory = viewModelFactory { initializer { CollectionViewModel(collectionRepository) } },
                )
                CollectionScreen(
                    viewModel = viewModel,
                    onScanClick = { navController.navigateToTopLevel(Routes.SCAN) },
                )
            }
            composable(
                route = Routes.CARD_DETAIL,
                arguments = listOf(navArgument(Routes.CARD_ID) { type = NavType.StringType }),
            ) { entry ->
                CardDetailRoute(
                    cardId = entry.arguments?.getString(Routes.CARD_ID).orEmpty(),
                    cardRepository = cardRepository,
                    collectionRepository = collectionRepository,
                    onSaved = { navController.popBackStack() },
                )
            }
        }
    }
}

@Composable
private fun CardDetailRoute(
    cardId: String,
    cardRepository: CardRepository,
    collectionRepository: CollectionRepository,
    onSaved: () -> Unit,
) {
    var card by remember(cardId) { mutableStateOf<Card?>(null) }
    var loading by remember(cardId) { mutableStateOf(true) }

    LaunchedEffect(cardId) {
        loading = true
        card = cardRepository.getCard(cardId)
        loading = false
    }

    val resolved = card
    when {
        loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            LoadingState(stringResource(R.string.detail_loading))
        }
        resolved != null -> {
            val viewModel: CardDetailViewModel = viewModel(
                key = "card-detail-$cardId",
                factory = viewModelFactory {
                    initializer { CardDetailViewModel(resolved, collectionRepository) }
                },
            )
            CardDetailScreen(viewModel = viewModel, onBack = onSaved, onSaved = onSaved)
        }
        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(
                icon = Icons.Outlined.SearchOff,
                title = stringResource(R.string.detail_not_found_title),
                body = stringResource(R.string.detail_not_found),
            )
        }
    }
}

private fun NavHostController.navigateToTopLevel(route: String) {
    navigate(route) {
        popUpTo(graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
