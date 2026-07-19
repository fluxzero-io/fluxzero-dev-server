/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.devserver;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggerContextListener;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.AppenderBase;
import org.slf4j.LoggerFactory;

final class EmbeddedLogCapture implements AutoCloseable {
    private final LoggerContext context;
    private final Logger rootLogger;
    private final CaptureAppender appender;
    private final ResetListener resetListener;

    static EmbeddedLogCapture start(DevLogStore store) {
        if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext context)) {
            return new EmbeddedLogCapture(null, null, null, null);
        }
        Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        CaptureAppender appender = new CaptureAppender(store);
        appender.setContext(context);
        appender.setName("fluxzero-dev-structured-log-" + store.sessionId());
        appender.start();
        ResetListener resetListener = new ResetListener(root, appender);
        context.addListener(resetListener);
        resetListener.attach();
        return new EmbeddedLogCapture(context, root, appender, resetListener);
    }

    private EmbeddedLogCapture(LoggerContext context, Logger rootLogger, CaptureAppender appender,
                               ResetListener resetListener) {
        this.context = context;
        this.rootLogger = rootLogger;
        this.appender = appender;
        this.resetListener = resetListener;
    }

    @Override
    public void close() {
        if (rootLogger != null && appender != null) {
            rootLogger.detachAppender(appender);
            appender.stop();
        }
        if (context != null && resetListener != null) {
            context.removeListener(resetListener);
        }
    }

    private static final class CaptureAppender extends AppenderBase<ILoggingEvent> {
        private final DevLogStore store;

        private CaptureAppender(DevLogStore store) {
            this.store = store;
        }

        @Override
        protected void append(ILoggingEvent event) {
            ServiceIdentity identity = identity(event.getLoggerName());
            String message = event.getFormattedMessage();
            if (event.getThrowableProxy() != null) {
                message += System.lineSeparator() + ThrowableProxyUtil.asString(event.getThrowableProxy());
            }
            store.embedded(identity.source(), identity.serviceType(), identity.serviceId(), level(event.getLevel()),
                           message);
        }

        private static ServiceIdentity identity(String loggerName) {
            if (loggerName != null && loggerName.startsWith("io.fluxzero.testserver")) {
                return new ServiceIdentity("runtime", "infrastructure", "runtime");
            }
            if (loggerName != null && loggerName.startsWith("io.fluxzero.proxy")) {
                return new ServiceIdentity("proxy", "infrastructure", "proxy");
            }
            if (loggerName != null && loggerName.startsWith("io.fluxzero.devserver")) {
                return new ServiceIdentity("dev-server", "supervisor", "dev-server");
            }
            return new ServiceIdentity("embedded", "embedded", loggerName == null ? "unknown" : loggerName);
        }

        private static DevLogEvent.Level level(Level level) {
            if (level == null) {
                return DevLogEvent.Level.INFO;
            }
            return switch (level.levelInt) {
                case Level.ERROR_INT -> DevLogEvent.Level.ERROR;
                case Level.WARN_INT -> DevLogEvent.Level.WARN;
                case Level.DEBUG_INT -> DevLogEvent.Level.DEBUG;
                case Level.TRACE_INT -> DevLogEvent.Level.TRACE;
                default -> DevLogEvent.Level.INFO;
            };
        }
    }

    private record ServiceIdentity(String source, String serviceType, String serviceId) {
    }

    private static final class ResetListener implements LoggerContextListener {
        private final Logger rootLogger;
        private final CaptureAppender appender;

        private ResetListener(Logger rootLogger, CaptureAppender appender) {
            this.rootLogger = rootLogger;
            this.appender = appender;
        }

        private void attach() {
            if (rootLogger.getAppender(appender.getName()) == null) {
                rootLogger.addAppender(appender);
            }
        }

        @Override
        public boolean isResetResistant() {
            return true;
        }

        @Override
        public void onStart(LoggerContext context) {
            attach();
        }

        @Override
        public void onReset(LoggerContext context) {
            appender.setContext(context);
            appender.start();
            attach();
        }

        @Override
        public void onStop(LoggerContext context) {
        }

        @Override
        public void onLevelChange(Logger logger, Level level) {
        }
    }
}
