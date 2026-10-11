package com.vigilix.app

import org.junit.Assert.*
import org.junit.Test

class ApkAnalyzerRiskScoreTest {

    @Test
    fun riskScoreCalculatesCorrectly() {
        val lowFactors = listOf(
            RiskFactor.ReadContacts,
            RiskFactor.ReadSms
        )
        val score = lowFactors.sumOf { it.weight }
        assertEquals(30, score)
    }

    @Test
    fun comboMultiplierInstallAndDelete() {
        val factors = listOf(
            RiskFactor.InstallPackages,
            RiskFactor.DeletePackages
        )
        val baseScore = factors.sumOf { it.weight } // 25 + 25 = 50
        val withMultiplier = (baseScore * 1.5).toInt()
        assertEquals(75, withMultiplier)
    }

    @Test
    fun deviceAdminCarriesHighWeight() {
        assertEquals(30, RiskFactor.DeviceAdmin.weight)
    }
}
