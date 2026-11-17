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

import io.fluxzero.tools.account.AccountBrowserContext;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class DevAccountReturnContextTest {
    @Test void keepsTheExactLocalDevboardPage() throws Exception {
        var context=DevConsole.accountBrowserContext("http://localhost:4741",bytes("{\"returnTo\":\"http://localhost:4741/_fluxzero/dev/#cloud/overview\"}"));
        assertEquals(AccountBrowserContext.Source.DEVBOARD,context.source());
        assertEquals("cloud/overview",context.returnTo().getFragment());
        assertNull(DevConsole.accountBrowserContext("http://localhost:4741",bytes("{}")).returnTo());
    }
    @Test void rejectsOtherOriginsAndUnexpectedAccountOptions() {
        for(String json:new String[]{"{\"returnTo\":\"http://localhost:9999/_fluxzero/dev/\"}","{\"returnTo\":\"https://example.com/_fluxzero/dev/\"}","{\"returnTo\":\"http://localhost:4741/other\"}","{\"returnTo\":true}","{\"other\":\"value\"}"})
            assertThrows(IllegalArgumentException.class,()->DevConsole.accountBrowserContext("http://localhost:4741",bytes(json)));
    }
    private static byte[] bytes(String text) {return text.getBytes(StandardCharsets.UTF_8);}
}
