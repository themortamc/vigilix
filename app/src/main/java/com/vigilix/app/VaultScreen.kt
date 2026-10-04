package com.vigilix.app

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.LayoutInflater
import android.view.autofill.AutofillManager
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.vigilix.app.databinding.DialogChangePasswordBinding
import com.vigilix.app.databinding.DialogCredentialBinding
import com.vigilix.app.databinding.ItemCredentialBinding
import com.vigilix.app.databinding.ScreenVaultBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale
import kotlin.math.ln
import kotlin.math.roundToInt

/** Pestaña "Contraseñas": bóveda con usuario, clave y origen de cada una, más herramientas de cifrado. */
class VaultScreen(private val activity: AppCompatActivity, private val v: ScreenVaultBinding) {

    private var vaultEncrypt = true
    private var failedAttempts = 0

    private val exportLauncher = activity.registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri -> if (uri != null) writeExport(uri) }

    private val importLauncher = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) readImport(uri)
    }

    fun setup() {
        v.calloutVaultInfo.show(Status.INFO, activity.getString(R.string.vault_info_title), activity.getString(R.string.vault_info_body))
        v.calloutVaultMsg.hide()
        v.calloutVaultError.hide()

        // Crear / desbloquear
        v.btnCreateVault.setOnClickListener { createVault() }
        v.btnUnlock.setOnClickListener { unlockWithPassword() }
        v.btnUnlockBio.setOnClickListener { unlockWithBiometric() }

        // Bóveda abierta
        v.btnAddItem.setOnClickListener { showCredentialDialog(null) }
        v.btnLockNow.setOnClickListener {
            VaultStore.lock()
            refresh()
        }
        v.btnVaultMenu.setOnClickListener { showMenu() }
        v.etSearch.doAfterTextChanged { renderItems() }

        setupTools()
        refresh()
    }

    /** Muestra el panel que corresponde según el estado de la bóveda. */
    fun refresh() {
        v.calloutVaultMsg.hide()
        val engine = SecurityBridge.isLoaded
        val exists = VaultStore.exists(activity)
        val unlocked = engine && VaultStore.isUnlocked

        v.panelSetup.isVisible = engine && !exists
        v.panelLocked.isVisible = engine && exists && !unlocked
        v.panelOpen.isVisible = unlocked

        if (!engine) {
            showError(R.string.error_engine_title, R.string.error_engine_body)
            return
        }
        if (v.panelLocked.isVisible) {
            val bio = Prefs.biometricEnabled(activity) && BiometricHelper.isAvailable(activity)
            v.btnUnlockBio.isVisible = bio
        }
        if (unlocked) renderItems()
    }

    /** Se llama cuando la app queda en segundo plano un rato: se borra lo sensible de la pantalla. */
    fun wipeScreen() {
        v.etNewPass.text?.clear()
        v.etNewPass2.text?.clear()
        v.etUnlock.text?.clear()
        v.etSearch.text?.clear()
        wipeTools()
    }

    // ---------------------------------------------------------------------------------------------
    // Crear y desbloquear
    // ---------------------------------------------------------------------------------------------

    private fun createVault() {
        val p1 = v.etNewPass.text?.toString().orEmpty()
        val p2 = v.etNewPass2.text?.toString().orEmpty()
        v.tilNewPass.error = null
        v.tilNewPass2.error = null
        if (p1.length < MIN_MASTER_LENGTH) {
            v.tilNewPass.error = activity.getString(R.string.setup_too_short, MIN_MASTER_LENGTH)
            return
        }
        if (p1 != p2) {
            v.tilNewPass2.error = activity.getString(R.string.setup_mismatch)
            return
        }
        v.btnCreateVault.isEnabled = false
        v.btnCreateVault.setText(R.string.working)
        activity.lifecycleScope.launch {
            val ok = withContext(Dispatchers.Default) { VaultStore.create(activity, p1) }
            v.btnCreateVault.isEnabled = true
            v.btnCreateVault.setText(R.string.setup_create)
            if (ok) {
                v.etNewPass.text?.clear()
                v.etNewPass2.text?.clear()
                refresh()
                offerBiometric()
            } else {
                showError(R.string.setup_failed_title, R.string.setup_failed_body)
            }
        }
    }

    private fun offerBiometric() {
        if (!BiometricHelper.isAvailable(activity) || Prefs.biometricEnabled(activity)) return
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.bio_offer_title)
            .setMessage(R.string.bio_offer_body)
            .setPositiveButton(R.string.bio_offer_yes) { _, _ -> enableBiometric() }
            .setNegativeButton(R.string.bio_offer_no, null)
            .show()
    }

    private fun enableBiometric() {
        BiometricHelper.enable(activity) { ok ->
            activity.toast(if (ok) R.string.bio_enabled else R.string.bio_enable_failed)
        }
    }

    private fun unlockWithPassword() {
        val password = v.etUnlock.text?.toString().orEmpty()
        v.tilUnlock.error = null
        if (password.isEmpty()) {
            v.tilUnlock.error = activity.getString(R.string.key_empty)
            return
        }
        v.btnUnlock.isEnabled = false
        v.btnUnlock.setText(R.string.working)
        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) { VaultStore.unlockWithPassword(activity, password) }
            when (result) {
                UnlockResult.OK -> {
                    failedAttempts = 0
                    v.etUnlock.text?.clear()
                }
                UnlockResult.WRONG_KEY -> {
                    failedAttempts++
                    v.tilUnlock.error = activity.getString(R.string.unlock_wrong)
                    if (failedAttempts >= FREE_ATTEMPTS) {
                        // Espera creciente tras varios intentos fallidos (frena adivinar a mano).
                        val wait = minOf(30, (failedAttempts - FREE_ATTEMPTS + 1) * 3)
                        for (left in wait downTo 1) {
                            v.btnUnlock.text = activity.getString(R.string.unlock_wait, left)
                            delay(1000)
                        }
                    }
                }
                UnlockResult.CORRUPT -> showError(R.string.unlock_corrupt_title, R.string.unlock_corrupt_body)
                UnlockResult.ENGINE_UNAVAILABLE -> showError(R.string.error_engine_title, R.string.error_engine_body)
                UnlockResult.NO_VAULT -> Unit
            }
            v.btnUnlock.isEnabled = true
            v.btnUnlock.setText(R.string.unlock_button)
            refresh()
        }
    }

    private fun unlockWithBiometric() {
        BiometricHelper.unlock(activity) { ok ->
            if (ok) {
                refresh()
            } else if (!Prefs.biometricEnabled(activity)) {
                // La clave del Keystore se invalidó (por ejemplo, cambiaron las huellas del teléfono).
                activity.toast(R.string.bio_invalidated)
                refresh()
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Lista de contraseñas
    // ---------------------------------------------------------------------------------------------

    private fun renderItems() {
        if (!VaultStore.isUnlocked) return
        val query = v.etSearch.text?.toString().orEmpty()
        val items = VaultStore.search(query)
        v.tvVaultCount.text = activity.getString(R.string.vault_count, VaultStore.count())
        v.itemsContainer.removeAllViews()
        v.tvItemsEmpty.isVisible = items.isEmpty()
        v.tvItemsEmpty.setText(if (query.isBlank()) R.string.vault_empty else R.string.vault_no_results)

        for (credential in items) {
            val row = ItemCredentialBinding.inflate(activity.layoutInflater, v.itemsContainer, false)
            row.tvCredTitle.text = credential.title.ifEmpty { credential.host ?: credential.packageName }
            row.tvCredSub.text = listOf(credential.username, credential.host ?: credential.packageName)
                .filter { it.isNotEmpty() }
                .joinToString(" · ")
            row.btnCredUser.isEnabled = credential.username.isNotEmpty()
            row.btnCredUser.setOnClickListener { activity.copyToClipboard(credential.username, sensitive = false) }
            row.btnCredPass.setOnClickListener {
                VaultStore.touch(activity)
                activity.copyToClipboard(credential.password, sensitive = true)
            }
            val canOpen = credential.host != null || credential.packageName.isNotEmpty()
            row.btnCredOpen.isVisible = canOpen
            row.btnCredOpen.setOnClickListener { openOrigin(credential) }
            row.rowCredMain.setOnClickListener { showCredentialDialog(credential) }
            v.itemsContainer.addView(row.root)
        }
    }

    private fun openOrigin(c: Credential) {
        val host = c.host
        if (host != null) {
            val url = if (c.url.contains("://")) c.url.trim() else "https://${c.url.trim()}"
            activity.openUrl(url)
        } else {
            val launch = activity.packageManager.getLaunchIntentForPackage(c.packageName)
            if (launch != null) activity.startActivity(launch) else activity.toast(R.string.cred_app_not_found)
        }
    }

    private fun showCredentialDialog(existing: Credential?) {
        val d = DialogCredentialBinding.inflate(LayoutInflater.from(activity))
        var selectedPackage = existing?.packageName.orEmpty()

        fun updateAppLabel() {
            if (selectedPackage.isEmpty()) {
                d.tvCredApp.setText(R.string.cred_app_none)
                d.btnCredClearApp.isVisible = false
            } else {
                val label = try {
                    @Suppress("DEPRECATION")
                    activity.packageManager.getApplicationLabel(activity.packageManager.getApplicationInfo(selectedPackage, 0)).toString()
                } catch (e: Exception) {
                    selectedPackage
                }
                d.tvCredApp.text = activity.getString(R.string.cred_app_selected, label)
                d.btnCredClearApp.isVisible = true
            }
        }

        existing?.let {
            d.etCredTitle.setText(it.title)
            d.etCredUrl.setText(it.url)
            d.etCredUser.setText(it.username)
            d.etCredPass.setText(it.password)
            d.etCredNotes.setText(it.notes)
            d.cbCredFav.isChecked = it.favorite
        }
        updateAppLabel()

        d.btnCredGenerate.setOnClickListener {
            val generated = SecurityBridge.newPassword(DEFAULT_GENERATED_LENGTH)
            if (generated == null) {
                activity.toast(R.string.error_engine_title)
            } else {
                d.etCredPass.setText(generated)
                activity.toast(R.string.cred_generated)
            }
        }
        d.btnCredPickApp.setOnClickListener {
            pickInstalledApp { pkg ->
                selectedPackage = pkg
                updateAppLabel()
            }
        }
        d.btnCredClearApp.setOnClickListener {
            selectedPackage = ""
            updateAppLabel()
        }

        val builder = MaterialAlertDialogBuilder(activity)
            .setTitle(if (existing == null) R.string.cred_add_title else R.string.cred_edit_title)
            .setView(d.root)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
        if (existing != null) builder.setNeutralButton(R.string.cred_delete, null)
        val dialog = builder.create()
        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val title = d.etCredTitle.text?.toString().orEmpty().trim()
            val url = d.etCredUrl.text?.toString().orEmpty().trim()
            val user = d.etCredUser.text?.toString().orEmpty().trim()
            val pass = d.etCredPass.text?.toString().orEmpty()
            d.tilCredPass.error = null
            d.tilCredUrl.error = null
            if (pass.isEmpty()) {
                d.tilCredPass.error = activity.getString(R.string.cred_pass_required)
                return@setOnClickListener
            }
            if (url.isNotEmpty() && Credential.hostOf(url) == null) {
                d.tilCredUrl.error = activity.getString(R.string.cred_url_invalid)
                return@setOnClickListener
            }
            if (title.isEmpty() && url.isEmpty() && selectedPackage.isEmpty()) {
                d.tilCredUrl.error = activity.getString(R.string.cred_origin_required)
                return@setOnClickListener
            }
            val credential = Credential(
                id = existing?.id ?: Credential.newId(),
                title = title.ifEmpty { Credential.hostOf(url) ?: selectedPackage },
                url = url,
                packageName = selectedPackage,
                username = user,
                password = pass,
                notes = d.etCredNotes.text?.toString().orEmpty(),
                favorite = d.cbCredFav.isChecked,
                updatedAt = System.currentTimeMillis(),
            )
            activity.lifecycleScope.launch {
                val saved = withContext(Dispatchers.IO) { VaultStore.upsert(activity, credential) }
                if (saved) {
                    dialog.dismiss()
                    renderItems()
                } else {
                    activity.toast(R.string.cred_save_failed)
                }
            }
        }
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
            if (existing != null) confirmDelete(existing, dialog)
        }
    }

    private fun confirmDelete(c: Credential, parent: AlertDialog) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.cred_delete_title)
            .setMessage(activity.getString(R.string.cred_delete_body, c.title))
            .setPositiveButton(R.string.cred_delete) { _, _ ->
                activity.lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) { VaultStore.delete(activity, c.id) }
                    if (ok) {
                        parent.dismiss()
                        renderItems()
                    } else {
                        activity.toast(R.string.cred_save_failed)
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun pickInstalledApp(onPicked: (String) -> Unit) {
        activity.lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) {
                val pm = activity.packageManager
                val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(launcher, 0)
                    .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
                    .distinctBy { it.first }
                    .filter { it.first != activity.packageName }
                    .sortedBy { it.second.lowercase(Locale.getDefault()) }
            }
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.cred_pick_app)
                .setItems(apps.map { it.second }.toTypedArray()) { _, which -> onPicked(apps[which].first) }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Menú de ajustes de la bóveda
    // ---------------------------------------------------------------------------------------------

    private fun showMenu() {
        val entries = ArrayList<Pair<String, () -> Unit>>()
        if (BiometricHelper.isAvailable(activity)) {
            if (Prefs.biometricEnabled(activity)) {
                entries.add(activity.getString(R.string.menu_bio_off) to {
                    BiometricHelper.disable(activity)
                    activity.toast(R.string.bio_disabled)
                })
            } else {
                entries.add(activity.getString(R.string.menu_bio_on) to { enableBiometric() })
            }
        }
        entries.add(activity.getString(R.string.menu_autolock, autoLockLabel(Prefs.autoLockSeconds(activity))) to { chooseAutoLock() })
        entries.add(
            activity.getString(
                R.string.menu_screen_off,
                activity.getString(if (Prefs.lockOnScreenOff(activity)) R.string.yes else R.string.no),
            ) to { Prefs.setLockOnScreenOff(activity, !Prefs.lockOnScreenOff(activity)) },
        )
        entries.add(activity.getString(autofillMenuLabel()) to { enableAutofill() })
        entries.add(activity.getString(R.string.menu_change_password) to { changePasswordDialog() })
        entries.add(activity.getString(R.string.menu_export) to { exportLauncher.launch("vigilix-boveda.vgv") })
        entries.add(activity.getString(R.string.menu_import) to { importLauncher.launch(arrayOf("*/*")) })

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.vault_menu)
            .setItems(entries.map { it.first }.toTypedArray()) { _, which -> entries[which].second() }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun autoLockLabel(seconds: Int): String = activity.getString(
        when (seconds) {
            0 -> R.string.lock_immediate
            60 -> R.string.lock_1m
            300 -> R.string.lock_5m
            900 -> R.string.lock_15m
            else -> R.string.lock_1m
        },
    )

    private fun chooseAutoLock() {
        val values = intArrayOf(0, 60, 300, 900)
        val labels = values.map { autoLockLabel(it) }.toTypedArray()
        val current = values.indexOf(Prefs.autoLockSeconds(activity)).coerceAtLeast(0)
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.menu_autolock_title)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                Prefs.setAutoLockSeconds(activity, values[which])
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun isAutofillActive(): Boolean {
        val manager = activity.getSystemService(AutofillManager::class.java) ?: return false
        if (!manager.isAutofillSupported || !manager.hasEnabledAutofillServices()) return false
        return try {
            val current = Settings.Secure.getString(activity.contentResolver, "autofill_service")
            val ours = ComponentName(activity, VigilixAutofillService::class.java)
            current != null && ComponentName.unflattenFromString(current) == ours
        } catch (e: Exception) {
            false
        }
    }

    @StringRes
    private fun autofillMenuLabel(): Int = if (isAutofillActive()) R.string.menu_autofill_on else R.string.menu_autofill_off

    private fun enableAutofill() {
        if (isAutofillActive()) {
            activity.toast(R.string.autofill_already)
            return
        }
        try {
            activity.startActivity(
                Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE, Uri.parse("package:${activity.packageName}")),
            )
            activity.toast(R.string.autofill_hint)
        } catch (e: ActivityNotFoundException) {
            activity.toast(R.string.autofill_unsupported)
        }
    }

    private fun changePasswordDialog() {
        val d = DialogChangePasswordBinding.inflate(LayoutInflater.from(activity))
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.menu_change_password)
            .setView(d.root)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val current = d.etPwCurrent.text?.toString().orEmpty()
            val new1 = d.etPwNew.text?.toString().orEmpty()
            val new2 = d.etPwNew2.text?.toString().orEmpty()
            d.tilPwCurrent.error = null
            d.tilPwNew.error = null
            d.tilPwNew2.error = null
            if (current.isEmpty()) {
                d.tilPwCurrent.error = activity.getString(R.string.key_empty)
                return@setOnClickListener
            }
            if (new1.length < MIN_MASTER_LENGTH) {
                d.tilPwNew.error = activity.getString(R.string.setup_too_short, MIN_MASTER_LENGTH)
                return@setOnClickListener
            }
            if (new1 != new2) {
                d.tilPwNew2.error = activity.getString(R.string.setup_mismatch)
                return@setOnClickListener
            }
            val hadBiometric = Prefs.biometricEnabled(activity)
            activity.lifecycleScope.launch {
                val ok = withContext(Dispatchers.Default) { VaultStore.changeMasterPassword(activity, current, new1) }
                if (ok) {
                    dialog.dismiss()
                    activity.toast(if (hadBiometric) R.string.pw_changed_bio_off else R.string.pw_changed)
                } else {
                    d.tilPwCurrent.error = activity.getString(R.string.unlock_wrong)
                }
            }
        }
    }

    // ----- Exportar e importar copias cifradas -----

    private fun writeExport(uri: Uri) {
        val blob = VaultStore.exportBlob(activity)
        if (blob == null) {
            activity.toast(R.string.export_failed)
            return
        }
        activity.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    activity.contentResolver.openOutputStream(uri, "wt")?.use { it.write(blob.toByteArray(Charsets.UTF_8)) } != null
                } catch (e: IOException) {
                    false
                }
            }
            activity.toast(if (ok) R.string.export_done else R.string.export_failed)
        }
    }

    private fun readImport(uri: Uri) {
        if (!VaultStore.isUnlocked) {
            showMessage(Status.WARN, R.string.import_locked_title, R.string.import_locked_body)
            return
        }
        activity.lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                try {
                    activity.contentResolver.openInputStream(uri)?.use { input ->
                        val bytes = input.readBytes()
                        if (bytes.size > MAX_IMPORT_BYTES) null else String(bytes, Charsets.UTF_8)
                    }
                } catch (e: IOException) {
                    null
                }
            }
            if (text == null) {
                activity.toast(R.string.import_failed)
                return@launch
            }
            activity.askText(R.string.import_password_title, R.string.unlock_hint, secret = true, message = R.string.import_password_body) { password ->
                activity.lifecycleScope.launch {
                    val added = withContext(Dispatchers.Default) { VaultStore.importBlob(activity, text, password) }
                    if (added == null) {
                        showMessage(Status.BAD, R.string.import_failed_title, R.string.import_failed_body)
                    } else {
                        showMessage(Status.OK, R.string.import_done_title, null, activity.getString(R.string.import_done_body, added))
                        renderItems()
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Herramientas: generador de contraseñas y cifrado de texto suelto
    // ---------------------------------------------------------------------------------------------

    private fun setupTools() {
        updateLengthLabel()
        v.sbLength.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = updateLengthLabel()
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        v.btnGenerate.setOnClickListener { generatePassword() }
        v.btnCopyPassword.setOnClickListener {
            val password = v.tvPassword.text.toString()
            if (password.isEmpty()) {
                activity.toast(R.string.gen_copy_empty)
            } else {
                activity.copyToClipboard(password, sensitive = true)
            }
        }

        v.toggleMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) setVaultMode(encrypt = checkedId == R.id.btnModeEncrypt)
        }
        v.btnRun.setOnClickListener { runCrypt() }
        v.btnClearVault.setOnClickListener { wipeTools() }
        v.btnCopyResult.setOnClickListener {
            val text = v.tvVaultResult.text.toString()
            if (text.isNotEmpty()) activity.copyToClipboard(text, sensitive = !vaultEncrypt)
        }
    }

    private fun passwordLength(): Int = 12 + v.sbLength.progress * 4

    private fun updateLengthLabel() {
        v.tvLengthLabel.text = activity.getString(R.string.gen_length, passwordLength())
    }

    private fun generatePassword() {
        val length = passwordLength()
        val password = SecurityBridge.newPassword(length)
        if (password == null) {
            showToolError(R.string.error_engine_title, R.string.error_engine_body)
            return
        }
        v.calloutVaultError.hide()
        // El juego de caracteres tiene 88 símbolos: log2(88) ≈ 6,46 bits por carácter.
        val bits = (length * ln(CHARSET_SIZE) / ln(2.0)).roundToInt()
        v.tvPassword.text = password
        v.tvPassword.isVisible = true
        v.tvPasswordMeta.text = activity.getString(R.string.gen_meta, bits)
        v.tvPasswordMeta.isVisible = true
    }

    private fun setVaultMode(encrypt: Boolean) {
        vaultEncrypt = encrypt
        v.tilData.hint = activity.getString(if (encrypt) R.string.data_hint_encrypt else R.string.data_hint_decrypt)
        v.btnRun.setText(if (encrypt) R.string.run_encrypt else R.string.run_decrypt)
        v.etData.text?.clear()
        v.tilData.error = null
        v.tilKey.error = null
        v.calloutVaultError.hide()
        v.panelVaultResult.isVisible = false
        v.tvVaultResult.text = ""
    }

    private fun runCrypt() {
        val encrypt = vaultEncrypt
        val key = v.etKey.text?.toString().orEmpty()
        val data = v.etData.text?.toString().orEmpty()

        v.calloutVaultError.hide()
        v.panelVaultResult.isVisible = false
        v.tilKey.error = null
        v.tilData.error = null

        if (!SecurityBridge.isLoaded) {
            showToolError(R.string.error_engine_title, R.string.error_engine_body)
            return
        }
        if (key.isEmpty()) {
            v.tilKey.error = activity.getString(R.string.key_empty)
            return
        }
        if (encrypt && key.length < MIN_KEY_LENGTH) {
            v.tilKey.error = activity.getString(R.string.key_too_short, MIN_KEY_LENGTH)
            return
        }
        if (data.isBlank()) {
            v.tilData.error = activity.getString(R.string.data_empty)
            return
        }

        v.btnRun.isEnabled = false
        v.btnRun.setText(R.string.working)
        activity.lifecycleScope.launch {
            // Argon2id usa 64 MiB y puede tardar un momento: nunca en el hilo principal.
            val output = withContext(Dispatchers.Default) {
                if (encrypt) SecurityBridge.encrypt(key, data) else SecurityBridge.decrypt(key, data)
            }
            v.btnRun.isEnabled = true
            v.btnRun.setText(if (encrypt) R.string.run_encrypt else R.string.run_decrypt)

            if (output == null) {
                // Los campos NO se borran: la persona no pierde lo que escribió.
                if (encrypt) {
                    showToolError(R.string.error_encrypt_title, R.string.error_encrypt_body)
                } else {
                    showToolError(R.string.error_decrypt_title, R.string.error_decrypt_body)
                }
            } else {
                v.tvVaultResultLabel.setText(if (encrypt) R.string.result_encrypted else R.string.result_decrypted)
                v.tvVaultResult.text = output
                v.panelVaultResult.isVisible = true
            }
        }
    }

    /** Borra de la pantalla todo lo sensible de las herramientas. */
    fun wipeTools() {
        v.etKey.text?.clear()
        v.etData.text?.clear()
        v.tilKey.error = null
        v.tilData.error = null
        v.tvPassword.text = ""
        v.tvPassword.isVisible = false
        v.tvPasswordMeta.isVisible = false
        v.tvVaultResult.text = ""
        v.panelVaultResult.isVisible = false
        v.calloutVaultError.hide()
    }

    // ----- Mensajes -----

    private fun showError(@StringRes title: Int, @StringRes body: Int) {
        v.calloutVaultMsg.show(Status.BAD, activity.getString(title), activity.getString(body))
    }

    private fun showToolError(@StringRes title: Int, @StringRes body: Int) {
        v.calloutVaultError.show(Status.BAD, activity.getString(title), activity.getString(body))
    }

    private fun showMessage(status: Status, @StringRes title: Int, @StringRes body: Int?, bodyText: String? = null) {
        v.calloutVaultMsg.show(status, activity.getString(title), body?.let { activity.getString(it) } ?: bodyText)
    }

    private companion object {
        const val MIN_MASTER_LENGTH = 10
        const val MIN_KEY_LENGTH = 8
        const val FREE_ATTEMPTS = 5
        const val DEFAULT_GENERATED_LENGTH = 20
        const val MAX_IMPORT_BYTES = 20 * 1024 * 1024
        const val CHARSET_SIZE = 88.0
    }
}
