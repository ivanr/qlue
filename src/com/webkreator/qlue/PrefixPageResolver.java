package com.webkreator.qlue;

import java.util.ArrayList;
import java.util.List;
import java.util.StringTokenizer;
import java.util.regex.Matcher;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

public class PrefixPageResolver extends PageResolver {

	private Log log = LogFactory.getLog(PageResolver.class);

	protected String uriSuffix = ".html";

	private String classSuffix = null;

	protected List<UriMapping> mappings = new ArrayList<UriMapping>();

	public List<UriMapping> getMappings() {
		return mappings;
	}

	public void setMappings(List<UriMapping> mappings) {
		this.mappings = mappings;
	}

	@Override
	public boolean isFolderUri(String uri) {
		// TODO Auto-generated method stub
		return false;
	}

	@SuppressWarnings("unchecked")
	@Override
	protected Class<Page> resolvePageClass(String uri) throws Exception {
		// Loop through the mappings in an attempt to
		// find one that will match
		for (UriMapping mapping : mappings) {
			if (log.isDebugEnabled()) {
				log.debug("Using mapping: " + mapping);
			}

			Matcher m = mapping.getMatcher(uri);
			if (m.matches()) {
				if (mapping.getPackageMapping() != null) {					
					// Map URI to package
					return resolveUri("/" + m.group(1),
							mapping.getPackageMapping());
				} else {					
					// Map URI to class
					@SuppressWarnings("rawtypes")
					Class pageClass = classForName(mapping.getClassMapping());

					// Check class is instance of Page
					if (!Page.class.isAssignableFrom(pageClass)) {
						throw new RuntimeException("ClassPageResolver: Class "
								+ mapping.getClassMapping()
								+ " is not a subclass of Page.");
					}

					return (Class<Page>) pageClass;
				}
			}
		}

		return null;
	}

	@SuppressWarnings("unchecked")
	public Class<Page> resolveUri(String uri, String rootPackage)
			throws Exception {
		@SuppressWarnings("rawtypes")
		Class pageClass = null;

		if (uri.indexOf("..") != -1) {
			throw new SecurityException("Directory backreferences not allowed");
		}

		// Make sure we know the root of the page hierarchy.
		if (rootPackage == null) {
			throw new ValidationException(
					"ClassPageResolver: Parameter rootPackage not set");
		}

		// Handle URI suffix
		if (uriSuffix != null) {
			if (uri.endsWith(uriSuffix)) {
				// Remove suffix from URI
				uri = uri.substring(0, uri.length() - 5);
			} else {
				// If the URI does not end with the configured
				// suffix then we can't match it to a page.
				if (log.isDebugEnabled()) {
					log.debug("Giving up mapping because no suffix match");
				}
				
				return null;
			}
		}

		// Start building class name.
		StringBuilder sb = new StringBuilder();

		// Start with the root package.
		sb.append(rootPackage);

		// Each folder in the URI corresponds to a package name.
		String lastToken = null;
		StringTokenizer st = new StringTokenizer(uri, "/");
		while (st.hasMoreTokens()) {
			if (lastToken != null) {
				sb.append(".");
				sb.append(lastToken);
			}

			lastToken = st.nextToken();
		}

		if (lastToken != null) {
			sb.append(".");
			sb.append(lastToken);
		}

		// Append class name suffix when needed
		if (classSuffix != null) {
			sb.append(classSuffix);
		}

		// Java does not allow dashes in class and package names, so
		// we have to replace them with underscores.
		String className = sb.toString().replace("-", "_");

		// Look for a class with this name.
		pageClass = classForName(className);
		if (pageClass == null) {
			if (log.isDebugEnabled()) {
				log.debug("Tried: " + className);
			}
			return null;
		}

		if (log.isDebugEnabled()) {
			log.debug("Found: " + className);
		}

		// Check class is instance of Page
		if (!Page.class.isAssignableFrom(pageClass)) {
			throw new RuntimeException("ClassPageResolver: Class " + className
					+ " is not a subclass of Page.");
		}

		return (Class<Page>) pageClass;
	}

	/**
	 * Find a class given its name.
	 * 
	 * @param classname
	 * @return
	 */
	@SuppressWarnings("rawtypes")
	public static Class classForName(String classname) {
		try {
			ClassLoader classLoader = Thread.currentThread()
					.getContextClassLoader();
			return Class.forName(classname, true, classLoader);
		} catch (ClassNotFoundException cnfe) {
			return null;
		} catch (NoClassDefFoundError ncdfe) {
			// NoClassDefFoundError is thrown when there is a class
			// that matches the name when ignoring case differences.
			// We do not care about that.
			return null;
		}
	}

	@Override
	public String resolvePackage(String uri) throws Exception {
		// Loop through the mappings in an attempt to
		// find one that will match
		for (UriMapping mapping : mappings) {
			if (log.isDebugEnabled()) {
				log.debug("Using mapping: " + mapping);
			}

			Matcher m = mapping.getMatcher(uri);
			if (m.matches()) {
				if (mapping.getPackageMapping() != null) {					
					return mapping.getPackageMapping() + "/" + m.group(1);
				} else {					
					// XXX Remove class name
					return mapping.getClassMapping();
				}
			}
		}

		return null;
	}
}
