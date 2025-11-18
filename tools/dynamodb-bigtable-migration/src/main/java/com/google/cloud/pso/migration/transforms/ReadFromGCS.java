package com.google.cloud.pso.migration.transforms;

import com.google.cloud.pso.migration.ControlFileProcessor;
import com.google.cloud.pso.migration.model.InputFormat;
import com.google.cloud.pso.migration.utils.BeamRowUtils;
import org.apache.beam.sdk.coders.RowCoder;
import org.apache.beam.sdk.io.Compression;
import org.apache.beam.sdk.io.TextIO;
import org.apache.beam.sdk.transforms.MapElements;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.values.PBegin;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.Row;
import org.apache.beam.sdk.values.TypeDescriptor;

public class ReadFromGCS extends PTransform<PBegin, PCollection<Row>> {
  private final String inputFilePath;
  private final InputFormat inputFormat;
  private final ControlFileProcessor.ControlFileConfig controlFileConfig;

  public ReadFromGCS(String inputFilePath,
      InputFormat inputFormat,
      ControlFileProcessor.ControlFileConfig controlFileConfig) {
    this.inputFilePath = inputFilePath;
    this.inputFormat = inputFormat;
    this.controlFileConfig = controlFileConfig;
  }

  @Override
  public PCollection<Row> expand(PBegin input) {
    switch (this.inputFormat) {
      case DYNAMO:
        PCollection<String> rawLines = input.apply("Read GZipped Files",
            TextIO.read().from(this.inputFilePath).withCompression(Compression.AUTO));

        return rawLines.apply("Convert to Beam Row",
                MapElements.into(TypeDescriptor.of(Row.class))
                    .via(line -> BeamRowUtils.jsonToBeamRow(line, this.controlFileConfig)))
            .setCoder(RowCoder.of(BeamRowUtils.bigtableRowWithTextPayloadSchema));
    }
    throw new IllegalArgumentException("Invalid Input Format passed");
  }
}