package com.vigilix.app

import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest

/**
 * Autocompletado del sistema para las contraseñas guardadas en Vigilix.
 *
 * Seguridad: solo ofrece entradas cuyo dominio coincide exactamente (o es subdominio) con el que informa
 * el sistema, o cuya app vinculada tiene el mismo nombre de paquete. Si la bóveda está bloqueada, exige
 * desbloquearla (huella o clave maestra) antes de mostrar o rellenar nada.
 */
class VigilixAutofillService : AutofillService() {

    override fun onFillRequest(request: FillRequest, cancellationSignal: CancellationSignal, callback: FillCallback) {
        val structure = request.fillContexts.lastOrNull()?.structure
        if (structure == null) {
            callback.onSuccess(null)
            return
        }
        val fields = AutofillParser.parse(structure)
        // Nunca se autocompleta dentro de la propia Vigilix ni en pantallas sin campos de inicio de sesión.
        if (!fields.isLogin || fields.packageName == packageName || !VaultStore.exists(this)) {
            callback.onSuccess(null)
            return
        }
        if (!VaultStore.isUnlocked) {
            callback.onSuccess(AutofillResponses.lockedResponse(this, fields))
            return
        }
        VaultStore.touch(this)
        callback.onSuccess(AutofillResponses.fillResponse(this, fields))
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        // Guardar contraseñas nuevas desde el autocompletado no está implementado en esta versión.
        callback.onSuccess()
    }
}
