# DynamoDB to Bigtable Migration Utility

## Overview

This utility migrates data from DynamoDB exports in Amazon S3 to Google Cloud Bigtable. It uses a Dataflow Flex Template to create a pipeline that reads DynamoDB JSON data from Google Cloud Storage (GCS), transforms it, and writes it to a Bigtable table.

## Prerequisites

Before you begin, ensure you have the following:

*   A Google Cloud Platform (GCP) project.
*   The `gcloud` command-line tool installed and configured.
*   A Bigtable instance and table created in your GCP project.
*   A GCS bucket for staging and temporary files for Dataflow.
*   A GCS bucket to store the DynamoDB export data.
*   A Google  repository (e.g., Artifact Registry) to store the flex template image.
*   Service account credentials with the necessary permissions for Dataflow, Bigtable, and GCS.
*   Java 11 and Maven installed to build the project.

## Configuration

1.  **Clone the repository:**

    ```bash
    git clone https://github.com/GoogleCloudPlatform/professional-services.git
    cd professional-services/tools/dynamodb-bigtable-migration
    ```

2.  **Set up environment variables:**

    Create a `.env` file in the `scripts` directory by copying the `env-sample`:

    ```bash
    cp scripts/env-sample scripts/.env
    ```

    Edit `scripts/.env` and set the following variables:

    | Variable                       | Description                                                                                              |
    | ------------------------------ | -------------------------------------------------------------------------------------------------------- |
    | `PROJECT_ID`                   | Your Google Cloud project ID.                                                                            |
    | `REGION`                       | The Google Cloud region to run the Dataflow job in (e.g., `us-central1`).                                |
    | `REPOSITORY`                   | The name of the Artifact Registry repository to store the Docker image.                                  |
    | `IMAGE_NAME`                   | The name of the Docker image for the flex template.                                                      |
    | `FLEX_TEMPLATE_SPEC_FILE_PATH` | The GCS path to store the flex template JSON specification (e.g., `gs://your-bucket/templates/dynamodb-bt`). |
    | `STAGING_LOCATION`             | The GCS path for Dataflow to stage files (e.g., `gs://your-bucket/staging/`).                            |
    | `TEMP_LOCATION`                | The GCS path for Dataflow to store temporary files (e.g., `gs://your-bucket/temp/`).                     |
    | `CONTROL_FILE_PATH`            | The GCS path to the control file (e.g., `gs://your-bucket/control-file.json`).                           |
    | `INPUT_FILEPATH`               | The GCS path to the DynamoDB export data (e.g., `gs://your-dynamodb-export-bucket/data/*.json.gz`).        |
    | `BIGTABLE_INSTANCE_ID`         | The ID of your Bigtable instance.                                                                        |
    | `BIGTABLE_TABLE_ID`            | The ID of your Bigtable table.                                                                           |

## Building the Utility

The `flextemplate-build.sh` script in the `scripts` directory builds the uber JAR and the Dataflow Flex Template.

1.  **Build the JAR and Flex Template:**

    First, make the script executable:
    ```bash
    chmod +x scripts/flextemplate-build.sh
    ```

    Then, run the script:
    ```bash
    bash scripts/flextemplate-build.sh
    ```

    This script performs the following actions:
    *   Runs `mvn clean package` to create an uber JAR in the `target/` directory.
    *   Uses `gcloud dataflow flex-template build` to create the flex template and uploads it to GCS.

## Control File

The control file is a JSON file that defines the mapping between DynamoDB items and Bigtable rows.

Here is an example of a `control-file.json`:

```json
{
  "sourceFileLocation": "gs://<your-gcs-bucket-name>/dynamodb-export/*.json.gz",
  "defaultColumnFamily": "cf_raw",
  "defaultColumnQualifier": "full_item",
  "rowKey": {
    "type": "simple",
    "fields": [
      "UserId"
    ]
  },
  "columnQualifierMappings": [
    {
      "json": "FullName",
      "columnFamily": "cf_profile",
      "columnQualifier": "full_name"
    },
    {
      "json": "EmailAddress",
      "columnFamily": "cf_contact",
      "columnQualifier": "email"
    }
  ]
}
```

*   `sourceFileLocation`: The GCS path to the DynamoDB export data. This can be overridden by the `inputFilePath` parameter.
*   `defaultColumnFamily`: (Optional) If a DynamoDB item does not match any of the `columnQualifierMappings`, the entire item will be written to this column family.
*   `defaultColumnQualifier`: (Optional) The column qualifier to use when writing an unmapped item to the `defaultColumnFamily`.
*   `rowKey`: An object that defines how to construct the Bigtable row key.
    *   `type`: The type of row key construction. Can be `simple` for a single field key, or `composite` to combine multiple fields.
    *   `fields`: An array of DynamoDB attribute names to use for the row key. For a `simple` key, provide one field. For a `composite` key, provide multiple fields in the desired order.
    *   `chainChar`: (Optional) The character to use when joining multiple fields for a `composite` row key. Defaults to `#`.
*   `columnQualifierMappings`: An array of objects that define how to map DynamoDB attributes to Bigtable columns.
    *   `json`: The name of the DynamoDB attribute. You can use dot notation for nested attributes (e.g., `Contact.Email`).
    *   `columnFamily`: The Bigtable column family.
    *   `columnQualifier`: The Bigtable column qualifier.

### Composite Row Key Example

To create a row key from a partition key (`UserId`) and a sort key (`TransactionId`), you can use the `composite` row key type:

```json
{
  "sourceFileLocation": "gs://<your-gcs-bucket-name>/dynamodb-export/*.json.gz",
  "defaultColumnFamily": "cf_raw",
  "rowKey": {
    "type": "composite",
    "fields": [
      "UserId",
      "TransactionId"
    ],
    "chainChar": "#"
  },
  "columnQualifierMappings": [
    {
      "json": "Amount",
      "columnFamily": "cf_details",
      "columnQualifier": "amount"
    }
  ]
}
```

This configuration will create a Bigtable row key like `12345#abcdef`.

## Running the Migration

The `flextemplate-run.sh` script in the `scripts` directory runs the Dataflow job to start the migration.

1.  **Run the migration:**

    First, make the script executable:
    ```bash
    chmod +x scripts/flextemplate-run.sh
    ```

    Then, run the script:
    ```bash
    bash scripts/flextemplate-run.sh DYNAMO-BT
    ```

    This command will start a new Dataflow job with a unique name.

## Pipeline Parameters

The following parameters can be passed to the Dataflow pipeline:

| Parameter                 | Description                                                                              |
| ------------------------- | ---------------------------------------------------------------------------------------- |
| `controlFilePath`         | The GCS path to the control file.                                                        |
| `inputFilePath`           | The GCS path to the DynamoDB export data. Overrides `sourceFileLocation` in the control file. |
| `bigtableProjectId`       | The GCP project ID of the Bigtable instance.                                             |
| `bigtableInstanceId`      | The ID of the Bigtable instance.                                                         |
| `bigtableTableId`         | The ID of the Bigtable table.                                                            |
| `bigtableSplitLargeRows`  | (Optional) Whether to split large rows into multiple `MutateRows` requests. Default: `true`. |
| `bigtableMaxMutationsPerRow` | (Optional) The maximum number of mutations per row. Default: `100000`.                     |

## Monitoring

You can monitor the progress of the Dataflow job in the Google Cloud Console under the "Dataflow" section.

## Troubleshooting

*   **Permission Denied errors:** Ensure the service account used by Dataflow has the necessary IAM roles. If you are using a custom service account, you can specify it by adding the `--service-account-email` parameter to the `gcloud dataflow flex-template run` command in the `scripts/flextemplate-run.sh` script.
    ```bash
    --service-account-email=SERVICE_ACCOUNT_NAME@PROJECT_ID.iam.gserviceaccount.com
    ```
    **Important: Required Permissions**

    The job will fail (often with "permissions denied" regarding GCS or worker startup) if the service account you provide does not have the correct IAM roles.

    Ensure your service account has at least these roles:

    *   **Dataflow Worker (`roles/dataflow.worker`):** Allows the worker VMs to interact with the Dataflow service.
    *   **Dataflow Admin (`roles/dataflow.admin`):** Needed if the service account is also used to launch the job.
    *   **Storage Object Admin (`roles/storage.objectAdmin`):** Required to read the template file, read/write to the staging bucket, and handle temp files.
    *   **Bigtable User (`roles/bigtable.user`):** Required to read from and write data to Bigtable.

*   **Job fails to start:** Check the Dataflow job logs for any configuration errors or issues with the control file.
*   **Data not appearing in Bigtable:** Verify the row key and column mappings in the control file are correct. Check the job logs for any transformation errors.
*   **Timeout in polling result file:** This error can occur if the Dataflow worker VMs do not have external IP addresses and cannot connect to Google APIs and services. To resolve this, you need to enable Private Google Access on the subnet used by the VMs. See [Configuring Private Google Access](https://cloud.google.com/vpc/docs/configure-private-google-access) for more details.
*   **Running the job in a non-default subnetwork:** To run the Dataflow job in a specific subnetwork, you need to add the `--subnetwork` parameter to the `gcloud dataflow flex-template run` command in the `scripts/flextemplate-run.sh` script.

    ```bash
    --subnetwork=https://www.googleapis.com/compute/v1/projects/HOST_PROJECT_ID/regions/REGION/subnetworks/SUBNETWORK_NAME
    ```
