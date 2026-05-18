package io.github.nbplugins.claudecodegui.mcp.tools;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.openide.windows.TopComponent;
import org.openbeans.claude.netbeans.tools.params.GetOpenEditorsParams;
import org.openbeans.claude.netbeans.tools.params.GetOpenEditorsResult;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TC-05 regression: getOpenEditors used getCurrentNodes() which only returns
 * the focused node. When the user has editor tabs open but focus is elsewhere
 * (e.g. Project Explorer), getCurrentNodes() returns an empty array and the
 * tool incorrectly reports "No editor tabs are currently open."
 *
 * Fix: use TopComponent.getRegistry().getOpened() to iterate ALL open TCs.
 */
public class GetOpenEditorsTest {

    @Test
    public void testUsesOpenedRegistryNotCurrentNodes() throws Exception {
        AtomicBoolean openedWasCalled = new AtomicBoolean(false);

        GetOpenEditors tool = new GetOpenEditors() {
            @Override
            protected Set<TopComponent> getOpenedTopComponents() {
                openedWasCalled.set(true);
                return Collections.emptySet();
            }
        };

        GetOpenEditorsResult result = tool.run(new GetOpenEditorsParams());

        assertTrue(openedWasCalled.get(),
                "getOpenEditors must use getOpened() (all open TCs), not getCurrentNodes() "
                + "(only the focused node). TC-05 regression: editors open but not focused "
                + "were incorrectly reported as absent.");
        assertNotNull(result.getEditors());
    }
}
