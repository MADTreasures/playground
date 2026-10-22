package ch.madtreasures.fluency.glasses

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import androidx.test.core.app.ApplicationProvider
import ch.madtreasures.fluency.BuildConfig
import ch.madtreasures.fluency.FluencyApp
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder

/** The door for G2 Handy: who gets in, and that the messages reach the bridge and come back. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GlassesServiceTest {
    private val app: FluencyApp = ApplicationProvider.getApplicationContext()

    private data class Reply(val what: Int, val text: String?, val message: String?)

    private val replies = mutableListOf<Reply>()
    private val client = Messenger(
        Handler(Looper.getMainLooper()) { m ->
            replies += Reply(m.what, m.data.getString(GlassesService.KEY_TEXT), m.data.getString(GlassesService.KEY_MESSAGE))
            true
        },
    )

    @Before
    fun setUp() {
        val pm = shadowOf(app.packageManager)
        pm.setPackagesForUid(G2_UID, GlassesService.G2_PACKAGE)
        pm.setPackagesForUid(OTHER_UID, "com.example.listener")
        pm.installPackage(
            PackageInfo().apply {
                packageName = GlassesService.G2_PACKAGE
                applicationInfo = ApplicationInfo().apply { packageName = GlassesService.G2_PACKAGE }
                // Robolectric keeps the certificate here (its SigningInfo stays empty)
                @Suppress("DEPRECATION")
                signatures = arrayOf(Signature(CERT_A))
            },
        )
    }

    @Test
    fun answersOnlyTheLinkAction() {
        val service = Robolectric.buildService(GlassesService::class.java).create().get()
        assertNull(service.onBind(Intent("ch.madtreasures.other.action.LINK")))
        assertNotNull(service.onBind(Intent(GlassesService.ACTION_LINK)))
    }

    @Test
    fun g2HandyGetsInAndTheGlassesTalkToFluency() {
        settings(glasses = true, certificate = "")
        val door = bind()
        send(door, GlassesService.OPEN) { putString(GlassesService.KEY_APP, "ch.madtreasures.fluency"); putString(GlassesService.KEY_VERSION, "1.1.0") }
        until("opened") { replies.isNotEmpty() }
        assertEquals(GlassesService.OPENED, replies.removeAt(0).what)
        assertEquals("Mit der Brille verbunden (App 1.1.0)", app.container.glassesStatus.value)
        // its certificate is remembered for the next time
        until("certificate") { app.container.settingsRepository.settings.value.glassesCert == Certificates.sha256(CERT_A) }

        send(door, GlassesService.MSG) { putString(GlassesService.KEY_TEXT, """{"t":"hello","proto":"fluency-g2@1","app":"1.1.0"}""") }
        until("welcome") { replies.isNotEmpty() }
        val welcome = replies.removeAt(0)
        assertEquals(GlassesService.MSG, welcome.what)
        JSONObject(welcome.text!!).let {
            assertEquals("welcome", it.getString("t"))
            assertEquals("fluency-g2@1", it.getString("proto"))
            assertEquals(BuildConfig.VERSION_NAME, it.getString("app"))
        }

        // another app cannot talk into the open connection
        send(door, GlassesService.MSG, uid = OTHER_UID) { putString(GlassesService.KEY_TEXT, """{"t":"hello","proto":"fluency-g2@1"}""") }
        settle()
        assertTrue(replies.isEmpty())

        // G2 Handy closes: nothing more gets through
        send(door, GlassesService.CLOSE)
        send(door, GlassesService.MSG) { putString(GlassesService.KEY_TEXT, """{"t":"hello","proto":"fluency-g2@1"}""") }
        settle()
        assertTrue(replies.isEmpty())
        assertEquals("Bereit, wartet auf die Brille (G2 Handy)", app.container.glassesStatus.value)
    }

    @Test
    fun otherAppsAreRefused() {
        settings(glasses = true, certificate = "")
        val door = bind()
        send(door, GlassesService.OPEN, uid = OTHER_UID)
        until("refused") { replies.isNotEmpty() }
        assertEquals(Reply(GlassesService.CLOSE, null, "Fluency nimmt nur G2 Handy an, nicht com.example.listener"), replies.single())
        assertEquals("Abgelehnt: Fluency nimmt nur G2 Handy an, nicht com.example.listener", app.container.glassesStatus.value)
    }

    @Test
    fun nothingGetsInWhileTheSwitchIsOff() {
        settings(glasses = false, certificate = "")
        val door = bind()
        send(door, GlassesService.OPEN)
        until("refused") { replies.isNotEmpty() }
        assertEquals(Reply(GlassesService.CLOSE, null, "In Fluency ist „Brille (G2 Watch)“ aus"), replies.single())
    }

    @Test
    fun g2HandyWithAnotherCertificateIsRefused() {
        settings(glasses = true, certificate = Certificates.sha256(CERT_B))
        val door = bind()
        send(door, GlassesService.OPEN)
        until("refused") { replies.isNotEmpty() }
        assertEquals(Reply(GlassesService.CLOSE, null, "G2 Handy mit anderem Zertifikat (Fluency → Optionen)"), replies.single())
    }

    @Test
    fun switchingOffClosesTheConnection() {
        settings(glasses = true, certificate = Certificates.sha256(CERT_A))
        val door = bind()
        send(door, GlassesService.OPEN)
        until("opened") { replies.isNotEmpty() }
        assertEquals(GlassesService.OPENED, replies.removeAt(0).what)
        app.container.settingsRepository.update { it.copy(glasses = false) }
        until("closed") { replies.isNotEmpty() }
        assertEquals(Reply(GlassesService.CLOSE, null, "In Fluency ausgeschaltet"), replies.single())
    }

    @Test
    fun certificatesAreShownShort() {
        assertEquals("01:02:03:04 … 1D:1E:1F:20", Certificates.short((1..32).joinToString("") { "%02X".format(it) }))
        assertEquals("AB:CD", Certificates.short("ABCD"))
    }

    private fun bind(): IBinder {
        val intent = Intent(GlassesService.ACTION_LINK)
        val service = Robolectric.buildService(GlassesService::class.java, intent).create().get()
        return service.onBind(intent)!!
    }

    private fun send(door: IBinder, what: Int, uid: Int = G2_UID, fill: Bundle.() -> Unit = {}) {
        ShadowBinder.setCallingUid(uid) // Messenger stamps the message with the caller, as across processes
        Messenger(door).send(Message.obtain(null, what).apply { data.fill(); replyTo = client })
        settle()
    }

    private fun settings(glasses: Boolean, certificate: String) {
        app.container.settingsRepository.update { it.copy(glasses = glasses, glassesCert = certificate) }
        until("settings") { app.container.settingsRepository.settings.value.let { it.glasses == glasses && it.glassesCert == certificate } }
    }

    /** Lets the main thread run what is due (the settings are read from disk on another thread). */
    private fun settle() {
        repeat(5) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
    }

    private fun until(what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!condition()) {
            if (System.currentTimeMillis() > end) fail("waited in vain: $what")
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    companion object {
        private const val G2_UID = 10_123
        private const val OTHER_UID = 10_456
        private val CERT_A = ByteArray(64) { it.toByte() }
        private val CERT_B = ByteArray(64) { (it * 7).toByte() }
    }
}
