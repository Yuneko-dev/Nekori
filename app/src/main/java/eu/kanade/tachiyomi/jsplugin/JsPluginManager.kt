package eu.kanade.tachiyomi.jsplugin

import android.content.ContentResolver
import android.content.Context
import android.provider.DocumentsContract
import com.hippo.unifile.UniFile
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.jsplugin.model.InstalledJsPlugin
import eu.kanade.tachiyomi.jsplugin.model.JsPlugin
import eu.kanade.tachiyomi.jsplugin.model.JsPluginRepository
import eu.kanade.tachiyomi.jsplugin.source.JsSource
import eu.kanade.tachiyomi.jsruntime.JsRuntime
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.interceptor.rateLimitExempt
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.util.lang.Hash
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import logcat.LogPriority
import okhttp3.CacheControl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.storage.service.StorageManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Manages JavaScript plugins from LNReader-compatible repositories.
 * Handles fetching plugin lists, downloading plugins, caching, and creating JsSource instances.
 */
class JsPluginManager(
    private val context: Context,
) {
    internal companion object {
        private const val CUSTOM_JS_KIND = "js"
        private const val CUSTOM_CSS_KIND = "css"
        private val CUSTOM_ASSET_FILE = Regex("""^[0-9a-f]{64}\.custom[.-](js|css)$""")

        private val repositoryJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        internal fun normalizeRepositoryUrl(url: String): String = url.trim().trimEnd('/')

        internal fun validateRepositoryUrl(url: String): String {
            val normalized = normalizeRepositoryUrl(url)
            val parsed = normalized.toHttpUrlOrNull()
            require(parsed != null && parsed.host.isNotBlank()) {
                "Repository URL must be an absolute HTTP(S) URL"
            }
            return normalized
        }

        internal fun decodeRepositoryManifest(body: String, allowEmpty: Boolean): List<JsPlugin> {
            val root = try {
                repositoryJson.decodeFromString<JsonElement>(body)
            } catch (e: Exception) {
                throw IllegalArgumentException("Repository manifest is not valid JSON", e)
            }
            val entries = root as? kotlinx.serialization.json.JsonArray
                ?: throw IllegalArgumentException("Repository manifest must be a JSON array")
            if (entries.isEmpty() && !allowEmpty) {
                throw IllegalArgumentException("Repository manifest does not contain any plugins")
            }

            return entries.mapIndexed { index, element ->
                val objectValue = element as? JsonObject
                    ?: throw IllegalArgumentException("Plugin entry $index must be an object")
                val id = objectValue.requiredRepositoryString("id", index)
                val plugin = try {
                    repositoryJson.decodeFromJsonElement<JsPlugin>(objectValue)
                } catch (e: Exception) {
                    throw IllegalArgumentException("Plugin entry $index is invalid", e)
                }
                require(isSafePluginId(id)) { "Plugin entry $index has an unsafe id" }
                listOf("name", "site", "lang", "version", "url", "iconUrl").forEach {
                    objectValue.requiredRepositoryString(it, index)
                }
                plugin
            }
        }

        private fun JsonObject.requiredRepositoryString(name: String, index: Int): String {
            val value = get(name) as? JsonPrimitive
            require(value?.isString == true && !value.contentOrNull.isNullOrBlank()) {
                "Plugin entry $index is missing a nonblank $name"
            }
            return value.content
        }

        internal fun isSafePluginId(pluginId: String): Boolean =
            pluginId.isNotEmpty() &&
                pluginId != "." &&
                pluginId != ".." &&
                pluginId.none { it == '/' || it == '\\' || it == '\u0000' }

        internal fun customAssetFileName(kind: String, content: String): String {
            require(kind == CUSTOM_JS_KIND || kind == CUSTOM_CSS_KIND)
            return "${Hash.sha256(content)}.custom.$kind"
        }

        internal fun isCustomAssetFileName(fileName: String): Boolean = CUSTOM_ASSET_FILE.matches(fileName)

        internal fun deduplicatePlugins(plugins: List<JsPlugin>): List<JsPlugin> {
            return plugins
                .groupBy { it.id }
                .map { (_, duplicates) ->
                    duplicates.reduce { newest, current ->
                        if (compareVersions(current.version, newest.version) > 0) current else newest
                    }
                }
        }

        private fun compareVersions(left: String, right: String): Int {
            val leftParts = left.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
            val rightParts = right.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
            repeat(maxOf(leftParts.size, rightParts.size)) { index ->
                val compared = leftParts.getOrElse(index) { 0 }.compareTo(rightParts.getOrElse(index) { 0 })
                if (compared != 0) return compared
            }
            return 0
        }

        /**
         * Combines what the runtime reported about a plugin with the fields only its listing knows.
         *
         * [engine] wins on everything the plugin declares about itself, because that code is what
         * actually runs while a listing is a catalogue entry that can describe a version which was
         * never installed. Where to download the plugin from, its icon, which repository it came
         * from, its language and the local names of its custom assets are not the code's to
         * declare, so those survive from [stored].
         */
        internal fun mergePluginMetadata(engine: JsPlugin, stored: JsPlugin?): JsPlugin {
            stored ?: return engine
            return engine.copy(
                url = stored.url,
                iconUrl = stored.iconUrl,
                lang = stored.lang,
                customCSS = stored.customCSS,
                customJS = stored.customJS,
                customCSSFile = stored.customCSSFile,
                customJSFile = stored.customJSFile,
                repositoryUrl = stored.repositoryUrl,
            )
        }
    }

    private val networkHelper: NetworkHelper = Injekt.get()
    private val sourcePreferences: SourcePreferences = Injekt.get()
    private val storageManager: StorageManager = Injekt.get()
    private val hermesRuntime: JsRuntime = Injekt.get()

    // Plugin repo/list/icon fetches, not a source's own content requests (JsSource routes
    // through JSLibraryProvider's fetch() instead) - not paced by novel-source throttling.
    private val client: OkHttpClient get() = networkHelper.client.rateLimitExempt()
    private val json = repositoryJson

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Storage directories
    private val pluginsDir: UniFile?
        get() = storageManager.getLNReaderPluginsDirectory()
            ?: run {
                // SAF/base storage may be unavailable on some devices/configurations.
                // Fall back to app-private storage so plugin install/import still works.
                val fallbackDir = File(context.filesDir, "lnreader_plugins").apply { mkdirs() }
                UniFile.fromFile(fallbackDir)
            }

    private val cacheDir: File = File(context.cacheDir, "lnreader_plugins_cache")

    // Persistent icon cache — stored in filesDir so Android won't purge it (cacheDir is ephemeral)
    private val iconsCacheDir: File
        get() = File(context.filesDir, "lnreader_icons_cache").apply { mkdirs() }

    // State
    private val _repositories = MutableStateFlow<List<JsPluginRepository>>(emptyList())
    val repositories: StateFlow<List<JsPluginRepository>> = _repositories.asStateFlow()

    private val _availablePlugins = MutableStateFlow<List<JsPlugin>>(emptyList())
    val availablePlugins: StateFlow<List<JsPlugin>> = _availablePlugins.asStateFlow()

    private val _installedPlugins = MutableStateFlow<List<InstalledJsPlugin>>(emptyList())
    val installedPlugins: StateFlow<List<InstalledJsPlugin>> = _installedPlugins.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // True once the first installed-plugin scan has completed.
    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private val refreshMutex = Mutex()
    private val repositoryMutex = Mutex()
    private val pluginMutationMutex = Mutex()

    private val _jsSources = MutableStateFlow<List<CatalogueSource>>(emptyList())
    val jsSources: StateFlow<List<CatalogueSource>> = _jsSources.asStateFlow()

    init {
        cacheDir.mkdirs()
        loadRepositoriesFromPrefs()
        scope.launch {
            loadInstalledPlugins()
            loadCachedPluginList()
        }

        storageManager.changes
            .onEach {
                logcat(LogPriority.INFO) { "JsPluginManager: storage changed, reloading plugins" }
                loadInstalledPlugins()
            }
            .launchIn(scope)
    }

    private fun saveCachedPluginList(plugins: List<JsPlugin>) {
        try {
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            }
            val cacheFile = File(cacheDir, "plugin_list_cache.json")
            cacheFile.parentFile?.mkdirs()
            val jsonString = json.encodeToString(plugins)
            cacheFile.writeText(jsonString)
            logcat(LogPriority.DEBUG) { "Saved ${plugins.size} plugins to cache" }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to save plugin list cache" }
        }
    }

    private fun loadCachedPluginList() {
        try {
            val cacheFile = File(cacheDir, "plugin_list_cache.json")
            if (cacheFile.exists()) {
                val jsonString = cacheFile.readText()
                val plugins = json.decodeFromString<List<JsPlugin>>(jsonString)
                _availablePlugins.value = plugins
                logcat(LogPriority.DEBUG) { "Loaded ${plugins.size} plugins from cache" }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to load plugin list cache" }
        }
    }

    /**
     * Refresh available plugins from all repositories
     */
    suspend fun refreshAvailablePlugins(forceRefresh: Boolean = false) = refreshMutex.withLock {
        _isLoading.value = true
        try {
            // Load from cache first if not forcing refresh
            if (!forceRefresh && _availablePlugins.value.isNotEmpty()) {
                logcat(LogPriority.DEBUG) { "Using cached plugin list (${_availablePlugins.value.size} plugins)" }
                return@withLock
            }

            val allPlugins = mutableListOf<JsPlugin>()

            for (repo in _repositories.value.filter { it.enabled }) {
                try {
                    val plugins = fetchPluginList(repo.url, allowEmpty = true)
                    plugins.forEach { it.repositoryUrl = repo.url }
                    allPlugins.addAll(plugins)
                    logcat(LogPriority.DEBUG) { "Loaded ${plugins.size} plugins from ${repo.name}" }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "Failed to fetch plugins from ${repo.name}" }
                }
            }

            val dedupedPlugins = deduplicatePlugins(allPlugins)
            _availablePlugins.value = dedupedPlugins

            // Save to cache file
            saveCachedPluginList(dedupedPlugins)

            // Cache icons to avoid re-fetching each time
            cacheIcons(dedupedPlugins)
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * Fetch plugin list from a repository URL
     */
    private suspend fun fetchPluginList(
        url: String,
        allowEmpty: Boolean,
    ): List<JsPlugin> = withContext(Dispatchers.IO) {
        val response = client.newCall(GET(url)).execute()
        response.use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("Repository request failed with HTTP ${resp.code}")
            }
            val body = resp.body.string()
            if (body.isBlank()) {
                throw IllegalStateException("Repository response body is empty")
            }
            decodeRepositoryManifest(body, allowEmpty)
        }
    }

    /**
     * Install a plugin by downloading and caching its code
     */
    suspend fun installPlugin(plugin: JsPlugin, repositoryUrl: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val dir = pluginsDir ?: throw Exception("Plugin directory not available")
            require(isSafePluginId(plugin.id)) { "Unsafe plugin id: ${plugin.id}" }
            val previousAssets = _installedPlugins.value
                .find { it.plugin.id == plugin.id }
                ?.customAssetFiles()
                .orEmpty()

            // Fetch every required file before replacing the installed version. A failed update
            // therefore leaves the previous plugin and its custom assets usable.
            val code = downloadText(plugin.url)
            require(code.isNotBlank()) { "Downloaded plugin code is empty" }
            val engineMetadata = inspectPlugin(code, plugin.id, validateId = true)
            if (engineMetadata.version != plugin.version) {
                // Not a failure. The listing is a catalogue entry; the code is the thing being
                // installed, so it decides what this plugin now is.
                logcat(LogPriority.WARN) {
                    "Repository lists ${plugin.id} as v${plugin.version}, its code reports " +
                        "v${engineMetadata.version}; recording the code's version"
                }
            }
            val customJS = plugin.customJS
                ?.takeIf(String::isNotBlank)
                ?.let(::downloadText)
            val customCSS = plugin.customCSS
                ?.takeIf(String::isNotBlank)
                ?.let(::downloadText)
            val installedMetadata = mergePluginMetadata(
                engine = engineMetadata,
                stored = plugin.copy(
                    customCSSFile = customCSS?.let { customAssetFileName(CUSTOM_CSS_KIND, it) },
                    customJSFile = customJS?.let { customAssetFileName(CUSTOM_JS_KIND, it) },
                    repositoryUrl = repositoryUrl,
                ),
            )

            installedMetadata.customJSFile?.let {
                writeCustomAsset(dir, it, customJS.orEmpty())
            }
            installedMetadata.customCSSFile?.let {
                writeCustomAsset(dir, it, customCSS.orEmpty())
            }
            val pluginFile = dir.replaceFile("${plugin.id}.js") ?: throw Exception("Failed to create plugin file")
            pluginFile.writeUtf8(code)

            val metadataFile = dir.replaceFile("${plugin.id}.json") ?: throw Exception("Failed to create metadata file")
            val installedPlugin = InstalledJsPlugin(
                plugin = installedMetadata,
                code = code,
                customCSS = customCSS.orEmpty(),
                customJS = customJS.orEmpty(),
                installedVersion = installedMetadata.version,
                repositoryUrl = repositoryUrl,
            )
            val metadataJson = json.encodeToString(installedPlugin.plugin)
            logcat(LogPriority.INFO) { "Saving metadata for ${plugin.id}: ${metadataJson.take(200)}" }
            metadataFile.writeText(metadataJson)

            // Update state
            _installedPlugins.update { current ->
                current.filter { it.plugin.id != plugin.id } + installedPlugin
            }
            cleanupCustomAssets(dir, previousAssets)

            // Rebuild sources
            rebuildSources()

            // Auto-enable the plugin's language so the source appears in Novel Sources tab
            val langCode = installedMetadata.langCode()
            if (langCode.isNotEmpty()) {
                val currentLangs = sourcePreferences.enabledLanguages.get()
                if (langCode !in currentLangs) {
                    sourcePreferences.enabledLanguages.set(currentLangs + langCode)
                    logcat(LogPriority.INFO) { "Auto-enabled language '$langCode' for ${installedMetadata.name}" }
                }
            }

            logcat(LogPriority.INFO) { "Installed plugin: ${installedMetadata.name} v${installedMetadata.version}" }
            true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to install plugin ${plugin.name}" }
            false
        }
    }

    /**
     * Uninstall a plugin
     */
    suspend fun uninstallPlugin(pluginId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            require(isSafePluginId(pluginId)) { "Unsafe plugin id: $pluginId" }
            val dir = pluginsDir ?: return@withContext false
            val previousAssets = _installedPlugins.value
                .find { it.plugin.id == pluginId }
                ?.customAssetFiles()
                .orEmpty()

            // Delete files
            dir.findFile("$pluginId.js")?.delete()
            dir.findFile("$pluginId.json")?.delete()

            // Update state
            _installedPlugins.update { current ->
                current.filter { it.plugin.id != pluginId }
            }
            cleanupCustomAssets(dir, previousAssets)

            // Rebuild sources
            rebuildSources()

            logcat(LogPriority.INFO) { "Uninstalled plugin: $pluginId" }
            true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to uninstall plugin $pluginId" }
            false
        }
    }

    /**
     * Check if a plugin has an update available
     */
    fun hasUpdate(installedPlugin: InstalledJsPlugin): Boolean {
        val available = _availablePlugins.value.find { it.id == installedPlugin.plugin.id }
        return available != null && available.version != installedPlugin.installedVersion
    }

    suspend fun installPluginFromBackup(
        plugin: JsPlugin,
        code: String,
        customJs: String?,
        customCss: String?,
        repositoryUrl: String?,
    ): BackupPluginInstallResult = withContext(Dispatchers.IO) {
        pluginMutationMutex.withLock {
            require(code.isNotBlank()) { "Plugin code is empty" }
            require(isSafePluginId(plugin.id)) { "Unsafe plugin id: ${plugin.id}" }

            val existing = _installedPlugins.value.find { it.plugin.id == plugin.id }
            if (existing != null && compareVersions(existing.installedVersion, plugin.version) > 0) {
                return@withLock BackupPluginInstallResult(existing.plugin, installed = false)
            }

            val dir = pluginsDir ?: error("Plugin directory not available")
            val previousAssets = existing?.customAssetFiles().orEmpty()
            val installedMetadata = plugin.copy(
                customJSFile = customJs?.takeIf(String::isNotBlank)?.let {
                    customAssetFileName(CUSTOM_JS_KIND, it)
                },
                customCSSFile = customCss?.takeIf(String::isNotBlank)?.let {
                    customAssetFileName(CUSTOM_CSS_KIND, it)
                },
                repositoryUrl = repositoryUrl,
            )
            val newAssets = setOfNotNull(installedMetadata.customJSFile, installedMetadata.customCSSFile)

            installedMetadata.customJSFile?.let { writeCustomAsset(dir, it, customJs.orEmpty()) }
            installedMetadata.customCSSFile?.let { writeCustomAsset(dir, it, customCss.orEmpty()) }
            try {
                commitBackupPluginFiles(dir, plugin.id, code, json.encodeToString(installedMetadata), existing)
            } catch (error: Exception) {
                cleanupCustomAssets(dir, newAssets - previousAssets)
                throw error
            }

            val installed = InstalledJsPlugin(
                plugin = installedMetadata,
                code = code,
                customCSS = customCss.orEmpty(),
                customJS = customJs.orEmpty(),
                installedVersion = plugin.version,
                repositoryUrl = repositoryUrl.orEmpty(),
            )
            _installedPlugins.update { current -> current.filter { it.plugin.id != plugin.id } + installed }
            cleanupCustomAssets(dir, previousAssets - newAssets)
            rebuildSources()
            BackupPluginInstallResult(installedMetadata, installed = true)
        }
    }

    private fun commitBackupPluginFiles(
        dir: UniFile,
        pluginId: String,
        code: String,
        metadata: String,
        existing: InstalledJsPlugin?,
    ) {
        val suffix = System.nanoTime().toString(16)
        val stagedCode = dir.replaceFile(".$pluginId-$suffix.js.tmp") ?: error("Failed to stage plugin code")
        val stagedMetadata = dir.replaceFile(".$pluginId-$suffix.json.tmp") ?: error("Failed to stage plugin metadata")
        val previousCode = dir.findFile("$pluginId.js")?.let { file -> runCatching { file.readUtf8() }.getOrNull() }
            ?: existing?.code
        val previousMetadata =
            dir.findFile("$pluginId.json")?.let { file -> runCatching { file.readText() }.getOrNull() }
                ?: existing?.plugin?.let { json.encodeToString(it) }
        try {
            stagedCode.writeUtf8(code)
            stagedMetadata.writeText(metadata)
            check(stagedCode.readUtf8() == code && stagedMetadata.readText() == metadata) {
                "Staged plugin files failed verification"
            }
            dir.replaceFile("$pluginId.js")?.writeUtf8(code) ?: error("Failed to write plugin code")
            dir.replaceFile("$pluginId.json")?.writeText(metadata) ?: error("Failed to write plugin metadata")
        } catch (error: Exception) {
            restorePluginFile(dir, "$pluginId.js", previousCode)
            restorePluginFile(dir, "$pluginId.json", previousMetadata)
            throw error
        } finally {
            stagedCode.delete()
            stagedMetadata.delete()
        }
    }

    private fun restorePluginFile(dir: UniFile, name: String, content: String?) {
        if (content == null) {
            dir.findFile(name)?.delete()
        } else {
            runCatching { dir.replaceFile(name)?.writeText(content) }
                .onFailure { logcat(LogPriority.ERROR, it) { "Failed to roll back $name" } }
        }
    }

    /**
     * Reports what [code] says about itself, without disturbing an already installed copy.
     *
     * Runs under a throwaway runtime key, so inspecting a candidate cannot replace the context a
     * running source is working with, and an install that fails afterwards leaves that context as
     * it was.
     */
    suspend fun inspectBackupPlugin(code: String, fallbackId: String): JsPlugin {
        return inspectPlugin(code, fallbackId, validateId = false)
    }

    private suspend fun inspectPlugin(code: String, pluginId: String, validateId: Boolean): JsPlugin {
        val runtimeKey = "inspect:$pluginId:${System.nanoTime()}"
        return try {
            loadPluginMetadata(pluginId, code, runtimeKey, validateId)
        } finally {
            val unloadPayload = buildJsonObject {
                put("id", pluginId)
                put("key", runtimeKey)
            }
            runCatching { hermesRuntime.call("plugin.unload", json.encodeToString(unloadPayload)) }
        }
    }

    /** Loads [code] into the runtime under [runtimeKey] and returns what it reports about itself. */
    private suspend fun loadPluginMetadata(
        pluginId: String,
        code: String,
        runtimeKey: String,
        validateId: Boolean = true,
    ): JsPlugin {
        val payload = buildJsonObject {
            put("id", pluginId)
            put("key", runtimeKey)
            put("code", code)
            put("validateId", validateId)
        }
        return json.decodeFromString<JsPlugin>(hermesRuntime.call("plugin.load", json.encodeToString(payload)))
            .let { it.copy(id = it.id.ifBlank { pluginId }, name = it.name.ifBlank { pluginId }) }
    }

    /**
     * Update a plugin to the latest version
     */
    suspend fun updatePlugin(installedPlugin: InstalledJsPlugin): Boolean {
        val available = _availablePlugins.value.find { it.id == installedPlugin.plugin.id }
            ?: return false
        return installPlugin(available, installedPlugin.repositoryUrl)
    }

    /**
     * Add a new repository
     */
    suspend fun addRepository(url: String): Result<Unit> {
        return try {
            val normalizedUrl = validateRepositoryUrl(url)
            repositoryMutex.withLock {
                require(_repositories.value.none { it.url == normalizedUrl }) {
                    "Repository already exists"
                }
                fetchPluginList(normalizedUrl, allowEmpty = false)
                val name = JsPluginRepository.nameFromUrl(normalizedUrl)
                logcat(LogPriority.INFO) {
                    "JsPluginManager: adding new repo — name='$name', url='$normalizedUrl'"
                }
                _repositories.update { current ->
                    require(current.none { it.url == normalizedUrl }) {
                        "Repository already exists"
                    }
                    current + JsPluginRepository(name, normalizedUrl)
                }
                saveRepositories()
            }
            scope.launch { refreshAvailablePlugins(forceRefresh = true) }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to add JS plugin repository '$url'" }
            Result.failure(e)
        }
    }

    suspend fun restoreRepositories(repositories: List<JsPluginRepository>) {
        if (repositories.isEmpty()) return
        val restored = repositories
            .map { it.copy(url = normalizeRepositoryUrl(it.url)) }
            .filter { it.url.isNotBlank() }
            .distinctBy { it.url }
        _repositories.update { current ->
            val restoredUrls = restored.mapTo(mutableSetOf()) { it.url }
            current.filterNot { normalizeRepositoryUrl(it.url) in restoredUrls } + restored
        }
        saveRepositories()
        refreshAvailablePlugins(forceRefresh = true)
    }

    /**
     * Remove a repository
     */
    fun removeRepository(url: String) {
        _repositories.update { current ->
            current.filter { it.url != url }
        }
        saveRepositories()
    }

    /**
     * Toggle repository enabled state
     */
    fun setRepositoryEnabled(url: String, enabled: Boolean) {
        _repositories.update { current ->
            current.map {
                if (it.url == url) it.copy(enabled = enabled) else it
            }
        }
        saveRepositories()
    }

    /**
     * Get all JS sources as CatalogueSources
     */
    fun getSources(): List<CatalogueSource> = _jsSources.value

    /**
     * Get a specific source by ID
     */
    fun getSource(sourceId: Long): CatalogueSource? {
        return _jsSources.value.find { it.id == sourceId }
    }

    fun contentWarningForSource(sourceId: Long?): Int? {
        return sourceId?.let { id ->
            _installedPlugins.value.firstOrNull { it.plugin.sourceId() == id }?.plugin?.contentWarning
        }
    }

    fun iconUrlForSource(sourceId: Long): String? {
        return _installedPlugins.value.firstOrNull { it.plugin.sourceId() == sourceId }?.plugin?.iconUrl
    }

    // Private helpers

    private fun loadInstalledPlugins() {
        scope.launch {
            try {
                val dir = pluginsDir
                if (dir == null) {
                    logcat(LogPriority.WARN) { "Plugins directory not available - storage may not be configured" }
                    return@launch
                }

                logcat(LogPriority.DEBUG) { "Loading installed plugins from: ${dir.uri}" }

                val allFiles = dir.listFiles()?.toList() ?: emptyList()
                val jsFiles = allFiles.filter {
                    it.name?.endsWith(".js") == true && !isCustomAssetFileName(it.name.orEmpty())
                }
                val jsonFiles = allFiles.filter { it.name?.endsWith(".json") == true }
                logcat(LogPriority.DEBUG) {
                    "Found ${jsFiles.size} .js files and ${jsonFiles.size} .json files in plugins directory"
                }
                val jsonByName = jsonFiles.associateBy { it.name?.substringBeforeLast(".") }

                // Booted once, up front. Asking per plugin would make a runtime that is down cost
                // one ready-timeout each instead of one for the whole scan.
                val runtimeAvailable = runCatching { hermesRuntime.start() }
                    .onFailure {
                        logcat(LogPriority.ERROR, it) {
                            "JS runtime unavailable; falling back to cached plugin metadata"
                        }
                    }
                    .isSuccess

                val plugins = jsFiles.mapNotNull { file ->
                    try {
                        var code = file.readUtf8()
                        if (code.isBlank()) {
                            logcat(LogPriority.WARN) { "Plugin file ${file.name} is empty, skipping" }
                            return@mapNotNull null
                        }
                        val nameWithoutExtension = file.name?.substringBeforeLast(".") ?: return@mapNotNull null
                        // The sidecar only caches what the runtime last reported, and the runtime is
                        // asked again below, so a stale SAF entry or corrupt metadata is not fatal.
                        val stored = jsonByName[nameWithoutExtension]?.let { metadataFile ->
                            try {
                                metadataFile.openInputStream().bufferedReader().readText()
                                    .takeIf { it.isNotBlank() && it.trim().startsWith("{") }
                                    ?.let { json.decodeFromString<JsPlugin>(it) }
                            } catch (e: Exception) {
                                logcat(LogPriority.WARN, e) { "Failed to read metadata for $nameWithoutExtension" }
                                null
                            }
                        }

                        // Auto-heal: if the plugin code looks truncated/incomplete, try re-download once.
                        // A common symptom is missing the final `exports.default = ...` assignment.
                        val downloadUrl = stored?.url.orEmpty()
                        if (!code.contains("exports.default") && downloadUrl.isNotBlank()) {
                            logcat(LogPriority.WARN) {
                                "Plugin '$nameWithoutExtension' code looks incomplete (len=${code.length}); re-downloading from $downloadUrl"
                            }
                            try {
                                val response = client.newCall(GET(downloadUrl)).execute()
                                response.use { resp ->
                                    if (resp.isSuccessful) {
                                        val fresh = resp.body.string()
                                        if (fresh.isNotBlank() && fresh.contains("exports.default")) {
                                            dir.replaceFile("$nameWithoutExtension.js")?.writeUtf8(fresh)
                                            code = fresh
                                            logcat(LogPriority.INFO) {
                                                "Re-downloaded plugin '$nameWithoutExtension' successfully (len=${fresh.length})"
                                            }
                                        } else {
                                            logcat(LogPriority.WARN) {
                                                "Re-download for '$nameWithoutExtension' returned unexpected content (len=${fresh.length})"
                                            }
                                        }
                                    } else {
                                        logcat(LogPriority.WARN) {
                                            "Re-download failed for '$nameWithoutExtension': HTTP ${resp.code}"
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                logcat(LogPriority.ERROR, e) { "Re-download failed for '$nameWithoutExtension'" }
                            }
                        }

                        // Loaded under the plugin's own id, which is the key its JsSource will use,
                        // so this is the context the source goes on to work with rather than a
                        // throwaway one. What the code reports about itself wins over the sidecar:
                        // the sidecar is a copy of a repository listing, which can describe a
                        // version that was never the one written to disk.
                        val engine = if (runtimeAvailable) {
                            runCatching { loadPluginMetadata(nameWithoutExtension, code, nameWithoutExtension) }
                                .onFailure {
                                    logcat(LogPriority.WARN, it) { "Runtime could not load '$nameWithoutExtension'" }
                                }
                                .getOrNull()
                        } else {
                            null
                        }
                        // Without the runtime there is still a source list to build. Degrading to the
                        // last known metadata costs browsing accuracy; dropping the plugin would cost
                        // the library entry and its downloads.
                        var plugin = when {
                            engine != null -> mergePluginMetadata(engine, stored)
                            stored != null -> stored
                            else -> extractPluginInfo(code, nameWithoutExtension)
                        }

                        val (customCSSFile, customCSS) = loadCustomAsset(
                            dir = dir,
                            fileName = plugin.customCSSFile,
                            kind = CUSTOM_CSS_KIND,
                            url = plugin.customCSS,
                        )
                        val (customJSFile, customJS) = loadCustomAsset(
                            dir = dir,
                            fileName = plugin.customJSFile,
                            kind = CUSTOM_JS_KIND,
                            url = plugin.customJS,
                        )
                        if (customCSSFile != plugin.customCSSFile || customJSFile != plugin.customJSFile) {
                            plugin = plugin.copy(
                                customCSSFile = customCSSFile,
                                customJSFile = customJSFile,
                            )
                        }
                        if (plugin != stored) {
                            dir.replaceFile("$nameWithoutExtension.json")
                                ?.writeText(json.encodeToString(plugin))
                        }

                        InstalledJsPlugin(
                            plugin = plugin,
                            code = code,
                            customCSS = customCSS,
                            customJS = customJS,
                            installedVersion = plugin.version,
                            repositoryUrl = plugin.repositoryUrl ?: "",
                        )
                    } catch (e: Exception) {
                        logcat(LogPriority.ERROR, e) { "Failed to load plugin: ${file.name}" }
                        null
                    }
                }

                _installedPlugins.value = plugins
                cleanupCustomAssets(dir)
                rebuildSources()

                logcat(LogPriority.INFO) { "Loaded ${plugins.size} installed JS plugins" }
            } finally {
                // Unblock startup consumers waiting for the first JS source scan.
                _isInitialized.value = true
            }
        }
    }

    private fun extractPluginInfo(code: String, fallbackId: String): JsPlugin {
        // Try to extract plugin info from the minified JS code
        val idRegex = """this\.id\s*=\s*["']([^"']+)["']""".toRegex()
        val nameRegex = """this\.name\s*=\s*["']([^"']+)["']""".toRegex()
        val versionRegex = """this\.version\s*=\s*["']([^"']+)["']""".toRegex()

        val id = idRegex.find(code)?.groupValues?.get(1) ?: fallbackId
        val name = nameRegex.find(code)?.groupValues?.get(1) ?: fallbackId
        val site = resolveJsPluginSite(metadataSite = null, code = code)
        val version = versionRegex.find(code)?.groupValues?.get(1) ?: "1.0.0"

        return JsPlugin(
            id = id,
            name = name,
            site = site,
            lang = "English",
            version = version,
            url = "",
            iconUrl = "",
        )
    }

    private fun rebuildSources() {
        val oldSources = _jsSources.value.filterIsInstance<JsSource>()
        val oldById = oldSources.associateBy { it.id }
        // Rebuilds fire on every storage change; recreating untouched sources kills their in-flight calls.
        val sources = _installedPlugins.value.map { installedPlugin ->
            val existing = oldById[installedPlugin.plugin.sourceId()]
            if (existing != null && existing.isSamePlugin(installedPlugin)) {
                existing
            } else {
                JsSource(installedPlugin)
            }
        }
        logcat(LogPriority.INFO) {
            "JsPluginManager: rebuildSources() - emitting ${sources.size} sources to jsSources StateFlow"
        }
        _jsSources.value = sources
        logcat(LogPriority.INFO) {
            "JsPluginManager: rebuildSources() - _jsSources.value updated, new count: ${_jsSources.value.size}"
        }

        // Release the contexts of plugins that are gone, not of instances that were merely replaced.
        // A replacement claims the same runtime key, so unloading the instance it replaced would
        // tear down the context the new one has just taken over.
        val liveKeys = sources.mapTo(mutableSetOf()) { it.runtimeKey }
        val removed = oldSources.filterNot { it.runtimeKey in liveKeys }
        if (removed.isNotEmpty()) {
            scope.launch {
                removed.forEach { source ->
                    runCatching { source.releaseRuntime() }
                }
            }
        }
    }

    /**
     * Load repositories from SharedPreferences.
     */
    private fun loadRepositoriesFromPrefs() {
        try {
            val json2 = sourcePreferences.jsRepositoriesBackup.get()
            if (json2.isNotBlank()) {
                val repos = json.decodeFromString<List<JsPluginRepository>>(json2)
                _repositories.value = repos.distinctBy { it.url }
                logcat(LogPriority.INFO) { "Loaded ${repos.size} repositories from SharedPreferences backup" }
            } else {
                logcat(LogPriority.DEBUG) { "No SharedPreferences repos backup found" }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to load repositories from SharedPreferences" }
        }
    }

    private fun saveRepositories() {
        try {
            val jsonContent = json.encodeToString(_repositories.value)
            sourcePreferences.jsRepositoriesBackup.set(jsonContent)
            logcat(LogPriority.DEBUG) { "Saved ${_repositories.value.size} repositories to SharedPreferences" }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to save repositories to SharedPreferences" }
        }
    }

    /**
     * Group plugins by language
     */
    fun getPluginsByLanguage(): Map<String, List<JsPlugin>> {
        return _availablePlugins.value.groupBy { it.lang }
    }

    /**
     * Filter plugins by search query
     */
    fun searchPlugins(query: String): List<JsPlugin> {
        if (query.isBlank()) return _availablePlugins.value

        val lowerQuery = query.lowercase()
        return _availablePlugins.value.filter { plugin ->
            plugin.name.lowercase().contains(lowerQuery) ||
                plugin.site.lowercase().contains(lowerQuery) ||
                plugin.id.lowercase().contains(lowerQuery)
        }
    }

    /**
     * Check if a plugin is installed
     */
    fun isInstalled(pluginId: String): Boolean {
        return _installedPlugins.value.any { it.plugin.id == pluginId }
    }

    /**
     * Get installed plugin by ID
     */
    fun getInstalledPlugin(pluginId: String): InstalledJsPlugin? {
        return _installedPlugins.value.find { it.plugin.id == pluginId }
    }

    // Icon caching

    /**
     * Returns the local cached icon file for a plugin, or null if not cached.
     */
    fun getCachedIconFile(pluginId: String): File? {
        if (!isSafePluginId(pluginId)) return null
        val file = File(iconsCacheDir, "$pluginId.png")
        return file.takeIf { it.exists() && it.length() > 0 }
    }

    /**
     * Returns the local icon path if cached, otherwise the original URL.
     * This avoids re-fetching icons on every screen visit.
     */
    fun getIconUrl(plugin: JsPlugin): String {
        val cached = getCachedIconFile(plugin.id)
        return cached?.let { "file://${it.absolutePath}" } ?: plugin.iconUrl
    }

    /**
     * Download and cache icons for a list of plugins in the background.
     */
    private suspend fun cacheIcons(plugins: List<JsPlugin>) = withContext(Dispatchers.IO) {
        for (plugin in plugins) {
            if (plugin.iconUrl.isBlank() || !isSafePluginId(plugin.id)) continue
            val iconFile = File(iconsCacheDir, "${plugin.id}.png")
            if (iconFile.exists() && iconFile.length() > 0) continue
            try {
                val response = client.newCall(GET(plugin.iconUrl)).execute()
                response.use { resp ->
                    if (resp.isSuccessful) {
                        resp.body.byteStream().use { input ->
                            iconFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                logcat(LogPriority.WARN) { "Failed to cache icon for ${plugin.name}: ${e.message}" }
            }
        }
    }

    private fun downloadText(url: String): String {
        val cacheBustedUrl = url.toHttpUrl()
            .newBuilder()
            .addQueryParameter("t", System.currentTimeMillis().toString())
            .build()
        return client.newCall(GET(cacheBustedUrl, cache = CacheControl.FORCE_NETWORK)).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code} while downloading $url" }
            response.body.string()
        }
    }

    private fun writeCustomAsset(dir: UniFile, fileName: String, content: String) {
        val kind = fileName.substringAfterLast("custom").removePrefix(".").removePrefix("-")
        require(fileName == customAssetFileName(kind, content))

        val existing = dir.findFile(fileName)
        if (existing != null && runCatching { existing.readUtf8() == content }.getOrDefault(false)) return

        val file = dir.replaceFile(fileName) ?: error("Failed to create custom plugin asset")
        file.writeUtf8(content)
    }

    private fun loadCustomAsset(
        dir: UniFile,
        fileName: String?,
        kind: String,
        url: String?,
    ): Pair<String?, String> {
        readCustomAsset(dir, fileName, kind)?.let { return fileName to it }
        val remoteUrl = url?.takeIf(String::isNotBlank) ?: return null to ""
        return runCatching {
            val content = downloadText(remoteUrl)
            val downloadedFileName = customAssetFileName(kind, content)
            writeCustomAsset(dir, downloadedFileName, content)
            downloadedFileName to content
        }.getOrElse {
            logcat(LogPriority.WARN, it) { "Failed to restore custom plugin $kind asset from $remoteUrl" }
            null to ""
        }
    }

    private fun readCustomAsset(dir: UniFile, fileName: String?, kind: String): String? {
        if (fileName == null) return null
        if (!isCustomAssetFileName(fileName) ||
            !(fileName.endsWith(".custom.$kind") || fileName.endsWith(".custom-$kind"))
        ) {
            logcat(LogPriority.WARN) { "Ignoring invalid custom plugin asset name: $fileName" }
            return null
        }

        val content = runCatching { dir.findFile(fileName)?.readUtf8() }
            .getOrElse {
                logcat(LogPriority.WARN, it) { "Failed to read custom plugin asset: $fileName" }
                null
            }
            ?: return null
        val hash = Hash.sha256(content)
        if (fileName != "$hash.custom.$kind" && fileName != "$hash.custom-$kind") {
            logcat(LogPriority.WARN) { "Ignoring custom plugin asset with mismatched hash: $fileName" }
            return null
        }
        return content
    }

    private fun InstalledJsPlugin.customAssetFiles(): Set<String> {
        return setOfNotNull(plugin.customCSSFile, plugin.customJSFile)
            .filterTo(mutableSetOf(), ::isCustomAssetFileName)
    }

    private fun cleanupCustomAssets(dir: UniFile, candidates: Set<String>? = null) {
        if (candidates?.isEmpty() == true) return
        val referenced = _installedPlugins.value.flatMapTo(mutableSetOf()) { it.customAssetFiles() }
        dir.listFiles()
            ?.asSequence()
            ?.filter { file ->
                val name = file.name ?: return@filter false
                isCustomAssetFileName(name) &&
                    name !in referenced &&
                    (candidates == null || name in candidates)
            }
            ?.forEach { it.delete() }
    }

    // UniFile helpers

    /**
     * Delete-then-create to guarantee truncation. SAF "w" mode does not truncate on many
     * providers, so a shorter overwrite leaves stale tail bytes (permanent SyntaxError for .js).
     */
    private fun UniFile.replaceFile(name: String): UniFile? {
        findFile(name)?.delete()
        var attempts = 0
        var delayMs = 20L
        while (true) {
            if (findFile(name) == null) {
                createExactFile(name)?.let { return it }
            }
            attempts++
            if (attempts >= 5) {
                logcat(LogPriority.WARN) {
                    "replaceFile: $name still contested after $attempts retries"
                }
                return null
            }
            Thread.sleep(delayMs)
            delayMs *= 2
        }
    }

    private fun UniFile.createExactFile(name: String): UniFile? {
        val created = runCatching {
            if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
                DocumentsContract.createDocument(
                    context.contentResolver,
                    uri,
                    "application/octet-stream",
                    name,
                )?.let { UniFile.fromUri(context, it) }
            } else {
                createFile(name)
            }
        }.getOrNull()
        return created?.takeIf { it.name == name } ?: run {
            created?.delete()
            null
        }
    }

    private fun UniFile.readText(): String {
        return this.openInputStream().use { it.reader().readText() }
    }

    private fun UniFile.writeText(text: String) {
        this.openOutputStream().use { output ->
            val writer = output.bufferedWriter(StandardCharsets.UTF_8)
            writer.write(text)
            writer.flush()
        }
        logcat(LogPriority.DEBUG) { "Wrote ${text.length} chars to ${this.name}" }
    }

    private fun UniFile.readUtf8(): String {
        return this.openInputStream().use { input ->
            val bytes = input.readBytes()
            String(bytes, StandardCharsets.UTF_8)
        }
    }

    private fun UniFile.writeUtf8(text: String) {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        this.openOutputStream().use { output ->
            output.write(bytes)
        }
    }
}
