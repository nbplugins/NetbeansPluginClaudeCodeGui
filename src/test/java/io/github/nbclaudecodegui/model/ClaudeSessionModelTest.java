package io.github.nbclaudecodegui.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ClaudeSessionModel} in isolation — no Swing, no PTY.
 *
 * <p>Listener notifications are verified by capturing arguments via atomic
 * references. Because {@code ClaudeSessionModel.fireOnEdt} runs synchronously
 * when already on the EDT (which is the case in unit tests), notifications
 * arrive before each setter returns.
 */
class ClaudeSessionModelTest {

    private ClaudeSessionModel model;

    @BeforeEach
    void setUp() {
        model = new ClaudeSessionModel();
        // Clean up any registry entries between tests
        ClaudeSessionModel.EDIT_MODE_REGISTRY.clear();
    }

    // -------------------------------------------------------------------------
    // initial state
    // -------------------------------------------------------------------------

    @Test
    void initialLifecycleIsStarting() {
        assertEquals(SessionLifecycle.STARTING, model.getLifecycle());
    }

    @Test
    void initialEditModeIsNull() {
        assertNull(model.getEditMode());
    }

    @Test
    void initialDirectoryIsNull() {
        assertNull(model.getWorkingDirectory());
    }

    // -------------------------------------------------------------------------
    // setLifecycle
    // -------------------------------------------------------------------------

    @Test
    void setLifecycleFiresListener() {
        AtomicReference<SessionLifecycle> captured = new AtomicReference<>();
        model.addListener(new NoOpListener() {
            @Override public void onLifecycleChanged(SessionLifecycle state) {
                captured.set(state);
            }
        });

        model.setLifecycle(SessionLifecycle.READY);

        assertEquals(SessionLifecycle.READY, model.getLifecycle());
        assertEquals(SessionLifecycle.READY, captured.get());
    }

    // -------------------------------------------------------------------------
    // setEditMode
    // -------------------------------------------------------------------------

    @Test
    void setEditModeFiresListener() {
        AtomicReference<EditMode> captured = new AtomicReference<>();
        model.addListener(new NoOpListener() {
            @Override public void onEditModeChanged(EditMode mode) {
                captured.set(mode);
            }
        });

        model.setEditMode(EditMode.ACCEPT_EDITS);

        assertEquals(EditMode.ACCEPT_EDITS, model.getEditMode());
        assertEquals(EditMode.ACCEPT_EDITS, captured.get());
    }

    @Test
    void setEditModeWritesRegistryWhenDirectorySet() {
        File dir = new File("/tmp/test-dir");
        model.setWorkingDirectory(dir);
        model.setEditMode(EditMode.PLAN);

        assertEquals(EditMode.PLAN, ClaudeSessionModel.EDIT_MODE_REGISTRY.get(dir.getAbsolutePath()));
    }

    @Test
    void setEditModeWithNullDirectoryDoesNotCrash() {
        // directory is null → registry update skipped silently
        assertNull(model.getWorkingDirectory());
        assertDoesNotThrow(() -> model.setEditMode(EditMode.ACCEPT_EDITS));
        assertEquals(EditMode.ACCEPT_EDITS, model.getEditMode());
    }

    @Test
    void setEditModeNullRemovesRegistryEntry() {
        File dir = new File("/tmp/test-dir");
        model.setWorkingDirectory(dir);
        model.setEditMode(EditMode.PLAN);
        assertNotNull(ClaudeSessionModel.EDIT_MODE_REGISTRY.get(dir.getAbsolutePath()));

        model.setEditMode(null);
        assertNull(ClaudeSessionModel.EDIT_MODE_REGISTRY.get(dir.getAbsolutePath()));
    }

    // -------------------------------------------------------------------------
    // setWorkingDirectory
    // -------------------------------------------------------------------------

    @Test
    void setWorkingDirectoryFiresListener() {
        AtomicReference<File> captured = new AtomicReference<>();
        model.addListener(new NoOpListener() {
            @Override public void onWorkingDirectoryChanged(File dir) {
                captured.set(dir);
            }
        });

        File dir = new File("/tmp/my-project");
        model.setWorkingDirectory(dir);

        assertEquals(dir, model.getWorkingDirectory());
        assertEquals(dir, captured.get());
    }

    // -------------------------------------------------------------------------
    // setModelList
    // -------------------------------------------------------------------------

    @Test
    void setModelListFiresListener() {
        AtomicReference<List<String>> capturedModels = new AtomicReference<>();
        AtomicInteger capturedIdx = new AtomicInteger(-99);

        model.addListener(new NoOpListener() {
            @Override public void onModelListChanged(List<String> models, int selectedIdx) {
                capturedModels.set(models);
                capturedIdx.set(selectedIdx);
            }
        });

        List<String> input = List.of("Sonnet 4.6", "Opus 4.6");
        model.setModelList(input, 1);

        assertEquals(input, capturedModels.get());
        assertEquals(1, capturedIdx.get());
        assertEquals(input, model.getAvailableModels());
        assertEquals(1, model.getSelectedModelIndex());
    }

    // -------------------------------------------------------------------------
    // setActiveChoiceMenu / clearChoiceMenu
    // -------------------------------------------------------------------------

    @Test
    void setActiveChoiceMenuFiresListener() {
        AtomicReference<ChoiceMenuModel> captured = new AtomicReference<>();
        model.addListener(new NoOpListener() {
            @Override public void onChoiceMenuChanged(ChoiceMenuModel menu) {
                captured.set(menu);
            }
        });

        ChoiceMenuModel menu = new ChoiceMenuModel("Continue?",
                List.of(new ChoiceMenuModel.Option("Yes", "y"),
                        new ChoiceMenuModel.Option("No", "n")), 0);
        model.setActiveChoiceMenu(menu);

        assertSame(menu, model.getActiveChoiceMenu());
        assertSame(menu, captured.get());
    }

    @Test
    void clearChoiceMenuFiresListenerWithNull() {
        ChoiceMenuModel menu = new ChoiceMenuModel("q", List.of(), 0);
        model.setActiveChoiceMenu(menu);

        AtomicReference<ChoiceMenuModel> captured = new AtomicReference<>(menu);
        model.addListener(new NoOpListener() {
            @Override public void onChoiceMenuChanged(ChoiceMenuModel m) {
                captured.set(m);
            }
        });

        model.clearChoiceMenu();

        assertNull(model.getActiveChoiceMenu());
        assertNull(captured.get());
    }

    // -------------------------------------------------------------------------
    // addPromptToHistory
    // -------------------------------------------------------------------------

    @Test
    void addPromptToHistoryAppendsAndCaps() {
        // Default max depth is 200 (ClaudeCodePreferences.DEFAULT_HISTORY_MAX_DEPTH).
        // Add 205 entries so the cap is triggered.
        for (int i = 0; i < 205; i++) {
            model.addPromptToHistory("prompt-" + i);
        }
        List<String> history = model.getPromptHistory();
        assertEquals(200, history.size(), "History must be capped at default max depth (200)");
        assertEquals("prompt-204", history.get(0), "Most recent prompt is first");
    }

    @Test
    void addPromptToHistoryMovesDuplicateToFront() {
        model.addPromptToHistory("first");
        model.addPromptToHistory("second");
        model.addPromptToHistory("first");  // duplicate

        List<String> history = model.getPromptHistory();
        assertEquals("first", history.get(0));
        assertEquals(2, history.size(), "Duplicate removed, not added again");
    }

    // -------------------------------------------------------------------------
    // removeListener
    // -------------------------------------------------------------------------

    @Test
    void removedListenerReceivesNoMoreNotifications() {
        AtomicInteger callCount = new AtomicInteger(0);
        ClaudeSessionModel.ClaudeSessionModelListener l = new NoOpListener() {
            @Override public void onLifecycleChanged(SessionLifecycle state) {
                callCount.incrementAndGet();
            }
        };
        model.addListener(l);
        model.setLifecycle(SessionLifecycle.WORKING);
        assertEquals(1, callCount.get());

        model.removeListener(l);
        model.setLifecycle(SessionLifecycle.READY);
        assertEquals(1, callCount.get(), "Removed listener must not be called again");
    }

    // -------------------------------------------------------------------------
    // clearEditModeRegistry
    // -------------------------------------------------------------------------

    @Test
    void clearEditModeRegistryCleansEntry() {
        File dir = new File("/tmp/test-project");
        model.setWorkingDirectory(dir);
        model.setEditMode(EditMode.ACCEPT_EDITS);
        assertNotNull(ClaudeSessionModel.EDIT_MODE_REGISTRY.get(dir.getAbsolutePath()));

        model.clearEditModeRegistry();
        assertNull(ClaudeSessionModel.EDIT_MODE_REGISTRY.get(dir.getAbsolutePath()));
    }

    @Test
    void clearEditModeRegistryWithNullDirectoryDoesNotCrash() {
        assertNull(model.getWorkingDirectory());
        assertDoesNotThrow(() -> model.clearEditModeRegistry());
    }

    // -------------------------------------------------------------------------
    // No-op listener base class
    // -------------------------------------------------------------------------

    private static class NoOpListener
            implements ClaudeSessionModel.ClaudeSessionModelListener {
        @Override public void onLifecycleChanged(SessionLifecycle state) {}
        @Override public void onEditModeChanged(io.github.nbclaudecodegui.model.EditMode mode) {}
        @Override public void onModelListChanged(List<String> models, int selectedIdx) {}
        @Override public void onChoiceMenuChanged(ChoiceMenuModel menu) {}
        @Override public void onWorkingDirectoryChanged(File dir) {}
    }
}
