package kr.tna;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DatabaseFixtureLoopbackTest {
    @Test
    void ownedPostgresReplacesExternalBindingsWithOneEphemeralLoopbackPort() {
        var postgres = ExposedPort.tcp(5432);
        var inherited = HostConfig.newHostConfig().withPublishAllPorts(true).withPortBindings(
                new PortBinding(Ports.Binding.bindIpAndPort("0.0.0.0", 15432), postgres),
                new PortBinding(Ports.Binding.bindIpAndPort("::", 15432), postgres),
                new PortBinding(Ports.Binding.bindPort(18080), ExposedPort.tcp(8080)));
        for (var config : List.of(HostConfig.newHostConfig(), inherited)) {
            config.withNetworkMode("bridge");
            var command = mock(CreateContainerCmd.class);
            when(command.getHostConfig()).thenReturn(config);

            DatabaseFixture.bindOwnedPostgresToLoopback(command);

            assertNotNull(config.getPortBindings());
            var bindings = config.getPortBindings().getBindings();
            assertEquals(Set.of(postgres), bindings.keySet());
            assertEquals(1, bindings.get(postgres).length);
            assertEquals("127.0.0.1", bindings.get(postgres)[0].getHostIp());
            assertNull(bindings.get(postgres)[0].getHostPortSpec());
            assertFalse(Boolean.TRUE.equals(config.getPublishAllPorts()));
            assertEquals("bridge", config.getNetworkMode());
        }
    }
}
