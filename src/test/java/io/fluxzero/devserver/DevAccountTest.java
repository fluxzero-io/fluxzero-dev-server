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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fluxzero.tools.account.AccountConfiguration;
import io.fluxzero.tools.account.LocalAccountService;
import io.fluxzero.tools.account.SecureCredentialStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DevAccountTest {
    @Test void constructionAndShutdownDoNotInitializeOptionalAccountFeatures() {
        var calls = new AtomicInteger();
        try (var account = new DevAccount(() -> { calls.incrementAndGet(); throw new IllegalStateException("private-detail"); })) {
            assertEquals(0, calls.get());
        }
        assertEquals(0, calls.get());
    }

    @Test void accountInitializationFailuresAreCustomerSafeAndContained() throws Exception {
        try (var account = new DevAccount(() -> { throw new IllegalStateException("private-detail"); })) {
            String result = new ObjectMapper().writeValueAsString(account.call("status"));
            assertTrue(result.contains("You can keep working locally"));
            assertFalse(result.contains("private-detail"));
        }
    }

    @Test void accountStatusWorksWithoutProjectProcessesNetworkOrCredentialAccess(@TempDir Path directory) throws Exception {
        var configuration = new AccountConfiguration("test", URI.create("https://unreachable.example.test"), "test-client", "",
                URI.create("https://unreachable.example.test/account"), directory.resolve("account"), false, Duration.ofSeconds(10));
        var store = new SecureCredentialStore() {
            public Optional<byte[]> read(String key, boolean interactive) { throw new AssertionError("No credential access"); }
            public void write(String key, byte[] value, boolean interactive) { throw new AssertionError("No credential write"); }
            public void delete(String key, boolean interactive) { throw new AssertionError("No credential delete"); }
        };
        try (var account = new DevAccount(() -> new LocalAccountService(configuration, store, uri -> fail("No browser")))) {
            assertTrue(new ObjectMapper().writeValueAsString(account.call("status")).contains("signed_out"));
            var tools = AccountTools.tools(account, new ObjectMapper());
            assertEquals(java.util.Set.of("get_account", "start_login", "logout"), tools.stream().map(t -> t.tool().name()).collect(java.util.stream.Collectors.toSet()));
            assertTrue(tools.getFirst().tool().annotations().readOnlyHint());
            assertFalse(Files.exists(configuration.directory()));
            assertFalse(tools.stream().filter(t -> t.tool().name().equals("start_login")).findFirst().orElseThrow().tool().annotations().idempotentHint());
        }
    }
}
