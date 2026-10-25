package kr.tna;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class Phase6HttpFixtureTest {
    @Test
    void absentGatewayKeepsTheOriginalLoopbackOnlyFixture() {
        assertNull(Phase6HttpFixture.gatewayNetwork(null));
    }

    @Test
    void privateIpv4GatewayAddsExactlyOneHostNetwork() {
        for (String address : List.of("10.0.0.0", "10.255.255.255", "172.16.0.0", "172.18.0.1",
                "172.31.255.255", "192.168.0.0", "192.168.65.1", "192.168.255.255")) {
            assertEquals(address + "/32", Phase6HttpFixture.gatewayNetwork(address), address);
        }
    }

    @Test
    void rejectsNonPrivateNonLiteralAndNonCanonicalGatewayInputs() {
        for (String address : List.of("", " ", "localhost", "host.docker.internal", "172.18.0.1/32",
                "172.18.0.0/16", "172.18.0.1,172.18.0.2", "172.18.0.1\n172.18.0.2",
                "127.0.0.1", "0.0.0.0", "169.254.1.1", "100.64.0.1", "8.8.8.8", "192.0.2.1",
                "224.0.0.1", "255.255.255.255", "9.255.255.255", "11.0.0.0", "172.15.255.255",
                "172.32.0.0", "192.167.255.255", "192.169.0.0", "::1", "fc00::1", "fe80::1",
                "::ffff:172.18.0.1", "[::ffff:172.18.0.1]", "010.0.0.1", "172.018.0.1",
                "172.18.0.01", " 172.18.0.1", "172.18.0.1 ", "10", "10.1", "10.1.1", "167772161",
                "0x0a000001", "10.0.0.256", "10.0.-1.1", "10.0.0.1:5432")) {
            assertThrows(IllegalArgumentException.class, () -> Phase6HttpFixture.gatewayNetwork(address), address);
        }
    }
}
