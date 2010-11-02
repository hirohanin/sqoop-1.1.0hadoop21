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

package com.cloudera.sqoop.manager;

import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.SQLException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.cloudera.sqoop.SqoopOptions;
import com.cloudera.sqoop.util.ImportException;

/**
 * Manages connections to Postgresql databases.
 */
public class PostgresqlManager extends GenericJdbcManager {

  public static final Log LOG = LogFactory.getLog(
      PostgresqlManager.class.getName());

  // driver class to ensure is loaded when making db connection.
  private static final String DRIVER_CLASS = "org.postgresql.Driver";

  // Fetch 50 rows at a time.
  private static final int POSTGRESQL_FETCH_SIZE = 50;

  // set to true after we warn the user that we can use direct fastpath.
  private static boolean warningPrinted = false;

  private Statement lastStatement;

  public PostgresqlManager(final SqoopOptions opts) {
    super(DRIVER_CLASS, opts);
  }

  protected PostgresqlManager(final SqoopOptions opts, boolean ignored) {
    // constructor used by subclasses to avoid the --direct warning.
    super(DRIVER_CLASS, opts);
  }

  @Override
  public void close() throws SQLException {
    if (this.hasOpenConnection()) {
      this.getConnection().commit(); // Commit any changes made thus far.
    }

    super.close();
  }

  @Override
  protected String getColNamesQuery(String tableName) {
    // Use LIMIT to return fast
    return "SELECT t.* FROM " + escapeTableName(tableName) + " AS t LIMIT 1";
  }

  @Override
  public void importTable(ImportJobContext context)
        throws IOException, ImportException {

    // The user probably should have requested --direct to invoke pg_dump.
    // Display a warning informing them of this fact.
    if (!PostgresqlManager.warningPrinted) {
      LOG.warn("It looks like you are importing from postgresql.");
      LOG.warn("This transfer can be faster! Use the --direct");
      LOG.warn("option to exercise a postgresql-specific fast path.");

      PostgresqlManager.warningPrinted = true; // don't display this twice.
    }

    // Then run the normal importTable() method.
    super.importTable(context);
  }

  @Override
  public String getPrimaryKey(String tableName) {
    // Postgresql stores table names using lower-case internally; need
    // to always convert to lowercase before querying the metadata dictionary.
    return super.getPrimaryKey(tableName.toLowerCase());
  }

  /**
   * Executes an arbitrary SQL statement. Sets the cursor fetch size
   * to ensure the entire table is not buffered in RAM before reading
   * any rows. A consequence of this is that every ResultSet returned
   * by this method *MUST* be close()'d, or read to exhaustion before
   * another query can be executed from this ConnManager instance.
   *
   * @param stmt The SQL statement to execute
   * @return A ResultSet encapsulating the results or null on error
   */
  protected ResultSet execute(String stmt, Object... args) throws SQLException {
    // Free any previous resources used.
    release();

    PreparedStatement statement = null;
    statement = this.getConnection().prepareStatement(stmt,
        ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
    this.lastStatement = statement;
    statement.setFetchSize(POSTGRESQL_FETCH_SIZE);
    if (null != args) {
      for (int i = 0; i < args.length; i++) {
        statement.setObject(i + 1, args[i]);
      }
    }

    LOG.info("Executing SQL statement: " + stmt);
    return statement.executeQuery();
  }

  public void release() {
    if (null != this.lastStatement) {
      try {
        this.lastStatement.close();
      } catch (SQLException e) {
        LOG.warn("Exception closing executed Statement: " + e);
      }

      this.lastStatement = null;
    }
  }
}

