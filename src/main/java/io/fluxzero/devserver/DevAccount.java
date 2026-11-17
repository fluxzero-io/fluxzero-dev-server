/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package io.fluxzero.devserver;

import io.fluxzero.tools.account.AccountException;
import io.fluxzero.tools.account.AccountBrowserContext;
import io.fluxzero.tools.account.AccountStatus;
import io.fluxzero.tools.account.LocalAccountService;

import java.util.Map;
import java.util.function.Supplier;

/** Lazy optional account boundary. Account failures never enter workspace health or lifecycle state. */
final class DevAccount implements AutoCloseable {
    private final Supplier<LocalAccountService> factory;
    private LocalAccountService service;

    DevAccount() { this(LocalAccountService::system); }
    DevAccount(Supplier<LocalAccountService> factory) { this.factory = factory; }

    private synchronized LocalAccountService service() {
        if (service == null) service = factory.get();
        return service;
    }

    Object call(String action) {
        return call(action, AccountBrowserContext.terminal());
    }

    Object call(String action, AccountBrowserContext context) {
        try {
            AccountStatus status = switch (action) {
                case "status" -> service().status();
                case "login" -> service().startLogin(false, context);
                case "login-another" -> service().startLogin(true, context);
                case "logout-everywhere" -> service().startLogoutEverywhere(context);
                case "cancel-browser" -> service().cancelBrowserAction();
                case "check" -> service().checkSession(false);
                case "check-interactive" -> service().checkSession(true);
                case "cancel" -> service().cancelLogin();
                case "logout" -> service().logout();
                case "refresh" -> service().refresh();
                default -> throw new IllegalArgumentException("Unknown account action");
            };
            return Map.of("account", status);
        } catch (AccountException error) {
            return Map.of("error", Map.of("code", error.code(), "message", error.getMessage()));
        } catch (RuntimeException | LinkageError error) {
            return Map.of("error", Map.of("code", "account_unavailable", "message",
                    "Account features are temporarily unavailable. You can keep working locally."));
        }
    }

    @Override public synchronized void close() {
        if (service != null) {
            try { service.close(); } catch (RuntimeException ignored) { /* Account cleanup cannot block workspace cleanup. */ }
            service = null;
        }
    }
}
