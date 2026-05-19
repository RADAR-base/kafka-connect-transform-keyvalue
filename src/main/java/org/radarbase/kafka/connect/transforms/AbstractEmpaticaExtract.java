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
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.transforms.Transformation;
import org.apache.kafka.connect.transforms.util.SimpleConfig;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.apache.kafka.connect.transforms.util.Requirements.requireMap;
import static org.apache.kafka.connect.transforms.util.Requirements.requireStruct;

/**
 * Shared base for SMTs that extract one modality of an Empatica EmbracePlus payload
 * (under {@code rawData.<modalityKey>}) into a record whose value carries the per-sample
 * array plus optional batch-level metadata ({@code samplingFrequency}, {@code imuParams}),
 * and whose key carries the device/enrollment metadata lifted from the input value's root
 * ({@code timezone, deviceSn, deviceModel, enrollment{...}, timestampStart}).
 *
 * <p>Subclasses provide the modality key, the per-sample schema and the two extraction
 * methods (schema/schemaless). Batch-level metadata is opt-in via {@link #hasSamplingFrequency()}
 * (default {@code true}) and {@link #hasImuParams()} (default {@code false}) — overrides
 * flip them off for event-only modalities (tags, systolicPeaks) or on for IMU modalities
 * (accelerometer, gyroscope). Free-form additional metadata still goes through the
 * {@link #addValueMetadataFields}/{@link #addValueMetadata}/{@link #addValueMetadataSchemaless} hooks.
 */
abstract class AbstractEmpaticaExtract<R extends ConnectRecord<R>> implements Transformation<R> {
  protected static final String TIMESTAMP_FIELD = "timestamp";
  protected static final String VALUE_FIELD = "value";
  protected static final String SAMPLES_FIELD = "samples";
  protected static final String TIMESTAMP_START_FIELD = "timestampStart";
  protected static final String SAMPLING_FREQUENCY_FIELD = "samplingFrequency";
  protected static final String IMU_PARAMS_FIELD = "imuParams";

  private static final String PHYSICAL_MIN_FIELD = "physicalMin";
  private static final String PHYSICAL_MAX_FIELD = "physicalMax";
  private static final String DIGITAL_MIN_FIELD = "digitalMin";
  private static final String DIGITAL_MAX_FIELD = "digitalMax";

  private static final String RAW_DATA_FIELD = "rawData";
  private static final String TIMEZONE_FIELD = "timezone";
  private static final String DEVICE_SN_FIELD = "deviceSn";
  private static final String DEVICE_MODEL_FIELD = "deviceModel";
  private static final String ENROLLMENT_FIELD = "enrollment";
  private static final String PARTICIPANT_ID_FIELD = "participantID";
  private static final String SITE_ID_FIELD = "siteID";
  private static final String STUDY_ID_FIELD = "studyID";
  private static final String ORGANIZATION_ID_FIELD = "organizationID";

  private static final String TOPIC_CONFIG = "topic";

  protected static final ConfigDef CONFIG_DEF = new ConfigDef()
          .define(TOPIC_CONFIG,
                  ConfigDef.Type.STRING,
                  "",
                  ConfigDef.Importance.HIGH,
                  "Destination topic for the extracted modality records. "
                          + "If empty, the input record's topic is preserved.");

  private static final Schema ENROLLMENT_SCHEMA = SchemaBuilder.struct()
          .name("org.radarbase.kafka.connect.transforms.EmpaticaEnrollment")
          .field(PARTICIPANT_ID_FIELD, Schema.STRING_SCHEMA)
          .field(SITE_ID_FIELD, Schema.STRING_SCHEMA)
          .field(STUDY_ID_FIELD, Schema.STRING_SCHEMA)
          .field(ORGANIZATION_ID_FIELD, Schema.STRING_SCHEMA)
          .build();

  private static final Schema KEY_SCHEMA = SchemaBuilder.struct()
          .name("org.radarbase.kafka.connect.transforms.EmpaticaKey")
          .field(TIMEZONE_FIELD, Schema.INT32_SCHEMA)
          .field(DEVICE_SN_FIELD, Schema.STRING_SCHEMA)
          .field(DEVICE_MODEL_FIELD, Schema.STRING_SCHEMA)
          .field(ENROLLMENT_FIELD, ENROLLMENT_SCHEMA)
          .field(TIMESTAMP_START_FIELD, Schema.INT64_SCHEMA)
          .build();

  private static final Schema IMU_PARAMS_SCHEMA = SchemaBuilder.struct()
          .name("org.radarbase.kafka.connect.transforms.EmpaticaImuParams")
          .field(PHYSICAL_MIN_FIELD, Schema.INT32_SCHEMA)
          .field(PHYSICAL_MAX_FIELD, Schema.INT32_SCHEMA)
          .field(DIGITAL_MIN_FIELD, Schema.INT32_SCHEMA)
          .field(DIGITAL_MAX_FIELD, Schema.INT32_SCHEMA)
          .build();

  private String topic = "";
  private Schema valueSchema;

  protected abstract String modalityKey();

  protected abstract Schema sampleSchema();

  protected abstract List<Struct> extractSamples(Struct modality);

  protected abstract List<Map<String, Object>> extractSamplesSchemaless(Map<String, Object> modality);

  protected boolean hasSamplingFrequency() {
    return true;
  }

  protected boolean hasImuParams() {
    return false;
  }

  protected long batchStartTimestamp(Struct modality) {
    return modality.getInt64(TIMESTAMP_START_FIELD);
  }

  protected long batchStartTimestampSchemaless(Map<String, Object> modality) {
    return ((Number) modality.get(TIMESTAMP_START_FIELD)).longValue();
  }

  @Override
  public final R apply(R r) {
    return r.valueSchema() == null ? applySchemaless(r) : applyWithSchema(r);
  }

  private R applyWithSchema(R r) {
    Struct root = requireStruct(r.value(), purpose());
    Struct modality = root.getStruct(RAW_DATA_FIELD).getStruct(modalityKey());
    Schema schema = valueSchema();
    Struct newValue = new Struct(schema).put(SAMPLES_FIELD, extractSamples(modality));
    if (hasSamplingFrequency()) {
      newValue.put(SAMPLING_FREQUENCY_FIELD,
              ((Number) modality.get(SAMPLING_FREQUENCY_FIELD)).doubleValue());
    }
    if (hasImuParams()) {
      newValue.put(IMU_PARAMS_FIELD, buildImuParams(modality.getStruct(IMU_PARAMS_FIELD)));
    }
    addValueMetadata(newValue, modality);
    Struct newKey = buildKey(root, modality);
    return r.newRecord(destinationTopic(r), r.kafkaPartition(),
            KEY_SCHEMA, newKey, schema, newValue, r.timestamp());
  }

  @SuppressWarnings("unchecked")
  private R applySchemaless(R r) {
    Map<String, Object> root = requireMap(r.value(), purpose());
    Map<String, Object> rawData = (Map<String, Object>) root.get(RAW_DATA_FIELD);
    Map<String, Object> modality = (Map<String, Object>) rawData.get(modalityKey());
    Map<String, Object> newValue = new HashMap<>();
    newValue.put(SAMPLES_FIELD, extractSamplesSchemaless(modality));
    if (hasSamplingFrequency()) {
      newValue.put(SAMPLING_FREQUENCY_FIELD,
              ((Number) modality.get(SAMPLING_FREQUENCY_FIELD)).doubleValue());
    }
    if (hasImuParams()) {
      newValue.put(IMU_PARAMS_FIELD,
              buildImuParamsSchemaless((Map<String, Object>) modality.get(IMU_PARAMS_FIELD)));
    }
    addValueMetadataSchemaless(newValue, modality);
    Map<String, Object> newKey = buildKeySchemaless(root, modality);
    return r.newRecord(destinationTopic(r), r.kafkaPartition(),
            null, newKey, null, newValue, r.timestamp());
  }

  private static Struct buildImuParams(Struct imu) {
    return new Struct(IMU_PARAMS_SCHEMA)
            .put(PHYSICAL_MIN_FIELD, imu.getInt32(PHYSICAL_MIN_FIELD))
            .put(PHYSICAL_MAX_FIELD, imu.getInt32(PHYSICAL_MAX_FIELD))
            .put(DIGITAL_MIN_FIELD, imu.getInt32(DIGITAL_MIN_FIELD))
            .put(DIGITAL_MAX_FIELD, imu.getInt32(DIGITAL_MAX_FIELD));
  }

  private static Map<String, Object> buildImuParamsSchemaless(Map<String, Object> imu) {
    Map<String, Object> out = new LinkedHashMap<>(4);
    out.put(PHYSICAL_MIN_FIELD, ((Number) imu.get(PHYSICAL_MIN_FIELD)).intValue());
    out.put(PHYSICAL_MAX_FIELD, ((Number) imu.get(PHYSICAL_MAX_FIELD)).intValue());
    out.put(DIGITAL_MIN_FIELD, ((Number) imu.get(DIGITAL_MIN_FIELD)).intValue());
    out.put(DIGITAL_MAX_FIELD, ((Number) imu.get(DIGITAL_MAX_FIELD)).intValue());
    return out;
  }

  private Struct buildKey(Struct root, Struct modality) {
    Struct enrollment = root.getStruct(ENROLLMENT_FIELD);
    Struct enrollmentStruct = new Struct(ENROLLMENT_SCHEMA)
            .put(PARTICIPANT_ID_FIELD, enrollment.getString(PARTICIPANT_ID_FIELD))
            .put(SITE_ID_FIELD, enrollment.getString(SITE_ID_FIELD))
            .put(STUDY_ID_FIELD, enrollment.getString(STUDY_ID_FIELD))
            .put(ORGANIZATION_ID_FIELD, enrollment.getString(ORGANIZATION_ID_FIELD));
    return new Struct(KEY_SCHEMA)
            .put(TIMEZONE_FIELD, root.getInt32(TIMEZONE_FIELD))
            .put(DEVICE_SN_FIELD, root.getString(DEVICE_SN_FIELD))
            .put(DEVICE_MODEL_FIELD, root.getString(DEVICE_MODEL_FIELD))
            .put(ENROLLMENT_FIELD, enrollmentStruct)
            .put(TIMESTAMP_START_FIELD, batchStartTimestamp(modality));
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> buildKeySchemaless(Map<String, Object> root, Map<String, Object> modality) {
    Map<String, Object> enrollment = (Map<String, Object>) root.get(ENROLLMENT_FIELD);
    Map<String, Object> enrollmentOut = new LinkedHashMap<>(4);
    enrollmentOut.put(PARTICIPANT_ID_FIELD, enrollment.get(PARTICIPANT_ID_FIELD));
    enrollmentOut.put(SITE_ID_FIELD, enrollment.get(SITE_ID_FIELD));
    enrollmentOut.put(STUDY_ID_FIELD, enrollment.get(STUDY_ID_FIELD));
    enrollmentOut.put(ORGANIZATION_ID_FIELD, enrollment.get(ORGANIZATION_ID_FIELD));
    Map<String, Object> key = new LinkedHashMap<>(5);
    key.put(TIMEZONE_FIELD, ((Number) root.get(TIMEZONE_FIELD)).intValue());
    key.put(DEVICE_SN_FIELD, root.get(DEVICE_SN_FIELD));
    key.put(DEVICE_MODEL_FIELD, root.get(DEVICE_MODEL_FIELD));
    key.put(ENROLLMENT_FIELD, enrollmentOut);
    key.put(TIMESTAMP_START_FIELD, batchStartTimestampSchemaless(modality));
    return key;
  }

  private Schema valueSchema() {
    if (valueSchema == null) {
      SchemaBuilder builder = SchemaBuilder.struct()
              .name("org.radarbase.kafka.connect.transforms.Empatica"
                      + capitalize(modalityKey()) + "Samples")
              .field(SAMPLES_FIELD, SchemaBuilder.array(sampleSchema()).build());
      if (hasSamplingFrequency()) {
        builder.field(SAMPLING_FREQUENCY_FIELD, Schema.FLOAT64_SCHEMA);
      }
      if (hasImuParams()) {
        builder.field(IMU_PARAMS_FIELD, IMU_PARAMS_SCHEMA);
      }
      addValueMetadataFields(builder);
      valueSchema = builder.build();
    }
    return valueSchema;
  }

  protected void addValueMetadataFields(SchemaBuilder builder) {
    // no extra metadata by default
  }

  protected void addValueMetadata(Struct value, Struct modality) {
    // no extra metadata by default
  }

  protected void addValueMetadataSchemaless(Map<String, Object> value, Map<String, Object> modality) {
    // no extra metadata by default
  }

  protected static long sampleTimestamp(long timestampStart, double samplingFrequency, int i) {
    return timestampStart + Math.round(i * 1_000_000.0 / samplingFrequency);
  }

  private String purpose() {
    return "extract Empatica " + modalityKey() + " samples";
  }

  private String destinationTopic(R r) {
    return topic.isEmpty() ? r.topic() : topic;
  }

  private static String capitalize(String s) {
    return Character.toUpperCase(s.charAt(0)) + s.substring(1);
  }

  @Override
  public ConfigDef config() {
    return CONFIG_DEF;
  }

  @Override
  public void close() {
    // no close
  }

  @Override
  public void configure(Map<String, ?> map) {
    topic = new SimpleConfig(CONFIG_DEF, map).getString(TOPIC_CONFIG);
  }
}
