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
import java.util.ArrayList;
import java.util.List;

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
import com.cloudera.sqoop.orm.ClassWriter;
import com.cloudera.sqoop.orm.CompilationManager;

/**
 * Tool that generates code from a database schema.
 */
public class CodeGenTool extends BaseSqoopTool {

  public static final Log LOG = LogFactory.getLog(CodeGenTool.class.getName());

  private List<String> generatedJarFiles;

  public CodeGenTool() {
    super("codegen");
    generatedJarFiles = new ArrayList<String>();
  }

  /**
   * @return a list of jar files generated as part of this import process
   */
  public List<String> getGeneratedJarFiles() {
    ArrayList<String> out = new ArrayList<String>(generatedJarFiles);
    return out;
  }

  /**
   * Generate the .class and .jar files.
   * @return the filename of the emitted jar file.
   * @throws IOException
   */
  public String generateORM(SqoopOptions options, String tableName)
      throws IOException {
    String existingJar = options.getExistingJarName();
    if (existingJar != null) {
      // This code generator is being invoked as part of an import or export
      // process, and the user has pre-specified a jar and class to use.
      // Don't generate.
      LOG.info("Using existing jar: " + existingJar);
      return existingJar;
    }

    LOG.info("Beginning code generation");
    CompilationManager compileMgr = new CompilationManager(options);
    ClassWriter classWriter = new ClassWriter(options, manager, tableName,
        compileMgr);
    classWriter.generate();
    compileMgr.compile();
    compileMgr.jar();
    String jarFile = compileMgr.getJarFilename();
    this.generatedJarFiles.add(jarFile);
    return jarFile;
  }


  @Override
  /** {@inheritDoc} */
  public int run(SqoopOptions options) {
    if (!init(options)) {
      return 1;
    }

    try {
      generateORM(options, options.getTableName());

      // If the user has also specified Hive import code generation,
      // use a HiveImport to generate the DDL statements and write
      // them to files (but don't actually perform the import -- thus
      // the generateOnly=true in the constructor).
      if (options.doHiveImport()) {
        HiveImport hiveImport = new HiveImport(options, manager,
            options.getConf(), true);
        hiveImport.importTable(options.getTableName(),
            options.getHiveTableName(), true);
      }

    } catch (IOException ioe) {
      LOG.error("Encountered IOException running codegen job: "
          + StringUtils.stringifyException(ioe));
      if (System.getProperty(Sqoop.SQOOP_RETHROW_PROPERTY) != null) {
        throw new RuntimeException(ioe);
      } else {
        return 1;
      }
    } finally {
      destroy(options);
    }

    return 0;
  }

  @Override
  /** Configure the command-line arguments we expect to receive */
  public void configureOptions(ToolOptions toolOptions) {

    toolOptions.addUniqueOptions(getCommonOptions());

    RelatedOptions codeGenOpts = getCodeGenOpts(false);
    codeGenOpts.addOption(OptionBuilder.withArgName("table-name")
        .hasArg()
        .withDescription("Table to generate code for")
        .withLongOpt(TABLE_ARG)
        .create());
    toolOptions.addUniqueOptions(codeGenOpts);

    toolOptions.addUniqueOptions(getOutputFormatOptions());
    toolOptions.addUniqueOptions(getInputFormatOptions());
    toolOptions.addUniqueOptions(getHiveOptions(true));
  }

  @Override
  /** {@inheritDoc} */
  public void printHelp(ToolOptions toolOptions) {
    super.printHelp(toolOptions);
    System.out.println("");
    System.out.println(
        "At minimum, you must specify --connect and --table");
  }

  @Override
  /** {@inheritDoc} */
  public void applyOptions(CommandLine in, SqoopOptions out)
      throws InvalidOptionsException {

    if (in.hasOption(TABLE_ARG)) {
      out.setTableName(in.getOptionValue(TABLE_ARG));
    }

    applyCommonOptions(in, out);
    applyOutputFormatOptions(in, out);
    applyInputFormatOptions(in, out);
    applyCodeGenOptions(in, out, false);
    applyHiveOptions(in, out);
  }

  @Override
  /** {@inheritDoc} */
  public void validateOptions(SqoopOptions options)
      throws InvalidOptionsException {

    if (hasUnrecognizedArgs(extraArguments)) {
      throw new InvalidOptionsException(HELP_STR);
    }

    validateCommonOptions(options);
    validateCodeGenOptions(options);
    validateOutputFormatOptions(options);
    validateHiveOptions(options);

    if (options.getTableName() == null) {
      throw new InvalidOptionsException(
          "--table is required for code generation." + HELP_STR);
    }
  }
}

