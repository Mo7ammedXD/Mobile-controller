package com.mobilecontroller.client

import com.mobilecontroller.client.net.ConnectLink
import com.mobilecontroller.client.net.KnownPcs
import com.mobilecontroller.client.net.KnownPcs.KnownPc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The desktop app's QR link and the list of known PCs. */
class ConnectionTest {
    @Test
    fun theDesktopLinkParses() {
        assertEquals(
            ConnectLink("192.168.1.20", 24681, "Living room PC"),
            ConnectLink.parse("mobilecontroller://connect?host=192.168.1.20&port=24681&name=Living%20room%20PC"),
        )
        // Port and name are optional.
        assertEquals(ConnectLink("gaming-pc.local", 24680, null), ConnectLink.parse("MobileController://Connect?host=gaming-pc.local"))
    }

    @Test
    fun anythingElseIsRefused() {
        for (link in listOf(
            null, "", "https://example.com", "mobilecontroller://other?host=1.2.3.4",
            "mobilecontroller://connect", "mobilecontroller://connect?port=24680",
            "mobilecontroller://connect?host=1.2.3.4&port=0", "mobilecontroller://connect?host=1.2.3.4&port=70000",
            "mobilecontroller://connect?host=1.2.3.4&port=fast", "mobilecontroller://connect?host=300.1.1.1",
            "mobilecontroller://connect?host=evil.com/path", "mobilecontroller://connect?host=a%20b",
            "mobilecontroller://connect?host=1.2.3", "mobilecontroller://connect?host=-bad-.com",
        )) {
            assertNull(link, ConnectLink.parse(link))
        }
    }

    @Test
    fun namesAreCleanedAndBounded() {
        val link = ConnectLink.parse("mobilecontroller://connect?host=10.0.0.2&name=" + "A".repeat(100) + "%0A")!!
        assertEquals(64, link.name!!.length)
    }

    @Test
    fun knownPcsKeepTheMostRecentFirstAndRoundTrip() {
        val pcs = KnownPcs()
        pcs.remember(KnownPc("Den", "192.168.1.20", 24680))
        pcs.remember(KnownPc("Office", "192.168.1.30", 24680))
        // The same PC at a new address replaces its old entry.
        pcs.remember(KnownPc("Den", "192.168.1.44", 24680))
        assertEquals(listOf("Den", "Office"), pcs.all.map { it.name })
        assertEquals("192.168.1.44", pcs.byName("Den")!!.host)

        val copy = KnownPcs.parse(pcs.serialize())
        assertEquals(pcs.all, copy.all)
        for (i in 0 until 20) copy.remember(KnownPc("PC $i", "10.0.0.$i", 24680))
        assertEquals(KnownPcs.MAX, copy.all.size)
        assertEquals(0, KnownPcs.parse("garbage\nmore\tgarbage").all.size)
    }
}
