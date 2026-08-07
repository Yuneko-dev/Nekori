package eu.kanade.tachiyomi.ui.reader.service

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.ImageRequest
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.util.system.getBitmapOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.interactor.GetManga
import uy.kohesive.injekt.injectLazy

class TtsPlaybackService : Service() {

    private var isPaused: Boolean = false
    private var progressPercent: Int = 0
    private var novelTitle: String = "TTS playback"
    private var chapterTitle: String = ""
    private var mangaId: Long = -1L
    private var chapterId: Long = -1L
    private var coverBitmap: Bitmap? = null
    private lateinit var mediaSession: MediaSession
    private val getManga: GetManga by injectLazy()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var coverLoadJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        mediaSession = MediaSession(this, "TtsPlaybackService").apply {
            setCallback(
                object : MediaSession.Callback() {
                    override fun onPlay() = sendControlBroadcast(COMMAND_PLAY)
                    override fun onPause() = sendControlBroadcast(COMMAND_PAUSE)
                    override fun onSkipToPrevious() = sendControlBroadcast(COMMAND_PREV_PARAGRAPH)
                    override fun onSkipToNext() = sendControlBroadcast(COMMAND_NEXT_PARAGRAPH)
                    override fun onStop() = stopPlayback()
                },
            )
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Null intent means a sticky restart by the system; there is no playback
        // state to restore and the app may not be allowed to start a foreground
        // service from the background, so just stop.
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent.action) {
            ACTION_PLAY -> sendControlBroadcast(COMMAND_PLAY)
            ACTION_PAUSE -> sendControlBroadcast(COMMAND_PAUSE)
            ACTION_PREV_PARAGRAPH -> sendControlBroadcast(COMMAND_PREV_PARAGRAPH)
            ACTION_NEXT_PARAGRAPH -> sendControlBroadcast(COMMAND_NEXT_PARAGRAPH)
            ACTION_STOP_PLAYBACK -> {
                stopPlayback()
                return START_NOT_STICKY
            }

            ACTION_SYNC -> {
                isPaused = intent.getBooleanExtra(EXTRA_IS_PAUSED, false)
                progressPercent = intent.getIntExtra(EXTRA_PROGRESS_PERCENT, 0).coerceIn(0, 100)
                novelTitle = intent.getStringExtra(EXTRA_NOVEL_TITLE).orEmpty().ifBlank { "TTS playback" }
                chapterTitle = intent.getStringExtra(EXTRA_CHAPTER_TITLE).orEmpty()
                val syncedMangaId = intent.getLongExtra(EXTRA_MANGA_ID, -1L)
                if (mangaId != syncedMangaId) {
                    mangaId = syncedMangaId
                    loadNovelCover()
                }
                chapterId = intent.getLongExtra(EXTRA_CHAPTER_ID, -1L)
                updateMediaSession()
            }
        }

        startForegroundWithNotification()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        serviceScope.cancel()
        mediaSession.release()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundWithNotification() {
        val playPauseIntent = PendingIntent.getService(
            this,
            1001,
            Intent(this, TtsPlaybackService::class.java).setAction(if (isPaused) ACTION_PLAY else ACTION_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1002,
            Intent(this, TtsPlaybackService::class.java).setAction(ACTION_STOP_PLAYBACK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val prevParagraphIntent = PendingIntent.getService(
            this,
            1004,
            Intent(this, TtsPlaybackService::class.java).setAction(ACTION_PREV_PARAGRAPH),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val nextParagraphIntent = PendingIntent.getService(
            this,
            1005,
            Intent(this, TtsPlaybackService::class.java).setAction(ACTION_NEXT_PARAGRAPH),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val openReaderIntent = ReaderActivity.newIntent(
            context = this,
            mangaId = mangaId.takeIf { it > 0L },
            chapterId = chapterId.takeIf { it > 0L },
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val openReaderPendingIntent = PendingIntent.getActivity(
            this,
            1003,
            openReaderIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val statusText = if (isPaused) "Paused" else "Reading in background"
        val contentText = if (chapterTitle.isNotBlank()) "$chapterTitle · $statusText" else statusText

        val notification = Notification.Builder(this, Notifications.CHANNEL_TTS_PLAYBACK)
            .setSmallIcon(R.drawable.ic_mihon)
            .setContentTitle(novelTitle)
            .setContentText(contentText)
            .setLargeIcon(coverBitmap)
            .setContentIntent(openReaderPendingIntent)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setProgress(100, progressPercent, false)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_skip_previous_24dp),
                    "Previous",
                    prevParagraphIntent,
                ).build(),
            )
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(
                        this,
                        if (isPaused) R.drawable.ic_play_arrow_24dp else R.drawable.ic_pause_24dp,
                    ),
                    if (isPaused) "Resume" else "Pause",
                    playPauseIntent,
                ).build(),
            )
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_skip_next_24dp),
                    "Next",
                    nextParagraphIntent,
                ).build(),
            )
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_close_24dp),
                    "Stop",
                    stopIntent,
                ).build(),
            )
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2),
            )
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    Notifications.ID_TTS_PLAYBACK,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
            } else {
                startForeground(Notifications.ID_TTS_PLAYBACK, notification)
            }
        } catch (e: Exception) {
            // Android 12+ disallows starting a foreground service from the
            // background; stop instead of crashing.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                e is ForegroundServiceStartNotAllowedException
            ) {
                logcat(LogPriority.WARN, e) { "Foreground start not allowed for TTS notification" }
                stopSelf()
            } else {
                throw e
            }
        }
    }

    private fun updateMediaSession() {
        mediaSession.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, novelTitle)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, chapterTitle)
                .apply { coverBitmap?.let { putBitmap(MediaMetadata.METADATA_KEY_ART, it) } }
                .build(),
        )
        mediaSession.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or
                        PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackState.ACTION_SKIP_TO_NEXT or
                        PlaybackState.ACTION_STOP,
                )
                .setState(
                    if (isPaused) PlaybackState.STATE_PAUSED else PlaybackState.STATE_PLAYING,
                    PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                    1f,
                )
                .build(),
        )
    }

    private fun loadNovelCover() {
        coverLoadJob?.cancel()
        coverBitmap = null
        val loadingMangaId = mangaId.takeIf { it > 0L } ?: return
        coverLoadJob = serviceScope.launch {
            val manga = getManga.await(loadingMangaId) ?: return@launch
            val request = ImageRequest.Builder(this@TtsPlaybackService)
                .data(manga)
                .size(NOTIFICATION_COVER_SIZE)
                .build()
            val bitmap = imageLoader.execute(request).image
                ?.asDrawable(resources)
                ?.getBitmapOrNull()
            if (mangaId != loadingMangaId) return@launch
            coverBitmap = bitmap
            updateMediaSession()
            startForegroundWithNotification()
        }
    }

    private fun sendControlBroadcast(command: String) {
        sendBroadcast(
            Intent(ACTION_CONTROL).apply {
                setPackage(packageName)
                putExtra(EXTRA_COMMAND, command)
            },
        )
    }

    private fun stopPlayback() {
        sendControlBroadcast(COMMAND_STOP)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        private const val ACTION_SYNC =
            "eu.kanade.tachiyomi.ui.reader.service.TtsPlaybackService.SYNC"
        private const val ACTION_PLAY =
            "eu.kanade.tachiyomi.ui.reader.service.TtsPlaybackService.PLAY"
        private const val ACTION_PAUSE =
            "eu.kanade.tachiyomi.ui.reader.service.TtsPlaybackService.PAUSE"
        private const val ACTION_PREV_PARAGRAPH =
            "eu.kanade.tachiyomi.ui.reader.service.TtsPlaybackService.PREV_PARAGRAPH"
        private const val ACTION_NEXT_PARAGRAPH =
            "eu.kanade.tachiyomi.ui.reader.service.TtsPlaybackService.NEXT_PARAGRAPH"
        private const val ACTION_STOP_PLAYBACK =
            "eu.kanade.tachiyomi.ui.reader.service.TtsPlaybackService.STOP_PLAYBACK"

        const val ACTION_CONTROL =
            "eu.kanade.tachiyomi.ui.reader.service.TtsPlaybackService.CONTROL"
        const val EXTRA_COMMAND = "extra_command"
        const val COMMAND_PLAY = "play"
        const val COMMAND_PAUSE = "pause"
        const val COMMAND_PREV_PARAGRAPH = "prev_paragraph"
        const val COMMAND_NEXT_PARAGRAPH = "next_paragraph"
        const val COMMAND_STOP = "stop"

        private const val EXTRA_IS_PAUSED = "extra_is_paused"
        private const val EXTRA_PROGRESS_PERCENT = "extra_progress_percent"
        private const val EXTRA_NOVEL_TITLE = "extra_novel_title"
        private const val EXTRA_CHAPTER_TITLE = "extra_chapter_title"
        private const val EXTRA_MANGA_ID = "extra_manga_id"
        private const val EXTRA_CHAPTER_ID = "extra_chapter_id"
        private const val NOTIFICATION_COVER_SIZE = 512

        fun syncState(
            context: Context,
            isPaused: Boolean,
            progressPercent: Int,
            novelTitle: String,
            chapterTitle: String,
            mangaId: Long,
            chapterId: Long,
        ) {
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, TtsPlaybackService::class.java)
                        .setAction(ACTION_SYNC)
                        .putExtra(EXTRA_IS_PAUSED, isPaused)
                        .putExtra(EXTRA_PROGRESS_PERCENT, progressPercent.coerceIn(0, 100))
                        .putExtra(EXTRA_NOVEL_TITLE, novelTitle)
                        .putExtra(EXTRA_CHAPTER_TITLE, chapterTitle)
                        .putExtra(EXTRA_MANGA_ID, mangaId)
                        .putExtra(EXTRA_CHAPTER_ID, chapterId),
                )
            } catch (e: Exception) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    e is ForegroundServiceStartNotAllowedException
                ) {
                    logcat(LogPriority.WARN, e) { "Cannot start TTS service from the background" }
                } else {
                    throw e
                }
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TtsPlaybackService::class.java))
        }
    }
}
