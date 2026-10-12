package com.vigilix.app

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExperienceManagerTest {

    private lateinit var experienceManager: ExperienceManager

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        // Reset singleton for test isolation
        ExperienceManager.getInstance(context).resetForTesting()
        experienceManager = ExperienceManager.getInstance(context)
    }

    @Test
    fun testDefaultExperienceLevelIsBasic() {
        assertEquals(ExperienceLevel.BASIC, experienceManager.getLevel())
        assertFalse(experienceManager.isWizardCompleted())
    }

    @Test
    fun testSetExperienceLevel() {
        experienceManager.setLevel(ExperienceLevel.EXPERT)
        assertEquals(ExperienceLevel.EXPERT, experienceManager.getLevel())
    }

    @Test
    fun testWizardCompletedFlow() {
        experienceManager.markWizardCompleted()
        assertTrue(experienceManager.isWizardCompleted())

        experienceManager.resetWizard()
        assertFalse(experienceManager.isWizardCompleted())
    }
}
