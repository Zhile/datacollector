/**
 * Copyright 2015 StreamSets Inc.
 *
 * Licensed under the Apache Software Foundation (ASF) under one
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
package com.streamsets.pipeline.stage.it;

import com.streamsets.pipeline.api.Field;
import com.streamsets.pipeline.api.Record;
import com.streamsets.pipeline.api.StageException;
import com.streamsets.pipeline.sdk.RecordCreator;
import com.streamsets.pipeline.stage.HiveMetadataProcessorBuilder;
import com.streamsets.pipeline.stage.HiveMetastoreTargetBuilder;
import com.streamsets.pipeline.stage.destination.hive.HiveMetastoreTarget;
import com.streamsets.pipeline.stage.lib.hive.Errors;
import com.streamsets.pipeline.stage.PartitionConfigBuilder;
import com.streamsets.pipeline.stage.processor.hive.HiveMetadataProcessor;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * Validates what happens on each drift type (columns added, removed, changed).
 */
public class DriftIT extends  BaseHiveMetadataPropagationIT {

  @Before
  public void createTestTable() throws Exception {
    executeUpdate("CREATE TABLE `tbl` (id int) PARTITIONED BY (dt string) STORED AS AVRO");
    executeUpdate("CREATE TABLE `tbl_no_partition` (city string) STORED AS AVRO");
    executeUpdate("CREATE TABLE `multiple` (id int, value string) PARTITIONED BY (dt string) STORED AS AVRO");
    executeUpdate("CREATE EXTERNAL TABLE `ext_table` (id int, value string) STORED AS AVRO LOCATION '/user/hive/external'");
  }

  @Test
  public void testNewColumn() throws Exception {
    HiveMetadataProcessor processor = new HiveMetadataProcessorBuilder()
        .build();
    HiveMetastoreTarget hiveTarget = new HiveMetastoreTargetBuilder()
        .build();

    List<Record> records = new LinkedList<>();

    Map<String, Field> map = new LinkedHashMap<>();
    map.put("id", Field.create(Field.Type.INTEGER, 1));
    Record record = RecordCreator.create("s", "s:1");
    record.set(Field.create(map));
    records.add(record);

    map = new LinkedHashMap<>();
    map.put("id", Field.create(Field.Type.INTEGER, 2));
    map.put("new_column", Field.create(Field.Type.STRING, "new value"));
    record = RecordCreator.create("s", "s:1");
    record.set(Field.create(map));
    records.add(record);

    processRecords(processor, hiveTarget, records);

    assertQueryResult("select * from tbl order by id", new QueryValidator() {
      @Override
      public void validateResultSet(ResultSet rs) throws Exception {
        assertResultSetStructure(rs,
            new ImmutablePair("tbl.id", Types.INTEGER),
            new ImmutablePair("tbl.new_column", Types.VARCHAR),
            new ImmutablePair("tbl.dt", Types.VARCHAR)
        );

        Assert.assertTrue("Table tbl doesn't contain any rows", rs.next());
        Assert.assertEquals(1, rs.getLong(1));
        Assert.assertEquals(null, rs.getString(2));

        Assert.assertTrue("Unexpected number of rows", rs.next());
        Assert.assertEquals(2, rs.getLong(1));
        Assert.assertEquals("new value", rs.getString(2));

        Assert.assertFalse("Unexpected number of rows", rs.next());
      }
    });
  }

  @Test
  public void testNewColumnInTheMiddle() throws Exception {
    HiveMetadataProcessor processor = new HiveMetadataProcessorBuilder()
        .table("multiple")
        .build();
    HiveMetastoreTarget hiveTarget = new HiveMetastoreTargetBuilder()
        .build();

    List<Record> records = new LinkedList<>();

    Map<String, Field> map = new LinkedHashMap<>();
    map.put("id", Field.create(Field.Type.INTEGER, 1));
    map.put("value", Field.create(Field.Type.STRING, "exists"));
    Record record = RecordCreator.create("s", "s:1");
    record.set(Field.create(map));
    records.add(record);

    map = new LinkedHashMap<>();
    map.put("id", Field.create(Field.Type.INTEGER, 2));
    map.put("new_column", Field.create(Field.Type.STRING, "new value"));
    map.put("value", Field.create(Field.Type.STRING, "exists"));
    record = RecordCreator.create("s", "s:1");
    record.set(Field.create(map));
    records.add(record);

    processRecords(processor, hiveTarget, records);

    assertQueryResult("select * from multiple order by id", new QueryValidator() {
      @Override
      public void validateResultSet(ResultSet rs) throws Exception {
        assertResultSetStructure(rs,
            new ImmutablePair("multiple.id", Types.INTEGER),
            new ImmutablePair("multiple.value", Types.VARCHAR),
            new ImmutablePair("multiple.new_column", Types.VARCHAR),
            new ImmutablePair("multiple.dt", Types.VARCHAR)
        );

        Assert.assertTrue("Table tbl doesn't contain any rows", rs.next());
        Assert.assertEquals(1, rs.getLong(1));
        Assert.assertEquals("exists", rs.getString(2));
        Assert.assertEquals(null, rs.getString(3));

        Assert.assertTrue("Unexpected number of rows", rs.next());
        Assert.assertEquals(2, rs.getLong(1));
        Assert.assertEquals("exists", rs.getString(2));
        Assert.assertEquals("new value", rs.getString(3));

        Assert.assertFalse("Unexpected number of rows", rs.next());
      }
    });
  }

  @Test
  public void testRemovedColumn() throws Exception {
    HiveMetadataProcessor processor = new HiveMetadataProcessorBuilder()
        .build();
    HiveMetastoreTarget hiveTarget = new HiveMetastoreTargetBuilder()
        .build();

    List<Record> records = new LinkedList<>();

    Map<String, Field> map = new LinkedHashMap<>();
    map.put("id", Field.create(Field.Type.INTEGER, 1));
    map.put("removed", Field.create(Field.Type.STRING, "value"));
    Record record = RecordCreator.create("s", "s:1");
    record.set(Field.create(map));
    records.add(record);

    map = new LinkedHashMap<>();
    map.put("id", Field.create(Field.Type.INTEGER, 2));
    record = RecordCreator.create("s", "s:1");
    record.set(Field.create(map));
    records.add(record);

    processRecords(processor, hiveTarget, records);

    assertQueryResult("select * from tbl order by id", new QueryValidator() {
      @Override
      public void validateResultSet(ResultSet rs) throws Exception {
        assertResultSetStructure(rs,
            new ImmutablePair("tbl.id", Types.INTEGER),
            new ImmutablePair("tbl.removed", Types.VARCHAR),
            new ImmutablePair("tbl.dt", Types.VARCHAR)
        );

        Assert.assertTrue("Table tbl doesn't contain any rows", rs.next());
        Assert.assertEquals(1, rs.getLong(1));
        Assert.assertEquals("value", rs.getString(2));

        Assert.assertTrue("Unexpected number of rows", rs.next());
        Assert.assertEquals(2, rs.getLong(1));
        Assert.assertEquals(null, rs.getString(2));

        Assert.assertFalse("Unexpected number of rows", rs.next());
      }
    });
  }

  @Test
  public void testChangedColumnType() throws Exception {
    HiveMetadataProcessor processor = new HiveMetadataProcessorBuilder()
        .build();
    HiveMetastoreTarget hiveTarget = new HiveMetastoreTargetBuilder()
        .build();

    List<Record> records = new LinkedList<>();

    Map<String, Field> map = new LinkedHashMap<>();
    map.put("id", Field.create(Field.Type.INTEGER, 1));
    Record record = RecordCreator.create("s", "s:1");
    record.set(Field.create(map));
    records.add(record);

    map = new LinkedHashMap<>();
    map.put("id", Field.create(Field.Type.STRING, "2"));
    record = RecordCreator.create("s", "s:1");
    record.set(Field.create(map));
    records.add(record);

    try {
      processRecords(processor, hiveTarget, records);
      Assert.fail("Column type change haven't resulted in exception");
    } catch (StageException e) {
      Assert.assertEquals(Errors.HIVE_21, e.getErrorCode());
    }
  }

  @Test
  public void testChangedColumnTypeDecimal() throws Exception {
    executeUpdate("CREATE TABLE `decimal` (dec decimal(2, 1)) PARTITIONED BY (dt string) STORED AS AVRO");

    HiveMetadataProcessor processor = new HiveMetadataProcessorBuilder()
        .table("decimal")
        .decimalDefaultsConfig(3, 2)
        .build();
    HiveMetastoreTarget hiveTarget = new HiveMetastoreTargetBuilder()
        .build();

    List<Record> records = new LinkedList<>();

    Map<String, Field> map = new LinkedHashMap<>();
    map.put("dec", Field.create(Field.Type.DECIMAL, BigDecimal.valueOf(2.2)));
    Record record = RecordCreator.create("s", "s:1");
    record.set(Field.create(map));
    records.add(record);

    try {
      processRecords(processor, hiveTarget, records);
      Assert.fail("Column type change haven't resulted in exception");
    } catch (StageException e) {
      Assert.assertEquals(Errors.HIVE_21, e.getErrorCode());
      Assert.assertTrue(e.getMessage().contains("Expected: DECIMAL(2,1), Actual: DECIMAL(3,2)"));
    }
  }

  @Test
  public void testDifferentColumnCase() throws Exception {
    HiveMetadataProcessor processor = new HiveMetadataProcessorBuilder()
        .build();
    HiveMetastoreTarget hiveTarget = new HiveMetastoreTargetBuilder()
        .build();
    List<Record> records = new LinkedList<>();

    Map<String, Field> map = new LinkedHashMap<>();
    map.put("ID", Field.create(Field.Type.INTEGER, 1));
    Record record = RecordCreator.create("s", "s:1");
    record.set(Field.create(map));
    records.add(record);
    processRecords(processor, hiveTarget, records);

    assertQueryResult("select * from tbl order by id", new QueryValidator() {
      @Override
      public void validateResultSet(ResultSet rs) throws Exception {
        assertResultSetStructure(rs,
            new ImmutablePair("tbl.id", Types.INTEGER),
            new ImmutablePair("tbl.dt", Types.VARCHAR)
        );

        Assert.assertTrue("Table tbl doesn't contain any rows", rs.next());
        Assert.assertEquals(1, rs.getInt(1));
        Assert.assertFalse("Unexpected number of rows", rs.next());
      }
    });
  }

  @Test
  public void testAddColumnToNonPartitionedTableInternal() throws Exception {
    HiveMetadataProcessor processor = new HiveMetadataProcessorBuilder()
        .table("tbl_no_partition")
        .partitions(new PartitionConfigBuilder().build())
        .build();

    HiveMetastoreTarget hiveTarget = new HiveMetastoreTargetBuilder()
        .build();
    List<Record> records = new LinkedList<>();

    Map<String, Field> map = new LinkedHashMap<>();
    map.put("city", Field.create("San Francisco"));
    map.put("state", Field.create("California"));
    Record record = RecordCreator.create();
    record.set(Field.create(map));
    records.add(record);
    processRecords(processor, hiveTarget, records);

    assertQueryResult("select * from tbl_no_partition", new QueryValidator() {
      @Override
      public void validateResultSet(ResultSet rs) throws Exception {
        assertResultSetStructure(rs,
            new ImmutablePair("tbl_no_partition.city", Types.VARCHAR),
            new ImmutablePair("tbl_no_partition.state", Types.VARCHAR)
        );

        Assert.assertTrue("Table tbl_no_partition doesn't contain any rows", rs.next());
        Assert.assertEquals("San Francisco", rs.getString(1));
        Assert.assertEquals("California", rs.getString(2));
        Assert.assertFalse("Unexpected number of rows", rs.next());
      }
    });
  }

  @Test
  public void testAddColumnToNonPartitionedTableExternal() throws Exception {
    HiveMetadataProcessor processor = new HiveMetadataProcessorBuilder()
        .table("ext_table")
        .partitions(new PartitionConfigBuilder().build())
        .external(true)
        .tablePathTemplate("/user/hive/external")
        .build();

    HiveMetastoreTarget hiveTarget = new HiveMetastoreTargetBuilder()
        .build();
    List<Record> records = new LinkedList<>();

    Map<String, Field> map = new LinkedHashMap<>();
    map.put("id", Field.create(123));
    map.put("value", Field.create("testtest"));
    Record record = RecordCreator.create();
    record.set(Field.create(map));
    records.add(record);
    processRecords(processor, hiveTarget, records);

    assertQueryResult("select * from ext_table", new QueryValidator() {
      @Override
      public void validateResultSet(ResultSet rs) throws Exception {
        assertResultSetStructure(rs,
            new ImmutablePair("ext_table.id", Types.INTEGER),
            new ImmutablePair("ext_table.value", Types.VARCHAR)
        );
        Assert.assertTrue("Table ext_table doesn't contain any rows", rs.next());
        Assert.assertEquals(123, rs.getInt(1));
        Assert.assertEquals("testtest", rs.getString(2));
        Assert.assertFalse("Unexpected number of rows", rs.next());
      }
    });
  }

  @Test
  public void testAddPartitionToNonPartitionedTable() throws Exception {
    HiveMetadataProcessor processor = new HiveMetadataProcessorBuilder()
        .table("tbl_no_partition")
        .build();

    HiveMetastoreTarget hiveTarget = new HiveMetastoreTargetBuilder()
        .build();
    List<Record> records = new LinkedList<>();

    Map<String, Field> map = new LinkedHashMap<>();
    map.put("city", Field.create("San Jose"));
    Record record = RecordCreator.create();
    record.set(Field.create(map));
    records.add(record);

    try {
      processRecords(processor, hiveTarget, records);
      Assert.fail("Adding a partition to non-partitioned table should fail");
    } catch (StageException e) {
      Assert.assertEquals(e.getErrorCode(), Errors.HIVE_01);
    }

    assertQueryResult("select * from tbl_no_partition", new QueryValidator() {
      @Override
      public void validateResultSet(ResultSet rs) throws Exception {
        // Table structure should not be altered
        assertResultSetStructure(rs,
            new ImmutablePair("tbl_no_partition.city", Types.VARCHAR)
        );
        // Alter Table query failed, so no data should be added to the table
        Assert.assertFalse("Table tbl_no_partition should not contain rows", rs.next());
      }
    });
  }
}