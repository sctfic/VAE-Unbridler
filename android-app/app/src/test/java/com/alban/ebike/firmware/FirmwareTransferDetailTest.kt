package com.alban.ebike.firmware

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class FirmwareTransferDetailTest {
    @Test fun literalPercentNeverBecomesAFormatConversion() {
        val previous = Locale.getDefault()
        try {
            for (locale in listOf(Locale.FRANCE, Locale.US)) {
                Locale.setDefault(locale)
                for (received in listOf(0, 3840, 222528, 445056)) {
                    val text = firmwareTransferDetail(received, 445056, 12.34)
                    assertTrue(text.contains("(${received.toLong() * 100 / 445056} %)"))
                    assertTrue(text.endsWith("12,3 Ko/s"))
                    assertTrue(text.contains("$received / 445056 octets"))
                }
            }
        } finally { Locale.setDefault(previous) }
    }
}
