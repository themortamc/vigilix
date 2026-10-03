package com.vigilix.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle

/** Copia al portapapeles; los datos sensibles se marcan como tales y se borran solos. */
object ClipboardHelper {
    const val CLEAR_AFTER_SECONDS = 45

    private const val LABEL_SENSITIVE = "vigilix-sensitive"
    private const val LABEL_PLAIN = "vigilix"
    private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

    private val handler = Handler(Looper.getMainLooper())
    private var pendingClear: Runnable? = null

    fun copy(context: Context, text: String, sensitive: Boolean) {
        val manager = context.applicationContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(if (sensitive) LABEL_SENSITIVE else LABEL_PLAIN, text)
        if (sensitive) {
            val extras = PersistableBundle()
            extras.putBoolean(EXTRA_IS_SENSITIVE, true)
            clip.description.extras = extras
        }
        manager.setPrimaryClip(clip)

        pendingClear?.let { handler.removeCallbacks(it) }
        pendingClear = null
        if (sensitive) {
            val task = Runnable { clearIfOurs(manager) }
            pendingClear = task
            handler.postDelayed(task, CLEAR_AFTER_SECONDS * 1000L)
        }
    }

    /** Solo borra si lo copiado sigue siendo lo nuestro (no pisa lo que la persona copió después). */
    private fun clearIfOurs(manager: ClipboardManager) {
        val label = manager.primaryClipDescription?.label?.toString()
        if (label != LABEL_SENSITIVE) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            manager.clearPrimaryClip()
        } else {
            manager.setPrimaryClip(ClipData.newPlainText("", ""))
        }
    }
}
