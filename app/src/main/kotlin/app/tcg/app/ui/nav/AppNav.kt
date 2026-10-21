package app.tcg.app.ui.nav

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import app.tcg.app.BuildConfig
import app.tcg.app.R
import app.tcg.app.auth.AuthController
import app.tcg.app.data.sync.SyncLinkCoordinator
import app.tcg.app.data.sync.SyncLifecycle
import app.tcg.app.data.sync.SyncLinkRepository
import app.tcg.app.ui.account.AccountScreen
import app.tcg.app.ui.account.AccountViewModel
import app.tcg.app.ui.card.CardDetailScreen
import app.tcg.app.ui.card.CardDetailViewModel
import app.tcg.app.ui.card.CollectionItemEditScreen
import app.tcg.app.ui.card.CollectionItemEditViewModel
import app.tcg.app.ui.collection.CollectionScreen
import app.tcg.app.ui.collection.CollectionViewModel
import app.tcg.app.ui.components.EmptyState
import app.tcg.app.ui.components.LoadingState
import app.tcg.app.ui.home.HomeScreen
import app.tcg.app.ui.home.HomeViewModel
import app.tcg.app.ui.profile.ProfileScreen
import app.tcg.app.ui.profile.ProfileViewModel
import app.tcg.app.data.backup.CollectionBackup
import app.tcg.app.ui.scan.ScanScreen
import app.tcg.app.ui.scan.ScanViewModel
import app.tcg.app.ui.update.UpdateScreen
import app.tcg.app.ui.update.UpdateViewModel
import app.tcg.app.update.UpdateCoordinator
import app.tcg.app.update.UpdateInstaller
import app.tcg.core.data.repository.CardRepository
import app.tcg.core.data.repository.CollectionRepository
import app.tcg.core.domain.model.Card
import kotlinx.coroutines.launch

private object Routes {
    const val HOME = "home"
    const val SCAN = "scan"
    const val COLLECTION = "collection"
    const val PROFILE = "profile"
    const val ACCOUNT = "account"
    const val UPDATE = "update"
    const val CARD_ID = "cardId"
    const val CARD_DETAIL = "card/{$CARD_ID}"
    const val COLLECTION_ITEM_ID = "itemId"
    const val COLLECTION_ITEM_EDIT = "collection-item/{$COLLECTION_ITEM_ID}"

    val topLevel = setOf(HOME, SCAN, COLLECTION, PROFILE)

    fun cardDetail(cardId: String) = "card/${Uri.encode(cardId)}"
    fun collectionItemEdit(itemId: String) = "collection-item/${Uri.encode(itemId)}"
}

@Composable
fun AppNav(
    cardRepository: CardRepository,
    collectionRepository: CollectionRepository,
    collectionBackup: CollectionBackup? = null,
    authController: AuthController,
    syncLinkRepository: SyncLinkRepository,
    syncLinkCoordinator: SyncLinkCoordinator,
    syncLifecycle: SyncLifecycle,
    updateCoordinator: UpdateCoordinator,
    updateInstaller: UpdateInstaller,
    navController: NavHostController = rememberNavController(),
) {
    // Restore any persisted session once so a returning signed-in user sees their state.
    LaunchedEffect(Unit) { authController.start() }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val topLevelRoute = currentRoute in Routes.topLevel

    Scaffold(
        bottomBar = {
            if (topLevelRoute && currentRoute != Routes.SCAN) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp,
                ) {
                    NavigationBarItem(
                        selected = currentRoute == Routes.HOME,
                        onClick = { navController.navigateToTopLevel(Routes.HOME) },
                        icon = { Icon(Icons.Outlined.Home, contentDescription = null) },
                        label = { Text(stringResource(R.string.nav_home)) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                        ),
                    )
                    NavigationBarItem(
                        selected = currentRoute == Routes.COLLECTION,
                        onClick = { navController.navigateToTopLevel(Routes.COLLECTION) },
                        icon = { Icon(Icons.Outlined.CollectionsBookmark, contentDescription = null) },
                        label = { Text(stringResource(R.string.nav_collection)) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                        ),
                    )
                    NavigationBarItem(
                        selected = currentRoute == Routes.SCAN,
                        onClick = { navController.navigateToTopLevel(Routes.SCAN) },
                        icon = { Icon(Icons.Outlined.QrCodeScanner, contentDescription = null) },
                        label = { Text(stringResource(R.string.nav_scan)) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                        ),
                    )
                    NavigationBarItem(
                        selected = currentRoute == Routes.PROFILE,
                        onClick = { navController.navigateToTopLevel(Routes.PROFILE) },
                        icon = { Icon(Icons.Outlined.Person, contentDescription = null) },
                        label = { Text(stringResource(R.string.nav_profile)) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                        ),
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.HOME) {
                val viewModel: HomeViewModel = viewModel(
                    factory = viewModelFactory { initializer { HomeViewModel(collectionRepository) } },
                )
                HomeScreen(
                    viewModel = viewModel,
                    onScanClick = { navController.navigateToTopLevel(Routes.SCAN) },
                    onCollectionItemSelected = { itemId -> navController.navigate(Routes.collectionItemEdit(itemId)) },
                )
            }
            composable(Routes.SCAN) {
                val viewModel: ScanViewModel = viewModel(
                    factory = viewModelFactory { initializer { ScanViewModel(cardRepository) } },
                )
                ScanScreen(
                    viewModel = viewModel,
                    onCardSelected = { card -> navController.navigate(Routes.cardDetail(card.id)) },
                    onExit = { navController.navigateToTopLevel(Routes.HOME) },
                )
            }
            composable(Routes.COLLECTION) {
                val viewModel: CollectionViewModel = viewModel(
                    factory = viewModelFactory { initializer { CollectionViewModel(collectionRepository, cardRepository) } },
                )
                CollectionScreen(
                    viewModel = viewModel,
                    onScanClick = { navController.navigateToTopLevel(Routes.SCAN) },
                    onCardSelected = { cardId -> navController.navigate(Routes.cardDetail(cardId)) },
                    onCollectionItemSelected = { itemId -> navController.navigate(Routes.collectionItemEdit(itemId)) },
                )
            }
            composable(Routes.PROFILE) {
                val viewModel: ProfileViewModel = viewModel(
                    factory = viewModelFactory { initializer { ProfileViewModel(collectionRepository, collectionBackup) } },
                )
                ProfileScreen(
                    viewModel = viewModel,
                    onScanClick = { navController.navigateToTopLevel(Routes.SCAN) },
                    onAccountClick = { navController.navigate(Routes.ACCOUNT) },
                    onUpdatesClick = { navController.navigate(Routes.UPDATE) },
                )
            }
            composable(Routes.ACCOUNT) {
                val viewModel: AccountViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer {
                            AccountViewModel(
                                auth = authController,
                                link = syncLinkCoordinator,
                                syncStatus = syncLifecycle.status,
                                onSyncNow = { syncLifecycle.request() },
                                onLinkConfirmed = { identity ->
                                    syncLifecycle.persistAndRefreshBinding(
                                        expectedIdentity = identity,
                                        authState = { authController.state.value },
                                        persistBinding = { syncLinkRepository.link(identity) },
                                        readLinkedIdentity = syncLinkRepository::currentIdentity,
                                        clearBinding = syncLinkRepository::unlink,
                                    ).await()
                                },
                                onLinkUnlinked = { syncLifecycle.unlinkBinding(syncLinkRepository::unlink) },
                                onResolveConflict = syncLifecycle::resolveConflict,
                            )
                        }
                    },
                )
                AccountScreen(
                    state = viewModel.state.collectAsStateWithLifecycle().value,
                    onSignIn = viewModel::submitSignIn,
                    onSignUp = viewModel::submitSignUp,
                    onRequestLink = viewModel::requestLink,
                    onConfirmLink = viewModel::confirmLink,
                    onCancelLink = viewModel::cancelLink,
                    onSyncNow = viewModel::syncNow,
                    onResolveConflict = viewModel::resolveConflict,
                    onSignOut = viewModel::signOut,
                    onDismissError = viewModel::dismissError,
                )
            }
            composable(Routes.UPDATE) {
                val context = LocalContext.current
                val viewModel: UpdateViewModel = viewModel(
                    factory = viewModelFactory {
                        initializer {
                            UpdateViewModel(
                                coordinator = updateCoordinator,
                                installHandoff = { file -> updateInstaller.handoff(file) },
                                openInstallSettings = { context.startActivity(updateInstaller.installSettingsIntent()) },
                            )
                        }
                    },
                )
                UpdateScreen(
                    state = viewModel.state.collectAsStateWithLifecycle().value,
                    installBlocked = viewModel.installBlocked.collectAsStateWithLifecycle().value,
                    currentVersionName = BuildConfig.VERSION_NAME,
                    onCheck = viewModel::check,
                    onAccept = viewModel::acceptAndDownload,
                    onCancel = viewModel::cancelDownload,
                    onInstall = viewModel::install,
                    onDismissError = viewModel::dismissError,
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
            composable(
                route = Routes.COLLECTION_ITEM_EDIT,
                arguments = listOf(navArgument(Routes.COLLECTION_ITEM_ID) { type = NavType.StringType }),
            ) { entry ->
                val itemId = entry.arguments?.getString(Routes.COLLECTION_ITEM_ID).orEmpty()
                val viewModel: CollectionItemEditViewModel = viewModel(
                    key = "collection-item-edit-$itemId",
                    factory = viewModelFactory {
                        initializer { CollectionItemEditViewModel(itemId, collectionRepository) }
                    },
                )
                CollectionItemEditScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                    onSaved = { navController.popBackStack() },
                    onRemoved = { navController.popBackStack() },
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
