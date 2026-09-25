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

/**
 * This transforms records by renaming every top-level field of the key to key_ followed by the
 * field name in snake_case, and every top-level field of the value to value_ followed by the field
 * name in snake_case. The key field projectId becomes key_project_id and the value field
 * timeReceived becomes value_time_received.
 */
public class RenameKeyValue<R extends ConnectRecord<R>> implements Transformation<R> {
  private static final String PURPOSE = "renaming key and value fields";
  private static final String KEY_PREFIX = "key_";
  private static final String VALUE_PREFIX = "value_";
  private static final Pattern LOWER_UPPER = Pattern.compile("([a-z0-9])([A-Z])");
  private static final Pattern ACRONYM_WORD = Pattern.compile("([A-Z]+)([A-Z][a-z])");
  private static final ConfigDef CONFIG_DEF = new ConfigDef();

  private final Map<String, String> snakeCaseNames = new ConcurrentHashMap<>();

  @Override
  public R apply(R r) {
    Schema keySchema = renameSchema(r.keySchema(), KEY_PREFIX);
    Object key = renameValue(r.keySchema(), keySchema, r.key(), KEY_PREFIX);
    Schema valueSchema = renameSchema(r.valueSchema(), VALUE_PREFIX);
    Object value = renameValue(r.valueSchema(), valueSchema, r.value(), VALUE_PREFIX);
    return r.newRecord(r.topic(), r.kafkaPartition(), keySchema, key, valueSchema, value,
        r.timestamp());
  }

  private Schema renameSchema(Schema schema, String prefix) {
    if (schema == null) {
      return null;
    }
    SchemaBuilder schemaBuilder = SchemaBuilder.struct()
        .name(schema.name())
        .version(schema.version())
        .doc(schema.doc());
    if (schema.isOptional()) {
      schemaBuilder.optional();
    }
    for (Field field : requireStructSchema(schema).fields()) {
      schemaBuilder.field(newName(prefix, field.name()), field.schema());
    }
    return schemaBuilder.build();
  }

  private Object renameValue(Schema schema, Schema newSchema, Object value, String prefix) {
    if (value == null) {
      return null;
    } else if (schema == null) {
      Map<String, Object> newMap = new HashMap<>();
      requireMap(value, PURPOSE).forEach((name, fieldValue) ->
          newMap.put(newName(prefix, name), fieldValue));
      return newMap;
    } else {
      Struct struct = requireStruct(value, PURPOSE);
      Struct newStruct = new Struct(newSchema);
      for (Field field : schema.fields()) {
        newStruct.put(newName(prefix, field.name()), struct.get(field));
      }
      return newStruct;
    }
  }

  private String newName(String prefix, String name) {
    return prefix + snakeCaseNames.computeIfAbsent(name, RenameKeyValue::snakeCase);
  }

  /** Converts a camelCase name to snake_case, for example heartRate to heart_rate. */
  static String snakeCase(String name) {
    String separated = LOWER_UPPER.matcher(name).replaceAll("$1_$2");
    separated = ACRONYM_WORD.matcher(separated).replaceAll("$1_$2");
    return separated.toLowerCase(Locale.ROOT);
  }

  private static Schema requireStructSchema(Schema schema) {
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
  }
}
