package ch.madtreasures.fluency.glasses

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.os.BundleCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import ch.madtreasures.fluency.BuildConfig
import ch.madtreasures.fluency.FluencyApp
import ch.madtreasures.fluency.MainActivity
import ch.madtreasures.fluency.R
import ch.madtreasures.fluency.engine.asr.SAMPLE_RATE
import ch.madtreasures.fluency.engine.audio.MicrophoneSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.security.MessageDigest

/**
 * The door for the app on the G2 glasses. "G2 Handy", the phone half of the G2 Watch app ([G2_PACKAGE]),
 * binds this service with [ACTION_LINK] and passes on what the glasses say, as Messenger messages:
 * [OPEN], [MSG], [CLOSE], [AUDIO_START], [AUDIO_STOP]; Fluency answers with [OPENED], [MSG], [CLOSE]
 * (PLATTFORM.md §6.2 of packages/fluency in MADTreasures/ER-G2-custom). The app's messages go to a
 * [GlassesBridge], the sound of the watch to [WatchAudio].
 *
 * Only G2 Handy gets in, with the certificate it had at its first connection, and only while "Brille
 * (G2 Watch)" is on. While the switch is on, the service runs in the foreground: Android lets Fluency
 * use the phone's microphone from the background only so.
 */
class GlassesService : LifecycleService() {
    private val c get() = (application as FluencyApp).container
    private val main = Handler(Looper.getMainLooper())
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { msg -> handle(msg); true })
    private val watchAudio = WatchAudio()

    /**
     * Where the pipelines run, like the screens' pipelineScope. Not cancelled with the service: a pipeline
     * that is given up still releases its models in it.
     */
    private val pipelineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var link: Link? = null

    /** the foreground service types in use; 0: not in the foreground */
    private var foreground = 0

    /** why the last connection was refused, until one opens */
    private var refused: String? = null
    private var notified: String? = null

    /** The connection to G2 Handy: who opened it, where answers go, and the bridge for the app on the glasses. */
    private inner class Link(val uid: Int, val client: Messenger, val version: String) {
        var activity: String? = null
        val bridge = GlassesBridge(
            scope = lifecycleScope,
            pipelines = Pipelines { config, audio, state, speak ->
                val p = c.pipelines.build(config, audio, pipelineScope, state, speak).pipeline
                object : BridgePipeline {
                    override fun start() = p.start()
                    override suspend fun stop() = p.stop()
                    override fun cancel() = p.cancel()
                }
            },
            audio = { source -> if (source == G2Protocol.Source.WATCH) watchAudio.source() else MicrophoneSource() },
            send = { text -> reply(this@Link, MSG, KEY_TEXT, text) },
            version = BuildConfig.VERSION_NAME,
            micProblem = ::micProblem,
            watchSoundAt = { watchAudio.lastSoundAt },
            onActivity = {
                activity = it
                status()
            },
        )
        val death = IBinder.DeathRecipient { main.post { if (link === this) close(null) } }
    }

    override fun onCreate() {
        super.onCreate()
        lifecycleScope.launch {
            // switched off in the settings (only after it was on: a fresh "on" may still be on its way to the disk)
            var wasOn = false
            c.settingsRepository.settings.map { it.glasses }.distinctUntilChanged().collect { on ->
                if (on) wasOn = true else if (wasOn) turnOff()
            }
        }
        status()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_OFF) {
            c.settingsRepository.update { it.copy(glasses = false) }
            turnOff()
        } else {
            goForeground()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return if (intent.action == ACTION_LINK) messenger.binder else null
    }

    override fun onUnbind(intent: Intent): Boolean {
        close(null)
        return false
    }

    override fun onDestroy() {
        close(null)
        watchAudio.detach()
        c.glassesStatus.value = null
        super.onDestroy()
    }

    private fun handle(msg: Message) {
        if (msg.what == OPEN) {
            val client = msg.replyTo ?: return
            open(msg.sendingUid, client, msg.data.getString(KEY_VERSION).orEmpty())
            return
        }
        val l = link ?: return
        if (msg.sendingUid != l.uid) return // only the app that opened the connection
        when (msg.what) {
            MSG -> msg.data.getString(KEY_TEXT)?.let(l.bridge::receive)
            CLOSE -> close(null)
            AUDIO_START -> {
                val pfd = BundleCompat.getParcelable(msg.data, KEY_PFD, ParcelFileDescriptor::class.java) ?: return
                if (msg.data.getInt(KEY_RATE, SAMPLE_RATE) == SAMPLE_RATE) {
                    watchAudio.attach(ParcelFileDescriptor.AutoCloseInputStream(pfd))
                } else {
                    runCatching { pfd.close() } // another format than 16 kHz PCM: the glasses hear "Kein Ton von der Uhr"
                }
            }
            AUDIO_STOP -> watchAudio.detach()
        }
    }

    private fun open(uid: Int, client: Messenger, version: String) {
        lifecycleScope.launch {
            val why = refusal(uid)
            if (why != null) {
                refused = why
                send(client, CLOSE, KEY_MESSAGE, why)
                status()
                return@launch
            }
            close("Eine neue Verbindung hat diese ersetzt")
            val l = Link(uid, client, version)
            link = l
            refused = null
            runCatching { client.binder.linkToDeath(l.death, 0) }
            send(client, OPENED)
            status()
        }
    }

    /** Why [uid] may not connect, or null. The first time, G2 Handy's certificate is remembered. */
    private suspend fun refusal(uid: Int): String? {
        val s = c.settingsRepository.loaded()
        if (!s.glasses) return "In Fluency ist „Brille (G2 Watch)“ aus"
        val packages = packageManager.getPackagesForUid(uid).orEmpty()
        if (G2_PACKAGE !in packages) return "Fluency nimmt nur G2 Handy an, nicht ${packages.firstOrNull() ?: "uid $uid"}"
        val certificates = Certificates.sha256(packageManager, G2_PACKAGE)
        if (certificates.isEmpty()) return "G2 Handy ohne Zertifikat"
        if (s.glassesCert.isEmpty()) {
            c.settingsRepository.update { if (it.glassesCert.isEmpty()) it.copy(glassesCert = certificates.last()) else it }
            return null
        }
        return if (s.glassesCert in certificates) null else "G2 Handy mit anderem Zertifikat (Fluency → Optionen)"
    }

    /** Ends the connection; [message]: tells G2 Handy why. */
    private fun close(message: String?) {
        val l = link ?: return
        link = null
        l.bridge.close()
        watchAudio.detach()
        runCatching { l.client.binder.unlinkToDeath(l.death, 0) }
        if (message != null) send(l.client, CLOSE, KEY_MESSAGE, message)
        status()
    }

    private fun reply(l: Link, what: Int, key: String, value: String) {
        if (link !== l) return
        if (!send(l.client, what, key, value)) main.post { if (link === l) close(null) } // G2 Handy is gone
    }

    private fun send(to: Messenger, what: Int, key: String? = null, value: String? = null): Boolean {
        val m = Message.obtain(null, what)
        if (key != null) m.data.putString(key, value)
        return try {
            to.send(m)
            true
        } catch (_: RemoteException) {
            false
        }
    }

    private fun micProblem(): String? = when {
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED -> "Fluency darf das Handy-Mikrofon nicht nutzen"
        (foreground and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) == 0 -> "Fürs Handy-Mikrofon Fluency einmal öffnen"
        else -> null
    }

    private fun goForeground() {
        val device = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        for (types in listOfNotNull(if (mic) device or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else null, device)) {
            try {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(statusLine()), types)
                foreground = types
                status()
                return
            } catch (_: Exception) {
                // refused: started from the background, or the microphone while Fluency is not on screen
            }
        }
        foreground = 0
        stopSelf()
        status()
    }

    private fun turnOff() {
        close("In Fluency ausgeschaltet")
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        foreground = 0
        notified = null
        stopSelf()
        status()
    }

    private fun statusLine(): String {
        val l = link
        return when {
            l?.activity != null -> "Übersetzt für die Brille: ${l.activity}"
            l != null -> "Mit der Brille verbunden" + l.version.takeIf { it.isNotEmpty() }?.let { " (App $it)" }.orEmpty()
            refused != null -> "Abgelehnt: $refused"
            else -> "Bereit, wartet auf die Brille (G2 Handy)"
        }
    }

    private fun status() {
        val line = statusLine()
        c.glassesStatus.value = line
        if (foreground != 0 && line != notified) {
            notified = line
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(line))
        }
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Brille (G2 Watch)", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val off = PendingIntent.getService(this, 1, Intent(this, GlassesService::class.java).setAction(ACTION_OFF), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_glasses)
            .setContentTitle("Fluency für die Brille")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Ausschalten", off)
            .build()
    }

    companion object {
        /** the intent action G2 Handy binds with */
        const val ACTION_LINK = "ch.madtreasures.g2watch.action.LINK"

        /** G2 Handy, the phone half of the G2 Watch app: the only app let in */
        const val G2_PACKAGE = "ch.madtreasures.g2watch"

        /** G2 Handy → Fluency: open a connection. Data: [KEY_APP], [KEY_VERSION] of the app on the glasses. */
        const val OPEN = 1

        /** Fluency → G2 Handy: the connection is open. */
        const val OPENED = 2

        /** Both ways: a message of the app ([KEY_TEXT]), passed on unchanged. */
        const val MSG = 3

        /** Both ways: the connection ends; Fluency says why in [KEY_MESSAGE] (also when it refuses an [OPEN]). */
        const val CLOSE = 4

        /** G2 Handy → Fluency: the watch's sound. Data: [KEY_PFD] (read end of a pipe, PCM s16le mono), [KEY_RATE] (16000). */
        const val AUDIO_START = 5

        /** G2 Handy → Fluency: no more sound for now. */
        const val AUDIO_STOP = 6

        const val KEY_APP = "app"
        const val KEY_VERSION = "version"
        const val KEY_TEXT = "text"
        const val KEY_MESSAGE = "message"
        const val KEY_PFD = "pfd"
        const val KEY_RATE = "rate"

        private const val ACTION_OFF = "ch.madtreasures.fluency.action.GLASSES_OFF"
        private const val CHANNEL = "glasses"
        private const val NOTIFICATION_ID = 43

        /** Runs the service in the foreground; only from Fluency's screen, Android allows the microphone so. */
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, GlassesService::class.java)) }
        }
    }
}

/** The certificates an app is signed with, as SHA-256 (hex, upper case). */
internal object Certificates {
    /** Also those it rotated away from, the current one last. */
    @Suppress("DEPRECATION") // PackageInfo.signatures: only where signingInfo is empty (Robolectric)
    fun sha256(pm: PackageManager, pkg: String): List<String> {
        val info = try {
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
        } catch (_: PackageManager.NameNotFoundException) {
            return emptyList()
        }
        // the history is null for an app with several signers; then all of them count
        val signatures = info.signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory ?: it.apkContentsSigners }
            ?.takeIf { it.isNotEmpty() } ?: info.signatures
        return signatures.orEmpty().map { sha256(it.toByteArray()) }
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02X".format(it) }

    /** "3A:5F:01:9C … 7E:22:C2:0D" */
    fun short(hex: String): String {
        val bytes = hex.chunked(2)
        return if (bytes.size <= 8) bytes.joinToString(":") else bytes.take(4).joinToString(":") + " … " + bytes.takeLast(4).joinToString(":")
    }
}
