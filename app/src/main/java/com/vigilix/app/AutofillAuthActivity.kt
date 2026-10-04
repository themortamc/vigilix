package com.vigilix.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * Pantalla breve que aparece sobre la app o el navegador cuando la bóveda está bloqueada:
 * pide la huella (si está activada) o la clave maestra y devuelve las opciones de autocompletado.
 */
class AutofillAuthActivity : AppCompatActivity() {

    private lateinit var fields: LoginFields

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Sin capturas de pantalla mientras se escribe la clave maestra.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_autofill_auth)

        @Suppress("DEPRECATION")
        val usernameIds: List<AutofillId> = intent.getParcelableArrayListExtra(EXTRA_USERNAME_IDS) ?: emptyList()
        @Suppress("DEPRECATION")
        val passwordIds: List<AutofillId> = intent.getParcelableArrayListExtra(EXTRA_PASSWORD_IDS) ?: emptyList()
        fields = LoginFields(usernameIds, passwordIds, intent.getStringExtra(EXTRA_DOMAIN), intent.getStringExtra(EXTRA_PACKAGE))

        if (VaultStore.isUnlocked) {
            finishWithResult()
            return
        }

        val input = findViewById<TextInputEditText>(R.id.etAuthPass)
        val inputLayout = findViewById<TextInputLayout>(R.id.tilAuthPass)
        val error = findViewById<TextView>(R.id.tvAuthError)
        val unlock = findViewById<Button>(R.id.btnAuthUnlock)
        val bio = findViewById<Button>(R.id.btnAuthBio)
        val cancel = findViewById<Button>(R.id.btnAuthCancel)

        unlock.setOnClickListener {
            val password = input.text?.toString().orEmpty()
            inputLayout.error = null
            if (password.isEmpty()) {
                inputLayout.error = getString(R.string.key_empty)
                return@setOnClickListener
            }
            unlock.isEnabled = false
            // Argon2id tarda un instante: en segundo plano para no congelar la pantalla.
            Thread {
                val result = VaultStore.unlockWithPassword(applicationContext, password)
                runOnUiThread {
                    unlock.isEnabled = true
                    if (result == UnlockResult.OK) {
                        input.text?.clear()
                        finishWithResult()
                    } else {
                        error.setText(if (result == UnlockResult.WRONG_KEY) R.string.unlock_wrong else R.string.error_engine_title)
                        error.visibility = TextView.VISIBLE
                    }
                }
            }.start()
        }
        cancel.setOnClickListener {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }

        val bioReady = Prefs.biometricEnabled(this) && BiometricHelper.isAvailable(this)
        bio.visibility = if (bioReady) Button.VISIBLE else Button.GONE
        bio.setOnClickListener { askBiometric() }
        if (bioReady) askBiometric()
    }

    private fun askBiometric() {
        BiometricHelper.unlock(this) { ok -> if (ok) finishWithResult() }
    }

    private fun finishWithResult() {
        val response = AutofillResponses.fillResponse(this, fields)
        VaultStore.touch(this)
        if (response == null) {
            setResult(Activity.RESULT_CANCELED)
        } else {
            setResult(RESULT_OK, Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, response))
        }
        finish()
    }

    companion object {
        const val EXTRA_USERNAME_IDS = "username_ids"
        const val EXTRA_PASSWORD_IDS = "password_ids"
        const val EXTRA_DOMAIN = "domain"
        const val EXTRA_PACKAGE = "package"
    }
}
