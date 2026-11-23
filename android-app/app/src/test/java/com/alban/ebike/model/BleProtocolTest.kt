package com.alban.ebike.model

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class BleProtocolTest {
    @Test fun configDistinguishesPreserveStandardAndSpeed() {
        assertEquals(0, BleProtocol.encodeConfig(2360)[1].toInt())
        assertEquals(2, BleProtocol.encodeConfig(2360, false)[1].toInt())
        assertEquals(3, BleProtocol.encodeConfig(2360, true)[1].toInt())
        assertEquals(8, BleProtocol.encodeConfig(2360, true).size)
    }

}
