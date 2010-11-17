package com.webkreator.qlue.view;

import java.io.File;
import java.util.Properties;
import java.util.StringTokenizer;

import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.runtime.RuntimeConstants;

import com.webkreator.qlue.Page;
import com.webkreator.qlue.QlueApplication;

public class ClasspathVelocityViewFactory extends VelocityViewFactory {

	protected String macroPath = "";

	@Override
	public void init(QlueApplication qlueApp) throws Exception {
		// Initialise properties.
		Properties properties = new Properties();
		properties.setProperty("input.encoding", inputEncoding);
		properties.setProperty("resource.loader", "class");
		properties
				.setProperty("class.resource.loader.class",
						"org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader");
		properties.setProperty("velocimacro.library", macroPath);
		properties.setProperty(RuntimeConstants.RUNTIME_LOG_LOGSYSTEM_CLASS,
				logChute);

		// Initialise engine.
		velocityEngine = new VelocityEngine(properties);
	}

	public View constructViewX(Page page, String viewName) throws Exception {
		String lastToken = null;
		StringTokenizer st = new StringTokenizer(page.getClass().getName(), ".");
		StringBuffer sb = new StringBuffer();
		while (st.hasMoreTokens()) {
			if (lastToken != null) {
				sb.append("/");
				sb.append(lastToken);
			}

			lastToken = st.nextToken();
		}

		String name = sb.toString() + "/" + new File(viewName).getName()
				+ suffix;

		return new VelocityView(this, velocityEngine.getTemplate(name));
	}

	@Override
	public View constructView(Page page, String viewName) throws Exception {
		String name = null;
				
		if (viewName.charAt(0) == '/') {
			// Absolute view
			String packageName = page.getQlueApp().getPageResolver()
					.resolvePackage(viewName);
			if (packageName == null) {
				throw new Exception("Unable to find package for path: "
						+ viewName);
			}

			name = packageName.replace('.', '/') + suffix;			
		} else {
			// Relative view
			String lastToken = null;
			StringTokenizer st = new StringTokenizer(page.getClass().getName(),
					".");
			StringBuffer sb = new StringBuffer();
			while (st.hasMoreTokens()) {
				if (lastToken != null) {
					sb.append("/");
					sb.append(lastToken);
				}

				lastToken = st.nextToken();
			}

			name = sb.toString() + "/" + new File(viewName).getName() + suffix;
		}

		return new VelocityView(this, velocityEngine.getTemplate(name));
	}

	public void setMacroPath(String macroPath) {
		this.macroPath = macroPath;
	}
}
