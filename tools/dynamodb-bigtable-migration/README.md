# DynamoDB to Bigtable Migration Utility

## Overview

The Bigtable Migration utility efficiently migrates data from a DynamoDB table to a Bigtable table. The utility reads data from a Cloud Storage bucket where the DynamoDB export files are stored. Next, the job transforms the data to ensure compatibility with Bigtable's structure and format, converting [DynamoDB attributes](docs/sample_dynamodb_row.json) to [Bigtable rows](docs/sample_bigtable_row.json). Finally, the job writes the transformed data into a designated Bigtable table.

## Prerequisites

*   **AWS Account:** An active AWS account with the DynamoDB table you want to migrate.
*   **Google Cloud Project:** An active Google Cloud project with a Bigtable instance and table created.
*   **AWS Credentials:** Properly configured AWS credentials with access to your DynamoDB table and S3 bucket.
*   **Google Cloud Credentials:** Properly configured Google Cloud credentials with access to your GCS bucket, Dataflow  and Bigtable instance.
*   **Artifact Registry:** An [Artifact Registry](https://cloud.google.com/artifact-registry/docs/repositories/create-repos#create) repository to store the Docker container image for the template.

*   **`gsutil`:** The `gsutil` command-line tool installed and configured with your Google Cloud credentials.

### DynamoDB

*   **DynamoDB table partition key:** The table must have unique Partition Keys. The utility reads the DynamoDB `Partition Key` and uses it as a `Bigtable Row Key`. Currently, the utility does not consider the DynamoDB Sort Key in mapping row keys.
*   **DynamoDB export:** The utility supports importing DynamoDB Full Exports. It does not support importing Incremental Exports.

### Bigtable

*   **Bigtable Table:** If you don't specify a Bigtable `table` in the environment file, the utility will create one for you.To migrate data to a specific Bigtable table, you need to create the table and at least one column family beforehand. The utility will create `column qualifier` in the specified column family.
*   **Bigtable row key:** The DynamoDB `partition key` will be mapped as the `row key` in Bigtable. Along with the `table name` and `column family`, specify the DynamoDB table `partition key` as a parameter in the .env file.

## Migration Steps

**1. Export Data from DynamoDB table to S3**

*   Navigate to your DynamoDB table in the AWS Management Console.
*   Follow the instructions in the [AWS documentation](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/S3DataExport_Requesting.html) to take a Full Export of your DynamoDB table to an S3 bucket.
*   **Key Configuration Options:**
	*   **S3 Bucket:** Specify the name of your S3 bucket.
	*   **IAM Role:** Ensure the IAM role has write access to the S3 bucket.


	When exporting a DynamoDB table to S3, the data is organized within your S3 bucket using a specific structure. Here's a general representation of that structure:

	```
	my-exports/
	└── AWSDynamoDB/
		└── 1234567890abcdef0123456789abcdef0/
			├── data/
			│   └── 1234567890abcdef0123456789abcdef0-000001.json.gz
			│   └── 1234567890abcdef0123456789abcdef0-000002.json.gz
			│   └── ...
			├── manifest.json
			└── schema.json
	```

	The DynamoDB export data is stored in the `data/` directory.

**2. Transfer Data from S3 to Google Cloud Storage (GCS)**

*   Use the following `gsutil` command to copy the data from your S3 bucket to your GCS bucket:
	```
	gsutil cp -r s3://<your-s3-bucket-name>/AWSDynamoDB/<export-id>/data/ gs://<your-gcs-bucket-name>/<destination-folder>/
	```


**3. Build and deploy the Dataflow flex template to import the data**

*    Before you begin, make sure you have the following installed:

	```
	Java Development Kit (JDK) 11
	Apache Maven 3
	```

*   Clone the repository:

	```
	git clone https://github.com/GoogleCloudPlatform/professional-services.git
	cd ./professional-services/tools/dynamodb-bigtable-migration

	```



*   Create a `.env` file setting the following parameters:
	```
	vi ./scripts/.env
	```

	```bash
	PROJECT_ID=<your-gcp-project-id>
	REGION=<your-gcp-region>

	REPOSITORY=<your-artifact-repo-name>
	IMAGE_NAME=dynamodb-to-bigtable:latest

	BUCKET_NAME=<your-gcs-bucket-name>

	FLEX_TEMPLATE_SPEC_FILE_PATH="gs://${BUCKET_NAME}/templates/dynamodb-to-bigtable.json"
	STAGING_LOCATION="gs://${BUCKET_NAME}/staging"
	TEMP_LOCATION="gs://${BUCKET_NAME}/temp"

	CONTROL_FILE_PATH="gs://${BUCKET_NAME}/config/control.json"
	INPUT_FILEPATH="gs://${BUCKET_NAME}/dynamodb-export/AWSDynamoDB/*.json.gz"

	BIGTABLE_INSTANCE_ID=<your-bigtable-instance-id>
	BIGTABLE_TABLE_ID=<your-bigtable-table-id>
	```

*   **Control File:**

	Create a `control-file.json` and upload it to the GCS path specified in `CONTROL_FILE_PATH`. This file defines the mapping between your DynamoDB data and Bigtable.

	Here is an example `control-file.json`:
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

*   Google Cloud authentication:

	```bash
	gcloud auth application-default login
	export GOOGLE_APPLICATION_CREDENTIALS="/Users/${USER}/.config/gcloud/application_default_credentials.json"
	```

*   Execute the below script from the project root directory to build the flex template:

	```bash
	sh ./scripts/flextemplate-build.sh
	```

	Successful execution of the script will generate the following artifacts:

	*   Docker image in the Artifactory registry
	*   Flex template specification file in the Cloud Storage location

**4. Executing the Flex Template**

*   The `flextemplate-run.sh` script reads the configuration from your `.env` file and starts the Dataflow job. Ensure the `.env` file is up-to-date. The following are the key parameters passed to the pipeline:
	*   `controlFilePath`: The GCS path to the control file.
	*   `inputFilePath`: The GCS path to the DynamoDB export data. This overrides the `sourceFileLocation` in the control file.
	*   `bigtableProjectId`: The GCP project ID of the Bigtable instance.
	*   `bigtableInstanceId`: The ID of the Bigtable instance.
	*   `bigtableTableId`: The ID of the Bigtable table.

*   Execute the below script from the project root directory to run the flex template:

	```
	sh ./scripts/flextemplate-run.sh DYNAMO-BT
	```

*   Once the pipeline is launched, monitor its progress in the Dataflow section of the [Google Cloud Console](https://console.cloud.google.com/dataflow/?hl=en).

*   Check for any errors or warnings during the execution in the [Dataflow Worker logs](https://console.google.com/dataflow/jobs/).
