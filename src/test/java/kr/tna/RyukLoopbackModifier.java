package kr.tna;

import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import org.testcontainers.core.CreateContainerCmdModifier;

/** Limits the cleanup controller's published port in this project's local Docker tests. */
public final class RyukLoopbackModifier implements CreateContainerCmdModifier {
    private static final String RYUK_IMAGE = "testcontainers/ryuk:0.14.0";
    private static final ExposedPort RYUK_PORT = ExposedPort.tcp(8080);

    @Override
    public CreateContainerCmd modify(CreateContainerCmd command) {
        if (!RYUK_IMAGE.equals(command.getImage())) {
            return command;
        }
        var ports = command.getExposedPorts();
        if (ports == null || ports.length != 1 || !RYUK_PORT.equals(ports[0])) {
            throw new IllegalStateException("Pinned Ryuk must expose only 8080/tcp");
        }
        var config = command.getHostConfig();
        if ("host".equals(config.getNetworkMode())) {
            throw new IllegalStateException("Local Ryuk requires a network with published ports");
        }
        config.withPublishAllPorts(false).withPortBindings(
                new PortBinding(Ports.Binding.bindIp("127.0.0.1"), RYUK_PORT));
        return command;
    }
}
