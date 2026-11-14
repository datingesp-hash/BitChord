package com.music.bitchord.playback.dlna

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.music.bitchord.R
import com.music.bitchord.playback.cast.CastController
import com.music.bitchord.wear.WearBridge

/**
 * Tap the phone on an NFC tag next to a speaker and the music moves there;
 * tap it again and it comes back. The tag holds a plain URI record:
 *
 *     bitchord://speaker?name=Mi%20Smart%20Clock
 *     bitchord://speaker?id=uuid:…            (the renderer's UDN, if names clash)
 *
 * Nothing here is new playback: it picks the renderer the way the output
 * drawer does, so the queue stays on the phone and the app, notification and
 * widgets keep the controls. Only acts while something is loaded to play.
 */
object NfcHandoff {
    private val main = Handler(Looper.getMainLooper())

    /** Main thread. Returns at once; the network part runs in the background. */
    fun handle(context: Context, uri: Uri) {
        val app = context.applicationContext
        val id = uri.getQueryParameter("id")?.trim()?.ifEmpty { null }
        val name = uri.getQueryParameter("name")?.trim()?.ifEmpty { null }
        if (id == null && name == null) return toast(app, app.getString(R.string.nfc_handoff_bad_tag))
        if (DlnaOutput.speaker == null) return toast(app, app.getString(R.string.nfc_handoff_nothing_playing))

        val state = DlnaOutput.state.value
        val playingOn = state.devices.firstOrNull { it.id == state.activeId }
        if (playingOn != null && playingOn.matches(id, name)) {
            // Second tap on the same speaker: back to the phone, playing on.
            DlnaOutput.leave(resumeHere = true)
            return toast(app, app.getString(R.string.nfc_handoff_back_here))
        }

        val known = state.devices.firstOrNull { it.matches(id, name) }
        DlnaOutput.background {
            val device = known ?: Upnp.search().firstOrNull { it.matches(id, name) }
            main.post { moveTo(app, device, name ?: id.orEmpty()) }
        }
    }

    private fun moveTo(app: Context, device: DlnaDevice?, label: String) {
        if (device == null) return toast(app, app.getString(R.string.nfc_handoff_not_found, label))
        // One speaker at a time, as in the drawer.
        if (CastController.state.value.casting) CastController.disconnect(resumeHere = false)
        WearBridge.leaveWatchOutput()
        if (DlnaOutput.select(device)) toast(app, app.getString(R.string.nfc_handoff_moved, device.name))
        else toast(app, app.getString(R.string.nfc_handoff_nothing_playing))
    }

    private fun DlnaDevice.matches(id: String?, name: String?): Boolean =
        (id != null && this.id.equals(id, ignoreCase = true)) ||
            (name != null && this.name.equals(name, ignoreCase = true))

    private fun toast(app: Context, text: String) = Toast.makeText(app, text, Toast.LENGTH_SHORT).show()
}

/**
 * The tag's landing point: no window, no task of its own, gone at once — the
 * app stays wherever it was (often not open at all).
 */
class NfcHandoffActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.data?.let { NfcHandoff.handle(this, it) }
        finish()
    }
}
