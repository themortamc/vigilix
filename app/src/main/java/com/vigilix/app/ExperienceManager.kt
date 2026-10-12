package com.vigilix.app

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

/**
 * Nivel de experiencia del usuario. Determina qué UI y features se muestran.
 */
enum class ExperienceLevel {
    BASIC,      // "Modo simple" - 1 botón grande, texto grande, auto
    ADVANCED,   // "Usuario avanzado" - Dashboard completo, ajustes visibles
    EXPERT      // "Desarrollador/Experto" - Todo visible, logs, ADB, raw
}

/**
 * Gestor del nivel de experiencia. Persiste en SharedPreferences y expone LiveData.
 */
class ExperienceManager private constructor(context: Context) {

    companion object {
        const val PREFS_NAME = "vx_experience"
        const val KEY_LEVEL = "experience_level"
        const val KEY_WIZARD_COMPLETED = "wizard_completed"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _level = MutableLiveData<ExperienceLevel>()
    private val _wizardCompleted = MutableLiveData<Boolean>()

    val level: LiveData<ExperienceLevel> = _level
    val wizardCompleted: LiveData<Boolean> = _wizardCompleted

    init {
        val saved = prefs.getString(KEY_LEVEL, null)
        _level.value = saved?.let { ExperienceLevel.valueOf(it) } ?: ExperienceLevel.BASIC
        _wizardCompleted.value = prefs.getBoolean(KEY_WIZARD_COMPLETED, false)
    }

    fun getLevel(): ExperienceLevel = _level.value ?: ExperienceLevel.BASIC

    fun isWizardCompleted(): Boolean = _wizardCompleted.value ?: false

    fun setLevel(newLevel: ExperienceLevel) {
        prefs.edit().putString(KEY_LEVEL, newLevel.name).apply()
        _level.value = newLevel
    }

    fun markWizardCompleted() {
        prefs.edit().putBoolean(KEY_WIZARD_COMPLETED, true).apply()
        _wizardCompleted.value = true
    }

    fun resetWizard() {
        prefs.edit().putBoolean(KEY_WIZARD_COMPLETED, false).apply()
        _wizardCompleted.value = false
    }

    companion object {
        @Volatile private var INSTANCE: ExperienceManager? = null

        fun getInstance(context: Context): ExperienceManager = INSTANCE ?: synchronized(this) {
            INSTANCE ?: ExperienceManager(context.applicationContext).also { INSTANCE = it }
        }
    }
}