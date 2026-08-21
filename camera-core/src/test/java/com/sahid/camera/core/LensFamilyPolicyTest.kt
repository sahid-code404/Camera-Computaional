package com.sahid.camera.core

import org.junit.Assert.assertEquals
import org.junit.Test

class LensFamilyPolicyTest {
    @Test
    fun commonLowNumberedHalKeepsFastDefaultRange() {
        assertEquals(
            255,
            LensFamilyPolicy.adaptiveNumericScanMax(listOf("0", "1", "20", "61", "101")),
        )
    }

    @Test
    fun highNumberedHalExtendsBeyondObservedEndpoint() {
        assertEquals(
            764,
            LensFamilyPolicy.adaptiveNumericScanMax(listOf("0", "1", "700")),
        )
    }

    @Test
    fun unusualNumberingCannotCreateUnboundedScan() {
        assertEquals(
            1024,
            LensFamilyPolicy.adaptiveNumericScanMax(listOf("0", "5000")),
        )
    }

    @Test
    fun nonNumericPublicIdsDoNotBreakCompatibilityScan() {
        assertEquals(
            255,
            LensFamilyPolicy.adaptiveNumericScanMax(listOf("rear_main", "front")),
        )
    }
}
