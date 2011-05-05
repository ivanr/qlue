/* 
 * Qlue Web Application Framework
 * Copyright 2009,2010 Ivan Ristic <ivanr@webkreator.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.webkreator.qlue;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.webkreator.qlue.view.FinalRedirectView;

/**
 * Keeps track of all persistent pages.
 */
public class QluePageManager {

	private Log log = LogFactory.getLog(QluePageManager.class);

	private static final int MAX_PERSISTENT_PAGES_PER_SESSION = 64;

	private int nextPersistentPageId;

	private Map<Integer, PersistentPageRecord> pages = new HashMap<Integer, PersistentPageRecord>();

	/**
	 * Initialize a new page manager.
	 */
	QluePageManager() {
		SecureRandom random = new SecureRandom();

		// Generate secret token
		byte[] randomBytes = new byte[16];
		random.nextBytes(randomBytes);		

		// Generate the first persistent ID we are going to use. 
		// We randomize a bit to avoid being predictable.
		nextPersistentPageId = 100000 + random.nextInt(900000);
	}

	/**
	 * Find persistent page with given ID.
	 *  
	 * @param id
	 * @return
	 */
	public Page findPage(Integer id) {
		PersistentPageRecord record = pages.get(id);
		if (record == null) {
			return null;
		}

		return record.page;
	}

	/**
	 * Store persistent page.
	 * 
	 * @param page
	 */
	public synchronized void storePage(Page page) {
		// Generate persistence ID when we're storing
		// the page for the first time.
		if (page.getId() == null) {
			page.setId(new Integer(generatePageId()));
			pages.put(page.getId(), new PersistentPageRecord(System.currentTimeMillis(),
					page));
		} else {
			// A page that already has an ID probably also
			// has a record. Look it up.
			PersistentPageRecord record = pages.get(page.getId());
			record.lastActivityTime = System.currentTimeMillis();
		}

		// Remove one page if we went over the limit.
		if (pages.size() > MAX_PERSISTENT_PAGES_PER_SESSION) {
			Integer oldestId = 0;
			long oldestTime = System.currentTimeMillis();

			for (PersistentPageRecord record : pages.values()) {
				if (record.lastActivityTime <= oldestTime) {
					oldestTime = record.createTime;
					oldestId = record.page.getId();
				}
			}

			pages.remove(oldestId);

			if (log.isWarnEnabled()) {
				// TODO Log session ID, username if we know them.
				log.warn("Forced removal of page from session storage: "
						+ oldestId);
			}
		}
	}

	/**
	 * Generate unique persistent page ID.
	 * @return
	 */
	public synchronized int generatePageId() {
		// TODO Randomly increment IDs
		return nextPersistentPageId++;
	}

	/**
	 * Replace a persistent page with the ReplacementView instance provide.
	 * 
	 * @param page
	 * @param view
	 */
	public void replacePage(Page page, FinalRedirectView view) {
		PersistentPageRecord record = pages.get(page.getId());
		record.page = null;
		record.replacementUri = view.getUri();		
	}

	/**
	 * Look for the record of the page with the given ID.
	 * 
	 * @param id
	 * @return
	 */
	public PersistentPageRecord findPageRecord(int id) {
		return pages.get(id);		
	}
}
