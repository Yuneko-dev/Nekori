package eu.kanade.tachiyomi.di

import android.app.Application
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.cash.sqldelight.db.SqlDriver
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteConfiguration
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDatabaseType
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDriver
import com.eygraber.sqldelight.androidx.driver.FileProvider
import eu.kanade.domain.track.store.DelayedTrackingStore
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.cache.LibrarySettingsCache
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.saver.ImageSaver
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.source.SourceTrackerDispatcher
import eu.kanade.tachiyomi.data.translation.AiSettingsStore
import eu.kanade.tachiyomi.data.translation.TranslationCache
import eu.kanade.tachiyomi.data.translation.TranslationEngineManager
import eu.kanade.tachiyomi.data.translation.TranslationService
import eu.kanade.tachiyomi.discord.DiscordAuth
import eu.kanade.tachiyomi.discord.DiscordRpcManager
import eu.kanade.tachiyomi.discord.DiscordSecureStore
import eu.kanade.tachiyomi.discord.SensitiveContentPolicy
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.jsplugin.JsPluginManager
import eu.kanade.tachiyomi.jsruntime.JsRuntime
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.interceptor.RequestRateLimitPolicy
import eu.kanade.tachiyomi.source.AndroidSourceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.plus
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import nl.adaptivity.xmlutil.XmlDeclMode
import nl.adaptivity.xmlutil.core.XmlVersion
import nl.adaptivity.xmlutil.serialization.XML
import tachiyomi.core.common.storage.AndroidStorageFolderProvider
import tachiyomi.data.AlternativeTitlesColumnAdapter
import tachiyomi.data.Chapters
import tachiyomi.data.Database
import tachiyomi.data.DatabaseMaintenance
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.domain.download.service.RateLimitResolver
import tachiyomi.domain.download.service.SourceRateLimitPolicy
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.source.local.image.LocalNovelCoverManager
import tachiyomi.source.local.io.LocalNovelSourceFileSystem
import uy.kohesive.injekt.api.InjektModule
import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.addSingletonFactory
import uy.kohesive.injekt.api.get
import java.lang.ref.WeakReference

private val lock = Any()

class AppModule(val app: Application) : InjektModule {

    private var sqlDriverRef: WeakReference<SqlDriver>? = null

    override fun InjektRegistrar.registerInjectables() {
        addSingleton(app)
        addSingleton<Context>(app)

        addSingletonFactory<SqlDriver> {
            synchronized(lock) {
                sqlDriverRef?.get()?.let { return@synchronized it }

                AndroidxSqliteDriver(
                    driver = BundledSQLiteDriver(),
                    databaseType = AndroidxSqliteDatabaseType.FileProvider(app, "tachiyomi.db"),
                    schema = Database.Schema,
                    configuration = AndroidxSqliteConfiguration(
                        isForeignKeyConstraintsEnabled = true,
                    ),
                )
                    .also { sqlDriverRef = WeakReference(it) }
            }
        }
        addSingletonFactory {
            Database(
                driver = get(),
                historyAdapter = History.Adapter(
                    last_readAdapter = DateColumnAdapter,
                ),

                mangasAdapter = Mangas.Adapter(
                    genreAdapter = StringListColumnAdapter,
                    update_strategyAdapter = UpdateStrategyColumnAdapter,
                    alternative_titlesAdapter = AlternativeTitlesColumnAdapter,
                    memoAdapter = MemoColumnAdapter,
                ),
                chaptersAdapter = Chapters.Adapter(
                    memoAdapter = MemoColumnAdapter,
                ),
            )
        }
        addSingletonFactory { DatabaseMaintenance(get()) }

        addSingletonFactory {
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            }
        }
        addSingletonFactory<XML> {
            XML.v1 {
                policy {
                    ignoreUnknownChildren()
                    autoPolymorphic = true
                }
                xmlDeclMode = XmlDeclMode.Charset
                xmlVersion = XmlVersion.XML10
                setIndent(2)
            }
        }
        addSingletonFactory<ProtoBuf> {
            ProtoBuf
        }

        addSingletonFactory<CoroutineScope> { ProcessLifecycleOwner.get().lifecycleScope + SupervisorJob() }

        addSingletonFactory { ChapterCache(app, get()) }
        addSingletonFactory { CoverCache(app) }
        addSingletonFactory { LibrarySettingsCache(app) }

        addSingletonFactory { NetworkHelper(app, get()) }
        addSingletonFactory {
            val networkHelper = get<NetworkHelper>()
            JsRuntime(app, networkHelper.jsPluginClient, networkHelper::defaultUserAgentProvider)
        }

        addSingletonFactory<SourceManager> { AndroidSourceManager(get()) }
        addSingletonFactory { ExtensionManager(app) }

        addSingletonFactory { RateLimitResolver(get()) }
        addSingletonFactory<RequestRateLimitPolicy> { SourceRateLimitPolicy(get(), get()) }

        addSingletonFactory { DownloadProvider(app) }
        addSingletonFactory { DownloadManager(app) }
        addSingletonFactory { DownloadCache(app) }

        addSingletonFactory { TrackerManager() }
        addSingletonFactory { DelayedTrackingStore(app) }
        addSingletonFactory { SourceTrackerDispatcher(get(), get(), get(), get()) }

        addSingletonFactory { ImageSaver(app) }

        // Translation services
        addSingletonFactory { TranslationCache(app) }
        addSingletonFactory { AiSettingsStore(get(), get()) }
        addSingletonFactory { TranslationEngineManager(get()) }
        addSingletonFactory { TranslationService(app) }

        // JS Plugin management (LNReader-style plugins)
        addSingletonFactory { JsPluginManager(app) }

        addSingletonFactory { DiscordSecureStore(app) }
        addSingletonFactory {
            DiscordAuth(
                context = app,
                client = get<NetworkHelper>().client,
                json = get(),
                scope = get(),
                store = get(),
            )
        }
        addSingletonFactory { SensitiveContentPolicy(get(), get(), get()) }
        addSingletonFactory {
            DiscordRpcManager(
                client = get<NetworkHelper>().client,
                json = get(),
                scope = get(),
                auth = get(),
                preferences = get(),
                basePreferences = get(),
                sensitiveContentPolicy = get(),
            )
        }

        addSingletonFactory { AndroidStorageFolderProvider(app) }
        addSingletonFactory { LocalNovelSourceFileSystem(get()) }
        addSingletonFactory { LocalNovelCoverManager(app, get()) }
        addSingletonFactory { StorageManager(app, get()) }

        // Font management
        addSingletonFactory { eu.kanade.tachiyomi.data.font.FontManager(app, get(), get()) }
    }
}
