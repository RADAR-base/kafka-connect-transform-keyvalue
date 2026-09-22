ARG BASE_IMAGE=confluentinc/cp-kafka-connect-base:7.8.7

FROM --platform=$BUILDPLATFORM gradle:8.14-jdk17 AS builder

COPY gradle/libs.versions.toml /code/gradle/
COPY ./*.gradle /code/
COPY iceberg-plugin/build.gradle /code/iceberg-plugin/
COPY src/main/java /code/src/main/java
WORKDIR /code
# `jar` builds this repository's transforms; the second task assembles the Iceberg sink plugin.
RUN gradle jar :iceberg-plugin:assembleConnectPlugin --no-watch-fs

FROM ${BASE_IMAGE}

ENV WAIT_FOR_KAFKA="1"

# Plugin 1: the Iceberg sink, with every jar it needs.
COPY --from=builder /code/iceberg-plugin/build/plugin/iceberg-sink/ /usr/share/"${COMPONENT}"/plugins/iceberg-sink/
# Plugin 2: this repository's transforms (MergeKey, TimestampConverter), from the same build.
COPY --from=builder /code/build/libs/kafka-connect-transform-keyvalue*.jar /usr/share/"${COMPONENT}"/plugins/radar-transforms/

COPY ./src/main/docker/launch /etc/confluent/docker/launch
COPY ./src/main/docker/kafka-wait /usr/bin/kafka-wait
