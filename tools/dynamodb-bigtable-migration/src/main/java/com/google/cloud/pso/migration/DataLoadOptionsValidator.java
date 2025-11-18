package com.google.cloud.pso.migration;

import com.google.cloud.bigtable.admin.v2.BigtableTableAdminClient;
import com.google.cloud.bigtable.admin.v2.BigtableTableAdminSettings;
import com.google.cloud.bigtable.admin.v2.models.CreateTableRequest;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Validator class for DataLoad pipeline options with specific validation for Bigtable
 * configurations.
 */
public class DataLoadOptionsValidator {

  /**
   * Validates all pipeline options and Bigtable configurations.
   *
   * @param options The DataLoadOptions to validate
   * @throws IllegalArgumentException If validation fails due to invalid or missing options
   */
  public static void validateOptions(DataLoadPipeline.DataLoadOptions options) {
    validateBigtableOptions(options);
    validateBigtableResources(options);
  }

  /** Validates Bigtable related options. */
  private static void validateBigtableOptions(DataLoadPipeline.DataLoadOptions options) {
    // Validate Bigtable configuration parameters
    if (options.getBigtableInstanceId() != null) {
      if (options.getBigtableProjectId() == null
          || options.getBigtableProjectId().trim().isEmpty()) {
        throw new IllegalArgumentException(
            "Bigtable Instance id was provided but bigtable projectid is missing.");
      }
    }
  }

  /**
   * Validates if the specified Bigtable resources exist, or creates them if they don't.
   */
  private static void validateBigtableResources(DataLoadPipeline.DataLoadOptions options) {
    if (options.getBigtableInstanceId() != null) {
      try {
        // Create Bigtable admin client settings
        String projectId = options.getBigtableProjectId();
        String instanceId = options.getBigtableInstanceId();

        BigtableTableAdminSettings adminSettings =
            BigtableTableAdminSettings.newBuilder()
                .setProjectId(projectId)
                .setInstanceId(instanceId)
                .build();

        // Create admin client
        try (BigtableTableAdminClient adminClient =
            BigtableTableAdminClient.create(adminSettings)) {
          String tableId = options.getBigtableTableId();
          if (tableId == null || tableId.trim().isEmpty()) {
            // Generate table ID with current datetime
            tableId = generateTableId(adminClient, "migrated_table");
            // Create table with a default column family "cf" if none exists.
            // The actual families used will be determined by the Control File during runtime.
            CreateTableRequest createTableRequest = CreateTableRequest.of(tableId).addFamily("cf");
            adminClient.createTable(createTableRequest);

            // Update options with the newly created table ID
            options.setBigtableTableId(tableId);
          } else {
            try {
              // Verify table exists
              adminClient.getTable(tableId);
            } catch (com.google.api.gax.rpc.NotFoundException e) {
              throw new IllegalArgumentException(
                  String.format("Table '%s' does not exist in instance '%s'", tableId, instanceId));
            }
          }
        }
      } catch (Exception e) {
        throw new IllegalArgumentException(
            "Failed to validate Bigtable resources: " + e.getMessage(), e);
      }
    }
  }

  /**
   * Generates a table ID with the given prefix and current datetime, adding a suffix if necessary
   * to ensure uniqueness.
   */
  private static String generateTableId(BigtableTableAdminClient adminClient, String prefix) {
    String baseTableId =
        prefix
            + DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
            .withZone(ZoneId.systemDefault())
            .format(Instant.now());
    String tableId = baseTableId;
    int suffix = 1;

    while (adminClient.exists(tableId)) {
      tableId = baseTableId + "_" + suffix;
      suffix++;
    }

    return tableId;
  }
}