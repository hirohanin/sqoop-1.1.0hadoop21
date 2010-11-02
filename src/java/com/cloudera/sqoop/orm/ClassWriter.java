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

package com.cloudera.sqoop.orm;

import org.apache.hadoop.io.BytesWritable;
import com.cloudera.sqoop.SqoopOptions;
import com.cloudera.sqoop.manager.ConnManager;
import com.cloudera.sqoop.manager.SqlManager;
import com.cloudera.sqoop.lib.BigDecimalSerializer;
import com.cloudera.sqoop.lib.BooleanParser;
import com.cloudera.sqoop.lib.DelimiterSet;
import com.cloudera.sqoop.lib.FieldFormatter;
import com.cloudera.sqoop.lib.JdbcWritableBridge;
import com.cloudera.sqoop.lib.LargeObjectLoader;
import com.cloudera.sqoop.lib.LobSerializer;
import com.cloudera.sqoop.lib.RecordParser;
import com.cloudera.sqoop.lib.BlobRef;
import com.cloudera.sqoop.lib.ClobRef;
import com.cloudera.sqoop.lib.SqoopRecord;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.HashSet;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Creates an ORM class to represent a table from a database.
 */
public class ClassWriter {

  public static final Log LOG = LogFactory.getLog(ClassWriter.class.getName());

  // The following are keywords and cannot be used for class, method, or field
  // names.
  public static final HashSet<String> JAVA_RESERVED_WORDS;

  static {
    JAVA_RESERVED_WORDS = new HashSet<String>();

    JAVA_RESERVED_WORDS.add("abstract");
    JAVA_RESERVED_WORDS.add("else");
    JAVA_RESERVED_WORDS.add("int");
    JAVA_RESERVED_WORDS.add("strictfp");
    JAVA_RESERVED_WORDS.add("assert");
    JAVA_RESERVED_WORDS.add("enum");
    JAVA_RESERVED_WORDS.add("interface");
    JAVA_RESERVED_WORDS.add("super");
    JAVA_RESERVED_WORDS.add("boolean");
    JAVA_RESERVED_WORDS.add("extends");
    JAVA_RESERVED_WORDS.add("long");
    JAVA_RESERVED_WORDS.add("switch");
    JAVA_RESERVED_WORDS.add("break");
    JAVA_RESERVED_WORDS.add("false");
    JAVA_RESERVED_WORDS.add("native");
    JAVA_RESERVED_WORDS.add("synchronized");
    JAVA_RESERVED_WORDS.add("byte");
    JAVA_RESERVED_WORDS.add("final");
    JAVA_RESERVED_WORDS.add("new");
    JAVA_RESERVED_WORDS.add("this");
    JAVA_RESERVED_WORDS.add("case");
    JAVA_RESERVED_WORDS.add("finally");
    JAVA_RESERVED_WORDS.add("null");
    JAVA_RESERVED_WORDS.add("throw");
    JAVA_RESERVED_WORDS.add("catch");
    JAVA_RESERVED_WORDS.add("float");
    JAVA_RESERVED_WORDS.add("package");
    JAVA_RESERVED_WORDS.add("throws");
    JAVA_RESERVED_WORDS.add("char");
    JAVA_RESERVED_WORDS.add("for");
    JAVA_RESERVED_WORDS.add("private");
    JAVA_RESERVED_WORDS.add("transient");
    JAVA_RESERVED_WORDS.add("class");
    JAVA_RESERVED_WORDS.add("goto");
    JAVA_RESERVED_WORDS.add("protected");
    JAVA_RESERVED_WORDS.add("true");
    JAVA_RESERVED_WORDS.add("const");
  }

  /**
   * This version number is injected into all generated Java classes to denote
   * which version of the ClassWriter's output format was used to generate the
   * class.
   *
   * If the way that we generate classes changes, bump this number.
   * This number is retrieved by the SqoopRecord.getClassFormatVersion()
   * method.
   */
  public static final int CLASS_WRITER_VERSION = 3;

  private SqoopOptions options;
  private ConnManager connManager;
  private String tableName;
  private CompilationManager compileManager;

  /**
   * Creates a new ClassWriter to generate an ORM class for a table
   * or arbitrary query.
   * @param opts program-wide options
   * @param connMgr the connection manager used to describe the table.
   * @param table the name of the table to read. If null, query is taken
   * from the SqoopOptions.
   */
  public ClassWriter(final SqoopOptions opts, final ConnManager connMgr,
      final String table, final CompilationManager compMgr) {
    this.options = opts;
    this.connManager = connMgr;
    this.tableName = table;
    this.compileManager = compMgr;
  }

  /**
   * Given some character that can't be in an identifier,
   * try to map it to a string that can.
   *
   * @param c a character that can't be in a Java identifier
   * @return a string of characters that can, or null if there's
   * no good translation.
   */
  static String getIdentifierStrForChar(char c) {
    if (Character.isJavaIdentifierPart(c)) {
      return "" + c;
    } else if (Character.isWhitespace(c)) {
      // Eliminate whitespace.
      return null;
    } else {
      // All other characters map to underscore.
      return "_";
    }
  }

  /**
   * @param word a word to test.
   * @return true if 'word' is reserved the in Java language.
   */
  private static boolean isReservedWord(String word) {
    return JAVA_RESERVED_WORDS.contains(word);
  }

  /**
   * Coerce a candidate name for an identifier into one which will
   * definitely compile.
   *
   * Ensures that the returned identifier matches [A-Za-z_][A-Za-z0-9_]*
   * and is not a reserved word.
   *
   * @param candidate A string we want to use as an identifier
   * @return A string naming an identifier which compiles and is
   *   similar to the candidate.
   */
  public static String toIdentifier(String candidate) {
    StringBuilder sb = new StringBuilder();
    boolean first = true;
    for (char c : candidate.toCharArray()) {
      if (Character.isJavaIdentifierStart(c) && first) {
        // Ok for this to be the first character of the identifier.
        sb.append(c);
        first = false;
      } else if (Character.isJavaIdentifierPart(c) && !first) {
        // Ok for this character to be in the output identifier.
        sb.append(c);
      } else {
        // We have a character in the original that can't be
        // part of this identifier we're building.
        // If it's just not allowed to be the first char, add a leading '_'.
        // If we have a reasonable translation (e.g., '-' -> '_'), do that.
        // Otherwise, drop it.
        if (first && Character.isJavaIdentifierPart(c)
            && !Character.isJavaIdentifierStart(c)) {
          sb.append("_");
          sb.append(c);
          first = false;
        } else {
          // Try to map this to a different character or string.
          // If we can't just give up.
          String translated = getIdentifierStrForChar(c);
          if (null != translated) {
            sb.append(translated);
            first = false;
          }
        }
      }
    }

    String output = sb.toString();
    if (isReservedWord(output)) {
      // e.g., 'class' -> '_class';
      return "_" + output;
    }

    return output;
  }

  /**
   * @param javaType
   * @return the name of the method of JdbcWritableBridge to read an entry
   * with a given java type.
   */
  private String dbGetterForType(String javaType) {
    // All Class-based types (e.g., java.math.BigDecimal) are handled with
    // "readBar" where some.package.foo.Bar is the canonical class name.  Turn
    // the javaType string into the getter type string.

    String [] parts = javaType.split("\\.");
    if (parts.length == 0) {
      LOG.error("No ResultSet method for Java type " + javaType);
      return null;
    }

    String lastPart = parts[parts.length - 1];
    try {
      String getter = "read" + Character.toUpperCase(lastPart.charAt(0))
          + lastPart.substring(1);
      return getter;
    } catch (StringIndexOutOfBoundsException oob) {
      // lastPart.*() doesn't work on empty strings.
      LOG.error("Could not infer JdbcWritableBridge getter for Java type "
          + javaType);
      return null;
    }
  }

  /**
   * @param javaType
   * @return the name of the method of JdbcWritableBridge to write an entry
   * with a given java type.
   */
  private String dbSetterForType(String javaType) {
    // TODO(aaron): Lots of unit tests needed here.
    // See dbGetterForType() for the logic used here; it's basically the same.

    String [] parts = javaType.split("\\.");
    if (parts.length == 0) {
      LOG.error("No PreparedStatement Set method for Java type " + javaType);
      return null;
    }

    String lastPart = parts[parts.length - 1];
    try {
      String setter = "write" + Character.toUpperCase(lastPart.charAt(0))
          + lastPart.substring(1);
      return setter;
    } catch (StringIndexOutOfBoundsException oob) {
      // lastPart.*() doesn't work on empty strings.
      LOG.error("Could not infer PreparedStatement setter for Java type "
          + javaType);
      return null;
    }
  }

  private String stringifierForType(String javaType, String colName) {
    if (javaType.equals("String")) {
      return colName;
    } else {
      // This is an object type -- just call its toString() in a null-safe way.
      return "\"\" + " + colName;
    }
  }

  /**
   * @param javaType the type to read
   * @param inputObj the name of the DataInput to read from
   * @param colName the column name to read
   * @return the line of code involving a DataInput object to read an entry
   * with a given java type.
   */
  private String rpcGetterForType(String javaType, String inputObj,
      String colName) {
    if (javaType.equals("Integer")) {
      return "    this." + colName + " = Integer.valueOf(" + inputObj
          + ".readInt());\n";
    } else  if (javaType.equals("java.lang.Object")) {
        return "    this." + colName + " = (java.lang.Object)" + inputObj
        + ";\n";
    }else if (javaType.equals("Long")) {
      return "    this." + colName + " = Long.valueOf(" + inputObj
          + ".readLong());\n";
    } else if (javaType.equals("Float")) {
      return "    this." + colName + " = Float.valueOf(" + inputObj
          + ".readFloat());\n";
    } else if (javaType.equals("Double")) {
      return "    this." + colName + " = Double.valueOf(" + inputObj
          + ".readDouble());\n";
    } else if (javaType.equals("Boolean")) {
      return "    this." + colName + " = Boolean.valueOf(" + inputObj
          + ".readBoolean());\n";
    } else if (javaType.equals("String")) {
      return "    this." + colName + " = Text.readString(" + inputObj + ");\n";
    } else if (javaType.equals("java.sql.Date")) {
      return "    this." + colName + " = new Date(" + inputObj
          + ".readLong());\n";
    } else if (javaType.equals("java.sql.Time")) {
      return "    this." + colName + " = new Time(" + inputObj
          + ".readLong());\n";
    } else if (javaType.equals("java.sql.Timestamp")) {
      return "    this." + colName + " = new Timestamp(" + inputObj
          + ".readLong());\n" + "    this." + colName + ".setNanos("
          + inputObj + ".readInt());\n";
    } else if (javaType.equals("java.math.BigDecimal")) {
      return "    this." + colName + " = "
          + BigDecimalSerializer.class.getCanonicalName()
          + ".readFields(" + inputObj + ");\n";
    } else if (javaType.equals(ClobRef.class.getName())) {
      return "    this." + colName + " = "
          + LobSerializer.class.getCanonicalName()
          + ".readClobFields(" + inputObj + ");\n";
    } else if (javaType.equals(BlobRef.class.getName())) {
      return "    this." + colName + " = "
          + LobSerializer.class.getCanonicalName()
          + ".readBlobFields(" + inputObj + ");\n";
    } else if (javaType.equals(BytesWritable.class.getName())) {
      return "    this." + colName + " = new BytesWritable();\n"
          + "    this." + colName + ".readFields(" + inputObj + ");\n";
    } else {
      LOG.error("No ResultSet method for Java type " + javaType);
      return null;
    }
  }

  /**
   * Deserialize a possibly-null value from the DataInput stream.
   * @param javaType name of the type to deserialize if it's not null.
   * @param inputObj name of the DataInput to read from
   * @param colName the column name to read.
   * @return
   */
  private String rpcGetterForMaybeNull(String javaType, String inputObj,
      String colName) {
    return "    if (" + inputObj + ".readBoolean()) { \n"
        + "        this." + colName + " = null;\n"
        + "    } else {\n"
        + rpcGetterForType(javaType, inputObj, colName)
        + "    }\n";
  }

  /**
   * @param javaType the type to write
   * @param inputObj the name of the DataOutput to write to
   * @param colName the column name to write
   * @return the line of code involving a DataOutput object to write an entry
   * with a given java type.
   */
  private String rpcSetterForType(String javaType, String outputObj,
      String colName) {
    if (javaType.equals("Integer")) {
      return "    " + outputObj + ".writeInt(this." + colName + ");\n";
    } else if (javaType.equals("java.lang.Object")) {
      return "    Text.writeString(" + outputObj + ", " + colName + ".toString());\n";   
    }else if (javaType.equals("Long")) {
      return "    " + outputObj + ".writeLong(this." + colName + ");\n";
    } else if (javaType.equals("Boolean")) {
      return "    " + outputObj + ".writeBoolean(this." + colName + ");\n";
    } else if (javaType.equals("Float")) {
      return "    " + outputObj + ".writeFloat(this." + colName + ");\n";
    } else if (javaType.equals("Double")) {
      return "    " + outputObj + ".writeDouble(this." + colName + ");\n";
    } else if (javaType.equals("String")) {
      return "    Text.writeString(" + outputObj + ", " + colName + ");\n";
    } else if (javaType.equals("java.sql.Date")) {
      return "    " + outputObj + ".writeLong(this." + colName
          + ".getTime());\n";
    } else if (javaType.equals("java.sql.Time")) {
      return "    " + outputObj + ".writeLong(this." + colName
          + ".getTime());\n";
    } else if (javaType.equals("java.sql.Timestamp")) {
      return "    " + outputObj + ".writeLong(this." + colName
          + ".getTime());\n" + "    " + outputObj + ".writeInt(this." + colName
          + ".getNanos());\n";
    } else if (javaType.equals(BytesWritable.class.getName())) {
      return "    this." + colName + ".write(" + outputObj + ");\n";
    } else if (javaType.equals("java.math.BigDecimal")) {
      return "    " + BigDecimalSerializer.class.getCanonicalName()
          + ".write(this." + colName + ", " + outputObj + ");\n";
    } else if (javaType.equals(ClobRef.class.getName())) {
      return "    " + LobSerializer.class.getCanonicalName()
          + ".writeClob(this." + colName + ", " + outputObj + ");\n";
    } else if (javaType.equals(BlobRef.class.getName())) {
      return "    " + LobSerializer.class.getCanonicalName()
          + ".writeBlob(this." + colName + ", " + outputObj + ");\n";
    } else {
      LOG.error("No ResultSet method for Java type " + javaType);
      return null;
    }
  }

  /**
   * Serialize a possibly-null value to the DataOutput stream. First a boolean
   * isNull is written, followed by the contents itself (if not null).
   * @param javaType name of the type to deserialize if it's not null.
   * @param inputObj name of the DataInput to read from
   * @param colName the column name to read.
   * @return
   */
  private String rpcSetterForMaybeNull(String javaType, String outputObj,
      String colName) {
    return "    if (null == this." + colName + ") { \n"
        + "        " + outputObj + ".writeBoolean(true);\n"
        + "    } else {\n"
        + "        " + outputObj + ".writeBoolean(false);\n"
        + rpcSetterForType(javaType, outputObj, colName)
        + "    }\n";
  }

  /**
   * Generate a member field and getter method for each column.
   * @param columnTypes - mapping from column names to sql types
   * @param colNames - ordered list of column names for table.
   * @param sb - StringBuilder to append code to
   */
  private void generateFields(Map<String, Integer> columnTypes,
      String [] colNames, StringBuilder sb) {

    for (String col : colNames) {
      int sqlType = columnTypes.get(col);
      String javaType = connManager.toJavaType(sqlType);
      if (null == javaType) {
        LOG.error("Cannot resolve SQL type " + sqlType);
        continue;
      }

      sb.append("  private " + javaType + " " + col + ";\n");
      sb.append("  public " + javaType + " get_" + col + "() {\n");
      sb.append("    return " + col + ";\n");
      sb.append("  }\n");
    }
  }

  /**
   * Generate the readFields() method used by the database.
   * @param columnTypes - mapping from column names to sql types
   * @param colNames - ordered list of column names for table.
   * @param sb - StringBuilder to append code to
   */
  private void generateDbRead(Map<String, Integer> columnTypes,
      String [] colNames, StringBuilder sb) {

    sb.append("  public void readFields(ResultSet __dbResults) ");
    sb.append("throws SQLException {\n");

    // Save ResultSet object cursor for use in LargeObjectLoader
    // if necessary.
    sb.append("    this.__cur_result_set = __dbResults;\n");

    int fieldNum = 0;

    for (String col : colNames) {
      fieldNum++;

      int sqlType = columnTypes.get(col);
      String javaType = connManager.toJavaType(sqlType);
      if (null == javaType) {
        LOG.error("No Java type for SQL type " + sqlType);
        continue;
      }

      String getterMethod = dbGetterForType(javaType);
      if (null == getterMethod) {
        LOG.error("No db getter method for Java type " + javaType);
        continue;
      }

      sb.append("    this." + col + " = JdbcWritableBridge." +  getterMethod
          + "(" + fieldNum + ", __dbResults);\n");
    }

    sb.append("  }\n");
  }

  /**
   * Generate the loadLargeObjects() method called by the mapper to load
   * delayed objects (that require the Context from the mapper).
   */
  private void generateLoadLargeObjects(Map<String, Integer> columnTypes,
      String [] colNames, StringBuilder sb) {

    // This method relies on the __cur_result_set field being set by
    // readFields() method generated by generateDbRead().

    sb.append("  public void loadLargeObjects(LargeObjectLoader __loader)\n");
    sb.append("      throws SQLException, IOException, ");
    sb.append("InterruptedException {\n");

    int fieldNum = 0;

    for (String col : colNames) {
      fieldNum++;

      int sqlType = columnTypes.get(col);
      String javaType = connManager.toJavaType(sqlType);
      if (null == javaType) {
        LOG.error("No Java type for SQL type " + sqlType);
        continue;
      }

      String getterMethod = dbGetterForType(javaType);
      if ("readClobRef".equals(getterMethod)
          || "readBlobRef".equals(getterMethod)) {
        // This field is a blob/clob field with delayed loading.  Call the
        // appropriate LargeObjectLoader method (which has the same name as a
        // JdbcWritableBridge method).
        sb.append("    this." + col + " = __loader." + getterMethod
            + "(" + fieldNum + ", this.__cur_result_set);\n");
      }
    }
    sb.append("  }\n");
  }


  /**
   * Generate the write() method used by the database.
   * @param columnTypes - mapping from column names to sql types
   * @param colNames - ordered list of column names for table.
   * @param sb - StringBuilder to append code to
   */
  private void generateDbWrite(Map<String, Integer> columnTypes,
      String [] colNames, StringBuilder sb) {

    sb.append("  public void write(PreparedStatement __dbStmt) "
        + "throws SQLException {\n");
    sb.append("    write(__dbStmt, 0);\n");
    sb.append("  }\n\n");

    sb.append("  public int write(PreparedStatement __dbStmt, int __off) "
        + "throws SQLException {\n");

    int fieldNum = 0;

    for (String col : colNames) {
      fieldNum++;

      int sqlType = columnTypes.get(col);
      String javaType = connManager.toJavaType(sqlType);
      if (null == javaType) {
        LOG.error("No Java type for SQL type " + sqlType);
        continue;
      }

      String setterMethod = dbSetterForType(javaType);
      if (null == setterMethod) {
        LOG.error("No db setter method for Java type " + javaType);
        continue;
      }

      sb.append("    JdbcWritableBridge." + setterMethod + "(" + col + ", "
          + fieldNum + " + __off, " + sqlType + ", __dbStmt);\n");
    }

    sb.append("    return " + fieldNum + ";\n");
    sb.append("  }\n");
  }


  /**
   * Generate the readFields() method used by the Hadoop RPC system.
   * @param columnTypes - mapping from column names to sql types
   * @param colNames - ordered list of column names for table.
   * @param sb - StringBuilder to append code to
   */
  private void generateHadoopRead(Map<String, Integer> columnTypes,
      String [] colNames, StringBuilder sb) {

    sb.append("  public void readFields(DataInput __dataIn) "
        + "throws IOException {\n");

    for (String col : colNames) {
      int sqlType = columnTypes.get(col);
      String javaType = connManager.toJavaType(sqlType);
      if (null == javaType) {
        LOG.error("No Java type for SQL type " + sqlType);
        continue;
      }

      String getterMethod = rpcGetterForMaybeNull(javaType, "__dataIn", col);
      if (null == getterMethod) {
        LOG.error("No RPC getter method for Java type " + javaType);
        continue;
      }

      sb.append(getterMethod);
    }

    sb.append("  }\n");
  }

  /**
   * Generate the clone() method.
   * @param columnTypes - mapping from column names to sql types
   * @param colNames - ordered list of column names for table.
   * @param sb - StringBuilder to append code to
   */
  private void generateCloneMethod(Map<String, Integer> columnTypes,
      String [] colNames, StringBuilder sb) {

    TableClassName tableNameInfo = new TableClassName(options);
    String className = tableNameInfo.getShortClassForTable(tableName);

    sb.append("  public Object clone() throws CloneNotSupportedException {\n");
    sb.append("    " + className + " o = (" + className + ") super.clone();\n");

    // For each field that is mutable, we need to perform the deep copy.
    for (String colName : colNames) {
      int sqlType = columnTypes.get(colName);
      String javaType = connManager.toJavaType(sqlType);
      if (null == javaType) {
        continue;
      } else if (javaType.equals("java.sql.Date")
          || javaType.equals("java.sql.Time")
          || javaType.equals("java.sql.Timestamp")
          || javaType.equals(ClobRef.class.getName())
          || javaType.equals(BlobRef.class.getName())) {
        sb.append("    o." + colName + " = (" + javaType
            + ") o." + colName + ".clone();\n");
      } else if (javaType.equals(BytesWritable.class.getName())) {
        sb.append("    o." + colName + " = new BytesWritable("
            + "Arrays.copyOf(" + colName + ".getBytes(), "
            + colName + ".getLength()));\n");
      }
    }

    sb.append("    return o;\n");
    sb.append("  }\n\n");
  }

  /**
   * Generate the getFieldMap() method.
   * @param columnTypes - mapping from column names to sql types
   * @param colNames - ordered list of column names for table.
   * @param sb - StringBuilder to append code to
   */
  private void generateGetFieldMap(Map<String, Integer> columnTypes,
      String [] colNames, StringBuilder sb) {
    sb.append("  public Map<String, Object> getFieldMap() {\n");
    sb.append("    Map<String, Object> __sqoop$field_map = "
        + "new TreeMap<String, Object>();\n");
    for (String colName : colNames) {
      sb.append("    __sqoop$field_map.put(\"" + colName + "\", this."
          + colName + ");\n");
    }
    sb.append("    return __sqoop$field_map;\n");
    sb.append("  }\n\n");
  }

  /**
   * Generate the toString() method.
   * @param columnTypes - mapping from column names to sql types
   * @param colNames - ordered list of column names for table.
   * @param sb - StringBuilder to append code to
   */
  private void generateToString(Map<String, Integer> columnTypes,
      String [] colNames, StringBuilder sb) {

    // Save the delimiters to the class.
    sb.append("  private final DelimiterSet __outputDelimiters = ");
    sb.append(options.getOutputDelimiters().formatConstructor() + ";\n");

    // The default toString() method itself follows. This just calls
    // the delimiter-specific toString() with the default delimiters.
    sb.append("  public String toString() {\n");
    sb.append("    return toString(__outputDelimiters);\n");
    sb.append("  }\n");

    // This toString() variant, though, accepts delimiters as arguments.
    sb.append("  public String toString(DelimiterSet delimiters) {\n");
    sb.append("    StringBuilder __sb = new StringBuilder();\n");
    sb.append("    char fieldDelim = delimiters.getFieldsTerminatedBy();\n");

    boolean first = true;
    for (String col : colNames) {
      int sqlType = columnTypes.get(col);
      String javaType = connManager.toJavaType(sqlType);
      if (null == javaType) {
        LOG.error("No Java type for SQL type " + sqlType);
        continue;
      }

      if (!first) {
        // print inter-field tokens.
        sb.append("    __sb.append(fieldDelim);\n");
      }

      first = false;

      String stringExpr = stringifierForType(javaType, col);
      if (null == stringExpr) {
        LOG.error("No toString method for Java type " + javaType);
        continue;
      }

      sb.append("    __sb.append(FieldFormatter.escapeAndEnclose(" + stringExpr 
          + ", delimiters));\n");
    }

    sb.append("    __sb.append(delimiters.getLinesTerminatedBy());\n");
    sb.append("    return __sb.toString();\n");
    sb.append("  }\n");
  }



  /**
   * Helper method for generateParser(). Writes out the parse() method for one
   * particular type we support as an input string-ish type.
   */
  private void generateParseMethod(String typ, StringBuilder sb) {
    sb.append("  public void parse(" + typ + " __record) "
        + "throws RecordParser.ParseError {\n");
    sb.append("    if (null == this.__parser) {\n");
    sb.append("      this.__parser = new RecordParser(__inputDelimiters);\n");
    sb.append("    }\n");
    sb.append("    List<String> __fields = "
        + "this.__parser.parseRecord(__record);\n");
    sb.append("    __loadFromFields(__fields);\n");
    sb.append("  }\n\n");
  }

  /**
   * Helper method for parseColumn(). Interpret the string 'null' as a null
   * for a particular column.
   */
  private void parseNullVal(String colName, StringBuilder sb) {
    sb.append("    if (__cur_str.equals(\"null\")) { this.");
    sb.append(colName);
    sb.append(" = null; } else {\n");
  }

  /**
   * Helper method for generateParser(). Generates the code that loads one
   * field of a specified name and type from the next element of the field
   * strings list.
   */
  private void parseColumn(String colName, int colType, StringBuilder sb) {
    // assume that we have __it and __cur_str vars, based on
    // __loadFromFields() code.
    sb.append("    __cur_str = __it.next();\n");
    String javaType = connManager.toJavaType(colType);

    parseNullVal(colName, sb);
    if (javaType.equals("String")) {
      // TODO(aaron): Distinguish between 'null' and null. Currently they both
      // set the actual object to null.
      sb.append("      this." + colName + " = __cur_str;\n");
    } else if (javaType.equals("Integer")) {
      sb.append("      this." + colName + " = Integer.valueOf(__cur_str);\n");
    } else if (javaType.equals("Long")) {
      sb.append("      this." + colName + " = Long.valueOf(__cur_str);\n");
    } else if (javaType.equals("Float")) {
      sb.append("      this." + colName + " = Float.valueOf(__cur_str);\n");
    } else if (javaType.equals("Double")) {
      sb.append("      this." + colName + " = Double.valueOf(__cur_str);\n");
    } else if (javaType.equals("Boolean")) {
      sb.append("      this." + colName
          + " = BooleanParser.valueOf(__cur_str);\n");
    } else if (javaType.equals("java.sql.Date")) {
      sb.append("      this." + colName
          + " = java.sql.Date.valueOf(__cur_str);\n");
    } else if (javaType.equals("java.sql.Time")) {
      sb.append("      this." + colName
          + " = java.sql.Time.valueOf(__cur_str);\n");
    } else if (javaType.equals("java.sql.Timestamp")) {
      sb.append("      this." + colName
          + " = java.sql.Timestamp.valueOf(__cur_str);\n");
    } else if (javaType.equals("java.math.BigDecimal")) {
      sb.append("      this." + colName
          + " = new java.math.BigDecimal(__cur_str);\n");
    } else if (javaType.equals(ClobRef.class.getName())) {
      sb.append("      this." + colName + " = ClobRef.parse(__cur_str);\n");
    } else if (javaType.equals(BlobRef.class.getName())) {
      sb.append("      this." + colName + " = BlobRef.parse(__cur_str);\n");
    } else {
      LOG.error("No parser available for Java type " + javaType);
    }

    sb.append("    }\n\n"); // the closing '{' based on code in parseNullVal();
  }

  /**
   * Generate the parse() method.
   * @param columnTypes - mapping from column names to sql types
   * @param colNames - ordered list of column names for table.
   * @param sb - StringBuilder to append code to
   */
  private void generateParser(Map<String, Integer> columnTypes,
      String [] colNames, StringBuilder sb) {

    // Embed into the class the delimiter characters to use when parsing input
    // records.  Note that these can differ from the delims to use as output
    // via toString(), if the user wants to use this class to convert one
    // format to another.
    sb.append("  private final DelimiterSet __inputDelimiters = ");
    sb.append(options.getInputDelimiters().formatConstructor() + ";\n");

    // The parser object which will do the heavy lifting for field splitting.
    sb.append("  private RecordParser __parser;\n"); 

    // Generate wrapper methods which will invoke the parser.
    generateParseMethod("Text", sb);
    generateParseMethod("CharSequence", sb);
    generateParseMethod("byte []", sb);
    generateParseMethod("char []", sb);
    generateParseMethod("ByteBuffer", sb);
    generateParseMethod("CharBuffer", sb);

    // The wrapper methods call __loadFromFields() to actually interpret the
    // raw field data as string, int, boolean, etc. The generation of this
    // method is type-dependent for the fields.
    sb.append("  private void __loadFromFields(List<String> fields) {\n");
    sb.append("    Iterator<String> __it = fields.listIterator();\n");
    sb.append("    String __cur_str;\n");
    for (String colName : colNames) {
      int colType = columnTypes.get(colName);
      parseColumn(colName, colType, sb);
    }
    sb.append("  }\n\n");
  }

  /**
   * Generate the write() method used by the Hadoop RPC system.
   * @param columnTypes - mapping from column names to sql types
   * @param colNames - ordered list of column names for table.
   * @param sb - StringBuilder to append code to
   */
  private void generateHadoopWrite(Map<String, Integer> columnTypes,
      String [] colNames, StringBuilder sb) {

    sb.append("  public void write(DataOutput __dataOut) "
        + "throws IOException {\n");

    for (String col : colNames) {
      int sqlType = columnTypes.get(col);
      String javaType = connManager.toJavaType(sqlType);
      if (null == javaType) {
        LOG.error("No Java type for SQL type " + sqlType);
        continue;
      }

      String setterMethod = rpcSetterForMaybeNull(javaType, "__dataOut", col);
      if (null == setterMethod) {
        LOG.error("No RPC setter method for Java type " + javaType);
        continue;
      }

      sb.append(setterMethod);
    }

    sb.append("  }\n");
  }

  /**
   * Create a list of identifiers to use based on the true column names
   * of the table.
   * @param colNames the actual column names of the table.
   * @return a list of column names in the same order which are
   * cleaned up to be used as identifiers in the generated Java class.
   */
  private String [] cleanColNames(String [] colNames) {
    String [] cleanedColNames = new String[colNames.length];
    for (int i = 0; i < colNames.length; i++) {
      String col = colNames[i];
      String identifier = toIdentifier(col);
      cleanedColNames[i] = identifier;
    }

    return cleanedColNames;
  }


  /**
   * Generate the ORM code for the class.
   */
  public void generate() throws IOException {
    Map<String, Integer> columnTypes;
    
    if (null != tableName) {
      // We're generating a class based on a table import.
      columnTypes = connManager.getColumnTypes(tableName);
    } else {
      // This is based on an arbitrary query.
      String query = this.options.getSqlQuery();
      if (query.indexOf(SqlManager.SUBSTITUTE_TOKEN) == -1) {
        throw new IOException("Query [" + query + "] must contain '"
            + SqlManager.SUBSTITUTE_TOKEN + "' in WHERE clause.");
      }

      columnTypes = connManager.getColumnTypesForQuery(query);
    }

    String [] colNames = options.getColumns();
    if (null == colNames) {
      if (null != tableName) {
        // Table-based import. Read column names from table.
        colNames = connManager.getColumnNames(tableName);
      } else {
        // Infer/assign column names for arbitrary query.
        colNames = connManager.getColumnNamesForQuery(
            this.options.getSqlQuery());
      }
    } else {
      // These column names were provided by the user. They may not be in
      // the same case as the keys in the columnTypes map. So make sure
      // we add the appropriate aliases in that map.
      for (String userColName : colNames) {
        for (Map.Entry<String, Integer> typeEntry : columnTypes.entrySet()) {
          String typeColName = typeEntry.getKey();
          if (typeColName.equalsIgnoreCase(userColName)
              && !typeColName.equals(userColName)) {
            // We found the correct-case equivalent.
            columnTypes.put(userColName, typeEntry.getValue());
            // No need to continue iteration; only one could match.
            // Also, the use of put() just invalidated the iterator.
            break;
          }
        }
      }
    }

    // Translate all the column names into names that are safe to
    // use as identifiers.
    String [] cleanedColNames = cleanColNames(colNames);

    for (int i = 0; i < colNames.length; i++) {
      // Make sure the col->type mapping holds for the 
      // new identifier name, too.
      String identifier = cleanedColNames[i];
      String col = colNames[i];
      columnTypes.put(identifier, columnTypes.get(col));
    }

    // The db write() method may use column names in a different
    // order. If this is set in the options, pull it out here and
    // make sure we format the column names to identifiers in the same way
    // as we do for the ordinary column list.
    String [] dbWriteColNames = options.getDbOutputColumns();
    String [] cleanedDbWriteColNames = null;
    if (null == dbWriteColNames) {
      cleanedDbWriteColNames = cleanedColNames;
    } else {
      cleanedDbWriteColNames = cleanColNames(dbWriteColNames);
    }

    if (LOG.isDebugEnabled()) {
      LOG.debug("selected columns:");
      for (String col : cleanedColNames) {
        LOG.debug("  " + col);
      }

      if (cleanedDbWriteColNames != cleanedColNames) {
        // dbWrite() has a different set of columns than the rest of the
        // generators.
        LOG.debug("db write column order:");
        for (String dbCol : cleanedDbWriteColNames) {
          LOG.debug("  " + dbCol);
        }
      }
    }

    // Generate the Java code.
    StringBuilder sb = generateClassForColumns(columnTypes,
        cleanedColNames, cleanedDbWriteColNames);

    // Write this out to a file in the jar output directory.
    // We'll move it to the user-visible CodeOutputDir after compiling.
    String codeOutDir = options.getJarOutputDir();

    // Get the class name to generate, which includes package components.
    String className = new TableClassName(options).getClassForTable(tableName);
    // Convert the '.' characters to '/' characters.
    String sourceFilename = className.replace('.', File.separatorChar)
        + ".java";
    String filename = codeOutDir + sourceFilename;

    if (LOG.isDebugEnabled()) {
      LOG.debug("Writing source file: " + filename);
      LOG.debug("Table name: " + tableName);
      StringBuilder sbColTypes = new StringBuilder();
      for (String col : colNames) {
        Integer colType = columnTypes.get(col);
        sbColTypes.append(col + ":" + colType + ", ");
      }
      String colTypeStr = sbColTypes.toString();
      LOG.debug("Columns: " + colTypeStr);
      LOG.debug("sourceFilename is " + sourceFilename);
    }

    compileManager.addSourceFile(sourceFilename);

    // Create any missing parent directories.
    File file = new File(filename);
    File dir = file.getParentFile();
    if (null != dir && !dir.exists()) {
      boolean mkdirSuccess = dir.mkdirs();
      if (!mkdirSuccess) {
        LOG.debug("Could not create directory tree for " + dir);
      }
    }

    OutputStream ostream = null;
    Writer writer = null;
    try {
      ostream = new FileOutputStream(filename);
      writer = new OutputStreamWriter(ostream);
      writer.append(sb.toString());
    } finally {
      if (null != writer) {
        try {
          writer.close();
        } catch (IOException ioe) {
          // ignored because we're closing.
        }
      }

      if (null != ostream) {
        try {
          ostream.close();
        } catch (IOException ioe) {
          // ignored because we're closing.
        }
      }
    }
  }

  /**
   * Generate the ORM code for a table object containing the named columns.
   * @param columnTypes - mapping from column names to sql types
   * @param colNames - ordered list of column names for table.
   * @param dbWriteColNames - ordered list of column names for the db
   * write() method of the class.
   * @return - A StringBuilder that contains the text of the class code.
   */
  private StringBuilder generateClassForColumns(
      Map<String, Integer> columnTypes,
      String [] colNames, String [] dbWriteColNames) {
    StringBuilder sb = new StringBuilder();
    sb.append("// ORM class for " + tableName + "\n");
    sb.append("// WARNING: This class is AUTO-GENERATED. "
        + "Modify at your own risk.\n");

    TableClassName tableNameInfo = new TableClassName(options);

    String packageName = tableNameInfo.getPackageForTable();
    if (null != packageName) {
      sb.append("package ");
      sb.append(packageName);
      sb.append(";\n");
    }

    sb.append("import org.apache.hadoop.io.BytesWritable;\n");
    sb.append("import org.apache.hadoop.io.Text;\n");
    sb.append("import org.apache.hadoop.io.Writable;\n");
    sb.append("import org.apache.hadoop.mapred.lib.db.DBWritable;\n");
    sb.append("import " + JdbcWritableBridge.class.getCanonicalName() + ";\n");
    sb.append("import " + DelimiterSet.class.getCanonicalName() + ";\n");
    sb.append("import " + FieldFormatter.class.getCanonicalName() + ";\n");
    sb.append("import " + RecordParser.class.getCanonicalName() + ";\n");
    sb.append("import " + BooleanParser.class.getCanonicalName() + ";\n");
    sb.append("import " + BlobRef.class.getCanonicalName() + ";\n");
    sb.append("import " + ClobRef.class.getCanonicalName() + ";\n");
    sb.append("import " + LargeObjectLoader.class.getCanonicalName() + ";\n");
    sb.append("import " + SqoopRecord.class.getCanonicalName() + ";\n");
    sb.append("import java.sql.PreparedStatement;\n");
    sb.append("import java.sql.ResultSet;\n");
    sb.append("import java.sql.SQLException;\n");
    sb.append("import java.io.DataInput;\n");
    sb.append("import java.io.DataOutput;\n");
    sb.append("import java.io.IOException;\n");
    sb.append("import java.nio.ByteBuffer;\n");
    sb.append("import java.nio.CharBuffer;\n");
    sb.append("import java.sql.Date;\n");
    sb.append("import java.sql.Time;\n");
    sb.append("import java.sql.Timestamp;\n");
    sb.append("import java.util.Arrays;\n");
    sb.append("import java.util.Iterator;\n");
    sb.append("import java.util.List;\n");
    sb.append("import java.util.Map;\n");
    sb.append("import java.util.TreeMap;\n");
    sb.append("\n");

    String className = tableNameInfo.getShortClassForTable(tableName);
    sb.append("public class " + className + " extends SqoopRecord "
        + " implements DBWritable, Writable {\n");
    sb.append("  private final int PROTOCOL_VERSION = "
        + CLASS_WRITER_VERSION + ";\n");
    sb.append(
        "  public int getClassFormatVersion() { return PROTOCOL_VERSION; }\n");
    sb.append("  protected ResultSet __cur_result_set;\n");
    generateFields(columnTypes, colNames, sb);
    generateDbRead(columnTypes, colNames, sb);
    generateLoadLargeObjects(columnTypes, colNames, sb);
    generateDbWrite(columnTypes, dbWriteColNames, sb);
    generateHadoopRead(columnTypes, colNames, sb);
    generateHadoopWrite(columnTypes, colNames, sb);
    generateToString(columnTypes, colNames, sb);
    generateParser(columnTypes, colNames, sb);
    generateCloneMethod(columnTypes, colNames, sb);
    generateGetFieldMap(columnTypes, colNames, sb);

    // TODO(aaron): Generate hashCode(), compareTo(), equals() so it can be a
    // WritableComparable

    sb.append("}\n");

    return sb;
  }
}
