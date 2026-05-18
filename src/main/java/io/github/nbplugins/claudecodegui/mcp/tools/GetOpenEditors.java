// Originally forked from https://github.com/emilianbold/claude-code-netbeans
// Original: src/main/java/org/openbeans/claude/netbeans/tools/GetOpenEditors.java
package io.github.nbplugins.claudecodegui.mcp.tools;

import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.netbeans.api.project.FileOwnerQuery;
import org.netbeans.api.project.Project;
import org.netbeans.api.project.ProjectUtils;
import org.openbeans.claude.netbeans.tools.Tool;
import org.openbeans.claude.netbeans.tools.params.GetOpenEditorsParams;
import org.openbeans.claude.netbeans.tools.params.GetOpenEditorsResult;
import org.openbeans.claude.netbeans.tools.params.Editor;
import java.util.ArrayList;
import java.util.List;
import org.openide.cookies.EditorCookie;
import org.openide.filesystems.FileObject;
import org.openide.loaders.DataObject;
import org.openide.windows.TopComponent;

/**
 * Tool to get list of currently open editor tabs.
 */
public class GetOpenEditors implements Tool<GetOpenEditorsParams, GetOpenEditorsResult> {

    /** Creates a new instance of this tool. */
    public GetOpenEditors() {}

    private static final Logger LOGGER = Logger.getLogger(GetOpenEditors.class.getName());

    @Override
    public String getName() {
        return "getOpenEditors";
    }

    @Override
    public String getDescription() {
        return "Get list of currently open editor tabs";
    }

    @Override
    public Class<GetOpenEditorsParams> getParameterClass() {
        return GetOpenEditorsParams.class;
    }

    /**
     * Returns the set of currently opened {@link TopComponent}s; overridable in tests.
     *
     * @return set of opened top components
     */
    protected Set<TopComponent> getOpenedTopComponents() {
        return TopComponent.getRegistry().getOpened();
    }

    @Override
    public GetOpenEditorsResult run(GetOpenEditorsParams params) throws Exception {
        GetOpenEditorsResult result = new GetOpenEditorsResult();
        List<Editor> editors = new ArrayList<>();

        try {
            for (TopComponent tc : getOpenedTopComponents()) {
                DataObject dataObject = tc.getLookup().lookup(DataObject.class);
                if (dataObject == null) continue;
                EditorCookie editorCookie = dataObject.getLookup().lookup(EditorCookie.class);
                if (editorCookie == null) continue;
                FileObject fileObject = dataObject.getPrimaryFile();
                if (fileObject == null) continue;

                Editor editor = new Editor();
                editor.setName(fileObject.getName());
                editor.setPath(fileObject.getPath());
                editor.setExtension(fileObject.getExt());
                editor.setMimeType(fileObject.getMIMEType());

                Project owner = FileOwnerQuery.getOwner(fileObject);
                if (owner != null) {
                    editor.setProjectName(ProjectUtils.getInformation(owner).getDisplayName());
                    editor.setProjectPath(owner.getProjectDirectory().getPath());
                }

                editors.add(editor);
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error getting open documents", e);
        }

        result.setEditors(editors);
        return result;
    }
}
