package app.tcg.app.di

import android.content.Context
import android.provider.Settings
import androidx.room.Room
import app.tcg.app.BuildConfig
import app.tcg.app.auth.AuthController
import app.tcg.app.auth.AuthRemote
import app.tcg.app.auth.AndroidKeystoreSessionCipher
import app.tcg.app.auth.EncryptedSessionStore
import app.tcg.app.auth.SessionCipher
import app.tcg.app.auth.SessionStore
import app.tcg.app.auth.SupabaseAuthClient
import app.tcg.app.data.backup.BackupDocumentStore
import app.tcg.app.data.backup.BackupPassphraseStore
import app.tcg.app.data.backup.AndroidBackupPassphraseStore
import app.tcg.app.data.backup.CollectionBackup
import app.tcg.app.data.backup.CollectionBackupWorker
import app.tcg.app.data.backup.FileRecoveryStore
import app.tcg.app.data.backup.LocalBackupManager
import app.tcg.app.data.backup.RecoveryStore
import app.tcg.app.data.backup.RoomCollectionBackup
import app.tcg.app.data.backup.SafBackupDocumentStore
import app.tcg.app.data.local.CardDao
import app.tcg.app.data.local.CollectionDao
import app.tcg.app.data.local.TcgDatabase
import app.tcg.app.data.prices.PriceRefresher
import app.tcg.app.data.repository.RoomCardRepository
import app.tcg.app.data.repository.RoomCollectionRepository
import app.tcg.app.data.sync.CollectionSyncCoordinator
import app.tcg.app.data.sync.RoomSyncLinkCoordinator
import app.tcg.app.data.sync.RoomSyncLocalStore
import app.tcg.app.data.sync.SupabaseSyncTransport
import app.tcg.app.data.sync.SyncLinkCoordinator
import app.tcg.app.data.sync.SyncLinkRepository
import app.tcg.app.data.sync.SyncLifecycle
import app.tcg.app.data.sync.SyncTriggerSources
import app.tcg.app.data.sync.SyncTriggerBinder
import app.tcg.app.update.ApkDownloader
import app.tcg.app.update.GitHubReleaseSource
import app.tcg.app.update.InstalledAppInfo
import app.tcg.app.update.OtaPlatform
import app.tcg.app.update.UpdateCoordinator
import app.tcg.app.update.UpdateInstaller
import app.tcg.core.data.repository.CardRepository
import app.tcg.core.data.repository.CollectionRepository
import app.tcg.core.data.tcgdex.KtorTcgdexCardSource
import app.tcg.core.data.tcgdex.TcgdexCardSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import java.io.File
import java.util.UUID
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideHttpClient(): HttpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    @Provides
    @Singleton
    fun provideTcgdexCardSource(httpClient: HttpClient): TcgdexCardSource =
        KtorTcgdexCardSource(httpClient)

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): TcgDatabase =
        Room.databaseBuilder(context, TcgDatabase::class.java, "tcg.db")
            .addMigrations(TcgDatabase.MIGRATION_1_2, TcgDatabase.MIGRATION_2_3, TcgDatabase.MIGRATION_3_4, TcgDatabase.MIGRATION_4_5, TcgDatabase.MIGRATION_5_6, TcgDatabase.MIGRATION_6_7, TcgDatabase.MIGRATION_7_8, TcgDatabase.MIGRATION_8_9)
            .addCallback(TcgDatabase.BACKUP_TRIGGERS)
            .build()

    @Provides
    fun provideCardDao(database: TcgDatabase): CardDao = database.cardDao()

    @Provides
    fun provideCollectionDao(database: TcgDatabase): CollectionDao = database.collectionDao()

    @Provides
    @Singleton
    fun provideCardRepository(
        cardDao: CardDao,
        source: TcgdexCardSource,
    ): CardRepository = RoomCardRepository(cardDao, source)

    @Provides
    @Singleton
    fun provideCollectionRepository(
        @ApplicationContext context: Context,
        collectionDao: CollectionDao,
        cardDao: CardDao,
    ): CollectionRepository = RoomCollectionRepository(collectionDao, cardDao) {
        CollectionBackupWorker.enqueue(context)
    }

    @Provides
    @Singleton
    fun providePriceRefresher(
        collectionRepository: CollectionRepository,
        cardRepository: CardRepository,
    ): PriceRefresher = PriceRefresher(collectionRepository, cardRepository)

    @Provides
    @Singleton
    fun provideDeviceId(@ApplicationContext context: Context): String {
        val preferences = context.getSharedPreferences("tcg_sync_device", Context.MODE_PRIVATE)
        val stored = preferences.getString("device_id", null)
        if (!stored.isNullOrBlank()) return stored
        val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString()
        check(preferences.edit().putString("device_id", deviceId).commit()) {
            "Não foi possível salvar a identificação deste aparelho"
        }
        return deviceId
    }

    @Provides
    @Singleton
    fun provideSessionCipher(): SessionCipher = AndroidKeystoreSessionCipher()

    @Provides
    @Singleton
    fun provideSessionStore(
        @ApplicationContext context: Context,
        cipher: SessionCipher,
    ): SessionStore = EncryptedSessionStore(
        prefs = context.getSharedPreferences("tcg_auth_session", Context.MODE_PRIVATE),
        cipher = cipher,
    )

    @Provides
    @Singleton
    fun provideAuthRemote(httpClient: HttpClient): AuthRemote = SupabaseAuthClient(
        baseUrl = BuildConfig.SUPABASE_URL,
        apiKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY,
        httpClient = httpClient,
    )

    @Provides
    @Singleton
    fun provideAuthController(remote: AuthRemote, store: SessionStore): AuthController =
        AuthController(remote, store)

    @Provides
    @Singleton
    fun provideSyncLinkRepository(dao: CollectionDao, deviceId: String): SyncLinkRepository =
        SyncLinkRepository(dao, deviceId)

    @Provides
    @Singleton
    fun provideSyncTransport(httpClient: HttpClient, authController: AuthController): SupabaseSyncTransport =
        SupabaseSyncTransport(
            baseUrl = BuildConfig.SUPABASE_URL,
            apiKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY,
            httpClient = httpClient,
            sessionProvider = { (authController.state.value as? app.tcg.app.auth.AuthState.SignedIn)?.session },
        )

    @Provides
    @Singleton
    fun provideRoomSyncLocalStore(database: TcgDatabase): RoomSyncLocalStore =
        RoomSyncLocalStore(database)

    @Provides
    @Singleton
    fun provideCollectionSyncCoordinator(
        transport: SupabaseSyncTransport,
        store: RoomSyncLocalStore,
    ): CollectionSyncCoordinator = CollectionSyncCoordinator(transport, store)

    @Provides
    @Singleton
    fun provideSyncLifecycleScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

@Provides
    @Singleton
    fun provideSyncTriggerBinder(
        @ApplicationContext context: android.content.Context,
        collectionRepository: app.tcg.core.data.repository.CollectionRepository,
        lifecycle: SyncLifecycle,
    ): SyncTriggerBinder = SyncTriggerBinder(
        collectionWrites = collectionRepository.observe(),
        isForeground = SyncTriggerSources.foreground(),
        networkAvailable = SyncTriggerSources.networkAvailable(context),
        onLocalWrite = lifecycle::onLocalWrite,
        onForeground = lifecycle::onForeground,
        onNetworkAvailable = lifecycle::onNetworkAvailable,
    )

    @Provides
    @Singleton
    fun provideSyncLifecycle(
        scope: CoroutineScope,
        coordinator: CollectionSyncCoordinator,
        authController: AuthController,
        linkRepository: SyncLinkRepository,
    ): SyncLifecycle {
        val lifecycle = SyncLifecycle(
            scope = scope,
            syncAction = { _ -> coordinator.syncOnce() },
            resolveConflictAction = coordinator::resolveConflict,
            onIdentityChanged = coordinator::bindIdentity,
        )
        // Bind/unbind from the live session: sync runs only while signed in AND this
        // device's stored binding belongs to that exact account. A sign-out or switch
        // resolves to null and unbinds before any further trigger can run.
        scope.launch {
            authController.state.collect { auth ->
                try {
                    val linkedIdentity = linkRepository.currentIdentity()
                    val signedInIdentity = (auth as? app.tcg.app.auth.AuthState.SignedIn)?.session?.userId
                    if (signedInIdentity != null && linkedIdentity != null && signedInIdentity != linkedIdentity) {
                        lifecycle.unlinkBinding(linkRepository::unlink)
                    } else {
                        lifecycle.refreshBinding(auth, linkedIdentity)
                    }
                } catch (_: Exception) {
                    lifecycle.refreshBinding(auth, null)
                }
            }
        }
        return lifecycle
    }

    @Provides
    @Singleton
    fun provideSyncLinkCoordinator(
        transport: SupabaseSyncTransport,
        store: RoomSyncLocalStore,
        dao: CollectionDao,
        cardDao: CardDao,
        cardRepository: CardRepository,
        deviceId: String,
    ): SyncLinkCoordinator = RoomSyncLinkCoordinator(
        transport = transport,
        store = store,
        dao = dao,
        cardDao = cardDao,
        cards = cardRepository,
        deviceId = deviceId,
    )

    @Provides
    @Singleton
    fun provideGitHubReleaseSource(httpClient: HttpClient): GitHubReleaseSource =
        GitHubReleaseSource(httpClient)

    @Provides
    @Singleton
    fun provideApkDownloader(@ApplicationContext context: Context, httpClient: HttpClient): ApkDownloader =
        ApkDownloader(httpClient, File(context.cacheDir, "ota"))

    @Provides
    @Singleton
    fun provideInstalledApp(@ApplicationContext context: Context): InstalledAppInfo = InstalledAppInfo(
        applicationId = context.packageName,
        versionCode = BuildConfig.VERSION_CODE.toLong(),
        signatureDigest = OtaPlatform.installedSignatureDigest(context),
    )

    @Provides
    @Singleton
    fun provideUpdateCoordinator(
        @ApplicationContext context: Context,
        installedApp: InstalledAppInfo,
        source: GitHubReleaseSource,
        downloader: ApkDownloader,
    ): UpdateCoordinator = UpdateCoordinator(
        installedApp = installedApp,
        fetchRelease = { source.fetchLatestStable() },
        downloadApk = { release, progress ->
            downloader.download(
                url = release.apkUrl,
                expectedSizeBytes = release.apkSizeBytes,
                expectedSha256 = release.apkSha256,
                onProgress = progress,
            )
        },
        readArchiveMetadata = { file -> OtaPlatform.archiveMetadata(context, file) },
    )

    @Provides
    @Singleton
    fun provideUpdateInstaller(@ApplicationContext context: Context): UpdateInstaller = UpdateInstaller(
        context = context,
        canRequestInstalls = {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.packageManager.canRequestPackageInstalls()
            } else {
                true
            }
        },
        archivePackageName = { file -> OtaPlatform.archiveMetadata(context, file)?.applicationId },
        launch = { intent -> context.startActivity(intent) },
    )
    @Provides
    @Singleton
    fun provideBackupDocumentStore(@ApplicationContext context: Context): BackupDocumentStore =
        SafBackupDocumentStore(context)

    @Provides
    @Singleton
    fun provideBackupPassphraseStore(@ApplicationContext context: Context): BackupPassphraseStore =
        AndroidBackupPassphraseStore(
            preferences = context.getSharedPreferences("tcg_local_backup", Context.MODE_PRIVATE),
            localCipher = AndroidKeystoreSessionCipher(keyAlias = "tcg_local_backup_key"),
        )

    @Provides
    @Singleton
    fun provideRecoveryStore(@ApplicationContext context: Context): RecoveryStore =
        FileRecoveryStore(context.filesDir.resolve("backup_recovery"))

    @Provides
    @Singleton
    fun provideLocalBackupManager(
        dao: CollectionDao,
        store: BackupDocumentStore,
        passphrases: BackupPassphraseStore,
    ): LocalBackupManager = LocalBackupManager(dao, store, passphrases)

    @Provides
    @Singleton
    fun provideCollectionBackup(
        dao: CollectionDao,
        recoveryStore: RecoveryStore,
        backupManager: LocalBackupManager,
        passphrases: BackupPassphraseStore,
    ): CollectionBackup = RoomCollectionBackup(dao, recoveryStore, backupManager, passphrases)
}
