package com.dynamo.cr.editor.handlers;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.ParseException;
import java.util.HashMap;
import java.util.Map;
import java.awt.Desktop;

import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.io.IOUtils;
import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.filesystem.EFS;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.QualifiedName;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubProgressMonitor;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.internal.ide.actions.BuildUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dynamo.cr.editor.Activator;
import com.dynamo.cr.editor.BobUtil;
import com.dynamo.cr.editor.core.EditorUtil;
import com.dynamo.cr.editor.core.ProjectProperties;
import com.dynamo.cr.engine.Engine;
import com.dynamo.cr.target.bundle.HTML5Bundler;

/**
 * LaunchHtmlHandler:
 * 	Bundles the game, placing content into a folder under the build directory, and launches the user's default browser.
 * @author peterhodges
 *
 */
//this suppression is for the usage of BuildUtilities in the bottom of this class
@SuppressWarnings("restriction")
public class LaunchHtmlHandler extends AbstractHandler {

	private static final String HTML_DIR = "__htmlLaunchDir";

	private ProjectProperties projectProperties = null;

	@Override
    public boolean isEnabled() {
        return Activator.getDefault().getBranchClient() != null;
    }

	IProject getActiveProject(ExecutionEvent event) {
        IEditorPart editor = HandlerUtil.getActiveEditor(event);
        if (editor != null) {
            IEditorInput input = editor.getEditorInput();
            if (input instanceof IFileEditorInput) {
                IFileEditorInput file_input = (IFileEditorInput) input;
                final IProject project = file_input.getFile().getProject();
                return project;
            }
        }
        // else return first project
        return ResourcesPlugin.getWorkspace().getRoot().getProjects()[0];
    }

	private void loadProjectProperties(IProject project) throws CoreException, IOException, ParseException {
        URI projectPropertiesLocation = EditorUtil.getContentRoot(project).getFile("game.project").getRawLocationURI();
        File localProjectPropertiesFile = EFS.getStore(projectPropertiesLocation).toLocalFile(0, new NullProgressMonitor());

        ProjectProperties projectProperties = new ProjectProperties();
        FileInputStream in = new FileInputStream(localProjectPropertiesFile);
        try {
            projectProperties.load(in);
            this.projectProperties = projectProperties;
        } finally {
            IOUtils.closeQuietly(in);
        }
    }

    private String getProjectRoot(IProject project) throws CoreException {
        URI projectLocation = EditorUtil.getContentRoot(project).getRawLocationURI();
        File localProjectRoot = EFS.getStore(projectLocation).toLocalFile(0, new NullProgressMonitor());
        String projectRoot = new Path(localProjectRoot.getAbsolutePath()).toPortableString();
        return projectRoot;
    }

    private String getCompiledContent(IProject project) throws CoreException {
        return new Path(getProjectRoot(project)).append("build").append("default").toPortableString();
    }

	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
		final Logger logger = LoggerFactory.getLogger(AbstractBundleHandler.class);

		// save all editors depending on user preferences (this is set to true by default in plugin_customization.ini)
        BuildUtilities.saveEditors(null);

        Job job = new Job("LaunchingHtml") {
        	@Override
        	protected IStatus run(IProgressMonitor monitor) {
                 try {
                	 IProject project = EditorUtil.getProject();
                	 loadProjectProperties(project);

                	 buildProject(project, IncrementalProjectBuilder.FULL_BUILD, new SubProgressMonitor(monitor, 9));
                     int severity = project.findMaxProblemSeverity(IMarker.PROBLEM, true, IResource.DEPTH_INFINITE);

                     if (severity < IMarker.SEVERITY_ERROR) {
                    	 bundleContent(project);
                         launchBrowser();
                     }

                     buildProject(project, IncrementalProjectBuilder.CLEAN_BUILD, new SubProgressMonitor(monitor, 1));
                     projectProperties = null;

                     return Status.OK_STATUS;
                 } catch (CoreException e) {
                	 return e.getStatus();
                 } catch (Exception e) {
                	 final String msg = e.getMessage();
                     final Status status = new Status(IStatus.ERROR, "com.dynamo.cr", msg);
                     logger.error("Failed to launch HTML application", e);
                     return status;
                 }
        	}
        };
        job.setProperty(new QualifiedName("com.dynamo.cr.editor", "buildHtml"), true);
        job.schedule();
		return null;
	}

	private void bundleContent(IProject project) throws CoreException, IOException, ConfigurationException {
		String js = Engine.getDefault().getEnginePath("js-web", true);
		String projectRoot = getProjectRoot(project);
		String contentRoot = getCompiledContent(project);

		File launchHtmlPath = new File(contentRoot, HTML_DIR);
		launchHtmlPath.mkdirs();
		String outputDir = new Path(launchHtmlPath.getAbsolutePath()).toPortableString();

        HTML5Bundler bundler = new HTML5Bundler(projectProperties, js, projectRoot, contentRoot, outputDir);
        bundler.bundleApplication();
	}

	private void launchBrowser() throws IOException, URISyntaxException, UnsupportedOperationException {
		String title = projectProperties.getStringValue("project",  "title", "Unnamed");
        String launchPath = String.format("http://localhost:8080/build/default/%1$s/%2$s/%2$s.html", HTML_DIR, title);
        if (Desktop.isDesktopSupported()) {
        	Desktop desktop = Desktop.getDesktop();
        	if (desktop.isSupported(Desktop.Action.BROWSE)) {
        		desktop.browse(new URI(launchPath));
        	} else {
        		throw new UnsupportedOperationException("Cannot launch browser");
        	}
        }
	}

	private void buildProject(IProject project, int kind, IProgressMonitor monitor) throws CoreException {
        HashMap<String, String> bobArgs = new HashMap<String, String>();
        bobArgs.put("build_disk_archive", "true");
        if (this.projectProperties.getBooleanValue("project", "compress_archive", true)) {
            bobArgs.put("compress_disk_archive_entries", "true");
        }

        Map<String, String> args = new HashMap<String, String>();
        args.put("location", "local");
        BobUtil.putBobArgs(bobArgs, args);
        project.build(kind,  "com.dynamo.cr.editor.builders.contentbuilder", args, monitor);
    }

}
