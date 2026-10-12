package com.vigilix.app

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.activity.viewModels
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModelProvider
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.vigilix.app.databinding.ActivitySetupWizardBinding
import com.vigilix.app.databinding.FragmentSetupExperienceBinding
import com.vigilix.app.databinding.FragmentSetupFeaturesBinding
import com.vigilix.app.databinding.FragmentSetupPermissionsBinding

/**
 * Wizard de configuración inicial (3 pasos).
 * 1. Selección de nivel de experiencia (Basic/Advanced/Expert)
 * 2. Permisos dinámicos según nivel
 * 3. Features toggles según nivel + Pro
 */
class SetupWizardActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupWizardBinding
    private lateinit var viewModel: SetupWizardViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupWizardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(this, SetupWizardViewModel.Factory(applicationContext))[SetupWizardViewModel::class.java]

        setupViewPager()
        setupTabs()
        observeViewModel()

        binding.btnNext.setOnClickListener { nextStep() }
        binding.btnBack.setOnClickListener { previousStep() }
        binding.btnFinish.setOnClickListener { finishWizard() }
        binding.btnSkip.setOnClickListener { skipWizard() }
    }

    private fun setupViewPager() {
        binding.viewPager.adapter = SetupWizardPagerAdapter(this) as androidx.recyclerview.widget.RecyclerView.Adapter<*>
        binding.viewPager.isUserInputEnabled = false // Solo navegación por botones
    }

    private fun setupTabs() {
        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> getString(R.string.wizard_step_experience)
                1 -> getString(R.string.wizard_step_permissions)
                2 -> getString(R.string.wizard_step_features)
                else -> ""
            }
        }.attach()
    }

    private fun observeViewModel() {
        viewModel.currentStep.observe(this) { step ->
            binding.viewPager.currentItem = step
            updateButtons(step)
        }

        viewModel.wizardCompleted.observe(this) { completed ->
            if (completed) {
                finishWizard()
            }
        }
    }

    private fun updateButtons(step: Int) {
        binding.btnBack.isVisible = step > 0
        binding.btnNext.isVisible = step < 2
        binding.btnFinish.isVisible = step == 2
        binding.btnSkip.isVisible = step < 2
    }

    private fun nextStep() {
        val current = binding.viewPager.currentItem
        if (current < 2) {
            binding.viewPager.currentItem = current + 1
        }
    }

    private fun previousStep() {
        val current = binding.viewPager.currentItem
        if (current > 0) {
            binding.viewPager.currentItem = current - 1
        }
    }

    private fun skipWizard() {
        viewModel.skipWizard()
    }

    private fun finishWizard() {
        viewModel.finishWizard()
        finish()
    }

    override fun onBackPressed() {
        val current = binding.viewPager.currentItem
        if (current > 0) {
            previousStep()
        } else {
            super.onBackPressed()
        }
    }
}

/** Adapter para los 3 fragmentos del wizard. */
class SetupWizardPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {
    override fun getItemCount(): Int = 3

    override fun createFragment(position: Int): Fragment = when (position) {
        0 -> SetupExperienceFragment()
        1 -> SetupPermissionsFragment()
        2 -> SetupFeaturesFragment()
        else -> throw IllegalArgumentException("Invalid position: $position")
    }
}

/** ViewModel compartido entre los 3 pasos. */
class SetupWizardViewModel(private val appContext: android.content.Context) : androidx.lifecycle.ViewModel() {
    private val _currentStep = MutableLiveData<Int>(0)
    val currentStep: androidx.lifecycle.LiveData<Int> = _currentStep

    private val _wizardCompleted = MutableLiveData<Boolean>(false)
    val wizardCompleted: androidx.lifecycle.LiveData<Boolean> = _wizardCompleted

    private val _selectedExperience = MutableLiveData<ExperienceLevel>()
    val selectedExperience: androidx.lifecycle.LiveData<ExperienceLevel> = _selectedExperience

    fun setExperienceLevel(level: ExperienceLevel) {
        _selectedExperience.value = level
    }

    fun nextStep() {
        _currentStep.value = (_currentStep.value ?: 0) + 1
    }

    fun skipWizard() {
        // Si salta, usar nivel por defecto (Basic) y marcar completado
        val exp = ExperienceManager.getInstance(appContext)
        exp.setLevel(ExperienceLevel.BASIC)
        exp.markWizardCompleted()
        _wizardCompleted.value = true
    }

    fun finishWizard() {
        val level = _selectedExperience.value ?: ExperienceLevel.BASIC
        val exp = ExperienceManager.getInstance(appContext)
        exp.setLevel(level)
        exp.markWizardCompleted()
        _wizardCompleted.value = true
    }

    class Factory(private val context: android.content.Context) : androidx.lifecycle.ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            return SetupWizardViewModel(context.applicationContext) as T
        }
    }
}

/** Fragment 1: Selección de nivel de experiencia. */
class SetupExperienceFragment : Fragment() {

    private var _binding: FragmentSetupExperienceBinding? = null
    private val binding get() = _binding!!
    private val viewModel: SetupWizardViewModel by viewModels({ requireActivity() }) {
        SetupWizardViewModel.Factory(requireActivity().applicationContext)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSetupExperienceBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.radioGroupExperience.setOnCheckedChangeListener { _, checkedId ->
            val level = when (checkedId) {
                R.id.radioBasic -> ExperienceLevel.BASIC
                R.id.radioAdvanced -> ExperienceLevel.ADVANCED
                R.id.radioExpert -> ExperienceLevel.EXPERT
                else -> ExperienceLevel.BASIC
            }
            viewModel.setExperienceLevel(level)
        }

        // Seleccionar por defecto Basic
        binding.radioBasic.isChecked = true
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

/** Fragment 2: Permisos dinámicos según nivel. */
class SetupPermissionsFragment : Fragment() {

    private var _binding: FragmentSetupPermissionsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: SetupWizardViewModel by viewModels({ requireActivity() }) {
        SetupWizardViewModel.Factory(requireActivity().applicationContext)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSetupPermissionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel.selectedExperience.observe(viewLifecycleOwner) { level ->
            updatePermissionsForLevel(level)
        }
    }

    private fun updatePermissionsForLevel(level: ExperienceLevel) {
        // Mostrar/ocultar permisos según nivel
        val permissions = when (level) {
            ExperienceLevel.BASIC -> listOf(
                PermissionItem("android.permission.MANAGE_EXTERNAL_STORAGE", "Acceso a archivos", "Para escanear tus archivos", true),
                PermissionItem("android.permission.USE_BIOMETRIC", "Huella digital", "Para desbloquear la bóveda", false)
            )
            ExperienceLevel.ADVANCED -> listOf(
                PermissionItem("android.permission.MANAGE_EXTERNAL_STORAGE", "Acceso a archivos", "Para escanear tus archivos", true),
                PermissionItem("android.permission.USE_BIOMETRIC", "Huella digital", "Para desbloquear la bóveda", true),
                PermissionItem("android.permission.WRITE_SECURE_SETTINGS", "DNS privado", "Cambiar DNS sin root (requiere ADB)", false)
            )
            ExperienceLevel.EXPERT -> listOf(
                PermissionItem("android.permission.MANAGE_EXTERNAL_STORAGE", "Acceso a archivos", "Para escanear tus archivos", true),
                PermissionItem("android.permission.USE_BIOMETRIC", "Huella digital", "Para desbloquear la bóveda", true),
                PermissionItem("android.permission.WRITE_SECURE_SETTINGS", "DNS privado", "Cambiar DNS sin root (requiere ADB)", false),
                PermissionItem("android.permission.BIND_VPN_SERVICE", "Monitor de red", "Ver conexiones de apps (VPN local)", false)
            )
        }

        binding.permissionsContainer.removeAllViews()
        permissions.forEach { perm ->
            val row = createPermissionRow(perm)
            binding.permissionsContainer.addView(row)
        }
    }

    private fun createPermissionRow(item: PermissionItem): View {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 16, 0, 16)
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        val textLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val titleView = android.widget.TextView(requireContext()).apply {
            text = item.title
            setTextAppearance(com.google.android.material.R.style.TextAppearance_MaterialComponents_Body1)
            setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.vx_text))
        }

        val descView = android.widget.TextView(requireContext()).apply {
            text = item.description
            setTextAppearance(com.google.android.material.R.style.TextAppearance_MaterialComponents_Caption)
            setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.vx_text_muted))
        }

        textLayout.addView(titleView)
        textLayout.addView(descView)

        val check = com.google.android.material.checkbox.MaterialCheckBox(requireContext()).apply {
            isChecked = item.required
            isEnabled = !item.required
        }

        row.addView(textLayout)
        row.addView(check)
        return row
    }

    data class PermissionItem(
        val permission: String,
        val title: String,
        val description: String,
        val required: Boolean
    )
}

/** Fragment 3: Features toggles según nivel + Pro. */
class SetupFeaturesFragment : Fragment() {

    private var _binding: FragmentSetupFeaturesBinding? = null
    private val binding get() = _binding!!
    private val viewModel: SetupWizardViewModel by viewModels({ requireActivity() }) {
        SetupWizardViewModel.Factory(requireActivity().applicationContext)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSetupFeaturesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel.selectedExperience.observe(viewLifecycleOwner) { level ->
            updateFeaturesForLevel(level)
        }

        binding.btnViewPlans.setOnClickListener {
            // Ir a pantalla de suscripción / planes Pro
            android.widget.Toast.makeText(requireContext(), getString(R.string.wizard_view_plans), android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateFeaturesForLevel(level: ExperienceLevel) {
        val baseFeatures = when (level) {
            ExperienceLevel.BASIC -> listOf(
                FeatureItem("Escáner de virus/archivos", "Análisis por hash SHA-256", true),
                FeatureItem("Bóveda de contraseñas", "Almacén seguro local cifrado", true)
            )
            ExperienceLevel.ADVANCED -> listOf(
                FeatureItem("Escáner de virus/archivos", "Análisis por hash SHA-256", true),
                FeatureItem("Bóveda de contraseñas", "Almacén seguro local cifrado", true),
                FeatureItem("Monitor de permisos", "Detección de accesos a cámara/mic", true),
                FeatureItem("DNS seguro", "AdGuard / Quad9 para bloqueo de anuncios", true)
            )
            ExperienceLevel.EXPERT -> listOf(
                FeatureItem("Escáner de virus/archivos", "Análisis por hash SHA-256", true),
                FeatureItem("Bóveda de contraseñas", "Almacén seguro local cifrado", true),
                FeatureItem("Monitor de permisos", "Detección de accesos a cámara/mic", true),
                FeatureItem("DNS seguro", "AdGuard / Quad9 para bloqueo de anuncios", true),
                FeatureItem("Monitor de red VPN", "Análisis de tráfico de apps en tiempo real", true),
                FeatureItem("Análisis de APKs", "Inspección estática de manifiestos y riesgo", true),
                FeatureItem("PIN de Coacción", "Bóveda falsa ante PIN secundario", true)
            )
        }

        val proFeatures = listOf(
            FeatureItem(getString(R.string.pro_feature_cloud_sync), "Sincronización segura WebDAV", false),
            FeatureItem(getString(R.string.pro_feature_behavior_analysis), "Sandbox dinámico de ejecución", false),
            FeatureItem(getString(R.string.pro_feature_extra_themes), "Personalización completa Material You", false)
        )

        binding.featuresContainer.removeAllViews()
        baseFeatures.forEach { feat ->
            binding.featuresContainer.addView(createFeatureSwitch(feat, isPro = false))
        }

        binding.proFeaturesContainer.removeAllViews()
        proFeatures.forEach { feat ->
            binding.proFeaturesContainer.addView(createFeatureSwitch(feat, isPro = true))
        }
    }

    private fun createFeatureSwitch(item: FeatureItem, isPro: Boolean): View {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 12, 0, 12)
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        val textLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val titleView = android.widget.TextView(requireContext()).apply {
            text = if (isPro) "${item.title} (Pro)" else item.title
            setTextAppearance(com.google.android.material.R.style.TextAppearance_MaterialComponents_Body2)
            setTextColor(androidx.core.content.ContextCompat.getColor(context, if (isPro) R.color.vx_accent else R.color.vx_text))
        }

        val descView = android.widget.TextView(requireContext()).apply {
            text = item.description
            setTextAppearance(com.google.android.material.R.style.TextAppearance_MaterialComponents_Caption)
            setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.vx_text_muted))
        }

        textLayout.addView(titleView)
        textLayout.addView(descView)

        val switch = com.google.android.material.switchmaterial.SwitchMaterial(requireContext()).apply {
            isChecked = item.enabled
            isEnabled = !isPro
        }

        row.addView(textLayout)
        row.addView(switch)
        return row
    }

    data class FeatureItem(
        val title: String,
        val description: String,
        val enabled: Boolean
    )

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}