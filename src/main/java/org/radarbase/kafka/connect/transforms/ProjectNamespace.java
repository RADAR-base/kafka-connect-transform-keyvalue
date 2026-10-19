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

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.connect.connector.ConnectRecord;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.errors.DataException;
import org.apache.kafka.connect.transforms.Transformation;

/**
 * This transforms records by putting the project in front of the topic name, so a sink that names
 * its tables after the topic writes one namespace per project. The project is the projectId field
 * of the record key, in lowercase, with every character other than a to z, 0 to 9 and underscore
 * replaced by an underscore. A record of project STAGING_PROJECT on topic android_phone_battery_level
 * gets the topic staging_project.android_phone_battery_level. The key and value are kept unchanged.
 * Run it before any transform that changes the key, because it reads projectId from the key.
 */
public class ProjectNamespace<R extends ConnectRecord<R>> implements Transformation<R> {
  private static final String PROJECT_FIELD = "projectId";
  private static final ConfigDef CONFIG_DEF = new ConfigDef();

  private final Map<String, String> namespaces = new ConcurrentHashMap<>();

  @Override
  public R apply(R r) {
    String namespace = namespaces.computeIfAbsent(project(r.key()), ProjectNamespace::toNamespace);
    return r.newRecord(namespace + "." + r.topic(), r.kafkaPartition(), r.keySchema(), r.key(),
        r.valueSchema(), r.value(), r.timestamp());
  }

  private static String project(Object key) {
    Object project;
    if (key instanceof Struct) {
      Struct struct = (Struct) key;
      if (struct.schema().field(PROJECT_FIELD) == null) {
        throw new DataException("The record key has no " + PROJECT_FIELD + " field.");
      }
      project = struct.get(PROJECT_FIELD);
    } else if (key instanceof Map) {
      project = ((Map<?, ?>) key).get(PROJECT_FIELD);
    } else {
      throw new DataException("Only struct and map keys hold a " + PROJECT_FIELD + ", but found "
          + (key == null ? "no key" : key.getClass().getSimpleName()) + ".");
    }
    if (project == null || project.toString().isEmpty()) {
      throw new DataException("The record key has no " + PROJECT_FIELD + " value.");
    }
    return project.toString();
  }

  private static String toNamespace(String project) {
    return project.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
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
