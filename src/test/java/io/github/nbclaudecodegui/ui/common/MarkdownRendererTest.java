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
        assertTrue(html.contains("<ol start=\"1\">"),  "ol open");
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

    // -------------------------------------------------------------------------
    // toHtml() — nested lists
    // -------------------------------------------------------------------------

    @Test
    void testNestedOrderedList() {
        String md = "1. first\n    1. sub-first\n    2. sub-second\n2. second";
        String html = MarkdownRenderer.toHtml(md);
        // outer ol must contain an inner ol
        int outerOl = html.indexOf("<ol start=");
        int innerOl = html.indexOf("<ol start=", outerOl + 1);
        assertTrue(innerOl > outerOl, "inner <ol> must exist inside outer <ol>");
        assertTrue(html.contains("<li>first</li>") || html.contains("<li>first"),
                "outer first item");
        assertTrue(html.contains("sub-first"), "sub-first item");
        assertTrue(html.contains("sub-second"), "sub-second item");
        assertTrue(html.contains("second"), "outer second item");
        // must have at least 2 </ol> closing tags
        long closeCount = html.chars().filter(c -> c == '<').count();
        assertTrue(html.indexOf("</ol>", html.indexOf("</ol>") + 1) > 0,
                "at least 2 </ol> tags expected");
    }

    @Test
    void testNestedUnorderedList() {
        String md = "- alpha\n    - sub-alpha\n    - sub-beta\n- beta";
        String html = MarkdownRenderer.toHtml(md);
        int outerUl = html.indexOf("<ul>");
        int innerUl = html.indexOf("<ul>", outerUl + 1);
        assertTrue(innerUl > outerUl, "inner <ul> must exist inside outer <ul>");
        assertTrue(html.contains("alpha"), "outer alpha");
        assertTrue(html.contains("sub-alpha"), "sub-alpha");
    }

    @Test
    void testMixedNestedList() {
        String md = "1. item\n    - nested-ul\n2. item2";
        String html = MarkdownRenderer.toHtml(md);
        assertTrue(html.contains("<ol start=\"1\">"), "outer ol");
        assertTrue(html.contains("<ul>"), "inner ul");
        assertTrue(html.contains("nested-ul"), "nested item text");
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

    // -------------------------------------------------------------------------
    // Ordered list numbering after code blocks
    // -------------------------------------------------------------------------

    @Test
    void testOrderedListExplicitStartAttribute() {
        // A list starting at 3 must emit <ol start="3">
        String html = MarkdownRenderer.toHtml("3. Third\n4. Fourth\n");
        assertTrue(html.contains("<ol start=\"3\">"),
                "Expected <ol start=\"3\"> but got: " + html);
    }

    @Test
    void testIndentedCodeBlockDoesNotResetNumbering() {
        // Code block indented inside a list item — both items must be in ONE <ol start="1">
        String md = "1. Item one\n\n    ```bash\n    code\n    ```\n\n2. Item two\n";
        String html = MarkdownRenderer.toHtml(md);
        // Only one <ol> should exist; items continue in the same list
        int firstOl = html.indexOf("<ol ");
        int secondOl = html.indexOf("<ol ", firstOl + 1);
        assertEquals(-1, secondOl,
                "Indented code block must not start a new <ol>; got: " + html);
        assertTrue(html.contains("Item one") && html.contains("Item two"),
                "Both items must be present: " + html);
    }

    @Test
    void testIndentedCodeBlockInsideLi() {
        // <pre> must be inside <li>, not between two separate <ol> elements
        String md = "1. Item one\n\n    ```bash\n    code here\n    ```\n\n2. Item two\n";
        String html = MarkdownRenderer.toHtml(md);
        // </li> must not appear before <pre>
        int liClose = html.indexOf("</li>");
        int preOpen = html.indexOf("<pre>");
        assertTrue(preOpen > 0, "Expected <pre> in: " + html);
        assertTrue(preOpen < liClose,
                "<pre> must appear before the first </li>; got: " + html);
    }

    @Test
    void testTopLevelCodeBlockResetsNumbering() {
        // Top-level code block between two lists — second list starts at 1
        String md = "1. Item A\n\n```\ntop level\n```\n\n1. New list\n";
        String html = MarkdownRenderer.toHtml(md);
        long count = html.chars().filter(c -> c == '1')
                .count(); // rough: just assert no <ol start="2"> for the second list
        assertFalse(html.contains("<ol start=\"2\">"),
                "Top-level code block should reset numbering; must not have <ol start=\"2\"> but got: " + html);
    }

    // -------------------------------------------------------------------------
    // Indented code block — no leading spaces in <pre>
    // -------------------------------------------------------------------------

    @Test
    void testIndentedFencedCodeBlockNoLeadingSpaces() {
        String md = "1. Item\n\n    ```bash\n    mkdir foo\n    ```\n\n2. Done\n";
        String html = MarkdownRenderer.toHtml(md);
        // Extract <pre> content and verify no leading spaces
        int preStart = html.indexOf("<pre>") + "<pre>".length();
        int preEnd = html.indexOf("</pre>");
        assertTrue(preStart > 0 && preEnd > preStart, "Expected <pre> block in: " + html);
        String preContent = html.substring(preStart, preEnd);
        assertFalse(preContent.startsWith(" "),
                "Code block content must not start with spaces but got: [" + preContent + "]");
        assertTrue(preContent.contains("mkdir foo"),
                "Code block must contain the command: " + preContent);
    }

    // -------------------------------------------------------------------------
    // Nested list + multiple code blocks
    // -------------------------------------------------------------------------

    @Test
    void testNestedListMultipleCodeBlocks() {
        // Depth-2 list: items 1 and 2 each have an indented code block.
        String md =
                "1. First:\n\n" +
                "    ```bash\n" +
                "    cmd1\n" +
                "    ```\n\n" +
                "2. Second:\n\n" +
                "    ```bash\n" +
                "    cmd2\n" +
                "    ```\n\n" +
                "3. Third\n";
        String html = MarkdownRenderer.toHtml(md);
        // All items must be in ONE <ol>.
        int firstOl = html.indexOf("<ol ");
        int secondOl = html.indexOf("<ol ", firstOl + 1);
        assertEquals(-1, secondOl, "Must be a single <ol>, got: " + html);
        assertTrue(html.contains("cmd1"), "cmd1 present: " + html);
        assertTrue(html.contains("cmd2"), "cmd2 present: " + html);
        assertTrue(html.contains("Third"), "Third present: " + html);
        // Both <pre> blocks must appear before the list closes.
        int pre1 = html.indexOf("<pre>");
        int pre2 = html.indexOf("<pre>", pre1 + 1);
        assertTrue(pre2 > pre1, "Two <pre> blocks expected: " + html);
        assertTrue(html.indexOf("</ol>") > pre2, "</ol> must come after both <pre>: " + html);
    }

    @Test
    void testListReturnsToOuterLevelAfterSubList() {
        // item 2 has a sub-list; item 3 must return to the top level.
        String md =
                "1. Item one\n" +
                "    1. Sub-item\n" +
                "2. Item two\n";
        String html = MarkdownRenderer.toHtml(md);
        // "Item two" must be in the outer <ol>, not stuck inside the inner one.
        // Verify: </ol> count is exactly 2 (inner + outer).
        int count = 0;
        int idx = 0;
        while ((idx = html.indexOf("</ol>", idx)) != -1) { count++; idx++; }
        assertEquals(2, count, "Expected 2 </ol> tags, got: " + html);
        assertTrue(html.contains("Item two"), "Item two present: " + html);
    }

    @Test
    void testUnorderedListIndentedCodeBlockNotSplit() {
        String md =
                "- Item one\n\n" +
                "    ```bash\n" +
                "    echo hi\n" +
                "    ```\n\n" +
                "- Item two\n";
        String html = MarkdownRenderer.toHtml(md);
        int firstUl = html.indexOf("<ul>");
        int secondUl = html.indexOf("<ul>", firstUl + 1);
        assertEquals(-1, secondUl, "Must be a single <ul>, got: " + html);
        assertTrue(html.contains("echo hi"), "code present: " + html);
        assertTrue(html.contains("Item two"), "Item two present: " + html);
    }

    @Test
    void testUnorderedListIndentedCodeBlockInsideLi() {
        String md =
                "- Item one\n\n" +
                "    ```bash\n" +
                "    echo hi\n" +
                "    ```\n\n" +
                "- Item two\n";
        String html = MarkdownRenderer.toHtml(md);
        int preOpen = html.indexOf("<pre>");
        int liClose = html.indexOf("</li>");
        assertTrue(preOpen > 0, "<pre> expected: " + html);
        assertTrue(preOpen < liClose, "<pre> must be before first </li>: " + html);
    }

    // -------------------------------------------------------------------------
    // Sub-list after code block + non-list continuation content
    // -------------------------------------------------------------------------

    @Test
    void testNestedListSubItemsAfterCodeBlock() {
        // Item 3 starts directly with a code block, followed by sub-items 3.1–3.3.
        // Sub-items must be INSIDE item 3's <li>, not at the same level as items 1–4.
        String md =
                "1. Item one\n\n" +
                "    ```bash\n" +
                "    cmd1\n" +
                "    ```\n\n" +
                "2. Item two\n\n" +
                "    ```bash\n" +
                "    cmd2\n" +
                "    ```\n\n" +
                "3. \n\n" +
                "    ```bash\n" +
                "    cmd3\n" +
                "    ```\n\n" +
                "    1. Sub one\n\n" +
                "    2. Sub two\n\n" +
                "    3. Sub three\n\n" +
                "4. Item four\n";
        String html = MarkdownRenderer.toHtml(md);
        // Sub-items must appear after cmd3's </pre> and before item 3's </li>
        int pre3 = html.indexOf("<pre>cmd3</pre>");
        assertTrue(pre3 > 0, "cmd3 <pre> expected: " + html);
        int sub1 = html.indexOf("Sub one", pre3);
        assertTrue(sub1 > 0, "Sub one must appear after cmd3 block: " + html);
        int item3LiClose = html.indexOf("</li>", pre3);
        assertTrue(sub1 < item3LiClose,
                "Sub one must be inside item 3's <li>: " + html);
        // Item four must appear after item 3's </li>
        int item4 = html.indexOf("Item four");
        assertTrue(item4 > item3LiClose,
                "Item four must be outside item 3's <li>: " + html);
    }

    @Test
    void testNonListContinuationInsideLi() {
        // A non-list line more indented than the list item should appear as
        // content of that <li>, not as a separate paragraph.
        String md =
                "1. Parent item\n\n" +
                "    1. Sub item\n\n" +
                "        extra content line\n\n" +
                "    2. Another sub\n";
        String html = MarkdownRenderer.toHtml(md);
        int extraIdx = html.indexOf("extra content line");
        assertTrue(extraIdx > 0, "extra content line must be present: " + html);
        int anotherSub = html.indexOf("Another sub");
        assertTrue(anotherSub > extraIdx, "Another sub must come after extra content: " + html);
        // extra content must not appear between </li> and the next <li> as a <p>
        int subItemClose = html.indexOf("</li>", html.indexOf("Sub item"));
        assertTrue(extraIdx < subItemClose,
                "extra content must be inside Sub item's <li>, not a separate paragraph: " + html);
    }

    @Test
    void testOuterItemAfterCodeBlockInSubList() {
        // 1. A / 1. B (code E) / 2. C / 2. D — D must be at A's level (depth-1).
        // Wrong: ...<li>C</li></ol><ol start="2"><li>D</li></ol></ol>
        // Right: ...<li>C</li></ol><li>D</li></ol>
        String md = "1. A\n\n    1. B\n\n        ```\n        E\n        ```\n\n    2. C\n\n2. D\n";
        String html = MarkdownRenderer.toHtml(md);
        // The inner </ol> (closing B/C list) must be followed directly by <li>D</li>,
        // NOT by another <ol start="..."> wrapper.
        int innerOlClose = html.indexOf("</ol>");
        assertTrue(innerOlClose >= 0, "</ol> expected: " + html);
        int dLiPos = html.indexOf("<li>D</li>");
        assertTrue(dLiPos > innerOlClose, "D must come after inner </ol>: " + html);
        String between = html.substring(innerOlClose + 5, dLiPos);
        assertFalse(between.contains("<ol"), "No <ol> must appear between inner </ol> and D: " + html);
    }
}
