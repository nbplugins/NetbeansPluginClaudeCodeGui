package io.github.nbplugins.claudecodegui.ui.markdown;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import io.github.nbplugins.claudecodegui.ui.common.BasicTextContextMenu;
import javax.swing.JEditorPane;
import javax.swing.UIManager;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.StyleSheet;

/**
 * Converts markdown-formatted text to HTML and appends it into a
 * {@link JEditorPane} backed by an {@link HTMLEditorKit}.
 *
 * <p>Supported markdown constructs:
 * <ul>
 *   <li>Fenced code blocks ({@code ```...```})</li>
 *   <li>Inline code ({@code `...`})</li>
 *   <li>Bold ({@code **...**}), italic ({@code *...*}),
 *       bold-italic ({@code ***...***})</li>
 *   <li>ATX headings ({@code #}, {@code ##}, {@code ###})</li>
 *   <li>Unordered list items ({@code - item}, {@code * item})</li>
 *   <li>Ordered list items ({@code 1. item})</li>
 *   <li>Blockquotes ({@code > text})</li>
 *   <li>Pipe tables ({@code | Col | Col |})</li>
 * </ul>
 *
 * <p>All public methods must be called on the Event Dispatch Thread.
 */
public final class MarkdownRenderer {

    // -------------------------------------------------------------------------
    // patterns for inline formatting
    // -------------------------------------------------------------------------

    private static final Pattern INLINE = Pattern.compile(
            "\\*\\*\\*(.+?)\\*\\*\\*"    // group 1: bold-italic
            + "|\\*\\*(.+?)\\*\\*"       // group 2: bold
            + "|\\*([^*\\n]+)\\*"        // group 3: italic
            + "|`([^`]+)`"               // group 4: inline code
            + "|!\\[([^\\]]*)\\]\\(([^)]+)\\)"  // group 5: image alt, group 6: image src
            + "|\\[([^\\]]+)\\]\\(([^)]+)\\)",  // group 7: link text, group 8: link url
            Pattern.DOTALL);

    // -------------------------------------------------------------------------
    // factory
    // -------------------------------------------------------------------------

    /**
     * Creates and configures a {@link JEditorPane} for rendering chat output.
     *
     * <p>The pane is non-editable, uses {@link HTMLEditorKit}, and has a
     * stylesheet suitable for markdown-rendered content.
     *
     * @param html  initial HTML content to display in the pane
     * @return a ready-to-use output pane
     */
    public static JEditorPane createOutputPane(String html) {
        JEditorPane pane = createOutputPane();
        pane.setText(html);
        return pane;
    }

    /**
     * Creates and configures a {@link JEditorPane} with HTML content and a base directory for
     * resolving relative image paths.
     *
     * @param html       initial HTML content to display
     * @param baseDirPath absolute path of the directory containing the rendered file, or null
     * @return a ready-to-use output pane
     */
    public static JEditorPane createOutputPane(String html, String baseDirPath) {
        JEditorPane pane = createOutputPane();
        pane.setText(resolveImagePaths(html, baseDirPath));
        return pane;
    }

    /**
     * Replaces relative {@code src} attributes in {@code <img>} tags with absolute
     * {@code file://} URIs so Swing's HTMLEditorKit can load them regardless of the
     * document base URL setting.
     *
     * @param html        HTML fragment (may contain {@code <img src="...">} tags)
     * @param baseDirPath absolute directory path of the file being rendered, or null
     * @return the HTML with relative image paths resolved, or the original HTML if
     *         {@code baseDirPath} is null
     */
    public static String resolveImagePaths(String html, String baseDirPath) {
        if (baseDirPath == null) return html;
        String base = baseDirPath.replace("\\", "/");
        return html.replaceAll(
                "src=\"(?!https?://|file://|data:)([^\"]+)\"",
                "src=\"file://" + base + "/$1\"");
    }

    /**
     * Creates and configures a {@link JEditorPane} for rendering chat output with no initial content.
     *
     * @return a ready-to-use output pane
     */
    public static JEditorPane createOutputPane() {
        // Detect dark theme by checking panel background brightness
        Color bg = UIManager.getColor("Panel.background");
        Color fg = UIManager.getColor("Panel.foreground");
        if (bg == null) bg = Color.WHITE;
        if (fg == null) fg = Color.BLACK;
        boolean dark = brightness(bg) < 0.5;

        // Derive theme-adaptive colors
        String codeBg    = dark ? blend(bg, Color.WHITE, 0.10) : blend(bg, Color.BLACK, 0.06);
        String codeInBg  = dark ? blend(bg, Color.WHITE, 0.07) : blend(bg, Color.BLACK, 0.04);
        String borderCol = dark ? blend(bg, Color.WHITE, 0.20) : blend(bg, Color.BLACK, 0.20);
        String thBg      = dark ? blend(bg, Color.WHITE, 0.13) : blend(bg, Color.BLACK, 0.08);
        String quoteFg   = dark ? blend(fg, bg, 0.35) : blend(fg, bg, 0.40);
        String fgStr     = toHex(fg);
        String linkCol   = dark ? "#5aabff" : "#0066cc";
        String userCol   = dark ? "#70b8ff" : "#0064b4";
        String infoCol   = dark ? blend(fg, bg, 0.45) : blend(fg, bg, 0.55);

        HTMLEditorKit kit = new HTMLEditorKit();
        StyleSheet ss = kit.getStyleSheet();
        ss.addRule("body   { font-family: sans-serif; font-size: 13pt;"
                + "         color: " + fgStr + "; margin: 6px; padding: 0; }");
        ss.addRule("p      { margin-top: 2px; margin-bottom: 4px; }");
        ss.addRule("pre    { background-color: " + codeBg + "; padding: 6px;"
                + "         font-family: monospace; font-size: 12pt;"
                + "         white-space: pre-wrap; margin: 4px 0; }");
        ss.addRule("code   { background-color: " + codeInBg + ";"
                + "         font-family: monospace; font-size: 12pt; }");
        ss.addRule("table  { border-collapse: collapse; margin: 4px 0; }");
        ss.addRule("th, td { border: 1px solid " + borderCol + "; padding: 3px 8px; }");
        ss.addRule("th     { background-color: " + thBg + "; font-weight: bold; }");
        ss.addRule("blockquote { color: " + quoteFg + "; font-style: italic;"
                + "             border-left: 3px solid " + borderCol + ";"
                + "             margin-left: 8px; padding-left: 8px; }");
        ss.addRule("h1 { font-size: 16pt; margin: 6px 0 2px; }");
        ss.addRule("h2 { font-size: 14pt; margin: 6px 0 2px; }");
        ss.addRule("h3 { font-size: 13pt; margin: 4px 0 2px; }");
        ss.addRule(".user-label { color: " + userCol + "; font-weight: bold; }");
        ss.addRule(".info  { color: " + infoCol + "; font-size: 11pt; }");
        ss.addRule("a { cursor: pointer; color: " + linkCol + "; }");

        JEditorPane pane = new JEditorPane();
        pane.setEditorKit(kit);
        pane.setDocument(kit.createDefaultDocument());
        pane.setEditable(false);
        pane.setBackground(bg);
        pane.setForeground(fg);
        pane.setCursor(java.awt.Cursor.getPredefinedCursor(
                java.awt.Cursor.TEXT_CURSOR));
        BasicTextContextMenu.attach(pane, BasicTextContextMenu.createReadOnly(pane));
        return pane;
    }

    /** Returns the perceived brightness of a color in [0,1]. */
    private static double brightness(Color c) {
        return (0.299 * c.getRed() + 0.587 * c.getGreen() + 0.114 * c.getBlue()) / 255.0;
    }

    /** Blends color {@code a} toward color {@code b} by fraction {@code t}. Returns CSS hex. */
    private static String blend(Color a, Color b, double t) {
        int r = (int) Math.round(a.getRed()   + (b.getRed()   - a.getRed())   * t);
        int g = (int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t);
        int bl= (int) Math.round(a.getBlue()  + (b.getBlue()  - a.getBlue())  * t);
        return String.format("#%02x%02x%02x", clamp(r), clamp(g), clamp(bl));
    }

    private static String toHex(Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    // -------------------------------------------------------------------------
    // public append API
    // -------------------------------------------------------------------------

    /**
     * Appends a user message with a styled {@code "You: "} prefix.
     *
     * @param pane the target pane (must be called on EDT)
     * @param text the user's message (plain text, not markdown)
     */
    public static void appendUserMessage(JEditorPane pane, String text) {
        appendHtml(pane,
                "<p><span class=\"user-label\">You:</span>&nbsp;"
                + esc(text) + "</p>");
    }

    /**
     * Appends a markdown-formatted assistant response.
     *
     * @param pane     the target pane (must be called on EDT)
     * @param markdown the markdown text returned by claude
     */
    public static void appendAssistantResponse(JEditorPane pane, String markdown) {
        appendHtml(pane, "<div>" + toHtml(markdown) + "</div>");
    }

    /**
     * Appends a plain informational or status line.
     *
     * @param pane the target pane (must be called on EDT)
     * @param line the text to append
     */
    public static void appendInfo(JEditorPane pane, String line) {
        appendHtml(pane, "<p class=\"info\">" + esc(line) + "</p>");
    }

    // -------------------------------------------------------------------------
    // markdown → HTML conversion (package-private for testing)
    // -------------------------------------------------------------------------

    /**
     * Converts a markdown string to an HTML fragment suitable for insertion
     * into an {@link HTMLDocument}.
     *
     * @param markdown the markdown source
     * @return the corresponding HTML fragment (no {@code <html>/<body>} wrapper)
     */
    public static String toHtml(String markdown) {
        StringBuilder html = new StringBuilder();
        String[] parts = markdown.split("```[^\\n]*\\n", -1);
        boolean continueList = false;
        List<Boolean> savedTypeStack = null;
        List<Integer> savedIndentStack = null;
        int savedOwnerIndent = -1;
        for (int i = 0; i < parts.length; i++) {
            if (i % 2 == 0) {
                String seg = parts[i];
                if (i > 0 && seg.startsWith("```")) {
                    seg = seg.substring(3);
                }
                if (continueList) {
                    String[] contLines = seg.split("\n", -1);
                    // Skip only blank lines to find the first non-blank content.
                    int firstIdx = 0;
                    while (firstIdx < contLines.length && contLines[firstIdx].isBlank()) {
                        firstIdx++;
                    }
                    int subListEnd = firstIdx;
                    if (firstIdx < contLines.length
                            && leadingSpaces(contLines[firstIdx]) > savedOwnerIndent) {
                        // Sub-items belong inside the currently open <li> — render them there.
                        subListEnd = renderNestedList(contLines, firstIdx, html);
                    }
                    // Close the owner item now that its content (code block + any sub-list) is done.
                    html.append("</li>");
                    // Render remaining lines using the saved outer context so that items at
                    // different indent levels (siblings vs. outer) are placed correctly.
                    int listEnd = renderNestedList(contLines, subListEnd, html,
                                     new ArrayList<>(savedIndentStack), new ArrayList<>(savedTypeStack));
                    continueList = false;
                    savedTypeStack = null;
                    savedIndentStack = null;
                    savedOwnerIndent = -1;
                    // Process any content after the list (headings, paragraphs, next list prefix).
                    if (listEnd < contLines.length) {
                        String tail = String.join("\n",
                                Arrays.copyOfRange(contLines, listEnd, contLines.length));
                        html.append(convertSegment(tail));
                    }
                } else {
                    html.append(convertSegment(seg));
                }
            } else {
                String code = parts[i];
                if (code.endsWith("```")) {
                    code = code.substring(0, code.length() - 3);
                }
                code = code.stripTrailing();
                code = dedent(code);
                String preHtml = "<pre>" + esc(code) + "</pre>";
                boolean indented = i > 0 && parts[i - 1].matches("(?s).*[ \t]+$");
                if (indented) {
                    // Reconstruct the active list context by scanning all text segments up to here.
                    savedIndentStack = new ArrayList<>();
                    savedTypeStack = new ArrayList<>();
                    for (int k = 0; k < i; k += 2) {
                        for (String pl : parts[k].split("\n", -1)) {
                            if (!isOrdered(pl) && !isUnordered(pl)) continue;
                            int indent = leadingSpaces(pl);
                            boolean isOl = isOrdered(pl);
                            while (!savedIndentStack.isEmpty()
                                   && savedIndentStack.get(savedIndentStack.size() - 1) >= indent) {
                                savedIndentStack.remove(savedIndentStack.size() - 1);
                                savedTypeStack.remove(savedTypeStack.size() - 1);
                            }
                            savedIndentStack.add(indent);
                            savedTypeStack.add(isOl);
                        }
                    }
                    savedOwnerIndent = savedIndentStack.isEmpty() ? -1
                                       : savedIndentStack.get(savedIndentStack.size() - 1);
                    // Remove ALL trailing list-close tags and the owner item's </li> from html.
                    String buf = html.toString();
                    while (buf.endsWith("</ol>") || buf.endsWith("</ul>")) {
                        html.delete(html.length() - 5, html.length());
                        buf = html.toString();
                    }
                    if (buf.endsWith("</li>")) {
                        html.delete(html.length() - 5, html.length());
                    }
                    // Append code block but leave the owner <li> open for sub-list content.
                    html.append(preHtml);
                    continueList = true;
                } else {
                    html.append(preHtml);
                }
            }
        }
        if (continueList) {
            // Code block was the last content — close owner item then close all open lists.
            html.append("</li>");
            for (int k = savedTypeStack.size() - 1; k >= 0; k--) {
                html.append(savedTypeStack.get(k) ? "</ol>" : "</ul>");
            }
        }
        return html.toString();
    }

    // -------------------------------------------------------------------------
    // block-level conversion
    // -------------------------------------------------------------------------

    private static String convertSegment(String text) {
        StringBuilder html = new StringBuilder();
        String[] lines = text.split("\n", -1);
        int i = 0;

        while (i < lines.length) {
            String line = lines[i];

            if (line.isBlank()) {
                i++;
                continue;
            }

            // --- Headings ---
            if (line.startsWith("# ")) {
                String heading = line.substring(2).trim();
                html.append("<h1><a name=\"").append(slugify(heading)).append("\">")
                        .append(inlineToHtml(heading)).append("</a></h1>");
                i++;
                continue;
            }
            if (line.startsWith("## ")) {
                String heading = line.substring(3).trim();
                html.append("<h2><a name=\"").append(slugify(heading)).append("\">")
                        .append(inlineToHtml(heading)).append("</a></h2>");
                i++;
                continue;
            }
            if (line.startsWith("### ")) {
                String heading = line.substring(4).trim();
                html.append("<h3><a name=\"").append(slugify(heading)).append("\">")
                        .append(inlineToHtml(heading)).append("</a></h3>");
                i++;
                continue;
            }

            // --- Table ---
            if (isTableRow(line) && i + 1 < lines.length
                    && isSeparatorRow(lines[i + 1])) {
                i = renderTable(lines, i, html);
                continue;
            }

            // --- List (nested ordered/unordered) ---
            if (isUnordered(line) || isOrdered(line)) {
                i = renderNestedList(lines, i, html);
                continue;
            }

            // --- Blockquote ---
            if (line.startsWith("> ")) {
                html.append("<blockquote>");
                while (i < lines.length && lines[i].startsWith("> ")) {
                    html.append(inlineToHtml(lines[i].substring(2)));
                    i++;
                    if (i < lines.length && lines[i].startsWith("> ")) {
                        html.append("<br>");
                    }
                }
                html.append("</blockquote>");
                continue;
            }

            // --- Paragraph ---
            html.append("<p>");
            while (i < lines.length && !lines[i].isBlank()
                    && !isBlockStart(lines[i])
                    && !(isTableRow(lines[i]) && i + 1 < lines.length
                            && isSeparatorRow(lines[i + 1]))) {
                html.append(inlineToHtml(lines[i]));
                i++;
                if (i < lines.length && !lines[i].isBlank()
                        && !isBlockStart(lines[i])) {
                    html.append(" ");
                }
            }
            html.append("</p>");
        }

        return html.toString();
    }

    // -------------------------------------------------------------------------
    // table rendering
    // -------------------------------------------------------------------------

    /**
     * Renders a table starting at {@code lines[start]}.
     *
     * <p>{@code lines[start]} is the header row, {@code lines[start+1]} is the
     * separator row (e.g., {@code |---|---|}).  Subsequent {@code |…|} rows are
     * data rows.
     *
     * @param lines the full line array
     * @param start index of the header row
     * @param out   output buffer
     * @return index of the first line after the table
     */
    private static int renderTable(String[] lines, int start, StringBuilder out) {
        out.append("<table>");

        // Header row
        out.append("<tr>");
        for (String cell : parseCells(lines[start])) {
            out.append("<th>").append(inlineToHtml(cell.trim())).append("</th>");
        }
        out.append("</tr>");

        // Skip separator row
        int i = start + 2;

        // Data rows
        while (i < lines.length && isTableRow(lines[i])) {
            out.append("<tr>");
            for (String cell : parseCells(lines[i])) {
                out.append("<td>").append(inlineToHtml(cell.trim())).append("</td>");
            }
            out.append("</tr>");
            i++;
        }

        out.append("</table>");
        return i;
    }

    // -------------------------------------------------------------------------
    // inline formatting
    // -------------------------------------------------------------------------

    /**
     * Converts inline markdown formatting within a single line to HTML.
     *
     * <p>Handles bold ({@code **}), italic ({@code *}), bold-italic
     * ({@code ***}), and inline code ({@code `}).  All other text is
     * HTML-escaped.
     *
     * @param text the inline markdown text
     * @return the corresponding HTML fragment
     */
    static String inlineToHtml(String text) {
        StringBuilder sb = new StringBuilder();
        int lastEnd = 0;
        Matcher m = INLINE.matcher(text);
        while (m.find()) {
            if (m.start() > lastEnd) {
                sb.append(esc(text.substring(lastEnd, m.start())));
            }
            if (m.group(1) != null) {
                sb.append("<b><em>").append(esc(m.group(1))).append("</em></b>");
            } else if (m.group(2) != null) {
                sb.append("<b>").append(esc(m.group(2))).append("</b>");
            } else if (m.group(3) != null) {
                sb.append("<em>").append(esc(m.group(3))).append("</em>");
            } else if (m.group(4) != null) {
                sb.append("<code>").append(esc(m.group(4))).append("</code>");
            } else if (m.group(5) != null) {
                sb.append("<img src=\"").append(esc(m.group(6)))
                        .append("\" alt=\"").append(esc(m.group(5))).append("\">");
            } else if (m.group(7) != null) {
                sb.append("<a href=\"").append(esc(m.group(8))).append("\">")
                        .append(esc(m.group(7))).append("</a>");
            }
            lastEnd = m.end();
        }
        if (lastEnd < text.length()) {
            sb.append(esc(text.substring(lastEnd)));
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    private static boolean isTableRow(String line) {
        String t = line.trim();
        return t.startsWith("|") && t.endsWith("|") && t.length() > 2;
    }

    private static boolean isSeparatorRow(String line) {
        if (!isTableRow(line)) return false;
        for (String cell : parseCells(line)) {
            if (!cell.trim().matches("[:\\- ]+")) return false;
        }
        return true;
    }

    private static List<String> parseCells(String row) {
        String t = row.trim();
        if (t.startsWith("|")) t = t.substring(1);
        if (t.endsWith("|")) t = t.substring(0, t.length() - 1);
        return Arrays.asList(t.split("\\|", -1));
    }

    private static boolean isUnordered(String line) {
        return line.matches("^\\s*[-*]\\s+.*");
    }

    private static boolean isOrdered(String line) {
        return line.matches("^\\s*\\d+\\.\\s+.*");
    }

    private static boolean isBlockStart(String line) {
        return line.startsWith("# ")
                || line.startsWith("## ")
                || line.startsWith("### ")
                || line.startsWith("> ")
                || isUnordered(line)
                || isOrdered(line)
                || isTableRow(line);
    }

    /**
     * Converts heading text to a URL-friendly slug for use as a named anchor.
     * Lowercases the text, strips non-alphanumeric characters (except spaces and hyphens),
     * trims, replaces spaces with hyphens, and collapses consecutive hyphens.
     */
    static String slugify(String text) {
        return text.toLowerCase()
                   .replaceAll("[^a-z0-9\\s-]", "")
                   .trim()
                   .replaceAll("\\s+", "-")
                   .replaceAll("-+", "-");
    }

    /**
     * Escapes HTML special characters in plain text.
     *
     * @param text the raw text
     * @return the HTML-escaped text
     */
    static String esc(String text) {
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;");
    }

    // -------------------------------------------------------------------------
    // HTML insertion
    // -------------------------------------------------------------------------

    private static void appendHtml(JEditorPane pane, String html) {
        try {
            HTMLDocument doc = (HTMLDocument) pane.getDocument();
            HTMLEditorKit kit = (HTMLEditorKit) pane.getEditorKit();
            kit.insertHTML(doc, doc.getLength(), html, 0, 0, null);
            pane.setCaretPosition(doc.getLength());
        } catch (Exception ex) {
            // Should not happen under normal usage
        }
    }

    private static int renderNestedList(String[] lines, int start, StringBuilder html) {
        return renderNestedList(lines, start, html, new ArrayList<>(), new ArrayList<>());
    }

    private static int renderNestedList(String[] lines, int start, StringBuilder html,
                                         List<Integer> indentStack, List<Boolean> typeStack) {
        int startIndent = indentStack.isEmpty() ? -1 : indentStack.get(0);

        int i = start;
        while (i < lines.length) {
            String line = lines[i];

            if (line.isBlank()) {
                int j = i + 1;
                while (j < lines.length && lines[j].isBlank()) j++;
                if (j < lines.length && (isOrdered(lines[j]) || isUnordered(lines[j]))) {
                    i = j;
                    continue;
                }
                break;
            }

            if (!isOrdered(line) && !isUnordered(line)) break;

            int indent = leadingSpaces(line);
            boolean ordered = isOrdered(line);

            if (indentStack.isEmpty()) {
                startIndent = indent;
                html.append(ordered ? "<ol start=\"" + orderedListNumber(line) + "\">" : "<ul>");
                indentStack.add(indent);
                typeStack.add(ordered);
            } else {
                int topIndent = indentStack.get(indentStack.size() - 1);
                if (indent > topIndent) {
                    html.append(ordered ? "<ol start=\"" + orderedListNumber(line) + "\">" : "<ul>");
                    indentStack.add(indent);
                    typeStack.add(ordered);
                } else if (indent < topIndent) {
                    while (!indentStack.isEmpty() && indentStack.get(indentStack.size() - 1) > indent) {
                        html.append(typeStack.get(typeStack.size() - 1) ? "</ol>" : "</ul>");
                        indentStack.remove(indentStack.size() - 1);
                        typeStack.remove(typeStack.size() - 1);
                    }
                    // Item is less indented than our starting level — belongs to outer context.
                    if (indentStack.isEmpty() || indent < startIndent) {
                        break;
                    }
                }
                if (!typeStack.isEmpty() && typeStack.get(typeStack.size() - 1) != ordered) {
                    html.append(typeStack.get(typeStack.size() - 1) ? "</ol>" : "</ul>");
                    typeStack.set(typeStack.size() - 1, ordered);
                    html.append(ordered ? "<ol start=\"" + orderedListNumber(line) + "\">" : "<ul>");
                }
            }

            String content = line.replaceFirst("^\\s*(?:\\d+\\.|[-*])\\s+", "");
            html.append("<li>").append(inlineToHtml(content));
            i++;
            // Lookahead: include non-list lines more indented than topIndent as continuation content.
            int curTopIndent = indentStack.get(indentStack.size() - 1);
            int j = i;
            while (j < lines.length && lines[j].isBlank()) j++;
            while (j < lines.length && !lines[j].isBlank()
                    && !isOrdered(lines[j]) && !isUnordered(lines[j])
                    && leadingSpaces(lines[j]) > curTopIndent) {
                html.append(inlineToHtml(lines[j].trim()));
                j++;
                while (j < lines.length && lines[j].isBlank()) j++;
            }
            i = j;
            html.append("</li>");
        }

        for (int k = typeStack.size() - 1; k >= 0; k--) {
            html.append(typeStack.get(k) ? "</ol>" : "</ul>");
        }
        return i;
    }

    private static int leadingSpaces(String line) {
        int n = 0;
        while (n < line.length() && line.charAt(n) == ' ') n++;
        return n;
    }

    private static int orderedListNumber(String line) {
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("^\\s*(\\d+)\\.\\s+").matcher(line);
        return m.find() ? Integer.parseInt(m.group(1)) : 1;
    }

    private static String dedent(String code) {
        String[] lines = code.split("\n", -1);
        int min = Integer.MAX_VALUE;
        for (String l : lines) {
            if (!l.isBlank()) {
                int sp = 0;
                while (sp < l.length() && l.charAt(sp) == ' ') sp++;
                min = Math.min(min, sp);
            }
        }
        if (min == 0 || min == Integer.MAX_VALUE) return code;
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < lines.length; k++) {
            String l = lines[k];
            sb.append(l.length() >= min ? l.substring(min) : l);
            if (k < lines.length - 1) sb.append('\n');
        }
        return sb.toString();
    }

    private MarkdownRenderer() {}
}
