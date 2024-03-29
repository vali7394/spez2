/*
 * Copyright 2019 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.spez.core;

import com.google.auto.value.AutoValue;
import com.google.cloud.spanner.ResultSet;
import com.google.cloud.spanner.Struct;
import com.google.cloud.spanner.Type;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.protobuf.ByteString;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.Set;
import org.apache.avro.Schema;
import org.apache.avro.SchemaBuilder;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.DatumWriter;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.io.JsonEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SpannerToAvro {
  private static final Logger log = LoggerFactory.getLogger(SpannerToAvro.class);

  private SpannerToAvro() {}

  public static SchemaSet GetSchema(String tableName, String avroNamespace, ResultSet resultSet) {

    final LinkedHashMap<String, String> spannerSchema = Maps.newLinkedHashMap();
    final SchemaBuilder.FieldAssembler<Schema> avroSchemaBuilder =
        SchemaBuilder.record(tableName).namespace(avroNamespace).fields();
    log.debug("Getting Schema");

    log.debug("Processing  Schema");
    while (resultSet.next()) {
      log.debug("Making Avro Schema");
      final Struct currentRow = resultSet.getCurrentRowAsStruct();
      final String name = currentRow.getString(0);
      final String type = currentRow.getString(1);
      spannerSchema.put(name, type);
      log.debug("Binding Avro Schema");
      switch (type) {
        case "ARRAY":
          log.debug("Made ARRAY");
          avroSchemaBuilder.name(name).type().array();
          break;
        case "BOOL":
          log.debug("Made BOOL");
          avroSchemaBuilder.name(name).type().booleanType().noDefault();
          break;
        case "BYTES":
          log.debug("Made BYTES");
          avroSchemaBuilder.name(name).type().bytesType().noDefault();
          break;
        case "DATE":
          // Date handled as String type
          log.debug("Made DATE");
          avroSchemaBuilder.name(name).type().stringType().noDefault();
          break;
        case "FLOAT64":
          log.debug("Made FLOAT64");
          avroSchemaBuilder.name(name).type().doubleType().noDefault();
          break;
        case "INT64":
          log.debug("Made INT64");
          avroSchemaBuilder.name(name).type().longType().noDefault();
          break;
        case "STRING(MAX)":
          log.debug("Made STRING(MAX)");
          avroSchemaBuilder.name(name).type().stringType().noDefault();
          break;
        case "TIMESTAMP":
          log.debug("Made TIMESTAMP");
          avroSchemaBuilder.name(name).type().stringType().noDefault();
          break;
        default:
          if (type.contains("STRING")) {
            log.debug("Made STRING: " + type);
            avroSchemaBuilder.name(name).type().stringType().noDefault();
          } else {
            log.error("Unknown Schema type when generating Avro Schema: " + type);
            //          stop();
            System.exit(1);
          }
          break;
      }
    }

    log.debug("Ending Avro Record");
    final Schema avroSchema = avroSchemaBuilder.endRecord();

    log.debug("Made Avro Schema");

    if (log.isDebugEnabled()) {
      final Set<String> keySet = spannerSchema.keySet();
      for (String k : keySet) {
        log.debug("-------------------------- ColName: " + k + " Type: " + spannerSchema.get(k));
      }

      log.debug("--------------------------- " + avroSchema.toString());
    }

    return SchemaSet.create(avroSchema, ImmutableMap.copyOf(spannerSchema));
  }

  public static Optional<ByteString> MakeRecord(SchemaSet schemaSet, Struct resultSet) {
    final ByteBuf bb = Unpooled.directBuffer();
    final Set<String> keySet = schemaSet.spannerSchema().keySet();

    final GenericRecord record = new GenericData.Record(schemaSet.avroSchema());
    keySet.forEach(
        x -> {
          switch (schemaSet.spannerSchema().get(x)) {
            case "ARRAY":
              log.debug("Put ARRAY");

              final Type columnType = resultSet.getColumnType(x);
              final String arrayTypeString = columnType.getArrayElementType().getCode().toString();

              switch (arrayTypeString) {
                case "BOOL":
                  log.debug("Put BOOL");
                  record.put(x, resultSet.getBooleanList(x));
                  break;
                case "BYTES":
                  log.debug("Put BYTES");
                  record.put(x, resultSet.getBytesList(x));
                  break;
                case "DATE":
                  log.debug("Put DATE");
                  record.put(x, resultSet.getStringList(x));
                  break;
                case "FLOAT64":
                  log.debug("Put FLOAT64");
                  record.put(x, resultSet.getDoubleList(x));
                  break;
                case "INT64":
                  log.debug("Put INT64");
                  record.put(x, resultSet.getLongList(x));
                  break;
                case "STRING(MAX)":
                  log.debug("Put STRING");
                  record.put(x, resultSet.getStringList(x));
                  break;
                case "TIMESTAMP":
                  // Timestamp lists are not supported as of now
                  log.error("Cannot add Timestamp array list to avro record: " + arrayTypeString);
                  break;
                default:
                  log.error("Unknown Data type when generating Array Schema: " + arrayTypeString);
                  break;
              }

              break;
            case "BOOL":
              log.debug("Put BOOL");
              record.put(x, resultSet.getBoolean(x));
              break;
            case "BYTES":
              log.debug("Put BYTES");
              record.put(x, resultSet.getBytes(x));
              break;
            case "DATE":
              log.debug("Put DATE");
              record.put(x, resultSet.getString(x));
              break;
            case "FLOAT64":
              log.debug("Put FLOAT64");
              record.put(x, resultSet.getDouble(x));
              break;
            case "INT64":
              log.debug("Put INT64");
              record.put(x, resultSet.getLong(x));
              break;
            case "STRING(MAX)":
              log.debug("Put STRING");
              record.put(x, resultSet.getString(x));
              break;
            case "TIMESTAMP":
              log.debug("Put TIMESTAMP");
              record.put(x, resultSet.getTimestamp(x).toString());
              break;
            default:
              if (schemaSet.spannerSchema().get(x).contains("STRING")) {
                log.debug("Put STRING");
                record.put(x, resultSet.getString(x));

              } else {
                log.error(
                    "Unknown Data type when generating Avro Record: "
                        + schemaSet.spannerSchema().get(x));
              }
              break;
          }
        });

    log.debug("Made Record");
    log.debug(record.toString());

    try (final ByteBufOutputStream outputStream = new ByteBufOutputStream(bb)) {

      final JsonEncoder encoder =
          EncoderFactory.get().jsonEncoder(schemaSet.avroSchema(), outputStream, true);
      final DatumWriter<Object> writer = new GenericDatumWriter<>(schemaSet.avroSchema());

      log.debug("Serializing Record");
      writer.write(record, encoder);
      encoder.flush();
      outputStream.flush();
      log.debug("Adding serialized record to list");
      final byte[] ba = new byte[bb.readableBytes()];
      log.debug("--------------------------------- readableBytes " + bb.readableBytes());
      log.debug("--------------------------------- readerIndex " + bb.readerIndex());
      log.debug("--------------------------------- writerIndex " + bb.writerIndex());
      bb.getBytes(bb.readerIndex(), ba);
      final ByteString message = ByteString.copyFrom(ba);

      return Optional.of(message);

    } catch (IOException e) {
      log.error(
          "IOException while Serializing Spanner Stuct to Avro Record: " + record.toString(), e);
    } finally {
      // ...
    }

    return Optional.empty();
  }

  @AutoValue
  public abstract static class SchemaSet {
    static SchemaSet create(Schema avroSchema, ImmutableMap<String, String> spannerSchema) {
      return new AutoValue_SpannerToAvro_SchemaSet(avroSchema, spannerSchema);
    }

    abstract Schema avroSchema();

    abstract ImmutableMap<String, String> spannerSchema();
  }
}
