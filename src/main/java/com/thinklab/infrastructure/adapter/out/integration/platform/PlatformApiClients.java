package com.thinklab.infrastructure.adapter.out.integration.platform;

import com.thinklab.infrastructure.adapter.out.integration.platform.PlatformItemsAdapter.CommentApiRequest;
import com.thinklab.infrastructure.adapter.out.integration.platform.PlatformItemsAdapter.IncidentResolveApiRequest;
import com.thinklab.infrastructure.adapter.out.integration.platform.PlatformItemsAdapter.ItemApiResponse;
import com.thinklab.infrastructure.adapter.out.integration.platform.PlatformItemsAdapter.NotesApiRequest;
import com.thinklab.infrastructure.adapter.out.integration.platform.PlatformItemsAdapter.ProblemCommentApiRequest;
import com.thinklab.infrastructure.adapter.out.integration.platform.PlatformItemsAdapter.ProblemResolveApiRequest;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Put;
import io.micronaut.http.client.annotation.Client;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Declarative clients of the three services the integration reads from and acts on (ADR-031). Each call carries the tenant and the service identity. */
@Client(id = "incident-service", path = "/it-incident-management/v1")
interface IncidentApiClient {
    @Get("/{id}/retrieve")
    Mono<ItemApiResponse> retrieve(@PathVariable UUID id, @Header("X-Tenant-Id") String tenant, @Header("X-Executor") String executor);

    @Put(value = "/{id}/control/acknowledge", consumes = MediaType.ALL)
    Mono<Void> acknowledge(@PathVariable UUID id, @Header("X-Tenant-Id") String tenant, @Header("X-Executor") String executor);

    @Put(value = "/{id}/control/start", consumes = MediaType.ALL)
    Mono<Void> start(@PathVariable UUID id, @Header("X-Tenant-Id") String tenant, @Header("X-Executor") String executor);

    @Put(value = "/{id}/control/resume", consumes = MediaType.ALL)
    Mono<Void> resume(@PathVariable UUID id, @Header("X-Tenant-Id") String tenant, @Header("X-Executor") String executor);

    @Put(value = "/{id}/control/close", consumes = MediaType.ALL)
    Mono<Void> close(@PathVariable UUID id, @Header("X-Tenant-Id") String tenant, @Header("X-Executor") String executor);

    @Put(value = "/{id}/control/cancel", consumes = MediaType.ALL)
    Mono<Void> cancel(@PathVariable UUID id, @Header("X-Tenant-Id") String tenant, @Header("X-Executor") String executor);

    @Put("/{id}/control/resolve")
    Mono<Void> resolve(@PathVariable UUID id, @Header("X-Tenant-Id") String tenant, @Header("X-Executor") String executor, @Body IncidentResolveApiRequest request);

    @Post("/{id}/comment/initiate")
    Mono<Void> comment(@PathVariable UUID id, @Header("X-Tenant-Id") String tenant, @Header("X-Executor") String executor, @Body CommentApiRequest request);
}

@Client(id = "service-request-service", path = "/it-service-request/v1")
interface ServiceRequestApiClient {
    @Get("/{id}/retrieve")
    Mono<ItemApiResponse> retrieve(@PathVariable UUID id, @Header("X-Tenant-Id") String tenant, @Header("X-Executor") String executor);

    @Put(value = "/{id}/control/start-fulfilment", consumes = MediaType.ALL)
    Mono<Void> startFulfilment(@PathVariable UUID id, @Header("X-Tenant-Id") String tenant, @Header("X-Executor") String executor);

    @Put(value = "/{id}/control/close", consumes = MediaType.ALL)
    Mono<Void> close(@PathVariable UUID id, @Header("X-Tenant-Id") String tenant, @Header("X-Executor") String executor);

    @Put(value = "/{id}/control/cancel", consumes = MediaType.ALL)
    Mono<Void> cancel(@PathVariable UUID id, @Header("X-Tenant-Id") String tenant, @Header("X-Executor") String executor);

    @Put("/{id}/control/fulfil")
    Mono<Void> fulfil(@PathVariable UUID id, @Header("X-Tenant-Id") String tenant, @Header("X-Executor") String executor, @Body NotesApiRequest request);

    @Post("/{id}/comment/initiate")
    Mono<Void> comment(@PathVariable UUID id, @Header("X-Tenant-Id") String tenant, @Header("X-Executor") String executor, @Body CommentApiRequest request);
}

@Client(id = "problem-service", path = "/it-problem-management/v1")
interface ProblemApiClient {
    @Get("/{id}/retrieve")
    Mono<ItemApiResponse> retrieve(@PathVariable UUID id, @Header("X-Tenant-Id") String tenant, @Header("X-Executor") String executor);

    @Put(value = "/{id}/control/investigate", consumes = MediaType.ALL)
    Mono<Void> investigate(@PathVariable UUID id, @Header("X-Tenant-Id") String tenant, @Header("X-Executor") String executor);

    @Put(value = "/{id}/control/close", consumes = MediaType.ALL)
    Mono<Void> close(@PathVariable UUID id, @Header("X-Tenant-Id") String tenant, @Header("X-Executor") String executor);

    @Put(value = "/{id}/control/cancel", consumes = MediaType.ALL)
    Mono<Void> cancel(@PathVariable UUID id, @Header("X-Tenant-Id") String tenant, @Header("X-Executor") String executor);

    @Put("/{id}/control/resolve")
    Mono<Void> resolve(@PathVariable UUID id, @Header("X-Tenant-Id") String tenant, @Header("X-Executor") String executor, @Body ProblemResolveApiRequest request);

    @Post("/{id}/comment/initiate")
    Mono<Void> comment(@PathVariable UUID id, @Header("X-Tenant-Id") String tenant, @Header("X-Executor") String executor, @Body ProblemCommentApiRequest request);
}
