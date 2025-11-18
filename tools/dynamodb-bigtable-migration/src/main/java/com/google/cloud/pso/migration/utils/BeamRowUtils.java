package com.google.cloud.pso.migration.utils;

import com.google.cloud.pso.migration.ControlFileProcessor;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.apache.beam.sdk.schemas.Schema;
import org.apache.beam.sdk.values.Row;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public class BeamRowUtils {
  private static final Logger LOG = LoggerFactory.getLogger(BeamRowUtils.class);
  // Using Text Payload schema as per DynamoDB requirement (storing JSON strings as values often)
  private static final Schema bigtableCellSchema = getBigtableCellSchema();
  public static final Schema bigtableRowWithTextPayloadSchema = getBigtableRowSchema(bigtableCellSchema);
  private static final Gson gson = new Gson();

  static {
    LOG.info("Initialized Bigtable schemas.");
  }

  private static Schema getBigtableCellSchema() {
    return Schema.builder()
        .addNullableField(DataLoadConstants.SchemaFields.COLUMN_FAMILY, Schema.FieldType.STRING)
        .addNullableField(DataLoadConstants.SchemaFields.COLUMN, Schema.FieldType.STRING)
        .addNullableField(DataLoadConstants.SchemaFields.PAYLOAD, Schema.FieldType.STRING)
        .addNullableField(DataLoadConstants.SchemaFields.TIMESTAMP, Schema.FieldType.INT64)
        .build();
  }

  private static Schema getBigtableRowSchema(Schema bigtableCellSchema) {
    return Schema.builder()
        .addField(DataLoadConstants.SchemaFields.ROW_KEY, Schema.FieldType.STRING)
        .addArrayField(DataLoadConstants.SchemaFields.CELLS, Schema.FieldType.row(bigtableCellSchema))
        .build();
  }

  // MODIFIED: Accepts ControlFileConfig
  public static Row jsonToBeamRow(String dynamoJsonRow, ControlFileProcessor.ControlFileConfig config) {
    DynamoRowUtils converter = new DynamoRowUtils();
    // Pass config to converter
    String bigtableJsonRow = converter.convertDynamoDBJson(dynamoJsonRow, config);

    JsonObject jsonObject = gson.fromJson(bigtableJsonRow, JsonObject.class);
    String rowKey = jsonObject.get(DataLoadConstants.SchemaFields.ROW_KEY).getAsString();
    List<Row> bigtableCells = new ArrayList<>();

    for (JsonElement jsonElement : jsonObject.getAsJsonArray(DataLoadConstants.SchemaFields.CELLS).asList()) {
      JsonObject bigtableCell = jsonElement.getAsJsonObject();
      Row.Builder rowBuilder = Row.withSchema(bigtableCellSchema);

      JsonElement payloadElement = bigtableCell.get(DataLoadConstants.SchemaFields.PAYLOAD);
      String payload = extractPayload(payloadElement);
      Row.FieldValueBuilder fieldValueBuilder = rowBuilder.withFieldValue(DataLoadConstants.SchemaFields.PAYLOAD, payload);

      fieldValueBuilder = addFieldIfNotNull(fieldValueBuilder, DataLoadConstants.SchemaFields.COLUMN_FAMILY, bigtableCell);
      fieldValueBuilder = addFieldIfNotNull(fieldValueBuilder, DataLoadConstants.SchemaFields.COLUMN, bigtableCell);
      // Optional timestamp support from Control File
      fieldValueBuilder = addFieldIfNotNull(fieldValueBuilder, DataLoadConstants.SchemaFields.TIMESTAMP, bigtableCell, JsonElement::getAsLong);

      bigtableCells.add(fieldValueBuilder.build());
    }

    return Row.withSchema(bigtableRowWithTextPayloadSchema)
        .withFieldValue(DataLoadConstants.SchemaFields.ROW_KEY, rowKey)
        .withFieldValue(DataLoadConstants.SchemaFields.CELLS, bigtableCells)
        .build();
  }

  private static Row.FieldValueBuilder addFieldIfNotNull(Row.FieldValueBuilder builder, String fieldName, JsonObject cell) {
    return addFieldIfNotNull(builder, fieldName, cell, JsonElement::getAsString);
  }

  private static <T> Row.FieldValueBuilder addFieldIfNotNull(Row.FieldValueBuilder builder, String fieldName, JsonObject cell, Function<JsonElement, T> typeConverter) {
    JsonElement value = cell.get(fieldName);
    if (value != null && !value.isJsonNull()) {
      return builder.withFieldValue(fieldName, typeConverter.apply(value));
    }
    return builder;
  }

  private static String extractPayload(JsonElement payloadElement) {
    if (payloadElement == null || payloadElement.isJsonNull()) return null;
    if (payloadElement.isJsonPrimitive() && payloadElement.getAsJsonPrimitive().isString()) {
      return payloadElement.getAsString();
    }
    return payloadElement.toString(); // Keep complex objects as JSON strings
  }
}