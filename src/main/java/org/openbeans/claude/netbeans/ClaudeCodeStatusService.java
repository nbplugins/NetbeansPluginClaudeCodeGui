package org.openbeans.claude.netbeans;

import io.github.nbplugins.claudecodegui.proxy.OpenAIProxyConfig;
import io.github.nbplugins.claudecodegui.settings.ProxyConfiguration;

/**
 * Service interface for accessing Claude Code integration status.
 * This service is registered in the global lookup to provide status information
 * without requiring direct access to the ModuleInstall singleton.
 */
public interface ClaudeCodeStatusService {
    
    /**
     * Gets the current status of the Claude Code integration.
     * 
     * @return status information as a formatted string
     */
    String getStatus();
    
    /**
     * Checks if the MCP server is currently running.
     * 
     * @return true if the server is running, false otherwise
     */
    boolean isServerRunning();
    
    /**
     * Gets the port number the MCP server is running on.
     * 
     * @return port number, or -1 if server is not running
     */
    int getServerPort();
    
    /**
     * Checks if the lock file is valid and accessible.
     *
     * @return true if lock file is valid, false otherwise
     */
    boolean isLockFileValid();

    /**
     * Registers an OpenAI-compatible proxy session.
     * Called by {@code ClaudeProcess} at session start.
     *
     * @param uuid    unique session identifier
     * @param baseUrl OpenAI-compatible endpoint base URL
     * @param apiKey  API key for the provider
     * @param proxy   proxy settings from the profile
     */
    void registerOpenAIProxy(String uuid, String baseUrl, String apiKey, ProxyConfiguration proxy);

    /**
     * Deregisters an OpenAI-compatible proxy session.
     * Called by {@code ClaudeProcess} at session stop.
     *
     * @param uuid unique session identifier previously passed to {@link #registerOpenAIProxy}
     */
    void deregisterOpenAIProxy(String uuid);

    /**
     * Returns the proxy config for the given session UUID, or {@code null} if not found.
     *
     * @param uuid unique session identifier
     * @return config snapshot, or {@code null}
     */
    OpenAIProxyConfig getOpenAIProxyConfig(String uuid);
}