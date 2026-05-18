package io.github.nbplugins.claudecodegui.ui.markdown;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.File;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link MarkdownRenderer}.
 *
 * <p>toHtml() tests are fixture-based: each case is a pair of resource files under
 * {@code io/github/nbplugins/claudecodegui/ui/markdown/markdown-renderer/to-html/}:
 * <ul>
 *   <li>{@code <case>.src.md} — input markdown</li>
 *   <li>{@code <case>.expectedHtml.html} — expected HTML output</li>
 * </ul>
 */
class MarkdownRendererTest {

    private static final String TO_HTML_RESOURCE_DIR =
            "io/github/nbplugins/claudecodegui/ui/markdown/markdown-renderer/to-html";

    static Stream<Object[]> toHtmlTestCases() throws Exception {
        URL dirUrl = MarkdownRendererTest.class.getClassLoader().getResource(TO_HTML_RESOURCE_DIR);
        assertNotNull(dirUrl, "Resource directory not found: " + TO_HTML_RESOURCE_DIR);
        File dir = new File(dirUrl.toURI());
        List<Object[]> cases = new ArrayList<>();
        File[] srcFiles = dir.listFiles((d, name) -> name.endsWith(".src.md"));
        assertNotNull(srcFiles);
        for (File src : srcFiles) {
            String caseName = src.getName().replace(".src.md", "");
            Path expectedPath = src.toPath().resolveSibling(caseName + ".expectedHtml.html");
            cases.add(new Object[]{caseName, src.toPath(), expectedPath});
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("toHtmlTestCases")
    void toHtml(String caseName, Path srcFile, Path expectedFile) throws Exception {
        String src = Files.readString(srcFile);
        String expected = Files.readString(expectedFile).strip();
        assertEquals(expected, MarkdownRenderer.toHtml(src).strip(),
                "HTML mismatch for case '" + caseName + "'");
    }

    // -------------------------------------------------------------------------
    // esc()
    // -------------------------------------------------------------------------

    @Test
    void testEscapeAmpersand() {
        assertEquals("a &amp; b", MarkdownRenderer.esc("a & b"));
    }

    @Test
    void testEscapeLtGt() {
        assertEquals("&lt;div&gt;", MarkdownRenderer.esc("<div>"));
    }

    @Test
    void testEscapeQuote() {
        assertEquals("say &quot;hi&quot;", MarkdownRenderer.esc("say \"hi\""));
    }

    // -------------------------------------------------------------------------
    // inlineToHtml()
    // -------------------------------------------------------------------------

    @Test
    void testBold() {
        assertEquals("<b>hello</b>", MarkdownRenderer.inlineToHtml("**hello**"));
    }

    @Test
    void testItalic() {
        assertEquals("<em>hello</em>", MarkdownRenderer.inlineToHtml("*hello*"));
    }

    @Test
    void testBoldItalic() {
        assertEquals("<b><em>hi</em></b>", MarkdownRenderer.inlineToHtml("***hi***"));
    }

    @Test
    void testInlineCode() {
        assertEquals("<code>foo()</code>", MarkdownRenderer.inlineToHtml("`foo()`"));
    }

    @Test
    void testMixedInline() {
        String result = MarkdownRenderer.inlineToHtml("Use **bold** and `code`");
        assertTrue(result.contains("<b>bold</b>"), "should contain bold");
        assertTrue(result.contains("<code>code</code>"), "should contain code");
        assertTrue(result.contains("Use "), "should contain prefix text");
    }

    @Test
    void testPlainTextEscaped() {
        assertEquals("a &amp; b &lt;c&gt;", MarkdownRenderer.inlineToHtml("a & b <c>"));
    }

    @Test
    void testInlineLink() {
        String result = MarkdownRenderer.inlineToHtml("[Installation & Build](docs/installation.md)");
        assertTrue(result.contains("<a href=\"docs/installation.md\">"), "anchor href");
        assertTrue(result.contains("Installation &amp; Build"), "link text escaped");
        assertTrue(result.contains("</a>"), "anchor close");
        assertFalse(result.contains("[Installation"), "raw markdown should not appear");
    }

    @Test
    void testInlineLinkMixedWithBold() {
        String result = MarkdownRenderer.inlineToHtml("See [guide](readme.md) and **bold**");
        assertTrue(result.contains("<a href=\"readme.md\">guide</a>"), "link");
        assertTrue(result.contains("<b>bold</b>"), "bold");
    }

    @Test
    void testInlineImage() {
        String result = MarkdownRenderer.inlineToHtml("![alt text](image.png)");
        assertTrue(result.contains("<img "), "img tag");
        assertTrue(result.contains("src=\"image.png\""), "img src");
        assertTrue(result.contains("alt=\"alt text\""), "img alt");
        assertFalse(result.contains("!["), "raw markdown should not appear");
    }

    @Test
    void testInlineImageEmptyAlt() {
        String result = MarkdownRenderer.inlineToHtml("![](photo.jpg)");
        assertTrue(result.contains("<img "), "img tag");
        assertTrue(result.contains("src=\"photo.jpg\""), "img src");
        assertTrue(result.contains("alt=\"\""), "empty alt");
    }

    // -------------------------------------------------------------------------
    // resolveImagePaths()
    // -------------------------------------------------------------------------

    @Test
    void testResolveImagePathsRelative() {
        String html = "<img src=\"screenshots/overview.png\" alt=\"\">";
        String result = MarkdownRenderer.resolveImagePaths(html, "/docs");
        assertEquals("<img src=\"file:///docs/screenshots/overview.png\" alt=\"\">", result);
    }

    @Test
    void testResolveImagePathsAbsoluteHttpUnchanged() {
        String html = "<img src=\"https://example.com/img.png\" alt=\"\">";
        String result = MarkdownRenderer.resolveImagePaths(html, "/docs");
        assertEquals(html, result);
    }

    @Test
    void testResolveImagePathsFileUriUnchanged() {
        String html = "<img src=\"file:///abs/path/img.png\" alt=\"\">";
        String result = MarkdownRenderer.resolveImagePaths(html, "/docs");
        assertEquals(html, result);
    }

    @Test
    void testResolveImagePathsDataUriUnchanged() {
        String html = "<img src=\"data:image/png;base64,abc\" alt=\"\">";
        String result = MarkdownRenderer.resolveImagePaths(html, "/docs");
        assertEquals(html, result);
    }

    @Test
    void testResolveImagePathsNullBaseDirReturnsUnchanged() {
        String html = "<img src=\"img.png\" alt=\"\">";
        String result = MarkdownRenderer.resolveImagePaths(html, null);
        assertEquals(html, result);
    }

    @Test
    void testResolveImagePathsWindowsBackslashesNormalized() {
        String html = "<img src=\"img.png\" alt=\"\">";
        String result = MarkdownRenderer.resolveImagePaths(html, "C:\\docs\\project");
        assertEquals("<img src=\"file://C:/docs/project/img.png\" alt=\"\">", result);
    }
}
