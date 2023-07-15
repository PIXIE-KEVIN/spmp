package com.toasterofbread.spmp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import com.toasterofbread.spmp.api.cast
import com.toasterofbread.spmp.model.Settings
import com.toasterofbread.spmp.model.mediaitem.Song
import com.toasterofbread.spmp.model.mediaitem.enums.SongAudioQuality
import com.toasterofbread.spmp.platform.PlatformBinder
import com.toasterofbread.spmp.platform.PlatformContext
import com.toasterofbread.spmp.platform.PlatformServiceImpl
import com.toasterofbread.spmp.platform.PlayerDownloadManager
import com.toasterofbread.spmp.platform.PlayerDownloadManager.DownloadStatus
import com.toasterofbread.spmp.resources.getString
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileOutputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

private const val FILE_DOWNLOADING_SUFFIX = ".part"
private const val NOTIFICATION_ID = 1
private const val NOTIFICATION_CHANNEL_ID = "download_channel"

class PlayerDownloadService: PlatformServiceImpl() {
    private inner class Download(
        val id: String,
        val quality: SongAudioQuality,
        var silent: Boolean,
        val instance: Int,
        var file: File? = null
    ) {
        var status: DownloadStatus.Status = DownloadStatus.Status.IDLE
            set(value) {
                if (field != value) {
                    field = value
                    broadcastStatus()
                }
            }
        val song: Song get() = Song.fromId(id)

        val finished: Boolean get() = status == DownloadStatus.Status.ALREADY_FINISHED || status == DownloadStatus.Status.FINISHED
        val downloading: Boolean get() = status == DownloadStatus.Status.DOWNLOADING || status == DownloadStatus.Status.PAUSED

        var cancelled: Boolean = false
            private set

        var downloaded: Long = 0
        var total_size: Long = -1

        val progress: Float get() = if (total_size < 0f) 0f else downloaded.toFloat() / total_size
        val percent_progress: Int get() = (progress * 100).toInt()

        fun getStatusObject(): DownloadStatus =
            DownloadStatus(
                song,
                status,
                quality,
                progress,
                instance.toString(),
                file
            )

        fun cancel() {
            cancelled = true
        }

        fun matchesFile(file: File): Boolean? {
            return fileMatchesDownload(file.name, id, quality)
        }

        fun generatePath(extension: String, in_progress: Boolean): String {
            return getDownloadPath(id, quality, extension, in_progress)
        }

        fun broadcastResult(result: Result<File?>?, instance: Int) {
            sendMessageOut(PlayerDownloadManager.PlayerDownloadMessage(
                IntentAction.START_DOWNLOAD,
                mapOf(
                    "status" to getStatusObject(),
                    "result" to (result ?: Result.success(null))
                ),
                instance
            ))
        }

        fun broadcastStatus(started: Boolean = false) {
            sendMessageOut(PlayerDownloadManager.PlayerDownloadMessage(
                IntentAction.STATUS_CHANGED,
                mapOf("status" to getStatusObject(), "started" to started)
            ))
        }
    }

    private var download_inc: Int = 0
    private fun getOrCreateDownload(song_id: String): Download {
        synchronized(downloads) {
            for (download in downloads) {
                if (download.id == song_id) {
                    return download
                }
            }
            return Download(song_id, Settings.getEnum(Settings.KEY_DOWNLOAD_AUDIO_QUALITY), true, download_inc++)
        }
    }

    fun getAllDownloadsStatus(): List<DownloadStatus> =
        downloads.map { it.getStatusObject() }

    fun getDownloadStatus(song_id: String): DownloadStatus? =
        downloads.firstOrNull { it.id == song_id }?.getStatusObject()

    private fun Collection<Download>.getTotalProgress(): Float {
        if (isEmpty()) {
            return 1f
        }

        var total_progress = 0f
        for (download in this) {
            total_progress += download.progress
        }
        return total_progress / size
    }

    enum class IntentAction {
        STOP, START_DOWNLOAD, CANCEL_DOWNLOAD, CANCEL_ALL, PAUSE_RESUME, STATUS_CHANGED
    }
    
    data class FilenameData(
        val id: String,
        val quality: SongAudioQuality,
        val extension: String,
        val downloading: Boolean
    )

    companion object {
        fun getFilenameData(filename: String): FilenameData {
            val downloading = filename.endsWith(FILE_DOWNLOADING_SUFFIX)
            val split = (if (downloading) filename.dropLast(FILE_DOWNLOADING_SUFFIX.length) else filename).split('.', limit = 3)
            require(split.size == 3)

            return FilenameData(
                split[0],
                SongAudioQuality.values()[split[1].toInt()],
                split[2],
                downloading
            )
        }

        fun getFilenameSong(filename: String): Song {
            return Song.fromId(filename.take(filename.indexOf('.')))
        }

        // Filename format: id.quality.mediatype(.part)
        // Return values: true = match, false = match (partial file), null = no match
        fun fileMatchesDownload(filename: String, id: String, quality: SongAudioQuality): Boolean? {
            if (!filename.startsWith("$id.${quality.ordinal}.")) {
                return null
            }
            return !filename.endsWith(FILE_DOWNLOADING_SUFFIX)
        }

        fun getDownloadPath(id: String, quality: SongAudioQuality, extension: String, in_progress: Boolean): String {
            return "$id.${quality.ordinal}.$extension${ if (in_progress) FILE_DOWNLOADING_SUFFIX else ""}"
        }
    }

    private var notification_builder: Notification.Builder? = null
    private lateinit var notification_manager: NotificationManagerCompat

    private val download_dir: File get() = PlayerDownloadManager.getDownloadDir(context)
    private val downloads: MutableList<Download> = mutableListOf()
    private val executor = Executors.newFixedThreadPool(3)
    private var stopping = false

    private var start_time: Long = 0
    private var completed_downloads: Int = 0
    private var failed_downloads: Int = 0
    private var cancelled: Boolean = false
    private var notification_update_time: Long = -1

    private var paused: Boolean = false
        set(value) {
            field = value
            pause_resume_action.title = if (value) "Resume" else "Pause"
        }

    private lateinit var notification_delete_intent: PendingIntent
    private lateinit var pause_resume_action: Notification.Action

    override fun onCreate() {
        super.onCreate()

        downloads.clear()

        notification_manager = NotificationManagerCompat.from(this)
        notification_delete_intent = PendingIntent.getService(
            this,
            IntentAction.STOP.ordinal,
            Intent(this, PlayerDownloadService::class.java).putExtra("action", IntentAction.STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT
        )
        pause_resume_action = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
            "Pause",
            PendingIntent.getService(
                this,
                IntentAction.PAUSE_RESUME.ordinal,
                Intent(this, PlayerDownloadService::class.java).putExtra("action", IntentAction.PAUSE_RESUME),
                PendingIntent.FLAG_IMMUTABLE
            )
        ).build()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        downloads.clear()
        super.onDestroy()
    }

    override fun onMessage(data: Any?) {
        require(data is PlayerDownloadManager.PlayerDownloadMessage)
        onActionIntentReceived(data)
    }

    private fun onActionIntentReceived(message: PlayerDownloadManager.PlayerDownloadMessage) {
        when (message.action) {
            IntentAction.STOP -> {
                println("Download service stopping...")
                synchronized(executor) {
                    stopping = true
                }
                stopSelf()
            }
            IntentAction.START_DOWNLOAD -> startDownload(message)
            IntentAction.CANCEL_DOWNLOAD -> cancelDownload(message)
            IntentAction.CANCEL_ALL -> cancelAllDownloads(message)
            IntentAction.PAUSE_RESUME -> {
                paused = !paused
                onDownloadProgress()
            }
            IntentAction.STATUS_CHANGED -> throw IllegalStateException("STATUS_CHANGED is for output only")
        }
    }

    private fun startDownload(message: PlayerDownloadManager.PlayerDownloadMessage) {
        require(message.instance != null)
        val download = getOrCreateDownload(message.data["song_id"] as String)

        val silent = message.data["silent"] as Boolean
        if (!silent) {
            download.silent = false
        }

        synchronized(download) {
            if (download.finished) {
                download.broadcastResult(Result.success(download.file), message.instance)
                return
            }

            if (download.downloading) {
                if (paused) {
                    paused = false
                }
                download.broadcastResult(null, message.instance)
                return
            }

            synchronized(downloads) {
                if (downloads.isEmpty()) {
                    if (!download.silent) {
                        notification_builder = getNotificationBuilder()
                        startForeground(NOTIFICATION_ID, notification_builder!!.build())
                    }
                    start_time = System.currentTimeMillis()
                    completed_downloads = 0
                    failed_downloads = 0
                    cancelled = false
                }

                downloads.add(download)
                download.broadcastStatus(true)
            }
        }

        onDownloadProgress()

        executor.submit {
            runBlocking {
                var result: Result<File?>? = null
                while (result == null || download.status == DownloadStatus.Status.IDLE || download.status == DownloadStatus.Status.PAUSED) {
                    if (paused && !download.cancelled) {
                        onDownloadProgress()
                        delay(1000)
                        continue
                    }

                    result = try {
                        performDownload(download)
                    }
                    catch (e: Exception) {
                        Result.failure(e)
                    }
                }

                synchronized(downloads) {
                    downloads.removeIf { it.id == download.id }
                    if (downloads.isEmpty()) {
                        cancelled = download.cancelled
                        stopForeground(false)
                    }

                    if (result.isFailure) {
                        failed_downloads += 1
                    }
                    else {
                        completed_downloads += 1
                    }
                }

                download.broadcastResult(result, message.instance)

                if (notification_update_time > 0) {
                    val delay_duration = 1000 - (System.currentTimeMillis() - notification_update_time)
                    if (delay_duration > 0) {
                        delay(delay_duration)
                    }
                }
                onDownloadProgress()
            }
        }
    }

    private fun cancelDownload(message: PlayerDownloadManager.PlayerDownloadMessage) {
        val id = message.data["id"] as String
        synchronized(downloads) {
            downloads.firstOrNull { it.id == id }?.cancel()
        }
    }

    private fun cancelAllDownloads(message: PlayerDownloadManager.PlayerDownloadMessage) {
        synchronized(downloads) {
            downloads.forEach { it.cancel() }
        }
    }

    private fun performDownload(download: Download): Result<File?> {

        val format = download.song.getFormatByQuality(download.quality)
        if (format.isFailure) {
            return format.cast()
        }

        val connection = URL(format.getOrThrow().stream_url).openConnection() as HttpURLConnection
        connection.setRequestProperty("Range", "bytes=${download.downloaded}-")
        connection.connect()

        if (connection.responseCode != 200 && connection.responseCode != 206) {
            return Result.failure(ConnectException(
                "${download.id}: Server returned code ${connection.responseCode} ${connection.responseMessage}"
            ))
        }
        
        var file: File? = download.file
        download_dir.mkdirs()

        if (file == null) {
            val extension = when (connection.contentType) {
                "audio/webm" -> "webm"
                else -> return Result.failure(NotImplementedError(connection.contentType))
            }
            file = download_dir.resolve(download.generatePath(extension, true))
        }

        check(file.name.endsWith(FILE_DOWNLOADING_SUFFIX))

        val data = ByteArray(4096)
        val output = FileOutputStream(file, true)
        val input = connection.inputStream

        fun close(status: DownloadStatus.Status) {
            input.close()
            output.close()
            connection.disconnect()
            download.status = status
        }

        download.total_size = connection.contentLengthLong + download.downloaded
        download.status = DownloadStatus.Status.DOWNLOADING

        while (true) {
            val size = input.read(data)
            if (size < 0) {
                break
            }

            synchronized(executor) {
                if (stopping || download.cancelled) {
                    close(DownloadStatus.Status.CANCELLED)
                    return Result.success(null)
                }
                if (paused) {
                    close(DownloadStatus.Status.PAUSED)
                    return Result.success(null)
                }
            }

            download.downloaded += size
            output.write(data, 0, size)

            onDownloadProgress()
        }

        close(DownloadStatus.Status.FINISHED)

        val renamed = file.resolveSibling(file.name.dropLast(FILE_DOWNLOADING_SUFFIX.length))
        file.renameTo(renamed)
        download.file = renamed
        download.status = DownloadStatus.Status.FINISHED

        return Result.success(download.file)
    }

    private fun getNotificationText(): String {
        var text = ""
        var additional = ""

        synchronized(downloads) {
            var downloading = 0
            for (dl in downloads) {
                if (dl.status != DownloadStatus.Status.DOWNLOADING && dl.status != DownloadStatus.Status.PAUSED) {
                    continue
                }

                if (text.isNotEmpty()) {
                    text += ", ${dl.percent_progress}%"
                }
                else {
                    text += "${dl.percent_progress}%"
                }

                downloading += 1
            }

            if (downloading < downloads.size) {
                additional += "${downloads.size - downloading} queued"
            }
            if (completed_downloads > 0) {
                if (additional.isNotEmpty()) {
                    additional += ", $completed_downloads finished"
                }
                else {
                    additional += "$completed_downloads finished"
                }
            }
            if (failed_downloads > 0) {
                if (additional.isNotEmpty()) {
                    additional += ", $failed_downloads failed"
                }
                else {
                    additional += "$failed_downloads failed"
                }
            }
        }

        return if (additional.isEmpty()) text else "$text ($additional)"
    }

    private fun onDownloadProgress() {
        if (downloads.isNotEmpty() && downloads.all { it.silent }) {
            return
        }

        notification_builder?.also { builder ->
            notification_update_time = System.currentTimeMillis()
            val total_progress = downloads.getTotalProgress()

            if (!downloads.any { !it.silent }) {
                builder.setProgress(0, 0, false)
                builder.setOngoing(false)
                builder.setDeleteIntent(notification_delete_intent)

                if (cancelled) {
                    builder.setContentTitle("Download cancelled")
                    builder.setSmallIcon(android.R.drawable.ic_menu_close_clear_cancel)
                }
                else if (completed_downloads == 0) {
                    builder.setContentTitle("Download failed")
                    builder.setSmallIcon(android.R.drawable.stat_notify_error)
                }
                else {
                    NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
                    return
//                    builder.setContentTitle("Download completed")
//                    builder.setSmallIcon(android.R.drawable.stat_sys_download_done)
                }

                builder.setContentText("")
            }
            else {
                builder.setProgress(100, (total_progress * 100).toInt(), false)

                val title = if (downloads.size == 1) {
                    if (downloads.first().song.title != null) {
                        "Downloading ${downloads.first().song.title}"
                    }
                    else {
                        "Downloading 1 song"
                    }
                }
                else {
                    "Downloading ${downloads.size} songs"
                }

                builder.setContentTitle(if (paused) "$title (paused)" else title)
                builder.setContentText(getNotificationText())

                val elapsed_minutes = ((System.currentTimeMillis() - start_time) / 60000f).toInt()
                builder.setSubText(when(elapsed_minutes) {
                    0 -> "Just started"
                    1 -> "Started 1 min ago"
                    else -> "Started $elapsed_minutes mins ago"
                })
            }

            if (ActivityCompat.checkSelfPermission(this, "android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED) {
                try {
                    NotificationManagerCompat.from(this).notify(
                        NOTIFICATION_ID, builder.build().apply {
                            if (downloads.isEmpty() || total_progress == 1f) {
                                actions = arrayOf<Notification.Action>()
                            }
                        }
                    )
                }
                catch (_: Throwable) {}
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
//        val action = intent?.extras?.get("action")
//        if (action is IntentAction) {
//            onActionIntentReceived(action, intent)
//            return super.onStartCommand(intent, flags, startId)
//        }

        return super.onStartCommand(intent, flags, startId)
    }

    private fun getNotificationBuilder(): Notification.Builder {
        val content_intent: PendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this@PlayerDownloadService, PlatformContext.main_activity),
            PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, getNotificationChannel())
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(content_intent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, 0, false)
            .addAction(pause_resume_action)
            .addAction(Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                "Cancel",
                PendingIntent.getService(
                    this,
                    IntentAction.CANCEL_ALL.ordinal,
                    Intent(this, PlayerDownloadService::class.java).putExtra("action", IntentAction.CANCEL_ALL),
                    PendingIntent.FLAG_IMMUTABLE
                )
            ).build())
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                }
            }
    }

    private fun getNotificationChannel(): String {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString("download_service_name"),
            NotificationManager.IMPORTANCE_LOW
        )
        channel.setSound(null, null)

        notification_manager.createNotificationChannel(channel)
        return NOTIFICATION_CHANNEL_ID
    }

    inner class ServiceBinder: PlatformBinder() {
        fun getService(): PlayerDownloadService = this@PlayerDownloadService
    }
    private val binder = ServiceBinder()
    override fun onBind() = binder
}