package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.jsplugin.JsPluginManager
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import tachiyomi.domain.download.service.RateLimitCandidate
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.repository.StubSourceRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.LocalNovelSource
import uy.kohesive.injekt.injectLazy
import java.util.concurrent.ConcurrentHashMap

class AndroidSourceManager(
    private val sourceRepository: StubSourceRepository,
) : SourceManager {

    private val _isInitialized = MutableStateFlow(false)
    override val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private val downloadManager: DownloadManager by injectLazy()
    private val jsPluginManager: JsPluginManager by injectLazy()
    private val networkHelper: NetworkHelper by injectLazy()

    private val scope = CoroutineScope(Job() + Dispatchers.IO)

    private val sourcesMapFlow = MutableStateFlow(ConcurrentHashMap<Long, Source>())

    private val stubSourcesMap = ConcurrentHashMap<Long, StubSource>()

    override val sources: Flow<List<Source>> = sourcesMapFlow.map { it.values.toList() }

    init {
        scope.launch {
            jsPluginManager.jsSources
                .collectLatest { jsSources ->
                    val mutableMap = ConcurrentHashMap<Long, Source>(
                        mapOf(
                            LocalNovelSource.ID to LocalNovelSource(),
                        ),
                    )

                    jsSources.forEach { jsSource ->
                        mutableMap[jsSource.id] = jsSource
                        registerStubSource(StubSource.from(jsSource))
                    }

                    sourcesMapFlow.value = mutableMap
                    // Keeps PerHostDynamicRateLimitInterceptor's per-host state bounded by "hosts
                    // with a currently installed source" instead of growing for the app's whole
                    // process lifetime.
                    networkHelper.rateLimitInterceptor.pruneToHosts(
                        mutableMap.values.mapNotNullTo(mutableSetOf()) { it.rateLimitHost() },
                    )
                    if (!_isInitialized.value) {
                        jsPluginManager.isInitialized.first { it }
                    }
                    _isInitialized.value = true
                }
        }

        scope.launch {
            sourceRepository.subscribeAll()
                .collectLatest { sources ->
                    stubSourcesMap.clear()
                    sources.forEach {
                        stubSourcesMap[it.id] = it
                    }
                }
        }
    }

    override fun get(sourceKey: Long): Source? {
        return sourcesMapFlow.value[sourceKey]
    }

    override fun getOrStub(sourceKey: Long): Source {
        return sourcesMapFlow.value[sourceKey] ?: stubSourcesMap.getOrPut(sourceKey) {
            runBlocking { createStubSource(sourceKey) }
        }
    }

    override fun getAll() = sourcesMapFlow.value.values.toList()

    override fun getOnlineSources() = sourcesMapFlow.value.values.filterIsInstance<HttpSource>()

    override fun getRateLimitCandidates(): List<RateLimitCandidate> {
        return sourcesMapFlow.value.values.mapNotNull { source ->
            val baseUrl = source.rateLimitBaseUrl() ?: return@mapNotNull null
            RateLimitCandidate(
                sourceId = source.id,
                baseUrl = baseUrl,
                isNovel = source.isNovelSource(),
                isUnmetered = source is UnmeteredSource,
                declaredMinimumMillis = (source as? RateLimited)?.minimumDelayMillis ?: 0L,
            )
        }
    }

    override fun getStubSources(): List<StubSource> {
        val onlineSourceIds = getOnlineSources().map { it.id }
        return stubSourcesMap.values.filterNot { it.id in onlineSourceIds }
    }

    private fun registerStubSource(source: StubSource) {
        scope.launch {
            val dbSource = sourceRepository.getStubSource(source.id)
            if (dbSource == source) return@launch
            sourceRepository.upsertStubSource(
                source.id,
                source.lang,
                source.name,
                source.isNovelSource,
                source.isJsSource,
            )
            if (dbSource != null) {
                downloadManager.renameSource(dbSource, source)
            }
        }
    }

    private suspend fun createStubSource(id: Long): StubSource {
        sourceRepository.getStubSource(id)?.let {
            return it
        }
        return StubSource(id = id, lang = "", name = "")
    }
}
