package com.example

import com.example.util.OptionUtils
import org.json.JSONObject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class OptionUtilsTest {

    @Test
    fun testCalculateAtmStrikeNifty() {
        // Nifty strike interval = 50
        assertEquals(24200, OptionUtils.calculateAtmStrike(24180.0, 50))
        assertEquals(24200, OptionUtils.calculateAtmStrike(24210.0, 50))
        assertEquals(24250, OptionUtils.calculateAtmStrike(24230.0, 50))
    }

    @Test
    fun testCalculateAtmStrikeBankNifty() {
        // BankNifty strike interval = 100
        assertEquals(51500, OptionUtils.calculateAtmStrike(51520.0, 100))
        assertEquals(51600, OptionUtils.calculateAtmStrike(51560.0, 100))
    }

    @Test
    fun testGetStrikeWithOffset() {
        // Spot = 24230 -> ATM = 24250
        // Offset +1 CE (OTM) = 24300
        assertEquals(24300, OptionUtils.getStrikeWithOffset(24230.0, 1, 50))
        // Offset -1 PE (OTM) = 24200
        assertEquals(24200, OptionUtils.getStrikeWithOffset(24230.0, -1, 50))
    }

    @Test
    fun testFindOptionSymbolFromChain() {
        val json = JSONObject("""
            {
                "options": [
                    {"strike": 24200, "optionType": "CE", "symbol": "NSE:NIFTY26OCT24200CE"},
                    {"strike": 24200, "optionType": "PE", "symbol": "NSE:NIFTY26OCT24200PE"}
                ]
            }
        """)

        val ceSymbol = OptionUtils.findOptionSymbolFromChain(json, 24200, "CE")
        assertNotNull(ceSymbol)
        assertEquals("NSE:NIFTY26OCT24200CE", ceSymbol)

        val peSymbol = OptionUtils.findOptionSymbolFromChain(json, 24200, "PE")
        assertNotNull(peSymbol)
        assertEquals("NSE:NIFTY26OCT24200PE", peSymbol)
    }
}
