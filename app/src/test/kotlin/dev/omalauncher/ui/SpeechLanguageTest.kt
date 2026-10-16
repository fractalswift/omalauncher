package dev.omalauncher.ui

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeechLanguageTest {
    @Test fun installedRegionalVariantWinsOverUnavailableExactLocale() {
        assertEquals("en-US", speechLanguage(Locale.UK, listOf(listOf("en-US"), listOf("en-GB"))))
    }

    @Test fun exactInstalledLocaleWinsWhenPresent() {
        assertEquals("en-GB", speechLanguage(Locale.UK, listOf(listOf("en-US", "en-GB"))))
    }

    @Test fun unrelatedInstalledLanguageDoesNotReplaceUsersLanguage() {
        assertNull(speechLanguage(Locale.UK, listOf(listOf("de-DE"))))
    }

    @Test fun noInstalledModelCanBeDetectedBeforeStartingMicrophone() {
        assertNull(speechLanguage(Locale.UK, listOf(emptyList())))
    }
}
