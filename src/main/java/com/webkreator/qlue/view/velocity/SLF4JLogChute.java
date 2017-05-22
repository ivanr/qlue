package com.webkreator.qlue.view.velocity;

import org.apache.velocity.runtime.RuntimeServices;
import org.apache.velocity.runtime.log.LogChute;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// https://github.com/spring-projects/spring-security-saml/blob/master/core/src/main/java/org/springframework/security/saml/util/SLF4JLogChute.java

public class SLF4JLogChute implements LogChute {

    private static final String RUNTIME_LOG_SLF4J_LOGGER = "runtime.log.logsystem.slf4j.logger";

    private static boolean loggingEnabled = true;

    private Logger logger = null;

    private int maxLogLevel = LogChute.ERROR_ID;

    /**
     * @see org.apache.velocity.runtime.log.LogChute#init(org.apache.velocity.runtime.RuntimeServices)
     */
    public void init(RuntimeServices rs) throws Exception {
        String name = (String) rs.getProperty(RUNTIME_LOG_SLF4J_LOGGER);
        if (name != null) {
            logger = LoggerFactory.getLogger(name);
            log(DEBUG_ID, "SLF4JLogChute using logger '" + logger.getName() + '\'');
        } else {
            logger = LoggerFactory.getLogger(this.getClass());
            log(DEBUG_ID, "SLF4JLogChute using logger '" + logger.getClass() + '\'');
        }

        String maxLogLevelText = rs.getString(VelocityViewFactory.QLUE_VELOCITY_MAX_LOG_LEVEL);
        if (maxLogLevelText != null) {
            switch (maxLogLevelText.toLowerCase()) {
                case "trace":
                    maxLogLevel = LogChute.TRACE_ID;
                    break;
                case "debug":
                    maxLogLevel = LogChute.DEBUG_ID;
                    break;
                case "info":
                    maxLogLevel = LogChute.INFO_ID;
                    break;
                case "warn":
                    maxLogLevel = LogChute.WARN_ID;
                    break;
                case "warning":
                    maxLogLevel = LogChute.WARN_ID;
                    break;
                case "error":
                    maxLogLevel = LogChute.ERROR_ID;
                    break;
                default:
                    logger.error("Invalid value for "
                            + VelocityViewFactory.QLUE_VELOCITY_MAX_LOG_LEVEL
                            + ": " + maxLogLevelText);
                    break;
            }

            if (maxLogLevel != LogChute.ERROR_ID) {
                logger.info("Limiting Velocity logging to level " + maxLogLevelText);
            }
        }
    }

    /**
     * @see org.apache.velocity.runtime.log.LogChute#log(int, java.lang.String)
     */
    public void log(int level, String message) {
        if (!loggingEnabled) {
            return;
        }

        if (level > maxLogLevel) {
            level = maxLogLevel;
        }

        switch (level) {
            case LogChute.WARN_ID:
                logger.warn(message);
                break;
            case LogChute.INFO_ID:
                logger.info(message);
                break;
            case LogChute.TRACE_ID:
                logger.trace(message);
                break;
            case LogChute.ERROR_ID:
                logger.error(message);
                break;
            case LogChute.DEBUG_ID:
            default:
                logger.debug(message);
                break;
        }
    }

    /**
     * @see org.apache.velocity.runtime.log.LogChute#log(int, java.lang.String, java.lang.Throwable)
     */
    public void log(int level, String message, Throwable t) {
        if (!loggingEnabled) {
            return;
        }

        if (level > maxLogLevel) {
            level = maxLogLevel;
        }

        switch (level) {
            case LogChute.WARN_ID:
                logger.warn(message, t);
                break;
            case LogChute.INFO_ID:
                logger.info(message, t);
                break;
            case LogChute.TRACE_ID:
                logger.trace(message, t);
                break;
            case LogChute.ERROR_ID:
                logger.error(message, t);
                break;
            case LogChute.DEBUG_ID:
            default:
                logger.debug(message, t);
                break;
        }
    }

    /**
     * @see org.apache.velocity.runtime.log.LogChute#isLevelEnabled(int)
     */
    public boolean isLevelEnabled(int level) {
        if (!loggingEnabled) {
            return false;
        }

        if (level > maxLogLevel) {
            level = maxLogLevel;
        }

        switch (level) {
            case LogChute.DEBUG_ID:
                return logger.isDebugEnabled();
            case LogChute.INFO_ID:
                return logger.isInfoEnabled();
            case LogChute.TRACE_ID:
                return logger.isTraceEnabled();
            case LogChute.WARN_ID:
                return logger.isWarnEnabled();
            case LogChute.ERROR_ID:
                return logger.isErrorEnabled();
            default:
                return true;
        }
    }

    static void setLoggingEnabled(boolean b) {
        loggingEnabled = b;
    }
}