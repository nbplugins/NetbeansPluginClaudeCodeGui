package io.github.nbclaudecodegui.mcp.tools;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.nbclaudecodegui.ui.MarkdownPreviewTab;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.SwingUtilities;
import org.openbeans.claude.netbeans.tools.Tool;
import org.openide.filesystems.FileUtil;

/**
 * MCP tool that opens a live-updating Markdown Preview tab for a file on disk.
 * The tab watches the file for changes and refreshes automatically.
 */
public class ShowMarkdownFile implements Tool<ShowMarkdownFile.Params, String> {

    private static final Logger LOGGER = Logger.getLogger(ShowMarkdownFile.class.getName());

    public static class Params {
        @JsonProperty("filePath")
        private String filePath;

        @JsonProperty("title")
        private String title;

        public String getFilePath() { return filePath; }
        public String getTitle()    { return title; }
    }

    @Override
    public String getName() { return "show_markdown_file"; }

    @Override
    public String getDescription() {
        return "Open a live-updating Markdown Preview tab for a .md file. "
             + "The tab refreshes automatically when the file changes on disk.";
    }

    @Override
    public Class<Params> getParameterClass() { return Params.class; }

    @Override
    public String run(Params params) throws Exception {
        if (params.getFilePath() == null || params.getFilePath().isBlank()) {
            return "error: filePath is required";
        }
        File file = new File(params.getFilePath());
        String absPath = file.getAbsolutePath();
        LOGGER.fine("show_markdown_file: " + absPath);

        String content;
        try {
            content = file.exists()
                    ? Files.readString(file.toPath(), StandardCharsets.UTF_8)
                    : "";
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Cannot read file: " + absPath, e);
            content = "";
        }

        var fo = FileUtil.toFileObject(FileUtil.normalizeFile(file));
        String markdown = content;
        SwingUtilities.invokeLater(() -> MarkdownPreviewTab.openLive(absPath, markdown, fo));
        return "ok";
    }
}
