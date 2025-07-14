ARG BASE_IMAGE=quay.io/strimzi/kafka:0.46.0-kafka-3.9.0

FROM --platform=$BUILDPLATFORM gradle:8.9-jdk17 AS builder

COPY ./*.gradle /code/
COPY src/main/java /code/src/main/java
WORKDIR /code
RUN gradle jar --no-watch-fs

FROM ${BASE_IMAGE}

ENV CONNECT_PLUGIN_PATH=/opt/kafka/plugins

COPY --from=builder /code/build/libs/kafka-connect-transform-keyvalue*.jar ${CONNECT_PLUGIN_PATH}/kafka-connect-transform-keyvalue/

USER 1001

COPY --chown=1001:1001 ./docker/ensure /opt/kafka/ensure
COPY --chown=1001:1001 ./docker/kafka_connect_run.sh /opt/kafka/kafka_connect_run.sh
RUN chmod +x /opt/kafka/ensure /opt/kafka/kafka_connect_run.sh
