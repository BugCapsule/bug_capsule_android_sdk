package dev.bugcapsule.android.replay

import org.junit.Assert.*
import org.junit.Test

class KeyboardInjectionTest {
    @Test fun retriesDeniedStartOnceAfterKeyboardTransition() {
        var attempts = 0
        var waited = false
        injectWithKeyboardRetry(true, { waited = true; true }) {
            if (++attempts == 1) throw SecurityException("IME still intercepts")
            assertTrue(waited)
        }
        assertEquals(2, attempts)
    }

    @Test fun doesNotDuplicateSuccessfulInput() {
        var attempts = 0
        injectWithKeyboardRetry(true, { error("Must not wait") }) { attempts++ }
        assertEquals(1, attempts)
    }

    @Test fun propagatesRepeatedDenial() {
        var attempts = 0
        assertThrows(SecurityException::class.java) {
            injectWithKeyboardRetry(true, { true }) { attempts++; throw SecurityException("Denied") }
        }
        assertEquals(2, attempts)
    }

    @Test fun doesNotRetryWithoutKeyboardChangeOrAfterLosingFocus() {
        for (changed in listOf(false, true)) {
            var attempts = 0
            assertThrows(SecurityException::class.java) {
                injectWithKeyboardRetry(changed, { false }) { attempts++; throw SecurityException("Other window") }
            }
            assertEquals(1, attempts)
        }
    }
}
