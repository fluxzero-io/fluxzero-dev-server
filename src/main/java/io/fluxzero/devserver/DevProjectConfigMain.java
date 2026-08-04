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

            # For multiple complete development configurations, replace the legacy fields below with profiles.
            # Select one with `fz dev --profile worker` or configure defaultProfile.
            # defaultProfile: app
            # profiles:
            #   app:
            #     environment: local
            #     apps: [app]
            #   worker:
            #     environment: local
            #     apps: [worker-local]
            #     frontend:
            #       command: "npm run worker -- --port {frontendPort}"

            # Shared application defaults. All fields except version are optional.
            environment: local
            # mainClass: com.example.Application
            # applicationName: example
            # namespace: local
            # port: 4200 # public URL for the complete dev environment; dynamic when omitted
            # idp: managed # managed or external
            # fastCompiler: false

            # Additional public gateway paths routed unchanged to Fluxzero. /api is always included.
            # backendPaths:
            #   - /webhooks

            # Application/module selectors to start. Omit apps to start all discovered applications.
            apps:
              - app

            # Named application flavors may be selected from apps or with: fz dev --app worker-local
            applicationConfig:
              worker-local:
                application: worker
                applicationName: worker
                # namespace: local-workers
                env:
                  FEATURE_MODE: local
                secrets:
                  API_TOKEN: "op://Shared vault/Worker/local token"

            # To compose independent Maven or Gradle roots, replace apps/applicationConfig with projects.
            # Each project owns its compile, source watch, rolling app replacement, and test pipeline.
            # projects:
            #   application:
            #     directory: .
            #     apps: [app]
            #   auditlog:
            #     directory: ../fluxzero-auditlog/backend
            #     apps: [auditlog-local]
            #     applicationConfig:
            #       auditlog-local:
            #         application: auditlog
            #         namespace: fluxzero_mp_prod-logs

            # Support services start before applications and frontends. Omit command for an external service.
            # Named ports may be fixed numbers or dynamic. Service values can be used in app env and frontend fields.
            # services:
            #   victoriaLogs:
            #     directory: local/victoria-logs
            #     command: docker compose up --remove-orphans
            #     stopCommand: docker compose down --remove-orphans
            #     ports:
            #       http: dynamic
            #     url: "http://127.0.0.1:{servicePort.http}"
            #     env:
            #       COMPOSE_PROJECT_NAME: "my-app-{session.id}"
            #     readiness:
            #       http: "{url}/health"
            #       timeout: 3m
            #   sharedMail:
            #     url: http://127.0.0.1:8025
            #
            # Use resolved service values in an application flavor:
            # applicationConfig:
            #   app-with-logs:
            #     application: app
            #     env:
            #       LOGS_URL: "{services.victoriaLogs.url}"

            # Use command for a managed frontend, url for an externally managed frontend, or omit frontend.
            # directory is the command working directory. {frontendPort} is its private allocated port.
            # The frontend remains publicly available through port, not frontendPort.
            frontend:
              directory: frontend
              setupCommand: "npm install --prefer-offline --no-audit --no-fund"
              command: "npm run dev -- --host 127.0.0.1 --port {frontendPort}"
              # url: "http://127.0.0.1:5173"

            # To serve several frontends through one public gateway, replace frontend with frontends.
            # path is the public URL mount; omit it on the root frontend. More specific paths use longest-prefix
            # routing for HTTP and WebSockets. directory remains the local command working directory.
            # frontends:
            #   application:
            #     directory: frontend
            #     command: "npm run dev -- --host 127.0.0.1 --port {frontendPort}"
            #   auditlog:
            #     path: /marketplace/logs/1
            #     directory: ../fluxzero-auditlog/frontend
            #     command: "npm run start-dashboard -- --host 127.0.0.1 --port {frontendPort}"

            # Stop forgotten environments after inactivity. Use disabled to keep one running indefinitely.
            lifecycle:
              idleTimeout: 24h

            # Startup commands run once per in-memory runtime, in declaration order, and retry after failure.
            # Entries may reference TestFixture JSON files or define a named command inline.
            # TestFixture @class aliases resolve through application types covered by @RegisterType.
            # Glob matches are inserted alphabetically at the pattern's position; ** matches recursively.
            # commands:
            #   - src/test/resources/users/*.json
            #   - create-extra-admin:
            #       type: com.example.CreateUser
            #       revision: 0
            #       payload:
            #         name: Local Admin
            #       metadata:
            #         source: dev
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
