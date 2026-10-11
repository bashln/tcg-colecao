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
import app.tcg.app.data.local.CardDao
import app.tcg.app.data.local.CollectionDao
import app.tcg.app.data.local.TcgDatabase
import app.tcg.app.data.repository.RoomCardRepository
import app.tcg.app.data.repository.RoomCollectionRepository
import app.tcg.app.data.sync.SyncLinkRepository
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
import java.util.UUID
import javax.inject.Singleton
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
            .addMigrations(TcgDatabase.MIGRATION_1_2, TcgDatabase.MIGRATION_2_3, TcgDatabase.MIGRATION_3_4, TcgDatabase.MIGRATION_4_5)
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
        collectionDao: CollectionDao,
        cardDao: CardDao,
    ): CollectionRepository = RoomCollectionRepository(collectionDao, cardDao)

    @Provides
    @Singleton
    fun provideDeviceId(@ApplicationContext context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: UUID.randomUUID().toString()

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
}
