package org.openbeans.claude.netbeans;

import io.github.nbplugins.claudecodegui.mcp.MCPSseServer;
import io.github.nbplugins.claudecodegui.mcp.NetBeansMCPHandler;
import java.net.ServerSocket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TC-16: MCPSseServer must accept a fixed port and fail-fast if it is busy.
 */
public class MCPSseServerTest {

    @Test
    public void testStartOnFreePort() throws Exception {
        int port = findFreePort();
        MCPSseServer server = new MCPSseServer(new NetBeansMCPHandler());
        try {
            assertTrue(server.start(port), "start() must return true for a free port");
            assertTrue(server.isRunning());
            assertEquals(port, server.getPort());
        } finally {
            server.stop();
        }
    }

    @Test
    public void testStartOnBusyPortReturnsFalse() throws Exception {
        int port = findFreePort();
        // Hold the port so MCPSseServer cannot bind it
        try (ServerSocket blocker = new ServerSocket(port)) {
            MCPSseServer server = new MCPSseServer(new NetBeansMCPHandler());
            assertFalse(server.start(port), "start() must return false when port is busy");
            assertFalse(server.isRunning());
        }
    }

    private static int findFreePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}
