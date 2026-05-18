package io.github.nbclaudecodegui.ui.common;

/** Implemented by UI surfaces that support font-size zoom. */
public interface Zoomable {
    void zoomIn();
    void zoomOut();
    void resetZoom();
    int getZoomDelta();
    int getMinDelta();
    int getMaxDelta();
}
