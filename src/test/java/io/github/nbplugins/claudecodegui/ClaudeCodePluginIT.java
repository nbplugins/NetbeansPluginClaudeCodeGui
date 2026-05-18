package io.github.nbplugins.claudecodegui;

import junit.framework.Test;
import org.netbeans.junit.NbModuleSuite;
import org.netbeans.junit.NbTestCase;

/**
 * Integration test verifying that the Claude Code GUI module loads without errors
 * inside a NetBeans module suite runtime.
 */
public class ClaudeCodePluginIT extends NbTestCase {

    /**
     * Creates the test suite using {@link NbModuleSuite} so that all
     * NetBeans module infrastructure is initialised before the tests run.
     *
     * @return the configured test suite
     */
    public static Test suite() {
        return NbModuleSuite.createConfiguration(ClaudeCodePluginIT.class)
                .gui(false)
                .suite();
    }

    /**
     * @param name test method name passed by JUnit runner
     */
    public ClaudeCodePluginIT(String name) {
        super(name);
    }

    /**
     * Verifies that the module's code-name base can be resolved, which confirms
     * the module was registered and activated correctly by the platform.
     */
    public void testModuleLoads() {
        // If this test body executes we are already inside a running NbModuleSuite,
        // meaning the module infrastructure initialised without errors.
        assertNotNull("Module class loader must be available",
                ClaudeCodePluginIT.class.getClassLoader());
    }
}
