/*
 * Copyright 2018-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.springframework.cloud.gateway.rsocket.server;

import java.util.Map;
import java.util.logging.Level;

import io.rsocket.AbstractRSocket;
import io.rsocket.Payload;
import io.rsocket.RSocket;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.cloud.gateway.rsocket.registry.Registry;
import org.springframework.cloud.gateway.rsocket.route.Routes;
import org.springframework.cloud.gateway.rsocket.support.Metadata;
import org.springframework.util.CollectionUtils;
import reactor.util.context.Context;

import static org.springframework.cloud.gateway.rsocket.server.GatewayExchange.Type.FIRE_AND_FORGET;
import static org.springframework.cloud.gateway.rsocket.server.GatewayExchange.Type.REQUEST_CHANNEL;
import static org.springframework.cloud.gateway.rsocket.server.GatewayExchange.Type.REQUEST_RESPONSE;
import static org.springframework.cloud.gateway.rsocket.server.GatewayExchange.Type.REQUEST_STREAM;
import static org.springframework.cloud.gateway.rsocket.server.GatewayFilterChain.executeFilterChain;

/**
 * Acts as a proxy to other registered sockets. Looks up target RSocket
 * via Registry. Creates GatewayExchange and executes a GatewayFilterChain.
 */
public class GatewayRSocket extends AbstractRSocket {

	private static final Log log = LogFactory.getLog(GatewayRSocket.class);

	private final Registry registry;
	private final Routes routes;

	public GatewayRSocket(Registry registry, Routes routes) {
		this.registry = registry;
		this.routes = routes;
	}

	protected Registry getRegistry() {
		return registry;
	}

	protected Routes getRoutes() {
		return routes;
	}

	@Override
	public Mono<Void> fireAndForget(Payload payload) {
		return findRSocket(FIRE_AND_FORGET, payload)
				.flatMap(rSocket -> rSocket.fireAndForget(payload));
	}

	@Override
	public Flux<Payload> requestChannel(Publisher<Payload> payloads) {
		return Flux.from(payloads)
				.switchOnFirst((signal, payloadFlux) -> {
					if (!signal.hasValue()) {
						return payloadFlux;
					}

					return findRSocket(REQUEST_CHANNEL, signal.get())
							.flatMapMany(rSocket -> rSocket.requestChannel(payloadFlux));
				});
	}

	@Override
	public Mono<Payload> requestResponse(Payload payload) {
		return findRSocket(REQUEST_RESPONSE, payload)
				.flatMap(rSocket -> rSocket.requestResponse(payload));
	}

	@Override
	public Flux<Payload> requestStream(Payload payload) {
		return findRSocket(REQUEST_STREAM, payload)
				.flatMapMany(rSocket -> rSocket.requestStream(payload));
	}

	private Mono<RSocket> findRSocket(GatewayExchange.Type type, Payload payload) {
		Map<String, String> metadata = getRoutingMetadata(payload);
		GatewayExchange exchange = new GatewayExchange(type, metadata);

		return findRSocket(exchange)
				// if a route can't be found or registered RSocket, create pending
				.switchIfEmpty(createPendingRSocket(exchange));
	}

	private Mono<RSocket> createPendingRSocket(GatewayExchange exchange) {
		if (log.isDebugEnabled()) {
			log.debug("creating pending RSocket for " + exchange.getRoutingMetadata());
		}
		PendingRequestRSocket pending = constructPendingRSocket(exchange);
		this.registry.addListener(pending); //TODO: deal with removing?
		return Mono.just(pending);
	}

	/* for testing */ PendingRequestRSocket constructPendingRSocket(GatewayExchange exchange) {
		return new PendingRequestRSocket(routes, exchange);
	}

	private Mono<RSocket> findRSocket(GatewayExchange exchange) {
		return this.routes.findRoute(exchange)
				.log("find route", Level.FINE)
				// TODO: see if I can store the route in the pending rsocket
				.flatMap(route -> executeFilterChain(route.getFilters(), exchange)
						.map(success -> {
							RSocket rsocket = registry.getRegistered(exchange.getRoutingMetadata());

							if (rsocket == null) {
								log.debug("Unable to find destination RSocket for " + exchange.getRoutingMetadata());
							}
							return rsocket;
						})
						.subscriberContext(Context.of("route", route)));

		// TODO: deal with connecting to cluster?
	}

	private Map<String, String> getRoutingMetadata(Payload payload) {
		if (payload == null || !payload.hasMetadata()) { // and metadata is routing
			return null;
		}

		// TODO: deal with composite metadata

		Map<String, String> properties = Metadata.decodeProperties(payload.sliceMetadata());

		if (CollectionUtils.isEmpty(properties)) {
			return null;
		}

		log.debug("found routing metadata " + properties);
		return properties;
	}


}

