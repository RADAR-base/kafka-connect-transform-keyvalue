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
 * Extracts {@code rawData.steps} into a record whose value is
 * {@code { samples: array<{ timestamp:int64, value:int32 }> }}.
 * Sample timestamps are microseconds since epoch; values are integer step counts.
 */
public class EmpaticaExtractSteps<R extends ConnectRecord<R>> extends AbstractEmpaticaExtract<R> {
  private static final Schema SAMPLE_SCHEMA = SchemaBuilder.struct()
          .name("org.radarbase.kafka.connect.transforms.EmpaticaStepsSample")
          .field(TIMESTAMP_FIELD, Schema.INT64_SCHEMA)
          .field(VALUE_FIELD, Schema.INT32_SCHEMA)
          .build();

  @Override
  protected String modalityKey() {
    return "steps";
  }

  @Override
  protected Schema sampleSchema() {
    return SAMPLE_SCHEMA;
  }

  @Override
  protected List<Struct> extractSamples(Struct m) {
    long t0 = m.getInt64("timestampStart");
    double fs = ((Number) m.get("samplingFrequency")).doubleValue();
    List<?> vs = m.getArray("values");
    List<Struct> out = new ArrayList<>(vs.size());
    for (int i = 0; i < vs.size(); i++) {
      out.add(new Struct(SAMPLE_SCHEMA)
              .put(TIMESTAMP_FIELD, sampleTimestamp(t0, fs, i))
              .put(VALUE_FIELD, ((Number) vs.get(i)).intValue()));
    }
    return out;
  }

  @Override
  protected List<Map<String, Object>> extractSamplesSchemaless(Map<String, Object> m) {
    long t0 = ((Number) m.get("timestampStart")).longValue();
    double fs = ((Number) m.get("samplingFrequency")).doubleValue();
    List<?> vs = (List<?>) m.get("values");
    List<Map<String, Object>> out = new ArrayList<>(vs.size());
    for (int i = 0; i < vs.size(); i++) {
      Map<String, Object> s = new LinkedHashMap<>(2);
      s.put(TIMESTAMP_FIELD, sampleTimestamp(t0, fs, i));
      s.put(VALUE_FIELD, ((Number) vs.get(i)).intValue());
      out.add(s);
    }
    return out;
  }
}
