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

import org.apache.kafka.connect.connector.ConnectRecord;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Extracts {@code rawData.gyroscope} into a record whose value is
 * {@code { samples: array<{ timestamp:int64, x:int32, y:int32, z:int32 }> }}.
 * Sample timestamps are microseconds since epoch.
 */
public class EmpaticaExtractGyroscope<R extends ConnectRecord<R>> extends AbstractEmpaticaExtract<R> {
  private static final Schema SAMPLE_SCHEMA = SchemaBuilder.struct()
          .name("org.radarbase.kafka.connect.transforms.EmpaticaGyroscopeSample")
          .field(TIMESTAMP_FIELD, Schema.INT64_SCHEMA)
          .field("x", Schema.INT32_SCHEMA)
          .field("y", Schema.INT32_SCHEMA)
          .field("z", Schema.INT32_SCHEMA)
          .build();

  @Override
  protected String modalityKey() {
    return "gyroscope";
  }

  @Override
  protected Schema sampleSchema() {
    return SAMPLE_SCHEMA;
  }

  @Override
  protected boolean hasImuParams() {
    return true;
  }

  @Override
  protected List<Struct> extractSamples(Struct m) {
    long t0 = m.getInt64("timestampStart");
    double fs = ((Number) m.get("samplingFrequency")).doubleValue();
    List<?> xs = m.getArray("x");
    List<?> ys = m.getArray("y");
    List<?> zs = m.getArray("z");
    int n = Math.min(xs.size(), Math.min(ys.size(), zs.size()));
    List<Struct> out = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      out.add(new Struct(SAMPLE_SCHEMA)
              .put(TIMESTAMP_FIELD, sampleTimestamp(t0, fs, i))
              .put("x", ((Number) xs.get(i)).intValue())
              .put("y", ((Number) ys.get(i)).intValue())
              .put("z", ((Number) zs.get(i)).intValue()));
    }
    return out;
  }

  @Override
  protected List<Map<String, Object>> extractSamplesSchemaless(Map<String, Object> m) {
    long t0 = ((Number) m.get("timestampStart")).longValue();
    double fs = ((Number) m.get("samplingFrequency")).doubleValue();
    List<?> xs = (List<?>) m.get("x");
    List<?> ys = (List<?>) m.get("y");
    List<?> zs = (List<?>) m.get("z");
    int n = Math.min(xs.size(), Math.min(ys.size(), zs.size()));
    List<Map<String, Object>> out = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      Map<String, Object> s = new LinkedHashMap<>(4);
      s.put(TIMESTAMP_FIELD, sampleTimestamp(t0, fs, i));
      s.put("x", ((Number) xs.get(i)).intValue());
      s.put("y", ((Number) ys.get(i)).intValue());
      s.put("z", ((Number) zs.get(i)).intValue());
      out.add(s);
    }
    return out;
  }
}
