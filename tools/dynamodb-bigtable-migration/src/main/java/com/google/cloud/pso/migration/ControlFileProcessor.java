package com.google.cloud.pso.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.beam.sdk.io.FileSystems;

import java.io.IOException;
import java.io.Serializable;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class ControlFileProcessor {

  public static ControlFileConfig parseControlFile(String controlFilePath) throws IOException, URISyntaxException {
    ObjectMapper objectMapper = new ObjectMapper();
    String jsonContent = readGcsFile(controlFilePath);
    JsonNode rootNode = objectMapper.readTree(jsonContent);

    ControlFileConfig config = new ControlFileConfig();
    config.sourceFileLocation = rootNode.path("sourceFileLocation").asText();
    config.defaultColumnFamily = rootNode.path("defaultColumnFamily").asText();
    config.defaultColumnQualifier = rootNode.path("defaultColumnQualifier").asText();
    config.defaultTimestamp = rootNode.path("defaultTimestamp").asText();

    JsonNode rowKeyNode = rootNode.path("rowKey");
    if (rowKeyNode.isObject()) {
      config.rowKeyType = rowKeyNode.path("type").asText();
      config.rowKeyFields = new ArrayList<>();
      rowKeyNode.path("fields").forEach(fieldNode -> config.rowKeyFields.add(fieldNode.asText()));
      config.rowKeyChainChar = rowKeyNode.path("chainChar").asText();
    }

    JsonNode mappingsNode = rootNode.path("columnQualifierMappings");
    if (mappingsNode.isArray()) {
      config.columnQualifierMappings = new ArrayList<>();
      mappingsNode.forEach(mappingNode -> {
        ColumnMapping mapping = new ColumnMapping();
        mapping.json = mappingNode.path("json").asText();
        mapping.columnFamily = mappingNode.path("columnFamily").asText(config.defaultColumnFamily);
        mapping.columnQualifier = mappingNode.path("columnQualifier").asText();
        config.columnQualifierMappings.add(mapping);

      });
    }

    if (config.defaultTimestamp == null || config.defaultTimestamp.isEmpty()) {
      config.defaultTimestamp = "default";
    }

    return config;
  }

  private static String readGcsFile(String gcsPath) throws IOException, URISyntaxException {
    URI uri = new URI(gcsPath);
    try (ReadableByteChannel channel = FileSystems.open(FileSystems.matchNewResource(uri.toString(), false))) {
      return new String(Channels.newInputStream(channel).readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  public static class ControlFileConfig implements Serializable{
    public String sourceFileLocation;
    public String defaultColumnFamily;
    public String defaultColumnQualifier;

    public String defaultTimestamp;
    public String rowKeyType;
    public List<String> rowKeyFields;
    public String rowKeyChainChar;
    public List<ColumnMapping> columnQualifierMappings;
  }

  public static class ColumnMapping implements Serializable {
    public String json;
    public String columnFamily;
    public String columnQualifier;
  }
}