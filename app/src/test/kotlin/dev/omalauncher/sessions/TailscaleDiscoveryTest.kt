package dev.omalauncher.sessions

import org.junit.Assert.*
import org.junit.Test

class TailscaleDiscoveryTest {
    @Test fun discoversTailnetIpAndStableIdWithoutAssumingSshAvailability() {
        val machines = TailscaleDiscovery.parse("""{"devices":[{
            "id":"123","hostname":"workstation","name":"workstation.example.ts.net",
            "addresses":["fd7a:115c:a1e0::1","100.80.1.2"],"lastSeen":"2026-10-16T12:00:00Z"
        }]}""")
        assertEquals("tailscale-123", machines.single().id)
        assertEquals("100.80.1.2", machines.single().host)
        assertEquals("workstation", machines.single().name)
        assertEquals("", machines.single().user)
        assertEquals("2026-10-16T12:00:00Z", machines.single().lastSeen)
    }

    @Test fun supportsIpv6OnlyAndSkipsDevicesWithoutAddresses() {
        val machines = TailscaleDiscovery.parse("""{"devices":[
            {"id":"1","name":"ipv6.ts.net","addresses":["fd7a:115c:a1e0::1"]},
            {"id":"2","name":"empty.ts.net","addresses":[]}
        ]}""")
        assertEquals(1, machines.size)
        assertEquals("fd7a:115c:a1e0::1", machines.single().host)
    }
}
