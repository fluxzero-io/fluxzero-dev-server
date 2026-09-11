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

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransport;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Serializes SDK queue submissions while tool handlers remain concurrent. */
final class SerialStdioServerTransportProvider extends StdioServerTransportProvider {
    private final ExecutorService submissions;
    private final Scheduler sender;

    SerialStdioServerTransportProvider(McpJsonMapper mapper, InputStream input, OutputStream output) {
        this(mapper, input, output, Executors.newSingleThreadExecutor(
                Thread.ofPlatform().daemon().name("fluxzero-stdio-send").factory()));
    }

    SerialStdioServerTransportProvider(McpJsonMapper mapper, InputStream input, OutputStream output,
                                      ExecutorService submissions) {
        super(mapper, input, output);
        this.submissions = submissions;
        sender = Schedulers.fromExecutorService(submissions);
    }

    @Override
    public void setSessionFactory(McpServerSession.Factory factory) {
        // MCP SDK 2.0.1 uses tryEmitNext on a non-serialized sink. Concurrent responses or
        // resource notifications otherwise terminate its inbound stream (upstream issue #686).
        super.setSessionFactory(delegate -> factory.create(new McpServerTransport() {
            @Override
            public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
                return delegate.sendMessage(message).subscribeOn(sender);
            }

            @Override
            public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
                return delegate.unmarshalFrom(data, typeRef);
            }

            @Override
            public List<String> protocolVersions() {
                return delegate.protocolVersions();
            }

            @Override
            public Mono<Void> closeGracefully() {
                return delegate.closeGracefully().subscribeOn(sender);
            }

            @Override
            public void close() {
                delegate.close();
            }
        }));
    }

    @Override
    public Mono<Void> closeGracefully() {
        // The SDK close signal does not await in-flight tool responses. Drain already queued submissions;
        // cancelling them would leave their Monos incomplete and the SDK's non-daemon transport threads alive.
        return super.closeGracefully().doFinally(signal -> submissions.shutdown());
    }
}
