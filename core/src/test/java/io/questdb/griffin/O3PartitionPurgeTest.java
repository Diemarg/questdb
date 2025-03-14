/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2022 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb.griffin;

import io.questdb.cairo.*;
import io.questdb.std.*;
import io.questdb.std.datetime.microtime.Timestamps;
import io.questdb.std.str.Path;
import io.questdb.test.tools.TestUtils;
import org.junit.*;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

public class O3PartitionPurgeTest extends AbstractGriffinTest {
    private static O3PartitionPurgeJob purgeJob;

    @BeforeClass
    public static void begin() {
        purgeJob = new O3PartitionPurgeJob(engine.getMessageBus(), 1);
    }

    @AfterClass
    public static void end() {
        purgeJob = Misc.free(purgeJob);
    }

    @Test
    public void test2ReadersUsePartition() throws Exception {
        assertMemoryLeak(() -> {
            compiler.compile("create table tbl as (select x, cast('1970-01-10T10' as timestamp) ts from long_sequence(1)) timestamp(ts) partition by DAY", sqlExecutionContext);

            // OOO insert
            compiler.compile("insert into tbl select 4, '1970-01-10T09'", sqlExecutionContext);

            // This should lock partition 1970-01-10.1 from being deleted from disk
            try (TableReader rdr = engine.getReader(sqlExecutionContext.getCairoSecurityContext(), "tbl")) {

                try (TableReader rdr2 = engine.getReader(sqlExecutionContext.getCairoSecurityContext(), "tbl")) {
                    // in order insert
                    compiler.compile("insert into tbl select 2, '1970-01-10T11'", sqlExecutionContext);

                    // OOO insert
                    compiler.compile("insert into tbl select 4, '1970-01-10T09'", sqlExecutionContext);

                    runPartitionPurgeJobs();

                    rdr2.openPartition(0);
                }

                runPartitionPurgeJobs();

                // This should not fail
                rdr.openPartition(0);
            }
            runPartitionPurgeJobs();

            try (Path path = new Path()) {
                path.concat(engine.getConfiguration().getRoot()).concat("tbl").concat("1970-01-10");
                int len = path.length();
                for (int i = 0; i < 3; i++) {
                    path.trimTo(len).put(".").put(Integer.toString(i)).concat("x.d").$();
                    Assert.assertFalse(Chars.toString(path), Files.exists(path));
                }
            }
        });
    }

    @Test
    public void testAsyncPurgeOnBusyWriter() throws Exception {
        int tableCount = 3;
        assertMemoryLeak(() -> {
            for (int i = 0; i < tableCount; i++) {
                compiler.compile("create table tbl" + i + " as (select x, cast('1970-01-01T10' as timestamp) ts from long_sequence(1)) timestamp(ts) partition by DAY", sqlExecutionContext);
            }

            final CyclicBarrier barrier = new CyclicBarrier(3);
            AtomicInteger done = new AtomicInteger();
            // Open a reader so that writer will not delete partitions easily
            ObjList<TableReader> readers = new ObjList<>(tableCount);
            for (int i = 0; i < tableCount; i++) {
                readers.add(engine.getReader(sqlExecutionContext.getCairoSecurityContext(), "tbl" + i));
            }

            Thread writeThread = new Thread(() -> {
                try {
                    barrier.await();
                    for (int i = 0; i < 32; i++) {
                        for (int j = 0; j < tableCount; j++) {
                            compiler.compile("insert into tbl" + j +
                                            " select 2, '1970-01-10T10' from long_sequence(1) " +
                                            "union all " +
                                            "select 1, '1970-01-09T09'  from long_sequence(1)"
                                    , sqlExecutionContext);
                        }
                    }
                    done.incrementAndGet();
                    Path.clearThreadLocals();
                } catch (Throwable ex) {
                    LOG.error().$(ex).$();
                    done.decrementAndGet();
                }
            });

            Thread readThread = new Thread(() -> {
                try {
                    barrier.await();
                    while (done.get() == 0) {
                        for (int i = 0; i < tableCount; i++) {
                            readers.get(i).openPartition(0);
                            readers.get(i).reload();
                        }
                        Os.pause();
                        Path.clearThreadLocals();
                    }
                } catch (Throwable ex) {
                    LOG.error().$(ex).$();
                    done.addAndGet(-2);
                }
            });

            writeThread.start();
            readThread.start();

            barrier.await();
            while (done.get() == 0) {
                runPartitionPurgeJobs();
                Os.pause();
            }
            runPartitionPurgeJobs();

            Assert.assertEquals(1, done.get());
            writeThread.join();
            readThread.join();
            Misc.freeObjList(readers);
        });
    }

    @Test
    public void testInvalidFolderNames() throws Exception {
        assertMemoryLeak(() -> {
            compiler.compile("create table tbl as (select x, cast('1970-01-10T10' as timestamp) ts from long_sequence(1)) timestamp(ts) partition by DAY", sqlExecutionContext);

            // This should lock partition 1970-01-10.1 from being deleted from disk
            try (TableReader ignored = engine.getReader(sqlExecutionContext.getCairoSecurityContext(), "tbl")) {
                // OOO insert
                compiler.compile("insert into tbl select 4, '1970-01-10T09'", sqlExecutionContext);
            }

            try (Path path = new Path()) {
                Files.mkdir(path.of(engine.getConfiguration().getRoot()).concat("tbl").concat("invalid_folder.123").$(), 509);
                Files.mkdir(path.of(engine.getConfiguration().getRoot()).concat("tbl").concat("1970-01-01.invalid").$(), 509);

                runPartitionPurgeJobs();

                path.of(engine.getConfiguration().getRoot()).concat("tbl").concat("1970-01-10").concat("x.d").$();
                Assert.assertFalse(Chars.toString(path), Files.exists(path));

                path.of(engine.getConfiguration().getRoot()).concat("tbl").concat("1970-01-10.1").concat("x.d").$();
                Assert.assertTrue(Chars.toString(path), Files.exists(path));
            }
        });
    }

    @Test
    public void testManyReadersOpenClosedAscDense() throws Exception {
        testManyReadersOpenClosedDense(0, 1, 5);
    }

    @Test
    public void testManyReadersOpenClosedAscSparse() throws Exception {
        testManyReadersOpenClosedSparse(0, 1, 4);
    }

    @Test
    public void testManyReadersOpenClosedDescDense() throws Exception {
        testManyReadersOpenClosedDense(3, -1, 4);
    }

    @Test
    public void testManyReadersOpenClosedDescSparse() throws Exception {
        testManyReadersOpenClosedSparse(4, -1, 5);
    }

    @Test
    public void testManyTablesFuzzTest() throws Exception {
        Rnd rnd = TestUtils.generateRandom();
        int tableCount = 1;
        int testIterations = 100;

        assertMemoryLeak(() -> {
            for (int i = 0; i < tableCount; i++) {
                compiler.compile("create table tbl" + i + " as (select x, cast('1970-01-10T10' as timestamp) ts from long_sequence(1)) timestamp(ts) partition by DAY", sqlExecutionContext);
            }

            ObjList<TableReader> readers = new ObjList<>();
            for (int i = 0; i < testIterations; i++) {
                String tableName = "tbl" + rnd.nextInt(tableCount);
                String partition = "1970-0" + (1 + rnd.nextInt(1)) + "-01";

                runPartitionPurgeJobs();

                if (rnd.nextBoolean()) {
                    // deffo OOO insert
                    compiler.compile("insert into " + tableName + " select 4, '" + partition + "T09'", sqlExecutionContext);
                } else {
                    // in order insert if last partition
                    compiler.compile("insert into " + tableName + " select 2, '" + partition + "T11'", sqlExecutionContext);
                }

                // lock reader on this transaction
                readers.add(engine.getReader(sqlExecutionContext.getCairoSecurityContext(), tableName));
            }

            runPartitionPurgeJobs();

            for (int i = 0; i < testIterations; i++) {
                runPartitionPurgeJobs();
                TableReader reader = readers.get(i);
                reader.openPartition(0);
                reader.close();
            }

            try (
                    Path path = new Path();
                    TxReader txReader = new TxReader(engine.getConfiguration().getFilesFacade())
            ) {
                for (int i = 0; i < tableCount; i++) {
                    String tableName = "tbl" + i;
                    path.of(engine.getConfiguration().getRoot()).concat(tableName);
                    int len = path.length();
                    int partitionBy = PartitionBy.DAY;
                    txReader.ofRO(path, partitionBy);
                    txReader.unsafeLoadAll();

                    Assert.assertEquals(2, txReader.getPartitionCount());
                    for (int p = 0; p < 2; p++) {
                        long partitionTs = txReader.getPartitionTimestamp(p);
                        long partitionNameVersion = txReader.getPartitionNameTxn(p);

                        for (int v = 0; v < partitionNameVersion + 5; v++) {
                            path.trimTo(len);
                            TableUtils.setPathForPartition(path, partitionBy, partitionTs, false);
                            TableUtils.txnPartitionConditionally(path, v);
                            path.concat("x.d").$();
                            Assert.assertEquals(Chars.toString(path), v == partitionNameVersion, Files.exists(path));
                        }
                    }
                    txReader.clear();
                }
            }
        });
    }

    @Test
    public void testNonAsciiTableName() throws Exception {
        String tableName = "таблица";

        assertMemoryLeak(() -> {
            compiler.compile("create table " + tableName + " as (select x, cast('1970-01-10T10' as timestamp) ts from long_sequence(1)) timestamp(ts) partition by DAY", sqlExecutionContext);

            // OOO insert
            compiler.compile("insert into " + tableName + " select 4, '1970-01-10T09'", sqlExecutionContext);

            // in order insert
            compiler.compile("insert into " + tableName + " select 2, '1970-01-10T11'", sqlExecutionContext);

            // This should lock partition 1970-01-10.1 from being deleted from disk
            try (TableReader rdr = engine.getReader(sqlExecutionContext.getCairoSecurityContext(), tableName)) {

                // OOO insert
                compiler.compile("insert into " + tableName + " select 4, '1970-01-10T09'", sqlExecutionContext);

                // This should not fail
                rdr.openPartition(0);
            }

            runPartitionPurgeJobs();

            try (Path path = new Path()) {
                path.concat(engine.getConfiguration().getRoot()).concat(tableName).concat("1970-01-10");
                int len = path.length();
                for (int i = 0; i < 3; i++) {
                    path.trimTo(len).put(".").put(Integer.toString(i)).concat("x.d").$();
                    Assert.assertFalse(Chars.toString(path), Files.exists(path));
                }
            }
        });
    }

    @Test
    public void testPurgeFailed() throws Exception {
        assertMemoryLeak(() -> {
            AtomicInteger deleteAttempts = new AtomicInteger();
            ff = new FilesFacadeImpl() {
                @Override
                public int rmdir(Path name) {
                    if (Chars.endsWith(name, "1970-01-10" + Files.SEPARATOR)) {
                        deleteAttempts.incrementAndGet();
                        return 5;
                    }
                    return super.rmdir(name);
                }
            };

            compiler.compile("create table tbl as (select x, cast('1970-01-10T10' as timestamp) ts from long_sequence(1)) timestamp(ts) partition by DAY", sqlExecutionContext);

            // This should lock partition 1970-01-10.1 from being deleted from disk
            try (TableReader ignored = engine.getReader(sqlExecutionContext.getCairoSecurityContext(), "tbl")) {
                // OOO insert
                compiler.compile("insert into tbl select 4, '1970-01-10T09'", sqlExecutionContext);
            }

            try (Path path = new Path()) {
                runPartitionPurgeJobs();

                Assert.assertEquals(2, deleteAttempts.get()); // One message from Writer, one from Reader

                path.of(engine.getConfiguration().getRoot()).concat("tbl").concat("1970-01-10.1").concat("x.d").$();
                Assert.assertTrue(Chars.toString(path), Files.exists(path));
            }
        });
    }

    @Test
    public void testPurgeFailedAndVacuumed() throws Exception {
        runPartitionPurgeJobs();
        assertMemoryLeak(() -> {
            AtomicInteger deleteAttempts = new AtomicInteger();
            ff = new FilesFacadeImpl() {
                @Override
                public int rmdir(Path name) {
                    if (Chars.endsWith(name, "1970-01-10" + Files.SEPARATOR)) {
                        if (deleteAttempts.incrementAndGet() < 3) {
                            return 5;
                        }
                    }
                    return super.rmdir(name);
                }
            };

            compiler.compile("create table tbl as (select x, cast('1970-01-10T10' as timestamp) ts from long_sequence(1)) timestamp(ts) partition by DAY", sqlExecutionContext);

            // This should lock partition 1970-01-10.1 from being deleted from disk
            try (TableReader ignored = engine.getReader(sqlExecutionContext.getCairoSecurityContext(), "tbl")) {
                // OOO insert
                compiler.compile("insert into tbl select 4, '1970-01-10T09'", sqlExecutionContext);
            }

            try (Path path = new Path()) {
                runPartitionPurgeJobs();

                Assert.assertEquals(2, deleteAttempts.get()); // One message from Writer, one from Reader

                path.of(engine.getConfiguration().getRoot()).concat("tbl").concat("1970-01-10").concat("x.d").$();
                Assert.assertTrue(Chars.toString(path), Files.exists(path));

                path.of(engine.getConfiguration().getRoot()).concat("tbl").concat("1970-01-10.1").concat("x.d").$();
                Assert.assertTrue(Chars.toString(path), Files.exists(path));

                // VACUUM SQL should delete partition version 1970-01-10 on attempt 3
                compiler.compile("vacuum partitions tbl", sqlExecutionContext);
                runPartitionPurgeJobs();
                path.of(engine.getConfiguration().getRoot()).concat("tbl").concat("1970-01-10").concat("x.d").$();
                Assert.assertFalse(Chars.toString(path), Files.exists(path));
            }
        });
    }

    @Test
    public void testPartitionsNotVacuumedBeforeCommit() throws Exception {
        assertMemoryLeak(() -> {

            compiler.compile("create table tbl as (" +
                    "select x, " +
                    "timestamp_sequence('1970-01-01', 10 * 60 * 60 * 1000000L) ts " +
                    "from long_sequence(1)" +
                    ") timestamp(ts) partition by HOUR", sqlExecutionContext);

            try (Path path = new Path()) {
                try (TableWriter writer = engine.getWriter(sqlExecutionContext.getCairoSecurityContext(), "tbl", "test")) {
                    long startTimestamp = Timestamps.HOUR_MICROS + 10;

                    for (int i = 0; i < 10; i ++) {
                        TableWriter.Row row = writer.newRow(startTimestamp);
                        row.putLong(0, i + 1);
                        row.append();
                        startTimestamp += Timestamps.HOUR_MICROS;
                    }

                    path.of(engine.getConfiguration().getRoot()).concat("tbl").concat("1970-01-01T01.0").concat("x.d").$();
                    Assert.assertTrue(Chars.toString(path), Files.exists(path));

                    compiler.compile("vacuum table tbl", sqlExecutionContext);
                    runPartitionPurgeJobs();

                    Assert.assertTrue(Chars.toString(path), Files.exists(path));

                    writer.commit();
                }
            }
        });
    }

    @Test
    public void testReaderUsesPartition() throws Exception {
        assertMemoryLeak(() -> {
            compiler.compile("create table tbl as (select x, cast('1970-01-10T10' as timestamp) ts from long_sequence(1)) timestamp(ts) partition by DAY", sqlExecutionContext);

            // OOO insert
            compiler.compile("insert into tbl select 4, '1970-01-10T09'", sqlExecutionContext);

            // This should lock partition 1970-01-10.1 from being deleted from disk
            try (TableReader rdr = engine.getReader(sqlExecutionContext.getCairoSecurityContext(), "tbl")) {

                // in order insert
                compiler.compile("insert into tbl select 2, '1970-01-10T11'", sqlExecutionContext);

                // OOO insert
                compiler.compile("insert into tbl select 4, '1970-01-10T09'", sqlExecutionContext);

                // This should not fail
                rdr.openPartition(0);
            }
            runPartitionPurgeJobs();

            try (Path path = new Path()) {
                path.concat(engine.getConfiguration().getRoot()).concat("tbl").concat("1970-01-10");
                int len = path.length();
                for (int i = 0; i < 3; i++) {
                    path.trimTo(len).put(".").put(Integer.toString(i)).concat("x.d").$();
                    Assert.assertFalse(Chars.toString(path), Files.exists(path));
                }
            }
        });
    }

    @Test
    public void testPartitionDeletedAsyncAfterDroppedBySql() throws Exception {
        assertMemoryLeak(() -> {
            try (Path path = new Path()) {

                compiler.compile("create table tbl as (select x, cast('1970-01-10T10' as timestamp) ts from long_sequence(1)) timestamp(ts) partition by DAY", sqlExecutionContext);

                // OOO inserts partition 1970-01-09
                compiler.compile("insert into tbl select 4, '1970-01-09T10'", sqlExecutionContext);

                path.of(engine.getConfiguration().getRoot()).concat("tbl").concat("1970-01-09.0").concat("x.d").$();
                Assert.assertTrue(Chars.toString(path), Files.exists(path));

                try (TableReader rdr = engine.getReader(sqlExecutionContext.getCairoSecurityContext(), "tbl")) {
                    // OOO inserts partition 1970-01-09
                    compiler.compile("insert into tbl select 4, '1970-01-09T09'", sqlExecutionContext);

                    path.of(engine.getConfiguration().getRoot()).concat("tbl").concat("1970-01-09.2").concat("x.d").$();
                    Assert.assertTrue(Chars.toString(path), Files.exists(path));

                    try (TableReader rdr2 = engine.getReader(sqlExecutionContext.getCairoSecurityContext(), "tbl")) {
                        compile("alter table tbl drop partition list '1970-01-09'", sqlExecutionContext);
                        runPartitionPurgeJobs();

                        // This should not fail
                        rdr2.openPartition(0);
                    }
                    runPartitionPurgeJobs();
                    Assert.assertFalse(Chars.toString(path), Files.exists(path));

                    // This should not fail
                    rdr.openPartition(0);
                }
                runPartitionPurgeJobs();

                path.of(engine.getConfiguration().getRoot()).concat("tbl").concat("1970-01-09.0").concat("x.d").$();
                Assert.assertFalse(Chars.toString(path), Files.exists(path));
            }
        });
    }

    @Test
    public void testTableDropAfterPurgeScheduled() throws Exception {
        assertMemoryLeak(() -> {
            compiler.compile("create table tbl as (select x, cast('1970-01-10T10' as timestamp) ts from long_sequence(1)) timestamp(ts) partition by DAY", sqlExecutionContext);

            // This should lock partition 1970-01-10.1 to not do delete in writer
            try (TableReader ignored = engine.getReader(sqlExecutionContext.getCairoSecurityContext(), "tbl")) {
                // OOO insert
                compiler.compile("insert into tbl select 4, '1970-01-10T09'", sqlExecutionContext);
            }

            engine.releaseInactive();
            compiler.compile("drop table tbl", sqlExecutionContext);

            // Main assert here is that job runs without exceptions
            runPartitionPurgeJobs();
        });
    }

    @Test
    public void testTableWriterDeletePartitionWhenNoReadersOpen() throws Exception {
        String tableName = "tbl";

        assertMemoryLeak(() -> {
            compiler.compile("create table " + tableName + " as (select x, cast('1970-01-10T10' as timestamp) ts from long_sequence(1)) timestamp(ts) partition by DAY", sqlExecutionContext);

            compiler.compile("insert into " + tableName +
                            " select 2, '1970-01-11T09' from long_sequence(1) " +
                            "union all " +
                            " select 2, '1970-01-12T09' from long_sequence(1) " +
                            "union all " +
                            " select 2, '1970-01-11T08' from long_sequence(1) " +
                            "union all " +
                            " select 2, '1970-01-10T09' from long_sequence(1) " +
                            "union all " +
                            "select 1, '1970-01-09T09'  from long_sequence(1)"
                    , sqlExecutionContext);

            try (Path path = new Path()) {
                path.of(engine.getConfiguration().getRoot()).concat(tableName).concat("1970-01-10").concat("x.d").$();
                Assert.assertFalse(Chars.toString(path), Files.exists(path));

                path.of(engine.getConfiguration().getRoot()).concat(tableName).concat("1970-01-10.0").concat("x.d").$();
                Assert.assertFalse(Chars.toString(path), Files.exists(path));

                path.of(engine.getConfiguration().getRoot()).concat(tableName).concat("1970-01-10.1").concat("x.d").$();
                Assert.assertTrue(Chars.toString(path), Files.exists(path));

                path.of(engine.getConfiguration().getRoot()).concat(tableName).concat("1970-01-11").concat("x.d").$();
                Assert.assertFalse(Chars.toString(path), Files.exists(path));

                path.of(engine.getConfiguration().getRoot()).concat(tableName).concat("1970-01-11.0").concat("x.d").$();
                Assert.assertFalse(Chars.toString(path), Files.exists(path));

                path.of(engine.getConfiguration().getRoot()).concat(tableName).concat("1970-01-11.1").concat("x.d").$();
                Assert.assertTrue(Chars.toString(path), Files.exists(path));
            }
        });
    }

    private void runPartitionPurgeJobs() {
        // when reader is returned to pool it remains in open state
        // holding files such that purge fails with access violation
        if (Os.type == Os.WINDOWS) {
            engine.releaseInactive();
        }
        //noinspection StatementWithEmptyBody
        while (purgeJob.run(0)) {
            // drain the purge job queue fully
        }
    }

    private void testManyReadersOpenClosedDense(int start, int increment, int iterations) throws Exception {
        assertMemoryLeak(() -> {
            compiler.compile("create table tbl as (select x, cast('1970-01-10T10' as timestamp) ts from long_sequence(1)) timestamp(ts) partition by DAY", sqlExecutionContext);

            TableReader[] readers = new TableReader[iterations];
            for (int i = 0; i < iterations; i++) {
                TableReader rdr = engine.getReader(sqlExecutionContext.getCairoSecurityContext(), "tbl");
                readers[i] = rdr;

                // OOO insert
                compiler.compile("insert into tbl select 4, '1970-01-10T09'", sqlExecutionContext);

                runPartitionPurgeJobs();
            }

            // Unwind readers one by one old to new
            for (int i = start; i >= 0 && i < iterations; i += increment) {
                TableReader reader = readers[i];

                reader.openPartition(0);
                reader.close();

                runPartitionPurgeJobs();
            }

            try (Path path = new Path()) {
                path.concat(engine.getConfiguration().getRoot()).concat("tbl").concat("1970-01-10");
                int len = path.length();

                Assert.assertFalse(Chars.toString(path.concat("x.d")), Files.exists(path));
                for (int i = 0; i < iterations; i++) {
                    path.trimTo(len).put(".").put(Integer.toString(i)).concat("x.d").$();
                    Assert.assertFalse(Chars.toString(path), Files.exists(path));
                }

                path.trimTo(len).put(".").put(Integer.toString(iterations)).concat("x.d").$();
                Assert.assertTrue(Files.exists(path));
            }
        });
    }

    private void testManyReadersOpenClosedSparse(int start, int increment, int iterations) throws Exception {
        assertMemoryLeak(() -> {
            compiler.compile("create table tbl as (select x, cast('1970-01-10T10' as timestamp) ts from long_sequence(1)) timestamp(ts) partition by DAY", sqlExecutionContext);
            TableReader[] readers = new TableReader[2 * iterations];

            for (int i = 0; i < iterations; i++) {
                TableReader rdr = engine.getReader(sqlExecutionContext.getCairoSecurityContext(), "tbl");
                readers[2 * i] = rdr;

                // in order insert
                compiler.compile("insert into tbl select 2, '1970-01-10T11'", sqlExecutionContext);

                runPartitionPurgeJobs();

                TableReader rdr2 = engine.getReader(sqlExecutionContext.getCairoSecurityContext(), "tbl");
                readers[2 * i + 1] = rdr2;
                // OOO insert
                compiler.compile("insert into tbl select 4, '1970-01-10T09'", sqlExecutionContext);

                runPartitionPurgeJobs();
            }

            // Unwind readers one by in set order
            for (int i = start; i >= 0 && i < iterations; i += increment) {
                TableReader reader = readers[2 * i];
                reader.openPartition(0);
                reader.close();

                runPartitionPurgeJobs();

                reader = readers[2 * i + 1];
                reader.openPartition(0);
                reader.close();

                runPartitionPurgeJobs();
            }

            try (Path path = new Path()) {
                path.concat(engine.getConfiguration().getRoot()).concat("tbl").concat("1970-01-10");
                int len = path.length();

                Assert.assertFalse(Chars.toString(path.concat("x.d")), Files.exists(path));
                for (int i = 0; i < 2 * iterations; i++) {
                    path.trimTo(len).put(".").put(Integer.toString(i)).concat("x.d").$();
                    Assert.assertFalse(Chars.toString(path), Files.exists(path));
                }

                path.trimTo(len).put(".").put(Integer.toString(2 * iterations)).concat("x.d").$();
                Assert.assertTrue(Files.exists(path));
            }
        });
    }
}
