package com.webkreator.qlue.router;

/**
 * This interface enables router implementations to retrieve the information about the default (index) file and dynamic
 * file suffixes from the supervising class, without tying them to a concrete implementation.
 */
public interface RouteManager {
	
	/**
	 * Returns the name of the default index name (e.g., "index").
	 */
	String getIndex();

    /**
     * Changes the default index name.
     * @param index New index name.
     */
    void setIndex(String index);
	
	/**
	 * Returns the suffix that will be used to determine which transactions can be mapped to pages. (e.g., ".html").
	 */
	String getSuffix();

    /**
     * Changes the default page suffix.
     * @param suffix New suffix.
     */
    void setSuffix(String suffix);

    String getIndexWithSuffix();

    boolean isConvertDashesToUnderscores();

    void setConcertDashesToUnderscores(boolean b);
}
