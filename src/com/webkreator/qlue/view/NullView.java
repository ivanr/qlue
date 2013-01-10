package com.webkreator.qlue.view;

import com.webkreator.qlue.Page;
import com.webkreator.qlue.TransactionContext;

/**
 * This class is used to indicate that no further output should be made. 
 */
public class NullView implements View {

	/**
	 * Because this class is a placeholder, this method is never invoked.
	 */
	@Override
	public void render(TransactionContext tx, Page page) throws Exception {
		// Do nothing. This method is never invoked.
				throw new RuntimeException(
						"Qlue: This method shouldn't have been invoked.");
	}
}
