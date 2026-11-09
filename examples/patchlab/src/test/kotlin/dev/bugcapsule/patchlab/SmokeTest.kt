package dev.bugcapsule.patchlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmokeTest {
    private val lab = Cart(listOf(Line("a", 2, 1999), Line("b", 10, 300)), 20)

    private fun check(s: String, d: String, t: String, cart: Cart = lab, label: String = "PATCHED 20%") =
        smokeFailure(cart, Priced(s, d, t, label))

    @Test fun signedFixPasses() {
        assertEquals(6848L, expectedSubtotalCents(lab))
        assertNull(check("68.48", "13.70", "54.78"))
    }

    @Test fun shippedBugFails() = assertEquals("subtotal_mismatch", check("69.98", "1399.60", "-1329.62"))

    @Test fun consistentButWrongFails() = assertEquals("subtotal_mismatch", check("1.00", "0.20", "0.80"))

    @Test fun wrongTotalFails() = assertEquals("total_mismatch", check("68.48", "13.70", "55.78"))

    @Test fun wrongPercentageFails() = assertEquals("discount_mismatch", check("68.48", "6.85", "61.63"))

    @Test fun hugeValuesAreRefusedWithoutOverflow() {
        assertEquals("unparseable_amounts", check("99999999999999999999.00", "0.00", "0.00"))
        assertEquals("unparseable_amounts", check("9223372036854775807.00", "0.00", "0.00"))
        assertEquals("subtotal_mismatch", check("999999999.99", "199999999.99", "800000000.00"))
    }

    @Test fun halfEvenAndCap() {
        assertNull(check("0.25", "0.12", "0.13", Cart(listOf(Line("x", 1, 25)), 50)))
        assertNull(check("10.00", "5.00", "5.00", Cart(listOf(Line("x", 1, 1000)), 80)))
    }

    @Test fun malformedAndEmptyLabelFail() {
        assertEquals("unparseable_amounts", check("68.4", "13.70", "54.78"))
        assertEquals("unparseable_amounts", check("068.48", "13.70", "54.78"))
        assertEquals("empty_label", check("68.48", "13.70", "54.78", label = ""))
    }
}
