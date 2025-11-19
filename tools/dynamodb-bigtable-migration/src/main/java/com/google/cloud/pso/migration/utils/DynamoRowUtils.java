package com.google.cloud.pso.migration.utils;

import com.google.cloud.pso.migration.ControlFileProcessor;
import com.google.gson.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class DynamoRowUtils {
  // Use serializeNulls to ensure we don't lose explicitly null data if needed
  private static final Gson gson = new GsonBuilder().serializeNulls().create();
  private static final Logger LOGGER = Logger.getLogger(DynamoRowUtils.class.getName());

  /** Main entry point. Normalizes DynamoDB JSON then applies Control File logic. */
  public String convertDynamoDBJson(String input, ControlFileProcessor.ControlFileConfig config) {
    try {
      // 1. Parse raw DynamoDB export line
      JsonObject rawWrapper = JsonParser.parseString(input).getAsJsonObject();
      // DynamoDB exports are usually wrapped in {"Item": ... }
      JsonObject dynamoItem = rawWrapper.getAsJsonObject(DataLoadConstants.DynamoDBFields.ITEMS);

      if (dynamoItem == null) {
        throw new RuntimeException(
            "Input JSON does not contain '"
                + DataLoadConstants.DynamoDBFields.ITEMS
                + "' root element.");
      }

      // 2. Normalize DynamoDB Typed JSON to Standard JSON
      JsonObject standardJson = normalizeDynamoItem(dynamoItem);

      // 3. Apply Control File Mappings (ported from MSSQL JsonRowUtils)
      return convertPayloadToCells(standardJson, config);

    } catch (Exception e) {
      LOGGER.log(Level.SEVERE, "Failed to convert DynamoDB JSON: " + e.getMessage(), e);
      throw new RuntimeException("Unable to convert the data: " + e.getMessage(), e);
    }
  }

  /**
   * Converts DynamoDB typed JSON (e.g., {"pk":{"S":"123"}}) into standard JSON (e.g., {"pk":"123"})
   */
  private JsonObject normalizeDynamoItem(JsonObject dynamoItem) {
    JsonObject standard = new JsonObject();
    for (Map.Entry<String, JsonElement> entry : dynamoItem.entrySet()) {
      // Parse the DynamoDB typed value into a Java object
      Object javaObject = parseDynamoDBValue(entry.getValue().getAsJsonObject());
      // Convert back to a pure GSON element to build a clean JsonObject
      standard.add(entry.getKey(), gson.toJsonTree(javaObject));
    }
    return standard;
  }

  // =================================================================================
  // PORTED LOGIC FROM MSSQL JsonRowUtils (ROW KEY & MAPPING)
  // =================================================================================

  private String convertPayloadToCells(
      JsonObject jsonPayload, ControlFileProcessor.ControlFileConfig config) {
    // 1. Build Row Key using Control File rules
    String bigtableRowKey = buildRowKey(jsonPayload, config);

    JsonObject result = new JsonObject();
    result.addProperty(DataLoadConstants.SchemaFields.ROW_KEY, bigtableRowKey);

    JsonArray cells = new JsonArray();
    long timestampMicros = getTimestampMicros(config.defaultTimestamp);

    // 2. Process explicit Column Mappings from Control File
    if (config.columnQualifierMappings != null) {
      for (ControlFileProcessor.ColumnMapping mapping : config.columnQualifierMappings) {
        // Use getNestedJsonElement to find the field in the *normalized* standard JSON
        JsonElement jsonValue = getNestedJsonElement(jsonPayload, mapping.json);
        if (jsonValue != null && !jsonValue.isJsonNull()) {
          cells.add(
              createCell(
                  mapping.columnFamily, mapping.columnQualifier, jsonValue, timestampMicros));
        }
      }
    }

    // 3. Process Default Column (dump everything if configured)
    if (config.defaultColumnQualifier != null && !config.defaultColumnQualifier.isEmpty()) {
      // Ensure we have a default family
      String family =
          config.defaultColumnFamily != null && !config.defaultColumnFamily.isEmpty()
              ? config.defaultColumnFamily
              : "cf";
      cells.add(createCell(family, config.defaultColumnQualifier, jsonPayload, timestampMicros));
    }

    result.add(DataLoadConstants.SchemaFields.CELLS, cells);
    return gson.toJson(result);
  }

  private String buildRowKey(
      JsonObject jsonPayload, ControlFileProcessor.ControlFileConfig config) {
    if (config.rowKeyType == null || config.rowKeyFields == null || config.rowKeyFields.isEmpty()) {
      throw new IllegalArgumentException("Row key configuration is missing in control file.");
    }

    if ("simple".equalsIgnoreCase(config.rowKeyType)) {
      String fieldName = config.rowKeyFields.get(0);
      JsonElement element = getNestedJsonElement(jsonPayload, fieldName);
      if (element == null || element.isJsonNull()) {
        throw new IllegalArgumentException(
            "Simple row key field '" + fieldName + "' not found in payload.");
      }
      return element.getAsString();
    } else if ("composite".equalsIgnoreCase(config.rowKeyType)) {
      StringBuilder keyBuilder = new StringBuilder();
      String separator = config.rowKeyChainChar != null ? config.rowKeyChainChar : "#";
      boolean first = true;
      for (String field : config.rowKeyFields) {
        if (!first) keyBuilder.append(separator);
        JsonElement element = getNestedJsonElement(jsonPayload, field);
        if (element == null || element.isJsonNull()) {
          throw new IllegalArgumentException("Composite row key field '" + field + "' not found.");
        }
        keyBuilder.append(element.getAsString());
        first = false;
      }
      return keyBuilder.toString();
    } else {
      throw new IllegalArgumentException("Unsupported rowKeyType: " + config.rowKeyType);
    }
  }

  private JsonElement getNestedJsonElement(JsonElement jsonElement, String path) {
    // (Simplified version of MSSQL's complex traverser, adequate for most standard JSON)
    if (jsonElement == null || path == null || path.isEmpty()) return JsonNull.INSTANCE;
    String[] parts = path.split("\\.");
    JsonElement current = jsonElement;
    for (String part : parts) {
      if (current.isJsonObject()) {
        current = current.getAsJsonObject().get(part);
      } else {
        return JsonNull.INSTANCE;
      }
      if (current == null) return JsonNull.INSTANCE;
    }
    return current;
  }

  private JsonObject createCell(
      String family, String qualifier, JsonElement payload, long timestampMicros) {
    JsonObject cell = new JsonObject();
    cell.addProperty(DataLoadConstants.SchemaFields.COLUMN_FAMILY, family);
    cell.addProperty(DataLoadConstants.SchemaFields.COLUMN, qualifier);
    // Store payload as a direct JSON element; BeamRowUtils will stringify it if needed
    cell.add(DataLoadConstants.SchemaFields.PAYLOAD, payload);
    cell.addProperty(DataLoadConstants.SchemaFields.TIMESTAMP, timestampMicros);
    return cell;
  }

  private long getTimestampMicros(String timestamp) {
    // Basic implementation defaulting to current time for simplicity in this view
    if (timestamp == null || timestamp.isEmpty() || "default".equalsIgnoreCase(timestamp)) {
      return System.currentTimeMillis() * 1000L;
    }
    try {
      return Instant.parse(timestamp).toEpochMilli() * 1000L;
    } catch (DateTimeParseException e) {
      try {
        return Long.parseLong(timestamp) * 1000L;
      } // Assume it might be millis
      catch (NumberFormatException ex) {
        return System.currentTimeMillis() * 1000L;
      }
    }
  }

  // =================================================================================
  // RETAINED & ADAPTED DYNAMODB PARSING LOGIC
  // =================================================================================
  // Changed return type to Object so GSON can easily re-serialize it in normalizeDynamoItem
  private Object parseDynamoDBValue(JsonObject value) {
    String typeKey = value.keySet().iterator().next();
    JsonElement actualValue = value.get(typeKey);

    switch (typeKey) {
      case "S":
        return actualValue.getAsString();
      case "N":
        String numStr = actualValue.getAsString();
        return numStr.contains(".") ? new BigDecimal(numStr) : Long.parseLong(numStr);
      case "B":
        return Base64.getEncoder().encodeToString(actualValue.getAsString().getBytes());
      case "BOOL":
        return actualValue.getAsBoolean();
      case "NULL":
        return null;
      case "M":
        Map<String, Object> map = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : actualValue.getAsJsonObject().entrySet()) {
          map.put(entry.getKey(), parseDynamoDBValue(entry.getValue().getAsJsonObject()));
        }
        return map;
      case "L":
        List<Object> list = new ArrayList<>();
        for (JsonElement element : actualValue.getAsJsonArray()) {
          list.add(parseDynamoDBValue(element.getAsJsonObject()));
        }
        return list;
      case "SS":
        List<String> ss = new ArrayList<>();
        actualValue.getAsJsonArray().forEach(e -> ss.add(e.getAsString()));
        return ss;
      case "NS":
        List<Number> ns = new ArrayList<>();
        for (JsonElement element : actualValue.getAsJsonArray()) {
          String n = element.getAsString();
          ns.add(n.contains(".") ? new BigDecimal(n) : Long.parseLong(n));
        }
        return ns;
      case "BS":
        List<String> bs = new ArrayList<>();
        actualValue
            .getAsJsonArray()
            .forEach(e -> bs.add(Base64.getEncoder().encodeToString(e.getAsString().getBytes())));
        return bs;
      default:
        throw new IllegalArgumentException("Unknown DynamoDB type: " + typeKey);
    }
  }
}
