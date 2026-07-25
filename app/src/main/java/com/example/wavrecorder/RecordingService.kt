package com.example.wavrecorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Owns the actual [WavRecorder] and runs as a foreground service so recording survives the
 * screen turning off or the app being backgrounded. Android blocks microphone access from
 * background apps entirely since API 28 — a foreground service (with the "microphone" type
 * declared) is the only way around that, and it requires showing a persistent notification
 * for the duration, which is intentional: the user should always be able to see (and stop)
 * an in-progress recording, even from the lock screen.
 *
 * This is both a *started* service (survives all clients unbinding) and a *bound* service
 * (lets [RecordFragment] talk to it live while visible). It self-stops the moment recording
 * ends, rather than lingering.
 */
class RecordingService : Service() {

    companion object {
        private const val CHANNEL_ID = "recording_channel"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP = "com.example.wavrecorder.action.STOP"
    }

    interface Listener {
        fun onAmplitude(amplitude: Float)
        fun onSegmentStarted(target: OutputTarget, partNumber: Int)
        fun onError(e: Exception)
        fun onStopped(lastTarget: OutputTarget?)
    }

    inner class LocalBinder : Binder() {
        fun getService(): RecordingService = this@RecordingService
    }

    private val binder = LocalBinder()
    private val recorder = WavRecorder()
    private lateinit var destinationManager: DestinationManager

    private var sessionTimestamp: String? = null
    private val partCounter = AtomicInteger(0)
    private var currentTarget: OutputTarget? = null
    private var currentPartNumber = 1

    var listener: Listener? = null

    val isRecording: Boolean get() = recorder.isActive
    val lastTarget: OutputTarget? get() = currentTarget
    val lastPartNumber: Int get() = currentPartNumber

    override fun onCreate() {
        super.onCreate()
        destinationManager = DestinationManager(applicationContext)
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRecording()
        }
        return START_NOT_STICKY
    }

    fun startRecording() {
        if (recorder.isActive) return
        sessionTimestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        partCounter.set(0)
        currentPartNumber = 1

        startForegroundCompat(buildNotification(1))

        recorder.start(
            context = applicationContext,
            nextTarget = WavRecorder.NextTarget {
                val part = partCounter.incrementAndGet()
                val fileName = "recording_${sessionTimestamp}_part${String.format(Locale.US, "%02d", part)}.wav"
                destinationManager.createOutputFile(fileName)
            },
            onSegmentStarted = { target ->
                currentTarget = target
                currentPartNumber = partCounter.get()
                updateNotification(currentPartNumber)
                listener?.onSegmentStarted(target, currentPartNumber)
            },
            onAmplitude = { amplitude -> listener?.onAmplitude(amplitude) },
            onError = { e ->
                // The recording thread already flipped its internal flag to false before this
                // fires, which used to make WavRecorder.stop() a silent no-op and leak the mic
                // (AudioRecord never released) on any mid-recording failure. stop() is now safe
                // to call here unconditionally.
                recorder.stop()
                listener?.onError(e)
                finishRecording()
            }
        )

        if (!recorder.isActive) {
            // AudioRecord setup failed synchronously inside recorder.start(); nothing to run.
            finishRecording()
        }
    }

    fun stopRecording() {
        if (!recorder.isActive) return
        val target = currentTarget
        recorder.stop()
        listener?.onStopped(target)
        finishRecording()
    }

    private fun finishRecording() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // App swiped away from Recents while recording: save what we have rather than leaving
        // a foreground service running with no UI left able to stop it.
        if (recorder.isActive) stopRecording()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        if (recorder.isActive) recorder.stop()
        super.onDestroy()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(part: Int): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, RecordingService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val text = if (part > 1) getString(R.string.status_recording_part, part) else getString(R.string.status_recording)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification_mic)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPendingIntent)
            .addAction(0, getString(R.string.stop_recording), stopPendingIntent)
            .build()
    }

    private fun updateNotification(part: Int) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(part))
    }
}
