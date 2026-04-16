package io.github.nbclaudecodegui.ui.common;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link MarkdownRenderer}'s markdown-to-HTML conversion.
 */
class MarkdownRendererTest {

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

    // -------------------------------------------------------------------------
    // toHtml() — block elements
    // -------------------------------------------------------------------------

    @Test
    void testHeadings() {
        String html = MarkdownRenderer.toHtml("# H1\n## H2\n### H3");
        assertTrue(html.contains("<h1>") && html.contains("H1") && html.contains("</h1>"), "h1");
        assertTrue(html.contains("<h2>") && html.contains("H2") && html.contains("</h2>"), "h2");
        assertTrue(html.contains("<h3>") && html.contains("H3") && html.contains("</h3>"), "h3");
    }

    @Test
    void testUnorderedList() {
        String html = MarkdownRenderer.toHtml("- alpha\n- beta\n- gamma");
        assertTrue(html.contains("<ul>"),  "ul open");
        assertTrue(html.contains("</ul>"), "ul close");
        assertTrue(html.contains("<li>alpha</li>"), "item alpha");
        assertTrue(html.contains("<li>beta</li>"),  "item beta");
        assertTrue(html.contains("<li>gamma</li>"), "item gamma");
    }

    @Test
    void testOrderedList() {
        String html = MarkdownRenderer.toHtml("1. first\n2. second");
        assertTrue(html.contains("<ol>"),  "ol open");
        assertTrue(html.contains("<li>first</li>"),  "item first");
        assertTrue(html.contains("<li>second</li>"), "item second");
    }

    @Test
    void testBlockquote() {
        String html = MarkdownRenderer.toHtml("> some quote\n> continued");
        assertTrue(html.contains("<blockquote>"), "blockquote open");
        assertTrue(html.contains("some quote"),   "content line 1");
        assertTrue(html.contains("continued"),    "content line 2");
    }

    @Test
    void testFencedCodeBlock() {
        String html = MarkdownRenderer.toHtml("```java\nSystem.out.println();\n```");
        assertTrue(html.contains("<pre>"), "pre open");
        assertTrue(html.contains("System.out.println();"), "code content");
    }

    @Test
    void testCodeBlockHtmlEscaped() {
        String html = MarkdownRenderer.toHtml("```\na < b && c > d\n```");
        assertTrue(html.contains("&lt;"), "< escaped");
        assertTrue(html.contains("&amp;"), "& escaped");
    }

    @Test
    void testParagraph() {
        String html = MarkdownRenderer.toHtml("Hello world");
        assertTrue(html.contains("<p>"), "paragraph");
        assertTrue(html.contains("Hello world"), "content");
    }

    // -------------------------------------------------------------------------
    // toHtml() — tables
    // -------------------------------------------------------------------------

    @Test
    void testBasicTable() {
        String md = "| Name | Age |\n|------|-----|\n| Alice | 30 |\n| Bob | 25 |";
        String html = MarkdownRenderer.toHtml(md);

        assertTrue(html.contains("<table>"),  "table open");
        assertTrue(html.contains("</table>"), "table close");
        assertTrue(html.contains("<th>"),     "th present");
        assertTrue(html.contains("<td>"),     "td present");
        assertTrue(html.contains("Name"),     "header Name");
        assertTrue(html.contains("Age"),      "header Age");
        assertTrue(html.contains("Alice"),    "cell Alice");
        assertTrue(html.contains("Bob"),      "cell Bob");
        assertTrue(html.contains("30"),       "cell 30");
        assertTrue(html.contains("25"),       "cell 25");
    }

    @Test
    void testTableHeaderRow() {
        String md = "| Col1 | Col2 | Col3 |\n|------|------|------|\n| a | b | c |";
        String html = MarkdownRenderer.toHtml(md);

        // Header cells must be <th>, data cells must be <td>
        assertTrue(html.indexOf("<th>") < html.indexOf("<td>"),
                "th should come before td");
        assertTrue(html.contains("<th>Col1</th>"), "th Col1");
        assertTrue(html.contains("<td>a</td>"),    "td a");
    }

    @Test
    void testTableWithInlineMarkdown() {
        String md = "| **Bold** | `code` |\n|----------|--------|\n| value | val2 |";
        String html = MarkdownRenderer.toHtml(md);
        assertTrue(html.contains("<b>Bold</b>"),    "bold in header");
        assertTrue(html.contains("<code>code</code>"), "code in header");
    }

    @Test
    void testTableHtmlEscapedCells() {
        String md = "| A<B | C&D |\n|-----|-----|\n| x | y |";
        String html = MarkdownRenderer.toHtml(md);
        assertTrue(html.contains("A&lt;B"), "< escaped in cell");
        assertTrue(html.contains("C&amp;D"), "& escaped in cell");
    }

    // -------------------------------------------------------------------------
    // inlineToHtml() — links
    // -------------------------------------------------------------------------

    @Test
    void testInlineLink() {
        String result = MarkdownRenderer.inlineToHtml("[Installation & Build](docs/installation.md)");
        assertTrue(result.contains("<a href=\"docs/installation.md\">"), "anchor href");
        assertTrue(result.contains("Installation &amp; Build"), "link text escaped");
        assertTrue(result.contains("</a>"), "anchor close");
        assertFalse(result.contains("[Installation"), "raw markdown should not appear");
    }

    @Test
    void testInlineLinkInParagraph() {
        String html = MarkdownRenderer.toHtml(
                "See [Installation & Build](docs/installation.md) for requirements.");
        assertTrue(html.contains("<a href=\"docs/installation.md\">"), "link in paragraph");
        assertTrue(html.contains("Installation &amp; Build"), "link text");
        assertFalse(html.contains("[Installation"), "raw markdown should not appear");
    }

    @Test
    void testInlineLinkMixedWithBold() {
        String result = MarkdownRenderer.inlineToHtml("See [guide](readme.md) and **bold**");
        assertTrue(result.contains("<a href=\"readme.md\">guide</a>"), "link");
        assertTrue(result.contains("<b>bold</b>"), "bold");
    }

    @Test
    void testTableFollowedByParagraph() {
        String md = "| X |\n|---|\n| v |\n\nSome text after.";
        String html = MarkdownRenderer.toHtml(md);
        assertTrue(html.contains("<table>"),         "table present");
        assertTrue(html.contains("Some text after"), "paragraph after table");
    }

    // -------------------------------------------------------------------------
    // Bug 2: Images
    // -------------------------------------------------------------------------

    @Test
    void testInlineImage() {
        String result = MarkdownRenderer.inlineToHtml("![alt text](image.png)");
        assertTrue(result.contains("<img "), "img tag");
        assertTrue(result.contains("src=\"image.png\""), "img src");
        assertTrue(result.contains("alt=\"alt text\""), "img alt");
        assertFalse(result.contains("!["), "raw markdown should not appear");
    }

    @Test
    void testInlineImageInParagraph() {
        String html = MarkdownRenderer.toHtml("Here is an image: ![logo](logo.png)");
        assertTrue(html.contains("<img "), "img tag in paragraph");
        assertTrue(html.contains("src=\"logo.png\""), "img src");
    }

    @Test
    void testInlineImageEmptyAlt() {
        String result = MarkdownRenderer.inlineToHtml("![](photo.jpg)");
        assertTrue(result.contains("<img "), "img tag");
        assertTrue(result.contains("src=\"photo.jpg\""), "img src");
        assertTrue(result.contains("alt=\"\""), "empty alt");
    }

    // -------------------------------------------------------------------------
    // Bug 3: Named anchors in headings for anchor navigation
    // -------------------------------------------------------------------------

    @Test
    void testHeadingH1HasNamedAnchor() {
        String html = MarkdownRenderer.toHtml("# My Heading");
        assertTrue(html.contains("<a name=\"my-heading\">"), "h1 named anchor");
        assertTrue(html.contains("<h1>"), "h1 tag");
    }

    @Test
    void testHeadingH2HasNamedAnchor() {
        String html = MarkdownRenderer.toHtml("## Section Two");
        assertTrue(html.contains("<a name=\"section-two\">"), "h2 named anchor");
    }

    @Test
    void testHeadingH3HasNamedAnchor() {
        String html = MarkdownRenderer.toHtml("### Sub Section");
        assertTrue(html.contains("<a name=\"sub-section\">"), "h3 named anchor");
    }

    @Test
    void testHeadingSlugStripNonAlphanumeric() {
        String html = MarkdownRenderer.toHtml("# Hello, World! (2024)");
        assertTrue(html.contains("<a name=\"hello-world-2024\">"), "slug strips punctuation");
    }
}
