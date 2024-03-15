ARG BASE_IMAGE=confluentinc/cp-kafka-connect-base:7.6.0

FROM --platform=$BUILDPLATFORM gradle:8.3-jdk17 as builder

COPY ./*.gradle /code/
COPY src/main/java /code/src/main/java
WORKDIR /code
RUN gradle jar --no-watch-fs

FROM ${BASE_IMAGE}

ENV WAIT_FOR_KAFKA="1"

COPY --from=builder /code/build/libs/kafka-connect-transform-keyvalue*.jar /usr/share/"${COMPONENT}"/plugins/
COPY ./src/main/docker/launch /etc/confluent/docker/launch
COPY ./src/main/docker/kafka-wait /usr/bin/kafka-wait
