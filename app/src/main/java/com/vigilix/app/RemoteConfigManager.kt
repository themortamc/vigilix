package com.vigilix.app

import android.content.Context
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.ktx.andAwait
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Wrapper para Firebase Remote Config con fetch automático y valores por defecto.
 */
class RemoteConfigManager private constructor(context: Context) {

    private val remoteConfig: FirebaseRemoteConfig = FirebaseRemoteConfig.getInstance()
    private val initialized = MutableLiveData<Boolean>()

    val isInitialized: LiveData<Boolean> = initialized

    init {
        val defaults = mapOf(
            "kill_switch" to false,
            "vt_enabled_default" to true,
            "scan_interval_hours" to 24L,
            "vpn_interval_seconds" to 300L,
            "max_scan_file_mb" to 2000L,
            "feature_duress_pin" to true,
            "feature_apk_analyzer" to true,
            "feature_network_monitor" to true,
            "feature_behavior_analysis" to false,
            "pro_features_enabled" to false,
            "vt_daily_limit" to 480L,
            "max_scan_file_mb" to 2000L
        )

        remoteConfig.setDefaultsAsync(defaults)
        fetchAndActivate()
    }

    /** Fuerza fetch y activación de nuevos valores. */
    fun fetchAndActivate() {
        remoteConfig.fetchAndActivate().addOnCompleteListener { task ->
            initialized.postValue(task.isSuccessful)
        }
    }

    /** Obtiene un valor booleano. */
    fun getBoolean(key: String): Boolean = remoteConfig.getBoolean(key)

    /** Obtiene un valor long. */
    fun getLong(key: String): Long = remoteConfig.getLong(key)

    /** Obtiene un valor string. */
    fun getString(key: String): String = remoteConfig.getString(key)

    /** Obtiene un valor double. */
    fun getDouble(key: String): Double = remoteConfig.getDouble(key)

    companion object {
        @Volatile private var INSTANCE: RemoteConfigManager? = null

        fun getInstance(context: Context): RemoteConfigManager = INSTANCE ?: synchronized(this) {
            INSTANCE ?: RemoteConfigManager(context.applicationContext).also { INSTANCE = it }
        }
    }
}