package kr.tna;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.api.model.Volume;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.testcontainers.core.CreateContainerCmdModifier;

class RyukLoopbackModifierTest {
    private static final String RYUK_IMAGE = "testcontainers/ryuk:0.14.0";
    private static final ExposedPort RYUK_PORT = ExposedPort.tcp(8080);

    @Test
    void ryukReplacesExternalBindingsWithoutChangingControllerPrivileges() {
        var inherited = HostConfig.newHostConfig().withPublishAllPorts(true).withPortBindings(
                new PortBinding(Ports.Binding.bindIpAndPort("0.0.0.0", 18080), RYUK_PORT),
                new PortBinding(Ports.Binding.bindIpAndPort("::", 18080), RYUK_PORT),
                new PortBinding(Ports.Binding.bindPort(15432), ExposedPort.tcp(5432)));
        var socket = new Bind("/var/run/docker.sock", new Volume("/var/run/docker.sock"));
        for (var config : List.of(HostConfig.newHostConfig(), inherited)) {
            config.withNetworkMode("bridge").withPrivileged(true).withAutoRemove(true)
                    .withBinds(socket).withMemory(33554432L);
            var command = command(RYUK_IMAGE, new ExposedPort[] {RYUK_PORT}, config);

            assertSame(command, new RyukLoopbackModifier().modify(command));

            assertSame(config, command.getHostConfig());
            assertNotNull(config.getPortBindings());
            var bindings = config.getPortBindings().getBindings();
            assertEquals(Set.of(RYUK_PORT), bindings.keySet());
            assertEquals(1, bindings.get(RYUK_PORT).length);
            assertEquals("127.0.0.1", bindings.get(RYUK_PORT)[0].getHostIp());
            assertNull(bindings.get(RYUK_PORT)[0].getHostPortSpec());
            assertFalse(Boolean.TRUE.equals(config.getPublishAllPorts()));
            assertEquals("bridge", config.getNetworkMode());
            assertTrue(config.getPrivileged());
            assertTrue(config.getAutoRemove());
            assertArrayEquals(new Bind[] {socket}, config.getBinds());
            assertEquals(33554432L, config.getMemory());
        }
    }

    @Test
    void otherImagesAndVersionsKeepTheirOriginalConfiguration() {
        for (var image : new String[] {"postgres:18.6-bookworm", "testcontainers/ryuk:0.15.0",
                "untrusted/testcontainers/ryuk:0.14.0", null}) {
            var config = HostConfig.newHostConfig().withPublishAllPorts(true).withPortBindings(
                    new PortBinding(Ports.Binding.bindIpAndPort("0.0.0.0", 18080), RYUK_PORT));
            var originalBindings = config.getPortBindings();
            var command = command(image, new ExposedPort[] {RYUK_PORT}, config);

            assertSame(command, new RyukLoopbackModifier().modify(command));

            assertSame(config, command.getHostConfig());
            assertSame(originalBindings, config.getPortBindings());
            assertTrue(config.getPublishAllPorts());
        }
    }

    @Test
    void pinnedRyukRejectsUnexpectedExposedPorts() {
        for (var ports : new ExposedPort[][] {null, {}, {ExposedPort.tcp(5432)},
                {RYUK_PORT, ExposedPort.tcp(5432)}}) {
            var command = command(RYUK_IMAGE, ports, HostConfig.newHostConfig());

            assertThrows(IllegalStateException.class, () -> new RyukLoopbackModifier().modify(command));
        }
    }

    @Test
    void pinnedRyukRejectsHostNetworkingThatIgnoresPublishedPorts() {
        var command = command(RYUK_IMAGE, new ExposedPort[] {RYUK_PORT},
                HostConfig.newHostConfig().withNetworkMode("host"));

        assertThrows(IllegalStateException.class, () -> new RyukLoopbackModifier().modify(command));
    }

    @Test
    void testClasspathRegistersTheModifierOnce() {
        var providers = ServiceLoader.load(CreateContainerCmdModifier.class).stream()
                .filter(provider -> provider.type() == RyukLoopbackModifier.class)
                .map(ServiceLoader.Provider::get).toList();

        assertEquals(1, providers.size());
    }

    private static CreateContainerCmd command(String image, ExposedPort[] ports, HostConfig config) {
        var command = mock(CreateContainerCmd.class);
        when(command.getImage()).thenReturn(image);
        when(command.getExposedPorts()).thenReturn(ports);
        when(command.getHostConfig()).thenReturn(config);
        return command;
    }
}
