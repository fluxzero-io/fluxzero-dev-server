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

package com.example.app;

import io.fluxzero.sdk.tracking.handling.HandleCommand;
import io.fluxzero.sdk.tracking.handling.HandleQuery;

public class GreetingHandlers {
    private final String devMode;

    public GreetingHandlers(String devMode) {
        this.devMode = devMode;
    }

    @HandleCommand
    public GreetingCreated handle(CreateGreeting command) {
        String value = "base:" + command.name() + ":" + AppVersion.VALUE;
        GreetingState.add(value);
        return new GreetingCreated(value);
    }

    @HandleQuery
    public GreetingSnapshot handle(GetGreetingState query) {
        return new GreetingSnapshot(AppVersion.VALUE, System.getenv("ENVIRONMENT"), devMode, GreetingState.entries());
    }
}
