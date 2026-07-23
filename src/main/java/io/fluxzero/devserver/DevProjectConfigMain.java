/*
 * Copyright (c) Fluxzero IP B.V. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fluxzero.devserver;

/** Prints the project configuration reference owned by this dev-server version. */
public final class DevProjectConfigMain {
    private static final String REFERENCE = """
            # .fluxzero/dev.yaml
            version: 1

            # Shared application defaults. All fields except version are optional.
            environment: local
            # mainClass: com.example.Application
            # applicationName: example
            # namespace: local
            # port: 4200
            # idp: managed # managed or external
            # fastCompiler: false

            # Application/module selectors to start. Omit apps to start all discovered applications.
            apps:
              - app

            # Named application flavors may be selected from apps or with: fz dev --app worker-local
            applicationConfig:
              worker-local:
                application: worker
                applicationName: worker
                env:
                  FEATURE_MODE: local
                secrets:
                  API_TOKEN: "op://Shared vault/Worker/local token"

            # Use command for a managed frontend, url for an externally managed frontend, or omit frontend.
            # command and url are mutually exclusive. {port} is the private frontend port allocated by Fluxzero.
            frontend:
              directory: frontend
              setupCommand: "npm install --prefer-offline --no-audit --no-fund"
              command: "npm run dev -- --host 127.0.0.1 --port {port}"
              # url: "http://127.0.0.1:5173"
              backendPaths:
                - /api

            # Startup commands run once per in-memory runtime, in declaration order, and retry after failure.
            # commands:
            #   create-admin:
            #     type: com.example.CreateUser
            #     revision: 0
            #     payload:
            #       name: Local Admin
            #     metadata:
            #       source: dev
            """;

    private DevProjectConfigMain() {
    }

    public static void main(String[] args) {
        if (args.length > 0) {
            throw new IllegalArgumentException("fz dev config does not accept arguments");
        }
        System.out.print(REFERENCE);
    }

    static String reference() {
        return REFERENCE;
    }
}
