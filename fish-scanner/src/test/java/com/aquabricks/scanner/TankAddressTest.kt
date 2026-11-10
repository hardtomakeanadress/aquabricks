package com.aquabricks.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TankAddressTest {

    @Test
    fun bareHostUsesTheDefaultPort() {
        assertEquals("192.168.0.178" to 8765, TankAddress.parse("192.168.0.178"))
        assertEquals("tank.local" to 8765, TankAddress.parse("  tank.local  "))
    }

    @Test
    fun hostAndPortAreAccepted() {
        assertEquals("192.168.0.178" to 9000, TankAddress.parse("192.168.0.178:9000"))
        assertEquals("tank.local" to 8765, TankAddress.parse("http://tank.local:8765/"))
        assertEquals("tank.local" to 8765, TankAddress.parse("https://tank.local:8765"))
    }

    @Test
    fun invalidAddressesAreRejected() {
        assertNull(TankAddress.parse(""))
        assertNull(TankAddress.parse("   "))
        assertNull(TankAddress.parse(":8765"))
        assertNull(TankAddress.parse("192.168.0.178:"))
        assertNull(TankAddress.parse("192.168.0.178:0"))
        assertNull(TankAddress.parse("192.168.0.178:99999"))
        assertNull(TankAddress.parse("host:notaport"))
        assertNull(TankAddress.parse("http://"))
        assertNull(TankAddress.parse("fe80::1"))
        assertNull(TankAddress.parse("bad host"))
        assertNull(TankAddress.parse("tank/path"))
    }
}
