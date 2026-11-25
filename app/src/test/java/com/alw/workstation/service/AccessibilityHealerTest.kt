package com.alw.workstation.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityHealerTest {

    @Test
    fun parseEnabledServicesReadsFlatAndShortForms() {
        val flat = "com.other.pkg/com.other.Service:" + AccessibilityHealer.FLAT_SERVICE_ENTRY
        val short = "com.other.pkg/com.other.Service:" + AccessibilityHealer.SHORT_SERVICE_ENTRY

        val flatEntries = AccessibilityHealer.parseEnabledServices(flat)
        val shortEntries = AccessibilityHealer.parseEnabledServices(short)

        assertTrue(flatEntries.any { AccessibilityHealer.isOurServiceEntry(it) })
        assertTrue(shortEntries.any { AccessibilityHealer.isOurServiceEntry(it) })
        assertEquals(2, flatEntries.size)
        assertEquals(2, shortEntries.size)
    }

    @Test
    fun parseEnabledServicesToleratesEmptyAndNullValues() {
        assertTrue(AccessibilityHealer.parseEnabledServices(null).isEmpty())
        assertTrue(AccessibilityHealer.parseEnabledServices("").isEmpty())
        assertTrue(AccessibilityHealer.parseEnabledServices("null").isEmpty())
        assertTrue(AccessibilityHealer.parseEnabledServices("NULL").isEmpty())
        assertTrue(AccessibilityHealer.parseEnabledServices(" : ").isEmpty())
    }

    @Test
    fun isOurServiceEntryMatchesBothStoredSpellingsOnly() {
        assertTrue(AccessibilityHealer.isOurServiceEntry(AccessibilityHealer.FLAT_SERVICE_ENTRY))
        assertTrue(AccessibilityHealer.isOurServiceEntry(AccessibilityHealer.SHORT_SERVICE_ENTRY))
        assertFalse(AccessibilityHealer.isOurServiceEntry("com.other.pkg/com.other.Service"))
        assertFalse(AccessibilityHealer.isOurServiceEntry("com.alw.workstation/.service.OtherService"))
    }

    @Test
    fun mergeServiceEntryAppendsToExistingServices() {
        val merged = AccessibilityHealer.mergeServiceEntry("com.other.pkg/com.other.Service")

        assertEquals(
            "com.other.pkg/com.other.Service:" + AccessibilityHealer.FLAT_SERVICE_ENTRY,
            merged
        )
    }

    @Test
    fun mergeServiceEntryFromEmptySetting() {
        assertEquals(
            AccessibilityHealer.FLAT_SERVICE_ENTRY,
            AccessibilityHealer.mergeServiceEntry(null)
        )
        assertEquals(
            AccessibilityHealer.FLAT_SERVICE_ENTRY,
            AccessibilityHealer.mergeServiceEntry("")
        )
        assertEquals(
            AccessibilityHealer.FLAT_SERVICE_ENTRY,
            AccessibilityHealer.mergeServiceEntry("null")
        )
    }

    @Test
    fun mergeServiceEntryNeverDuplicatesAndPreservesStoredSpelling() {
        val flat = AccessibilityHealer.FLAT_SERVICE_ENTRY
        assertEquals(flat, AccessibilityHealer.mergeServiceEntry(flat))

        val short = "com.other.pkg/com.other.Service:" + AccessibilityHealer.SHORT_SERVICE_ENTRY
        assertEquals(short, AccessibilityHealer.mergeServiceEntry(short))
    }

    @Test
    fun removeServiceEntryStripsOurServiceOnly() {
        val flatOnly = AccessibilityHealer.FLAT_SERVICE_ENTRY
        assertEquals("", AccessibilityHealer.removeServiceEntry(flatOnly))

        val shortOnly = AccessibilityHealer.SHORT_SERVICE_ENTRY
        assertEquals("", AccessibilityHealer.removeServiceEntry(shortOnly))

        val multiple = "com.other.pkg/com.other.Service:" + AccessibilityHealer.FLAT_SERVICE_ENTRY
        assertEquals("com.other.pkg/com.other.Service", AccessibilityHealer.removeServiceEntry(multiple))

        val none = "com.other.pkg/com.other.Service"
        assertEquals("com.other.pkg/com.other.Service", AccessibilityHealer.removeServiceEntry(none))

        assertEquals("", AccessibilityHealer.removeServiceEntry(null))
        assertEquals("", AccessibilityHealer.removeServiceEntry(""))
    }

    @Test
    fun storedEntryConstantsAreCanonical() {
        assertEquals(
            "com.alw.workstation/com.alw.workstation.service.AlwKeyInterceptService",
            AccessibilityHealer.FLAT_SERVICE_ENTRY
        )
        assertEquals(
            "com.alw.workstation/.service.AlwKeyInterceptService",
            AccessibilityHealer.SHORT_SERVICE_ENTRY
        )
    }
}
