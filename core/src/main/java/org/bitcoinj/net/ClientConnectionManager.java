/*
 * Copyright 2013 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.bitcoinj.net;

import com.google.common.util.concurrent.Service;

import java.net.SocketAddress;
import java.util.concurrent.CompletableFuture;

/**
 * A generic interface for an object which keeps track of a set of open client connections, creates new ones and
 * ensures they are serviced properly.
 * <p>
 * When the service is stopped via {@link #stop()}, all connections will be closed and the appropriate
 * {@code connectionClosed()} calls must be made.
 * <p>
 * <b>Deprecation warning:</b> this class currently extends <b>Guava</b> {@link Service} but this will be removed
 * in the next release. {@link #start()} amd {@link #stop()} have been provided to replace existing uses of {@link Service}
 * methods and in a future release {@link ClientConnectionManager} instances will not be Guava services.
 */
public interface ClientConnectionManager extends Service {
    /**
     * Creates a new connection to the given address, with the given connection used to handle incoming data. Any errors
     * that occur during connection will be returned in the given future, including errors that can occur immediately.
     */
    CompletableFuture<SocketAddress> openConnection(SocketAddress serverAddress, StreamConnection connection);

    /** Gets the number of connected peers */
    int getConnectedClientCount();

    /** Closes n peer connections */
    void closeConnections(int n);

    /**
     * Start the service asynchronously.
     * @return a future that will complete when the service is started
     */
    default CompletableFuture<Void> start() {
        startAsync();
        return CompletableFuture.runAsync(this::awaitRunning);
    }

    /**
     * Stop the service asynchronously and close all connections.
     * @return a future that will complete when the service is stopped
     */
    default CompletableFuture<Void> stop() {
        startAsync();
        return CompletableFuture.runAsync(this::awaitTerminated);
    }
}
