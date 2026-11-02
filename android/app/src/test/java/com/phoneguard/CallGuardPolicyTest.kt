package com.phoneguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallGuardPolicyTest {
    @Test
    fun normalizesCountryCodeAndLeadingZero() {
        assertEquals("13800138000", CallGuardPolicy.normalizeNumber("+86 138-0013-8000"))
        assertEquals("13800138000", CallGuardPolicy.normalizeNumber("8613800138000"))
        assertEquals("13800138000", CallGuardPolicy.normalizeNumber("008613800138000"))
        assertEquals("13800138000", CallGuardPolicy.normalizeNumber("013800138000"))
        assertEquals("07711234567", CallGuardPolicy.normalizeNumber("0771-1234567"))
    }

    @Test
    fun stripsRegionSuffixes() {
        assertEquals("广西", CallGuardPolicy.normalizeRegion("广西壮族自治区"))
        assertEquals("北京", CallGuardPolicy.normalizeRegion("北京市"))
        assertEquals("内蒙古", CallGuardPolicy.normalizeRegion("内蒙古自治区"))
        assertEquals("香港", CallGuardPolicy.normalizeRegion("香港特别行政区"))
    }

    @Test
    fun allowsLocalProvinceOrCity() {
        assertTrue(CallGuardPolicy.isLocalRegion("广西壮族自治区", "广西", "南宁"))
        assertTrue(CallGuardPolicy.isLocalRegion("南宁", "广西", "南宁市"))
        assertFalse(CallGuardPolicy.isLocalRegion("广西", "广东", "广州"))
    }

    @Test
    fun blocksNonLocalStrangerWhenProtectionIsOn() {
        val decision = CallGuardPolicy.decide(facts(numberProvince = "广东", numberCity = "广州"))
        assertEquals(CallGuardPolicy.Decision.BLOCK, decision)
    }

    @Test
    fun allowsContactEvenWhenNonLocal() {
        val decision = CallGuardPolicy.decide(
            facts(numberProvince = "广东", numberCity = "广州", inContacts = true),
        )
        assertEquals(CallGuardPolicy.Decision.ALLOW, decision)
    }

    @Test
    fun allowsWhitelistAndBlocksBlacklist() {
        assertEquals(
            CallGuardPolicy.Decision.ALLOW,
            CallGuardPolicy.decide(facts(numberProvince = "广东", inWhitelist = true)),
        )
        assertEquals(
            CallGuardPolicy.Decision.BLOCK,
            CallGuardPolicy.decide(facts(inBlacklist = true)),
        )
    }

    @Test
    fun failsOpenWhenContactsOrLocationAreUnknown() {
        assertEquals(
            CallGuardPolicy.Decision.ALLOW,
            CallGuardPolicy.decide(facts(contactsReadable = false, numberProvince = "广东")),
        )
        assertEquals(
            CallGuardPolicy.Decision.ALLOW,
            CallGuardPolicy.decide(facts(locationKnown = false, numberProvince = "广东")),
        )
        assertEquals(
            CallGuardPolicy.Decision.ALLOW,
            CallGuardPolicy.decide(facts(localRegion = "")),
        )
        assertEquals(
            CallGuardPolicy.Decision.ALLOW,
            CallGuardPolicy.decide(facts(protectionEnabled = false, numberProvince = "广东")),
        )
        assertEquals(
            CallGuardPolicy.Decision.ALLOW,
            CallGuardPolicy.decide(facts(normalizedNumber = "")),
        )
    }

    @Test
    fun doesNotEndCallAgainWhenScreeningRoleIsHeld() {
        assertFalse(CallGuardPolicy.shouldEndCallFromPhoneState(screeningRoleHeld = true))
        assertTrue(CallGuardPolicy.shouldEndCallFromPhoneState(screeningRoleHeld = false))
    }

    @Test
    fun matchesNumbersAcrossFormats() {
        assertTrue(CallGuardPolicy.matchesList(setOf("+86 138-0013-8000"), "13800138000"))
        assertFalse(CallGuardPolicy.matchesList(setOf("13900139000"), "13800138000"))
    }

    private fun facts(
        normalizedNumber: String = "13800138000",
        protectionEnabled: Boolean = true,
        autoBlock: Boolean = true,
        localRegion: String = "广西",
        numberProvince: String = "广西",
        numberCity: String = "南宁",
        locationKnown: Boolean = true,
        inContacts: Boolean = false,
        contactsReadable: Boolean = true,
        inWhitelist: Boolean = false,
        inBlacklist: Boolean = false,
    ): CallGuardPolicy.Facts {
        return CallGuardPolicy.Facts(
            normalizedNumber = normalizedNumber,
            protectionEnabled = protectionEnabled,
            autoBlock = autoBlock,
            localRegion = localRegion,
            numberProvince = numberProvince,
            numberCity = numberCity,
            locationKnown = locationKnown,
            inContacts = inContacts,
            contactsReadable = contactsReadable,
            inWhitelist = inWhitelist,
            inBlacklist = inBlacklist,
        )
    }
}
