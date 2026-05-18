package io.github.nbplugins.claudecodegui.settings;

import java.io.File;
import javax.swing.JComponent;
import org.netbeans.api.project.Project;
import org.netbeans.spi.project.ui.support.ProjectCustomizer;
import org.netbeans.spi.project.ui.support.ProjectCustomizer.Category;
import org.openide.filesystems.FileUtil;
import org.openide.util.Lookup;

/**
 * Provides the "Claude Code" category in the Project Properties dialog for
 * Maven, Java SE, and Gradle projects.
 *
 * <p>Registered via {@code layer.xml} under the customiser paths for each
 * project type.  The registration is done using
 * {@link ProjectCustomizer.CompositeCategoryProvider} so that the panel
 * appears under its own top-level category in the Properties dialog.
 *
 * <h2>Registration paths (layer.xml)</h2>
 * <ul>
 *   <li>{@code Projects/org-netbeans-modules-maven/Customizer} — Maven</li>
 *   <li>{@code Projects/org-netbeans-modules-java-j2seproject/Customizer} — Java SE</li>
 *   <li>{@code Projects/org-netbeans-modules-gradle/Customizer} — Gradle</li>
 * </ul>
 */
public final class ClaudeProjectPropertiesPanelProvider
        implements ProjectCustomizer.CompositeCategoryProvider {

    /** Category identifier used in layer.xml registrations. */
    public static final String CATEGORY_NAME = "ClaudeCode";

    /** Display label shown in the Project Properties tree. */
    static final String CATEGORY_LABEL = "Claude Code";

    /**
     * No-arg constructor required by the layer.xml {@code newvalue} registration.
     */
    public ClaudeProjectPropertiesPanelProvider() {}

    // -------------------------------------------------------------------------
    // CompositeCategoryProvider
    // -------------------------------------------------------------------------

    /**
     * Creates the "Claude Code" category node.
     *
     * @param context project lookup
     * @return the category, or {@code null} if no project is in context
     */
    @Override
    public Category createCategory(Lookup context) {
        Project project = context.lookup(Project.class);
        if (project == null) return null;
        return ProjectCustomizer.Category.create(
                CATEGORY_NAME,
                CATEGORY_LABEL,
                null  // no icon
        );
    }

    /**
     * Creates the settings panel for the given category.
     *
     * @param category the category created by {@link #createCategory(Lookup)}
     * @param context  project lookup
     * @return the settings panel, or an empty panel if no project found
     */
    @Override
    public JComponent createComponent(Category category, Lookup context) {
        Project project = context.lookup(Project.class);
        if (project == null) return new javax.swing.JPanel();
        File projectDir = FileUtil.toFile(project.getProjectDirectory());
        if (projectDir == null) return new javax.swing.JPanel();

        ClaudeProjectPropertiesPanel panel = new ClaudeProjectPropertiesPanel(projectDir);

        // Wire up the OK action so that settings are saved on Apply/OK
        category.setOkButtonListener(e -> panel.store());

        return panel;
    }
}
