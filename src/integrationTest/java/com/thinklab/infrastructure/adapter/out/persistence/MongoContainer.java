package com.thinklab.infrastructure.adapter.out.persistence;

import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One MongoDB for the whole integration suite, started on first use (Testcontainers' Ryuk sidecar removes
 * it when the JVM exits). Single-node replica set, the topology the local stack runs.
 */
final class MongoContainer {

    static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse("mongo:7.0")).withReplicaSet();

    static {
        MONGO.start();
    }

    private MongoContainer() {
    }

    static String uri(String database) {
        return MONGO.getReplicaSetUrl(database);
    }
}
