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
 * Extracts {@code rawData.systolicPeaks.peaksTimeNanos} into a record whose value is
 * {@code { samples: array<{ timestamp:int64 }> }}. Source nanosecond timestamps are
 * converted to microseconds since epoch (sub-microsecond precision is dropped).
 */
public class EmpaticaExtractSystolicPeaks<R extends ConnectRecord<R>> extends AbstractEmpaticaExtract<R> {
  private static final String PEAKS_TIME_NANOS_FIELD = "peaksTimeNanos";

  private static final Schema SAMPLE_SCHEMA = SchemaBuilder.struct()
          .name("org.radarbase.kafka.connect.transforms.EmpaticaSystolicPeakSample")
          .field(TIMESTAMP_FIELD, Schema.INT64_SCHEMA)
          .build();

  @Override
  protected String modalityKey() {
    return "systolicPeaks";
  }

  @Override
  protected Schema sampleSchema() {
    return SAMPLE_SCHEMA;
  }

  @Override
  protected boolean hasSamplingFrequency() {
    return false;
  }

  @Override
  protected long batchStartTimestamp(Struct modality) {
    List<?> ts = modality.getArray(PEAKS_TIME_NANOS_FIELD);
    return ts.isEmpty() ? 0L : ((Number) ts.get(0)).longValue() / 1000L;
  }

  @Override
  protected long batchStartTimestampSchemaless(Map<String, Object> modality) {
    List<?> ts = (List<?>) modality.get(PEAKS_TIME_NANOS_FIELD);
    return ts.isEmpty() ? 0L : ((Number) ts.get(0)).longValue() / 1000L;
  }

  @Override
  protected List<Struct> extractSamples(Struct m) {
    List<?> ts = m.getArray(PEAKS_TIME_NANOS_FIELD);
    List<Struct> out = new ArrayList<>(ts.size());
    for (Object t : ts) {
      out.add(new Struct(SAMPLE_SCHEMA)
              .put(TIMESTAMP_FIELD, ((Number) t).longValue() / 1000L));
    }
    return out;
  }

  @Override
  protected List<Map<String, Object>> extractSamplesSchemaless(Map<String, Object> m) {
    List<?> ts = (List<?>) m.get(PEAKS_TIME_NANOS_FIELD);
    List<Map<String, Object>> out = new ArrayList<>(ts.size());
    for (Object t : ts) {
      Map<String, Object> s = new LinkedHashMap<>(1);
      s.put(TIMESTAMP_FIELD, ((Number) t).longValue() / 1000L);
      out.add(s);
    }
    return out;
  }
}
