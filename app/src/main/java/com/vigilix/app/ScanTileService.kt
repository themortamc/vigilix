package com.vigilix.app

import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi

/**
 * Quick Settings Tile para "Escanear ahora".
 * Lanza ScanService en modo rápido y actualiza el estado del tile.
 */
class ScanTileService : TileService() {

    override fun onTileAdded() {
        super.onTileAdded()
        requestListeningState(this)
    }

    override fun onTileRemoved() {
        super.onTileRemoved()
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val tile = qsTile
        // Toggle: si estaba activo, cancela; si inactivo, inicia escaneo rápido
        if (tile.state == Tile.STATE_ACTIVE) {
            ScanService.stop(this)
            tile.state = Tile.STATE_INACTIVE
        } else {
            ScanService.start(this, full = false, useVirusTotal = false)
            tile.state = Tile.STATE_ACTIVE
        }
        tile.updateTile()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun updateTile() {
        val tile = qsTile
        val isScanning = ScanEngine.state.value.running
        tile.state = if (isScanning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_scan)
        // subtitle requiere API 29 (Q)
        tile.subtitle = if (isScanning) getString(R.string.tile_scanning) else getString(R.string.tile_ready)
        // Icono: usar ic_scan del drawable
        tile.icon = Icon.createWithResource(this, R.drawable.ic_scan)
        tile.updateTile()
    }

    /** Solicita al sistema que mantenga el tile escuchando. */
    private fun requestListeningState(context: android.content.Context) {
        // Android 7.0+ requiere que el usuario añada el tile manualmente.
        // No se puede forzar, solo solicitar.
        try {
            val intent = Intent("android.settings.QUICK_SETTINGS_SETTINGS")
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
        } catch (e: Exception) {
            // Ignore
        }
    }
}