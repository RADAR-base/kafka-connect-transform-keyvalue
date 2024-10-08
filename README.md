REMOVE ME

# Introduction

Transforms can be used to change the data before it is written by a Kafka Connector.

## Usage

Each transformation can be configured with a properties file, in case a standalone connector is used or with a JSON request if a distributed connector is used. The property names and values are identical for both cases.

A standalone properties file would look like this:
```properties
name=Connector1
connector.class=org.apache.kafka.some.Connector
transforms=MyTransformName
transforms.MyTransformName.type=org.radarbase.kafka.connect.transforms.MyTransformType
```

whereas in the distributed case the JSON file looks as follows:

```json
{
  "name": "Connector1",
  "connector.class": "org.apache.kafka.some.Connector",
  "transforms": "MyTransformName",
  "transforms.MyTransformName.type": "org.radarbase.kafka.connect.transforms.MyTransformType"
}
```

That JSON can be used to start a connector:

```bash
curl -s -X POST -H 'Content-Type: application/json' --data @connector.json http://localhost:8083/connectors
```

Change `http://localhost:8083/` the the endpoint of one of your Kafka Connect worker(s).

The JSON can also be used to update an existing connector:

```bash
curl -s -X POST -H 'Content-Type: application/json' --data @connector.json http://localhost:8083/connectors/Connector1/config
```

### Docker build

This repository creates two docker builds, a base connector image [radarbase/kafka-connect-transform-keyvalue](https://hub.docker.com/r/radarbase/kafka-connect-transform-keyvalue) and an extension of that image that is bundled with the Confluent S3 connector [radarbase/kafka-connect-transform-s3](https://hub.docker.com/r/radarbase/kafka-connect-transform-s3).

## Transformations

### MergeKey

The MergeKey transformation copies all fields from the record key into the record value, and adds a timestamp. If this causes duplicate fields names, the value will be picked, in order of preference, from value, key and finally timestamp.

Example configuration
```properties
transforms=mergeKey
transforms.mergeKey.type=org.radarbase.kafka.connect.transforms.MergeKey
```


### CombineKeyValue

The CombineKeyValue transformation creates a new value with a key field and value field, which will contain the original record key and record value.

Example configuration
```properties
transforms=combineKeyValue
transforms.combineKeyValue.type=org.radarbase.kafka.connect.transforms.CombineKeyValue
```


### TimestampConverter

The TimestampConverter transformation converts milliseconds value fields and floating point seconds value fields to logical timestamp fields. The fields that should be converted can be configured with the `fields` configuration.

Example configuration
```properties
transforms=convertTimestamp
transforms.convertTimestamp.type=org.radarbase.kafka.connect.transforms.TimestampConverter
transforms.convertTimestamp.fields=time,timeReceived,timeCompleted,timestamp
```
