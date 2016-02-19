package com.webkreator.qlue.router;

/**
 * This interface enables router implementations to retrieve the information about the default (index) file and dynamic
 * file suffixes from the supervising class, without tying them to a concrete implementation.
 */
public interface RouteManager {
	
	/**
	 * Returns the name of the default index name (e.g., "index").
	 */
	public String getIndex();

    /**
     * Changes the default index name.
     * @param index New index name.
     */
    public void setIndex(String index);
	
	/**
	 * Returns the suffix that will be used to determine which transactions can be mapped to pages. (e.g., ".html").
	 */
	public String getSuffix();

    /**
     * Changes the default page suffix.
     * @param suffix New suffix.
     */
    public void setSuffix(String suffix);
}
