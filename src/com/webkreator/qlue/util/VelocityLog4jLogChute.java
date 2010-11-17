/* 
 * Qlue Web Application Framework
 * Copyright 2009 Ivan Ristic <ivanr@webkreator.com>
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
package com.webkreator.qlue.util;

import java.lang.reflect.Field;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.velocity.runtime.RuntimeServices;
import org.apache.velocity.runtime.log.LogChute;

/**
 * Velocity logging adapter. Receive log messages from the Velocity engine and
 * forward them to log4J. This class has been adapted from Log4JLogChute from
 * the Velocity package.
 */
public class VelocityLog4jLogChute implements LogChute {

	protected Logger logger = null;

	private boolean hasTrace = false;

	public static final String RUNTIME_LOG_LOG4J_LOGGER = "runtime.log.logsystem.log4j.logger";

	@Override
	public void init(RuntimeServices rs) throws Exception {
		// Determine if log4j supports TRACE.
		try {
			@SuppressWarnings("unused")
			Field traceLevel = Level.class.getField("TRACE");
			// We'll never get here in pre 1.2.12 log4j.
			hasTrace = true;
		} catch (NoSuchFieldException e) {
			log(DEBUG_ID,
					"The version of log4j being used does not support the \"trace\" level.");
		}

		String name = (String) rs.getProperty(RUNTIME_LOG_LOG4J_LOGGER);
		if (name != null) {
			logger = Logger.getLogger(name);
			log(DEBUG_ID, "VelocityLog4jLogChute using logger '" + name + '\'');
		} else {
			// Create a logger with this class name to avoid conflicts.
			logger = Logger.getLogger(this.getClass().getName());
		}
	}

	@Override
	public boolean isLevelEnabled(int level) {
		switch (level) {
		case LogChute.DEBUG_ID:
			return logger.isDebugEnabled();
		case LogChute.INFO_ID:
			return logger.isInfoEnabled();
		case LogChute.TRACE_ID:
			if (hasTrace) {
				return logger.isTraceEnabled();
			} else {
				return logger.isDebugEnabled();
			}
		case LogChute.WARN_ID:
			return logger.isEnabledFor(Level.WARN);
		case LogChute.ERROR_ID:
			// Can't be disabled in log4j.
			return logger.isEnabledFor(Level.ERROR);
		default:
			return true;
		}
	}

	@Override
	public void log(int level, String message) {
		switch (level) {
		case LogChute.WARN_ID:
			logger.warn(message);
			break;
		case LogChute.INFO_ID:
			logger.info(message);
			break;
		case LogChute.DEBUG_ID:
			logger.debug(message);
			break;
		case LogChute.TRACE_ID:
			if (hasTrace) {
				logger.trace(message);
			} else {
				logger.debug(message);
			}
			break;
		case LogChute.ERROR_ID:
			logger.error(message);
			break;
		default:
			logger.debug(message);
			break;
		}
	}

	@Override
	public void log(int level, String message, Throwable t) {
		switch (level) {
		case LogChute.WARN_ID:
			logger.warn(message, t);
			break;
		case LogChute.INFO_ID:
			logger.info(message, t);
			break;
		case LogChute.DEBUG_ID:
			logger.debug(message, t);
			break;
		case LogChute.TRACE_ID:
			if (hasTrace) {
				logger.trace(message, t);
			} else {
				logger.debug(message, t);
			}
			break;
		case LogChute.ERROR_ID:
			logger.error(message, t);
			break;
		default:
			logger.debug(message, t);
			break;
		}
	}
}
