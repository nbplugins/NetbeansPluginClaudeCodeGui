package io.github.nbplugins.claudecodegui.mcp.tools;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.nbplugins.claudecodegui.ui.MarkdownPreviewTab;
import java.util.logging.Logger;
import javax.swing.SwingUtilities;
import org.openbeans.claude.netbeans.tools.Tool;

/**
 * MCP tool that renders markdown text in a NetBeans Markdown Preview tab.
 * The tab is identified by title and updated in-place on repeated calls.
 */
public class ShowMarkdown implements Tool<ShowMarkdown.Params, String> {

    private static final Logger LOGGER = Logger.getLogger(ShowMarkdown.class.getName());

    public static class Params {
        @JsonProperty("markdown")
        private String markdown;

        @JsonProperty("title")
        private String title;

        public String getMarkdown() { return markdown; }
        public String getTitle()    { return title; }
    }

    @Override
    public String getName() { return "show_markdown"; }

    @Override
    public String getDescription() {
        return "Display markdown content in the IDE Markdown Preview tab. "
             + "Call this to show plans, summaries, or structured output with rich formatting.";
    }

    @Override
    public Class<Params> getParameterClass() { return Params.class; }

    @Override
    public String run(Params params) throws Exception {
        String title = params.getTitle() != null && !params.getTitle().isBlank()
                ? params.getTitle() : "Plan";
        String key = "mcp://show_markdown/" + title;
        String markdown = params.getMarkdown() != null ? params.getMarkdown() : "";
        LOGGER.fine("show_markdown: title=" + title);
        SwingUtilities.invokeLater(() -> MarkdownPreviewTab.openLive(key, markdown, null));
        return "ok";
    }
}
