/**
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.radarbase.kafka.connect.transforms;

import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.connect.connector.ConnectRecord;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.data.Timestamp;
import org.apache.kafka.connect.errors.DataException;
import org.apache.kafka.connect.transforms.Transformation;
import org.apache.kafka.connect.transforms.util.SimpleConfig;

import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.apache.kafka.connect.transforms.util.Requirements.requireMap;
import static org.apache.kafka.connect.transforms.util.Requirements.requireStruct;

/**
 * This transforms records converting a timestamp value to an actual timestamp field. An optional
 * field stays optional, and a null value stays null.
 */
public class TimestampConverter<R extends ConnectRecord<R>> implements Transformation<R> {
  private static final String PURPOSE = "convert time fields to actual dates";
  private static final String FIELDS_CONFIG = "fields";
  private static final Schema OPTIONAL_TIMESTAMP_SCHEMA = Timestamp.builder().optional().build();

  private static final ConfigDef CONFIG_DEF = new ConfigDef()
          .define(FIELDS_CONFIG,
                  ConfigDef.Type.LIST,
                  "time,timeReceived,timeCompleted,timestamp",
                  ConfigDef.Importance.HIGH,
                  "Comma-separated list of fields that should be converted.");
  private Set<String> timeFields = new HashSet<>();

  @Override
  public R apply(R record) {
    if (timeFields.isEmpty()) {
      return record;
    }

    if (record.valueSchema() == null) {
      return applySchemaless(record);
    } else {
      return applyWithSchema(record);
    }
  }

  private R applyWithSchema(R r) {
    Schema oldSchema = r.valueSchema();
    boolean shouldConvert = false;
    SchemaBuilder schemaBuilder = SchemaBuilder.struct().name(r.valueSchema().name());
    for (Field field : oldSchema.fields()) {
      String fieldName = field.name();
      if (shouldConvertTimestamp(field)) {
        schemaBuilder.field(fieldName,
            field.schema().isOptional() ? OPTIONAL_TIMESTAMP_SCHEMA : Timestamp.SCHEMA);
        shouldConvert = true;
      } else {
        schemaBuilder.field(fieldName, field.schema());
      }
    }
    if (!shouldConvert) {
      return r;
    }
    Schema schema = schemaBuilder.build();

    final Struct recordValue = requireStruct(r.value(), PURPOSE);
    Struct newData = new Struct(schema);
    for (Field field : schema.fields()) {
      Object fieldVal;
      if (Timestamp.LOGICAL_NAME.equals(field.schema().name())
          && !Timestamp.LOGICAL_NAME.equals(oldSchema.field(field.name()).schema().name())) {
        fieldVal = convertTimestamp(recordValue.get(field));
      } else {
        fieldVal = recordValue.get(field);
      }
      newData.put(field, fieldVal);
    }

    return r.newRecord(r.topic(), r.kafkaPartition(), r.keySchema(), r.key(), schema, newData, r.timestamp());
  }

  private boolean shouldConvertTimestamp(Field field) {
    return timeFields.contains(field.name())
            && !Timestamp.LOGICAL_NAME.equals(field.schema().name())
            && (field.schema().type() == Schema.Type.FLOAT64
            || field.schema().type() == Schema.Type.INT64);
  }

  private R applySchemaless(R r) {
    Map<String, Object> value = requireMap(r.value(), PURPOSE);
    Map<String, Object> newData = new HashMap<>();
    for (Map.Entry<String, Object> entry : value.entrySet()) {
      String fieldName = entry.getKey();
      Object fieldVal;
      if (timeFields.contains(fieldName)) {
        fieldVal = convertTimestamp(entry.getValue());
      } else {
        fieldVal = entry.getValue();
      }
      newData.put(fieldName, fieldVal);
    }

    return r.newRecord(r.topic(), r.kafkaPartition(), r.keySchema(), r.key(), null, newData, r.timestamp());
  }

  private Object convertTimestamp(Object time){
    if (time == null) {
      return null;
    } else if (time instanceof Double) {
      // with double assume it is seconds
      return new Date(Math.round((Double) time * 1000));
    } else if (time instanceof Long) {
      // with long assume it is milliseconds
      return new Date((Long)time);
    } else {
      throw new DataException("Expected timestamp to be a Long or Double, but found "
              + time.getClass());
    }
  }

  @Override
  public ConfigDef config() {
    return CONFIG_DEF;
  }

  @Override
  public void close() {
  }

  @Override
  public void configure(Map<String, ?> map) {
    SimpleConfig simpleConfig = new SimpleConfig(CONFIG_DEF, map);
    timeFields = new HashSet<>(simpleConfig.getList(FIELDS_CONFIG));
  }
}
