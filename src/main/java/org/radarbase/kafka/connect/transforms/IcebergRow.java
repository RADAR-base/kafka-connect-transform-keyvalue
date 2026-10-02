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

import static org.apache.kafka.connect.transforms.util.Requirements.requireMap;
import static org.apache.kafka.connect.transforms.util.Requirements.requireStruct;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.connect.connector.ConnectRecord;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.errors.DataException;
import org.apache.kafka.connect.transforms.Transformation;
import org.apache.kafka.connect.transforms.util.SimpleConfig;

/**
 * This transforms records by copying every top-level field of the key and of the value into one
 * new value, renamed on the way. Key fields become key_ followed by the field name in snake_case,
 * value fields become value_ followed by the field name in snake_case, and a timestamp field holds
 * the Kafka record timestamp in milliseconds, as MergeKey adds it. The key field projectId becomes
 * key_project_id and the value field timeReceived becomes value_time_received. The record key is
 * kept unchanged. The text between the prefix and the field name is set by the delimiter option,
 * which defaults to an underscore. This replaces running MergeKey after the renaming, so do not
 * run both.
 */
public class IcebergRow<R extends ConnectRecord<R>> implements Transformation<R> {
  private static final String PURPOSE = "renaming and merging key and value fields";
  private static final String TIMESTAMP_FIELD = "timestamp";
  private static final String KEY_PREFIX = "key";
  private static final String VALUE_PREFIX = "value";
  private static final String DELIMITER_CONFIG = "delimiter";
  private static final Pattern LOWER_UPPER = Pattern.compile("([a-z0-9])([A-Z])");
  private static final Pattern ACRONYM_WORD = Pattern.compile("([A-Z]+)([A-Z][a-z])");
  private static final ConfigDef CONFIG_DEF = new ConfigDef()
      .define(DELIMITER_CONFIG,
          ConfigDef.Type.STRING,
          "_",
          new ConfigDef.NonEmptyString(),
          ConfigDef.Importance.LOW,
          "Text between the key or value prefix and the field name. The default _ gives"
              + " key_project_id. Use only letters, digits and underscores if the names are read"
              + " by SQL engines such as Trino, which read a dot as a nested field.");

  private final Map<String, String> snakeCaseNames = new ConcurrentHashMap<>();
  private String delimiter = "_";

  @Override
  public R apply(R r) {
    if (r.valueSchema() == null) {
      Map<String, Object> newValue = new HashMap<>();
      newValue.put(TIMESTAMP_FIELD, r.timestamp());
      requireMap(r.key(), PURPOSE).forEach((name, fieldValue) ->
          newValue.put(newName(KEY_PREFIX, name), fieldValue));
      requireMap(r.value(), PURPOSE).forEach((name, fieldValue) ->
          newValue.put(newName(VALUE_PREFIX, name), fieldValue));
      return r.newRecord(r.topic(), r.kafkaPartition(), r.keySchema(), r.key(), null, newValue,
          r.timestamp());
    }
    Schema keySchema = requireStructSchema(r.keySchema());
    Schema valueSchema = requireStructSchema(r.valueSchema());
    SchemaBuilder schemaBuilder = SchemaBuilder.struct()
        .name(valueSchema.name())
        .version(valueSchema.version())
        .doc(valueSchema.doc())
        .field(TIMESTAMP_FIELD, Schema.INT64_SCHEMA);
    for (Field field : keySchema.fields()) {
      schemaBuilder.field(newName(KEY_PREFIX, field.name()), field.schema());
    }
    for (Field field : valueSchema.fields()) {
      schemaBuilder.field(newName(VALUE_PREFIX, field.name()), field.schema());
    }
    Schema schema = schemaBuilder.build();

    Struct key = requireStruct(r.key(), PURPOSE);
    Struct value = requireStruct(r.value(), PURPOSE);
    Struct newValue = new Struct(schema);
    newValue.put(TIMESTAMP_FIELD, r.timestamp());
    for (Field field : keySchema.fields()) {
      newValue.put(newName(KEY_PREFIX, field.name()), key.get(field));
    }
    for (Field field : valueSchema.fields()) {
      newValue.put(newName(VALUE_PREFIX, field.name()), value.get(field));
    }
    return r.newRecord(r.topic(), r.kafkaPartition(), r.keySchema(), r.key(), schema, newValue,
        r.timestamp());
  }

  private String newName(String prefix, String name) {
    return prefix + delimiter + snakeCaseNames.computeIfAbsent(name, IcebergRow::snakeCase);
  }

  /** Converts a camelCase name to snake_case, for example heartRate to heart_rate. */
  static String snakeCase(String name) {
    String separated = LOWER_UPPER.matcher(name).replaceAll("$1_$2");
    separated = ACRONYM_WORD.matcher(separated).replaceAll("$1_$2");
    return separated.toLowerCase(Locale.ROOT);
  }

  private static Schema requireStructSchema(Schema schema) {
    if (schema == null) {
      throw new DataException("Records need both a key schema and a value schema.");
    }
    if (schema.type() != Schema.Type.STRUCT) {
      throw new DataException(
          "Only struct keys and values can be renamed, but found " + schema.type() + ".");
    }
    return schema;
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
    delimiter = simpleConfig.getString(DELIMITER_CONFIG);
  }
}
