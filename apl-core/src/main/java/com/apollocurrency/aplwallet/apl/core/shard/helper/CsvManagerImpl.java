/*
 * Copyright © 2018-2019 Apollo Foundation
 */

package com.apollocurrency.aplwallet.apl.core.shard.helper;

import static org.slf4j.LoggerFactory.getLogger;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.sql.Array;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import com.apollocurrency.aplwallet.apl.core.app.Convert2;
import com.apollocurrency.aplwallet.apl.core.db.DbUtils;
import com.apollocurrency.aplwallet.apl.core.db.derived.MinMaxDbId;
import com.apollocurrency.aplwallet.apl.crypto.Convert;
import org.h2.api.ErrorCode;
import org.h2.jdbc.JdbcArray;
import org.h2.message.DbException;
import org.h2.tools.SimpleResultSet;
import org.h2.tools.SimpleRowSource;
import org.h2.util.StringUtils;
import org.slf4j.Logger;

/**
 * A facility to read from and write to CSV (comma separated values) files. When
 * reading, the BOM (the byte-order-mark) character 0xfeff at the beginning of
 * the file is ignored.
 */
@Singleton
public class CsvManagerImpl implements SimpleRowSource, CsvManager {
    private static final Logger log = getLogger(CsvManagerImpl.class);

    /**
     * The block size for I/O operations.
     */
    public static final int IO_BUFFER_SIZE = 4 * 1024;
    /**
     * System property <code>line.separator</code> (default: \n).<br />
     * It is usually set by the system, and used by the script and trace tools.
     */
    public static final String LINE_SEPARATOR = "\n";
    /**
     * UTF-8 is expected here
     */
    public static final String FILE_ENCODING = Charset.forName("UTF-8").name(); // UTF-8 default
    public static final String FILE_EXTENSION = ".csv"; // UTF-8
    protected static final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");

    private String[] columnNames;

    private String characterSet = FILE_ENCODING;
    private char escapeCharacter = '\"';
    private char fieldDelimiter = '\"';
    private char fieldSeparatorRead = ',';
    private String fieldSeparatorWrite = ",";
    private boolean caseSensitiveColumnNames;
    private boolean preserveWhitespace;
    private boolean writeColumnHeader = true; // if HEADER is not written (false), we CAN'T store skipped column index !!
    private char lineComment;
    private String lineSeparator = LINE_SEPARATOR;
    private String nullString = "null";// "";

    private Path dataExportPath; // common path for al CSV files
    private String fileName; // file name changes by table name
    private Reader input;
    private char[] inputBuffer;
    private int inputBufferPos;
    private int inputBufferStart = -1;
    private int inputBufferEnd;
    private StringBuffer outputBuffer = new StringBuffer(400);
    private Writer output;
    private boolean endOfLine, endOfFile;

    private Set<String> excludeColumn = new HashSet<>();
    private Set<Integer> excludeColumnIndex = new HashSet<>(); // if HEADER is not written (writeColumnHeader=false), we CAN'T store skipped column index !!

    @Inject
    public CsvManagerImpl(@Named("dataExportDir") Path dataExportPath, Set<String> excludeColumnNames) {
        this.dataExportPath = Objects.requireNonNull(dataExportPath, "dataExportPath is NULL");
        if (excludeColumnNames != null && excludeColumnNames.size() > 0) {
            // assign non empty Set
            this.excludeColumn = excludeColumnNames;
            log.debug("Excluded columns = {}", Arrays.toString(excludeColumnNames.toArray()));
        }
    }

    private int writeResultSet(ResultSet rs, MinMaxDbId minMaxDbId, boolean closeWhenNotAppend) throws SQLException {
        try {
            int rows = 0;
            ResultSetMetaData meta = rs.getMetaData();
            int columnCount = meta.getColumnCount();
//            String[] rowColumnNames = new String[columnCount];
            Object[] rowColumnNames = new String[columnCount];
            int[] sqlTypes = new int[columnCount];
            for (int i = 0; i < columnCount; i++) {
                rowColumnNames[i] = meta.getColumnLabel(i + 1);
                sqlTypes[i] = meta.getColumnType(i + 1);
            }
            if (writeColumnHeader) {
                log.debug("Header = {}", Arrays.toString(rowColumnNames));
                writeHeaderRow(rowColumnNames);
                this.writeColumnHeader = false;// write header columns only once after fileName/tableName has been changed
            }
            while (rs.next()) {
                for (int i = 0; i < columnCount; i++) {
                    java.util.Date date = null;
                    Object o;
                    switch (sqlTypes[i]) {
                        case Types.BLOB:
                            o = rs.getBlob(i + 1);
                            break;
                        case Types.BIGINT:
                        case Types.BIT:
                        case Types.BOOLEAN:
                        case Types.DECIMAL:
                        case Types.DOUBLE:
                        case Types.FLOAT:
                        case Types.INTEGER:
                        case Types.SMALLINT:
                        case Types.TINYINT:
                            o = rs.getString(i + 1);
//                            columnValues.append(v);
                            break;

                        case Types.DATE:
                            date = rs.getDate(i + 1);
                        case Types.TIME:
                            if (date == null) date = rs.getTime(i + 1);
                        case Types.TIMESTAMP:
                            if (date == null) date = rs.getTimestamp(i + 1);
                            if (date == null) {
//                                columnValues.append("null");
                                o = nullString;
                            } else {
//                                columnValues.append("TO_DATE('").append(super.dateFormat.format(d)).append("', 'YYYY/MM/DD HH24:MI:SS')");
                                o = "TO_DATE('" + dateFormat.format(date) + "', 'YYYY/MM/DD HH24:MI:SS')";
                            }
                            break;
                        case Types.ARRAY:
                            Array array = rs.getArray(i + 1);
                            o = array != null ? array.getArray() : nullString;
/*
                            if (array != null && array.getArray() instanceof Object[]) {
                                o = (Object[])array.getArray();
                            } else {
                                o = array != null ? array.getArray() : "";
                            }
*/
                            break;
                        case Types.NVARCHAR:
                            o = rs.getNString(i + 1);
                            break;
                        case Types.VARBINARY:
//                            o = Convert.toHexString( rs.getString(i + 1) != null ? rs.getString(i + 1).getBytes() : "".getBytes());
                            o = rs.getString(i + 1);
                            break;

                        case Types.VARCHAR:
                        default:
                            o = rs.getString(i + 1);
                            if (o != null) {
//                                columnValues.append("'").append( v.replaceAll("'", "''")).append("'");
                                 o = "'" + ((String)o).replaceAll("'", "''") + "'";
                            } else {
//                                columnValues.append("null");
                                 o = nullString;
                            }
                            break;
                    }
//                    rowColumnNames[i] = o == null ? null : o.toString();
                    rowColumnNames[i] = o;
                }
                log.debug("Row = {}", Arrays.toString(rowColumnNames));
                writeRow(rowColumnNames);
                rows++;
                minMaxDbId.setMinDbId(rs.getLong("db_id"));
            }
            if (rows == 1) {
                minMaxDbId.incrementMin(); // increase by one in order to advance further on result set
            }
            if (closeWhenNotAppend) {
                output.close(); // close file on 'write mode'
            } else {
                output.flush(); // flush unfinished file on 'append mode'
            }
            log.debug("CSV file '{}' written rows=[{}]", fileName, rows);
            return rows;
        } catch (IOException e) {
            throw DbException.convertIOException(e, null);
        } finally {
            if (closeWhenNotAppend) {
                close();
            }
            DbUtils.closeSilently(rs);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int write(Writer writer, ResultSet rs, MinMaxDbId minMaxDbId) throws SQLException {
        this.output = writer;
        return writeResultSet(rs, minMaxDbId, true);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int write(String outputFileName, ResultSet rs, MinMaxDbId minMaxDbId) throws SQLException {
        Objects.requireNonNull(outputFileName, "outputFileName is NULL");
        Objects.requireNonNull(rs, "resultSet is NULL");
        Objects.requireNonNull(minMaxDbId, "minMaxDbId is NULL");
        assignNewFileName(outputFileName, true);
        try {
            initWrite(false);
            return writeResultSet(rs, minMaxDbId, true);
        } catch (IOException e) {
            throw convertException("IOException writing " + outputFileName, e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int append(String outputFileName, ResultSet rs, MinMaxDbId minMaxDbId) throws SQLException {
        Objects.requireNonNull(outputFileName, "outputFileName is NULL");
        Objects.requireNonNull(rs, "resultSet is NULL");
        Objects.requireNonNull(minMaxDbId, "minMaxDbId is NULL");
        assignNewFileName(outputFileName, false);
        try {
            initWrite(true);
            return writeResultSet(rs, minMaxDbId, false);
        } catch (IOException e) {
            throw convertException("IOException writing " + outputFileName, e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int write(Connection conn, String outputFileName, String sql, String charset, MinMaxDbId minMaxDbId) throws SQLException {
        Statement stat = conn.createStatement();
        ResultSet rs = stat.executeQuery(sql);
        int rows = write(outputFileName, rs, minMaxDbId);
        stat.close();
        return rows;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ResultSet read(String inputFileName, String[] colNames, String charset) throws SQLException {
        Objects.requireNonNull(inputFileName, "inputFileName is NULL");
        Objects.requireNonNull(colNames, "columnNames is NULL");
        Objects.requireNonNull(charset, "charset is NULL");
        assignNewFileName(inputFileName, true);
        try {
            return readResultSet(colNames);
        } catch (IOException e) {
            throw convertException("IOException reading " + inputFileName, e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ResultSet read(Reader reader, String[] colNames) throws IOException {
        Objects.requireNonNull(reader, "reader is NULL");
        Objects.requireNonNull(colNames, "columnName is NULL");
        // assignNewFileName(null);
        this.input = reader;
        return readResultSet(colNames);
    }

    private ResultSet readResultSet(String[] colNames) throws IOException {
        this.columnNames = colNames;
        initRead();
        SimpleResultSet result = new SimpleResultSet(this);
        makeColumnNamesUnique();
        for (String columnName : columnNames) {
            result.addColumn(columnName, Types.VARCHAR, Integer.MAX_VALUE, 0);
        }
        return result;
    }

    private void makeColumnNamesUnique() {
        for (int i = 0; i < columnNames.length; i++) {
            StringBuilder buff = new StringBuilder();
            String n = columnNames[i];
            if (n == null || n.length() == 0) {
                buff.append('C').append(i + 1);
            } else {
                buff.append(n);
            }
            for (int j = 0; j < i; j++) {
                String y = columnNames[j];
                if (buff.toString().equals(y)) {
                    buff.append('1');
                    j = -1;
                }
            }
            columnNames[i] = buff.toString();
        }
    }

    private void assignNewFileName(String newFileName, boolean closeWhenAppend) {
        Objects.requireNonNull(newFileName, "fileName is NULL");
        if (!newFileName.equalsIgnoreCase(this.fileName)) {
            // new file name is assigned
            this.writeColumnHeader = true; // will write header column names
        }
        if (closeWhenAppend) {
            excludeColumnIndex.clear(); // clean previously stored id's
        }
        this.fileName = newFileName;
    }

    private void initWrite(boolean appendMode) throws IOException {
        if (output == null) {
            try {
                OutputStream out = DbUtils.newOutputStream(this.dataExportPath,
                        !this.fileName.contains(FILE_EXTENSION) ? this.fileName + FILE_EXTENSION : this.fileName,
                        appendMode);
                out = new BufferedOutputStream(out, IO_BUFFER_SIZE);
                output = new BufferedWriter(new OutputStreamWriter(out, characterSet));
            } catch (Exception e) {
                close();
                throw DbException.convertToIOException(e);
            }
        }
    }

//    private void writeHeaderRow(String[] rowColumnNames) throws IOException {
    private void writeHeaderRow(Object[] rowColumnNames) throws IOException {
        Objects.requireNonNull(rowColumnNames, "rowColumnNames is NULL");
        boolean isSkippedColumn = false;
        for (int i = 0; i < rowColumnNames.length; i++) {
/*
            if (i > 0 && i < rowColumnNames.length - 1) {
                if (fieldSeparatorWrite != null && !isSkippedColumn) { // do not write comma in case skipped column
                    output.write(fieldSeparatorWrite);
                }
            }
*/
            if (rowColumnNames[i] != null && rowColumnNames[i].toString() != null) {
                String s = rowColumnNames[i].toString(); // column name
                if (i > 0 /*&& i < rowColumnNames.length - 1*/ && !isSkippedColumn) {
                    if (fieldSeparatorWrite != null) { // do not write comma in case skipped column
                        // or when last known column
//                        output.write(fieldSeparatorWrite);
                        outputBuffer.append(fieldSeparatorWrite);
                    }
                }
                if (excludeColumn.contains(s)) {
                    // skip processing specified columns
                    isSkippedColumn = true;
                    excludeColumnIndex.add(i); // if HEADER is not written, we CAN'T store skipped column index !!
                    continue;
                }
//                if (escapeCharacter != 0) {
                    if (fieldDelimiter != 0) {
//                        output.write(fieldDelimiter);
                        outputBuffer.append(fieldDelimiter);
                    }
//                    output.write(escape(s));
//                    output.write(s);
                        outputBuffer.append(s);
                    if (fieldDelimiter != 0) {
//                        output.write(fieldDelimiter);
                        outputBuffer.append(fieldDelimiter);
                    }
//                } else {
//                    output.write(s);
//                }
//            } else if (nullString != null && nullString.length() > 0) {
//                output.write(nullString);
            } else {
                // we can't proceed if column name is empty
                log.error("ERROR, column name is EMPTY. Array = {}", Arrays.toString(rowColumnNames));
                throw new IllegalArgumentException("ERROR, column name is EMPTY");
            }
            isSkippedColumn = false; // reset flag
        }
        if (isSkippedColumn) {
            // remove last comma
            outputBuffer.deleteCharAt(outputBuffer.lastIndexOf(","));
        }
        outputBuffer.append(lineSeparator);
        output.write(outputBuffer.toString());
        outputBuffer.setLength(0); // reset
    }

//    private void writeRow(String[] rowColumnValues) throws IOException {
    private void writeRow(Object[] rowColumnValues) throws IOException {
        boolean isSkippedColumn = false;
        for (int i = 0; i < rowColumnValues.length; i++) {
/*
            if (i > 0 && i < rowColumnValues.length - 1) {
                if (fieldSeparatorWrite != null && !isSkippedColumn) {
                    output.write(fieldSeparatorWrite);
                }
            }
*/
            if (rowColumnValues[i] != null && rowColumnValues[i].toString() != null) {
                if (i > 0 && !isSkippedColumn) {
                    if (fieldSeparatorWrite != null/* && !isSkippedColumn*/) {
//                        output.write(fieldSeparatorWrite);
                        outputBuffer.append(fieldSeparatorWrite);
                    }
                }
                if (excludeColumnIndex.contains(i)) {
                    // skip column value processing
                    isSkippedColumn = true; // do not put not needed comma
                    continue;
                }
                String s;
                if (rowColumnValues[i] instanceof Object[]) {
                    int index = 0;
                    for (int j = 0; j < rowColumnValues.length; j++) {
                        Object rowColumnValue = rowColumnValues[j];
                        if (j == 0) {
                            outputBuffer.append("(");
                        }
                        outputBuffer.append(rowColumnValue).append(",");
                    }
                    outputBuffer.append(")");
                } else {
                    s = rowColumnValues[i].toString(); // column value
                    outputEscapedValueWithDelimiter(s);
                }
            } else if (nullString != null && nullString.length() > 0 && !nullString.equalsIgnoreCase("null")) {
//                output.write(nullString);
                outputBuffer.append(nullString);
            }
            isSkippedColumn = false; // reset flag
        }
        // remove last comma, when latest columnn was skipped
        if (isSkippedColumn) {
            outputBuffer.deleteCharAt(outputBuffer.lastIndexOf(","));
        }
//        output.write(lineSeparator);
        outputBuffer.append(lineSeparator);
        output.write(outputBuffer.toString());
        outputBuffer.setLength(0); // reset
    }

    private void outputEscapedValueWithDelimiter(String s) throws IOException {
        if (escapeCharacter != 0) {
            if (fieldDelimiter != 0) {
//                output.write(fieldDelimiter);
                outputBuffer.append(fieldDelimiter);
            }
//            output.write(escape(s));
            outputBuffer.append(escape(s));
            if (fieldDelimiter != 0) {
//                output.write(fieldDelimiter);
                outputBuffer.append(fieldDelimiter);
            }
        } else {
//            output.write(s);
            outputBuffer.append(s);
        }
    }

    private String escape(String data) {
        if (data.indexOf(fieldDelimiter) < 0) {
            if (escapeCharacter == fieldDelimiter || data.indexOf(escapeCharacter) < 0) {
                return data;
            }
        }
        int length = data.length();
        StringBuilder buff = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            char ch = data.charAt(i);
            if (ch == fieldDelimiter || ch == escapeCharacter) {
                buff.append(escapeCharacter);
            }
            buff.append(ch);
        }
        return buff.toString();
    }

    private void initRead() throws IOException {
        if (input == null) {
            try {
                InputStream in = DbUtils.newInputStream(
                        this.dataExportPath,
                        !this.fileName.contains(FILE_EXTENSION) ? this.fileName + FILE_EXTENSION : this.fileName
                );
                in = new BufferedInputStream(in, IO_BUFFER_SIZE);
                input = new InputStreamReader(in, characterSet);
            } catch (IOException e) {
                close();
                throw e;
            }
        }
        if (!input.markSupported()) {
            input = new BufferedReader(input);
        }
        input.mark(1);
        int bom = input.read();
        if (bom != 0xfeff) {
            // Microsoft Excel compatibility
            // ignore pseudo-BOM
            input.reset();
        }
        inputBuffer = new char[IO_BUFFER_SIZE * 2];
        if (columnNames == null) {
            readHeader();
        }
    }

    private void readHeader() throws IOException {
        ArrayList<String> list = new ArrayList<>(4);
        while (true) {
            String v = readValue();
            if (v == null) {
                if (endOfLine) {
                    if (endOfFile || list.size() > 0) {
                        break;
                    }
                } else {
                    v = "COLUMN" + list.size();
                    list.add(v);
                }
            } else {
                if (v.length() == 0) {
                    v = "COLUMN" + list.size();
                } else if (!caseSensitiveColumnNames && isSimpleColumnName(v)) {
                    v = StringUtils.toUpperEnglish(v);
                }
                list.add(v);
                if (endOfLine) {
                    break;
                }
            }
        }
        columnNames = new String[list.size()];
        list.toArray(columnNames);
    }

    private static boolean isSimpleColumnName(String columnName) {
        for (int i = 0, length = columnName.length(); i < length; i++) {
            char ch = columnName.charAt(i);
            if (i == 0) {
                if (ch != '_' && !Character.isLetter(ch)) {
                    return false;
                }
            } else {
                if (ch != '_' && !Character.isLetterOrDigit(ch)) {
                    return false;
                }
            }
        }
        if (columnName.length() == 0) {
            return false;
        }
        return true;
    }

    private void pushBack() {
        inputBufferPos--;
    }

    private int readChar() throws IOException {
        if (inputBufferPos >= inputBufferEnd) {
            return readBuffer();
        }
        return inputBuffer[inputBufferPos++];
    }

    private int readBuffer() throws IOException {
        if (endOfFile) {
            return -1;
        }
        int keep;
        if (inputBufferStart >= 0) {
            keep = inputBufferPos - inputBufferStart;
            if (keep > 0) {
                char[] src = inputBuffer;
                if (keep + IO_BUFFER_SIZE > src.length) {
                    inputBuffer = new char[src.length * 2];
                }
                System.arraycopy(src, inputBufferStart, inputBuffer, 0, keep);
            }
            inputBufferStart = 0;
        } else {
            keep = 0;
        }
        inputBufferPos = keep;
        int len = input.read(inputBuffer, keep, IO_BUFFER_SIZE);
        if (len == -1) {
            // ensure bufferPos > bufferEnd
            // even after pushBack
            inputBufferEnd = -1024;
            endOfFile = true;
            // ensure the right number of characters are read
            // in case the input buffer is still used
            inputBufferPos++;
            return -1;
        }
        inputBufferEnd = keep + len;
        return inputBuffer[inputBufferPos++];
    }

    private String readValue() throws IOException {
        endOfLine = false;
        inputBufferStart = inputBufferPos;
        while (true) {
            int ch = readChar();
            if (ch == fieldDelimiter) {
                // delimited value
                boolean containsEscape = false;
                inputBufferStart = inputBufferPos;
                int sep;
                while (true) {
                    ch = readChar();
                    if (ch == fieldDelimiter) {
                        ch = readChar();
                        if (ch != fieldDelimiter) {
                            sep = 2;
                            break;
                        }
                        containsEscape = true;
                    } else if (ch == escapeCharacter) {
                        ch = readChar();
                        if (ch < 0) {
                            sep = 1;
                            break;
                        }
                        containsEscape = true;
                    } else if (ch < 0) {
                        sep = 1;
                        break;
                    }
                }
                String s = new String(inputBuffer,
                        inputBufferStart, inputBufferPos - inputBufferStart - sep);
                if (containsEscape) {
                    s = unEscape(s);
                }
                inputBufferStart = -1;
                while (true) {
                    if (ch == fieldSeparatorRead) {
                        break;
                    } else if (ch == '\n' || ch < 0 || ch == '\r') {
                        endOfLine = true;
                        break;
                    } else if (ch == ' ' || ch == '\t') {
                        // ignore
                    } else {
                        pushBack();
                        break;
                    }
                    ch = readChar();
                }
                return s;
            } else if (ch == '\n' || ch < 0 || ch == '\r') {
                endOfLine = true;
                return null;
            } else if (ch == fieldSeparatorRead) {
                // null
                return null;
            } else if (ch <= ' ') {
                // ignore spaces
                continue;
            } else if (lineComment != 0 && ch == lineComment) {
                // comment until end of line
                inputBufferStart = -1;
                while (true) {
                    ch = readChar();
                    if (ch == '\n' || ch < 0 || ch == '\r') {
                        break;
                    }
                }
                endOfLine = true;
                return null;
            } else {
                // un-delimited value
                while (true) {
                    ch = readChar();
                    if (ch == fieldSeparatorRead) {
                        break;
                    } else if (ch == '\n' || ch < 0 || ch == '\r') {
                        endOfLine = true;
                        break;
                    }
                }
                String s = new String(inputBuffer,
                        inputBufferStart, inputBufferPos - inputBufferStart - 1);
                if (!preserveWhitespace) {
                    s = s.trim();
                }
                inputBufferStart = -1;
                // check un-delimited value for nullString
                return readNull(s);
            }
        }
    }

    private String readNull(String s) {
        return s.equals(nullString) ? null : s;
    }

    private String unEscape(String s) {
        StringBuilder buff = new StringBuilder(s.length());
        int start = 0;
        char[] chars = null;
        while (true) {
            int idx = s.indexOf(escapeCharacter, start);
            if (idx < 0) {
                idx = s.indexOf(fieldDelimiter, start);
                if (idx < 0) {
                    break;
                }
            }
            if (chars == null) {
                chars = s.toCharArray();
            }
            buff.append(chars, start, idx - start);
            if (idx == s.length() - 1) {
                start = s.length();
                break;
            }
            buff.append(chars[idx + 1]);
            start = idx + 2;
        }
        buff.append(s.substring(start));
        return buff.toString();
    }

    @Override
    public Object[] readRow() throws SQLException {
        if (input == null) {
            return null;
        }
        String[] row = new String[columnNames.length];
        try {
            int i = 0;
            while (true) {
                String v = readValue();
                if (v == null) {
                    if (endOfLine) {
                        if (i == 0) {
                            if (endOfFile) {
                                return null;
                            }
                            // empty line
                            continue;
                        }
                        break;
                    }
                }
                if (i < row.length) {
                    row[i++] = v;
                }
                if (endOfLine) {
                    break;
                }
            }
        } catch (IOException e) {
            throw convertException("IOException reading from " + fileName, e);
        }
        return row;
    }

    private static SQLException convertException(String message, Exception e) {
        return DbException.get(ErrorCode.IO_EXCEPTION_1, e, message).getSQLException();
    }

    /**
     * INTERNAL
     */
    @Override
    public void close() {
        outputBuffer.setLength(0);
        DbUtils.closeSilently(input);
        input = null;
        DbUtils.closeSilently(output);
        output = null;
    }

    /**
     * INTERNAL
     */
    @Override
    public void reset() throws SQLException {
        throw new SQLException("Method is not supported", "CSV");
    }

    /**
     * Override the field separator for writing. The default is ",".
     *
     * @param fieldSeparatorWrite the field separator
     */
    public void setFieldSeparatorWrite(String fieldSeparatorWrite) {
        this.fieldSeparatorWrite = fieldSeparatorWrite;
    }

    /**
     * Get the current field separator for writing.
     *
     * @return the field separator
     */
    public String getFieldSeparatorWrite() {
        return fieldSeparatorWrite;
    }

    /**
     * Override the case sensitive column names setting. The default is false.
     * If enabled, the case of all column names is always preserved.
     *
     * @param caseSensitiveColumnNames whether column names are case sensitive
     */
    public void setCaseSensitiveColumnNames(boolean caseSensitiveColumnNames) {
        this.caseSensitiveColumnNames = caseSensitiveColumnNames;
    }

    /**
     * Get the current case sensitive column names setting.
     *
     * @return whether column names are case sensitive
     */
    public boolean getCaseSensitiveColumnNames() {
        return caseSensitiveColumnNames;
    }

    /**
     * Override the field separator for reading. The default is ','.
     *
     * @param fieldSeparatorRead the field separator
     */
    public void setFieldSeparatorRead(char fieldSeparatorRead) {
        this.fieldSeparatorRead = fieldSeparatorRead;
    }

    /**
     * Get the current field separator for reading.
     *
     * @return the field separator
     */
    public char getFieldSeparatorRead() {
        return fieldSeparatorRead;
    }

    /**
     * Set the line comment character. The default is character code 0 (line
     * comments are disabled).
     *
     * @param lineCommentCharacter the line comment character
     */
    public void setLineCommentCharacter(char lineCommentCharacter) {
        this.lineComment = lineCommentCharacter;
    }

    /**
     * Get the line comment character.
     *
     * @return the line comment character, or 0 if disabled
     */
    public char getLineCommentCharacter() {
        return lineComment;
    }

    /**
     * Set the field delimiter. The default is " (a double quote).
     * The value 0 means no field delimiter is used.
     *
     * @param fieldDelimiter the field delimiter
     */
    public void setFieldDelimiter(char fieldDelimiter) {
        this.fieldDelimiter = fieldDelimiter;
    }

    /**
     * Get the current field delimiter.
     *
     * @return the field delimiter
     */
    public char getFieldDelimiter() {
        return fieldDelimiter;
    }

    /**
     * Set the escape character. The escape character is used to escape the
     * field delimiter. This is needed if the data contains the field delimiter.
     * The default escape character is " (a double quote), which is the same as
     * the field delimiter. If the field delimiter and the escape character are
     * both " (double quote), and the data contains a double quote, then an
     * additional double quote is added. Example:
     * <pre>
     * Data: He said "Hello".
     * Escape character: "
     * Field delimiter: "
     * CSV file: "He said ""Hello""."
     * </pre>
     * If the field delimiter is a double quote and the escape character is a
     * backslash, then escaping is done similar to Java (however, only the field
     * delimiter is escaped). Example:
     * <pre>
     * Data: He said "Hello".
     * Escape character: \
     * Field delimiter: "
     * CSV file: "He said \"Hello\"."
     * </pre>
     * The value 0 means no escape character is used.
     *
     * @param escapeCharacter the escape character
     */
    public void setEscapeCharacter(char escapeCharacter) {
        this.escapeCharacter = escapeCharacter;
    }

    /**
     * Get the current escape character.
     *
     * @return the escape character
     */
    public char getEscapeCharacter() {
        return escapeCharacter;
    }

    /**
     * Set the line separator used for writing. This is usually a line feed (\n
     * or \r\n depending on the system settings). The line separator is written
     * after each row (including the last row), so this option can include an
     * end-of-row marker if needed.
     *
     * @param lineSeparator the line separator
     */
    public void setLineSeparator(String lineSeparator) {
        this.lineSeparator = lineSeparator;
    }

    /**
     * Get the line separator used for writing.
     *
     * @return the line separator
     */
    public String getLineSeparator() {
        return lineSeparator;
    }

    /**
     * Set the value that represents NULL. It is only used for non-delimited
     * values.
     *
     * @param nullString the null
     */
    public void setNullString(String nullString) {
        this.nullString = nullString;
    }

    /**
     * Get the current null string.
     *
     * @return the null string.
     */
    public String getNullString() {
        return nullString;
    }

    /**
     * Enable or disable preserving whitespace in unquoted text.
     *
     * @param value the new value for the setting
     */
    public void setPreserveWhitespace(boolean value) {
        this.preserveWhitespace = value;
    }

    /**
     * Whether whitespace in unquoted text is preserved.
     *
     * @return the current value for the setting
     */
    public boolean getPreserveWhitespace() {
        return preserveWhitespace;
    }

    /**
     * Enable or disable writing the column header.
     *
     * @param value the new value for the setting
     */
    public void setWriteColumnHeader(boolean value) {
        this.writeColumnHeader = value;
    }

    /**
     * Whether the column header is written.
     *
     * @return the current value for the setting
     */
    public boolean getWriteColumnHeader() {
        return writeColumnHeader;
    }

    /**
     * INTERNAL.
     * Parse and set the CSV options.
     *
     * @param options the the options
     * @return the character set
     */
    public String setOptions(String options) {
        String charset = null;
        String[] keyValuePairs = StringUtils.arraySplit(options, ' ', false);
        for (String pair : keyValuePairs) {
            if (pair.length() == 0) {
                continue;
            }
            int index = pair.indexOf('=');
            String key = StringUtils.trim(pair.substring(0, index), true, true, " ");
            String value = pair.substring(index + 1);
            char ch = value.length() == 0 ? 0 : value.charAt(0);
            if (isParam(key, "escape", "esc", "escapeCharacter")) {
                setEscapeCharacter(ch);
            } else if (isParam(key, "fieldDelimiter", "fieldDelim")) {
                setFieldDelimiter(ch);
            } else if (isParam(key, "fieldSeparator", "fieldSep")) {
                setFieldSeparatorRead(ch);
                setFieldSeparatorWrite(value);
            } else if (isParam(key, "lineComment", "lineCommentCharacter")) {
                setLineCommentCharacter(ch);
            } else if (isParam(key, "lineSeparator", "lineSep")) {
                setLineSeparator(value);
            } else if (isParam(key, "null", "nullString")) {
                setNullString(value);
            } else if (isParam(key, "charset", "characterSet")) {
                charset = value;
            } else if (isParam(key, "preserveWhitespace")) {
                setPreserveWhitespace(Boolean.parseBoolean(value));
            } else if (isParam(key, "writeColumnHeader")) {
                setWriteColumnHeader(Boolean.parseBoolean(value));
            } else if (isParam(key, "caseSensitiveColumnNames")) {
                setCaseSensitiveColumnNames(Boolean.parseBoolean(value));
            } else {
                throw DbException.getUnsupportedException(key);
            }
        }
        return charset;
    }

    private static boolean isParam(String key, String... values) {
        for (String v : values) {
            if (key.equalsIgnoreCase(v)) {
                return true;
            }
        }
        return false;
    }


}
