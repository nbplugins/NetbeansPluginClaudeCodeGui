package io.github.nbplugins.claudecodegui.ui.common;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.swing.text.Document;
import javax.swing.text.Element;

/**
 * A range of lines selected in an IDE editor, attached to prompts as an
 * {@code @path#L12-20} mention.
 *
 * <p>Claude Code parses this mention syntax natively and reads just those lines;
 * it is the same token the VS Code extension inserts for a selection.
 *
 * @param filePath  absolute path of the file containing the selection
 * @param startLine 1-based first selected line
 * @param endLine   1-based last selected line (inclusive)
 */
public record EditorSelection(String filePath, int startLine, int endLine) {

    /**
     * Builds a selection from document offsets.
     *
     * <p>A selection that ends at column 0 of a line does not include that line
     * (the usual result of selecting whole lines), matching Claude Code's own
     * line counting for IDE selections.
     *
     * @param filePath absolute file path; {@code null} if the document is not backed by a file
     * @param doc      the editor document
     * @param selStart selection start offset
     * @param selEnd   selection end offset
     * @return the selection, or {@code null} if nothing is selected or there is no file
     */
    public static EditorSelection of(String filePath, Document doc, int selStart, int selEnd) {
        if (filePath == null || doc == null || selStart >= selEnd) return null;
        Element root = doc.getDefaultRootElement();
        int startIdx = root.getElementIndex(selStart);
        int endIdx   = root.getElementIndex(selEnd);
        if (endIdx > startIdx && root.getElement(endIdx).getStartOffset() == selEnd) {
            endIdx--;
        }
        return new EditorSelection(filePath, startIdx + 1, endIdx + 1);
    }

    /**
     * Returns the {@code @path#L<start>-<end>} mention for this selection
     * ({@code #L<line>} for a single line). The path is relative to
     * {@code workingDir} when the file is inside it, absolute otherwise, and
     * always uses forward slashes.
     *
     * @param workingDir the session working directory; may be {@code null}
     * @return the mention token, without a trailing space
     */
    public String toMention(String workingDir) {
        String path = filePath;
        if (workingDir != null) {
            try {
                Path wd   = Paths.get(workingDir).toAbsolutePath().normalize();
                Path file = Paths.get(filePath).toAbsolutePath().normalize();
                if (file.startsWith(wd) && !file.equals(wd)) {
                    path = wd.relativize(file).toString();
                }
            } catch (InvalidPathException e) {
                // keep the absolute path
            }
        }
        path = path.replace('\\', '/');
        String range = startLine == endLine
                ? "#L" + startLine
                : "#L" + startLine + "-" + endLine;
        return "@" + path + range;
    }
}
