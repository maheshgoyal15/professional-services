package com.google.cloud.pso.migration;

import com.google.cloud.pso.migration.model.InputFormat;
import com.google.cloud.pso.migration.transforms.ReadFromGCS;
import com.google.cloud.pso.migration.transforms.WriteToBigtable;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.extensions.gcp.options.GcsOptions;
import org.apache.beam.sdk.io.FileSystems;
import org.apache.beam.sdk.options.Default;
import org.apache.beam.sdk.options.Description;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.Row;

public class DataLoadPipeline {

  public interface DataLoadOptions extends BaseOptions {
    @Description("The flag for enabling splitting of large rows into multiple MutateRows requests.")
    @Default.Boolean(true)
    boolean getBigtableSplitLargeRows();

    void setBigtableSplitLargeRows(boolean value);

    @Description("Maximum mutations per row.")
    @Default.Integer(100000)
    int getBigtableMaxMutationsPerRow();

    void setBigtableMaxMutationsPerRow(int value);

    @Description("Path to the control file in GCS (JSON format).")
    String getControlFilePath();

    void setControlFilePath(String value);
  }

  public static void main(String[] args) throws Exception {
    DataLoadOptions options =
        PipelineOptionsFactory.fromArgs(args).withValidation().as(DataLoadOptions.class);

    // 2. Run your custom validation logic
    DataLoadOptionsValidator.validateOptions(options);

    // Validations for basic Bigtable connectivity
    if (options.getBigtableInstanceId() == null
        || options.getBigtableProjectId() == null
        || options.getBigtableTableId() == null) {
      throw new IllegalArgumentException("Bigtable Project, Instance, and Table IDs are required.");
    }
    if (options.getControlFilePath() == null) {
      throw new IllegalArgumentException("Control File Path (--controlFilePath) is required.");
    }

    Pipeline pipeline = Pipeline.create(options);
    FileSystems.setDefaultPipelineOptions(options.as(GcsOptions.class));

    // 1. Parse Control File
    ControlFileProcessor.ControlFileConfig config =
        ControlFileProcessor.parseControlFile(options.getControlFilePath());

    // 2. Read and Process Data using Control File Config
    // Priority: Use inputFilePath from command line if present, otherwise fall back to control
    // file's sourceFileLocation
    String inputPath =
        options.getInputFilePath() != null ? options.getInputFilePath() : config.sourceFileLocation;
    if (inputPath == null) {
      throw new IllegalArgumentException(
          "Input file path must be provided either via --inputFilePath or in the Control File.");
    }

    PCollection<Row> inputData =
        pipeline.apply(
            "Read from Cloud Storage", new ReadFromGCS(inputPath, InputFormat.DYNAMO, config));

    // 3. Write to Bigtable
    inputData.apply(
        "Write To Bigtable", new WriteToBigtable(options.getBigtableMaxMutationsPerRow()));

    pipeline.run();
  }
}
