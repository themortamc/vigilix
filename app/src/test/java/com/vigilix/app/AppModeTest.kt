package com.vigilix.app

import org.junit.Assert.*
import org.junit.Test

class AppModeTest {

    @Test
    fun rootModeIsDistinguishedFromNonRoot() {
        val root: AppMode = AppMode.Root(hasMagisk = true, hasKernelSu = false, hasDebug = false)
        val nonRoot: AppMode = AppMode.NonRoot(hasDebug = false, hasAdbWireless = false)

        assertTrue(root is AppMode.Root)
        assertFalse(root is AppMode.NonRoot)
        assertTrue(nonRoot is AppMode.NonRoot)
        assertFalse(nonRoot is AppMode.Root)
    }

    @Test
    fun rootModeDataFields() {
        val root = AppMode.Root(hasMagisk = false, hasKernelSu = true, hasDebug = true)
        assertTrue(root.hasKernelSu)
        assertTrue(root.hasDebug)
        assertFalse(root.hasMagisk)
    }
}
