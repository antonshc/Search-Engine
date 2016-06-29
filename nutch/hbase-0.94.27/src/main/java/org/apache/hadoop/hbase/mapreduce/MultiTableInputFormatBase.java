/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
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
package org.apache.hadoop.hbase.mapreduce;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.Pair;
import org.apache.hadoop.mapreduce.InputFormat;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.TaskAttemptContext;

import java.util.Map;
import java.util.HashMap;
import java.util.Iterator;
/**
 * A base for {@link MultiTableInputFormat}s. Receives a list of
 * {@link Scan} instances that define the input tables and
 * filters etc. Subclasses may use other TableRecordReader implementations.
 */
@InterfaceAudience.Public
@InterfaceStability.Evolving
public abstract class MultiTableInputFormatBase extends
    InputFormat<ImmutableBytesWritable, Result> {

  final Log LOG = LogFactory.getLog(MultiTableInputFormatBase.class);

  /** Holds the set of scans used to define the input. */
  private List<Scan> scans;

  /** The reader scanning the table, can be a custom one. */
  private TableRecordReader tableRecordReader = null;

  /**
   * Builds a TableRecordReader. If no TableRecordReader was provided, uses the
   * default.
   *
   * @param split The split to work with.
   * @param context The current context.
   * @return The newly created record reader.
   * @throws IOException When creating the reader fails.
   * @throws InterruptedException when record reader initialization fails
   * @see org.apache.hadoop.mapreduce.InputFormat#createRecordReader(
   *      org.apache.hadoop.mapreduce.InputSplit,
   *      org.apache.hadoop.mapreduce.TaskAttemptContext)
   */
  @Override
  public RecordReader<ImmutableBytesWritable, Result> createRecordReader(
      InputSplit split, TaskAttemptContext context)
      throws IOException, InterruptedException {
    TableSplit tSplit = (TableSplit) split;

    if (tSplit.getTableName() == null) {
      throw new IOException("Cannot create a record reader because of a"
          + " previous error. Please look at the previous logs lines from"
          + " the task's full log for more details.");
    }
    HTable table =
        new HTable(context.getConfiguration(), tSplit.getTableName());

    TableRecordReader trr = this.tableRecordReader;
    // if no table record reader was provided use default
    if (trr == null) {
      trr = new TableRecordReader();
    }
    Scan sc = tSplit.getScan();
    sc.setStartRow(tSplit.getStartRow());
    sc.setStopRow(tSplit.getEndRow());
    trr.setScan(sc);
    trr.setHTable(table);
    trr.initialize(split, context);
    return trr;
  }

  /**
   * Calculates the splits that will serve as input for the map tasks. The
   * number of splits matches the number of regions in a table.
   *
   * @param context The current job context.
   * @return The list of input splits.
   * @throws IOException When creating the list of splits fails.
   * @see org.apache.hadoop.mapreduce.InputFormat#getSplits(org.apache.hadoop.mapreduce.JobContext)
   */


  @Override
  public List<InputSplit> getSplits(JobContext context) throws IOException {
    if (scans.isEmpty()) {
      throw new IOException("No scans were provided.");
    }

    Map<String, List<Scan>> tableMaps = new HashMap<String, List<Scan>>();
    for (Scan scan : scans) {
      byte[] tableName = scan.getAttribute(Scan.SCAN_ATTRIBUTES_TABLE_NAME);
      if (tableName == null)
        throw new IOException("A scan object did not have a table name");
      String tableNameStr = Bytes.toString(tableName);

      List<Scan> scanList = tableMaps.get(tableNameStr);

      if (scanList == null) {
        scanList = new ArrayList<Scan>();
        tableMaps.put(tableNameStr, scanList);
      }
      scanList.add(scan);
    }

    List<InputSplit> splits = new ArrayList<InputSplit>();
    Iterator iter = tableMaps.entrySet().iterator();
    while (iter.hasNext()) {
      Map.Entry<String, List<Scan>> entry = (Map.Entry<String, List<Scan>>) iter.next();
      String tableNameStr = entry.getKey();
      List<Scan> scanList = entry.getValue();
      HTable table = new HTable(context.getConfiguration(), tableNameStr);
      Pair<byte[][], byte[][]> keys = table.getStartEndKeys();
      for (Scan scan : scanList) {
        if (keys == null || keys.getFirst() == null ||
                keys.getFirst().length == 0) {
          throw new IOException("Expecting at least one region for table : "
                  + tableNameStr);
        }
        int count = 0;
        byte[] startRow = scan.getStartRow();
        byte[] stopRow = scan.getStopRow();
        for (int i = 0; i < keys.getFirst().length; i++) {
          if (!includeRegionInSplit(keys.getFirst()[i], keys.getSecond()[i])) {
            continue;
          }

          // determine if the given start and stop keys fall into the range
          if ((startRow.length == 0 || keys.getSecond()[i].length == 0 ||
                  Bytes.compareTo(startRow, keys.getSecond()[i]) < 0) &&
                  (stopRow.length == 0 || Bytes.compareTo(stopRow,
                  keys.getFirst()[i]) > 0)) {
            byte[] splitStart = startRow.length == 0 ||
                    Bytes.compareTo(keys.getFirst()[i],
                    startRow) >= 0 ? keys.getFirst()[i] : startRow;
            byte[] splitStop = (stopRow.length == 0 ||
                    Bytes.compareTo(keys.getSecond()[i],
                    stopRow) <= 0) && keys.getSecond()[i].length > 0 ?
                    keys.getSecond()[i] : stopRow;
            String regionLocation = table.getRegionLocation(
                    keys.getFirst()[i], false).getHostname();
            InputSplit split = new TableSplit(Bytes.toBytes(tableNameStr), scan,
                    splitStart, splitStop, regionLocation);
            splits.add(split);
            if (LOG.isDebugEnabled())
              LOG.debug("getSplits: split -> " + (count++) + " -> " + split);
          }
        }
      }
      table.close();
    }

    return splits;
  }

  /**
   * Test if the given region is to be included in the InputSplit while
   * splitting the regions of a table.
   * <p>
   * This optimization is effective when there is a specific reasoning to
   * exclude an entire region from the M-R job, (and hence, not contributing to
   * the InputSplit), given the start and end keys of the same. <br>
   * Useful when we need to remember the last-processed top record and revisit
   * the [last, current) interval for M-R processing, continuously. In addition
   * to reducing InputSplits, reduces the load on the region server as well, due
   * to the ordering of the keys. <br>
   * <br>
   * Note: It is possible that <code>endKey.length() == 0 </code> , for the last
   * (recent) region. <br>
   * Override this method, if you want to bulk exclude regions altogether from
   * M-R. By default, no region is excluded( i.e. all regions are included).
   *
   * @param startKey Start key of the region
   * @param endKey End key of the region
   * @return true, if this region needs to be included as part of the input
   *         (default).
   */
  protected boolean includeRegionInSplit(final byte[] startKey,
      final byte[] endKey) {
    return true;
  }

  /**
   * Allows subclasses to get the list of {@link Scan} objects.
   */
  protected List<Scan> getScans() {
    return this.scans;
  }

  /**
   * Allows subclasses to set the list of {@link Scan} objects.
   *
   * @param scans The list of {@link Scan} used to define the input
   */
  protected void setScans(List<Scan> scans) {
    this.scans = scans;
  }

  /**
   * Allows subclasses to set the {@link TableRecordReader}.
   *
   * @param tableRecordReader A different {@link TableRecordReader}
   *          implementation.
   */
  protected void setTableRecordReader(TableRecordReader tableRecordReader) {
    this.tableRecordReader = tableRecordReader;
  }
}
