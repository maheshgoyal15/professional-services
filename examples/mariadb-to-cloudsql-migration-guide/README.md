# MariaDB to Cloud SQL (MySQL) Migration Guide

This repository provides a detailed, step-by-step guide for migrating a MariaDB database to Cloud SQL for MySQL using Google Cloud's Database Migration Service (DMS). Since DMS does not directly support MariaDB as a source, this guide employs a chain replication approach using MySQL as an intermediary.

## Table of Contents

1.  [Prerequisites](#prerequisites)
2.  [MariaDB Setup](#mariadb-setup)
3.  [MySQL Replica Setup](#mysql-replica-setup)
4.  [Chain Replication Configuration](#chain-replication-configuration)
5.  [Cloud SQL for MySQL Instance Creation](#cloud-sql-for-mysql-instance-creation)
6.  [DMS Configuration](#dms-configuration)
7.  [Data Validation](#data-validation)
8.  [Switchover Procedure](#switchover-procedure)
9.  [Troubleshooting](#troubleshooting)

## 1. Prerequisites

* A Google Cloud Platform (GCP) project with billing enabled.
* Sufficient IAM permissions to create Compute Engine instances, Cloud SQL instances, and DMS migration jobs.
* A Virtual Private Cloud (VPC) network with appropriate firewall rules to allow communication between the MariaDB instance, the MySQL replica, and Cloud SQL.

## 2. MariaDB Setup
These steps assume you have a running MariaDB instance.
### 2.1. Create a Sample Database and Table to test the replication

    ```sql
    CREATE DATABASE testdb;

    use testdb;

    CREATE TABLE employees (id INT, name VARCHAR(20), email VARCHAR(20));

    INSERT INTO employees (id,name,email) VALUES(02,"lorna2","lorna2@example.com");
    ```

### 2.2. Configure MariaDB for Replication (my.cnf)

* Edit the `my.cnf` file (e.g., `/etc/mysql/my.cnf.d/mariadb.cnf` or `/etc/my.cnf`) and add/modify the following settings:

    ```
    [mariadb]
    server_id=1
    log_bin = /var/log/mysql/mysql-bin.log
    binlog_do_db = testdb
    binlog_format = row
    bind-address = 10.128.0.3  # Replace with your MariaDB IP address
    log_slave_updates = ON
    ```

* **Important Notes:**
    * `server_id` must be unique across all MariaDB servers in the replication setup.
    * `log_bin` enables binary logging, which is required for replication.
    * `binlog_do_db` specifies the database to be included in the binary log. 
    * `binlog_format = row` is recommended for row-based replication.
    * `bind-address` specifies the IP address that MariaDB listens on. Replace `10.128.0.3` with the actual IP address of your MariaDB server.
    * `log_slave_updates = ON` is needed to enable the MySQL replica to act as a master in the chain replication.

* Restart the MariaDB service:

    ```bash
    sudo systemctl restart mariadb
    ```

### 2.3. Create a Replication User

    ```sql
    mysql -u root -p

    MariaDB > SET SESSION SQL_LOG_BIN=0;

    MariaDB > CREATE USER 'mdb_replica_user'@'%' IDENTIFIED BY 'your_password';  # Replace 'your_password'

    MariaDB > GRANT REPLICATION SLAVE ON *.* TO 'mdb_replica_user'@'%';

    MariaDB > GRANT EXECUTE ON *.* TO 'mdb_replica_user'@'%';

    MariaDB > GRANT SELECT ON *.* TO 'mdb_replica_user'@'%';

    MariaDB > GRANT REPLICATION CLIENT ON *.* TO 'mdb_replica_user'@'%';

    MariaDB > GRANT RELOAD ON *.* TO 'mdb_replica_user'@'%';

    MariaDB > SET SESSION SQL_LOG_BIN=1;

    MariaDB > SHOW GRANTS FOR mdb_replica_user@'%';  # Verify grants
    ```

* **Important Notes:**
    * Replace `your_password` with a strong password.
    * These grants give the replication user the necessary privileges to replicate data.

## 3. MySQL Replica Setup

These steps involve setting up a MySQL server that will act as a replica of your MariaDB server.

### 3.1. Install MySQL

* Install MySQL on a new Compute Engine instance within the same VPC network. Choose a compatible MySQL version (e.g., MySQL 5.7 or 8.0) that is supported by Cloud SQL.

* Here's a general outline for a Debian/Ubuntu-based system:

    ```bash
    sudo apt update
    sudo apt upgrade
    sudo apt install mysql-server
    ```

* During the installation, you'll be prompted to set a root password.

### 3.2. Configure MySQL (my.cnf)

* Edit the MySQL configuration file (e.g., `/etc/mysql/mysql.conf.d/mysqld.cnf`) and add/modify the following settings:

    ```
    [mysqld]
    server-id = 2  # Must be unique
    log_bin = /var/log/mysql/mysql-bin.log
    binlog_do_db = testdb  # Ensure this matches the MariaDB source
    binlog_format = row
    bind-address = 10.128.0.5  # Replace with your MySQL IP address
    relay-log = /var/log/mysql/mysql-relay-bin.log
    log_slave_updates = ON
    sync_binlog = 1
    read_only = 1
    ```

* **Important Notes:**
    * `server_id` must be unique.
    * `binlog_do_db` must match the database name you are replicating from MariaDB.
    * `bind-address` specifies the IP address that MySQL listens on. Replace `10.128.0.5` with the actual IP address of your MySQL server.
    * `relay-log` enables the relay log, which stores events received from the master.
    * `sync_binlog = 1` improves durability by ensuring that binary log transactions are written to disk immediately.
    * `read_only = 1` makes the MySQL instance read-only, which is recommended for the replica until the switchover.

* Restart the MySQL service:

    ```bash
    sudo systemctl restart mysql
    ```

## 4. Chain Replication Configuration

These steps establish the replication link between MariaDB and MySQL.

### 4.1. Take a Backup of MariaDB

* On the MariaDB server, take a consistent backup of the database:

    ```bash
    mysqldump -h 10.128.0.3 -u root -p --master-data=2 --single-transaction --quick --opt testdb > latest.sql  # Replace with your MariaDB connection details
    ```

* **Important Notes:**
    * `--master-data=2` adds a `CHANGE MASTER TO` statement to the dump file, which is crucial for configuring replication.
    * `--single-transaction` ensures a consistent snapshot of the data.

### 4.2. Restore the Backup to MySQL

* Transfer the `latest.sql` file to the MySQL server.
* Restore the database:

    ```bash
    mysql -u root -p testdb < latest.sql
    ```

### 4.3. Configure MySQL as a Replica

* On the MySQL server, configure it to replicate from MariaDB:

    ```sql
    mysql -u root -p

    mysql> SET SESSION SQL_LOG_BIN=0; STOP SLAVE;

    mysql> CHANGE MASTER TO MASTER_HOST='10.128.0.3',  # Replace with your MariaDB IP
                                MASTER_USER='mdb_replica_user',
                                MASTER_PASSWORD='your_password',  # Replace with the password
                                MASTER_LOG_FILE='mysql-bin.000001', # Get from backup
                                MASTER_LOG_POS=4009680;           # Get from backup

    mysql> START SLAVE; SET SESSION SQL_LOG_BIN=1;

    mysql> SHOW SLAVE STATUS\G;  # Verify replication status
    ```

* **Important Notes:**
    * Replace `10.128.0.3`, `mdb_replica_user`, and `your_password` with the correct values.
    * The `MASTER_LOG_FILE` and `MASTER_LOG_POS` values must be obtained from the `CHANGE MASTER TO` statement within the `latest.sql` backup file. You can usually find this near the beginning of the file.

### 4.4. Verify Replication

* Check the replication status on the MySQL server:

    ```sql
    mysql> SHOW SLAVE STATUS\G;
    ```

* Ensure that `Slave_IO_Running` and `Slave_SQL_Running` are both `Yes`.
* Insert some data into the `employees` table on the MariaDB server and verify that it is replicated to the MySQL server.

## 5. Cloud SQL for MySQL Instance Creation

* Create a Cloud SQL for MySQL instance in your GCP project. You can use the Google Cloud Console, the `gcloud` command-line tool, or Terraform.

* **Using `gcloud`:**

    ```bash
    gcloud sql instances create prod-instance --database-version=MYSQL_5_7 --cpu=2 --memory=4GB --region=us-central1 --root-password=your_cloud_sql_root_password
    ```

    * Replace `MYSQL_5_7` (or other version) with the desired MySQL version, `us-central1` with the desired region, and `your_cloud_sql_root_password` with a strong password.

* Ensure that the Cloud SQL instance is accessible from the MySQL replica server. This might involve configuring private connectivity (recommended) or authorizing network access.

## 6. DMS Configuration

These steps involve setting up a Database Migration Service (DMS) job to migrate data from the MySQL replica to the Cloud SQL instance.

### 6.1. Create a Connection Profile

* In the DMS console, create a connection profile for the MySQL replica.
* Provide the connection details, including the MySQL server's IP address, port, username (`dms_replication_user`), and password.
* Ensure that the connection profile test is successful.

### 6.2. Create a Replication User in MySQL Replica

* On the MySQL replica, create a user for DMS:

    ```sql
    mysql -u root -p

    mysql> SET SESSION SQL_LOG_BIN=0;

    mysql> CREATE USER 'dms_replication_user'@'%' IDENTIFIED BY 'your_dms_password'; # Replace your_dms_password

    mysql> GRANT REPLICATION SLAVE ON *.* TO 'dms_replication_user'@'%';

    mysql> GRANT EXECUTE ON *.* TO 'dms_replication_user'@'%';

    mysql> GRANT SELECT ON *.* TO 'dms_replication_user'@'%';

    mysql> GRANT SHOW VIEW ON *.* TO 'dms_replication_user'@'%';

    mysql> GRANT REPLICATION CLIENT ON *.* TO 'dms_replication_user'@'%';

    mysql> GRANT RELOAD ON *.* TO 'dms_replication_user'@'%';

    mysql> SET SESSION SQL_LOG_BIN=1;

    mysql> SHOW GRANTS FOR dms_replication_user@'%';  # Verify grants
    ```

* **Important Notes:**
    * Replace `your_dms_password` with a strong password.
    * These grants give the DMS user the necessary privileges to replicate data.

### 6.3. Create a Migration Job

* In the DMS console, create a new migration job.
* Select the MySQL replica connection profile as the source.
* Select the Cloud SQL for MySQL instance as the destination.
* Configure the migration job settings, such as the migration type (continuous) and any filtering rules.
* Start the migration job.
* Monitor the migration job status in the DMS console.

## 7. Data Validation

* Once the DMS migration job is running, validate the data on the Cloud SQL instance.
* Compare the number of rows and data values between the source MariaDB, the MySQL replica, and the Cloud SQL instance.
* You can insert sample data into the MariaDB source and verify that it is replicated to Cloud SQL.

## 8. Switchover Procedure

* This is the critical step where you transition your application from the source MariaDB to the Cloud SQL instance.

### 8.1. Stop Application Traffic

* Stop all write operations to the MariaDB database.
* Ideally, stop all read operations as well to ensure data consistency during the switchover.

### 8.2. Verify Replication Lag

* Monitor the DMS migration job and ensure that the replication lag is minimal.
* You can also check the replication status on the MySQL replica to confirm that it has caught up with the MariaDB source.

### 8.3. Promote Cloud SQL Instance

* When you are confident that the replication lag is negligible, promote the Cloud SQL instance in the DMS console. This will stop the replication process and make the Cloud SQL instance the primary database.

### 8.4. Update Application Connections

* Update your application's database connection strings to point to the Cloud SQL instance.

### 8.5. Verify Application Functionality

* Thoroughly test your application to ensure that it is functioning correctly with the Cloud SQL instance.

### 8.6. Enable High Availability (Recommended)

* Enable High Availability for your Cloud SQL instance to ensure redundancy and minimize downtime.
* Consider creating read replicas for read scaling.

## 9. Troubleshooting

* **Replication Issues:**
    * Check the MySQL error logs (e.g., `/var/log/mysql/error.log`) and the MariaDB error logs for any replication errors.
    * Verify the network connectivity between the MariaDB instance, the MySQL replica, and Cloud SQL.
    * Ensure that the firewall rules allow traffic on the necessary ports (e.g., 3306).
    * Double-check the replication configuration in `my.cnf` and the `CHANGE MASTER TO` statement.
* **DMS Issues:**
    * Check the DMS migration job logs for any errors.
    * Verify the connection profile configuration.
    * Ensure that the DMS replication user has the necessary privileges.
    * If you encounter issues with DMS, consult the Google Cloud documentation and support resources.
* **General Issues:**
    * Use `ping` and `telnet` to test network connectivity.
    * Use `SHOW PROCESSLIST` to check database connections and queries.
    * Consult the MariaDB, MySQL, and Google Cloud documentation for further assistance.