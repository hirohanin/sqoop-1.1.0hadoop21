/**
 * Licensed to Cloudera, Inc. under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  Cloudera, Inc. licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.cloudera.sqoop.tool;

import java.io.IOException;

import java.math.BigDecimal;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.apache.hadoop.util.StringUtils;

import com.cloudera.sqoop.Sqoop;
import com.cloudera.sqoop.SqoopOptions;
import com.cloudera.sqoop.SqoopOptions.InvalidOptionsException;
import com.cloudera.sqoop.cli.RelatedOptions;
import com.cloudera.sqoop.cli.ToolOptions;
import com.cloudera.sqoop.hive.HiveImport;
import com.cloudera.sqoop.manager.ImportJobContext;

import com.cloudera.sqoop.metastore.JobData;
import com.cloudera.sqoop.metastore.JobStorage;
import com.cloudera.sqoop.metastore.JobStorageFactory;
import com.cloudera.sqoop.util.AppendUtils;
import com.cloudera.sqoop.util.ImportException;
import org.apache.hadoop.fs.Path;

/**
 * Tool that performs database imports to HDFS.
 */
public class ImportTool extends BaseSqoopTool {

  public static final Log LOG = LogFactory.getLog(ImportTool.class.getName());

  private CodeGenTool codeGenerator;

  // true if this is an all-tables import. Set by a subclass which
  // overrides the run() method of this tool (which can only do
  // a single table).
  private boolean allTables;

  public ImportTool() {
    this("import", false);
  }

  public ImportTool(String toolName, boolean allTables) {
    super(toolName);
    this.codeGenerator = new CodeGenTool();
    this.allTables = allTables;
  }

  @Override
  protected boolean init(SqoopOptions sqoopOpts) {
    boolean ret = super.init(sqoopOpts);
    codeGenerator.setManager(manager);
    return ret;
  }

  /**
   * @return a list of jar files generated as part of this import process
   */
  public List<String> getGeneratedJarFiles() {
    return this.codeGenerator.getGeneratedJarFiles();
  }

  /**
   * @return true if the supplied options specify an incremental import.
   */
  private boolean isIncremental(SqoopOptions options) {
    return !options.getIncrementalMode().equals(
        SqoopOptions.IncrementalMode.None);
  }
  
  /**
   * If this is an incremental import, then we should save the
   * user's state back to the metastore (if this job was run
   * from the metastore). Otherwise, log to the user what data
   * they need to supply next time.
   */
  private void saveIncrementalState(SqoopOptions options)
      throws IOException {
    if (!isIncremental(options)) {
      return;
    }

    Map<String, String> descriptor = options.getStorageDescriptor();
    String jobName = options.getJobName();

    if (null != jobName && null != descriptor) {
      // Actually save it back to the metastore.
      LOG.info("Saving incremental import state to the metastore");
      JobStorageFactory ssf = new JobStorageFactory(options.getConf());
      JobStorage storage = ssf.getJobStorage(descriptor);
      storage.open(descriptor);
      try {
        // Save the 'parent' SqoopOptions; this does not contain the mutations
        // to the SqoopOptions state that occurred over the course of this
        // execution, except for the one we specifically want to memorize:
        // the latest value of the check column.
        JobData data = new JobData(options.getParent(), this);
        storage.update(jobName, data);
        LOG.info("Updated data for job: " + jobName);
      } finally {
        storage.close();
      }
    } else {
      // If there wasn't a parent SqoopOptions, then the incremental
      // state data was stored in the current SqoopOptions.
      LOG.info("Incremental import complete! To run another incremental "
          + "import of all data following this import, supply the "
          + "following arguments:");
      SqoopOptions.IncrementalMode incrementalMode =
          options.getIncrementalMode();
      switch (incrementalMode) {
      case AppendRows:
        LOG.info(" --incremental append");
        break;
      case DateLastModified:
        LOG.info(" --incremental lastmodified");
        break;
      default:
        LOG.warn("Undefined incremental mode: " + incrementalMode);
        break;
      }
      LOG.info("  --check-column " + options.getIncrementalTestColumn());
      LOG.info("  --last-value " + options.getIncrementalLastValue());
      LOG.info("(Consider saving this with 'sqoop job --create')");
    }
  }

  /**
   * Return the max value in the incremental-import test column. This
   * value must be numeric.
   */
  private BigDecimal getMaxColumnId(SqoopOptions options) throws SQLException {
    StringBuilder sb = new StringBuilder();
    sb.append("SELECT MAX(");
    sb.append(options.getIncrementalTestColumn());
    sb.append(") FROM ");
    sb.append(options.getTableName());

    String where = options.getWhereClause();
    if (null != where) {
      sb.append(" WHERE ");
      sb.append(where);
    }

    Connection conn = manager.getConnection();
    Statement s = null;
    ResultSet rs = null;
    try {
      s = conn.createStatement();
      rs = s.executeQuery(sb.toString());
      if (!rs.next()) {
        // This probably means the table is empty.
        LOG.warn("Unexpected: empty results for max value query?");
        return null;
      }

      return rs.getBigDecimal(1);
    } finally {
      try {
        if (null != rs) {
          rs.close();
        }
      } catch (SQLException sqlE) {
        LOG.warn("SQL Exception closing resultset: " + sqlE);
      }

      try {
        if (null != s) {
          s.close();
        }
      } catch (SQLException sqlE) {
        LOG.warn("SQL Exception closing statement: " + sqlE);
      }
    }
  }

  /**
   * Initialize the constraints which set the incremental import range.
   * @return false if an import is not necessary, because the dataset has not
   * changed.
   */
  private boolean initIncrementalConstraints(SqoopOptions options,
      ImportJobContext context) throws ImportException, IOException {

    // If this is an incremental import, determine the constraints
    // to inject in the WHERE clause or $CONDITIONS for a query.
    // Also modify the 'last value' field of the SqoopOptions to
    // specify the current job start time / start row.

    if (!isIncremental(options)) {
      return true;
    }

    SqoopOptions.IncrementalMode incrementalMode = options.getIncrementalMode();
    String nextIncrementalValue = null;

    switch (incrementalMode) {
    case AppendRows:
      try {
        BigDecimal nextVal = getMaxColumnId(options);
        if (null != nextVal) {
          nextIncrementalValue = nextVal.toString();
        }
      } catch (SQLException sqlE) {
        throw new IOException(sqlE);
      }
      break;
    case DateLastModified:
      Timestamp dbTimestamp = manager.getCurrentDbTimestamp();
      if (null == dbTimestamp) {
        throw new IOException("Could not get current time from database");
      }

      nextIncrementalValue = manager.timestampToQueryString(dbTimestamp);
      break;
    default:
      throw new ImportException("Undefined incremental import type: "
          + incrementalMode);
    }

    // Build the WHERE clause components that are used to import
    // only this incremental section.
    StringBuilder sb = new StringBuilder();
    String prevEndpoint = options.getIncrementalLastValue();

    if (incrementalMode == SqoopOptions.IncrementalMode.DateLastModified
        && null != prevEndpoint && !prevEndpoint.contains("\'")) {
      // Incremental imports based on timestamps should be 'quoted' in
      // ANSI SQL. If the user didn't specify single-quotes, put them
      // around, here.
      prevEndpoint = "'" + prevEndpoint + "'";
    }

    String checkColName = manager.escapeColName(
        options.getIncrementalTestColumn());
    LOG.info("Incremental import based on column " + checkColName);
    if (null != prevEndpoint) {
      if (prevEndpoint.equals(nextIncrementalValue)) {
        LOG.info("No new rows detected since last import.");
        return false;
      }
      LOG.info("Lower bound value: " + prevEndpoint);
      sb.append(checkColName);
      switch (incrementalMode) {
      case AppendRows:
        sb.append(" > ");
        break;
      case DateLastModified:
        sb.append(" >= ");
        break;
      default:
        throw new ImportException("Undefined comparison");
      }
      sb.append(prevEndpoint);
      sb.append(" AND ");
    }

    if (null != nextIncrementalValue) {
      sb.append(checkColName);
      switch (incrementalMode) {
      case AppendRows:
        sb.append(" <= ");
        break;
      case DateLastModified:
        sb.append(" < ");
        break;
      default:
        throw new ImportException("Undefined comparison");
      }
      sb.append(nextIncrementalValue);
    } else {
      sb.append(checkColName);
      sb.append(" IS NULL ");
    }

    LOG.info("Upper bound value: " + nextIncrementalValue);

    String prevWhereClause = options.getWhereClause();
    if (null != prevWhereClause) {
      sb.append(" AND (");
      sb.append(prevWhereClause);
      sb.append(")");
    }

    String newConstraints = sb.toString();
    options.setWhereClause(newConstraints);

    // Save this state for next time.
    SqoopOptions recordOptions = options.getParent();
    if (null == recordOptions) {
      recordOptions = options;
    }
    recordOptions.setIncrementalLastValue(nextIncrementalValue);

    return true;
  }

  /**
   * Import a table or query.
   * @return true if an import was performed, false otherwise.
   */
  protected boolean importTable(SqoopOptions options, String tableName,
      HiveImport hiveImport) throws IOException, ImportException {
    String jarFile = null;

    // Generate the ORM code for the tables.
    jarFile = codeGenerator.generateORM(options, tableName);

    // Do the actual import.
    ImportJobContext context = new ImportJobContext(tableName, jarFile,
        options, getOutputPath(options, tableName));
    
    // If we're doing an incremental import, set up the
    // filtering conditions used to get the latest records.
    if (!initIncrementalConstraints(options, context)) {
      return false;
    }
    
    if (null != tableName) {
      manager.importTable(context);
    } else {
      manager.importQuery(context);
    }
    
    if (options.isAppendMode()) {
      AppendUtils app = new AppendUtils(context);
      app.append();
    }

    // If the user wants this table to be in Hive, perform that post-load.
    if (options.doHiveImport()) {
      hiveImport.importTable(tableName, options.getHiveTableName(), false);
    }

    saveIncrementalState(options);

    return true;
  }
  
  /**   
   * @return the output path for the imported files;
   * in append mode this will point to a temporary folder.
   * if importing to hbase, this may return null.
   */
  private Path getOutputPath(SqoopOptions options, String tableName) {
    // Get output directory
    String hdfsWarehouseDir = options.getWarehouseDir();
    String hdfsTargetDir = options.getTargetDir();
    Path outputPath = null;
    if (options.isAppendMode()) {
      // Use temporary path, later removed when appending
      outputPath = AppendUtils.getTempAppendDir(tableName);
      LOG.debug("Using temporary folder: " + outputPath.getName());
    } else {
      // Try in this order: target-dir or warehouse-dir 
      if (hdfsTargetDir != null) {
        outputPath = new Path(hdfsTargetDir);
      } else if (hdfsWarehouseDir != null) {
        outputPath = new Path(hdfsWarehouseDir, tableName);
      } else if (null != tableName) {
        outputPath = new Path(tableName);
      }
    }

    return outputPath; 
  }
   
  @Override
  /** {@inheritDoc} */
  public int run(SqoopOptions options) {
    HiveImport hiveImport = null;

    if (allTables) {
      // We got into this method, but we should be in a subclass.
      // (This method only handles a single table)
      // This should not be reached, but for sanity's sake, test here.
      LOG.error("ImportTool.run() can only handle a single table.");
      return 1;
    }

    if (!init(options)) {
      return 1;
    }

    codeGenerator.setManager(manager);

    try {
      if (options.doHiveImport()) {
        hiveImport = new HiveImport(options, manager, options.getConf(), false);
      }

      // Import a single table (or query) the user specified.
      importTable(options, options.getTableName(), hiveImport);
    } catch (IOException ioe) {
      LOG.error("Encountered IOException running import job: "
          + StringUtils.stringifyException(ioe));
      if (System.getProperty(Sqoop.SQOOP_RETHROW_PROPERTY) != null) {
        throw new RuntimeException(ioe);
      } else {
        return 1;
      }
    } catch (ImportException ie) {
      LOG.error("Error during import: " + ie.toString());
      if (System.getProperty(Sqoop.SQOOP_RETHROW_PROPERTY) != null) {
        throw new RuntimeException(ie);
      } else {
        return 1;
      }
    } finally {
      destroy(options);
    }

    return 0;
  }

  /**
   * Construct the set of options that control imports, either of one
   * table or a batch of tables.
   * @return the RelatedOptions that can be used to parse the import
   * arguments.
   */
  protected RelatedOptions getImportOptions() {
    // Imports
    RelatedOptions importOpts = new RelatedOptions("Import control arguments");

    importOpts.addOption(OptionBuilder
        .withDescription("Use direct import fast path")
        .withLongOpt(DIRECT_ARG)
        .create());

    if (!allTables) {
      importOpts.addOption(OptionBuilder.withArgName("table-name")
          .hasArg().withDescription("Table to read")
          .withLongOpt(TABLE_ARG)
          .create());
      importOpts.addOption(OptionBuilder.withArgName("col,col,col...")
          .hasArg().withDescription("Columns to import from table")
          .withLongOpt(COLUMNS_ARG)
          .create());
      importOpts.addOption(OptionBuilder.withArgName("column-name")
          .hasArg()
          .withDescription("Column of the table used to split work units")
          .withLongOpt(SPLIT_BY_ARG)
          .create());
      importOpts.addOption(OptionBuilder.withArgName("where clause")
          .hasArg().withDescription("WHERE clause to use during import")
          .withLongOpt(WHERE_ARG)
          .create());
      importOpts.addOption(OptionBuilder
          .withDescription("Imports data in append mode")
          .withLongOpt(APPEND_ARG)
          .create());        
      importOpts.addOption(OptionBuilder.withArgName("dir")
          .hasArg().withDescription("HDFS plain table destination")
          .withLongOpt(TARGET_DIR_ARG)
          .create());    
      importOpts.addOption(OptionBuilder.withArgName("statement")
          .hasArg()
          .withDescription("Import results of SQL 'statement'")
          .withLongOpt(SQL_QUERY_ARG)
          .create(SQL_QUERY_SHORT_ARG));
    }

    importOpts.addOption(OptionBuilder.withArgName("dir")
        .hasArg().withDescription("HDFS parent for table destination")
        .withLongOpt(WAREHOUSE_DIR_ARG)
        .create());
    importOpts.addOption(OptionBuilder
        .withDescription("Imports data to SequenceFiles")
        .withLongOpt(FMT_SEQUENCEFILE_ARG)
        .create());
    importOpts.addOption(OptionBuilder
        .withDescription("Imports data as plain text (default)")
        .withLongOpt(FMT_TEXTFILE_ARG)
        .create());
    importOpts.addOption(OptionBuilder.withArgName("n")
        .hasArg().withDescription("Use 'n' map tasks to import in parallel")
        .withLongOpt(NUM_MAPPERS_ARG)
        .create(NUM_MAPPERS_SHORT_ARG));
    importOpts.addOption(OptionBuilder
        .withDescription("Enable compression")
        .withLongOpt(COMPRESS_ARG)
        .create(COMPRESS_SHORT_ARG));
    importOpts.addOption(OptionBuilder.withArgName("n")
        .hasArg()
        .withDescription("Split the input stream every 'n' bytes "
        + "when importing in direct mode")
        .withLongOpt(DIRECT_SPLIT_SIZE_ARG)
        .create());
    importOpts.addOption(OptionBuilder.withArgName("n")
        .hasArg()
        .withDescription("Set the maximum size for an inline LOB")
        .withLongOpt(INLINE_LOB_LIMIT_ARG)
        .create());

    return importOpts;
  }

  /**
   * Return options for incremental import.
   */
  protected RelatedOptions getIncrementalOptions() {
    RelatedOptions incrementalOpts =
        new RelatedOptions("Incremental import arguments");

    incrementalOpts.addOption(OptionBuilder.withArgName("import-type")
        .hasArg()
        .withDescription(
        "Define an incremental import of type 'append' or 'lastmodified'")
        .withLongOpt(INCREMENT_TYPE_ARG)
        .create());
    incrementalOpts.addOption(OptionBuilder.withArgName("column")
        .hasArg()
        .withDescription("Source column to check for incremental change")
        .withLongOpt(INCREMENT_COL_ARG)
        .create());
    incrementalOpts.addOption(OptionBuilder.withArgName("value")
        .hasArg()
        .withDescription("Last imported value in the incremental check column")
        .withLongOpt(INCREMENT_LAST_VAL_ARG)
        .create());

    return incrementalOpts;
  }

  @Override
  /** Configure the command-line arguments we expect to receive */
  public void configureOptions(ToolOptions toolOptions) {

    toolOptions.addUniqueOptions(getCommonOptions());
    toolOptions.addUniqueOptions(getImportOptions());
    if (!allTables) {
      toolOptions.addUniqueOptions(getIncrementalOptions());
    }
    toolOptions.addUniqueOptions(getOutputFormatOptions());
    toolOptions.addUniqueOptions(getInputFormatOptions());
    toolOptions.addUniqueOptions(getHiveOptions(true));
    toolOptions.addUniqueOptions(getHBaseOptions());

    // get common codegen opts.
    RelatedOptions codeGenOpts = getCodeGenOpts(allTables);

    // add import-specific codegen opts:
    codeGenOpts.addOption(OptionBuilder.withArgName("file")
        .hasArg()
        .withDescription("Disable code generation; use specified jar")
        .withLongOpt(JAR_FILE_NAME_ARG)
        .create());

    toolOptions.addUniqueOptions(codeGenOpts);
  }

  @Override
  /** {@inheritDoc} */
  public void printHelp(ToolOptions toolOptions) {
    super.printHelp(toolOptions);
    System.out.println("");
    if (allTables) {
      System.out.println("At minimum, you must specify --connect");
    } else {
      System.out.println(
          "At minimum, you must specify --connect and --table");
    } 

    System.out.println(
        "Arguments to mysqldump and other subprograms may be supplied");
    System.out.println(
        "after a '--' on the command line.");
  }

  private void applyIncrementalOptions(CommandLine in, SqoopOptions out)
      throws InvalidOptionsException  {
    if (in.hasOption(INCREMENT_TYPE_ARG)) {
      String incrementalTypeStr = in.getOptionValue(INCREMENT_TYPE_ARG);
      if ("append".equals(incrementalTypeStr)) {
        out.setIncrementalMode(SqoopOptions.IncrementalMode.AppendRows);
        // This argument implies ability to append to the same directory.
        out.setAppendMode(true);
      } else if ("lastmodified".equals(incrementalTypeStr)) {
        out.setIncrementalMode(SqoopOptions.IncrementalMode.DateLastModified);
      } else {
        throw new InvalidOptionsException("Unknown incremental import mode: "
            + incrementalTypeStr + ". Use 'append' or 'lastmodified'."
            + HELP_STR);
      }
    }

    if (in.hasOption(INCREMENT_COL_ARG)) {
      out.setIncrementalTestColumn(in.getOptionValue(INCREMENT_COL_ARG));
    }

    if (in.hasOption(INCREMENT_LAST_VAL_ARG)) {
      out.setIncrementalLastValue(in.getOptionValue(INCREMENT_LAST_VAL_ARG));
    }
  }

  @Override
  /** {@inheritDoc} */
  public void applyOptions(CommandLine in, SqoopOptions out)
      throws InvalidOptionsException {

    try {
      applyCommonOptions(in, out);

      if (in.hasOption(DIRECT_ARG)) {
        out.setDirectMode(true);
      }

      if (!allTables) {
        if (in.hasOption(TABLE_ARG)) {
          out.setTableName(in.getOptionValue(TABLE_ARG));
        }

        if (in.hasOption(COLUMNS_ARG)) {
          out.setColumns(in.getOptionValue(COLUMNS_ARG).split(","));
        }

        if (in.hasOption(SPLIT_BY_ARG)) {
          out.setSplitByCol(in.getOptionValue(SPLIT_BY_ARG));
        }

        if (in.hasOption(WHERE_ARG)) {
          out.setWhereClause(in.getOptionValue(WHERE_ARG));
        }

        if (in.hasOption(TARGET_DIR_ARG)) {
          out.setTargetDir(in.getOptionValue(TARGET_DIR_ARG));
        }
        
        if (in.hasOption(APPEND_ARG)) {
          out.setAppendMode(true);
        }

        if (in.hasOption(SQL_QUERY_ARG)) {
          out.setSqlQuery(in.getOptionValue(SQL_QUERY_ARG));
        }
      }

      if (in.hasOption(WAREHOUSE_DIR_ARG)) {
        out.setWarehouseDir(in.getOptionValue(WAREHOUSE_DIR_ARG));
      }

      if (in.hasOption(FMT_SEQUENCEFILE_ARG)) {
        out.setFileLayout(SqoopOptions.FileLayout.SequenceFile);
      }

      if (in.hasOption(FMT_TEXTFILE_ARG)) {
        out.setFileLayout(SqoopOptions.FileLayout.TextFile);
      }

      if (in.hasOption(NUM_MAPPERS_ARG)) {
        out.setNumMappers(Integer.parseInt(in.getOptionValue(NUM_MAPPERS_ARG)));
      }

      if (in.hasOption(COMPRESS_ARG)) {
        out.setUseCompression(true);
      }

      if (in.hasOption(DIRECT_SPLIT_SIZE_ARG)) {
        out.setDirectSplitSize(Long.parseLong(in.getOptionValue(
            DIRECT_SPLIT_SIZE_ARG)));
      }

      if (in.hasOption(INLINE_LOB_LIMIT_ARG)) {
        out.setInlineLobLimit(Long.parseLong(in.getOptionValue(
            INLINE_LOB_LIMIT_ARG)));
      }

      if (in.hasOption(JAR_FILE_NAME_ARG)) {
        out.setExistingJarName(in.getOptionValue(JAR_FILE_NAME_ARG));
      }

      applyIncrementalOptions(in, out);
      applyHiveOptions(in, out);
      applyOutputFormatOptions(in, out);
      applyInputFormatOptions(in, out);
      applyCodeGenOptions(in, out, allTables);
      applyHBaseOptions(in, out);
    } catch (NumberFormatException nfe) {
      throw new InvalidOptionsException("Error: expected numeric argument.\n"
          + "Try --help for usage.");
    }
  }

  /**
   * Validate import-specific arguments.
   * @param options the configured SqoopOptions to check
   */
  protected void validateImportOptions(SqoopOptions options)
      throws InvalidOptionsException {
    if (!allTables && options.getTableName() == null
        && options.getSqlQuery() == null) {
      throw new InvalidOptionsException(
          "--table or --" + SQL_QUERY_ARG + " is required for import. "
          + "(Or use sqoop import-all-tables.)"
          + HELP_STR);
    } else if (options.getExistingJarName() != null
        && options.getClassName() == null) {
      throw new InvalidOptionsException("Jar specified with --jar-file, but no "
          + "class specified with --class-name." + HELP_STR);
    } else if (options.getTargetDir() != null
        && options.getWarehouseDir() != null) {
      throw new InvalidOptionsException(
          "--target-dir with --warehouse-dir are incompatible options."
          + HELP_STR);
    } else if (options.getTableName() != null
        && options.getSqlQuery() != null) {
      throw new InvalidOptionsException(
          "Cannot specify --" + SQL_QUERY_ARG + " and --table together."
          + HELP_STR);
    } else if (options.getSqlQuery() != null
        && options.getTargetDir() == null && options.getHBaseTable() == null) {
      throw new InvalidOptionsException(
          "Must specify destination with --target-dir."
          + HELP_STR);
    } else if (options.getSqlQuery() != null && options.doHiveImport()
        && options.getHiveTableName() == null) {
      throw new InvalidOptionsException(
          "When importing a query to Hive, you must specify --"
          + HIVE_TABLE_ARG + "." + HELP_STR);
    } else if (options.getSqlQuery() != null && options.getNumMappers() > 1
        && options.getSplitByCol() == null) {
      throw new InvalidOptionsException(
          "When importing query results in parallel, you must specify --"
          + SPLIT_BY_ARG + "." + HELP_STR);
    }
  }

  /**
   * Validate the incremental import options.
   */
  private void validateIncrementalOptions(SqoopOptions options)
      throws InvalidOptionsException {
    if (options.getIncrementalMode() != SqoopOptions.IncrementalMode.None
        && options.getIncrementalTestColumn() == null) {
      throw new InvalidOptionsException(
          "For an incremental import, the check column must be specified "
          + "with --" + INCREMENT_COL_ARG + ". " + HELP_STR);
    }

    if (options.getIncrementalMode() == SqoopOptions.IncrementalMode.None
        && options.getIncrementalTestColumn() != null) {
      throw new InvalidOptionsException(
          "You must specify an incremental import mode with --"
          + INCREMENT_TYPE_ARG + ". " + HELP_STR);
    }

    if (options.getIncrementalMode() != SqoopOptions.IncrementalMode.None
        && options.getTableName() == null) {
      throw new InvalidOptionsException("Incremental imports require a table."
          + HELP_STR);
    }
  }

  @Override
  /** {@inheritDoc} */
  public void validateOptions(SqoopOptions options)
      throws InvalidOptionsException {

    // If extraArguments is full, check for '--' followed by args for
    // mysqldump or other commands we rely on.
    options.setExtraArgs(getSubcommandArgs(extraArguments));
    int dashPos = getDashPosition(extraArguments);

    if (hasUnrecognizedArgs(extraArguments, 0, dashPos)) {
      throw new InvalidOptionsException(HELP_STR);
    }

    validateImportOptions(options);
    validateIncrementalOptions(options);
    validateCommonOptions(options);
    validateCodeGenOptions(options);
    validateOutputFormatOptions(options);
    validateHBaseOptions(options);
  }
}

