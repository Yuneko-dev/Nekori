package eu.kanade.tachiyomi.data.translation

import android.content.Context
import eu.kanade.tachiyomi.data.download.ChapterContentReader
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.translation.engine.LibreTranslateEngine
import eu.kanade.tachiyomi.source.awaitSource
import eu.kanade.tachiyomi.source.isNovelSource
import eu.kanade.tachiyomi.source.model.Page
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.translation.model.TranslatedChapter
import tachiyomi.domain.translation.model.TranslationContext
import tachiyomi.domain.translation.model.TranslationEngineId
import tachiyomi.domain.translation.model.TranslationLocator
import tachiyomi.domain.translation.model.TranslationProgress
import tachiyomi.domain.translation.model.TranslationPurpose
import tachiyomi.domain.translation.model.TranslationRequest
import tachiyomi.domain.translation.model.TranslationResult
import tachiyomi.domain.translation.model.TranslationStatus
import tachiyomi.domain.translation.model.TranslationTask
import tachiyomi.domain.translation.model.contextualAnchoringParagraphs
import tachiyomi.domain.translation.repository.TranslatedChapterRepository
import tachiyomi.domain.translation.service.TranslationPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.exp

/**
 * Service for managing translation queue and executing translations.
 *
 * Thread-safe: uses [ConcurrentHashMap] keyed by chapterId to avoid
 * the check-then-act race that existed with the old ConcurrentLinkedQueue.
 *
 * HTML utilities are delegated to [TranslationHtmlUtils].
 * Chapter content reading is delegated to [ChapterContentReader].
 */
class TranslationService(
    private val context: Context,
    private val sourceManager: SourceManager = Injekt.get(),
    private val translationPreferences: TranslationPreferences = Injekt.get(),
    private val translationEngineManager: TranslationEngineManager = Injekt.get(),
    private val downloadProvider: DownloadProvider = Injekt.get(),
    private val downloadCache: DownloadCache = Injekt.get(),
    private val translatedChapterRepository: TranslatedChapterRepository = Injekt.get(),
    private val getManga: GetManga = Injekt.get(),
    private val getChapter: GetChapter = Injekt.get(),
    private val updateChapter: tachiyomi.domain.chapter.interactor.UpdateChapter = Injekt.get(),
) {
    // Lazy-loaded to avoid circular dependency during initialization
    private val downloadManager: DownloadManager by lazy { Injekt.get() }
    private val chapterContentReader: ChapterContentReader by lazy { ChapterContentReader(context, downloadProvider) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Thread-safe queue keyed by chapterId — atomic putIfAbsent replaces check-then-act (fix 4.1)
    private val queueMap = ConcurrentHashMap<Long, TranslationTask>()

    // Stored in TranslationTask.id: FIFO tie-break for equal priorities
    private val enqueueSequence = AtomicLong(0)

    private val store = TranslationStore(context)
    private val saveMutex = Mutex()

    private val saveRequested = AtomicBoolean(false)

    // In-flight task (already polled off queueMap); persisted so process death mid-translation doesn't lose it
    @Volatile
    private var currentTask: TranslationTask? = null

    /**
     * The chapter ID currently being translated.
     * Exposed so the queue UI can highlight it (fix 6.2).
     */
    private val _currentTranslatingChapterId = MutableStateFlow<Long?>(null)
    val currentTranslatingChapterId = _currentTranslatingChapterId.asStateFlow()

    private val _queueState = MutableStateFlow<List<TranslationTask>>(emptyList())
    val queueState = combine(
        _queueState,
        translationPreferences.sourceLanguage().changes(),
        translationPreferences.targetLanguage().changes(),
    ) { queue, sourceLanguage, targetLanguage ->
        queue.map { it.copy(sourceLanguage = sourceLanguage, targetLanguage = targetLanguage) }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private var translationJob: Job? = null

    private val _progressState = MutableStateFlow(
        TranslationProgress(
            totalChapters = 0,
            completedChapters = 0,
            currentChapterName = null,
            currentChapterProgress = 0f,
            isRunning = false,
            isPaused = false,
        ),
    )
    val progressState = _progressState.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused = _isPaused.asStateFlow()

    init {
        // Restore persisted queue; synchronous so an early enqueue can't clobber the file before restore
        if (translationPreferences.translationEnabled().get()) {
            val saved = store.load()
            if (saved.isNotEmpty()) {
                saved.forEach { task -> queueMap.putIfAbsent(task.chapterId, task) }
                enqueueSequence.set(saved.maxOf { it.id })
                publishQueueState()
                TranslationJob.start(context)
            }
        }
    }

    /**
     * Add a chapter to the translation queue.
     * @param forceRetranslate if true, skip the "already translated" check.
     */
    fun enqueue(
        manga: Manga,
        chapter: Chapter,
        priority: Int = 0,
        forceRetranslate: Boolean = false,
    ) {
        if (!translationPreferences.translationEnabled().get()) return

        val task = TranslationTask(
            id = enqueueSequence.incrementAndGet(),
            chapterId = chapter.id,
            mangaId = manga.id,
            chapterName = chapter.name,
            mangaTitle = manga.title,
            sourceLanguage = translationPreferences.sourceLanguage().get(),
            targetLanguage = translationPreferences.targetLanguage().get(),
            priority = priority,
            status = TranslationStatus.QUEUED,
            retryCount = 0,
            forceRetranslate = forceRetranslate,
        )

        // Don't re-queue the chapter currently translating (auto-translate fires when
        // the download this service itself triggered completes)
        if (!forceRetranslate && chapter.id == currentTask?.chapterId) return

        // Atomic: only inserts if the key is absent (fix 4.1)
        if (queueMap.putIfAbsent(chapter.id, task) == null) {
            publishQueueState()
            start()
        }
    }

    /**
     * Add multiple chapters to the translation queue.
     * @param forceRetranslate if true, retranslate even if already translated.
     */
    fun enqueueAll(
        manga: Manga,
        chapters: List<Chapter>,
        priority: Int = 0,
        forceRetranslate: Boolean = false,
    ) {
        if (!translationPreferences.translationEnabled().get()) return

        // Oldest chapters first, same as Downloader.queueChapters
        chapters.sortedByDescending { it.sourceOrder }.forEach { chapter ->
            enqueue(manga, chapter, priority, forceRetranslate)
        }
    }

    /**
     * Remove a chapter from the queue.
     */
    fun dequeue(chapterId: Long) {
        queueMap.remove(chapterId)
        publishQueueState()
    }

    /**
     * Remove all queued chapters of a novel.
     */
    fun dequeueAllForManga(mangaId: Long) {
        val chapterIds = queueMap.values.filter { it.mangaId == mangaId }.map { it.chapterId }
        if (chapterIds.isEmpty()) return
        chapterIds.forEach { queueMap.remove(it) }
        publishQueueState()
    }

    /**
     * Move all queued chapters of a novel to the front of the queue
     * (within their priority band), preserving their relative order.
     */
    fun moveMangaToFront(mangaId: Long) {
        val tasks = queueMap.values.filter { it.mangaId == mangaId }.sortedBy { it.id }
        if (tasks.isEmpty()) return
        var nextId = queueMap.values.minOf { it.id } - tasks.size
        tasks.forEach { task -> queueMap[task.chapterId] = task.copy(id = nextId++) }
        publishQueueState()
    }

    /**
     * Clear all items from the queue.
     */
    fun clearQueue() {
        queueMap.clear()
        publishQueueState()
    }

    /**
     * Start processing the translation queue.
     */
    fun start() {
        if (translationJob?.isActive == true) return

        _isPaused.value = false

        translationJob = scope.launch {
            while (isActive && queueMap.isNotEmpty()) {
                if (_isPaused.value) {
                    delay(PAUSE_POLL_DELAY_MS)
                    continue
                }

                // Pick highest-priority task (fix 4.3)
                val polled = pollHighestPriority() ?: break
                // Languages follow current prefs, matching engine selection which resolves at translate time
                val task = polled.copy(
                    sourceLanguage = translationPreferences.sourceLanguage().get(),
                    targetLanguage = translationPreferences.targetLanguage().get(),
                )

                currentTask = task
                _currentTranslatingChapterId.value = task.chapterId

                try {
                    // Resolve chapter name for progress display
                    val chapterNameForProgress = try {
                        getChapter.await(task.chapterId)?.name ?: "Chapter ${task.chapterId}"
                    } catch (_: Exception) {
                        "Chapter ${task.chapterId}"
                    }

                    _progressState.update { current ->
                        current.copy(
                            isRunning = true,
                            currentChapterName = chapterNameForProgress,
                            currentChapterProgress = 0f,
                        )
                    }

                    translateChapter(task)

                    _progressState.update { current ->
                        current.copy(
                            completedChapters = current.completedChapters + 1,
                            currentChapterProgress = 1f,
                        )
                    }

                    // Rate limiting delay
                    val delayMs = translationPreferences.rateLimitDelayMs().get()
                    if (delayMs > 0) {
                        delay(delayMs.toLong())
                    }
                } catch (e: CancellationException) {
                    // Two different things land here. A user cancel ([cancel]) asks to drop *this*
                    // chapter - its partial progress is already saved as .tmp - so the queue keeps
                    // going, matching what the button offers ("Cancel current translation"). A
                    // cancellation raised while the flag is clear came from the job itself (scope
                    // teardown) and has to keep propagating; swallowing it would spin this loop
                    // against a dead coroutine.
                    if (!_progressState.value.isCancelling) throw e
                    logcat(LogPriority.DEBUG) { "Translation cancelled by user for chapter ${task.chapterId}" }
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "Translation failed for chapter ${task.chapterId}" }
                    // Re-queue failed task with lower priority if retry count is low
                    val retryCount = task.retryCount + 1
                    if (retryCount < MAX_TASK_RETRIES) {
                        queueMap[task.chapterId] = task.copy(
                            status = TranslationStatus.FAILED,
                            errorMessage = e.message,
                            retryCount = retryCount,
                        )
                    } else {
                        logcat(LogPriority.ERROR) { "Max retries reached for chapter ${task.chapterId}, skipping" }
                    }
                } finally {
                    currentTask = null
                    _currentTranslatingChapterId.value = null
                    // The cancel request is scoped to one chapter, so it is consumed here on every
                    // exit path - including the chapter that finished before any chunk read it.
                    // Nothing else clears it in practice ([stop] does, but has no caller), and a
                    // flag left set made every later chapter die on its first chunk.
                    _progressState.update { it.copy(isCancelling = false) }
                    publishQueueState()
                }
            }

            _progressState.update { current ->
                current.copy(
                    isRunning = false,
                    currentChapterName = null,
                    currentChapterProgress = 0f,
                )
            }
        }
    }

    /** Atomically remove and return the highest-priority task from the queue (FIFO within equal priority). */
    private fun pollHighestPriority(): TranslationTask? {
        if (queueMap.isEmpty()) return null
        val best = queueMap.entries.minWithOrNull(
            compareByDescending<Map.Entry<Long, TranslationTask>> { it.value.priority }
                .thenBy { it.value.id },
        ) ?: return null
        return queueMap.remove(best.key)
    }

    /**
     * Pause translation processing.
     */
    fun pause() {
        _isPaused.value = true
        _progressState.update { it.copy(isPaused = true) }
    }

    /**
     * Resume translation processing.
     */
    fun resume() {
        _isPaused.value = false
        _progressState.update { it.copy(isPaused = false) }
        if (translationJob?.isActive != true && queueMap.isNotEmpty()) {
            start()
        }
    }

    /**
     * Stop translation processing.
     */
    fun stop() {
        translationJob?.cancel()
        translationJob = null
        _currentTranslatingChapterId.value = null
        _progressState.update {
            it.copy(
                isRunning = false,
                isPaused = false,
                isCancelling = false,
                currentChapterName = null,
                currentChapterProgress = 0f,
                currentChunkIndex = 0,
                totalChunks = 0,
            )
        }
    }

    /**
     * Request cancellation of the current translation.
     * The current chunk will finish, then partial progress will be saved.
     */
    fun cancel() {
        _progressState.update { it.copy(isCancelling = true) }
    }

    /**
     * Check if translation service is running.
     */
    fun isRunning(): Boolean = translationJob?.isActive == true

    /**
     * Get the current queue size.
     */
    fun queueSize(): Int = queueMap.size

    /**
     * Translate a single chapter.
     * Supports partial translation: if some chunks fail, the partial result is saved
     * with a .tmp extension and can be resumed on the next attempt.
     */
    private suspend fun translateChapter(task: TranslationTask) = withContext(Dispatchers.IO) {
        val engine = translationEngineManager.getEngine(TranslationPurpose.CHAPTER)
            ?: throw IllegalStateException("No translation engine available")

        // Get chapter and manga from database
        val chapter = getChapter.await(task.chapterId)
            ?: throw IllegalStateException("Chapter ${task.chapterId} not found")
        val manga = getManga.await(task.mangaId)
            ?: throw IllegalStateException("Manga ${task.mangaId} not found")
        val source = sourceManager.awaitSource(manga.source)
            ?: throw IllegalStateException("Source ${manga.source} not found")

        val locator = TranslationLocator(
            sourceName = sourceManager.getOrStub(manga.source).toString(),
            novelTitle = manga.title,
            chapterName = chapter.name,
            chapterUrl = chapter.url,
        )

        logcat(LogPriority.DEBUG) { "Starting translation for chapter: ${chapter.name}" }

        // Check if already translated (skip unless forceRetranslate)
        if (!task.forceRetranslate) {
            val existingTranslation = translatedChapterRepository.getTranslatedChapter(
                locator,
                task.targetLanguage,
            )
            if (existingTranslation != null) {
                logcat(LogPriority.DEBUG) { "Chapter ${chapter.name} already translated, skipping" }
                return@withContext
            }
        }

        if (translationPreferences.smartAutoTranslate().get()) {
            val sourceLang = source.lang
            if (sourceLang.isNotBlank() && sourceLang != "all" && sourceLang != "other") {
                if (TranslationHtmlUtils.languageCodesMatch(sourceLang, task.targetLanguage)) {
                    logcat(LogPriority.DEBUG) { "Source language ($sourceLang) matches target language, skipping" }
                    return@withContext
                }
            }
        }

        // Try to get content from downloaded chapter first, fall back to fetching from source
        val allContent = getChapterContent(chapter, manga, source)

        if (allContent.isBlank()) {
            logcat(LogPriority.WARN) { "No text content found in chapter" }
            return@withContext
        }

        if (task.sourceLanguage == "auto" && translationPreferences.smartAutoTranslate().get()) {
            val detected = detectLanguage(allContent, task.mangaId)
            if (detected != null && TranslationHtmlUtils.languageCodesMatch(detected, task.targetLanguage)) {
                logcat(LogPriority.DEBUG) { "Detected language ($detected) matches target language, skipping" }
                return@withContext
            }
        }

        // Extract and preserve media tags (delegated — fix SRP)
        val translationPlan = TranslationHtmlUtils.prepareTranslation(allContent)
        val paragraphs = translationPlan.texts

        if (paragraphs.isEmpty()) {
            logcat(LogPriority.WARN) { "No translatable text in chapter ${chapter.name}, skipping" }
            return@withContext
        }

        logcat(LogPriority.DEBUG) { "Translating ${paragraphs.size} paragraphs for chapter ${chapter.name}" }

        val chunks = chunkForTranslation(paragraphs, engine.id)

        logcat(LogPriority.DEBUG) { "Grouped into ${chunks.size} chunks for ${engine.name}" }

        // Update progress with chunk info
        _progressState.update { current ->
            current.copy(totalChunks = chunks.size, currentChunkIndex = 0)
        }

        // Check for existing partial translation to resume from
        val existingTmpParagraphs = run {
            val tmp = translatedChapterRepository.getTmpTranslation(locator, task.targetLanguage)
            if (tmp != null) {
                TranslationHtmlUtils.extractTranslatedParagraphs(tmp.translatedContent)
            } else {
                emptyList()
            }
        }

        // Resume only where the saved paragraphs land exactly on a chunk boundary. Partial data written
        // against a different split (older build, changed source) would land on the wrong paragraphs.
        val chunkOffsets = chunks.runningFold(0) { covered, chunk -> covered + chunk.size }
        val resumeFromChunk = if (task.forceRetranslate) {
            0
        } else {
            chunkOffsets.indexOf(existingTmpParagraphs.size).coerceAtLeast(0)
        }
        if (resumeFromChunk > 0) {
            logcat(LogPriority.INFO) { "Resuming from chunk $resumeFromChunk/${chunks.size}" }
        } else if (existingTmpParagraphs.isNotEmpty()) {
            logcat(LogPriority.WARN) {
                "Ignoring misaligned partial translation (${existingTmpParagraphs.size} paragraphs " +
                    "vs ${paragraphs.size} in this chapter)"
            }
        }

        // Translate chapter title
        var translatedTitle: String? = null
        try {
            val titleResult = translationEngineManager.translate(
                TranslationPurpose.CHAPTER,
                TranslationRequest(listOf(chapter.name), task.sourceLanguage, task.targetLanguage),
            )
            if (titleResult is TranslationResult.Success) {
                translatedTitle = titleResult.translatedTexts.firstOrNull()?.trim()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Failed to translate chapter title: ${chapter.name}" }
        }

        val translatedChunks = MutableList<List<String>?>(chunks.size) { null }
        repeat(resumeFromChunk) { chunkIndex ->
            translatedChunks[chunkIndex] = existingTmpParagraphs.subList(
                chunkOffsets[chunkIndex],
                chunkOffsets[chunkIndex + 1],
            )
        }

        val dispatch = chunkDispatchFor(engine.id)
        val completedChunks = AtomicInteger(resumeFromChunk)
        if (resumeFromChunk > 0) {
            _progressState.update { current ->
                current.copy(
                    currentChapterProgress =
                    PROGRESS_TRANSLATE_START + PROGRESS_TRANSLATE_RANGE * resumeFromChunk / chunks.size,
                    currentChunkIndex = resumeFromChunk,
                )
            }
        }

        val pendingChunks = chunks.drop(resumeFromChunk)
        val failure = try {
            parallelCatchingFirstFailure(
                values = pendingChunks,
                maxParallel = dispatch.maxParallel,
            ) { pendingIndex, chunk ->
                val chunkIndex = resumeFromChunk + pendingIndex
                if (_progressState.value.isCancelling) {
                    throw CancellationException("Translation cancelled by user")
                }
                logcat(LogPriority.DEBUG) {
                    "Sending chunk ${chunkIndex + 1}/${chunks.size} for translation " +
                        "(${chunk.sumOf(String::length)} chars)"
                }
                translatedChunks[chunkIndex] = translateChunk(
                    texts = chunk,
                    sourceLanguage = task.sourceLanguage,
                    targetLanguage = task.targetLanguage,
                    context = dispatch.contextFrom(chunkIndex, chunks, translatedChunks),
                )
                val completed = completedChunks.incrementAndGet()
                _progressState.update { current ->
                    current.copy(
                        currentChapterProgress =
                        PROGRESS_TRANSLATE_START + PROGRESS_TRANSLATE_RANGE * completed / chunks.size,
                        currentChunkIndex = completed,
                    )
                }
            }
        } catch (e: CancellationException) {
            savePartialTranslation(
                locator,
                task,
                engine.id.key,
                contiguousTranslationPrefix(translatedChunks),
                translatedTitle,
            )
            throw e
        }

        if (failure != null) {
            val failedChunkIndex = resumeFromChunk + failure.index
            val partial = contiguousTranslationPrefix(translatedChunks)
            if (partial.isNotEmpty()) {
                savePartialTranslation(locator, task, engine.id.key, partial, translatedTitle)
                logcat(LogPriority.WARN) {
                    "Saved partial translation (${partial.size} paragraphs) for chapter ${chapter.name}"
                }
            }
            throw Exception(
                "Translation incomplete: failed at chunk ${failedChunkIndex + 1}/${chunks.size}. " +
                    "${partial.size} paragraphs translated, will resume on next attempt.",
                failure.cause,
            )
        }

        val allTranslated = contiguousTranslationPrefix(translatedChunks)

        // Build final HTML (single source of truth — fix DRY 2.1, HTML escaping — fix 6.5)
        val translatedBody = translationPlan.apply(allTranslated)
        val translatedHtml = if (translatedTitle.isNullOrBlank()) {
            translatedBody
        } else {
            "<h1>${TranslationHtmlUtils.escapeHtml(translatedTitle.trim())}</h1>$translatedBody"
        }

        // Save the complete translation
        val translatedChapter = TranslatedChapter(
            chapterId = task.chapterId,
            mangaId = task.mangaId,
            targetLanguage = task.targetLanguage,
            engineId = engine.id.key,
            translatedContent = translatedHtml,
            dateTranslated = System.currentTimeMillis(),
        )
        translatedChapterRepository.upsertTranslation(locator, translatedChapter)

        logcat(LogPriority.DEBUG) { "Successfully translated and saved chapter ${chapter.name}" }

        _progressState.update { current ->
            current.copy(currentChapterProgress = 1f)
        }
    }

    /**
     * How a chapter's chunks are dispatched. Anchoring feeds each chunk the previous one's
     * translation, so it rules out parallelism rather than silently dropping the context.
     */
    private data class ChunkDispatch(val maxParallel: Int, val anchoringParagraphs: Int) {
        fun contextFrom(
            chunkIndex: Int,
            chunks: List<List<String>>,
            translated: List<List<String>?>,
        ): TranslationContext? {
            if (anchoringParagraphs == 0 || chunkIndex == 0) return null
            return TranslationContext(
                previousSourceParagraphs = chunks[chunkIndex - 1].takeLast(anchoringParagraphs),
                previousTranslatedParagraphs = translated[chunkIndex - 1].orEmpty().takeLast(anchoringParagraphs),
            )
        }
    }

    private fun chunkDispatchFor(engineId: TranslationEngineId): ChunkDispatch {
        val anchoringParagraphs = contextualAnchoringParagraphs(
            engineId = engineId,
            enabled = translationPreferences.contextualAnchoringEnabled().get(),
            paragraphs = translationPreferences.contextualAnchoringParagraphs().get(),
        )
        return if (anchoringParagraphs > 0) {
            ChunkDispatch(maxParallel = 1, anchoringParagraphs = anchoringParagraphs)
        } else {
            ChunkDispatch(
                maxParallel = translationPreferences.maxParallelTranslations().get()
                    .coerceIn(1, MAX_PARALLEL_TRANSLATIONS),
                anchoringParagraphs = 0,
            )
        }
    }

    /**
     * One chunk, one result. Retrying belongs to [AiRetryPolicy], which knows which provider errors
     * are worth another request; a second loop here only multiplied the requests a failure costs.
     */
    private suspend fun translateChunk(
        texts: List<String>,
        sourceLanguage: String,
        targetLanguage: String,
        context: TranslationContext? = null,
    ): List<String> {
        val result = translationEngineManager.translate(
            TranslationPurpose.CHAPTER,
            TranslationRequest(texts, sourceLanguage, targetLanguage, context = context),
        )
        // Which chunk this was belongs to the caller's error, which already names it.
        return when (result) {
            is TranslationResult.Error -> throw IllegalStateException(result.message)
            is TranslationResult.Success -> result.translatedTexts.takeIf { it.size == texts.size }
                ?: throw IllegalStateException(
                    "Expected ${texts.size} translations, received ${result.translatedTexts.size}",
                )
        }
    }

    /**
     * Save a partial translation as a .tmp file for later resume.
     * Uses [TranslationHtmlUtils.buildTranslatedHtml] (fix DRY 2.1).
     */
    private suspend fun savePartialTranslation(
        locator: TranslationLocator,
        task: TranslationTask,
        engineId: String,
        translatedParagraphs: List<String>,
        translatedTitle: String?,
    ) {
        if (translatedParagraphs.isEmpty()) return

        val html = TranslationHtmlUtils.buildTranslatedHtml(translatedTitle, translatedParagraphs)

        val partial = TranslatedChapter(
            chapterId = task.chapterId,
            mangaId = task.mangaId,
            targetLanguage = task.targetLanguage,
            engineId = engineId,
            translatedContent = html,
            dateTranslated = System.currentTimeMillis(),
        )
        try {
            translatedChapterRepository.upsertTmpTranslation(locator, partial)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to save partial translation" }
        }
    }

    /**
     * Get chapter content from downloaded files or source.
     * Delegates filesystem reading to the shared [ChapterContentReader] (fix 1.2 DRY).
     */
    private suspend fun getChapterContent(
        chapter: Chapter,
        manga: Manga,
        source: eu.kanade.tachiyomi.source.Source,
    ): String {
        // Try downloaded content first via shared reader
        val downloadedContent = chapterContentReader.readDownloadedContent(manga, chapter, source)
        if (!downloadedContent.isNullOrBlank()) {
            return downloadedContent
        }

        // Check download cache for inconsistency logging
        val isDownloaded = downloadCache.isChapterDownloaded(
            chapter.name,
            chapter.scanlator,
            chapter.url,
            manga.title,
            manga.source,
            skipCache = false,
        )
        if (isDownloaded) {
            logcat(LogPriority.WARN) {
                "Download cache says chapter is downloaded but no content found: ${chapter.name}"
            }
        }

        // Download first (or wait for an in-flight download) so the user keeps an offline copy
        if (translationPreferences.autoDownloadBeforeTranslate().get() && source.isNovelSource()) {
            if (downloadManager.getQueuedDownloadOrNull(chapter.id) == null) {
                downloadManager.downloadChapters(manga, listOf(chapter))
            }
            _progressState.update { it.copy(currentChapterProgress = PROGRESS_FETCH_START) }
            awaitDownloadedContent(chapter, manga, source)?.let { return it }
            logcat(LogPriority.WARN) {
                "Auto-download before translate did not produce content for ${chapter.name}, fetching directly"
            }
        }

        // Fall back to fetching from source
        logcat(LogPriority.DEBUG) { "Fetching content from source for chapter: ${chapter.name}" }

        if (!source.isNovelSource()) {
            throw IllegalStateException("Source ${source.name} is not a novel source")
        }

        val page = Page(0, chapter.url, chapter.url)

        _progressState.update { it.copy(currentChapterProgress = PROGRESS_FETCH_START) }

        val content = try {
            source.fetchPageText(page)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to fetch novel page text for chapter: ${chapter.name}" }
            throw e
        }

        _progressState.update { it.copy(currentChapterProgress = PROGRESS_TRANSLATE_START) }

        return content
    }

    /**
     * Poll until the chapter's downloaded content becomes readable, the download leaves the
     * queue without producing content (failed/cancelled), or the timeout elapses.
     * The initial grace period covers queueChapters landing the download asynchronously.
     */
    private suspend fun awaitDownloadedContent(
        chapter: Chapter,
        manga: Manga,
        source: eu.kanade.tachiyomi.source.Source,
    ): String? = withTimeoutOrNull(AUTO_DOWNLOAD_WAIT_MS) {
        var elapsed = 0L
        while (true) {
            val content = chapterContentReader.readDownloadedContent(manga, chapter, source)
            if (!content.isNullOrBlank()) return@withTimeoutOrNull content
            if (elapsed >= AUTO_DOWNLOAD_GRACE_MS &&
                downloadManager.getQueuedDownloadOrNull(chapter.id) == null
            ) {
                return@withTimeoutOrNull null
            }
            delay(AUTO_DOWNLOAD_POLL_MS)
            elapsed += AUTO_DOWNLOAD_POLL_MS
        }
        @Suppress("UNREACHABLE_CODE")
        null
    }

    /**
     * Stable ordered snapshot of the queue. Iterating CHM.values is weakly consistent and never
     * throws; copying into a growable list first avoids the toArray sizing race that
     * `sortedWith` (via toTypedArray) hits under concurrent put/remove (NoSuchElementException).
     */
    private fun sortedQueueSnapshot(): List<TranslationTask> {
        val tasks = ArrayList<TranslationTask>(queueMap.size + 1)
        for (task in queueMap.values) tasks.add(task)
        tasks.sortWith(compareByDescending<TranslationTask> { it.priority }.thenBy { it.id })
        return tasks
    }

    private fun publishQueueState() {
        val snapshot = sortedQueueSnapshot()
        _progressState.update { current ->
            current.copy(totalChapters = snapshot.size + current.completedChapters)
        }
        _queueState.value = snapshot
        persistQueue()
    }

    /** Persist the queue off the caller thread; concurrent calls collapse to one pending save. */
    private fun persistQueue() {
        if (!saveRequested.compareAndSet(false, true)) return
        scope.launch {
            saveMutex.withLock {
                // Clear before reading so a mutation that races the read schedules a fresh save
                saveRequested.set(false)
                val snapshot = sortedQueueSnapshot()
                val inFlight = currentTask
                val toSave = if (inFlight != null && snapshot.none { it.chapterId == inFlight.chapterId }) {
                    listOf(inFlight) + snapshot
                } else {
                    snapshot
                }
                store.save(toSave)
            }
        }
    }

    /**
     * Translate text using the configured engine.
     */
    suspend fun translateText(
        text: String,
        sourceLanguage: String = translationPreferences.sourceLanguage().get(),
        targetLanguage: String = translationPreferences.targetLanguage().get(),
    ): TranslationResult {
        val engine = translationEngineManager.getEngine(TranslationPurpose.CHAPTER)
            ?: return TranslationResult.Error("No translation engine available")

        logcat(LogPriority.DEBUG) {
            "Translation: sending ${text.length} chars via ${engine.name} ($sourceLanguage → $targetLanguage)"
        }
        val result = translationEngineManager.translate(
            TranslationPurpose.CHAPTER,
            TranslationRequest(listOf(text), sourceLanguage, targetLanguage),
        )
        when (result) {
            is TranslationResult.Success -> logcat(LogPriority.DEBUG) {
                "Translation: received ${result.translatedTexts.firstOrNull()?.length ?: 0} chars"
            }
            is TranslationResult.Error -> logcat(LogPriority.WARN) {
                "Translation: engine error — ${result.message}"
            }
        }
        return result
    }

    /**
     * Translate chapter content in real-time (for reader).
     * Translates the same HTML fragments as LNReader and writes them back into the original DOM.
     * Saves translations to database for future use.
     */
    suspend fun translateChapterContent(
        content: String,
        locator: TranslationLocator? = null,
        sourceLanguage: String? = null,
        targetLanguage: String? = null,
        forceRetranslate: Boolean = false,
        onProgress: (Float) -> Unit = {},
    ): String {
        if (!translationPreferences.translationEnabled().get()) {
            return content
        }

        val srcLang = sourceLanguage ?: translationPreferences.sourceLanguage().get()
        val tgtLang = targetLanguage ?: translationPreferences.targetLanguage().get()

        // Don't translate if source and target are the same
        if (TranslationHtmlUtils.languageCodesMatch(srcLang, tgtLang)) {
            return content
        }

        if (locator != null && !forceRetranslate) {
            val existingTranslation = translatedChapterRepository.getTranslatedChapter(locator, tgtLang)
            if (TranslationCachePolicy.shouldServeCached(existingTranslation, forceRetranslate)) {
                logcat(LogPriority.DEBUG) { "Using cached translation for ${locator.chapterName} (lang: $tgtLang)" }
                return existingTranslation!!.translatedContent
            }
        }

        val translationPlan = TranslationHtmlUtils.prepareTranslation(content)

        logcat(LogPriority.DEBUG) {
            "translateChapterContent: chapter=${locator?.chapterName} srcLang=$srcLang tgtLang=$tgtLang " +
                "segments=${translationPlan.texts.size}"
        }
        if (translationPlan.texts.isEmpty()) return content

        val engine = translationEngineManager.getEngine(TranslationPurpose.CHAPTER)
            ?: throw IllegalStateException("No translation engine available")
        val chunks = chunkForTranslation(translationPlan.texts, engine.id)
        val translatedChunks = MutableList<List<String>?>(chunks.size) { null }
        val dispatch = chunkDispatchFor(engine.id)
        val chunkProgress = FloatArray(chunks.size)
        val progressLock = Any()
        var lastProgress = 0f
        fun reportChunkProgress(index: Int, progress: Float) {
            synchronized(progressLock) {
                chunkProgress[index] = maxOf(chunkProgress[index], progress)
                val combined = chunkProgress.sum() / chunks.size
                if (combined > lastProgress) {
                    lastProgress = combined
                    onProgress(combined)
                }
            }
        }

        onProgress(0f)
        val failure = parallelCatchingFirstFailure(
            values = chunks,
            maxParallel = dispatch.maxParallel,
        ) { index, texts ->
            coroutineScope {
                val startedAt = System.nanoTime()
                val progressJob = launch {
                    while (isActive) {
                        delay(100)
                        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000.0
                        val estimated = 0.99 * (1 - exp(-elapsedMs / 30_000.0))
                        reportChunkProgress(index, estimated.toFloat())
                    }
                }
                try {
                    translatedChunks[index] = translateChunk(
                        texts = texts,
                        sourceLanguage = srcLang,
                        targetLanguage = tgtLang,
                        context = dispatch.contextFrom(index, chunks, translatedChunks),
                    )
                } finally {
                    progressJob.cancelAndJoin()
                }
                reportChunkProgress(index, 1f)
            }
        }
        if (failure != null) {
            throw IllegalStateException(
                "Translation failed at chunk ${failure.index + 1}/${chunks.size}",
                failure.cause,
            )
        }

        val translatedHtml = translationPlan.apply(contiguousTranslationPrefix(translatedChunks))
        return translatedHtml.also {
            if (locator != null) {
                val translatedChapter = TranslatedChapter(
                    chapterId = 0,
                    mangaId = 0,
                    targetLanguage = tgtLang,
                    engineId = engine.id.key,
                    translatedContent = translatedHtml,
                    dateTranslated = System.currentTimeMillis(),
                )
                try {
                    translatedChapterRepository.upsertTranslation(locator, translatedChapter)
                    logcat(LogPriority.DEBUG) { "Saved translation for ${locator.chapterName} (lang: $tgtLang)" }
                } catch (e: CancellationException) {
                    logcat(LogPriority.DEBUG) { "Translation save was cancelled for ${locator.chapterName}" }
                    throw e
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "Failed to save translation" }
                }
            }
        }
    }

    /**
     * Check if a translation exists for a chapter locator in the given (or default) language.
     */
    suspend fun hasTranslation(
        locator: TranslationLocator,
        targetLanguage: String? = null,
    ): Boolean {
        val tgtLang = targetLanguage ?: translationPreferences.targetLanguage().get()
        return translatedChapterRepository.hasTranslation(locator, tgtLang)
    }

    /**
     * Get last used target language (for quick translate).
     */
    fun getLastTargetLanguage(): String {
        return translationPreferences.targetLanguage().get()
    }

    private fun chunkForTranslation(
        texts: List<String>,
        engineId: TranslationEngineId,
    ): List<List<String>> = TranslationChunker.chunk(
        texts = texts,
        engineId = engineId,
        splitLargeChapters = translationPreferences.splitLargeChapters().get(),
        mode = TranslationChunkMode.fromKey(translationPreferences.translationChunkMode().get()),
        paragraphLimit = translationPreferences.translationChunkSize().get(),
        wordLimit = translationPreferences.translationChunkWordLimit().get(),
    )

    /**
     * Set target language (for language picker).
     */
    fun setTargetLanguage(language: String) {
        translationPreferences.targetLanguage().set(language)
    }

    /**
     * Detect language of text content. Uses LibreTranslate's detection endpoint if available,
     * otherwise falls back to using the manga source's language setting.
     */
    suspend fun detectLanguage(text: String, mangaId: Long? = null): String? {
        val engine = translationEngineManager.resolve(TranslationPurpose.CHAPTER).engine

        if (engine is LibreTranslateEngine) {
            val sample = text.take(DETECT_SAMPLE_SIZE)
            return engine.detectLanguage(sample)
        }

        // For non-Libre engines, use the source's language as a heuristic
        if (mangaId != null) {
            val manga = getManga.await(mangaId)
            if (manga != null) {
                val source = sourceManager.get(manga.source)
                if (source != null) {
                    // Source lang is typically a 2-letter ISO code like "en", "ja", "ko"
                    val sourceLang = source.lang
                    if (sourceLang.isNotBlank() && sourceLang != "all" && sourceLang != "other") {
                        return sourceLang
                    }
                }
            }
        }

        // If source language preference is set (not "auto"), use that
        val configuredLang = translationPreferences.sourceLanguage().get()
        if (configuredLang.isNotBlank() && configuredLang != "auto") {
            return configuredLang
        }

        return null
    }

    companion object {
        /** Priority values for translation queue. */
        const val PRIORITY_LOW = 0
        const val PRIORITY_AHEAD = 25
        const val PRIORITY_NORMAL = 50
        const val PRIORITY_HIGH = 100
        const val PRIORITY_MANUAL_READ = 200

        /** Upper bound for [TranslationPreferences.maxParallelTranslations], and the slider's range. */
        const val MAX_PARALLEL_TRANSLATIONS = 10

        /** Max retries per task at the queue level before dropping. */
        private const val MAX_TASK_RETRIES = 2

        /** Delay between pause polling iterations. */
        private const val PAUSE_POLL_DELAY_MS = 500L

        /** Max wait for an auto-triggered download to produce readable content. */
        private const val AUTO_DOWNLOAD_WAIT_MS = 120_000L

        /** Poll interval while waiting for the auto-triggered download. */
        private const val AUTO_DOWNLOAD_POLL_MS = 1000L

        /** Grace before treating "not in download queue" as failed; queueChapters lands async. */
        private const val AUTO_DOWNLOAD_GRACE_MS = 5000L

        /** Progress fraction at which source fetch completes. */
        private const val PROGRESS_FETCH_START = 0.3f

        /** Progress fraction at which translation begins. */
        private const val PROGRESS_TRANSLATE_START = 0.5f

        /** Range of progress bar dedicated to translation chunks. */
        private const val PROGRESS_TRANSLATE_RANGE = 0.5f

        /** Number of characters to sample for language detection. */
        private const val DETECT_SAMPLE_SIZE = 500
    }
}
