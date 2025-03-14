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

package io.questdb.cutlass.pgwire;

import io.questdb.cairo.*;
import io.questdb.cairo.security.AllowAllCairoSecurityContext;
import io.questdb.cairo.sql.OperationFuture;
import io.questdb.cairo.sql.Record;
import io.questdb.cairo.sql.RecordCursor;
import io.questdb.cairo.sql.RecordCursorFactory;
import io.questdb.cutlass.NetUtils;
import io.questdb.griffin.QueryFutureUpdateListener;
import io.questdb.griffin.SqlException;
import io.questdb.griffin.SqlExecutionContextImpl;
import io.questdb.log.Log;
import io.questdb.log.LogFactory;
import io.questdb.mp.SOCountDownLatch;
import io.questdb.mp.WorkerPool;
import io.questdb.network.NetworkFacade;
import io.questdb.network.NetworkFacadeImpl;
import io.questdb.std.*;
import io.questdb.std.datetime.microtime.MicrosecondClock;
import io.questdb.std.datetime.microtime.TimestampFormatUtils;
import io.questdb.std.datetime.microtime.Timestamps;
import io.questdb.std.str.CharSink;
import io.questdb.std.str.LPSZ;
import io.questdb.std.str.StringSink;
import io.questdb.test.tools.TestUtils;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;
import org.postgresql.PGResultSetMetaData;
import org.postgresql.copy.CopyIn;
import org.postgresql.copy.CopyManager;
import org.postgresql.core.BaseConnection;
import org.postgresql.util.PGTimestamp;
import org.postgresql.util.PSQLException;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.sql.*;
import java.text.SimpleDateFormat;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Properties;
import java.util.TimeZone;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static io.questdb.std.Numbers.hexDigits;
import static io.questdb.test.tools.TestUtils.assertContains;
import static org.junit.Assert.*;

@SuppressWarnings("SqlNoDataSourceInspection")
public class PGJobContextTest extends BasePGTest {

    private static final Log LOG = LogFactory.getLog(PGJobContextTest.class);
    private static final long DAY_MICROS = Timestamps.HOUR_MICROS * 24L;
    private static final int count = 200;
    private static final String createDatesTblStmt = "create table xts as (select timestamp_sequence(0, 3600L * 1000 * 1000) ts from long_sequence(" + count + ")) timestamp(ts) partition by DAY";
    private static List<Object[]> datesArr;

    @BeforeClass
    public static void init() {
        inputRoot = new File(".").getAbsolutePath();
        final SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss'.0'");
        formatter.setTimeZone(TimeZone.getTimeZone("UTC"));
        final Stream<Object[]> dates = LongStream.rangeClosed(0, count - 1)
                .map(i -> i * Timestamps.HOUR_MICROS / 1000L)
                .mapToObj(ts -> new Object[]{ts * 1000L, formatter.format(new java.util.Date(ts))});
        datesArr = dates.collect(Collectors.toList());
    }

    @Test
//this looks like the same script as the preparedStatementHex()
    public void testAllParamsHex() throws Exception {
        final String script = ">0000006e00030000757365720078797a0064617461626173650071646200636c69656e745f656e636f64696e67005554463800446174655374796c650049534f0054696d655a6f6e65004575726f70652f4c6f6e646f6e0065787472615f666c6f61745f64696769747300320000\n" +
                "<520000000800000003\n" +
                ">70000000076f6800\n" +
                "<520000000800000000530000001154696d655a6f6e6500474d5400530000001d6170706c69636174696f6e5f6e616d6500517565737444420053000000187365727665725f76657273696f6e0031312e33005300000019696e74656765725f6461746574696d6573006f6e005300000019636c69656e745f656e636f64696e670055544638005a0000000549\n" +
                ">5000\n" +
                ">00\n" +
                ">002200\n" +
                ">5345\n" +
                ">5420\n" +
                ">65\n" +
                ">7874\n" +
                ">7261\n" +
                ">5f\n" +
                ">66\n" +
                ">6c6f\n" +
                ">6174\n" +
                ">5f\n" +
                ">64\n" +
                ">69676974\n" +
                ">73\n" +
                ">20\n" +
                ">3d20\n" +
                ">33\n" +
                ">0000\n" +
                ">0042\n" +
                ">0000000c0000000000000000450000000900000000015300000004\n" +
                "<310000000432000000044300000008534554005a0000000549\n" +
                ">50\n" +
                ">0000\n" +
                ">00\n" +
                ">3700\n" +
                ">5345\n" +
                ">54\n" +
                ">2061\n" +
                ">70\n" +
                ">706c\n" +
                ">69\n" +
                ">6361\n" +
                ">7469\n" +
                ">6f\n" +
                ">6e5f\n" +
                ">6e\n" +
                ">616d6520\n" +
                ">3d20\n" +
                ">2750\n" +
                ">6f\n" +
                ">7374\n" +
                ">67\n" +
                ">726553514c20\n" +
                ">4a\n" +
                ">44\n" +
                ">42\n" +
                ">4320\n" +
                ">44\n" +
                ">726976\n" +
                ">657227\n" +
                ">00\n" +
                ">0000\n" +
                ">420000000c00000000\n" +
                ">0000000045\n" +
                ">00\n" +
                ">000009000000\n" +
                ">00\n" +
                ">0153\n" +
                ">0000\n" +
                ">0004\n" +
                "<310000000432000000044300000008534554005a0000000549\n" +
                ">50\n" +
                ">00\n" +
                ">0000\n" +
                ">2a00\n" +
                ">73\n" +
                ">656c\n" +
                ">65\n" +
                ">6374\n" +
                ">2031\n" +
                ">2c322c\n" +
                ">33\n" +
                ">2066\n" +
                ">72\n" +
                ">6f6d\n" +
                ">20\n" +
                ">6c6f\n" +
                ">6e\n" +
                ">67\n" +
                ">5f7365\n" +
                ">7175\n" +
                ">65\n" +
                ">6e63\n" +
                ">6528\n" +
                ">31\n" +
                ">2900\n" +
                ">00\n" +
                ">0042\n" +
                ">0000000c000000000000000044000000065000450000000900000000005300000004\n" +
                "<31000000043200000004540000004200033100000000000001000000170004ffffffff00003200000000000002000000170004ffffffff00003300000000000003000000170004ffffffff000044000000150003000000013100000001320000000133430000000d53454c4543542031005a0000000549\n" +
                ">50\n" +
                ">000000260073656c6563\n" +
                ">74\n" +
                ">2031\n" +
                ">2066\n" +
                ">72\n" +
                ">6f\n" +
                ">6d\n" +
                ">206c\n" +
                ">6f6e\n" +
                ">675f73\n" +
                ">65\n" +
                ">7175656e63\n" +
                ">65\n" +
                ">2832\n" +
                ">290000\n" +
                ">00\n" +
                ">420000000c000000000000000044000000065000450000000900000000005300000004\n" +
                "<31000000043200000004540000001a00013100000000000001000000170004ffffffff0000440000000b00010000000131440000000b00010000000131430000000d53454c4543542032005a0000000549\n" +
                ">5000\n" +
                ">0000\n" +
                ">2a00\n" +
                ">7365\n" +
                ">6c\n" +
                ">65637420\n" +
                ">31\n" +
                ">2c32\n" +
                ">2c33\n" +
                ">2066\n" +
                ">726f\n" +
                ">6d\n" +
                ">206c\n" +
                ">6f6e\n" +
                ">675f\n" +
                ">7365\n" +
                ">7175\n" +
                ">65\n" +
                ">6e63\n" +
                ">65\n" +
                ">28\n" +
                ">31\n" +
                ">2900\n" +
                ">0000\n" +
                ">420000000c00\n" +
                ">00\n" +
                ">000000\n" +
                ">00\n" +
                ">0000\n" +
                ">44\n" +
                ">0000\n" +
                ">0006\n" +
                ">5000\n" +
                ">45\n" +
                ">0000\n" +
                ">0009\n" +
                ">0000\n" +
                ">0000\n" +
                ">00\n" +
                ">530000\n" +
                ">00\n" +
                ">04\n" +
                "<31000000043200000004540000004200033100000000000001000000170004ffffffff00003200000000000002000000170004ffffffff00003300000000000003000000170004ffffffff000044000000150003000000013100000001320000000133430000000d53454c4543542031005a0000000549\n" +
                ">50\n" +
                ">0000\n" +
                ">00\n" +
                ">26\n" +
                ">0073\n" +
                ">656c\n" +
                ">65637420\n" +
                ">3120\n" +
                ">6672\n" +
                ">6f6d\n" +
                ">20\n" +
                ">6c6f\n" +
                ">6e67\n" +
                ">5f7365\n" +
                ">7175\n" +
                ">656e\n" +
                ">6365\n" +
                ">2832\n" +
                ">2900\n" +
                ">0000\n" +
                ">42000000\n" +
                ">0c\n" +
                ">0000\n" +
                ">0000\n" +
                ">0000\n" +
                ">00\n" +
                ">00\n" +
                ">44\n" +
                ">0000\n" +
                ">0006\n" +
                ">50\n" +
                ">0045\n" +
                ">0000\n" +
                ">0009\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">0000\n" +
                ">530000\n" +
                ">0004\n" +
                "<31000000043200000004540000001a00013100000000000001000000170004ffffffff0000440000000b00010000000131440000000b00010000000131430000000d53454c4543542032005a0000000549\n" +
                ">5000\n" +
                ">00\n" +
                ">00\n" +
                ">2a00\n" +
                ">73\n" +
                ">65\n" +
                ">6c65\n" +
                ">6374\n" +
                ">2031\n" +
                ">2c32\n" +
                ">2c\n" +
                ">33\n" +
                ">20\n" +
                ">6672\n" +
                ">6f6d\n" +
                ">20\n" +
                ">6c6f\n" +
                ">6e67\n" +
                ">5f\n" +
                ">73657175656e6365283129\n" +
                ">0000\n" +
                ">0042000000\n" +
                ">0c000000000000\n" +
                ">00\n" +
                ">00\n" +
                ">44\n" +
                ">00\n" +
                ">0000\n" +
                ">06\n" +
                ">5000\n" +
                ">4500\n" +
                ">00\n" +
                ">00\n" +
                ">0900\n" +
                ">00\n" +
                ">00\n" +
                ">0000\n" +
                ">53000000\n" +
                ">04\n" +
                "<31000000043200000004540000004200033100000000000001000000170004ffffffff00003200000000000002000000170004ffffffff00003300000000000003000000170004ffffffff000044000000150003000000013100000001320000000133430000000d53454c4543542031005a0000000549\n" +
                ">50\n" +
                ">0000\n" +
                ">0026\n" +
                ">0073\n" +
                ">65\n" +
                ">6c\n" +
                ">6563\n" +
                ">7420\n" +
                ">312066\n" +
                ">726f\n" +
                ">6d\n" +
                ">206c\n" +
                ">6f\n" +
                ">6e67\n" +
                ">5f73\n" +
                ">65\n" +
                ">7175\n" +
                ">656e\n" +
                ">6365\n" +
                ">283229\n" +
                ">00\n" +
                ">0000\n" +
                ">420000000c\n" +
                ">000000\n" +
                ">00\n" +
                ">0000\n" +
                ">00\n" +
                ">00\n" +
                ">44\n" +
                ">0000\n" +
                ">000650\n" +
                ">0045\n" +
                ">00\n" +
                ">00\n" +
                ">0009\n" +
                ">0000\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">5300\n" +
                ">00\n" +
                ">0004\n" +
                "<31000000043200000004540000001a00013100000000000001000000170004ffffffff0000440000000b00010000000131440000000b00010000000131430000000d53454c4543542032005a0000000549\n" +
                ">50\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">2a\n" +
                ">0073\n" +
                ">65\n" +
                ">6c\n" +
                ">65\n" +
                ">63\n" +
                ">74\n" +
                ">20\n" +
                ">31\n" +
                ">2c\n" +
                ">32\n" +
                ">2c\n" +
                ">3320\n" +
                ">6672\n" +
                ">6f\n" +
                ">6d\n" +
                ">20\n" +
                ">6c6f\n" +
                ">6e\n" +
                ">67\n" +
                ">5f\n" +
                ">73\n" +
                ">65\n" +
                ">71\n" +
                ">75\n" +
                ">65\n" +
                ">6e\n" +
                ">63\n" +
                ">65\n" +
                ">28\n" +
                ">31\n" +
                ">29\n" +
                ">000000420000000c\n" +
                ">0000000000\n" +
                ">00\n" +
                ">000044\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">06\n" +
                ">50\n" +
                ">00\n" +
                ">45\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">0900\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">530000\n" +
                ">00\n" +
                ">04\n" +
                "<31000000043200000004540000004200033100000000000001000000170004ffffffff00003200000000000002000000170004ffffffff00003300000000000003000000170004ffffffff000044000000150003000000013100000001320000000133430000000d53454c4543542031005a0000000549\n" +
                ">50\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">2600\n" +
                ">7365\n" +
                ">6c\n" +
                ">6563\n" +
                ">74\n" +
                ">20\n" +
                ">3120\n" +
                ">66\n" +
                ">72\n" +
                ">6f\n" +
                ">6d\n" +
                ">20\n" +
                ">6c\n" +
                ">6f\n" +
                ">6e\n" +
                ">67\n" +
                ">5f\n" +
                ">73\n" +
                ">65\n" +
                ">71\n" +
                ">75\n" +
                ">65\n" +
                ">6e\n" +
                ">63\n" +
                ">65\n" +
                ">28\n" +
                ">32\n" +
                ">29\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">420000\n" +
                ">00\n" +
                ">0c\n" +
                ">00\n" +
                ">00\n" +
                ">0000\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">4400\n" +
                ">00\n" +
                ">00\n" +
                ">06\n" +
                ">50\n" +
                ">00\n" +
                ">45\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">09\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">0000\n" +
                ">5300\n" +
                ">00\n" +
                ">00\n" +
                ">04\n" +
                "<31000000043200000004540000001a00013100000000000001000000170004ffffffff0000440000000b00010000000131440000000b00010000000131430000000d53454c4543542032005a0000000549\n" +
                ">50\n" +
                ">0000\n" +
                ">00\n" +
                ">2d\n" +
                ">53\n" +
                ">5f\n" +
                ">31\n" +
                ">0073\n" +
                ">65\n" +
                ">6c\n" +
                ">65\n" +
                ">63\n" +
                ">74\n" +
                ">20\n" +
                ">31\n" +
                ">2c\n" +
                ">32\n" +
                ">2c\n" +
                ">33\n" +
                ">2066\n" +
                ">72\n" +
                ">6f\n" +
                ">6d\n" +
                ">206c\n" +
                ">6f\n" +
                ">6e\n" +
                ">675f\n" +
                ">7365\n" +
                ">71\n" +
                ">75\n" +
                ">65\n" +
                ">6e\n" +
                ">63\n" +
                ">65\n" +
                ">28\n" +
                ">31\n" +
                ">2900\n" +
                ">00\n" +
                ">00\n" +
                ">420000000f0053\n" +
                ">5f\n" +
                ">31\n" +
                ">0000\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">44\n" +
                ">0000\n" +
                ">0006\n" +
                ">50\n" +
                ">00\n" +
                ">4500\n" +
                ">00\n" +
                ">00\n" +
                ">09\n" +
                ">0000\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">530000\n" +
                ">0004\n" +
                "<31000000043200000004540000004200033100000000000001000000170004ffffffff00003200000000000002000000170004ffffffff00003300000000000003000000170004ffffffff000044000000150003000000013100000001320000000133430000000d53454c4543542031005a0000000549\n" +
                ">5000\n" +
                ">00\n" +
                ">00\n" +
                ">26\n" +
                ">00\n" +
                ">73\n" +
                ">656c\n" +
                ">65\n" +
                ">63\n" +
                ">74\n" +
                ">20\n" +
                ">31\n" +
                ">20\n" +
                ">66\n" +
                ">72\n" +
                ">6f\n" +
                ">6d\n" +
                ">20\n" +
                ">6c\n" +
                ">6f\n" +
                ">6e\n" +
                ">67\n" +
                ">5f\n" +
                ">73\n" +
                ">65\n" +
                ">71\n" +
                ">75\n" +
                ">65\n" +
                ">6e\n" +
                ">6365\n" +
                ">28\n" +
                ">32\n" +
                ">29\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">420000\n" +
                ">00\n" +
                ">0c\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">44\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">06\n" +
                ">50\n" +
                ">00\n" +
                ">45\n" +
                ">0000\n" +
                ">0009\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">5300\n" +
                ">00\n" +
                ">00\n" +
                ">04\n" +
                "<31000000043200000004540000001a00013100000000000001000000170004ffffffff0000440000000b00010000000131440000000b00010000000131430000000d53454c4543542032005a0000000549\n" +
                ">4200\n" +
                ">00\n" +
                ">00\n" +
                ">0f\n" +
                ">00\n" +
                ">53\n" +
                ">5f\n" +
                ">31\n" +
                ">0000\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">45000000\n" +
                ">0900\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">0053\n" +
                ">00000004\n" +
                "<320000000444000000150003000000013100000001320000000133430000000d53454c4543542031005a0000000549\n" +
                ">5000\n" +
                ">00\n" +
                ">00\n" +
                ">26\n" +
                ">00\n" +
                ">73\n" +
                ">65\n" +
                ">6c\n" +
                ">65\n" +
                ">63\n" +
                ">74\n" +
                ">20\n" +
                ">31\n" +
                ">20\n" +
                ">66\n" +
                ">72\n" +
                ">6f\n" +
                ">6d\n" +
                ">20\n" +
                ">6c6f6e675f73657175656e63\n" +
                ">65283229000000\n" +
                ">420000000c00\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">44\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">06\n" +
                ">50\n" +
                ">00\n" +
                ">45\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">09\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">53000000\n" +
                ">04\n" +
                "<31000000043200000004540000001a00013100000000000001000000170004ffffffff0000440000000b00010000000131440000000b00010000000131430000000d53454c4543542032005a0000000549\n" +
                ">42\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">0f\n" +
                ">00\n" +
                ">53\n" +
                ">5f\n" +
                ">31\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">45\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">09\n" +
                ">00\n" +
                ">00\n" +
                ">000000\n" +
                ">5300\n" +
                ">0000\n" +
                ">04\n" +
                "<320000000444000000150003000000013100000001320000000133430000000d53454c4543542031005a0000000549\n" +
                ">5000\n" +
                ">00\n" +
                ">00\n" +
                ">26\n" +
                ">00\n" +
                ">73\n" +
                ">65\n" +
                ">6c\n" +
                ">65\n" +
                ">63\n" +
                ">74\n" +
                ">20\n" +
                ">31\n" +
                ">20\n" +
                ">66\n" +
                ">72\n" +
                ">6f\n" +
                ">6d\n" +
                ">20\n" +
                ">6c\n" +
                ">6f\n" +
                ">6e\n" +
                ">67\n" +
                ">5f\n" +
                ">73\n" +
                ">65\n" +
                ">71\n" +
                ">75\n" +
                ">65\n" +
                ">6e\n" +
                ">63\n" +
                ">65\n" +
                ">28\n" +
                ">32\n" +
                ">29\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">420000\n" +
                ">00\n" +
                ">0c\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">44\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">06\n" +
                ">50\n" +
                ">00\n" +
                ">45\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">09\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">5300\n" +
                ">00\n" +
                ">00\n" +
                ">04\n" +
                "<31000000043200000004540000001a00013100000000000001000000170004ffffffff0000440000000b00010000000131440000000b00010000000131430000000d53454c4543542032005a0000000549\n" +
                ">42\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">0f\n" +
                ">00\n" +
                ">53\n" +
                ">5f\n" +
                ">31\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">45\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">09\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">53\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">04\n" +
                "<320000000444000000150003000000013100000001320000000133430000000d53454c4543542031005a0000000549\n" +
                ">50\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">2600\n" +
                ">73\n" +
                ">65\n" +
                ">6c\n" +
                ">65\n" +
                ">63\n" +
                ">74\n" +
                ">20\n" +
                ">31\n" +
                ">20\n" +
                ">66\n" +
                ">72\n" +
                ">6f\n" +
                ">6d\n" +
                ">20\n" +
                ">6c\n" +
                ">6f\n" +
                ">6e\n" +
                ">67\n" +
                ">5f\n" +
                ">73\n" +
                ">65\n" +
                ">71\n" +
                ">75\n" +
                ">65\n" +
                ">6e\n" +
                ">63\n" +
                ">65\n" +
                ">28\n" +
                ">32\n" +
                ">29\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">420000\n" +
                ">00\n" +
                ">0c\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">0000\n" +
                ">0000\n" +
                ">00\n" +
                ">4400\n" +
                ">00\n" +
                ">00\n" +
                ">06\n" +
                ">50\n" +
                ">00\n" +
                ">45\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">09\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">5300\n" +
                ">00\n" +
                ">00\n" +
                ">04\n" +
                "<31000000043200000004540000001a00013100000000000001000000170004ffffffff0000440000000b00010000000131440000000b00010000000131430000000d53454c4543542032005a0000000549\n" +
                ">42\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">0f\n" +
                ">00\n" +
                ">53\n" +
                ">5f\n" +
                ">31\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">45\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">09\n" +
                ">0000\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">5300\n" +
                ">00\n" +
                ">00\n" +
                ">04\n" +
                "<320000000444000000150003000000013100000001320000000133430000000d53454c4543542031005a0000000549\n" +
                ">50\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">26\n" +
                ">00\n" +
                ">73\n" +
                ">65\n" +
                ">6c\n" +
                ">65\n" +
                ">63\n" +
                ">74\n" +
                ">2031\n" +
                ">20\n" +
                ">66\n" +
                ">72\n" +
                ">6f\n" +
                ">6d\n" +
                ">20\n" +
                ">6c6f6e675f\n" +
                ">73\n" +
                ">65\n" +
                ">71\n" +
                ">75\n" +
                ">65\n" +
                ">6e\n" +
                ">63\n" +
                ">65\n" +
                ">28\n" +
                ">32\n" +
                ">29\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">420000\n" +
                ">00\n" +
                ">0c\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">44\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">06\n" +
                ">50\n" +
                ">00\n" +
                ">45\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">09\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">5300\n" +
                ">00\n" +
                ">00\n" +
                ">04\n" +
                "<31000000043200000004540000001a00013100000000000001000000170004ffffffff0000440000000b00010000000131440000000b00010000000131430000000d53454c4543542032005a0000000549\n" +
                ">42\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">0f\n" +
                ">00\n" +
                ">53\n" +
                ">5f\n" +
                ">31\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">45\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">09\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">53\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">04\n" +
                "<320000000444000000150003000000013100000001320000000133430000000d53454c4543542031005a0000000549\n" +
                ">50\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">26\n" +
                ">00\n" +
                ">73\n" +
                ">65\n" +
                ">6c\n" +
                ">65\n" +
                ">63\n" +
                ">74\n" +
                ">20\n" +
                ">31\n" +
                ">20\n" +
                ">66\n" +
                ">72\n" +
                ">6f\n" +
                ">6d\n" +
                ">20\n" +
                ">6c\n" +
                ">6f\n" +
                ">6e\n" +
                ">67\n" +
                ">5f\n" +
                ">73\n" +
                ">65\n" +
                ">71\n" +
                ">75\n" +
                ">65\n" +
                ">6e\n" +
                ">63\n" +
                ">65\n" +
                ">28\n" +
                ">32\n" +
                ">29\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">420000\n" +
                ">00\n" +
                ">0c\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">44\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">06\n" +
                ">50\n" +
                ">00\n" +
                ">45\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">09\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">00\n" +
                ">5300\n" +
                ">00\n" +
                ">00\n" +
                ">04\n" +
                "<31000000043200000004540000001a00013100000000000001000000170004ffffffff0000440000000b00010000000131440000000b00010000000131430000000d53454c4543542032005a0000000549\n" +
                ">5800000004\n";
        assertHexScript(
                getFragmentedSendFacade(),
                script,
                getHexPgWireConfig()
        );
    }

    @Test
    public void testAllTypesSelectExtended() throws Exception {
        testAllTypesSelect(false);
    }

    @Test
    public void testAllTypesSelectSimple() throws Exception {
        testAllTypesSelect(true);
    }

    @Test
    public void testBadMessageLength() throws Exception {
        final String script =
                ">0000006900030000757365720078797a006461746162617365006e6162755f61707000636c69656e745f656e636f64696e67005554463800446174655374796c650049534f0054696d655a6f6e6500474d540065787472615f666c6f61745f64696769747300320000\n" +
                        "<520000000800000003\n" +
                        ">70000000006f6800\n" +
                        "<!!";
        assertHexScript(
                getFragmentedSendFacade(),
                script,
                new DefaultPGWireConfiguration()
        );
    }

    @Test
    public void testBadPasswordLength() throws Exception {
        assertHexScript(
                NetworkFacadeImpl.INSTANCE,
                ">0000000804d2162f\n" +
                        "<4e\n" +
                        ">0000007500030000757365720061646d696e006461746162617365006e6162755f61707000636c69656e745f656e636f64696e67005554463800446174655374796c650049534f0054696d655a6f6e65004575726f70652f4c6f6e646f6e0065787472615f666c6f61745f64696769747300320000\n" +
                        "<520000000800000003\n" +
                        ">700000000464756e6e6f00\n" +
                        "<!!",
                new DefaultPGWireConfiguration()
        );
    }

    @Test
    public void testBasicFetch() throws Exception {
        assertMemoryLeak(() -> {
            try (
                    final PGWireServer ignored = createPGServer(1);
                    final Connection connection = getConnection(false, true)
            ) {
                connection.setAutoCommit(false);
                int totalRows = 100;

                PreparedStatement tbl = connection.prepareStatement("create table x (a int)");
                tbl.execute();

                PreparedStatement insert = connection.prepareStatement("insert into x(a) values(?)");
                for (int i = 0; i < totalRows; i++) {
                    insert.setInt(1, i);
                    insert.execute();
                }
                connection.commit();
                PreparedStatement stmt = connection.prepareStatement("x");
                int[] testSizes = {0, 1, 49, 50, 51, 99, 100, 101};
                for (int testSize : testSizes) {
                    stmt.setFetchSize(testSize);
                    assertEquals(testSize, stmt.getFetchSize());

                    ResultSet rs = stmt.executeQuery();
                    assertEquals(testSize, rs.getFetchSize());

                    int count = 0;
                    while (rs.next()) {
                        assertEquals(count, rs.getInt(1));
                        ++count;
                    }

                    assertEquals(totalRows, count);
                }
            }
        });
    }

    @Test
    public void testBatchInsertWithTransaction() throws Exception {
        assertMemoryLeak(() -> {

            try (
                    final PGWireServer ignored = createPGServer(2);
                    final Connection connection = getConnection(false, true)
            ) {
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate("create table test (id long,val int)");
                    statement.executeUpdate("create table test2(id long,val int)");
                }

                connection.setAutoCommit(false);
                try (PreparedStatement batchInsert = connection.prepareStatement("insert into test(id,val) values(?,?)")) {
                    batchInsert.setLong(1, 0L);
                    batchInsert.setInt(2, 1);
                    batchInsert.addBatch();
                    batchInsert.setLong(1, 1L);
                    batchInsert.setInt(2, 2);
                    batchInsert.addBatch();
                    batchInsert.setLong(1, 2L);
                    batchInsert.setInt(2, 3);
                    batchInsert.addBatch();
                    batchInsert.clearParameters();
                    batchInsert.executeLargeBatch();
                }

                try (PreparedStatement batchInsert = connection.prepareStatement("insert into test2(id,val) values(?,?)")) {
                    batchInsert.setLong(1, 0L);
                    batchInsert.setInt(2, 1);
                    batchInsert.addBatch();
                    batchInsert.setLong(1, 1L);
                    batchInsert.setInt(2, 2);
                    batchInsert.addBatch();
                    batchInsert.setLong(1, 2L);
                    batchInsert.setInt(2, 3);
                    batchInsert.addBatch();
                    batchInsert.clearParameters();
                    batchInsert.executeLargeBatch();
                }

                connection.commit();

                connection.setAutoCommit(true);
                StringSink sink = new StringSink();
                String expected = "id[BIGINT],val[INTEGER]\n" +
                        "0,1\n" +
                        "1,2\n" +
                        "2,3\n";
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("select * from test");
                assertResultSet(expected, sink, rs);

                sink.clear();
                Statement statement2 = connection.createStatement();
                ResultSet rs2 = statement2.executeQuery("select * from test2");
                assertResultSet(expected, sink, rs2);

                //now switch on autocommit and check that data is inserted without explicitly calling commit()
                connection.setAutoCommit(true);
                try (PreparedStatement batchInsert = connection.prepareStatement("insert into test(id,val) values(?,?)")) {
                    batchInsert.setLong(1, 3L);
                    batchInsert.setInt(2, 4);
                    batchInsert.addBatch();
                    batchInsert.setLong(1, 4L);
                    batchInsert.setInt(2, 5);
                    batchInsert.addBatch();
                    batchInsert.setLong(1, 5L);
                    batchInsert.setInt(2, 6);
                    batchInsert.addBatch();
                    batchInsert.clearParameters();
                    batchInsert.executeLargeBatch();
                }

                sink.clear();
                expected = "id[BIGINT],val[INTEGER]\n" +
                        "0,1\n" +
                        "1,2\n" +
                        "2,3\n" +
                        "3,4\n" +
                        "4,5\n" +
                        "5,6\n";
                Statement statement3 = connection.createStatement();
                ResultSet rs3 = statement3.executeQuery("select * from test");
                assertResultSet(expected, sink, rs3);

                //now fail insertion during transaction
                try (Statement statement4 = connection.createStatement()) {
                    statement4.executeUpdate("create table anothertab(id long, val int, k timestamp) timestamp(k) ");
                }
                connection.setAutoCommit(false);
                try (PreparedStatement batchInsert = connection.prepareStatement("insert into anothertab(id, val, k) values(?,?,?)")) {
                    batchInsert.setLong(1, 3L);
                    batchInsert.setInt(2, 4);
                    batchInsert.setLong(3, 1_000L);
                    batchInsert.addBatch();
                    batchInsert.setLong(1, 4L);
                    batchInsert.setInt(2, 5);
                    batchInsert.setLong(3, 0L);
                    batchInsert.addBatch();
                    batchInsert.setLong(1, 5L);
                    batchInsert.setInt(2, 6);
                    batchInsert.setLong(3, 2_000L);
                    batchInsert.addBatch();
                    batchInsert.clearParameters();
                    batchInsert.executeLargeBatch();
                    Assert.fail();
                } catch (Exception e) {
                    LOG.error().$(e).$();
                }
                //now transaction fail, we should rollback transaction
                connection.rollback();
                connection.setAutoCommit(true);
                sink.clear();
                expected = "id[BIGINT],val[INTEGER],k[TIMESTAMP]\n";
                Statement statement4 = connection.createStatement();
                ResultSet rs4 = statement4.executeQuery("select * from anothertab");
                assertResultSet(expected, sink, rs4);
            }
        });
    }

    @Test
    public void testBindVariableInFilterBinaryTransfer() throws Exception {
        testBindVariableInFilter(true);
    }

    @Test
    public void testBindVariableInFilterStringTransfer() throws Exception {
        testBindVariableInFilter(false);
    }

    @Test
    public void testBindVariableIsNotNullBinaryTransfer() throws Exception {
        testBindVariableIsNotNull(true);
    }

    @Test
    public void testBindVariableIsNotNullStringTransfer() throws Exception {
        testBindVariableIsNotNull(false);
    }

    @Test
    public void testBindVariableIsNullBinaryTransfer() throws Exception {
        testBindVariableIsNull(true);
    }

    @Test
    public void testBindVariableIsNullStringTransfer() throws Exception {
        testBindVariableIsNull(false);
    }

    @Test
    public void testBindVariablesWithIndexedSymbolInFilterBinaryTransfer() throws Exception {
        testBindVariablesWithIndexedSymbolInFilter(true, true);
    }

    @Test
    public void testBindVariablesWithIndexedSymbolInFilterStringTransfer() throws Exception {
        testBindVariablesWithIndexedSymbolInFilter(false, true);
    }

    @Test
    public void testBindVariablesWithNonIndexedSymbolInFilterBinaryTransfer() throws Exception {
        testBindVariablesWithIndexedSymbolInFilter(true, false);
    }

    @Test
    public void testBindVariablesWithNonIndexedSymbolInFilterStringTransfer() throws Exception {
        testBindVariablesWithIndexedSymbolInFilter(false, false);
    }

    @Test
    public void testBlobOverLimit() throws Exception {
        PGWireConfiguration configuration = new DefaultPGWireConfiguration() {
            @Override
            public int getMaxBlobSizeOnQuery() {
                return 150;
            }
        };

        TestUtils.assertMemoryLeak(() -> {
            try (
                    final PGWireServer ignored = createPGServer(configuration);
                    final Connection connection = getConnection(false, true)
            ) {
                Statement statement = connection.createStatement();
                statement.executeQuery(
                        "select " +
                                "rnd_str(4,4,4) s, " +
                                "rnd_int(0, 256, 4) i, " +
                                "rnd_double(4) d, " +
                                "timestamp_sequence(0,10000) t, " +
                                "rnd_float(4) f, " +
                                "rnd_short() _short, " +
                                "rnd_long(0, 10000000, 5) l, " +
                                "rnd_timestamp(to_timestamp('2015','yyyy'),to_timestamp('2016','yyyy'),2) ts2, " +
                                "rnd_byte(0,127) bb, " +
                                "rnd_boolean() b, " +
                                "rnd_symbol(4,4,4,2), " +
                                "rnd_date(to_date('2015', 'yyyy'), to_date('2016', 'yyyy'), 2)," +
                                "rnd_bin(1024,2048,2) " +
                                "from long_sequence(50)");

                Assert.fail();
            } catch (PSQLException e) {
                Assert.assertNotNull(e.getServerErrorMessage());
                TestUtils.assertContains(e.getServerErrorMessage().getMessage(), "blob is too large");
            }
        });
    }

    @Test
    public void testBrokenUtf8QueryInParseMessage() throws Exception {
        assertHexScript(
                NetworkFacadeImpl.INSTANCE,
                ">0000000804d2162f\n" +
                        "<4e\n" +
                        ">0000007500030000757365720061646d696e006461746162617365006e6162755f61707000636c69656e745f656e636f64696e67005554463800446174655374796c650049534f0054696d655a6f6e65004575726f70652f4c6f6e646f6e0065787472615f666c6f61745f64696769747300320000\n" +
                        "<520000000800000003\n" +
                        ">700000000a717565737400\n" +
                        "<520000000800000000530000001154696d655a6f6e6500474d5400530000001d6170706c69636174696f6e5f6e616d6500517565737444420053000000187365727665725f76657273696f6e0031312e33005300000019696e74656765725f6461746574696d6573006f6e005300000019636c69656e745f656e636f64696e670055544638005a0000000549\n" +
                        ">50000000220053ac542065787472615f666c6f61745f646967697473203d2033000000420000000c0000000000000000450000000900000000015300000004\n" +
                        "<!!"
                , new DefaultPGWireConfiguration()
        );
    }

    @Test
    public void testCairoException() throws Exception {
        assertMemoryLeak(() -> {
            try (
                    final PGWireServer ignored = createPGServer(2);
                    final Connection connection = getConnection(false, true)
            ) {

                connection.prepareStatement("create table xyz(a int)").execute();
                try (TableWriter ignored1 = engine.getWriter(sqlExecutionContext.getCairoSecurityContext(), "xyz", "testing")) {
                    connection.prepareStatement("drop table xyz").execute();
                    Assert.fail();
                } catch (SQLException e) {
                    TestUtils.assertContains(e.getMessage(), "Could not lock 'xyz'");
                    Assert.assertEquals("00000", e.getSQLState());
                }
            }
        });
    }

    @Test
    public void testCharIntLongDoubleBooleanParametersWithoutExplicitParameterTypeHex() throws Exception {
        String script = ">0000006e00030000757365720078797a0064617461626173650071646200636c69656e745f656e636f64696e67005554463800446174655374796c650049534f0054696d655a6f6e65004575726f70652f4c6f6e646f6e0065787472615f666c6f61745f64696769747300320000\n" +
                "<520000000800000003\n" +
                ">70000000076f6800\n" +
                "<520000000800000000530000001154696d655a6f6e6500474d5400530000001d6170706c69636174696f6e5f6e616d6500517565737444420053000000187365727665725f76657273696f6e0031312e33005300000019696e74656765725f6461746574696d6573006f6e005300000019636c69656e745f656e636f64696e670055544638005a0000000549\n" +
                ">50000000300073656c65637420782c202024312c2024322066726f6d206c6f6e675f73657175656e63652832293b000000\n" +
                ">4200000021000000010000000200000001330000000a35303030303030303030000044000000065000450000000900000000004800000004\n" +
                "<31000000043200000004540000004400037800000000000001000000140008ffffffff000024310000000000000200000413ffffffffffff000024320000000000000300000413ffffffffffff0000440000001e0003000000013100000001330000000a35303030303030303030440000001e0003000000013200000001330000000a35303030303030303030430000000d53454c454354203200\n";

        assertHexScript(
                NetworkFacadeImpl.INSTANCE,
                script,
                getHexPgWireConfig()
        );
    }

    @Test
    public void testCloseMessageFollowedByNewQueryHex() throws Exception {
        String script = ">0000006e00030000757365720078797a0064617461626173650071646200636c69656e745f656e636f64696e67005554463800446174655374796c650049534f0054696d655a6f6e65004575726f70652f4c6f6e646f6e0065787472615f666c6f61745f64696769747300320000\n" +
                "<520000000800000003\n" +
                ">70000000076f6800\n" +
                "<520000000800000000530000001154696d655a6f6e6500474d5400530000001d6170706c69636174696f6e5f6e616d6500517565737444420053000000187365727665725f76657273696f6e0031312e33005300000019696e74656765725f6461746574696d6573006f6e005300000019636c69656e745f656e636f64696e670055544638005a0000000549\n" +
                ">5000000022005345542065787472615f666c6f61745f646967697473203d2033000000420000000c0000000000000000450000000900000000015300000004\n" +
                "<310000000432000000044300000008534554005a0000000549\n" +
                ">500000003700534554206170706c69636174696f6e5f6e616d65203d2027506f737467726553514c204a4442432044726976657227000000420000000c0000000000000000450000000900000000015300000004\n" +
                "<310000000432000000044300000008534554005a0000000549\n" +
                ">500000002a0073656c65637420312c322c332066726f6d206c6f6e675f73657175656e6365283129000000420000000c000000000000000044000000065000450000000900000000005300000004\n" +
                "<31000000043200000004540000004200033100000000000001000000170004ffffffff00003200000000000002000000170004ffffffff00003300000000000003000000170004ffffffff000044000000150003000000013100000001320000000133430000000d53454c4543542031005a0000000549\n" +
                ">500000002d535f310073656c65637420312c322c332066726f6d206c6f6e675f73657175656e6365283129000000420000000f00535f310000000000000044000000065000450000000900000000005300000004\n" +
                "<31000000043200000004540000004200033100000000000001000000170004ffffffff00003200000000000002000000170004ffffffff00003300000000000003000000170004ffffffff000044000000150003000000013100000001320000000133430000000d53454c4543542031005a0000000549\n" +
                ">420000000f00535f3100000000000000450000000900000000005300000004\n" +
                "<320000000444000000150003000000013100000001320000000133430000000d53454c4543542031005a0000000549\n" +
                ">420000000f00535f3100000000000000450000000900000000005300000004\n" +
                "<320000000444000000150003000000013100000001320000000133430000000d53454c4543542031005a0000000549\n" +
                ">430000000953535f310050000000260073656c65637420312066726f6d206c6f6e675f73657175656e6365283229000000420000000c000000000000000044000000065000450000000900000000005300000004\n" +
                "<330000000431000000043200000004540000001a00013100000000000001000000170004ffffffff0000440000000b00010000000131440000000b00010000000131430000000d53454c4543542032005a0000000549\n" +
                ">5800000004\n";
        assertHexScript(
                NetworkFacadeImpl.INSTANCE,
                script,
                getHexPgWireConfig()
        );
    }

    @Test
    public void testCloseMessageForPortalHex() throws Exception {
        String script = ">0000006e00030000757365720078797a0064617461626173650071646200636c69656e745f656e636f64696e67005554463800446174655374796c650049534f0054696d655a6f6e65004575726f70652f4c6f6e646f6e0065787472615f666c6f61745f64696769747300320000\n" +
                "<520000000800000003\n" +
                ">70000000076f6800\n" +
                "<520000000800000000530000001154696d655a6f6e6500474d5400530000001d6170706c69636174696f6e5f6e616d6500517565737444420053000000187365727665725f76657273696f6e0031312e33005300000019696e74656765725f6461746574696d6573006f6e005300000019636c69656e745f656e636f64696e670055544638005a0000000549\n" +
                ">5000000022005345542065787472615f666c6f61745f646967697473203d2033000000420000000c0000000000000000450000000900000000015300000004\n" +
                "<310000000432000000044300000008534554005a0000000549\n" +
                ">500000003700534554206170706c69636174696f6e5f6e616d65203d2027506f737467726553514c204a4442432044726976657227000000420000000c0000000000000000450000000900000000015300000004\n" +
                "<310000000432000000044300000008534554005a0000000549\n" +
                ">500000002a0073656c65637420312c322c332066726f6d206c6f6e675f73657175656e6365283129000000420000000c000000000000000044000000065000450000000900000000005300000004\n" +
                "<31000000043200000004540000004200033100000000000001000000170004ffffffff00003200000000000002000000170004ffffffff00003300000000000003000000170004ffffffff000044000000150003000000013100000001320000000133430000000d53454c4543542031005a0000000549\n" +
                ">500000002d535f310073656c65637420312c322c332066726f6d206c6f6e675f73657175656e6365283129000000420000000f00535f310000000000000044000000065000450000000900000000005300000004\n" +
                "<31000000043200000004540000004200033100000000000001000000170004ffffffff00003200000000000002000000170004ffffffff00003300000000000003000000170004ffffffff000044000000150003000000013100000001320000000133430000000d53454c4543542031005a0000000549\n" +
                ">420000000f00535f3100000000000000450000000900000000005300000004\n" +
                "<320000000444000000150003000000013100000001320000000133430000000d53454c4543542031005a0000000549\n" +
                ">420000000f00535f3100000000000000450000000900000000005300000004\n" +
                "<320000000444000000150003000000013100000001320000000133430000000d53454c4543542031005a0000000549\n" +
                ">430000000950535f31005300000004\n";
        assertHexScript(
                NetworkFacadeImpl.INSTANCE,
                script,
                getHexPgWireConfig()
        );
    }

    @Test
    public void testCloseMessageForSelectWithParamsHex() throws Exception {
        //hex for close message 43 00000009 53 535f31 00
        String script = ">0000006e00030000757365720078797a0064617461626173650071646200636c69656e745f656e636f64696e67005554463800446174655374796c650049534f0054696d655a6f6e65004575726f70652f4c6f6e646f6e0065787472615f666c6f61745f64696769747300320000\n" +
                "<520000000800000003\n" +
                ">70000000076f6800\n" +
                "<520000000800000000530000001154696d655a6f6e6500474d5400530000001d6170706c69636174696f6e5f6e616d6500517565737444420053000000187365727665725f76657273696f6e0031312e33005300000019696e74656765725f6461746574696d6573006f6e005300000019636c69656e745f656e636f64696e670055544638005a0000000549\n" +
                ">5000000022005345542065787472615f666c6f61745f646967697473203d2033000000420000000c0000000000000000450000000900000000015300000004\n" +
                "<310000000432000000044300000008534554005a0000000549\n" +
                ">500000003700534554206170706c69636174696f6e5f6e616d65203d2027506f737467726553514c204a4442432044726976657227000000420000000c0000000000000000450000000900000000015300000004\n" +
                "<310000000432000000044300000008534554005a0000000549\n" +
                ">500000003b0073656c65637420782c24312c24322c24332066726f6d206c6f6e675f73657175656e63652832290000030000001700000014000002bd420000002600000003000000000000000300000001340000000331323300000004352e3433000044000000065000450000000900000000005300000004\n" +
                "<31000000043200000004540000005900047800000000000001000000140008ffffffff000024310000000000000200000413ffffffffffff000024320000000000000300000413ffffffffffff000024330000000000000400000413ffffffffffff0000440000001f0004000000013100000001340000000331323300000004352e3433440000001f0004000000013200000001340000000331323300000004352e3433430000000d53454c4543542032005a0000000549\n" +
                ">500000003b0073656c65637420782c24312c24322c24332066726f6d206c6f6e675f73657175656e63652832290000030000001700000014000002bd420000002600000003000000000000000300000001340000000331323300000004352e3433000044000000065000450000000900000000005300000004\n" +
                "<31000000043200000004540000005900047800000000000001000000140008ffffffff000024310000000000000200000413ffffffffffff000024320000000000000300000413ffffffffffff000024330000000000000400000413ffffffffffff0000440000001f0004000000013100000001340000000331323300000004352e3433440000001f0004000000013200000001340000000331323300000004352e3433430000000d53454c4543542032005a0000000549\n" +
                ">500000003b0073656c65637420782c24312c24322c24332066726f6d206c6f6e675f73657175656e63652832290000030000001700000014000002bd420000002600000003000000000000000300000001340000000331323300000004352e3433000044000000065000450000000900000000005300000004\n" +
                "<31000000043200000004540000005900047800000000000001000000140008ffffffff000024310000000000000200000413ffffffffffff000024320000000000000300000413ffffffffffff000024330000000000000400000413ffffffffffff0000440000001f0004000000013100000001340000000331323300000004352e3433440000001f0004000000013200000001340000000331323300000004352e3433430000000d53454c4543542032005a0000000549\n" +
                ">500000003b0073656c65637420782c24312c24322c24332066726f6d206c6f6e675f73657175656e63652832290000030000001700000014000002bd420000002600000003000000000000000300000001340000000331323300000004352e3433000044000000065000450000000900000000005300000004\n" +
                "<31000000043200000004540000005900047800000000000001000000140008ffffffff000024310000000000000200000413ffffffffffff000024320000000000000300000413ffffffffffff000024330000000000000400000413ffffffffffff0000440000001f0004000000013100000001340000000331323300000004352e3433440000001f0004000000013200000001340000000331323300000004352e3433430000000d53454c4543542032005a0000000549\n" +
                ">500000003e535f310073656c65637420782c24312c24322c24332066726f6d206c6f6e675f73657175656e63652832290000030000001700000014000002bd420000002900535f31000003000000000000000300000001340000000331323300000004352e3433000044000000065000450000000900000000005300000004\n" +
                "<31000000043200000004540000005900047800000000000001000000140008ffffffff000024310000000000000200000413ffffffffffff000024320000000000000300000413ffffffffffff000024330000000000000400000413ffffffffffff0000440000001f0004000000013100000001340000000331323300000004352e3433440000001f0004000000013200000001340000000331323300000004352e3433430000000d53454c4543542032005a0000000549\n" +
                ">420000002900535f31000003000000000000000300000001340000000331323300000004352e34330000450000000900000000005300000004\n" +
                "<3200000004440000001f0004000000013100000001340000000331323300000004352e3433440000001f0004000000013200000001340000000331323300000004352e3433430000000d53454c4543542032005a0000000549\n" +
                ">430000000953535f31005300000004\n" +
                "<33000000045a0000000549\n" +
                ">5800000004\n";
        assertHexScript(
                NetworkFacadeImpl.INSTANCE,
                script,
                getHexPgWireConfig()
        );
    }

    @Test
    public void testCloseMessageHex() throws Exception {
        //hex for close message 43 00000009 53 535f31 00
        String script = ">0000006e00030000757365720078797a0064617461626173650071646200636c69656e745f656e636f64696e67005554463800446174655374796c650049534f0054696d655a6f6e65004575726f70652f4c6f6e646f6e0065787472615f666c6f61745f64696769747300320000\n" +
                "<520000000800000003\n" +
                ">70000000076f6800\n" +
                "<520000000800000000530000001154696d655a6f6e6500474d5400530000001d6170706c69636174696f6e5f6e616d6500517565737444420053000000187365727665725f76657273696f6e0031312e33005300000019696e74656765725f6461746574696d6573006f6e005300000019636c69656e745f656e636f64696e670055544638005a0000000549\n" +
                ">5000000022005345542065787472615f666c6f61745f646967697473203d2033000000420000000c0000000000000000450000000900000000015300000004\n" +
                "<310000000432000000044300000008534554005a0000000549\n" +
                ">500000003700534554206170706c69636174696f6e5f6e616d65203d2027506f737467726553514c204a4442432044726976657227000000420000000c0000000000000000450000000900000000015300000004\n" +
                "<310000000432000000044300000008534554005a0000000549\n" +
                ">500000002a0073656c65637420312c322c332066726f6d206c6f6e675f73657175656e6365283129000000420000000c000000000000000044000000065000450000000900000000005300000004\n" +
                "<31000000043200000004540000004200033100000000000001000000170004ffffffff00003200000000000002000000170004ffffffff00003300000000000003000000170004ffffffff000044000000150003000000013100000001320000000133430000000d53454c4543542031005a0000000549\n" +
                ">500000002d535f310073656c65637420312c322c332066726f6d206c6f6e675f73657175656e6365283129000000420000000f00535f310000000000000044000000065000450000000900000000005300000004\n" +
                "<31000000043200000004540000004200033100000000000001000000170004ffffffff00003200000000000002000000170004ffffffff00003300000000000003000000170004ffffffff000044000000150003000000013100000001320000000133430000000d53454c4543542031005a0000000549\n" +
                ">420000000f00535f3100000000000000450000000900000000005300000004\n" +
                "<320000000444000000150003000000013100000001320000000133430000000d53454c4543542031005a0000000549\n" +
                ">420000000f00535f3100000000000000450000000900000000005300000004\n" +
                "<320000000444000000150003000000013100000001320000000133430000000d53454c4543542031005a0000000549\n" +
                ">430000000953535f31005300000004\n" +
                "<33000000045a0000000549\n" +
                ">5800000004\n";
        assertHexScript(
                NetworkFacadeImpl.INSTANCE,
                script,
                getHexPgWireConfig()
        );
    }

    @Test
    public void testCloseMessageWithBadUtf8InStatementNameHex() throws Exception {
        String script = ">0000006e00030000757365720078797a0064617461626173650071646200636c69656e745f656e636f64696e67005554463800446174655374796c650049534f0054696d655a6f6e65004575726f70652f4c6f6e646f6e0065787472615f666c6f61745f64696769747300320000\n" +
                "<520000000800000003\n" +
                ">70000000076f6800\n" +
                "<520000000800000000530000001154696d655a6f6e6500474d5400530000001d6170706c69636174696f6e5f6e616d6500517565737444420053000000187365727665725f76657273696f6e0031312e33005300000019696e74656765725f6461746574696d6573006f6e005300000019636c69656e745f656e636f64696e670055544638005a0000000549\n" +
                ">5000000022005345542065787472615f666c6f61745f646967697473203d2033000000420000000c0000000000000000450000000900000000015300000004\n" +
                "<310000000432000000044300000008534554005a0000000549\n" +
                ">500000003700534554206170706c69636174696f6e5f6e616d65203d2027506f737467726553514c204a4442432044726976657227000000420000000c0000000000000000450000000900000000015300000004\n" +
                "<310000000432000000044300000008534554005a0000000549\n" +
                ">500000002a0073656c65637420312c322c332066726f6d206c6f6e675f73657175656e6365283129000000420000000c000000000000000044000000065000450000000900000000005300000004\n" +
                "<31000000043200000004540000004200033100000000000001000000170004ffffffff00003200000000000002000000170004ffffffff00003300000000000003000000170004ffffffff000044000000150003000000013100000001320000000133430000000d53454c4543542031005a0000000549\n" +
                ">500000002d535f310073656c65637420312c322c332066726f6d206c6f6e675f73657175656e6365283129000000420000000f00535f310000000000000044000000065000450000000900000000005300000004\n" +
                "<31000000043200000004540000004200033100000000000001000000170004ffffffff00003200000000000002000000170004ffffffff00003300000000000003000000170004ffffffff000044000000150003000000013100000001320000000133430000000d53454c4543542031005a0000000549\n" +
                ">420000000f00535f3100000000000000450000000900000000005300000004\n" +
                "<320000000444000000150003000000013100000001320000000133430000000d53454c4543542031005a0000000549\n" +
                ">420000000f00535f3100000000000000450000000900000000005300000004\n" +
                "<320000000444000000150003000000013100000001320000000133430000000d53454c4543542031005a0000000549\n" +
                ">430000000953535fac005300000004\n" +
                "<!!";
        assertHexScript(
                NetworkFacadeImpl.INSTANCE,
                script,
                getHexPgWireConfig()
        );
    }

    @Test
    public void testCloseMessageWithInvalidStatementNameHex() throws Exception {
        String script = ">0000006e00030000757365720078797a0064617461626173650071646200636c69656e745f656e636f64696e67005554463800446174655374796c650049534f0054696d655a6f6e65004575726f70652f4c6f6e646f6e0065787472615f666c6f61745f64696769747300320000\n" +
                "<520000000800000003\n" +
                ">70000000076f6800\n" +
                "<520000000800000000530000001154696d655a6f6e6500474d5400530000001d6170706c69636174696f6e5f6e616d6500517565737444420053000000187365727665725f76657273696f6e0031312e33005300000019696e74656765725f6461746574696d6573006f6e005300000019636c69656e745f656e636f64696e670055544638005a0000000549\n" +
                ">5000000022005345542065787472615f666c6f61745f646967697473203d2033000000420000000c0000000000000000450000000900000000015300000004\n" +
                "<310000000432000000044300000008534554005a0000000549\n" +
                ">500000003700534554206170706c69636174696f6e5f6e616d65203d2027506f737467726553514c204a4442432044726976657227000000420000000c0000000000000000450000000900000000015300000004\n" +
                "<310000000432000000044300000008534554005a0000000549\n" +
                ">500000002a0073656c65637420312c322c332066726f6d206c6f6e675f73657175656e6365283129000000420000000c000000000000000044000000065000450000000900000000005300000004\n" +
                "<31000000043200000004540000004200033100000000000001000000170004ffffffff00003200000000000002000000170004ffffffff00003300000000000003000000170004ffffffff000044000000150003000000013100000001320000000133430000000d53454c4543542031005a0000000549\n" +
                ">500000002d535f310073656c65637420312c322c332066726f6d206c6f6e675f73657175656e6365283129000000420000000f00535f310000000000000044000000065000450000000900000000005300000004\n" +
                "<31000000043200000004540000004200033100000000000001000000170004ffffffff00003200000000000002000000170004ffffffff00003300000000000003000000170004ffffffff000044000000150003000000013100000001320000000133430000000d53454c4543542031005a0000000549\n" +
                ">420000000f00535f3100000000000000450000000900000000005300000004\n" +
                "<320000000444000000150003000000013100000001320000000133430000000d53454c4543542031005a0000000549\n" +
                ">420000000f00535f3100000000000000450000000900000000005300000004\n" +
                "<320000000444000000150003000000013100000001320000000133430000000d53454c4543542031005a0000000549\n" +
                ">430000000953535f32005300000004\n" +
                "<!!";
        assertHexScript(NetworkFacadeImpl.INSTANCE,
                script,
                getHexPgWireConfig());
    }

    @Test
    public void testCloseMessageWithInvalidTypeHex() throws Exception {
        String script = ">0000006e00030000757365720078797a0064617461626173650071646200636c69656e745f656e636f64696e67005554463800446174655374796c650049534f0054696d655a6f6e65004575726f70652f4c6f6e646f6e0065787472615f666c6f61745f64696769747300320000\n" +
                "<520000000800000003\n" +
                ">70000000076f6800\n" +
                "<520000000800000000530000001154696d655a6f6e6500474d5400530000001d6170706c69636174696f6e5f6e616d6500517565737444420053000000187365727665725f76657273696f6e0031312e33005300000019696e74656765725f6461746574696d6573006f6e005300000019636c69656e745f656e636f64696e670055544638005a0000000549\n" +
                ">5000000022005345542065787472615f666c6f61745f646967697473203d2033000000420000000c0000000000000000450000000900000000015300000004\n" +
                "<310000000432000000044300000008534554005a0000000549\n" +
                ">500000003700534554206170706c69636174696f6e5f6e616d65203d2027506f737467726553514c204a4442432044726976657227000000420000000c0000000000000000450000000900000000015300000004\n" +
                "<310000000432000000044300000008534554005a0000000549\n" +
                ">500000002a0073656c65637420312c322c332066726f6d206c6f6e675f73657175656e6365283129000000420000000c000000000000000044000000065000450000000900000000005300000004\n" +
                "<31000000043200000004540000004200033100000000000001000000170004ffffffff00003200000000000002000000170004ffffffff00003300000000000003000000170004ffffffff000044000000150003000000013100000001320000000133430000000d53454c4543542031005a0000000549\n" +
                ">500000002d535f310073656c65637420312c322c332066726f6d206c6f6e675f73657175656e6365283129000000420000000f00535f310000000000000044000000065000450000000900000000005300000004\n" +
                "<31000000043200000004540000004200033100000000000001000000170004ffffffff00003200000000000002000000170004ffffffff00003300000000000003000000170004ffffffff000044000000150003000000013100000001320000000133430000000d53454c4543542031005a0000000549\n" +
                ">420000000f00535f3100000000000000450000000900000000005300000004\n" +
                "<320000000444000000150003000000013100000001320000000133430000000d53454c4543542031005a0000000549\n" +
                ">420000000f00535f3100000000000000450000000900000000005300000004\n" +
                "<320000000444000000150003000000013100000001320000000133430000000d53454c4543542031005a0000000549\n" +
                ">430000000951535f31005300000004\n" +
                "<!!";
        assertHexScript(NetworkFacadeImpl.INSTANCE,
                script,
                getHexPgWireConfig());
    }

    @Test
    @Ignore
    public void testCopyIn() throws SQLException {
        try (
                final PGWireServer ignored = createPGServer(2);
                final Connection connection = getConnection(false, true)
        ) {
            PreparedStatement stmt = connection.prepareStatement("create table tab (a int, b int)");
            stmt.execute();

            CopyManager copyManager = new CopyManager((BaseConnection) connection);

            CopyIn copyIn = copyManager.copyIn("copy tab from STDIN");

            String text = "a,b\r\n" +
                    "10,20";

            byte[] bytes = text.getBytes();
            copyIn.writeToCopy(bytes, 0, bytes.length);
            copyIn.endCopy();
        }
    }

    @Test
    public void testCreateTableDuplicateColumnName() throws Exception {
        assertMemoryLeak(() -> {
            try (
                    final PGWireServer ignored = createPGServer(2);
                    final Connection conn = getConnection(false, true)
            ) {
                conn.prepareStatement("create table tab as (\n" +
                        "            select\n" +
                        "                rnd_byte() b,\n" +
                        "                rnd_boolean() B\n" +
                        "            from long_sequence(1)\n" +
                        "        )").execute();
            } catch (PSQLException e) {
                assertContains(e.getMessage(), "Duplicate column [name=B]");
            }
        });
    }

    @Test
    public void testCreateTableDuplicateColumnNameNonAscii() throws Exception {
        assertMemoryLeak(() -> {
            try (
                    final PGWireServer ignored = createPGServer(2);
                    final Connection conn = getConnection(false, true)
            ) {
                conn.prepareStatement("create table tab as (\n" +
                        "            select\n" +
                        "                rnd_byte() 侘寂,\n" +
                        "                rnd_boolean() 侘寂\n" +
                        "            from long_sequence(1)\n" +
                        "        )").execute();
            } catch (PSQLException e) {
                assertContains(e.getMessage(), "Duplicate column [name=侘寂]");
            }
        });
    }

    @Test
    public void testCursorFetch() throws Exception {
        assertMemoryLeak(() -> {
            try (
                    final PGWireServer ignored = createPGServer(1);
                    final Connection connection = getConnection(false, true)
            ) {
                connection.setAutoCommit(false);
                int totalRows = 10000;
                int fetchSize = 10;

                CallableStatement stmt = connection.prepareCall(
                        "create table x as (select" +
                                " cast(x as int) kk, " +
                                " rnd_int() a," +
                                " rnd_boolean() b," + // str
                                " rnd_str(1,1,2) c," + // str
                                " rnd_double(2) d," +
                                " rnd_float(2) e," +
                                " rnd_short(10,1024) f," +
                                " rnd_date(to_date('2015', 'yyyy'), to_date('2016', 'yyyy'), 2) g," +
                                " rnd_symbol(4,4,4,2) i," + // str
                                " rnd_long() j," +
                                " timestamp_sequence(889001, 8890012) k," +
                                " rnd_byte(2,50) l," +
                                " rnd_bin(10, 20, 2) m," +
                                " rnd_str(5,16,2) n," +
                                " rnd_char() cc," + // str
                                " rnd_long256() l2" + // str
                                " from long_sequence(" + totalRows + "))" // str
                );
                stmt.execute();

                try (PreparedStatement statement = connection.prepareStatement("x")) {
                    statement.setFetchSize(fetchSize);
                    int count = 0;
                    try (ResultSet rs = statement.executeQuery()) {
                        while (rs.next()) {
                            count++;
                            assertEquals(count, rs.getInt(1));
                        }
                    }
                    Assert.assertEquals(totalRows, count);
                }
            }
        });
    }

    @Test
    public void testDDL() throws Exception {
        assertMemoryLeak(() -> {
            try (
                    final PGWireServer ignored = createPGServer(1);
                    final Connection connection = getConnection(false, true);
                    final PreparedStatement statement = connection.prepareStatement("create table x (a int)")
            ) {
                statement.execute();
                try (
                        PreparedStatement select = connection.prepareStatement("x");
                        ResultSet rs = select.executeQuery()
                ) {
                    sink.clear();
                    assertResultSet("a[INTEGER]\n", sink, rs);
                }
            }
        });
    }

    @Test
    public void testDotNetHex() throws Exception {
        // DotNet code sends the following:
        //   SELECT version()
        // The issue that was here is STRING is required to be sent as "binary" type
        // it is the same as non-binary, but DotNet puts strict criteria on field format. It has to be 1.
        // Other drivers are less sensitive, perhaps they just do non-zero check
        // Here we assert that 1 is correctly derived from column type

        String script = ">0000003b00030000757365720061646d696e00636c69656e745f656e636f64696e67005554463800646174616261736500706f7374677265730000\n" +
                "<520000000800000003\n" +
                ">700000000a717565737400\n" +
                "<520000000800000000530000001154696d655a6f6e6500474d5400530000001d6170706c69636174696f6e5f6e616d6500517565737444420053000000187365727665725f76657273696f6e0031312e33005300000019696e74656765725f6461746574696d6573006f6e005300000019636c69656e745f656e636f64696e670055544638005a0000000549\n" +
                ">50000000180053454c4543542076657273696f6e2829000000420000000e0000000000000001000144000000065000450000000900000000005300000004\n" +
                "<310000000432000000045400000020000176657273696f6e0000000000000100000413ffffffffffff0001440000004400010000003a506f737467726553514c2031322e332c20636f6d70696c65642062792056697375616c20432b2b206275696c6420313931342c2036342d626974430000000d53454c4543542031005a0000000549\n" +
                ">51000000104449534341524420414c4c005800000004\n" +
                "<4300000008534554005a0000000549";
        assertHexScript(
                NetworkFacadeImpl.INSTANCE,
                script,
                new DefaultPGWireConfiguration()
        );
    }

    @Test
    public void testDropTable() throws Exception {
        String[][] sqlExpectedErrMsg = {
                {"drop table doesnt", "ERROR: table 'doesnt' does not exist"},
                {"drop table", "ERROR: expected [if exists] table-name"},
                {"drop doesnt", "ERROR: 'table' expected"},
                {"drop", "ERROR: 'table' expected"},
                {"drop table if doesnt", "ERROR: expected exists"},
                {"drop table exists doesnt", "ERROR: unexpected token [doesnt]"},
                {"drop table if exists", "ERROR: table name expected"},
                {"drop table if exists;", "ERROR: table name expected"},
        };
        TestUtils.assertMemoryLeak(() -> {
            try (final PGWireServer ignored = createPGServer(1);
                 final Connection connection = getConnection(false, false)) {
                for (int i = 0, n = sqlExpectedErrMsg.length; i < n; i++) {
                    String[] testData = sqlExpectedErrMsg[i];
                    try (PreparedStatement statement = connection.prepareStatement(testData[0])) {
                        statement.execute();
                        Assert.fail();
                    } catch (PSQLException e) {
                        assertContains(e.getMessage(), testData[1]);
                    }
                }
            }
        });
    }

    @Test
    public void testDropTableIfExistsDoesNotFailWhenTableDoesNotExist() throws Exception {
        TestUtils.assertMemoryLeak(() -> {
            try (final PGWireServer ignored = createPGServer(1)) {
                try (final Connection connection = getConnection(false, false)) {
                    try (PreparedStatement statement = connection.prepareStatement("drop table if exists doesnt")) {
                        statement.execute();
                    }
                }
            }
        });
    }

    @Test
    public void testEmptySql() throws Exception {
        assertMemoryLeak(() -> {
            try (
                    final PGWireServer ignored = createPGServer(1);
                    final Connection connection = getConnection(false, true)
            ) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("");
                }
            }
        });
    }

    @Test
    public void testExtendedModeTransaction() throws Exception {
        assertTransaction(false);
    }

    @Test
    public void testExtendedSyntaxErrorReporting() throws Exception {
        testSyntaxErrorReporting(false);
    }

    @Test
    public void testFetchDisconnectReleasesReaderCrossJoin() throws Exception {
        assertMemoryLeak(() -> {
            try (final PGWireServer ignored = createPGServer(1)) {
                try (final Connection connection = getConnection(false, true)) {
                    connection.setAutoCommit(false);

                    PreparedStatement tbl = connection.prepareStatement("create table xx as (" +
                            "select x," +
                            " timestamp_sequence(0, 1000) ts" +
                            " from long_sequence(100000)) timestamp (ts)");
                    tbl.execute();

                    PreparedStatement stmt = connection.prepareStatement("with crj as (select first(x) as p0 from xx) select x / p0 from xx cross join crj");

                    connection.setNetworkTimeout(Runnable::run, 5);
                    int testSize = 100000;
                    stmt.setFetchSize(testSize);
                    assertEquals(testSize, stmt.getFetchSize());

                    try {
                        stmt.executeQuery();
                        Assert.fail();
                    } catch (PSQLException ex) {
                        // expected
                        Assert.assertNotNull(ex);
                    }
                }
                Thread.sleep(100); // Give connection some time to close before closing the server.
            }
            // Assertion that no open readers left will be performed in assertMemoryLeak
        });
    }

    @Test
    public void testGORMConnect() throws Exception {
        // GORM is a Golang ORM tool
        assertHexScript(
                ">0000005e0003000064617461626173650071646200646174657374796c650049534f2c204d44590065787472615f666c6f61745f646967697473003200757365720061646d696e00636c69656e745f656e636f64696e6700555446380000\n" +
                        "<520000000800000003\n" +
                        ">700000000a717565737400\n" +
                        "<520000000800000000530000001154696d655a6f6e6500474d5400530000001d6170706c69636174696f6e5f6e616d6500517565737444420053000000187365727665725f76657273696f6e0031312e33005300000019696e74656765725f6461746574696d6573006f6e005300000019636c69656e745f656e636f64696e670055544638005a0000000549\n" +
                        ">51000000063b00\n"
        );
    }

    @Test
    public void testGeoHashInsertAllBin() throws Exception {
        insertAllGeoHashTypes(true);
    }

    @Test
    public void testGeoHashInsertAllStr() throws Exception {
        insertAllGeoHashTypes(false);
    }

    @Test
    public void testGeoHashSelectBin() throws Exception {
        testGeoHashSelect(false, true);
    }

    @Test
    public void testGeoHashSelectSimpleBin() throws Exception {
        testGeoHashSelect(true, true);
    }

    @Test
    public void testGeoHashSelectSimpleStr() throws Exception {
        testGeoHashSelect(true, false);
    }

    @Test
    public void testGeoHashSelectStr() throws Exception {
        testGeoHashSelect(false, false);
    }

    @Test
    public void testGetRow() throws Exception {
        assertMemoryLeak(() -> {
            try (
                    final PGWireServer ignored = createPGServer(1);
                    final Connection connection = getConnection(false, true)
            ) {
                connection.setAutoCommit(false);
                Statement stmt = connection.createStatement();
                stmt.setFetchSize(1);
                int totalRows = 10;
                CallableStatement tbl = connection.prepareCall(
                        "create table x as (select cast(x as int) a from long_sequence(" + totalRows + "))");
                tbl.execute();
                ResultSet rs = stmt.executeQuery("x");
                int count = 0;
                while (rs.next()) {
                    count++;
                    assertEquals(count, rs.getInt(1));
                    assertEquals(count, rs.getRow());
                }
                assertEquals(totalRows, count);
            }
        });
    }

    @Test
    /*
     * Tests the following golang code (PGX):
     * <pre>
     * package main
     *
     * import (
     * 	"context"
     * 	"fmt"
     * 	"github.com/jackc/pgx/v4/pgxpool"
     * 	"log"
     *
     * 	_ "github.com/jackc/pgx/v4"
     * )
     *
     * const (
     * 	host     = "localhost"
     * 	port     = 5432
     * 	user     = "admin"
     * 	password = "quest"
     * 	dbname   = "qdb"
     * )
     *
     * func main() {
     *
     * 	//urlExample := "postgres://postgres:pwd@localhost:5432/postgres"
     * 	urlExample := "postgres://admin:quest@localhost:5432/postgres"
     *
     * 	ctx0 := context.Background()
     * 	dbpool, err := pgxpool.Connect(ctx0, urlExample)
     *
     * 	if err != nil {
     * 		log.Fatalln("Unable to connect: %v\n", err)
     *        }
     * 	defer dbpool.Close()
     *
     * 	query := fmt.Sprintf("SELECT true, false")
     * 	rows, err := dbpool.Query(ctx0, query)
     * 	if (err != nil) {
     * 		log.Fatalln("Query failed")
     *    }
     *
     * 	for rows.Next() {
     * 		var side bool
     * 		var side2 bool
     * 		err = rows.Scan(&side, &side2)
     * 		if (err != nil) {
     * 			log.Fatalln("err scan row")
     *        } else {
     * 			log.Println(side, side2)
     *        }
     *    }
     *
     * }
     * </pre>
     */
    public void testGoLangBoolean() throws Exception {
        final String script = ">0000000804d2162f\n" +
                "<4e\n" +
                ">0000002400030000757365720078797a00646174616261736500706f7374677265730000\n" +
                "<520000000800000003\n" +
                ">70000000076f6800\n" +
                "<520000000800000000530000001154696d655a6f6e6500474d5400530000001d6170706c69636174696f6e5f6e616d6500517565737444420053000000187365727665725f76657273696f6e0031312e33005300000019696e74656765725f6461746574696d6573006f6e005300000019636c69656e745f656e636f64696e670055544638005a0000000549\n" +
                ">50000000246c72757073635f315f300053454c45435420747275652c2066616c73650000004400000010536c72757073635f315f30005300000004\n" +
                "<310000000474000000060000540000003500027472756500000000000001000000100001ffffffff000066616c736500000000000002000000100001ffffffff00005a0000000549\n" +
                ">420000001a006c72757073635f315f30000000000000020001000144000000065000450000000900000000005300000004\n" +
                "<3200000004540000003500027472756500000000000001000000100001ffffffff000166616c736500000000000002000000100001ffffffff00014400000010000200000001010000000100430000000d53454c4543542031005a0000000549\n" +
                ">5800000004\n";
        assertHexScript(
                NetworkFacadeImpl.INSTANCE,
                script,
                getHexPgWireConfig()
        );
    }

    @Test
    public void testHappyPathForIntParameterWithoutExplicitParameterTypeHex() throws Exception {
        String script = ">0000006e00030000757365720078797a0064617461626173650071646200636c69656e745f656e636f64696e67005554463800446174655374796c650049534f0054696d655a6f6e65004575726f70652f4c6f6e646f6e0065787472615f666c6f61745f64696769747300320000\n" +
                "<520000000800000003\n" +
                ">70000000076f6800\n" +
                "<520000000800000000530000001154696d655a6f6e6500474d5400530000001d6170706c69636174696f6e5f6e616d6500517565737444420053000000187365727665725f76657273696f6e0031312e33005300000019696e74656765725f6461746574696d6573006f6e005300000019636c69656e745f656e636f64696e670055544638005a0000000549\n" +
                ">500000002c0073656c65637420782c202024312066726f6d206c6f6e675f73657175656e63652832293b000000\n" +
                ">42000000110000000000010000000133000044000000065000450000000900000000004800000004\n" +
                "<31000000043200000004540000002f00027800000000000001000000140008ffffffff000024310000000000000200000413ffffffffffff000044000000100002000000013100000001334400000010000200000001320000000133430000000d53454c454354203200\n";

        assertHexScript(
                NetworkFacadeImpl.INSTANCE,
                script,
                getHexPgWireConfig()
        );
    }

    @Test
    public void testHexFragmentedSend() throws Exception {
        // this is a HEX encoded bytes of the same script as 'testSimple' sends using postgres jdbc driver
        String script = ">0000000804d2162f\n" +
                "<4e\n" +
                ">0000007000030000757365720061646d696e0064617461626173650071646200636c69656e745f656e636f64696e\n" +
                ">6700\n" +
                ">5554463800\n" +
                ">44\n" +
                ">61746553\n" +
                ">74\n" +
                ">796c\n" +
                ">65\n" +
                ">004953\n" +
                ">4f00\n" +
                ">54696d\n" +
                ">655a6f\n" +
                ">6e65\n" +
                ">00\n" +
                ">4575\n" +
                ">72\n" +
                ">6f70652f\n" +
                ">4c6f\n" +
                ">6e646f\n" +
                ">6e0065\n" +
                ">787472615f\n" +
                ">666c6f\n" +
                ">61\n" +
                ">745f\n" +
                ">64\n" +
                ">696769\n" +
                ">747300\n" +
                ">320000\n" +
                "<520000000800000003\n" +
                ">7000\n" +
                ">00000a\n" +
                ">7175\n" +
                ">65\n" +
                ">737400\n" +
                "<520000000800000000530000001154696d655a6f6e6500474d5400530000001d6170706c69636174696f6e5f6e616d6500517565737444420053000000187365727665725f76657273696f6e0031312e33005300000019696e74656765725f6461746574696d6573006f6e005300000019636c69656e745f656e636f64696e670055544638005a0000000549\n" +
                ">50\n" +
                ">000000220053\n" +
                ">45542065\n" +
                ">7874\n" +
                ">72615f\n" +
                ">666c6f\n" +
                ">61745f64\n" +
                ">6967\n" +
                ">69747320\n" +
                ">3d20\n" +
                ">33\n" +
                ">00\n" +
                ">0000420000000c0000000000000000450000000900000000015300000004\n" +
                "<310000000432000000044300000008534554005a0000000549\n" +
                ">5000000037\n" +
                ">00\n" +
                ">5345\n" +
                ">54\n" +
                ">20617070\n" +
                ">6c69\n" +
                ">63\n" +
                ">617469\n" +
                ">6f6e5f\n" +
                ">6e616d65203d2027506f737467726553514c204a444243\n" +
                ">2044726976657227\n" +
                ">000000\n" +
                ">420000000c0000000000000000450000000900000000015300000004\n" +
                "<310000000432000000044300000008534554005a0000000549\n" +
                ">5000\n" +
                ">00\n" +
                ">01940073\n" +
                ">656c656374\n" +
                ">20726e\n" +
                ">645f73\n" +
                ">74722834\n" +
                ">2c342c\n" +
                ">342920732c\n" +
                ">20\n" +
                ">726e\n" +
                ">64\n" +
                ">5f\n" +
                ">69\n" +
                ">6e7428\n" +
                ">302c20\n" +
                ">32\n" +
                ">3536\n" +
                ">2c2034\n" +
                ">2920\n" +
                ">692c\n" +
                ">20\n" +
                ">726e\n" +
                ">64\n" +
                ">5f646f75\n" +
                ">62\n" +
                ">6c65\n" +
                ">28342920\n" +
                ">642c20\n" +
                ">7469\n" +
                ">6d6573\n" +
                ">7461\n" +
                ">6d705f\n" +
                ">736571\n" +
                ">75656e636528302c3130\n" +
                ">30\n" +
                ">30302920742c\n" +
                ">20\n" +
                ">726e\n" +
                ">64\n" +
                ">5f66\n" +
                ">6c6f617428\n" +
                ">342920\n" +
                ">66\n" +
                ">2c\n" +
                ">20726e\n" +
                ">645f73\n" +
                ">686f72\n" +
                ">742829\n" +
                ">205f73\n" +
                ">686f7274\n" +
                ">2c2072\n" +
                ">6e645f\n" +
                ">6c6f\n" +
                ">6e6728\n" +
                ">302c20313030\n" +
                ">3030\n" +
                ">3030302c\n" +
                ">2035\n" +
                ">29\n" +
                ">206c2c\n" +
                ">20726e64\n" +
                ">5f7469\n" +
                ">6d\n" +
                ">657374\n" +
                ">616d70\n" +
                ">28746f\n" +
                ">5f\n" +
                ">74696d\n" +
                ">65737461\n" +
                ">6d70\n" +
                ">2827\n" +
                ">323031\n" +
                ">35272c27797979\n" +
                ">792729\n" +
                ">2c746f5f7469\n" +
                ">6d\n" +
                ">65\n" +
                ">7374616d\n" +
                ">70\n" +
                ">2827323031\n" +
                ">3627\n" +
                ">2c\n" +
                ">2779\n" +
                ">7979\n" +
                ">7927\n" +
                ">292c\n" +
                ">32\n" +
                ">292074\n" +
                ">73\n" +
                ">322c\n" +
                ">2072\n" +
                ">6e645f62\n" +
                ">79\n" +
                ">746528\n" +
                ">302c31\n" +
                ">3237\n" +
                ">2920\n" +
                ">6262\n" +
                ">2c20\n" +
                ">726e\n" +
                ">64\n" +
                ">5f\n" +
                ">626f\n" +
                ">6f6c\n" +
                ">6561\n" +
                ">6e282920\n" +
                ">622c\n" +
                ">20\n" +
                ">72\n" +
                ">6e64\n" +
                ">5f73\n" +
                ">796d\n" +
                ">626f\n" +
                ">6c28\n" +
                ">34\n" +
                ">2c\n" +
                ">342c34\n" +
                ">2c\n" +
                ">3229\n" +
                ">2c\n" +
                ">20\n" +
                ">726e\n" +
                ">64\n" +
                ">5f64\n" +
                ">61\n" +
                ">74\n" +
                ">6528\n" +
                ">74\n" +
                ">6f5f64\n" +
                ">6174\n" +
                ">65\n" +
                ">2827323031\n" +
                ">35272c202779\n" +
                ">7979\n" +
                ">79\n" +
                ">2729\n" +
                ">2c20\n" +
                ">74\n" +
                ">6f5f64\n" +
                ">6174\n" +
                ">65\n" +
                ">28\n" +
                ">27\n" +
                ">32\n" +
                ">30\n" +
                ">31\n" +
                ">36\n" +
                ">27\n" +
                ">2c2027\n" +
                ">797979\n" +
                ">792729\n" +
                ">2c\n" +
                ">20\n" +
                ">3229\n" +
                ">2c72\n" +
                ">6e\n" +
                ">64\n" +
                ">5f62\n" +
                ">69\n" +
                ">6e28\n" +
                ">3130\n" +
                ">2c32\n" +
                ">30\n" +
                ">2c\n" +
                ">322920\n" +
                ">66726f6d\n" +
                ">206c6f\n" +
                ">6e\n" +
                ">675f\n" +
                ">7365\n" +
                ">7175\n" +
                ">656e\n" +
                ">6365283530\n" +
                ">29\n" +
                ">0000004200\n" +
                ">00000c000000000000000044000000065000450000000900000000005300000004\n" +
                "<310000000432000000045400000128000d730000000000000100000413ffffffffffff00006900000000000002000000170004ffffffff00006400000000000003000002bd0008ffffffff000074000000000000040000045a0008ffffffff00006600000000000005000002bc0004ffffffff00005f73686f727400000000000006000000150002ffffffff00006c00000000000007000000140008ffffffff0000747332000000000000080000045a0008ffffffff0000626200000000000009000000150001ffffffff0000620000000000000a000000100001ffffffff0000726e645f73796d626f6c0000000000000b00000413ffffffffffff0000726e645f646174650000000000000c0000045a0008ffffffff0000726e645f62696e0000000000000d00000011ffffffffffff000144000000a6000dffffffff00000002353700000012302e363235343032313534323431323031380000001a313937302d30312d30312030303a30303a30302e30303030303000000005302e343632000000052d313539330000000733343235323332ffffffff000000033132310000000166000000045045484e00000017323031352d30332d31372030343a32353a35322e3736350000000e19c49594365349b4597e3b08a11e44000000c8000d00000004585953420000000331343200000012302e353739333436363332363836323231310000001a313937302d30312d30312030303a30303a30302e30313030303000000005302e39363900000005323030383800000007313531373439300000001a323031352d30312d31372032303a34313a31392e343830363835000000033130300000000174000000045045484e00000017323031352d30362d32302030313a31303a35382e35393900000011795f8b812b934d1a8e78b5b91153d0fb6444000000c2000d000000044f5a5a560000000332313900000013302e31363338313337343737333734383531340000001a313937302d30312d30312030303a30303a30302e30323030303000000005302e363539000000062d313233303300000007393438393530380000001a323031352d30382d31332031373a31303a31392e37353235323100000001360000000166ffffffff00000017323031352d30352d32302030313a34383a33372e3431380000000f2b4d5ff64690c3b3598ee5612f640e44000000b1000d000000044f4c595800000002333000000012302e373133333931303237313535353834330000001a313937302d30312d30312030303a30303a30302e30333030303000000005302e363535000000043636313000000007363530343432380000001a323031352d30382d30382030303a34323a32342e353435363339000000033132330000000166ffffffff00000017323031352d30312d30332031333a35333a30332e313635ffffffff44000000ac000d000000045449514200000002343200000012302e363830363837333133343632363431380000001a313937302d30312d30312030303a30303a30302e30343030303000000005302e363236000000052d3136303500000007383831343038360000001a323031352d30372d32382031353a30383a35332e34363234393500000002323800000001740000000443505357ffffffff0000000e3ba6dc3b7d2be392fe6938e1779a44000000af000d000000044c544f560000000331333700000012302e373633323631353030343332343530330000001a313937302d30312d30312030303a30303a30302e30353030303000000005302e3838320000000439303534ffffffff0000001a323031352d30342d32302030353a30393a30332e353830353734000000033130360000000166000000045045484e00000017323031352d30312d30392030363a35373a31372e353132ffffffff44000000a0000d000000045a494d4e00000003313235ffffffff0000001a313937302d30312d30312030303a30303a30302e303630303030ffffffff00000005313135323400000007383333353236310000001a323031352d31302d32362030323a31303a35302e363838333934000000033131310000000174000000045045484e00000017323031352d30382d32312031353a34363a33322e363234ffffffff44000000a1000d000000044f504a4f0000000331363800000013302e31303435393335323331323333313138330000001a313937302d30312d30312030303a30303a30302e30373030303000000005302e353335000000052d3539323000000007373038303730340000001a323031352d30372d31312030393a31353a33382e3334323731370000000331303300000001660000000456544a57ffffffffffffffff44000000b5000d00000004474c554f0000000331343500000012302e353339313632363632313739343637330000001a313937302d30312d30312030303a30303a30302e30383030303000000005302e37363700000005313432343200000007323439393932320000001a323031352d31312d30322030393a30313a33312e3331323830340000000238340000000166000000045045484e00000016323031352d31312d31342031373a33373a33362e3433ffffffff44000000c2000d000000045a5651450000000331303300000012302e363732393430353539303737333633380000001a313937302d30312d30312030303a30303a30302e303930303030ffffffff00000005313337323700000007373837353834360000001a323031352d31322d31322031333a31363a32362e3133343536320000000232320000000174000000045045484e00000016323031352d30312d32302030343a35303a33342e393800000012143380c9eba3677a1a79e435e43adc5c65ff44000000a7000d000000044c4947590000000331393900000012302e323833363334373133393438313436390000001a313937302d30312d30312030303a30303a30302e313030303030ffffffff00000005333034323600000007333231353536320000001a323031352d30382d32312031343a35353a30372e30353537323200000002313100000001660000000456544a57ffffffff0000000dff703ac78ab314cd470b0c391244000000a7000d000000044d514e5400000002343300000012302e353835393333323338383539393633380000001a313937302d30312d30312030303a30303a30302e31313030303000000005302e333335000000053237303139ffffffffffffffff0000000232370000000174000000045045484e00000017323031352d30372d31322031323a35393a34372e3636350000001326fb2e42faf56e8f80e354b807b13257ff9aef44000000c8000d00000004575743430000000332313300000012302e373636353032393931343337363935320000001a313937302d30312d30312030303a30303a30302e31323030303000000005302e35383000000005313336343000000007343132313932330000001a323031352d30382d30362030323a32373a33302e3436393736320000000237330000000166000000045045484e00000017323031352d30342d33302030383a31383a31302e3435330000001271a7d5af11963708dd98ef54882aa2ade7d444000000af000d00000004564647500000000331323000000012302e383430323936343730383132393534360000001a313937302d30312d30312030303a30303a30302e31333030303000000005302e373733000000043732323300000007373234313432330000001a323031352d31322d31382030373a33323a31382e34353630323500000002343300000001660000000456544a57ffffffff00000011244e44a80dfe27ec53135db215e7b8356744000000b7000d00000004524d44470000000331333400000013302e31313034373331353231343739333639360000001a313937302d30312d30312030303a30303a30302e31343030303000000005302e30343300000005323132323700000007373135353730380000001a323031352d30372d30332030343a31323a34352e3737343238310000000234320000000174000000044350535700000017323031352d30322d32342031323a31303a34332e313939ffffffff44000000a4000d0000000457464f5100000003323535ffffffff0000001a313937302d30312d30312030303a30303a30302e31353030303000000005302e31313600000005333135363900000007363638383237370000001a323031352d30352d31392030333a33303a34352e373739393939000000033132360000000174000000045045484e00000016323031352d31322d30392030393a35373a31372e3738ffffffff4400000098000d000000044d58444b00000002353600000012302e393939373739373233343033313638380000001a313937302d30312d30312030303a30303a30302e31363030303000000005302e353233000000062d33323337320000000736383834313332ffffffff0000000235380000000166ffffffff00000017323031352d30312d32302030363a31383a31382e353833ffffffff44000000ba000d00000004584d4b4a0000000331333900000012302e383430353831353439333536373431370000001a313937302d30312d30312030303a30303a30302e31373030303000000005302e333036000000053235383536ffffffff0000001a323031352d30352d31382030333a35303a32322e373331343337000000013200000001740000000456544a5700000016323031352d30362d32352031303a34353a30312e31340000000d007cfb0119caf2bf845a6f383544000000af000d0000000456494844ffffffffffffffff0000001a313937302d30312d30312030303a30303a30302e31383030303000000005302e35353000000005323232383000000007393130393834320000001a323031352d30312d32352031333a35313a33382e3237303538330000000239340000000166000000044350535700000017323031352d31302d32372030323a35323a31392e3933350000000e2d16f389a38364ded6fdc45bc4e944000000bd000d0000000457504e58ffffffff00000012302e393436393730303831333932363930370000001a313937302d30312d30312030303a30303a30302e31393030303000000005302e343135000000062d3137393333000000063637343236310000001a323031352d30332d30342031353a34333a31352e3231333638360000000234330000000174000000044859525800000017323031352d31322d31382032313a32383a32352e3332350000000ab34c0e8ff10cc560b7d144000000bd000d0000000459504f5600000002333600000012302e363734313234383434383732383832340000001a313937302d30312d30312030303a30303a30302e32303030303000000005302e303331000000052d3538383800000007313337353432330000001a323031352d31322d31302032303a35303a33352e38363636313400000001330000000174ffffffff00000017323031352d30372d32332032303a31373a30342e3233360000000dd4abbe30fa8dac3d98a0ad9a5d44000000c6000d000000044e55484effffffff00000012302e363934303931373932353134383333320000001a313937302d30312d30312030303a30303a30302e32313030303000000005302e333339000000062d323532323600000007333532343734380000001a323031352d30352d30372030343a30373a31382e31353239363800000002333900000001740000000456544a5700000017323031352d30342d30342031353a32333a33342e31333000000012b8bef8a146872892a39be3cbc2648ab035d8440000009c000d00000004424f53450000000332343000000013302e30363030313832373732313535363031390000001a313937302d30312d30312030303a30303a30302e32323030303000000005302e33373900000005323339303400000007393036393333390000001a323031352d30332d32312030333a34323a34322e3634333138360000000238340000000174ffffffffffffffffffffffff44000000c5000d00000004494e4b470000000331323400000012302e383631353834313632373730323735330000001a313937302d30312d30312030303a30303a30302e32333030303000000005302e343034000000062d333033383300000007373233333534320000001a323031352d30372d32312031363a34323a34372e3031323134380000000239390000000166ffffffff00000017323031352d30382d32372031373a32353a33352e3330380000001287fc9283fc88f3322770c801b0dcc93a5b7e44000000b1000d000000044655584300000002353200000012302e373433303130313939343531313531370000001a313937302d30312d30312030303a30303a30302e323430303030ffffffff000000062d313437323900000007313034323036340000001a323031352d30382d32312030323a31303a35382e3934393637340000000232380000000174000000044350535700000017323031352d30382d32392032303a31353a35312e383335ffffffff44000000bd000d00000004554e595100000002373100000011302e3434323039353431303238313933380000001a313937302d30312d30312030303a30303a30302e32353030303000000005302e353339000000062d3232363131ffffffff0000001a323031352d31322d32332031383a34313a34322e3331393835390000000239380000000174000000045045484e00000017323031352d30312d32362030303a35353a35302e3230320000000f28ed9799d877333fb267da984747bf44000000b1000d000000044b424d51ffffffff00000013302e32383031393231383832353035313339350000001a313937302d30312d30312030303a30303a30302e323630303030ffffffff000000053132323430ffffffff0000001a323031352d30382d31362030313a30323a35352e3736363632320000000232310000000166ffffffff00000017323031352d30352d31392030303a34373a31382e3639380000000d6ade4604d381e7a21622353b1c4400000091000d000000044a534f4c00000003323433ffffffff0000001a313937302d30312d30312030303a30303a30302e32373030303000000005302e303638000000062d3137343638ffffffffffffffff0000000232300000000174ffffffff00000017323031352d30362d31392031303a33383a35342e343833000000113de02d0486e7ca29980769ca5bd6cf0969440000007f000d00000004484e535300000003313530ffffffff0000001a313937302d30312d30312030303a30303a30302e32383030303000000005302e3134380000000531343834310000000735393932343433ffffffff0000000232350000000166000000045045484effffffff0000000c14d6fcee032281b806c406af44000000c3000d00000004505a50420000000331303100000014302e3036313634363731373738363135383034350000001a313937302d30312d30312030303a30303a30302e323930303030ffffffff00000005313232333700000007393837383137390000001a323031352d30392d30332032323a31333a31382e38353234363500000002373900000001660000000456544a5700000017323031352d31322d31372031353a31323a35342e3935380000001012613a9aad982e7552ad62878845b99d44000000c3000d000000044f594e4e00000002323500000012302e333339333530393531343030303234370000001a313937302d30312d30312030303a30303a30302e33303030303000000005302e36323800000005323234313200000007343733363337380000001a323031352d31302d31302031323a31393a34322e353238323234000000033130360000000174000000044350535700000017323031352d30372d30312030303a32333a34392e3738390000000d54133fffb67ecd0427669489db4400000083000dffffffff0000000331313700000012302e353633383430343737353636333136310000001a313937302d30312d30312030303a30303a30302e333130303030ffffffff000000052d353630340000000736333533303138ffffffff0000000238340000000166ffffffffffffffff0000000b2bad2507db6244336e008e4400000099000d00000004485652490000000332333300000013302e32323430373636353739303730353737370000001a313937302d30312d30312030303a30303a30302e33323030303000000005302e3432350000000531303436390000000731373135323133ffffffff0000000238360000000166ffffffff00000017323031352d30322d30322030353a34383a31372e333733ffffffff44000000b6000d000000044f59544f00000002393600000012302e373430373538313631363931363336340000001a313937302d30312d30312030303a30303a30302e33333030303000000005302e353238000000062d313232333900000007333439393632300000001a323031352d30322d30372032323a33353a30332e3231323236380000000231370000000166000000045045484e00000017323031352d30332d32392031323a35353a31312e363832ffffffff44000000a5000d000000044c46435900000002363300000012302e373231373331353732393739303732320000001a313937302d30312d30312030303a30303a30302e333430303030ffffffff0000000532333334340000000739353233393832ffffffff000000033132330000000166000000044350535700000017323031352d30352d31382030343a33353a32372e3232380000000e05e5c04eccd6e37b34cd1535bba444000000c1000d0000000447484c580000000331343800000012302e333035373933373730343936343237320000001a313937302d30312d30312030303a30303a30302e33353030303000000005302e363336000000062d333134353700000007323332323333370000001a323031352d31302d32322031323a30363a30352e3534343730310000000239310000000174000000044859525800000017323031352d30352d32312030393a33333a31382e3135380000000a571d91723004b702cb0344000000a3000d000000045954535a00000003313233ffffffff0000001a313937302d30312d30312030303a30303a30302e33363030303000000005302e35313900000005323235333400000007343434363233360000001a323031352d30372d32372030373a32333a33372e3233333731310000000235330000000166000000044350535700000016323031352d30312d31332030343a33373a31302e3336ffffffff44000000a1000d0000000453574c5500000003323531ffffffff0000001a313937302d30312d30312030303a30303a30302e33373030303000000005302e313739000000043737333400000007343038323437350000001a323031352d31302d32312031383a32343a33342e3430303334350000000236390000000166000000045045484e00000015323031352d30342d30312031343a33333a34322e35ffffffff44000000b1000d0000000454514a4c00000003323435ffffffff0000001a313937302d30312d30312030303a30303a30302e33383030303000000005302e3836350000000439353136000000063932393334300000001a323031352d30352d32382030343a31383a31382e36343035363700000002363900000001660000000456544a5700000017323031352d30362d31322032303a31323a32382e3838310000000f6c3e51d7ebb10771321faf404e8c47440000009e000d000000045245494a000000023934ffffffff0000001a313937302d30312d30312030303a30303a30302e33393030303000000005302e313330000000062d3239393234ffffffff0000001a323031352d30332d32302032323a31343a34362e323034373138000000033131330000000174000000044859525800000017323031352d31322d31392031333a35383a34312e383139ffffffff44000000c2000d000000044844485100000002393400000012302e373233343138313737333430373533360000001a313937302d30312d30312030303a30303a30302e34303030303000000005302e373330000000053139393730000000063635343133310000001a323031352d30312d31302032323a35363a30382e3438303435300000000238340000000174ffffffff00000017323031352d30332d30352031373a31343a34382e323735000000124f566b65a45338e9cdc1a7ee8675ada52d4944000000b8000d00000004554d455500000002343000000014302e3030383434343033333233303538303733390000001a313937302d30312d30312030303a30303a30302e34313030303000000005302e383035000000062d313136323300000007343539393836320000001a323031352d31312d32302030343a30323a34342e3333353934370000000237360000000166000000045045484e00000017323031352d30352d31372031373a33333a32302e393232ffffffff44000000ad000d00000004594a494800000003313834ffffffff0000001a313937302d30312d30312030303a30303a30302e34323030303000000005302e33383300000005313736313400000007333130313637310000001a323031352d30312d32382031323a30353a34362e363833303031000000033130350000000174ffffffff00000017323031352d31322d30372031393a32343a33362e3833380000000cec69cd73bb9bc595db6191ce44000000a3000d000000044359584700000002323700000012302e323931373739363035333034353734370000001a313937302d30312d30312030303a30303a30302e34333030303000000005302e393533000000043339343400000006323439313635ffffffff0000000236370000000174ffffffff00000017323031352d30332d30322030383a31393a34342e3536360000000e0148153e0c7f3f8fe4b5ab34212944000000b4000d000000044d5254470000000331343300000013302e30323633323533313336313439393131330000001a313937302d30312d30312030303a30303a30302e34343030303000000005302e393433000000062d323733323000000007313636373834320000001a323031352d30312d32342031393a35363a31352e3937333130390000000231310000000166ffffffff00000017323031352d30312d32342030373a31353a30322e373732ffffffff44000000c3000d00000004444f4e500000000332343600000011302e3635343232363234383734303434370000001a313937302d30312d30312030303a30303a30302e34353030303000000005302e35353600000005323734373700000007343136303031380000001a323031352d31322d31342030333a34303a30352e3931313833390000000232300000000174000000045045484e00000017323031352d31302d32392031343a33353a31302e3136370000000e079201f56aa131cdcbc2a2b48e9944000000c4000d00000004495158530000000332333200000013302e32333037353730303231383033383835330000001a313937302d30312d30312030303a30303a30302e34363030303000000005302e303439000000062d313831313300000007343030353232380000001a323031352d30362d31312031333a30303a30372e32343831383800000001380000000174000000044350535700000017323031352d30382d31362031313a30393a32342e3331310000000dfa1f9224b1b8676508b7f8410044000000b0000dffffffff00000003313738ffffffff0000001a313937302d30312d30312030303a30303a30302e34373030303000000005302e393033000000062d313436323600000007323933343537300000001a323031352d30342d30342030383a35313a35342e3036383135340000000238380000000174ffffffff00000016323031352d30372d30312030343a33323a32332e383300000014843625632b6361431c477db646babb98ca08bea444000000b0000d000000044855575a00000002393400000011302e3131303430313337343937393631330000001a313937302d30312d30312030303a30303a30302e34383030303000000005302e343230000000052d3337333600000007353638373531340000001a323031352d30312d30322031373a31383a30352e3632373633330000000237340000000166ffffffff00000017323031352d30332d32392030363a33393a31312e363432ffffffff44000000ab000d000000045352454400000002363600000013302e31313237343636373134303931353932380000001a313937302d30312d30312030303a30303a30302e34393030303000000005302e303630000000062d313035343300000007333636393337370000001a323031352d31302d32322030323a35333a30322e3338313335310000000237370000000174000000045045484effffffff0000000b7c3fd6883a93ef24a5e2bc430000000e53454c454354203530005a0000000549\n";

        assertHexScript(script);
    }

    @Test
    public void testIndexedSymbolBindVariableNotEqualsSingleValueMultipleExecutions() throws Exception {
        assertMemoryLeak(() -> {
            try (
                    final PGWireServer ignored = createPGServer(1);
                    final Connection connection = getConnection(false, true)
            ) {
                connection.prepareStatement("create table x as " +
                        "(" +
                        "select" +
                        " rnd_double(0)*100 a," +
                        " rnd_symbol(5,4,4,3) b," +
                        " timestamp_sequence(0, 100000000000) k" +
                        " from" +
                        " long_sequence(4)" +
                        "), index(b) timestamp(k) partition by DAY").execute();

                sink.clear();
                try (PreparedStatement ps = connection.prepareStatement("select * from x where b != ?")) {
                    ps.setString(1, "VTJW");
                    try (ResultSet rs = ps.executeQuery()) {
                        assertResultSet(
                                "a[DOUBLE],b[VARCHAR],k[TIMESTAMP]\n" +
                                        "11.427984775756228,null,1970-01-01 00:00:00.0\n" +
                                        "23.90529010846525,RXGZ,1970-01-03 07:33:20.0\n" +
                                        "70.94360487171201,PEHN,1970-01-04 11:20:00.0\n",
                                sink,
                                rs
                        );
                    }
                }

                // Verify that the underlying factory correctly re-calculates
                // the excluded set when the bind variable value changes.
                sink.clear();
                try (PreparedStatement ps = connection.prepareStatement("select * from x where b != ?")) {
                    ps.setString(1, "RXGZ");
                    try (ResultSet rs = ps.executeQuery()) {
                        assertResultSet(
                                "a[DOUBLE],b[VARCHAR],k[TIMESTAMP]\n" +
                                        "11.427984775756228,null,1970-01-01 00:00:00.0\n" +
                                        "42.17768841969397,VTJW,1970-01-02 03:46:40.0\n" +
                                        "70.94360487171201,PEHN,1970-01-04 11:20:00.0\n",
                                sink,
                                rs
                        );
                    }
                }

                // The factory should correctly recognize NULL as the excluded value.
                sink.clear();
                try (PreparedStatement ps = connection.prepareStatement("select * from x where b != ?")) {
                    ps.setString(1, null);
                    try (ResultSet rs = ps.executeQuery()) {
                        assertResultSet(
                                "a[DOUBLE],b[VARCHAR],k[TIMESTAMP]\n" +
                                        "42.17768841969397,VTJW,1970-01-02 03:46:40.0\n" +
                                        "23.90529010846525,RXGZ,1970-01-03 07:33:20.0\n" +
                                        "70.94360487171201,PEHN,1970-01-04 11:20:00.0\n",
                                sink,
                                rs
                        );
                    }
                }
            }
        });
    }

    @Test
    public void testIndexedSymbolBindVariableNotMultipleValuesMultipleExecutions() throws Exception {
        assertMemoryLeak(() -> {
            try (
                    final PGWireServer ignored = createPGServer(1);
                    final Connection connection = getConnection(false, true)
            ) {
                connection.prepareStatement("create table x as " +
                        "(" +
                        "select" +
                        " rnd_double(0)*100 a," +
                        " rnd_symbol(5,4,4,0) b," +
                        " timestamp_sequence(0, 100000000000) k" +
                        " from" +
                        " long_sequence(1)" +
                        "), index(b) timestamp(k) partition by DAY").execute();

                // First we try to filter out not yet existing keys.
                sink.clear();
                try (PreparedStatement ps = connection.prepareStatement("select * from x where b != ? and b != ?")) {
                    ps.setString(1, "EHBH");
                    ps.setString(2, "BBTG");
                    try (ResultSet rs = ps.executeQuery()) {
                        assertResultSet(
                                "a[DOUBLE],b[VARCHAR],k[TIMESTAMP]\n" +
                                        "11.427984775756228,HYRX,1970-01-01 00:00:00.0\n",
                                sink,
                                rs
                        );
                    }
                }

                // Insert new rows including the keys of interest.
                connection.prepareStatement("insert into x " +
                        "select" +
                        " rnd_double(0)*100 a," +
                        " rnd_symbol(5,4,4,0) b," +
                        " timestamp_sequence(100000000000, 100000000000) k" +
                        " from" +
                        " long_sequence(3)").execute();

                // The query should filter the keys out.
                sink.clear();
                try (PreparedStatement ps = connection.prepareStatement("select * from x where b != ? and b != ?")) {
                    ps.setString(1, "EHBH");
                    ps.setString(2, "BBTG");
                    try (ResultSet rs = ps.executeQuery()) {
                        assertResultSet(
                                "a[DOUBLE],b[VARCHAR],k[TIMESTAMP]\n" +
                                        "11.427984775756228,HYRX,1970-01-01 00:00:00.0\n" +
                                        "40.22810626779558,EYYQ,1970-01-04 11:20:00.0\n",
                                sink,
                                rs
                        );
                    }
                }
            }
        });
    }

    // Test odd queries that should not be transformed into cursor-based fetches.
    @Test
    public void testInsert() throws Exception {
        assertMemoryLeak(() -> {
            try (
                    final PGWireServer ignored = createPGServer(1);
                    final Connection connection = getConnection(false, true)
            ) {
                int totalRows = 1;
                PreparedStatement tbl = connection.prepareStatement("create table x (a int)");
                tbl.execute();

                PreparedStatement insert = connection.prepareStatement("insert into x(a) values(?)");
                for (int i = 0; i < totalRows; i++) {
                    insert.setInt(1, i);
                    insert.setFetchSize(100); // Should be meaningless.
                    insert.execute();
                }
            }
        });
    }

    @Test
    public void testInsertAllTypesBinary() throws Exception {
        testInsertAllTypes(true);
    }

    @Test
    public void testInsertAllTypesText() throws Exception {
        testInsertAllTypes(false);
    }

    @Test
    public void testInsertBinaryBindVariable1() throws Exception {
        testInsertBinaryBindVariable(true);
    }

    @Test
    public void testInsertBinaryBindVariable2() throws Exception {
        testInsertBinaryBindVariable(false);
    }

    @Test
    @Ignore // TODO: support big binary parameter buffers (epic)
    public void testInsertBinaryOver1Mb() throws Exception {
        final int maxLength = 1024 * 1024;
        testBinaryInsert(maxLength, false);
    }

    @Test
    public void testInsertBinaryOver200KbBinaryProtocol() throws Exception {
        final int maxLength = 200 * 1024;
        testBinaryInsert(maxLength, true);
    }

    @Test
    public void testInsertBinaryOver200KbNonBinaryProtocol() throws Exception {
        final int maxLength = 200 * 1024;
        testBinaryInsert(maxLength, false);
    }

    @Test
    public void testInsertBooleans() throws Exception {
        assertMemoryLeak(() -> {
            try (
                    PGWireServer ignored = createPGServer(4);
                    Connection conn = getConnection(true, true)
            ) {
                conn.prepareStatement(
                        "create table booleans (value boolean, ts timestamp) timestamp(ts)"
                ).execute();

                Rnd rand = new Rnd();
                String[] values = {"TrUE", null, "", "false", "true", "banana", "22"};

                try (PreparedStatement insert = conn.prepareStatement("insert into booleans values (cast(? as boolean), ?)")) {
                    long micros = TimestampFormatUtils.parseTimestamp("2022-04-19T18:50:00.998666Z");
                    for (int i = 0; i < 30; i++) {
                        insert.setString(1, values[rand.nextInt(values.length)]);
                        insert.setTimestamp(2, new Timestamp(micros / 1000L));
                        insert.execute();
                        Assert.assertEquals(1, insert.getUpdateCount());
                        micros += 1_000_000L;
                    }
                }

                try (ResultSet resultSet = conn.prepareStatement("booleans").executeQuery()) {
                    sink.clear();
                    assertResultSet(
                            "value[BIT],ts[TIMESTAMP]\n" +
                                    "true,2022-04-19 18:50:00.998\n" +
                                    "false,2022-04-19 18:50:01.998\n" +
                                    "false,2022-04-19 18:50:02.998\n" +
                                    "true,2022-04-19 18:50:03.998\n" +
                                    "false,2022-04-19 18:50:04.998\n" +
                                    "false,2022-04-19 18:50:05.998\n" +
                                    "false,2022-04-19 18:50:06.998\n" +
                                    "false,2022-04-19 18:50:07.998\n" +
                                    "false,2022-04-19 18:50:08.998\n" +
                                    "true,2022-04-19 18:50:09.998\n" +
                                    "false,2022-04-19 18:50:10.998\n" +
                                    "false,2022-04-19 18:50:11.998\n" +
                                    "false,2022-04-19 18:50:12.998\n" +
                                    "false,2022-04-19 18:50:13.998\n" +
                                    "false,2022-04-19 18:50:14.998\n" +
                                    "false,2022-04-19 18:50:15.998\n" +
                                    "false,2022-04-19 18:50:16.998\n" +
                                    "true,2022-04-19 18:50:17.998\n" +
                                    "false,2022-04-19 18:50:18.998\n" +
                                    "true,2022-04-19 18:50:19.998\n" +
                                    "false,2022-04-19 18:50:20.998\n" +
                                    "false,2022-04-19 18:50:21.998\n" +
                                    "false,2022-04-19 18:50:22.998\n" +
                                    "true,2022-04-19 18:50:23.998\n" +
                                    "true,2022-04-19 18:50:24.998\n" +
                                    "true,2022-04-19 18:50:25.998\n" +
                                    "true,2022-04-19 18:50:26.998\n" +
                                    "false,2022-04-19 18:50:27.998\n" +
                                    "false,2022-04-19 18:50:28.998\n" +
                                    "false,2022-04-19 18:50:29.998\n",
                            sink,
                            resultSet);
                }
            }
        });
    }

    @Test
    public void testInsertDateAndTimestampFromRustHex() throws Exception {
        String script = ">0000004300030000636c69656e745f656e636f64696e6700555446380074696d657a6f6e650055544300757365720061646d696e006461746162617365007164620000\n" +
                "<520000000800000003\n" +
                ">700000000a717565737400\n" +
                "<520000000800000000530000001154696d655a6f6e6500474d5400530000001d6170706c69636174696f6e5f6e616d6500517565737444420053000000187365727665725f76657273696f6e0031312e33005300000019696e74656765725f6461746574696d6573006f6e005300000019636c69656e745f656e636f64696e670055544638005a0000000549\n" +
                ">5100000063435245415445205441424c45204946204e4f54204558495354532072757374202874732054494d455354414d502c20647420444154452c206e616d6520535452494e472c2076616c756520494e54292074696d657374616d70287473293b00\n" +
                "<43000000074f4b005a0000000549\n" +
                ">500000002e733000494e5345525420494e544f20727573742056414c5545532824312c24322c24332c2434290000004400000008537330005300000004\n" +
                "<3100000004740000001600040000045a0000045a00000413000000176e000000045a0000000549\n" +
                ">4200000042007330000001000100040000000800025c7a454d92ad0000000800025c7a454d92ad0000000c72757374206578616d706c65000000040000007b00010001450000000900000000005300000004\n" +
                "<3200000004430000000f494e5345525420302031005a0000000549\n" +
                ">4300000008537330005300000004\n" +
                "<33000000045a0000000549\n" +
                ">5800000004";
        assertHexScript(
                NetworkFacadeImpl.INSTANCE,
                script,
                new DefaultPGWireConfiguration()
        );
    }

    @Test
    public void testInsertExtendedBinary() throws Exception {
        testInsert0(false, true);
    }

    @Test
    public void testInsertExtendedBinaryAndCommit() throws Exception {
        assertMemoryLeak(() -> {
            String expectedAll = "count[BIGINT]\n" +
                    "10000\n";

            try (
                    final PGWireServer ignored = createPGServer(3);
                    final Connection connection = getConnection(false, true)
            ) {

                connection.setAutoCommit(false);
                //
                // test methods of inserting QuestDB's DATA and TIMESTAMP values
                //
                final PreparedStatement statement = connection.prepareStatement("create table x (a int, d date, t timestamp, d1 date, t1 timestamp, t3 timestamp, b1 short, t4 timestamp) timestamp(t)");
                statement.execute();

                // exercise parameters on select statement
                PreparedStatement select = connection.prepareStatement("x where a = ?");
                execSelectWithParam(select, 9);


                final PreparedStatement insert = connection.prepareStatement("insert into x values (?, ?, ?, ?, ?, ?, ?, ?)");
                long micros = TimestampFormatUtils.parseTimestamp("2011-04-11T14:40:54.998821Z");
                for (int i = 0; i < 10_000; i++) {
                    insert.setInt(1, i);
                    // DATE as jdbc's DATE
                    // jdbc's DATE takes millis from epoch and i think it removes time element from it, leaving
                    // just date
                    insert.setDate(2, new Date(micros / 1000));

                    // TIMESTAMP as jdbc's TIMESTAMP, this should keep the micros
                    insert.setTimestamp(3, new Timestamp(micros));

                    // DATE as jdbc's TIMESTAMP, this should keep millis and we need to supply millis
                    insert.setTimestamp(4, new Timestamp(micros / 1000L));

                    // TIMESTAMP as jdbc's DATE, DATE takes millis and throws them away
                    insert.setDate(5, new Date(micros));

                    // TIMESTAMP as PG specific TIMESTAMP type
                    insert.setTimestamp(6, new PGTimestamp(micros));

                    insert.setByte(7, (byte) 'A');

                    // TIMESTAMP as long
                    insert.setLong(8, micros);

                    insert.execute();
                    Assert.assertEquals(1, insert.getUpdateCount());
                    micros += 1;

                    if (i % 128 == 0) {
                        connection.commit();
                    }
                }
                connection.commit();

                try (ResultSet resultSet = connection.prepareStatement("select count() from x").executeQuery()) {
                    sink.clear();
                    assertResultSet(expectedAll, sink, resultSet);
                }

                TestUtils.assertEquals(expectedAll, sink);

                // exercise parameters on select statement
                execSelectWithParam(select, 9);
                TestUtils.assertEquals("9\n", sink);

                execSelectWithParam(select, 11);
                TestUtils.assertEquals("11\n", sink);

            }
        });
    }

    @Test
    public void testInsertExtendedText() throws Exception {
        testInsert0(false, false);
    }

    /*
    nodejs code:
    ------------------
            const { Client } = require("pg")

            const start = async () => {
              try {
                const client = new Client({
                  database: "qdb",
                  host: "127.0.0.1",
                  password: "quest",
                  port: 8812,
                  user: "admin",
                })
                await client.connect()

                const res = await client.query("INSERT INTO test VALUES($1, $2);", [
                  "abc",
                  "123"
                ])

                console.log(res)

                await client.end()
              } catch (e) {
                console.log(e)
              }
            }

            start()
    ------------------
     */
    @Test
    public void testInsertFomNodeJsWith2Parameters_TableDoesNotExist() throws Exception {
        final String script = ">0000006900030000757365720078797a006461746162617365006e6162755f61707000636c69656e745f656e636f64696e67005554463800446174655374796c650049534f0054696d655a6f6e6500474d540065787472615f666c6f61745f64696769747300320000\n" +
                "<520000000800000003\n" +
                ">70000000076f6800\n" +
                "<520000000800000000530000001154696d655a6f6e6500474d5400530000001d6170706c69636174696f6e5f6e616d6500517565737444420053000000187365727665725f76657273696f6e0031312e33005300000019696e74656765725f6461746574696d6573006f6e005300000019636c69656e745f656e636f64696e670055544638005a0000000549\n";
        assertHexScript(NetworkFacadeImpl.INSTANCE, script, getHexPgWireConfig());
    }

    /*
nodejs code:
------------------
        const { Client } = require("pg")

        const start = async () => {
          try {
            const client = new Client({
              database: "qdb",
              host: "127.0.0.1",
              password: "quest",
              port: 8812,
              user: "admin",
            })
            await client.connect()

            const res = await client.query("CREATE TABLE test (id string, number int);")

            const res = await client.query("INSERT INTO test VALUES($1, $2);", [
              "abc",
              "123"
            ])

            console.log(res)

            await client.end()
          } catch (e) {
            console.log(e)
          }
        }

        start()
------------------
 */
    @Test
    public void testInsertFomNodeJsWith2Parameters_WithTableCreation() throws Exception {
        final String script = ">0000006900030000757365720078797a006461746162617365006e6162755f61707000636c69656e745f656e636f64696e67005554463800446174655374796c650049534f0054696d655a6f6e6500474d540065787472615f666c6f61745f64696769747300320000\n" +
                "<520000000800000003\n" +
                ">70000000076f6800\n" +
                "<520000000800000000530000001154696d655a6f6e6500474d5400530000001d6170706c69636174696f6e5f6e616d6500517565737444420053000000187365727665725f76657273696f6e0031312e33005300000019696e74656765725f6461746574696d6573006f6e005300000019636c69656e745f656e636f64696e670055544638005a0000000549\n" +
                ">510000002f435245415445205441424c4520746573742028696420737472696e672c206e756d62657220696e74293b00\n" +
                "<43000000074f4b005a0000000549\n" +
                ">500000002800494e5345525420494e544f20746573742056414c5545532824312c202432293b000000420000001a00000000000200000003616263000000033132330000\n" +
                ">44000000065000450000000900000000004800000004\n" +
                "<310000000432000000046e00000004430000000f494e534552542030203100\n" +
                ">4800000004\n" +
                ">5300000004\n" +
                "<5a0000000549\n" +
                ">5800000004\n";
        assertHexScript(
                NetworkFacadeImpl.INSTANCE,
                script,
                getHexPgWireConfig()
        );
    }

    @Test
    @Ignore
    public void testInsertSimpleText() throws Exception {
        testInsert0(true, false);
    }

    @Test
    public void testInsertTableDoesNotExistPrepared() throws Exception {
        testInsertTableDoesNotExist(false, "could not open read-write");
    }

    @Test
    public void testInsertTableDoesNotExistSimple() throws Exception {
        testInsertTableDoesNotExist(true, "table 'x' does not exist");
    }

    @Test
    public void testInsertTimestampAsString() throws Exception {
        assertMemoryLeak(() -> {
            String expectedAll = "count[BIGINT]\n" +
                    "10\n";

            try (
                    final PGWireServer ignored = createPGServer(3);
                    final Connection connection = getConnection(false, true)
            ) {

                connection.setAutoCommit(false);
                //
                // test methods of inserting QuestDB's DATA and TIMESTAMP values
                //
                final PreparedStatement statement = connection.prepareStatement("create table x (a int, t timestamp, t1 timestamp) timestamp(t)");
                statement.execute();

                // exercise parameters on select statement
                PreparedStatement select = connection.prepareStatement("x where a = ?");
                execSelectWithParam(select, 9);


                final PreparedStatement insert = connection.prepareStatement("insert into x values (?, ?, ?)");
                for (int i = 0; i < 10; i++) {
                    insert.setInt(1, i);
                    // TIMESTAMP as ISO string to designated and non-designated timestamp
                    insert.setString(2, "2011-04-1" + i + "T14:40:54.998821Z");
                    insert.setString(3, "2011-04-11T1" + i + ":40:54.998821Z");

                    insert.execute();
                    Assert.assertEquals(1, insert.getUpdateCount());
                }
                connection.commit();

                try (ResultSet resultSet = connection.prepareStatement("select count() from x").executeQuery()) {
                    sink.clear();
                    assertResultSet(expectedAll, sink, resultSet);
                }

                TestUtils.assertEquals(expectedAll, sink);

                // exercise parameters on select statement
                execSelectWithParam(select, 9);
                TestUtils.assertEquals("9\n", sink);
            }
        });
    }

    @Test
    public void testInsertTimestampWithTypeSuffix() throws Exception {
        assertMemoryLeak(() -> {
            try (
                    final PGWireServer ignored = createPGServer(1);
                    final Connection connection = getConnection(true, false)
            ) {
                final PreparedStatement statement = connection.prepareStatement("create table x (ts timestamp) timestamp(ts)");
                statement.execute();

                // the below timestamp formats are used by Python drivers
                final PreparedStatement insert = connection.prepareStatement("insert into x values " +
                        "('2020-06-01T00:00:02'::timestamp)," +
                        "('2020-06-01T00:00:02.000009'::timestamp)");
                insert.execute();

                final String expected = "ts[TIMESTAMP]\n" +
                        "2020-06-01 00:00:02.0\n" +
                        "2020-06-01 00:00:02.000009\n";
                try (ResultSet resultSet = connection.prepareStatement("select * from x").executeQuery()) {
                    sink.clear();
                    assertResultSet(expected, sink, resultSet);
                }
            }
        });
    }

    @Test
    public void testInsertDoubleTableWithTypeSuffix() throws Exception {
        assertMemoryLeak(() -> {
            try (
                    final PGWireServer ignored = createPGServer(1);
                    final Connection connection = getConnection(true, false)
            ) {
                final PreparedStatement statement = connection.prepareStatement("create table x (val double)");
                statement.execute();

                // mimics the behavior of Python drivers
                // which will set NaN and Inf into string with ::float suffix
                final PreparedStatement insert = connection.prepareStatement("insert into x values " +
                        "('NaN'::float)," +
                        "('Infinity'::float)," +
                        "('-Infinity'::float)," +
                        "('1.234567890123'::float)");
                insert.execute();

                final String expectedAbleToInsertToDoubleTable = "val[DOUBLE]\n" +
                        "null\n" +
                        "Infinity\n" +
                        "-Infinity\n" +
                        "1.234567890123\n";
                try (ResultSet resultSet = connection.prepareStatement("select * from x").executeQuery()) {
                    sink.clear();
                    assertResultSet(expectedAbleToInsertToDoubleTable, sink, resultSet);
                }

                final String expectedInsertWithoutLosingPrecision = "val[DOUBLE]\n" +
                        "1.234567890123\n";
                try (ResultSet resultSet = connection.prepareStatement("select * from x where val = cast('1.234567890123' as double)").executeQuery()) {
                    sink.clear();
                    assertResultSet(expectedInsertWithoutLosingPrecision, sink, resultSet);
                }
            }
        });
    }

    @Test
    public void testInsertFloatTableWithTypeSuffix() throws Exception {
        assertMemoryLeak(() -> {
            try (
                    final PGWireServer ignored = createPGServer(1);
                    final Connection connection = getConnection(true, false)
            ) {
                final PreparedStatement statement = connection.prepareStatement("create table x (val float)");
                statement.execute();

                // mimics the behavior of Python drivers
                // which will set NaN and Inf into string with ::float suffix
                final PreparedStatement insert = connection.prepareStatement("insert into x values " +
                        "('NaN'::float)," +
                        "('Infinity'::float)," +
                        "('-Infinity'::float)," +
                        "('1.234567890123'::float)");  // should be first cast info double, then cast to float on insert
                insert.execute();

                final String expectedAbleToInsertToFloatTable = "val[REAL]\n" +
                        "null\n" +
                        "Infinity\n" +
                        "-Infinity\n" +
                        "1.235\n";
                try (ResultSet resultSet = connection.prepareStatement("select * from x").executeQuery()) {
                    sink.clear();
                    assertResultSet(expectedAbleToInsertToFloatTable, sink, resultSet);
                }

                final String expectedInsertWithLosingPrecision = "val[REAL]\n" +
                        "1.235\n";
                try (ResultSet resultSet = connection.prepareStatement("select * from x where val = 1.23456788063").executeQuery()) {
                    sink.clear();
                    assertResultSet(expectedInsertWithLosingPrecision, sink, resultSet);
                }
            }
        });
    }

    @Test
    public void testIntAndLongParametersWithFormatCountGreaterThanValueCount() throws Exception {
        String script = ">0000006e00030000757365720078797a0064617461626173650071646200636c69656e745f656e636f64696e67005554463800446174655374796c650049534f0054696d655a6f6e65004575726f70652f4c6f6e646f6e0065787472615f666c6f61745f64696769747300320000\n" +
                "<520000000800000003\n" +
                ">70000000076f6800\n" +
                "<520000000800000000530000001154696d655a6f6e6500474d5400530000001d6170706c69636174696f6e5f6e616d6500517565737444420053000000187365727665725f76657273696f6e0031312e33005300000019696e74656765725f6461746574696d6573006f6e005300000019636c69656e745f656e636f64696e670055544638005a0000000549\n" +
                ">50000000300073656c65637420782c202024312c2024322066726f6d206c6f6e675f73657175656e63652832293b000000\n" +
                ">420000002500000003000000000000000200000001330000000a353030303030303030300000\n" +
                "<!!";

        assertHexScript(
                NetworkFacadeImpl.INSTANCE,
                script,
                getHexPgWireConfig()
        );
    }

    @Test
    public void testIntAndLongParametersWithFormatCountSmallerThanValueCount() throws Exception {
        String script = ">0000006e00030000757365720078797a0064617461626173650071646200636c69656e745f656e636f64696e67005554463800446174655374796c650049534f0054696d655a6f6e65004575726f70652f4c6f6e646f6e0065787472615f666c6f61745f64696769747300320000\n" +
                "<520000000800000003\n" +
                ">70000000076f6800\n" +
                "<520000000800000000530000001154696d655a6f6e6500474d5400530000001d6170706c69636174696f6e5f6e616d6500517565737444420053000000187365727665725f76657273696f6e0031312e33005300000019696e74656765725f6461746574696d6573006f6e005300000019636c69656e745f656e636f64696e670055544638005a0000000549\n" +
                ">50000000300073656c65637420782c202024312c2024322066726f6d206c6f6e675f73657175656e63652832293b000000\n" +
                ">42000000230000000200000000000300000001330000000a353030303030303030300000\n" +
                "<!!";

        assertHexScript
                (NetworkFacadeImpl.INSTANCE,
                        script,
                        getHexPgWireConfig()
                );
    }

    @Test
    public void testIntAndLongParametersWithoutExplicitParameterTypeButOneExplicitTextFormatHex() throws Exception {
        String script = ">0000006e00030000757365720078797a0064617461626173650071646200636c69656e745f656e636f64696e67005554463800446174655374796c650049534f0054696d655a6f6e65004575726f70652f4c6f6e646f6e0065787472615f666c6f61745f64696769747300320000\n" +
                "<520000000800000003\n" +
                ">70000000076f6800\n" +
                "<520000000800000000530000001154696d655a6f6e6500474d5400530000001d6170706c69636174696f6e5f6e616d6500517565737444420053000000187365727665725f76657273696f6e0031312e33005300000019696e74656765725f6461746574696d6573006f6e005300000019636c69656e745f656e636f64696e670055544638005a0000000549\n" +
                ">50000000300073656c65637420782c202024312c2024322066726f6d206c6f6e675f73657175656e63652832293b000000\n" +
                ">4200000021000000010000000200000001330000000a35303030303030303030000044000000065000450000000900000000004800000004\n" +
                "<31000000043200000004540000004400037800000000000001000000140008ffffffff000024310000000000000200000413ffffffffffff000024320000000000000300000413ffffffffffff0000440000001e0003000000013100000001330000000a35303030303030303030440000001e0003000000013200000001330000000a35303030303030303030430000000d53454c454354203200\n";

        assertHexScript(
                NetworkFacadeImpl.INSTANCE,
                script,
                getHexPgWireConfig()
        );
    }

    @Test
    public void testIntParameterWithoutExplicitParameterTypeButExplicitTextFormatHex() throws Exception {
        String script = ">0000006e00030000757365720078797a0064617461626173650071646200636c69656e745f656e636f64696e67005554463800446174655374796c650049534f0054696d655a6f6e65004575726f70652f4c6f6e646f6e0065787472615f666c6f61745f64696769747300320000\n" +
                "<520000000800000003\n" +
                ">70000000076f6800\n" +
                "<520000000800000000530000001154696d655a6f6e6500474d5400530000001d6170706c69636174696f6e5f6e616d6500517565737444420053000000187365727665725f76657273696f6e0031312e33005300000019696e74656765725f6461746574696d6573006f6e005300000019636c69656e745f656e636f64696e670055544638005a0000000549\n" +
                ">500000002c0073656c65637420782c202024312066726f6d206c6f6e675f73657175656e63652832293b000000\n" +
                ">420000001300000001000000010000000133000044000000065000450000000900000000004800000004\n" +
                "<31000000043200000004540000002f00027800000000000001000000140008ffffffff000024310000000000000200000413ffffffffffff000044000000100002000000013100000001334400000010000200000001320000000133430000000d53454c454354203200\n";

        assertHexScript(
                NetworkFacadeImpl.INSTANCE,
                script,
                getHexPgWireConfig()
        );
    }

    @Test
    public void testInvalidateWriterBetweenInserts() throws Exception {
        assertMemoryLeak(() -> {
            try (
                    final PGWireServer ignored = createPGServer(2);
                    final Connection connection = getConnection(false, true)
            ) {
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate("create table test_batch(id long,val int)");
                }
                try (PreparedStatement batchInsert = connection.prepareStatement("insert into test_batch(id,val) values(?,?)")) {
                    batchInsert.setLong(1, 0L);
                    batchInsert.setInt(2, 1);
                    batchInsert.addBatch();

                    batchInsert.clearParameters();
                    batchInsert.setLong(1, 1L);
                    batchInsert.setInt(2, 2);
                    batchInsert.addBatch();

                    batchInsert.clearParameters();
                    batchInsert.setLong(1, 2L);
                    batchInsert.setInt(2, 3);
                    batchInsert.addBatch();

                    int[] a = batchInsert.executeBatch();
                    Assert.assertEquals(3, a.length);
                    Assert.assertEquals(1, a[0]);
                    Assert.assertEquals(1, a[1]);
                    Assert.assertEquals(1, a[2]);


                    compiler.compile("create table spot1 as (select * from test_batch)", sqlExecutionContext);
                    compiler.compile("drop table test_batch", sqlExecutionContext);
                    compiler.compile("rename table spot1 to test_batch", sqlExecutionContext);

                    batchInsert.setLong(1, 0L);
                    batchInsert.setInt(2, 1);
                    batchInsert.addBatch();

                    batchInsert.clearParameters();
                    batchInsert.setLong(1, 1L);
                    batchInsert.setInt(2, 2);
                    batchInsert.addBatch();

                    batchInsert.clearParameters();
                    batchInsert.setLong(1, 2L);
                    batchInsert.setInt(2, 3);
                    batchInsert.addBatch();

                    a = batchInsert.executeBatch();
                    Assert.assertEquals(3, a.length);
                    Assert.assertEquals(1, a[0]);
                    Assert.assertEquals(1, a[1]);
                    Assert.assertEquals(1, a[2]);
                }

                StringSink sink = new StringSink();
                String expected = "id[BIGINT],val[INTEGER]\n" +
                        "0,1\n" +
                        "1,2\n" +
                        "2,3\n" +
                        "0,1\n" +
                        "1,2\n" +
                        "2,3\n";
                try (
                        Statement statement = connection.createStatement();
                        ResultSet rs = statement.executeQuery("select * from test_batch")
                ) {
                    assertResultSet(expected, sink, rs);
                }
            }
        });
    }

    @Test
    public void testLargeBatchCairoExceptionResume() throws Exception {
        assertMemoryLeak(() -> {
            try (
                    final PGWireServer ignored = createPGServer(4);
                    final Connection connection = getConnection(false, true)
            ) {
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate("create table test_large_batch(id long, val int, ts timestamp) timestamp(ts)");
                }
                connection.setAutoCommit(false);
                try (PreparedStatement batchInsert = connection.prepareStatement("insert into test_large_batch(id,val,ts) values(?,?,?)")) {
                    for (int i = 0; i < 2; i++) {
                        batchInsert.clearParameters();
                        batchInsert.setLong(1, 0L);
                        batchInsert.setInt(2, 1);
                        batchInsert.setLong(3, i);
                        batchInsert.addBatch();
                    }

                    try {
                        // insert out of order
                        batchInsert.setLong(1, 0L);
                        batchInsert.setInt(2, 1);
                        batchInsert.setLong(3, -100);
                        batchInsert.addBatch();
                        batchInsert.executeBatch();
                        Assert.fail();
                    } catch (SQLException e) {
                        TestUtils.assertContains(e.getMessage(), "timestamp before 1970-01-01 is not allowed");
                        connection.rollback();
                    }

                    // try again
                    for (int i = 0; i < 30; i++) {
                        batchInsert.clearParameters();
                        batchInsert.setLong(1, 0L);
                        batchInsert.setInt(2, 1);
                        batchInsert.setLong(3, i);
                        batchInsert.addBatch();
                    }
                    batchInsert.executeBatch();
                    connection.commit();
                }

                StringSink sink = new StringSink();
                String expected = "count[BIGINT]\n" +
                        "30\n";
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("select count(*) from test_large_batch");
                assertResultSet(expected, sink, rs);
            }
        });
    }

    @Test
    public void testLargeBatchInsertMethod() throws Exception {
        assertMemoryLeak(() -> {
            try (
                    final PGWireServer ignored = createPGServer(4);
                    final Connection connection = getConnection(false, true)
            ) {
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate("create table test_large_batch(id long,val int)");
                }
                connection.setAutoCommit(false);
                try (PreparedStatement batchInsert = connection.prepareStatement("insert into test_large_batch(id,val) values(?,?)")) {
                    for (int i = 0; i < 50_000; i++) {
                        batchInsert.clearParameters();
                        batchInsert.setLong(1, 0L);
                        batchInsert.setInt(2, 1);
                        batchInsert.addBatch();

                        batchInsert.clearParameters();
                        batchInsert.setLong(1, 1L);
                        batchInsert.setInt(2, 2);
                        batchInsert.addBatch();

                        batchInsert.clearParameters();
                        batchInsert.setLong(1, 2L);
                        batchInsert.setInt(2, 3);
                        batchInsert.addBatch();
                    }
                    batchInsert.executeBatch();
                    connection.commit();
                }

                StringSink sink = new StringSink();
                String expected = "count[BIGINT]\n" +
                        "150000\n";
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("select count(*) from test_large_batch");
                assertResultSet(expected, sink, rs);
            }
        });
    }

    @Test
    public void testLargeOutput() throws Exception {
        TestUtils.assertMemoryLeak(() -> {

            final String expected = "1[INTEGER],2[INTEGER],3[INTEGER]\n" +
                    "1,2,3\n" +
                    "1,2,3\n" +
                    "1,2,3\n" +
                    "1,2,3\n" +
                    "1,2,3\n" +
                    "1,2,3\n" +
                    "1,2,3\n" +
                    "1,2,3\n" +
                    "1,2,3\n" +
                    "1,2,3\n" +
                    "1,2,3\n" +
                    "1,2,3\n" +
                    "1,2,3\n" +
                    "1,2,3\n" +
                    "1,2,3\n" +
                    "1,2,3\n" +
                    "1,2,3\n" +
                    "1,2,3\n" +
                    "1,2,3\n" +
                    "1,2,3\n" +
                    "1,2,3\n" +
                    "1,2,3\n" +
                    "1,2,3\n" +
                    "1,2,3\n" +
                    "1,2,3\n" +
                    "1,2,3\n" +
                    "1,2,3\n" +
                    "1,2,3\n" +
                    "1,2,3\n" +
                    "1,2,3\n" +
                    "1,2,3\n" +
                    "1,2,3\n" +
                    "1,2,3\n" +
                    "1,2,3\n" +
                    "1,2,3\n" +
                    "1,2,3\n" +
                    "1,2,3\n" +
                    "1,2,3\n" +
                    "1,2,3\n" +
                    "1,2,3\n" +
                    "1,2,3\n" +
                    "1,2,3\n" +
                    "1,2,3\n" +
                    "1,2,3\n" +
                    "1,2,3\n" +
                    "1,2,3\n" +
                    "1,2,3\n" +
                    "1,2,3\n" +
                    "1,2,3\n" +
                    "1,2,3\n";

            final PGWireConfiguration configuration = new DefaultPGWireConfiguration() {
                @Override
                public int getSendBufferSize() {
                    return 512;
                }
            };

            try (
                    final PGWireServer ignored = createPGServer(configuration);
                    final Connection connection = getConnection(false, false)
            ) {
                PreparedStatement statement = connection.prepareStatement("select 1,2,3 from long_sequence(50)");
                Statement statement1 = connection.createStatement();

                StringSink sink = new StringSink();
                for (int i = 0; i < 10; i++) {
                    sink.clear();
                    ResultSet rs = statement.executeQuery();

                    statement1.executeQuery("select 1 from long_sequence(2)");
                    assertResultSet(expected, sink, rs);
                    rs.close();
                }
            }
        });
    }

    @Test
    public void testLargeOutputHex() throws Exception {
        String script = ">0000007300030000757365720078797a006461746162617365006e6162755f61707000636c69656e745f656e636f64696e67005554463800446174655374796c650049534f0054696d655a6f6e65004575726f70652f4c6f6e646f6e0065787472615f666c6f61745f64696769747300320000\n" +
                "<520000000800000003\n" +
                ">70000000076f6800\n" +
                "<520000000800000000530000001154696d655a6f6e6500474d5400530000001d6170706c69636174696f6e5f6e616d6500517565737444420053000000187365727665725f76657273696f6e0031312e33005300000019696e74656765725f6461746574696d6573006f6e005300000019636c69656e745f656e636f64696e670055544638005a0000000549\n" +
                ">5000000022005345542065787472615f666c6f61745f646967697473203d2033000000420000000c0000000000000000450000000900000000015300000004\n" +
                "<310000000432000000044300000008534554005a0000000549\n" +
                ">500000003700534554206170706c69636174696f6e5f6e616d65203d2027506f737467726553514c204a4442432044726976657227000000420000000c0000000000000000450000000900000000015300000004\n" +
                "<310000000432000000044300000008534554005a0000000549\n" +
                ">500000002b0073656c65637420312c322c332066726f6d206c6f6e675f73657175656e636528353029000000420000000c000000000000000044000000065000450000000900000000005300000004\n" +
                "<31000000043200000004540000004200033100000000000001000000170004ffffffff00003200000000000002000000170004ffffffff00003300000000000003000000170004ffffffff000044000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133\n" +
                "<4400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133\n" +
                "<4400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133430000000e53454c454354203530005a0000000549\n" +
                ">50000000260073656c65637420312066726f6d206c6f6e675f73657175656e6365283229000000420000000c000000000000000044000000065000450000000900000000005300000004\n" +
                "<31000000043200000004540000001a00013100000000000001000000170004ffffffff0000440000000b00010000000131440000000b00010000000131430000000d53454c4543542032005a0000000549\n" +
                ">500000002b0073656c65637420312c322c332066726f6d206c6f6e675f73657175656e636528353029000000420000000c000000000000000044000000065000450000000900000000005300000004\n" +
                "<31000000043200000004540000004200033100000000000001000000170004ffffffff00003200000000000002000000170004ffffffff00003300000000000003000000170004ffffffff000044000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133\n" +
                "<4400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133\n" +
                "<4400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133430000000e53454c454354203530005a0000000549\n" +
                ">50000000260073656c65637420312066726f6d206c6f6e675f73657175656e6365283229000000420000000c000000000000000044000000065000450000000900000000005300000004\n" +
                "<31000000043200000004540000001a00013100000000000001000000170004ffffffff0000440000000b00010000000131440000000b00010000000131430000000d53454c4543542032005a0000000549\n" +
                ">500000002b0073656c65637420312c322c332066726f6d206c6f6e675f73657175656e636528353029000000420000000c000000000000000044000000065000450000000900000000005300000004\n" +
                "<31000000043200000004540000004200033100000000000001000000170004ffffffff00003200000000000002000000170004ffffffff00003300000000000003000000170004ffffffff000044000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133\n" +
                "<4400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133\n" +
                "<4400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133430000000e53454c454354203530005a0000000549\n" +
                ">50000000260073656c65637420312066726f6d206c6f6e675f73657175656e6365283229000000420000000c000000000000000044000000065000450000000900000000005300000004\n" +
                "<31000000043200000004540000001a00013100000000000001000000170004ffffffff0000440000000b00010000000131440000000b00010000000131430000000d53454c4543542032005a0000000549\n" +
                ">500000002b0073656c65637420312c322c332066726f6d206c6f6e675f73657175656e636528353029000000420000000c000000000000000044000000065000450000000900000000005300000004\n" +
                "<31000000043200000004540000004200033100000000000001000000170004ffffffff00003200000000000002000000170004ffffffff00003300000000000003000000170004ffffffff000044000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133\n" +
                "<4400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133\n" +
                "<4400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133430000000e53454c454354203530005a0000000549\n" +
                ">50000000260073656c65637420312066726f6d206c6f6e675f73657175656e6365283229000000420000000c000000000000000044000000065000450000000900000000005300000004\n" +
                "<31000000043200000004540000001a00013100000000000001000000170004ffffffff0000440000000b00010000000131440000000b00010000000131430000000d53454c4543542032005a0000000549\n" +
                ">500000002e535f310073656c65637420312c322c332066726f6d206c6f6e675f73657175656e636528353029000000420000000f00535f310000000000000044000000065000450000000900000000005300000004\n" +
                "<31000000043200000004540000004200033100000000000001000000170004ffffffff00003200000000000002000000170004ffffffff00003300000000000003000000170004ffffffff000044000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133\n" +
                "<4400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133\n" +
                "<4400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133430000000e53454c454354203530005a0000000549\n" +
                ">50000000260073656c65637420312066726f6d206c6f6e675f73657175656e6365283229000000420000000c000000000000000044000000065000450000000900000000005300000004\n" +
                "<31000000043200000004540000001a00013100000000000001000000170004ffffffff0000440000000b00010000000131440000000b00010000000131430000000d53454c4543542032005a0000000549\n" +
                ">420000000f00535f3100000000000000450000000900000000005300000004\n" +
                "<32000000044400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133\n" +
                "<4400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133\n" +
                "<44000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133430000000e53454c454354203530005a0000000549\n" +
                ">50000000260073656c65637420312066726f6d206c6f6e675f73657175656e6365283229000000420000000c000000000000000044000000065000450000000900000000005300000004\n" +
                "<31000000043200000004540000001a00013100000000000001000000170004ffffffff0000440000000b00010000000131440000000b00010000000131430000000d53454c4543542032005a0000000549\n" +
                ">420000000f00535f3100000000000000450000000900000000005300000004\n" +
                "<32000000044400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133\n" +
                "<4400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133\n" +
                "<44000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133430000000e53454c454354203530005a0000000549\n" +
                ">50000000260073656c65637420312066726f6d206c6f6e675f73657175656e6365283229000000420000000c000000000000000044000000065000450000000900000000005300000004\n" +
                "<31000000043200000004540000001a00013100000000000001000000170004ffffffff0000440000000b00010000000131440000000b00010000000131430000000d53454c4543542032005a0000000549\n" +
                ">420000000f00535f3100000000000000450000000900000000005300000004\n" +
                "<32000000044400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133\n" +
                "<4400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133\n" +
                "<44000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133430000000e53454c454354203530005a0000000549\n" +
                ">50000000260073656c65637420312066726f6d206c6f6e675f73657175656e6365283229000000420000000c000000000000000044000000065000450000000900000000005300000004\n" +
                "<31000000043200000004540000001a00013100000000000001000000170004ffffffff0000440000000b00010000000131440000000b00010000000131430000000d53454c4543542032005a0000000549\n" +
                ">420000000f00535f3100000000000000450000000900000000005300000004\n" +
                "<32000000044400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133\n" +
                "<4400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133\n" +
                "<44000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133430000000e53454c454354203530005a0000000549\n" +
                ">50000000260073656c65637420312066726f6d206c6f6e675f73657175656e6365283229000000420000000c000000000000000044000000065000450000000900000000005300000004\n" +
                "<31000000043200000004540000001a00013100000000000001000000170004ffffffff0000440000000b00010000000131440000000b00010000000131430000000d53454c4543542032005a0000000549\n" +
                ">420000000f00535f3100000000000000450000000900000000005300000004\n" +
                "<32000000044400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133\n" +
                "<4400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133\n" +
                "<44000000150003000000013100000001320000000133440000001500030000000131000000013200000001334400000015000300000001310000000132000000013344000000150003000000013100000001320000000133430000000e53454c454354203530005a0000000549\n" +
                ">50000000260073656c65637420312066726f6d206c6f6e675f73657175656e6365283229000000420000000c000000000000000044000000065000450000000900000000005300000004\n" +
                "<31000000043200000004540000001a00013100000000000001000000170004ffffffff0000440000000b00010000000131440000000b00010000000131430000000d53454c4543542032005a0000000549\n" +
                ">5800000004\n";
        assertHexScript(NetworkFacadeImpl.INSTANCE, script, new DefaultPGWireConfiguration() {
            @Override
            public String getDefaultPassword() {
                return "oh";
            }

            @Override
            public String getDefaultUsername() {
                return "xyz";
            }

            @Override
            public int getSendBufferSize() {
                return 512;
            }
        });
    }

    @Test
    public void testLargeSelect() throws Exception {
        assertMemoryLeak(() -> {
            try (
                    final PGWireServer ignored = createPGServer(4);
                    final Connection connection = getConnection(false, true)
            ) {
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate("CREATE TABLE IF NOT EXISTS recorded_l1_data (\n" +
                            " HighLimitPrice double,\n" +
                            " LastAuctionImbalanceSide string,\n" +
                            " LastAuctionImbalanceVolume double,\n" +
                            " LastAuctionPrice double,\n" +
                            " LastAuctionVolume double,\n" +
                            " LastPrice double,\n" +
                            " LastTradePrice double,\n" +
                            " LastTradeQty double,\n" +
                            " LowLimitPrice double,\n" +
                            " MARKET_EURONEXT_PhaseQualifier long,\n" +
                            " MARKET_EURONEXT_StatusReason long,\n" +
                            " MARKET_EURONEXT_TradingPeriod long,\n" +
                            " MARKET_GroupTradingStatus long,\n" +
                            " MARKET_JSE_MIT_TradingStatusDetails string,\n" +
                            " MARKET_LSE_SuspendedIndicator string,\n" +
                            " MARKET_OMX_NORDIC_NoteCodes1 long,\n" +
                            " MARKET_OMX_NORDIC_NoteCodes2 long,\n" +
                            " MARKET_SWX_BookCondition long,\n" +
                            " MARKET_SWX_SecurityTradingStatus long,\n" +
                            " MARKET_SWX_TradingPhase string,\n" +
                            " MARKET_SWX_TradingSessionSubID string,\n" +
                            " MARKET_TradingStatus long,\n" +
                            " askPx double,\n" +
                            " askQty double,\n" +
                            " bidPx double,\n" +
                            " bidQty double,\n" +
                            " glid symbol,\n" +
                            " TradingStatus long,\n" +
                            " serverTimestamp long,\n" +
                            " marketTimestamp long,\n" +
                            " timestamp timestamp\n" +
                            " ) timestamp(timestamp) partition by DAY;"
                    );
                }

                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate("insert into recorded_l1_data \n" +
                            " select \n" +
                            "     rnd_double(), \n" +
                            "     rnd_str(), \n" +
                            "     rnd_double(),\n" +
                            "     rnd_double(),\n" +
                            "     rnd_double(),\n" +
                            "     rnd_double(),\n" +
                            "     rnd_double(),\n" +
                            "     rnd_double(),\n" +
                            "     rnd_double(),\n" +
                            "     rnd_long(),\n" +
                            "     rnd_long(),\n" +
                            "     rnd_long(),\n" +
                            "     rnd_long(),\n" +
                            "     rnd_str(),\n" +
                            "     rnd_str(),\n" +
                            "     rnd_long(),\n" +
                            "     rnd_long(),\n" +
                            "     rnd_long(),\n" +
                            "     rnd_long(),\n" +
                            "     rnd_str(),\n" +
                            "     rnd_str(),\n" +
                            "     rnd_long(),\n" +
                            "     rnd_double(),\n" +
                            "     rnd_double(),\n" +
                            "     rnd_double(),\n" +
                            "     rnd_double(),\n" +
                            "     rnd_symbol('a','b','c'),\n" +
                            "     rnd_long(),\n" +
                            "     rnd_long(),\n" +
                            "     rnd_long(),\n" +
                            "     timestamp_sequence(0, 100000)\n" +
                            "     from long_sequence(50000)\n" +
                            "    )");
                }

                double sum = 0;
                long count = 0;
                try (PreparedStatement preparedStatement = connection.prepareStatement("SELECT * FROM recorded_l1_data;")) {
                    try (ResultSet rs = preparedStatement.executeQuery()) {
                        while (rs.next()) {
                            sum += rs.getDouble(1);
                            count++;
                        }
                    }
                }
                Assert.assertEquals(50_000, count);
                Assert.assertEquals(24963.57352782434, sum, 0.00000001);
            }
        });
    }

    @Test
    public void testLocalCopyFrom() throws Exception {
        try (final PGWireServer ignored = createPGServer(2);
             final Connection connection = getConnection(false, true);
             final PreparedStatement copyStatement = connection.prepareStatement("copy testLocalCopyFrom from '/src/test/resources/csv/test-numeric-headers.csv' with header true")) {

            copyStatement.execute();

            try (final PreparedStatement selectStatement = connection.prepareStatement("select * FROM testLocalCopyFrom");
                 final ResultSet rs = selectStatement.executeQuery()) {
                sink.clear();
                assertResultSet("type[VARCHAR],value[VARCHAR],active[VARCHAR],desc[VARCHAR],_1[INTEGER]\n"
                        + "ABC,xy,a,brown fox jumped over the fence,10\n"
                        + "CDE,bb,b,sentence 1\n"
                        + "sentence 2,12\n", sink, rs);
            }
        }
    }

    @Test
    public void testLoginBadPassword() throws Exception {
        assertMemoryLeak(() -> {
            try (PGWireServer ignored = createPGServer(1)) {
                Properties properties = new Properties();
                properties.setProperty("user", "admin");
                properties.setProperty("password", "dunno");
                try {
                    DriverManager.getConnection("jdbc:postgresql://127.0.0.1:8812/qdb", properties);
                    Assert.fail();
                } catch (SQLException e) {
                    TestUtils.assertContains(e.getMessage(), "invalid username/password");
                }
            }
        });
    }

    @Test
    public void testLoginBadUsername() throws Exception {
        TestUtils.assertMemoryLeak(() -> {
            try (PGWireServer ignored = createPGServer(1)) {
                Properties properties = new Properties();
                properties.setProperty("user", "joe");
                properties.setProperty("password", "quest");
                try {
                    DriverManager.getConnection("jdbc:postgresql://127.0.0.1:8812/qdb", properties);
                    Assert.fail();
                } catch (SQLException e) {
                    TestUtils.assertContains(e.getMessage(), "invalid username/password");
                }
            }
        });
    }

    @Test
    public void testLoginBadUsernameHex() throws Exception {
        // this test specifically assert that we do not send
        // "ready for next query" message back to client when they fail to log in
        String script = ">0000000804d2162f\n" +
                "<4e\n" +
                ">0000003c00030000636c69656e745f656e636f64696e6700277574662d382700757365720078797a00646174616261736500706f7374677265730000\n" +
                "<520000000800000003\n" +
                ">70000000076f6800\n" +
                "<450000002e433030303030004d696e76616c696420757365726e616d652f70617373776f726400534552524f520000\n";
        assertHexScript(
                NetworkFacadeImpl.INSTANCE,
                script,
                new DefaultPGWireConfiguration()
        );
    }

    @Test
    public void testMalformedInitPropertyName() throws Exception {
        assertHexScript(
                NetworkFacadeImpl.INSTANCE,
                ">0000004c00030000757365720061646d696e006461746162617365006e6162755f61707000636c69656e745f656e636f64696e67005554463800446174655374796c650049534f0054696d655a6f6e6500474d540065787472615f666c6f61745f64696769747300320000\n" +
                        "<!!",
                new DefaultPGWireConfiguration()
        );
    }

    @Test
    public void testMalformedInitPropertyValue() throws Exception {
        assertHexScript(
                NetworkFacadeImpl.INSTANCE,
                ">0000001e00030000757365720061646d696e006461746162617365006e6162755f61707000636c69656e745f656e636f64696e67005554463800446174655374796c650049534f0054696d655a6f6e6500474d540065787472615f666c6f61745f64696769747300320000\n" +
                        "<!!",
                new DefaultPGWireConfiguration()
        );
    }

    @Test
    public void testMicroTimestamp() throws Exception {
        assertMemoryLeak(() -> {
            try (
                    final PGWireServer ignored = createPGServer(2);
                    final Connection connection = getConnection(false, true)
            ) {
                connection.prepareCall("create table x(t timestamp)").execute();

                PreparedStatement statement = connection.prepareStatement("insert into x values (?)");

                final String expected = "t[TIMESTAMP]\n" +
                        "2019-02-11 13:48:11.123998\n" +
                        "2019-02-11 13:48:11.123999\n" +
                        "2019-02-11 13:48:11.124\n" +
                        "2019-02-11 13:48:11.124001\n" +
                        "2019-02-11 13:48:11.124002\n" +
                        "2019-02-11 13:48:11.124003\n" +
                        "2019-02-11 13:48:11.124004\n" +
                        "2019-02-11 13:48:11.124005\n" +
                        "2019-02-11 13:48:11.124006\n" +
                        "2019-02-11 13:48:11.124007\n" +
                        "2019-02-11 13:48:11.124008\n" +
                        "2019-02-11 13:48:11.124009\n" +
                        "2019-02-11 13:48:11.12401\n" +
                        "2019-02-11 13:48:11.124011\n" +
                        "2019-02-11 13:48:11.124012\n" +
                        "2019-02-11 13:48:11.124013\n" +
                        "2019-02-11 13:48:11.124014\n" +
                        "2019-02-11 13:48:11.124015\n" +
                        "2019-02-11 13:48:11.124016\n" +
                        "2019-02-11 13:48:11.124017\n";

                long ts = TimestampFormatUtils.parseUTCTimestamp("2019-02-11T13:48:11.123998Z");
                for (int i = 0; i < 20; i++) {
                    statement.setLong(1, ts + i);
                    statement.execute();
                }
                StringSink sink = new StringSink();
                PreparedStatement sel = connection.prepareStatement("x");
                ResultSet res = sel.executeQuery();
                assertResultSet(expected, sink, res);
            }
        });
    }

    @Test
    public void testMultiplePreparedStatements() throws Exception {
        TestUtils.assertMemoryLeak(() -> {
            try (
                    final PGWireServer ignored = createPGServer(2);
                    final Connection connection = getConnection(false, false)
            ) {
                PreparedStatement ps1 = connection.prepareStatement("select 1,2,3 from long_sequence(1)");
                PreparedStatement ps2 = connection.prepareStatement("select 4,5,6 from long_sequence(1)");
                PreparedStatement ps3 = connection.prepareStatement("select 7,8,9 from long_sequence(2)");

                final String expected = "1[INTEGER],2[INTEGER],3[INTEGER]\n" +
                        "1,2,3\n";

                StringSink sink = new StringSink();
                for (int i = 0; i < 10; i++) {
                    sink.clear();
                    ResultSet rs1 = ps1.executeQuery();
                    ResultSet rs2 = ps2.executeQuery();
                    ResultSet rs3 = ps3.executeQuery();

                    assertResultSet(expected, sink, rs1);
                    rs1.close();
                    rs2.close();
                    rs3.close();
                }

                Statement statement1 = connection.createStatement();
                for (int i = 0; i < 10; i++) {
                    PreparedStatement s = connection.prepareStatement("select 2,2,2,2 from long_sequence(1)");
                    s.executeQuery();
                    statement1.executeQuery("select 1 from long_sequence(2)");
                }
            }
        });
    }

    @Test
    @Ignore
    public void testMultistatement() throws Exception {
        assertMemoryLeak(() -> {
            try (
                    final PGWireServer ignored = createPGServer(1);
                    final Connection connection = getConnection(false, true)
            ) {
                connection.setAutoCommit(false);
                int totalRows = 100;

                CallableStatement tbl = connection.prepareCall(
                        "create table x as (select cast(x - 1 as int) a from long_sequence(" + totalRows + "))");
                tbl.execute();
                connection.commit();
                // Queries with multiple statements should not be transformed.
                PreparedStatement stmt = connection.prepareStatement("insert into x(a) values(100); x");
                stmt.setFetchSize(10);

                assertFalse(stmt.execute()); // INSERT
                assertTrue(stmt.getMoreResults()); // SELECT
                ResultSet rs = stmt.getResultSet();
                int count = 0;
                while (rs.next()) {
                    assertEquals(count, rs.getInt(1));
                    ++count;
                }
                assertEquals(totalRows + 1, count);
            }
        });
    }

    @Test
    public void testNamedStatementWithoutParameterTypeHex() throws Exception {
        String script = ">0000006e00030000757365720078797a0064617461626173650071646200636c69656e745f656e636f64696e67005554463800446174655374796c650049534f0054696d655a6f6e65004575726f70652f4c6f6e646f6e0065787472615f666c6f61745f64696769747300320000\n" +
                "<520000000800000003\n" +
                ">70000000076f6800\n" +
                "<520000000800000000530000001154696d655a6f6e6500474d5400530000001d6170706c69636174696f6e5f6e616d6500517565737444420053000000187365727665725f76657273696f6e0031312e33005300000019696e74656765725f6461746574696d6573006f6e005300000019636c69656e745f656e636f64696e670055544638005a0000000549\n" +
                ">5000000022005345542065787472615f666c6f61745f646967697473203d2033000000420000000c0000000000000000450000000900000000015300000004\n" +
                "<310000000432000000044300000008534554005a0000000549\n" +
                ">500000003700534554206170706c69636174696f6e5f6e616d65203d2027506f737467726553514c204a4442432044726976657227000000420000000c0000000000000000450000000900000000015300000004\n" +
                "<310000000432000000044300000008534554005a0000000549\n" +
                ">5000000032535f310073656c65637420782c24312c24322c24332066726f6d206c6f6e675f73657175656e6365283229000000420000002900535f31000003000000000000000300000001340000000331323300000004352e3433000044000000065000450000000900000000005300000004\n" +
                "<31000000043200000004540000005900047800000000000001000000140008ffffffff000024310000000000000200000413ffffffffffff000024320000000000000300000413ffffffffffff000024330000000000000400000413ffffffffffff0000440000001f0004000000013100000001340000000331323300000004352e3433440000001f0004000000013200000001340000000331323300000004352e3433430000000d53454c4543542032005a0000000549\n" +
                ">420000002900535f31000003000000000000000300000001340000000331323300000004352e34330000450000000900000000005300000004\n" +
                "<3200000004440000001f0004000000013100000001340000000331323300000004352e3433440000001f0004000000013200000001340000000331323300000004352e3433430000000d53454c4543542032005a0000000549\n" +
                ">430000000953535f31005300000004\n" +
                "<33000000045a0000000549\n" +
                ">5800000004\n";
        assertHexScript(
                NetworkFacadeImpl.INSTANCE,
                script,
                getHexPgWireConfig()
        );
    }

    // if the driver tries to use a cursor with autocommit on
    // it will fail because the cursor will disappear partway
    // through execution
    @Test
    public void testNoCursorWithAutoCommit() throws Exception {
        assertMemoryLeak(() -> {
            try (
                    final PGWireServer ignored = createPGServer(1);
                    final Connection connection = getConnection(false, true)
            ) {
                connection.setAutoCommit(false);
                int totalRows = 10;

                CallableStatement tbl = connection.prepareCall(
                        "create table x as (select cast(x - 1 as int) a from long_sequence(" + totalRows + "))");
                tbl.execute();

                connection.setAutoCommit(true);
                Statement stmt = connection.createStatement();
                stmt.setFetchSize(3);
                ResultSet rs = stmt.executeQuery("x");
                int count = 0;
                while (rs.next()) {
                    assertEquals(count++, rs.getInt(1));
                }
                assertEquals(totalRows, count);
            }
        });
    }

    @Test
    public void testNoDataAndEmptyQueryResponsesHex() throws Exception {
        String script = ">0000006e00030000757365720078797a0064617461626173650071646200636c69656e745f656e636f64696e67005554463800446174655374796c650049534f0054696d655a6f6e65004575726f70652f4c6f6e646f6e0065787472615f666c6f61745f64696769747300320000\n" +
                "<520000000800000003\n" +
                ">70000000076f6800\n" +
                "<520000000800000000530000001154696d655a6f6e6500474d5400530000001d6170706c69636174696f6e5f6e616d6500517565737444420053000000187365727665725f76657273696f6e0031312e33005300000019696e74656765725f6461746574696d6573006f6e005300000019636c69656e745f656e636f64696e670055544638005a0000000549\n" +
                ">5000000022005345542065787472615f666c6f61745f646967697473203d2033000000420000000c0000000000000000450000000900000000015300000004\n" +
                "<310000000432000000044300000008534554005a0000000549\n" +
                ">500000003700534554206170706c69636174696f6e5f6e616d65203d2027506f737467726553514c204a4442432044726976657227000000420000000c0000000000000000450000000900000000015300000004\n" +
                "<310000000432000000044300000008534554005a0000000549\n" +
                ">500000000800000000420000000c000000000000000044000000065000450000000900000000005300000004\n" +
                "<310000000432000000046e0000000449000000045a0000000549\n";
        assertHexScript(NetworkFacadeImpl.INSTANCE,
                script,
                getHexPgWireConfig());
    }

    @Test
    public void testNullTypeSerialization() throws Exception {
        assertMemoryLeak(() -> {
            try (final PGWireServer ignored = createPGServer(1)) {
                try (final Connection connection = getConnection(false, true)) {
                    sink.clear();
                    try (
                            PreparedStatement ps = connection.prepareStatement("create table test as (select x from long_sequence(10))")
                    ) {
                        ps.execute();
                    }
                }
                testNullTypeSerialization0(true, true);
                testNullTypeSerialization0(true, false);
                testNullTypeSerialization0(false, false);
                testNullTypeSerialization0(false, true);
            }
        });
    }

    @Test
    public void testPHPSelectHex() throws Exception {
        //         PHP client script to reproduce
        //        $dbName = 'qdb';
        //        $hostname = '127.0.0.1';
        //        $password = 'quest';
        //        $port = 8812;
        //        $username = 'admin';
        //
        //        $pdo = new PDO("pgsql:host=$hostname;dbname=$dbName;port=$port;options='--client_encoding=UTF8'", $username, $password);
        //        $stmt = $pdo->prepare("SELECT * FROM x00 limit 10");
        //        try {
        //            $stmt->execute(array());
        //            $res = $stmt->fetchAll();
        //            print_r($res);
        //        } catch(PDOException $e) {
        //            echo $e;
        //        }

        String scriptx00 = ">0000000804d2162f\n" +
                "<4e\n" +
                ">0000004000030000757365720061646d696e00646174616261736500716462006f7074696f6e73002d2d636c69656e745f656e636f64696e673d555446380000\n" +
                "<520000000800000003\n" +
                ">700000000a717565737400\n" +
                "<520000000800000000530000001154696d655a6f6e6500474d5400530000001d6170706c69636174696f6e5f6e616d6500517565737444420053000000187365727665725f76657273696f6e0031312e33005300000019696e74656765725f6461746574696d6573006f6e005300000019636c69656e745f656e636f64696e670055544638005a0000000549\n" +
                ">500000003370646f5f73746d745f30303030303030310053454c454354202a2046524f4d20783030206c696d69742031300000005300000004\n" +
                "<31000000045a0000000549\n" +
                ">420000001f0070646f5f73746d745f303030303030303100000000000001000044000000065000450000000900000000005300000004\n" +
                "<3200000004540000008a00066900000000000001000000170004ffffffff000073796d0000000000000200000413ffffffffffff0000616d7400000000000003000002bd0008ffffffff000074696d657374616d70000000000000040000045a0008ffffffff0000630000000000000500000413ffffffffffff00006400000000000006000002bd0008ffffffff0000440000005700060000000131000000046d7366740000000632322e3436330000001a323031382d30312d30312030303a31323a30302e3030303030300000000343444500000011302e323939313939303435393631383435440000005500060000000132000000046d7366740000000636352e3038360000001a323031382d30312d30312030303a32343a30302e303030303030ffffffff00000012302e39383536323930383435383734323633440000005800060000000133000000046d7366740000000635302e3933380000001a323031382d30312d30312030303a33363a30302e3030303030300000000358595a00000012302e37363131303239353134393935373434440000006400060000000134000000046d7366740000001235352e3939323030303030303030303030340000001a323031382d30312d30312030303a34383a30302e3030303030300000000358595a00000012302e32333930353239303130383436353235440000005a0006000000013500000005676f6f676c0000000636372e3738360000001a323031382d30312d30312030313a30303a30302e3030303030300000000358595a00000013302e333835333939343738363532343439393444000000650006000000013600000005676f6f676c0000001233332e3630383030303030303030303030340000001a323031382d30312d30312030313a31323a30302e3030303030300000000343444500000012302e3736373536373330373037393631303444000000590006000000013700000005676f6f676c0000000636322e3137330000001a323031382d30312d30312030313a32343a30302e3030303030300000000343444500000012302e3633383136303735333131373835313344000000470006000000013800000005676f6f676c0000000635372e3933350000001a323031382d30312d30312030313a33363a30302e3030303030300000000358595affffffff440000004300060000000139000000046d7366740000000636372e3631390000001a323031382d30312d30312030313a34383a30302e303030303030ffffffffffffffff4400000057000600000002313000000005676f6f676c0000000634322e3238310000001a323031382d30312d30312030323a30303a30302e303030303030ffffffff00000012302e37363634323536373533353936313338430000000e53454c454354203130005a0000000549\n" +
                ">51000000214445414c4c4f434154452070646f5f73746d745f303030303030303100\n" +
                "<450000003e433030303030004d7461626c6520646f6573206e6f74206578697374205b6e616d653d4445414c4c4f434154455d00534552524f5200503100005a0000000549\n" +
                ">5800000004\n";

        assertMemoryLeak(() -> {
            compiler.compile(
                    "create table x00 as (" +
                            "select" +
                            " cast(x as int) i," +
                            " rnd_symbol('msft','ibm', 'googl') sym," +
                            " round(rnd_double(0)*100, 3) amt," +
                            " to_timestamp('2018-01', 'yyyy-MM') + x * 720000000 timestamp," +
                            " rnd_str('ABC', 'CDE', null, 'XYZ') c," +
                            " rnd_double(2) d" +
                            " from long_sequence(10)" +
                            ") timestamp (timestamp)",
                    sqlExecutionContext
            );
            try (PGWireServer ignored = createPGServer(new DefaultPGWireConfiguration())) {
                NetUtils.playScript(NetworkFacadeImpl.INSTANCE, scriptx00, "127.0.0.1", 8812);
            }
        });
    }

    @Test
    public void testParameterTypeCountGreaterThanParameterValueCount() throws Exception {
        String script = ">0000006e00030000757365720078797a0064617461626173650071646200636c69656e745f656e636f64696e67005554463800446174655374796c650049534f0054696d655a6f6e65004575726f70652f4c6f6e646f6e0065787472615f666c6f61745f64696769747300320000\n" +
                "<520000000800000003\n" +
                ">70000000076f6800\n" +
                "<520000000800000000530000001154696d655a6f6e6500474d5400530000001d6170706c69636174696f6e5f6e616d6500517565737444420053000000187365727665725f76657273696f6e0031312e33005300000019696e74656765725f6461746574696d6573006f6e005300000019636c69656e745f656e636f64696e670055544638005a0000000549\n" +
                ">5000000022005345542065787472615f666c6f61745f646967697473203d2033000000420000000c0000000000000000450000000900000000015300000004\n" +
                "<310000000432000000044300000008534554005a0000000549\n" +
                ">500000003700534554206170706c69636174696f6e5f6e616d65203d2027506f737467726553514c204a4442432044726976657227000000420000000c0000000000000000450000000900000000015300000004\n" +
                "<310000000432000000044300000008534554005a0000000549\n" +
                ">500000003b0073656c65637420782c24312c24322c24332066726f6d206c6f6e675f73657175656e63652832290000030000001700000014000002bd420000002600000003000000000000000200000001340000000331323300000004352e3433000044000000065000450000000900000000005300000004\n" +
                "<!!";
        assertHexScript(NetworkFacadeImpl.INSTANCE,
                script,
                getHexPgWireConfig());
    }

    //checks that function parser error doesn't persist and affect later queries issued through the same connection
    @Test
    public void testParseErrorDoesNotCorruptConnection() throws Exception {
        TestUtils.assertMemoryLeak(() -> {
            try (final PGWireServer ignored = createPGServer(2);
                 final Connection connection = getConnection(false, false)) {

                try (PreparedStatement ps1 = connection.prepareStatement("select * from " +
                        "(select cast(x as timestamp) ts, cast('0x05cb69971d94a00000192178ef80f0' as long256) as id, x from long_sequence(10) ) " +
                        "where ts between '2022-03-20' " +
                        "AND id <> '0x05ab6d9fabdabb00066a5db735d17a' " +
                        "AND id <> '0x05aba84839b9c7000006765675e630' " +
                        "AND id <> '0x05abc58d80ba1f000001ed05351873'")) {
                    ps1.executeQuery();
                    Assert.fail("PSQLException should be thrown");
                } catch (PSQLException e) {
                    assertContains(e.getMessage(), "ERROR: unexpected argument for function: between");
                }

                try (PreparedStatement s = connection.prepareStatement("select 2 a,2 b from long_sequence(1) where x > 0 and x < 10")) {
                    StringSink sink = new StringSink();
                    ResultSet result = s.executeQuery();
                    assertResultSet("a[INTEGER],b[INTEGER]\n2,2\n", sink, result);
                }
            }
        });
    }

    @Test
    //checks that function parser error doesn't persist and affect later queries issued through the same connection
    public void testParseErrorDoesntCorruptConnection() throws Exception {
        TestUtils.assertMemoryLeak(() -> {
            try (final PGWireServer ignored = createPGServer(2);
                 final Connection connection = getConnection(false, false)) {

                try (PreparedStatement ps1 = connection.prepareStatement("select * from " +
                        "(select cast(x as timestamp) ts, cast('0x05cb69971d94a00000192178ef80f0' as long256) as id, x from long_sequence(10) ) " +
                        "where ts between '2022-03-20' " +
                        "AND id <> '0x05ab6d9fabdabb00066a5db735d17a' " +
                        "AND id <> '0x05aba84839b9c7000006765675e630' " +
                        "AND id <> '0x05abc58d80ba1f000001ed05351873'")) {
                    ps1.executeQuery();
                    Assert.fail("PSQLException should be thrown");
                } catch (PSQLException e) {
                    assertContains(e.getMessage(), "ERROR: unexpected argument for function: between");
                }

                try (PreparedStatement s = connection.prepareStatement("select 2 a,2 b from long_sequence(1) where x > 0 and x < 10")) {
                    StringSink sink = new StringSink();
                    ResultSet result = s.executeQuery();
                    assertResultSet("a[INTEGER],b[INTEGER]\n2,2\n", sink, result);
                }
            }
        });
    }

    @Test
    public void testParseMessageBadQueryTerminator() throws Exception {
        final String script = ">0000006900030000757365720078797a006461746162617365006e6162755f61707000636c69656e745f656e636f64696e67005554463800446174655374796c650049534f0054696d655a6f6e6500474d540065787472615f666c6f61745f64696769747300320000\n" +
                "<520000000800000003\n" +
                ">70000000076f6800\n" +
                "<520000000800000000530000001154696d655a6f6e6500474d5400530000001d6170706c69636174696f6e5f6e616d6500517565737444420053000000187365727665725f76657273696f6e0031312e33005300000019696e74656765725f6461746574696d6573006f6e005300000019636c69656e745f656e636f64696e670055544638005a0000000549\n" +
                ">5000000022005345542065787472615f666c6f61745f646967697473203d203308899889988998\n" +
                "<!!";
        assertHexScript(
                NetworkFacadeImpl.INSTANCE,
                script,
                getHexPgWireConfig()
        );
    }

    @Test
    public void testParseMessageBadStatementTerminator() throws Exception {
        final String script = ">0000006900030000757365720078797a006461746162617365006e6162755f61707000636c69656e745f656e636f64696e67005554463800446174655374796c650049534f0054696d655a6f6e6500474d540065787472615f666c6f61745f64696769747300320000\n" +
                "<520000000800000003\n" +
                ">70000000076f6800\n" +
                "<520000000800000000530000001154696d655a6f6e6500474d5400530000001d6170706c69636174696f6e5f6e616d6500517565737444420053000000187365727665725f76657273696f6e0031312e33005300000019696e74656765725f6461746574696d6573006f6e005300000019636c69656e745f656e636f64696e670055544638005a0000000549\n" +
                ">5000000022555345542065787472615f666c6f61745f646967697473203d2033555555425555550c5555555555555555455555550955555555015355555504\n" +
                "<!!";
        assertHexScript(
                NetworkFacadeImpl.INSTANCE,
                script,
                getHexPgWireConfig()
        );
    }

    @Test
    public void testParseMessageNegativeParameterCount() throws Exception {
        final String script = ">0000006900030000757365720078797a006461746162617365006e6162755f61707000636c69656e745f656e636f64696e67005554463800446174655374796c650049534f0054696d655a6f6e6500474d540065787472615f666c6f61745f64696769747300320000\n" +
                "<520000000800000003\n" +
                ">70000000076f6800\n" +
                "<520000000800000000530000001154696d655a6f6e6500474d5400530000001d6170706c69636174696f6e5f6e616d6500517565737444420053000000187365727665725f76657273696f6e0031312e33005300000019696e74656765725f6461746574696d6573006f6e005300000019636c69656e745f656e636f64696e670055544638005a0000000549\n" +
                ">5000000022005345542065787472615f666c6f61745f646967697473203d2033000000420000000c0000000000000000450000000900000000015300000004\n" +
                "<310000000432000000044300000008534554005a0000000549\n" +
                ">500000003700534554206170706c69636174696f6e5f6e616d65203d2027506f737467726553514c204a4442432044726976657227000000420000000c0000000000000000450000000900000000015300000004\n" +
                "<310000000432000000044300000008534554005a0000000549\n" +
                ">50000000cd0073656c65637420782c24312c24322c24332c24342c24352c24362c24372c24382c24392c2431302c2431312c2431322c2431332c2431342c2431352c2431362c2431372c2431382c2431392c2432302c2432312c2432322066726f6d206c6f6e675f73657175656e636528352900fefe0000001700000014000002bc000002bd0000001500000010000004130000041300000000000000000000001700000014000002bc000002bd000000150000001000000413000004130000043a000000000000045a000004a0420000012c0000001600010001000100010001000000000000000000000001000100010001000100000000000000010000000000000016000000040000000400000008000000000000007b0000000440adc28f000000083fe22c27a63736ce00000002005b00000004545255450000000568656c6c6f0000001dd0b3d180d183d0bfd0bfd0b020d182d183d180d0b8d181d182d0bed0b20000000e313937302d30312d3031202b30300000001a313937302d30382d32302031313a33333a32302e3033332b3030ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff0000001a313937302d30312d30312030303a30353a30302e3031312b30300000001a313937302d30312d30312030303a30383a32302e3032332b3030000044000000065000450000000900000000005300000004\n" +
                "<!!";
        assertHexScript(
                NetworkFacadeImpl.INSTANCE,
                script,
                getHexPgWireConfig()
        );
    }

    @Test
    public void testParseMessageTruncatedAtParameter() throws Exception {
        final String script = ">0000006900030000757365720078797a006461746162617365006e6162755f61707000636c69656e745f656e636f64696e67005554463800446174655374796c650049534f0054696d655a6f6e6500474d540065787472615f666c6f61745f64696769747300320000\n" +
                "<520000000800000003\n" +
                ">70000000076f6800\n" +
                "<520000000800000000530000001154696d655a6f6e6500474d5400530000001d6170706c69636174696f6e5f6e616d6500517565737444420053000000187365727665725f76657273696f6e0031312e33005300000019696e74656765725f6461746574696d6573006f6e005300000019636c69656e745f656e636f64696e670055544638005a0000000549\n" +
                ">5000000022005345542065787472615f666c6f61745f646967697473203d2033000000420000000c0000000000000000450000000900000000015300000004\n" +
                "<310000000432000000044300000008534554005a0000000549\n" +
                ">500000003700534554206170706c69636174696f6e5f6e616d65203d2027506f737467726553514c204a4442432044726976657227000000420000000c0000000000000000450000000900000000015300000004\n" +
                "<310000000432000000044300000008534554005a0000000549\n" +
                ">50000000cd0073656c65637420782c24312c24322c24332c24342c24352c24362c24372c24382c24392c2431302c2431312c2431322c2431332c2431342c2431352c2431362c2431372c2431382c2431392c2432302c2432312c2432322066726f6d206c6f6e675f73657175656e63652835290000260000001700000014000002bc000002bd0000001500000010000004130000041300000000000000000000001700000014000002bc000002bd000000150000001000000413000004130000043a000000000000045a000004a0420000012c0000001600010001000100010001000000000000000000000001000100010001000100000000000000010000000000000016000000040000000400000008000000000000007b0000000440adc28f000000083fe22c27a63736ce00000002005b00000004545255450000000568656c6c6f0000001dd0b3d180d183d0bfd0bfd0b020d182d183d180d0b8d181d182d0bed0b20000000e313937302d30312d3031202b30300000001a313937302d30382d32302031313a33333a32302e3033332b3030ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff0000001a313937302d30312d30312030303a30353a30302e3031312b30300000001a313937302d30312d30312030303a30383a32302e3032332b3030000044000000065000450000000900000000005300000004\n" +
                "<!!";
        assertHexScript(
                NetworkFacadeImpl.INSTANCE,
                script,
                getHexPgWireConfig()
        );
    }

    @Test
    public void testParseMessageTruncatedAtParameterCount() throws Exception {
        final String script = ">0000006900030000757365720078797a006461746162617365006e6162755f61707000636c69656e745f656e636f64696e67005554463800446174655374796c650049534f0054696d655a6f6e6500474d540065787472615f666c6f61745f64696769747300320000\n" +
                "<520000000800000003\n" +
                ">70000000076f6800\n" +
                "<520000000800000000530000001154696d655a6f6e6500474d5400530000001d6170706c69636174696f6e5f6e616d6500517565737444420053000000187365727665725f76657273696f6e0031312e33005300000019696e74656765725f6461746574696d6573006f6e005300000019636c69656e745f656e636f64696e670055544638005a0000000549\n" +
                ">5000000022005345542065787472615f666c6f61745f646967697473203d2033000000420000000c0000000000000000450000000900000000015300000004\n" +
                "<310000000432000000044300000008534554005a0000000549\n" +
                ">500000003700534554206170706c69636174696f6e5f6e616d65203d2027506f737467726553514c204a4442432044726976657227000000420000000c0000000000000000450000000900000000015300000004\n" +
                "<310000000432000000044300000008534554005a0000000549\n" +
                // II
                ">50000000740073656c65637420782c24312c24322c24332c24342c24352c24362c24372c24382c24392c2431302c2431312c2431322c2431332c2431342c2431352c2431362c2431372c2431382c2431392c2432302c2432312c2432322066726f6d206c6f6e675f73657175656e63652835290000160000001700000014000002bc000002bd0000001500000010000004130000041300000000000000000000001700000014000002bc000002bd000000150000001000000413000004130000043a000000000000045a000004a0420000012c0000001600010001000100010001000000000000000000000001000100010001000100000000000000010000000000000016000000040000000400000008000000000000007b0000000440adc28f000000083fe22c27a63736ce00000002005b00000004545255450000000568656c6c6f0000001dd0b3d180d183d0bfd0bfd0b020d182d183d180d0b8d181d182d0bed0b20000000e313937302d30312d3031202b30300000001a313937302d30382d32302031313a33333a32302e3033332b3030ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff0000001a313937302d30312d30312030303a30353a30302e3031312b30300000001a313937302d30312d30312030303a30383a32302e3032332b3030000044000000065000450000000900000000005300000004\n" +
                "<!!";
        assertHexScript(
                NetworkFacadeImpl.INSTANCE,
                script,
                getHexPgWireConfig()
        );
    }

    @Test
    public void testPreparedStatement() throws Exception {
        TestUtils.assertMemoryLeak(() -> {
            try (
                    final PGWireServer ignored = createPGServer(2);
                    final Connection connection = getConnection(false, false)
            ) {
                PreparedStatement statement = connection.prepareStatement("select 1,2,3 from long_sequence(1)");
                Statement statement1 = connection.createStatement();

                final String expected = "1[INTEGER],2[INTEGER],3[INTEGER]\n" +
                        "1,2,3\n";

                StringSink sink = new StringSink();
                for (int i = 0; i < 10; i++) {
                    sink.clear();
                    ResultSet rs = statement.executeQuery();

                    statement1.executeQuery("select 1 from long_sequence(2)");
                    assertResultSet(expected, sink, rs);
                    rs.close();
                }
            }
        });
    }

    @Test
    public void testPreparedStatementHex() throws Exception {
        assertHexScript(
                NetworkFacadeImpl.INSTANCE,
                ">0000006e00030000757365720078797a0064617461626173650071646200636c69656e745f656e636f64696e67005554463800446174655374796c650049534f0054696d655a6f6e65004575726f70652f4c6f6e646f6e0065787472615f666c6f61745f64696769747300320000\n" +
                        "<520000000800000003\n" +
                        ">70000000076f6800\n" +
                        "<520000000800000000530000001154696d655a6f6e6500474d5400530000001d6170706c69636174696f6e5f6e616d6500517565737444420053000000187365727665725f76657273696f6e0031312e33005300000019696e74656765725f6461746574696d6573006f6e005300000019636c69656e745f656e636f64696e670055544638005a0000000549\n" +
                        ">5000000022005345542065787472615f666c6f61745f646967697473203d2033000000420000000c0000000000000000450000000900000000015300000004\n" +
                        "<310000000432000000044300000008534554005a0000000549\n" +
                        ">500000003700534554206170706c69636174696f6e5f6e616d65203d2027506f737467726553514c204a4442432044726976657227000000420000000c0000000000000000450000000900000000015300000004\n" +
                        "<310000000432000000044300000008534554005a0000000549\n" +
                        ">500000002a0073656c65637420312c322c332066726f6d206c6f6e675f73657175656e6365283129000000420000000c000000000000000044000000065000450000000900000000005300000004\n" +
                        "<31000000043200000004540000004200033100000000000001000000170004ffffffff00003200000000000002000000170004ffffffff00003300000000000003000000170004ffffffff000044000000150003000000013100000001320000000133430000000d53454c4543542031005a0000000549\n" +
                        ">50000000260073656c65637420312066726f6d206c6f6e675f73657175656e6365283229000000420000000c000000000000000044000000065000450000000900000000005300000004\n" +
                        "<31000000043200000004540000001a00013100000000000001000000170004ffffffff0000440000000b00010000000131440000000b00010000000131430000000d53454c4543542032005a0000000549\n" +
                        ">500000002a0073656c65637420312c322c332066726f6d206c6f6e675f73657175656e6365283129000000420000000c000000000000000044000000065000450000000900000000005300000004\n" +
                        "<31000000043200000004540000004200033100000000000001000000170004ffffffff00003200000000000002000000170004ffffffff00003300000000000003000000170004ffffffff000044000000150003000000013100000001320000000133430000000d53454c4543542031005a0000000549\n" +
                        ">50000000260073656c65637420312066726f6d206c6f6e675f73657175656e6365283229000000420000000c000000000000000044000000065000450000000900000000005300000004\n" +
                        "<31000000043200000004540000001a00013100000000000001000000170004ffffffff0000440000000b00010000000131440000000b00010000000131430000000d53454c4543542032005a0000000549\n" +
                        ">500000002a0073656c65637420312c322c332066726f6d206c6f6e675f73657175656e6365283129000000420000000c000000000000000044000000065000450000000900000000005300000004\n" +
                        "<31000000043200000004540000004200033100000000000001000000170004ffffffff00003200000000000002000000170004ffffffff00003300000000000003000000170004ffffffff000044000000150003000000013100000001320000000133430000000d53454c4543542031005a0000000549\n" +
                        ">50000000260073656c65637420312066726f6d206c6f6e675f73657175656e6365283229000000420000000c000000000000000044000000065000450000000900000000005300000004\n" +
                        "<31000000043200000004540000001a00013100000000000001000000170004ffffffff0000440000000b00010000000131440000000b00010000000131430000000d53454c4543542032005a0000000549\n" +
                        ">500000002a0073656c65637420312c322c332066726f6d206c6f6e675f73657175656e6365283129000000420000000c000000000000000044000000065000450000000900000000005300000004\n" +
                        "<31000000043200000004540000004200033100000000000001000000170004ffffffff00003200000000000002000000170004ffffffff00003300000000000003000000170004ffffffff000044000000150003000000013100000001320000000133430000000d53454c4543542031005a0000000549\n" +
                        ">50000000260073656c65637420312066726f6d206c6f6e675f73657175656e6365283229000000420000000c000000000000000044000000065000450000000900000000005300000004\n" +
                        "<31000000043200000004540000001a00013100000000000001000000170004ffffffff0000440000000b00010000000131440000000b00010000000131430000000d53454c4543542032005a0000000549\n" +
                        ">500000002d535f310073656c65637420312c322c332066726f6d206c6f6e675f73657175656e6365283129000000420000000f00535f310000000000000044000000065000450000000900000000005300000004\n" +
                        "<31000000043200000004540000004200033100000000000001000000170004ffffffff00003200000000000002000000170004ffffffff00003300000000000003000000170004ffffffff000044000000150003000000013100000001320000000133430000000d53454c4543542031005a0000000549\n" +
                        ">50000000260073656c65637420312066726f6d206c6f6e675f73657175656e6365283229000000420000000c000000000000000044000000065000450000000900000000005300000004\n" +
                        "<31000000043200000004540000001a00013100000000000001000000170004ffffffff0000440000000b00010000000131440000000b00010000000131430000000d53454c4543542032005a0000000549\n" +
                        ">420000000f00535f3100000000000000450000000900000000005300000004\n" +
                        "<320000000444000000150003000000013100000001320000000133430000000d53454c4543542031005a0000000549\n" +
                        ">50000000260073656c65637420312066726f6d206c6f6e675f73657175656e6365283229000000420000000c000000000000000044000000065000450000000900000000005300000004\n" +
                        "<31000000043200000004540000001a00013100000000000001000000170004ffffffff0000440000000b00010000000131440000000b00010000000131430000000d53454c4543542032005a0000000549\n" +
                        ">420000000f00535f3100000000000000450000000900000000005300000004\n" +
                        "<320000000444000000150003000000013100000001320000000133430000000d53454c4543542031005a0000000549\n" +
                        ">50000000260073656c65637420312066726f6d206c6f6e675f73657175656e6365283229000000420000000c000000000000000044000000065000450000000900000000005300000004\n" +
                        "<31000000043200000004540000001a00013100000000000001000000170004ffffffff0000440000000b00010000000131440000000b00010000000131430000000d53454c4543542032005a0000000549\n" +
                        ">420000000f00535f3100000000000000450000000900000000005300000004\n" +
                        "<320000000444000000150003000000013100000001320000000133430000000d53454c4543542031005a0000000549\n" +
                        ">50000000260073656c65637420312066726f6d206c6f6e675f73657175656e6365283229000000420000000c000000000000000044000000065000450000000900000000005300000004\n" +
                        "<31000000043200000004540000001a00013100000000000001000000170004ffffffff0000440000000b00010000000131440000000b00010000000131430000000d53454c4543542032005a0000000549\n" +
                        ">420000000f00535f3100000000000000450000000900000000005300000004\n" +
                        "<320000000444000000150003000000013100000001320000000133430000000d53454c4543542031005a0000000549\n" +
                        ">50000000260073656c65637420312066726f6d206c6f6e675f73657175656e6365283229000000420000000c000000000000000044000000065000450000000900000000005300000004\n" +
                        "<31000000043200000004540000001a00013100000000000001000000170004ffffffff0000440000000b00010000000131440000000b00010000000131430000000d53454c4543542032005a0000000549\n" +
                        ">420000000f00535f3100000000000000450000000900000000005300000004\n" +
                        "<320000000444000000150003000000013100000001320000000133430000000d53454c4543542031005a0000000549\n" +
                        ">50000000260073656c65637420312066726f6d206c6f6e675f73657175656e6365283229000000420000000c000000000000000044000000065000450000000900000000005300000004\n" +
                        "<31000000043200000004540000001a00013100000000000001000000170004ffffffff0000440000000b00010000000131440000000b00010000000131430000000d53454c4543542032005a0000000549\n" +
                        ">5800000004\n",
                getHexPgWireConfig()
        );
    }

    @Test
    public void testPreparedStatementInsertSelectNullDesignatedColumn() throws Exception {
        TestUtils.assertMemoryLeak(() -> {
            try (
                    final PGWireServer ignored = createPGServer(2);
                    final Connection connection = getConnection(false, false);
                    final Statement statement = connection.createStatement();
                    final PreparedStatement insert = connection.prepareStatement("insert into tab(ts, value) values(?, ?)")
            ) {
                statement.execute("create table tab(ts timestamp, value double) timestamp(ts) partition by MONTH");
                // Null is not allowed
                insert.setNull(1, Types.NULL);
                insert.setNull(2, Types.NULL);
                try {
                    insert.executeUpdate();
                    fail("cannot insert null when the column is designated");
                } catch (PSQLException expected) {
                    Assert.assertEquals("ERROR: timestamp before 1970-01-01 is not allowed", expected.getMessage());
                }
                // Insert a dud
                insert.setString(1, "1970-01-01 00:11:22.334455");
                insert.setNull(2, Types.NULL);
                insert.executeUpdate();
                try (ResultSet rs = statement.executeQuery("select null, ts, value from tab where value = null")) {
                    StringSink sink = new StringSink();
                    String expected = "null[VARCHAR],ts[TIMESTAMP],value[DOUBLE]\n" +
                            "null,1970-01-01 00:11:22.334455,null\n";
                    assertResultSet(expected, sink, rs);
                }
                statement.execute("drop table tab");
            }
        });
    }

    @Test
    public void testPreparedStatementInsertSelectNullNoDesignatedColumn() throws Exception {
        TestUtils.assertMemoryLeak(() -> {
            try (
                    final PGWireServer ignored = createPGServer(2);
                    final Connection connection = getConnection(false, false);
                    final Statement statement = connection.createStatement()
            ) {
                statement.execute("create table tab(ts timestamp, value double)");
                try (PreparedStatement insert = connection.prepareStatement("insert into tab(ts, value) values(?, ?)")) {
                    insert.setNull(1, Types.NULL);
                    insert.setNull(2, Types.NULL);
                    insert.executeUpdate();
                }
                try (ResultSet rs = statement.executeQuery("select null, ts, value from tab where value = null")) {
                    StringSink sink = new StringSink();
                    String expected = "null[VARCHAR],ts[TIMESTAMP],value[DOUBLE]\n" +
                            "null,null,null\n";
                    assertResultSet(expected, sink, rs);
                }
                statement.execute("drop table tab");
            }
        });
    }

    @Test
    public void testPreparedStatementParamBadByte() throws Exception {
        assertHexScript(
                NetworkFacadeImpl.INSTANCE,
                ">0000006b00030000757365720061646d696e006461746162617365006e6162755f61707000636c69656e745f656e636f64696e67005554463800446174655374796c650049534f0054696d655a6f6e6500474d540065787472615f666c6f61745f64696769747300320000\n" +
                        "<520000000800000003\n" +
                        ">700000000a717565737400\n" +
                        "<520000000800000000530000001154696d655a6f6e6500474d5400530000001d6170706c69636174696f6e5f6e616d6500517565737444420053000000187365727665725f76657273696f6e0031312e33005300000019696e74656765725f6461746574696d6573006f6e005300000019636c69656e745f656e636f64696e670055544638005a0000000549\n" +
                        ">5000000022005345542065787472615f666c6f61745f646967697473203d2033000000420000000c0000000000000000450000000900000000015300000004\n" +
                        "<310000000432000000044300000008534554005a0000000549\n" +
                        ">500000003700534554206170706c69636174696f6e5f6e616d65203d2027506f737467726553514c204a4442432044726976657227000000420000000c0000000000000000450000000900000000015300000004\n" +
                        "<310000000432000000044300000008534554005a0000000549\n" +
                        ">50000000cd0073656c65637420782c24312c24322c24332c24342c24352c24362c24372c24382c24392c2431302c2431312c2431322c2431332c2431342c2431352c2431362c2431372c2431382c2431392c2432302c2432312c2432322066726f6d206c6f6e675f73657175656e63652835290000160000001700000014000002bd000002bd0000001500000010000004130000041300000000000000000000001700000014000002bc000002bd000000150000001000000413000004130000043a000000000000045a000004a04200000123000000160000000000000000000000000000000000000000000000000000000000000000000000000000000000000000001600000001340000000331323300000004352e343300000007302e353637383900000002993100000004545255450000000568656c6c6f0000001dd0b3d180d183d0bfd0bfd0b020d182d183d180d0b8d181d182d0bed0b20000000e313937302d30312d3031202b30300000001a313937302d30382d32302031313a33333a32302e3033332b3030ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff0000001a313937302d30312d30312030303a30353a30302e3031312b30300000001a313937302d30312d30312030303a30383a32302e3032332b3030000044000000065000450000000900000000005300000004\n" +
                        "<!!",
                new DefaultPGWireConfiguration()
        );
    }

    @Test
    public void testPreparedStatementParamBadInt() throws Exception {
        assertHexScript(
                NetworkFacadeImpl.INSTANCE,
                ">0000006b00030000757365720061646d696e006461746162617365006e6162755f61707000636c69656e745f656e636f64696e67005554463800446174655374796c650049534f0054696d655a6f6e6500474d540065787472615f666c6f61745f64696769747300320000\n" +
                        "<520000000800000003\n" +
                        ">700000000a717565737400\n" +
                        "<520000000800000000530000001154696d655a6f6e6500474d5400530000001d6170706c69636174696f6e5f6e616d6500517565737444420053000000187365727665725f76657273696f6e0031312e33005300000019696e74656765725f6461746574696d6573006f6e005300000019636c69656e745f656e636f64696e670055544638005a0000000549\n" +
                        ">5000000022005345542065787472615f666c6f61745f646967697473203d2033000000420000000c0000000000000000450000000900000000015300000004\n" +
                        "<310000000432000000044300000008534554005a0000000549\n" +
                        ">500000003700534554206170706c69636174696f6e5f6e616d65203d2027506f737467726553514c204a4442432044726976657227000000420000000c0000000000000000450000000900000000015300000004\n" +
                        "<310000000432000000044300000008534554005a0000000549\n" +
                        ">50000000cd0073656c65637420782c24312c24322c24332c24342c24352c24362c24372c24382c24392c2431302c2431312c2431322c2431332c2431342c2431352c2431362c2431372c2431382c2431392c2432302c2432312c2432322066726f6d206c6f6e675f73657175656e63652835290000160000001700000014000002bd000002bd0000001500000010000004130000041300000000000000000000001700000014000002bc000002bd000000150000001000000413000004130000043a000000000000045a000004a04200000123000000160000000000000000000000000000000000000000000000000000000000000000000000000000000000000000001600000001fc0000000331323300000004352e343300000007302e353637383900000002393100000004545255450000000568656c6c6f0000001dd0b3d180d183d0bfd0bfd0b020d182d183d180d0b8d181d182d0bed0b20000000e313937302d30312d3031202b30300000001a313937302d30382d32302031313a33333a32302e3033332b3030ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff0000001a313937302d30312d30312030303a30353a30302e3031312b30300000001a313937302d30312d30312030303a30383a32302e3032332b3030000044000000065000450000000900000000005300000004\n" +
                        "<!!",
                new DefaultPGWireConfiguration()
        );
    }

    @Test
    public void testPreparedStatementParamBadLong() throws Exception {
        assertHexScript(
                NetworkFacadeImpl.INSTANCE,
                ">0000006b00030000757365720061646d696e006461746162617365006e6162755f61707000636c69656e745f656e636f64696e67005554463800446174655374796c650049534f0054696d655a6f6e6500474d540065787472615f666c6f61745f64696769747300320000\n" +
                        "<520000000800000003\n" +
                        ">700000000a717565737400\n" +
                        "<520000000800000000530000001154696d655a6f6e6500474d5400530000001d6170706c69636174696f6e5f6e616d6500517565737444420053000000187365727665725f76657273696f6e0031312e33005300000019696e74656765725f6461746574696d6573006f6e005300000019636c69656e745f656e636f64696e670055544638005a0000000549\n" +
                        ">5000000022005345542065787472615f666c6f61745f646967697473203d2033000000420000000c0000000000000000450000000900000000015300000004\n" +
                        "<310000000432000000044300000008534554005a0000000549\n" +
                        ">500000003700534554206170706c69636174696f6e5f6e616d65203d2027506f737467726553514c204a4442432044726976657227000000420000000c0000000000000000450000000900000000015300000004\n" +
                        "<310000000432000000044300000008534554005a0000000549\n" +
                        ">50000000cd0073656c65637420782c24312c24322c24332c24342c24352c24362c24372c24382c24392c2431302c2431312c2431322c2431332c2431342c2431352c2431362c2431372c2431382c2431392c2432302c2432312c2432322066726f6d206c6f6e675f73657175656e63652835290000160000001700000014000002bd000002bd0000001500000010000004130000041300000000000000000000001700000014000002bc000002bd000000150000001000000413000004130000043a000000000000045a000004a04200000123000000160000000000000000000000000000000000000000000000000000000000000000000000000000000000000000001600000001340000000331b23300000004352e343300000007302e353637383900000002393100000004545255450000000568656c6c6f0000001dd0b3d180d183d0bfd0bfd0b020d182d183d180d0b8d181d182d0bed0b20000000e313937302d30312d3031202b30300000001a313937302d30382d32302031313a33333a32302e3033332b3030ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff0000001a313937302d30312d30312030303a30353a30302e3031312b30300000001a313937302d30312d30312030303a30383a32302e3032332b3030000044000000065000450000000900000000005300000004\n" +
                        "<!!",
                new DefaultPGWireConfiguration()
        );
    }

    @Test
    public void testPreparedStatementParamValueLengthOverflow() throws Exception {
        assertHexScript(
                NetworkFacadeImpl.INSTANCE,
                ">0000006b00030000757365720061646d696e006461746162617365006e6162755f61707000636c69656e745f656e636f64696e67005554463800446174655374796c650049534f0054696d655a6f6e6500474d540065787472615f666c6f61745f64696769747300320000\n" +
                        "<520000000800000003\n" +
                        ">700000000a717565737400\n" +
                        "<520000000800000000530000001154696d655a6f6e6500474d5400530000001d6170706c69636174696f6e5f6e616d6500517565737444420053000000187365727665725f76657273696f6e0031312e33005300000019696e74656765725f6461746574696d6573006f6e005300000019636c69656e745f656e636f64696e670055544638005a0000000549\n" +
                        ">5000000022005345542065787472615f666c6f61745f646967697473203d2033000000420000000c0000000000000000450000000900000000015300000004\n" +
                        "<310000000432000000044300000008534554005a0000000549\n" +
                        ">500000003700534554206170706c69636174696f6e5f6e616d65203d2027506f737467726553514c204a4442432044726976657227000000420000000c0000000000000000450000000900000000015300000004\n" +
                        "<310000000432000000044300000008534554005a0000000549\n" +
                        ">50000000cd0073656c65637420782c24312c24322c24332c24342c24352c24362c24372c24382c24392c2431302c2431312c2431322c2431332c2431342c2431352c2431362c2431372c2431382c2431392c2432302c2432312c2432322066726f6d206c6f6e675f73657175656e63652835290000160000001700000014000002bd000002bd0000001500000010000004130000041300000000000000000000001700000014000002bc000002bd000000150000001000000413000004130000043a000000000000045a000004a04200000123000000160000000000000000000000000000000000000000000000000000000000000000000000000000000000000000001600000001340000333331b23300000004352e343300000007302e353637383900000002393100000004545255450000000568656c6c6f0000001dd0b3d180d183d0bfd0bfd0b020d182d183d180d0b8d181d182d0bed0b20000000e313937302d30312d3031202b30300000001a313937302d30382d32302031313a33333a32302e3033332b3030ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff0000001a313937302d30312d30312030303a30353a30302e3031312b30300000001a313937302d30312d30312030303a30383a32302e3032332b3030000044000000065000450000000900000000005300000004\n" +
                        "<!!",
                new DefaultPGWireConfiguration()
        );
    }

    @Test
    public void testPreparedStatementParams() throws Exception {
        assertMemoryLeak(() -> {
            final PGWireConfiguration conf = new DefaultPGWireConfiguration() {
                @Override
                public int[] getWorkerAffinity() {
                    return TestUtils.getWorkerAffinity(getWorkerCount());
                }

                @Override
                public int getWorkerCount() {
                    return 4;
                }
            };

            try (final PGWireServer ignored = PGWireServer.create(
                    conf,
                    null,
                    LOG,
                    engine,
                    compiler.getFunctionFactoryCache(),
                    snapshotAgent,
                    metrics
            )) {
                Properties properties = new Properties();
                properties.setProperty("user", "admin");
                properties.setProperty("password", "quest");
                properties.setProperty("sslmode", "disable");
                properties.setProperty("binaryTransfer", "true");
                TimeZone.setDefault(TimeZone.getTimeZone("EDT"));
                final Connection connection = DriverManager.getConnection("jdbc:postgresql://127.0.0.1:8812/qdb", properties);
                PreparedStatement statement = connection.prepareStatement("select x,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,? from long_sequence(5)");
                statement.setInt(1, 4);
                statement.setLong(2, 123L);
                statement.setFloat(3, 5.43f);
                statement.setDouble(4, 0.56789);
                statement.setByte(5, (byte) 91);
                statement.setBoolean(6, true);
                statement.setString(7, "hello");
                // this is to test UTF8 behaviour
                statement.setString(8, "группа туристов");
                statement.setDate(9, new Date(100L));
                statement.setTimestamp(10, new Timestamp(20000000033L));

                // nulls
                statement.setNull(11, Types.INTEGER);
                statement.setNull(12, Types.BIGINT);
                statement.setNull(13, Types.REAL);
                statement.setNull(14, Types.DOUBLE);
                statement.setNull(15, Types.SMALLINT);
                statement.setNull(16, Types.BOOLEAN);
                statement.setNull(17, Types.VARCHAR);
                statement.setString(18, null);
                statement.setNull(19, Types.DATE);

                // when someone uses PostgreSQL's type extensions, which alter driver behaviour
                // we should handle this gracefully
                statement.setTimestamp(20, new PGTimestamp(300011));
                statement.setTimestamp(21, new PGTimestamp(500023, new GregorianCalendar()));
                statement.setTimestamp(22, null);


                final String expected = "x[BIGINT],$1[VARCHAR],$2[VARCHAR],$3[VARCHAR],$4[VARCHAR],$5[VARCHAR],$6[VARCHAR],$7[VARCHAR],$8[VARCHAR],$9[VARCHAR],$10[VARCHAR],$11[VARCHAR],$12[VARCHAR],$13[VARCHAR],$14[VARCHAR],$15[VARCHAR],$16[VARCHAR],$17[VARCHAR],$18[VARCHAR],$19[VARCHAR],$20[VARCHAR],$21[VARCHAR],$22[VARCHAR]\n" +
                        "1,4,123,5.4300,0.56789,91,TRUE,hello,группа туристов,1970-01-01 +00,1970-08-20 11:33:20.033+00,null,null,null,null,null,null,null,null,null,1970-01-01 00:05:00.011+00,1970-01-01 00:08:20.023+00,null\n" +
                        "2,4,123,5.4300,0.56789,91,TRUE,hello,группа туристов,1970-01-01 +00,1970-08-20 11:33:20.033+00,null,null,null,null,null,null,null,null,null,1970-01-01 00:05:00.011+00,1970-01-01 00:08:20.023+00,null\n" +
                        "3,4,123,5.4300,0.56789,91,TRUE,hello,группа туристов,1970-01-01 +00,1970-08-20 11:33:20.033+00,null,null,null,null,null,null,null,null,null,1970-01-01 00:05:00.011+00,1970-01-01 00:08:20.023+00,null\n" +
                        "4,4,123,5.4300,0.56789,91,TRUE,hello,группа туристов,1970-01-01 +00,1970-08-20 11:33:20.033+00,null,null,null,null,null,null,null,null,null,1970-01-01 00:05:00.011+00,1970-01-01 00:08:20.023+00,null\n" +
                        "5,4,123,5.4300,0.56789,91,TRUE,hello,группа туристов,1970-01-01 +00,1970-08-20 11:33:20.033+00,null,null,null,null,null,null,null,null,null,1970-01-01 00:05:00.011+00,1970-01-01 00:08:20.023+00,null\n";

                StringSink sink = new StringSink();
                for (int i = 0; i < 10000; i++) {
                    sink.clear();
                    ResultSet rs = statement.executeQuery();
                    assertResultSet(expected, sink, rs);
                    rs.close();
                }
                connection.close();
            }
        });
    }

    @Test
    public void testPreparedStatementSelectNull() throws Exception {
        TestUtils.assertMemoryLeak(() -> {
            try (
                    final PGWireServer ignored = createPGServer(2);
                    final Connection connection = getConnection(false, false);
                    final PreparedStatement statement = connection.prepareStatement("select ? from long_sequence(1)")
            ) {
                StringSink sink = new StringSink();
                statement.setNull(1, Types.NULL);
                try (ResultSet rs = statement.executeQuery()) {
                    assertResultSet("$1[VARCHAR]\nnull\n", sink, rs);
                }
                statement.setNull(1, Types.VARCHAR);
                try (ResultSet rs = statement.executeQuery()) {
                    sink.clear();
                    assertResultSet("$1[VARCHAR]\nnull\n", sink, rs);
                }
            }
        });
    }

    @Test
    public void testPreparedStatementTextParams() throws Exception {
        TestUtils.assertMemoryLeak(() -> {
            try (
                    final PGWireServer ignored = createPGServer(2);
                    final Connection connection = getConnection(false, false)
            ) {

                PreparedStatement statement = connection.prepareStatement("select x,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,? from long_sequence(5)");
                statement.setInt(1, 4);
                statement.setLong(2, 123L);
                statement.setFloat(3, 5.43f);
                statement.setDouble(4, 0.56789);
                statement.setByte(5, (byte) 91);
                statement.setBoolean(6, true);
                statement.setString(7, "hello");
                // this is to test UTF8 behaviour
                statement.setString(8, "группа туристов");
                statement.setDate(9, new Date(100L));
                statement.setTimestamp(10, new Timestamp(20000000033L));

                // nulls
                statement.setNull(11, Types.INTEGER);
                statement.setNull(12, Types.BIGINT);
                statement.setNull(13, Types.REAL);
                statement.setNull(14, Types.DOUBLE);
                statement.setNull(15, Types.SMALLINT);
                statement.setNull(16, Types.BOOLEAN);
                statement.setNull(17, Types.VARCHAR);
                statement.setString(18, null);
                statement.setNull(19, Types.DATE);
//                statement.setNull(20, Types.TIMESTAMP);

                // when someone uses PostgreSQL's type extensions, which alter driver behaviour
                // we should handle this gracefully

                statement.setTimestamp(20, new PGTimestamp(300011));
                statement.setTimestamp(21, new PGTimestamp(500023, new GregorianCalendar()));

                // Bind variables are out of context here, hence they are all STRING/VARCHAR
                // this is the reason why we show PG wire Dates verbatim. Even though PG wire does eventually tell us
                // that this data is typed (sometimes), their requirement to describe SQL statement before
                // they send us bind variable types and values forces us to stick with STRING.
                final String expected = "x[BIGINT],$1[VARCHAR],$2[VARCHAR],$3[VARCHAR],$4[VARCHAR],$5[VARCHAR],$6[VARCHAR],$7[VARCHAR],$8[VARCHAR],$9[VARCHAR],$10[VARCHAR],$11[VARCHAR],$12[VARCHAR],$13[VARCHAR],$14[VARCHAR],$15[VARCHAR],$16[VARCHAR],$17[VARCHAR],$18[VARCHAR],$19[VARCHAR],$20[VARCHAR],$21[VARCHAR]\n" +
                        "1,4,123,5.43,0.56789,91,TRUE,hello,группа туристов,1970-01-01 +00,1970-08-20 11:33:20.033+00,null,null,null,null,null,null,null,null,null,1970-01-01 00:05:00.011+00,1970-01-01 00:08:20.023+00\n" +
                        "2,4,123,5.43,0.56789,91,TRUE,hello,группа туристов,1970-01-01 +00,1970-08-20 11:33:20.033+00,null,null,null,null,null,null,null,null,null,1970-01-01 00:05:00.011+00,1970-01-01 00:08:20.023+00\n" +
                        "3,4,123,5.43,0.56789,91,TRUE,hello,группа туристов,1970-01-01 +00,1970-08-20 11:33:20.033+00,null,null,null,null,null,null,null,null,null,1970-01-01 00:05:00.011+00,1970-01-01 00:08:20.023+00\n" +
                        "4,4,123,5.43,0.56789,91,TRUE,hello,группа туристов,1970-01-01 +00,1970-08-20 11:33:20.033+00,null,null,null,null,null,null,null,null,null,1970-01-01 00:05:00.011+00,1970-01-01 00:08:20.023+00\n" +
                        "5,4,123,5.43,0.56789,91,TRUE,hello,группа туристов,1970-01-01 +00,1970-08-20 11:33:20.033+00,null,null,null,null,null,null,null,null,null,1970-01-01 00:05:00.011+00,1970-01-01 00:08:20.023+00\n";

                StringSink sink = new StringSink();
                for (int i = 0; i < 10_000; i++) {
                    sink.clear();
                    ResultSet rs = statement.executeQuery();
                    assertResultSet(expected, sink, rs);
                    rs.close();
                }
            }
        });
    }

    @Test
    public void testPreparedStatementWithBindVariablesOnDifferentConnection() throws Exception {
        TestUtils.assertMemoryLeak(() -> {
            try (final PGWireServer ignored = createPGServer(1)) {
                try (final Connection connection = getConnection(false, false)) {
                    try (PreparedStatement statement = connection.prepareStatement(createDatesTblStmt)) {
                        statement.execute();
                    }
                    queryTimestampsInRange(connection);
                }

                try (final Connection connection = getConnection(false, false)) {
                    queryTimestampsInRange(connection);
                    try (PreparedStatement statement = connection.prepareStatement("drop table xts")) {
                        statement.execute();
                    }
                }

            }
        });
    }

    @Test
    public void testPreparedStatementWithBindVariablesSetWrongOnDifferentConnection() throws Exception {
        TestUtils.assertMemoryLeak(() -> {
            try (final PGWireServer ignored = createPGServer(1)) {
                try (final Connection connection = getConnection(false, false)) {
                    try (PreparedStatement statement = connection.prepareStatement(createDatesTblStmt)) {
                        statement.execute();
                    }
                    queryTimestampsInRange(connection);
                }

                boolean caught = false;
                try (final Connection connection = getConnection(false, false)) {
                    try (PreparedStatement statement = connection.prepareStatement("select ts FROM xts WHERE ts <= dateadd('d', -1, ?) and ts >= dateadd('d', -2, ?)")) {
                        sink.clear();
                        statement.setString(1, "abcd");
                        statement.setString(2, "abdc");
                        statement.executeQuery();
                    } catch (PSQLException ex) {
                        caught = true;
                        Assert.assertEquals("ERROR: could not parse [value='abcd', as=TIMESTAMP, index=0]\n  Position: 1", ex.getMessage());
                    }
                }

                try (final Connection connection = getConnection(false, false);
                     PreparedStatement statement = connection.prepareStatement("drop table xts")) {
                    statement.execute();
                }
                Assert.assertTrue("Exception is not thrown", caught);
            }
        });
    }

    @Test
    public void testPreparedStatementWithBindVariablesTimestampRange() throws Exception {
        TestUtils.assertMemoryLeak(() -> {
            try (
                    final PGWireServer ignored = createPGServer(1);
                    final Connection connection = getConnection(false, false)
            ) {
                try (PreparedStatement statement = connection.prepareStatement(createDatesTblStmt)) {
                    statement.execute();
                }

                queryTimestampsInRange(connection);

                try (PreparedStatement statement = connection.prepareStatement("drop table xts")) {
                    statement.execute();
                }
            }
        });
    }

    @Test
    public void testPreparedStatementWithNowFunction() throws Exception {
        TestUtils.assertMemoryLeak(() -> {
            try (final PGWireServer ignored = createPGServer(1)) {
                try (final Connection connection = getConnection(false, false)) {
                    try (PreparedStatement statement = connection.prepareStatement(
                            "create table xts (ts timestamp) timestamp(ts)")) {
                        statement.execute();
                    }

                    try (PreparedStatement statement = connection.prepareStatement("INSERT INTO xts VALUES(now())")) {
                        for (currentMicros = 0; currentMicros < 200 * Timestamps.HOUR_MICROS; currentMicros += Timestamps.HOUR_MICROS) {
                            statement.execute();
                        }
                    }

                    queryTimestampsInRange(connection);

                    try (PreparedStatement statement = connection.prepareStatement("drop table xts")) {
                        statement.execute();
                    }
                } finally {
                    currentMicros = -1;
                }
            }
        });
    }

    @Test
    public void testPythonInsertDateSelectHex() throws Exception {
        String script = ">0000000804d2162f\n" +
                "<4e\n" +
                ">0000002100030000757365720061646d696e006461746162617365007164620000\n" +
                "<520000000800000003\n" +
                ">700000000a717565737400\n" +
                "<520000000800000000530000001154696d655a6f6e6500474d5400530000001d6170706c69636174696f6e5f6e616d6500517565737444420053000000187365727665725f76657273696f6e0031312e33005300000019696e74656765725f6461746574696d6573006f6e005300000019636c69656e745f656e636f64696e670055544638005a0000000549\n" +
                ">510000001b53455420646174657374796c6520544f202749534f2700\n" +
                "<4300000008534554005a0000000549\n" +
                ">510000000a424547494e00\n" +
                "<430000000a424547494e005a0000000554\n" +
                ">5100000067435245415445205441424c45204946204e4f542045584953545320747261646573202874732054494d455354414d502c206461746520444154452c206e616d6520535452494e472c2076616c756520494e54292074696d657374616d70287473293b00\n" +
                "<43000000074f4b005a0000000554\n" +
                ">51000000930a2020202020202020494e5345525420494e544f207472616465730a202020202020202056414c554553202827323032312d30312d32365431333a34333a34302e323230303839273a3a74696d657374616d702c2027323032312d30312d3236273a3a646174652c2027707974686f6e20707265702073746174656d656e74272c2030293b0a202020202020202000\n" +
                "<430000000f494e5345525420302031005a0000000554\n" +
                ">51000000930a2020202020202020494e5345525420494e544f207472616465730a202020202020202056414c554553202827323032312d30312d32365431333a34333a34302e323331303238273a3a74696d657374616d702c2027323032312d30312d3236273a3a646174652c2027707974686f6e20707265702073746174656d656e74272c2031293b0a202020202020202000\n" +
                "<430000000f494e5345525420302031005a0000000554\n" +
                ">51000000930a2020202020202020494e5345525420494e544f207472616465730a202020202020202056414c554553202827323032312d30312d32365431333a34333a34302e323332303238273a3a74696d657374616d702c2027323032312d30312d3236273a3a646174652c2027707974686f6e20707265702073746174656d656e74272c2032293b0a202020202020202000\n" +
                "<430000000f494e5345525420302031005a0000000554\n" +
                ">51000000930a2020202020202020494e5345525420494e544f207472616465730a202020202020202056414c554553202827323032312d30312d32365431333a34333a34302e323332303238273a3a74696d657374616d702c2027323032312d30312d3236273a3a646174652c2027707974686f6e20707265702073746174656d656e74272c2033293b0a202020202020202000\n" +
                "<430000000f494e5345525420302031005a0000000554\n" +
                ">51000000930a2020202020202020494e5345525420494e544f207472616465730a202020202020202056414c554553202827323032312d30312d32365431333a34333a34302e323333303238273a3a74696d657374616d702c2027323032312d30312d3236273a3a646174652c2027707974686f6e20707265702073746174656d656e74272c2034293b0a202020202020202000\n" +
                "<430000000f494e5345525420302031005a0000000554\n" +
                ">51000000930a2020202020202020494e5345525420494e544f207472616465730a202020202020202056414c554553202827323032312d30312d32365431333a34333a34302e323333303238273a3a74696d657374616d702c2027323032312d30312d3236273a3a646174652c2027707974686f6e20707265702073746174656d656e74272c2035293b0a202020202020202000\n" +
                "<430000000f494e5345525420302031005a0000000554\n" +
                ">51000000930a2020202020202020494e5345525420494e544f207472616465730a202020202020202056414c554553202827323032312d30312d32365431333a34333a34302e323334303238273a3a74696d657374616d702c2027323032312d30312d3236273a3a646174652c2027707974686f6e20707265702073746174656d656e74272c2036293b0a202020202020202000\n" +
                "<430000000f494e5345525420302031005a0000000554\n" +
                ">51000000930a2020202020202020494e5345525420494e544f207472616465730a202020202020202056414c554553202827323032312d30312d32365431333a34333a34302e323334303238273a3a74696d657374616d702c2027323032312d30312d3236273a3a646174652c2027707974686f6e20707265702073746174656d656e74272c2037293b0a202020202020202000\n" +
                "<430000000f494e5345525420302031005a0000000554\n" +
                ">51000000930a2020202020202020494e5345525420494e544f207472616465730a202020202020202056414c554553202827323032312d30312d32365431333a34333a34302e323335303738273a3a74696d657374616d702c2027323032312d30312d3236273a3a646174652c2027707974686f6e20707265702073746174656d656e74272c2038293b0a202020202020202000\n" +
                "<430000000f494e5345525420302031005a0000000554\n" +
                ">51000000930a2020202020202020494e5345525420494e544f207472616465730a202020202020202056414c554553202827323032312d30312d32365431333a34333a34302e323335303738273a3a74696d657374616d702c2027323032312d30312d3236273a3a646174652c2027707974686f6e20707265702073746174656d656e74272c2039293b0a202020202020202000\n" +
                "<430000000f494e5345525420302031005a0000000554\n" +
                ">510000000b434f4d4d495400\n" +
                "<430000000b434f4d4d4954005a0000000549\n" +
                ">510000000a424547494e00\n" +
                "<430000000a424547494e005a0000000554\n" +
                ">510000001a53454c454354202a2046524f4d207472616465733b00\n" +
                "<540000006100047473000000000000010000045a0008ffffffff000064617465000000000000020000045a0008ffffffff00006e616d650000000000000300000413ffffffffffff000076616c756500000000000004000000170004ffffffff0000440000005b00040000001a323032312d30312d32362031333a34333a34302e32323030383900000015323032312d30312d32362030303a30303a30302e3000000015707974686f6e20707265702073746174656d656e740000000130440000005b00040000001a323032312d30312d32362031333a34333a34302e32333130323800000015323032312d30312d32362030303a30303a30302e3000000015707974686f6e20707265702073746174656d656e740000000131440000005b00040000001a323032312d30312d32362031333a34333a34302e32333230323800000015323032312d30312d32362030303a30303a30302e3000000015707974686f6e20707265702073746174656d656e740000000132440000005b00040000001a323032312d30312d32362031333a34333a34302e32333230323800000015323032312d30312d32362030303a30303a30302e3000000015707974686f6e20707265702073746174656d656e740000000133440000005b00040000001a323032312d30312d32362031333a34333a34302e32333330323800000015323032312d30312d32362030303a30303a30302e3000000015707974686f6e20707265702073746174656d656e740000000134440000005b00040000001a323032312d30312d32362031333a34333a34302e32333330323800000015323032312d30312d32362030303a30303a30302e3000000015707974686f6e20707265702073746174656d656e740000000135440000005b00040000001a323032312d30312d32362031333a34333a34302e32333430323800000015323032312d30312d32362030303a30303a30302e3000000015707974686f6e20707265702073746174656d656e740000000136440000005b00040000001a323032312d30312d32362031333a34333a34302e32333430323800000015323032312d30312d32362030303a30303a30302e3000000015707974686f6e20707265702073746174656d656e740000000137440000005b00040000001a323032312d30312d32362031333a34333a34302e32333530373800000015323032312d30312d32362030303a30303a30302e3000000015707974686f6e20707265702073746174656d656e740000000138440000005b00040000001a323032312d30312d32362031333a34333a34302e32333530373800000015323032312d30312d32362030303a30303a30302e3000000015707974686f6e20707265702073746174656d656e740000000139430000000e53454c454354203130005a0000000554\n" +
                ">5800000004\n";
        assertHexScript(NetworkFacadeImpl.INSTANCE,
                script,
                new DefaultPGWireConfiguration()
        );
    }

    @Test
    public void testPythonInsertSelectHex() throws Exception {
        String script = ">0000000804d2162f\n" +
                "<4e\n" +
                ">0000002100030000757365720061646d696e006461746162617365007164620000\n" +
                "<520000000800000003\n" +
                ">700000000a717565737400\n" +
                "<520000000800000000530000001154696d655a6f6e6500474d5400530000001d6170706c69636174696f6e5f6e616d6500517565737444420053000000187365727665725f76657273696f6e0031312e33005300000019696e74656765725f6461746574696d6573006f6e005300000019636c69656e745f656e636f64696e670055544638005a0000000549\n" +
                ">510000001b53455420646174657374796c6520544f202749534f2700\n" +
                "<4300000008534554005a0000000549\n" +
                ">510000000a424547494e00\n" +
                "<430000000a424547494e005a0000000554\n" +
                ">510000005c435245415445205441424c45204946204e4f542045584953545320747261646573202874732054494d455354414d502c206e616d6520535452494e472c2076616c756520494e54292074696d657374616d70287473293b00\n" +
                "<43000000074f4b005a0000000554\n" +
                ">51000000840a2020202020202020494e5345525420494e544f20747261646573202874732c206e616d652c2076616c7565290a202020202020202056414c554553202827323032312d30312d32345430353a30313a31312e383335383439273a3a74696d657374616d702c202770792d616263272c20313233293b0a202020202020202000\n" +
                "<430000000f494e5345525420302031005a0000000554\n" +
                ">51000000840a2020202020202020494e5345525420494e544f20747261646573202874732c206e616d652c2076616c7565290a202020202020202056414c554553202827323032312d30312d32345430353a30313a31312e383431343837273a3a74696d657374616d702c202770792d616263272c20313233293b0a202020202020202000\n" +
                "<430000000f494e5345525420302031005a0000000554\n" +
                ">51000000840a2020202020202020494e5345525420494e544f20747261646573202874732c206e616d652c2076616c7565290a202020202020202056414c554553202827323032312d30312d32345430353a30313a31312e383432313035273a3a74696d657374616d702c202770792d616263272c20313233293b0a202020202020202000\n" +
                "<430000000f494e5345525420302031005a0000000554\n" +
                ">51000000840a2020202020202020494e5345525420494e544f20747261646573202874732c206e616d652c2076616c7565290a202020202020202056414c554553202827323032312d30312d32345430353a30313a31312e383432353134273a3a74696d657374616d702c202770792d616263272c20313233293b0a202020202020202000\n" +
                "<430000000f494e5345525420302031005a0000000554\n" +
                ">51000000840a2020202020202020494e5345525420494e544f20747261646573202874732c206e616d652c2076616c7565290a202020202020202056414c554553202827323032312d30312d32345430353a30313a31312e383432393439273a3a74696d657374616d702c202770792d616263272c20313233293b0a202020202020202000\n" +
                "<430000000f494e5345525420302031005a0000000554\n" +
                ">51000000840a2020202020202020494e5345525420494e544f20747261646573202874732c206e616d652c2076616c7565290a202020202020202056414c554553202827323032312d30312d32345430353a30313a31312e383433333739273a3a74696d657374616d702c202770792d616263272c20313233293b0a202020202020202000\n" +
                "<430000000f494e5345525420302031005a0000000554\n" +
                ">51000000840a2020202020202020494e5345525420494e544f20747261646573202874732c206e616d652c2076616c7565290a202020202020202056414c554553202827323032312d30312d32345430353a30313a31312e383433383237273a3a74696d657374616d702c202770792d616263272c20313233293b0a202020202020202000\n" +
                "<430000000f494e5345525420302031005a0000000554\n" +
                ">51000000840a2020202020202020494e5345525420494e544f20747261646573202874732c206e616d652c2076616c7565290a202020202020202056414c554553202827323032312d30312d32345430353a30313a31312e383434333138273a3a74696d657374616d702c202770792d616263272c20313233293b0a202020202020202000\n" +
                "<430000000f494e5345525420302031005a0000000554\n" +
                ">51000000840a2020202020202020494e5345525420494e544f20747261646573202874732c206e616d652c2076616c7565290a202020202020202056414c554553202827323032312d30312d32345430353a30313a31312e383434373833273a3a74696d657374616d702c202770792d616263272c20313233293b0a202020202020202000\n" +
                "<430000000f494e5345525420302031005a0000000554\n" +
                ">51000000840a2020202020202020494e5345525420494e544f20747261646573202874732c206e616d652c2076616c7565290a202020202020202056414c554553202827323032312d30312d32345430353a30313a31312e383435323833273a3a74696d657374616d702c202770792d616263272c20313233293b0a202020202020202000\n" +
                "<430000000f494e5345525420302031005a0000000554\n" +
                ">510000000b434f4d4d495400\n" +
                "<430000000b434f4d4d4954005a0000000549\n" +
                ">510000000a424547494e00\n" +
                "<430000000a424547494e005a0000000554\n" +
                ">510000001a53454c454354202a2046524f4d207472616465733b00\n" +
                "<540000004a00037473000000000000010000045a0008ffffffff00006e616d650000000000000200000413ffffffffffff000076616c756500000000000003000000170004ffffffff0000440000003500030000001a323032312d30312d32342030353a30313a31312e3833353834390000000670792d61626300000003313233440000003500030000001a323032312d30312d32342030353a30313a31312e3834313438370000000670792d61626300000003313233440000003500030000001a323032312d30312d32342030353a30313a31312e3834323130350000000670792d61626300000003313233440000003500030000001a323032312d30312d32342030353a30313a31312e3834323531340000000670792d61626300000003313233440000003500030000001a323032312d30312d32342030353a30313a31312e3834323934390000000670792d61626300000003313233440000003500030000001a323032312d30312d32342030353a30313a31312e3834333337390000000670792d61626300000003313233440000003500030000001a323032312d30312d32342030353a30313a31312e3834333832370000000670792d61626300000003313233440000003500030000001a323032312d30312d32342030353a30313a31312e3834343331380000000670792d61626300000003313233440000003500030000001a323032312d30312d32342030353a30313a31312e3834343738330000000670792d61626300000003313233440000003500030000001a323032312d30312d32342030353a30313a31312e3834353238330000000670792d61626300000003313233430000000e53454c454354203130005a0000000554\n" +
                ">5800000004\n";
        assertHexScript(NetworkFacadeImpl.INSTANCE,
                script,
                new DefaultPGWireConfiguration()
        );
    }

    @Test
    public void testQueryTimeout() throws Exception {
        assertMemoryLeak(() -> {
            compiler.compile("create table tab as (select rnd_double() d from long_sequence(10000000))", sqlExecutionContext);
            try (
                    final PGWireServer ignored = createPGServer(1, Timestamps.SECOND_MICROS);
                    final Connection connection = getConnection(false, true);
                    final PreparedStatement statement = connection.prepareStatement("select * from tab order by d")
            ) {
                try {
                    statement.execute();
                    Assert.fail();
                } catch (SQLException e) {
                    TestUtils.assertContains(e.getMessage(), "timeout, query aborted ");
                }
            }
        });
    }

    @Test
    public void testRegProcedure() throws Exception {
        assertMemoryLeak(() -> {
            try (
                    final PGWireServer ignored = createPGServer(1);
                    final Connection connection = getConnection(false, true)
            ) {
                final CallableStatement stmt = connection.prepareCall("SELECT t.oid, t.typname, t.typelem, t.typdelim, t.typinput, r.rngsubtype, t.typtype, t.typbasetype " +
                        "FROM pg_type as t " +
                        "LEFT JOIN pg_range as r ON oid = rngtypid " +
                        "WHERE " +
                        "t.typname IN ('int2', 'int4', 'int8', 'oid', 'float4', 'float8', 'text', 'varchar', 'char', 'name', 'bpchar', 'bool', 'bit', 'varbit', 'timestamptz', 'date', 'money', 'bytea', 'point', 'hstore', 'json', 'jsonb', 'cidr', 'inet', 'uuid', 'xml', 'tsvector', 'macaddr', 'citext', 'ltree', 'line', 'lseg', 'box', 'path', 'polygon', 'circle', 'time', 'timestamp', 'numeric', 'interval') " +
                        "OR t.typtype IN ('r', 'e', 'd') " +
                        "OR t.typinput = 'array_in(cstring,oid,integer)'::regprocedure " +
                        "OR t.typelem != 0 ");
                stmt.execute();
            }
        });
    }

    @Test
    public void testRegularBatchInsertMethod() throws Exception {

        assertMemoryLeak(() -> {
            try (
                    final PGWireServer ignored = createPGServer(2);
                    final Connection connection = getConnection(false, true)
            ) {
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate("create table test_batch(id long,val int)");
                }
                try (PreparedStatement batchInsert = connection.prepareStatement("insert into test_batch(id,val) values(?,?)")) {
                    batchInsert.setLong(1, 0L);
                    batchInsert.setInt(2, 1);
                    batchInsert.addBatch();

                    batchInsert.clearParameters();
                    batchInsert.setLong(1, 1L);
                    batchInsert.setInt(2, 2);
                    batchInsert.addBatch();

                    batchInsert.clearParameters();
                    batchInsert.setLong(1, 2L);
                    batchInsert.setInt(2, 3);
                    batchInsert.addBatch();

                    int[] a = batchInsert.executeBatch();
                    Assert.assertEquals(3, a.length);
                    Assert.assertEquals(1, a[0]);
                    Assert.assertEquals(1, a[1]);
                    Assert.assertEquals(1, a[2]);
                }

                StringSink sink = new StringSink();
                String expected = "id[BIGINT],val[INTEGER]\n" +
                        "0,1\n" +
                        "1,2\n" +
                        "2,3\n";
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("select * from test_batch");
                assertResultSet(expected, sink, rs);
            }
        });
    }

    // test four:
    // -set fetchsize = 50
    // -run query (50 rows fetched)
    // -set fetchsize = 25
    // -process results:
    // --process 50 rows.
    // --do a FETCH FORWARD 25
    // --process 25 rows
    // --do a FETCH FORWARD 25
    // --process 25 rows. end of results.
    @Test
    public void testResultSetFetchSizeFour() throws Exception {
        assertMemoryLeak(() -> {
            try (
                    final PGWireServer ignored = createPGServer(1);
                    final Connection connection = getConnection(false, true)
            ) {
                connection.setAutoCommit(false);
                int totalRows = 100;

                CallableStatement tbl = connection.prepareCall(
                        "create table x as (select cast(x - 1 as int) a from long_sequence(" + totalRows + "))");
                tbl.execute();

                connection.commit();
                PreparedStatement stmt = connection.prepareStatement("x");
                stmt.setFetchSize(50);
                ResultSet rs = stmt.executeQuery();
                rs.setFetchSize(25);

                int count = 0;
                while (rs.next()) {
                    assertEquals(count, rs.getInt(1));
                    ++count;
                }

                assertEquals(totalRows, count);
            }
        });
    }

    // test one:
    // -set fetchsize = 0
    // -run query (all rows should be fetched)
    // -set fetchsize = 50 (should have no effect)
    // -process results
    @Test
    public void testResultSetFetchSizeOne() throws Exception {
        assertMemoryLeak(() -> {
            try (
                    final PGWireServer ignored = createPGServer(1);
                    final Connection connection = getConnection(false, true)
            ) {
                connection.setAutoCommit(false);
                int totalRows = 100;

                CallableStatement tbl = connection.prepareCall(
                        "create table x as (select cast(x - 1 as int) a from long_sequence(" + totalRows + "))");
                tbl.execute();

                PreparedStatement stmt = connection.prepareStatement("x");
                stmt.setFetchSize(0);

                ResultSet rs = stmt.executeQuery();
                rs.setFetchSize(50); // Should have no effect.

                int count = 0;
                while (rs.next()) {
                    assertEquals(count, rs.getInt(1));
                    ++count;
                }

                assertEquals(totalRows, count);
            }
        });
    }

    // test three:
    // -set fetchsize = 25
    // -run query (25 rows fetched)
    // -set fetchsize = 50
    // -process results:
    // --process 25 rows. should NOT hit end-of-results here.
    // --do a FETCH FORWARD 50
    // --process 50 rows
    // --do a FETCH FORWARD 50
    // --process 25 rows. end of results.
    @Test
    public void testResultSetFetchSizeThree() throws Exception {
        assertMemoryLeak(() -> {
            try (
                    final PGWireServer ignored = createPGServer(1);
                    final Connection connection = getConnection(false, true)
            ) {
                connection.setAutoCommit(false);
                int totalRows = 100;

                CallableStatement tbl = connection.prepareCall(
                        "create table x as (select cast(x - 1 as int) a from long_sequence(" + totalRows + "))");
                tbl.execute();

                connection.commit();

                PreparedStatement stmt = connection.prepareStatement("x");
                stmt.setFetchSize(25);
                ResultSet rs = stmt.executeQuery();
                rs.setFetchSize(50);

                int count = 0;
                while (rs.next()) {
                    assertEquals(count, rs.getInt(1));
                    ++count;
                }

                assertEquals(totalRows, count);
            }
        });
    }

    // test two:
    // -set fetchsize = 25
    // -run query (25 rows fetched)
    // -set fetchsize = 0
    // -process results:
    // --process 25 rows
    // --should do a FETCH ALL to get more data
    // --process 75 rows
    @Test
    public void testResultSetFetchSizeTwo() throws Exception {
        assertMemoryLeak(() -> {
            try (
                    final PGWireServer ignored = createPGServer(1);
                    final Connection connection = getConnection(false, true)
            ) {
                connection.setAutoCommit(false);
                int totalRows = 100;

                CallableStatement tbl = connection.prepareCall(
                        "create table x as (select cast(x - 1 as int) a from long_sequence(" + totalRows + "))");
                tbl.execute();

                connection.commit();
                PreparedStatement stmt = connection.prepareStatement("x");
                stmt.setFetchSize(25);
                ResultSet rs = stmt.executeQuery();
                rs.setFetchSize(0);

                int count = 0;
                while (rs.next()) {
                    assertEquals(count, rs.getInt(1));
                    ++count;
                }

                assertEquals(totalRows, count);
            }
        });
    }

    @Test
    public void testRollbackDataOnStaleTransaction() throws Exception {
        assertMemoryLeak(() -> {
            try (final PGWireServer ignored = createPGServer(2)) {
                try (final Connection connection = getConnection(false, true)) {
                    connection.setAutoCommit(false);
                    connection.prepareStatement("create table xyz(a int)").execute();
                    connection.prepareStatement("insert into xyz values (100)").execute();
                    connection.prepareStatement("insert into xyz values (101)").execute();
                    connection.prepareStatement("insert into xyz values (102)").execute();
                    connection.prepareStatement("insert into xyz values (103)").execute();

                    sink.clear();
                    try (
                            PreparedStatement ps = connection.prepareStatement("xyz");
                            ResultSet rs = ps.executeQuery()
                    ) {
                        assertResultSet(
                                "a[INTEGER]\n",
                                sink,
                                rs
                        );
                    }
                }

                // we need to let server process disconnect and release writer
                Os.sleep(2000);

                try (TableWriter w = engine.getWriter(sqlExecutionContext.getCairoSecurityContext(), "xyz", "testing")) {
                    w.commit();
                }

                try (final Connection connection = getConnection(false, true)) {
                    sink.clear();
                    try (
                            PreparedStatement ps = connection.prepareStatement("xyz");
                            ResultSet rs = ps.executeQuery()
                    ) {
                        assertResultSet(
                                "a[INTEGER]\n",
                                sink,
                                rs
                        );
                    }
                }
            }
        });
    }

    @Test
    public void testRowLimitNotResumed() throws Exception {
        assertMemoryLeak(() -> {
            try (final PGWireServer ignored = createPGServer(1)) {
                try (final Connection connection = getConnection(false
                        , true)) {
                    try (CallableStatement st1 = connection.prepareCall("create table y as (" +
                            "select timestamp_sequence(0, 1000000000) timestamp," +
                            " rnd_symbol('a','b',null) symbol1 " +
                            " from long_sequence(10)" +
                            ") timestamp (timestamp)")) {
                        st1.execute();
                    }
                }
            }

            try (final PGWireServer ignored = createPGServer(1)) {
                for (int i = 0; i < 3; i++) {
                    try (final Connection connection = getConnection(false, true)) {
                        try (PreparedStatement select1 = connection.prepareStatement("select version()")) {
                            ResultSet rs0 = select1.executeQuery();
                            sink.clear();
                            assertResultSet("version[VARCHAR]\n" +
                                    "PostgreSQL 12.3, compiled by Visual C++ build 1914, 64-bit\n", sink, rs0);
                            rs0.close();
                        }
                        try (PreparedStatement select2 = connection.prepareStatement("select timestamp from y")) {
                            select2.setMaxRows(1);
                            ResultSet rs2 = select2.executeQuery();
                            rs2.next();
                            rs2.close();
                        }
                    }
                }
            }
        });
    }

    @Test
    public void testRunAlterWhenTableLockedAndAlterTakesTooLong() throws Exception {
        assertMemoryLeak(() -> {
            writerAsyncCommandBusyWaitTimeout = 1_000_000;
            writerAsyncCommandMaxTimeout = 30_000_000;
            SOCountDownLatch queryStartedCountDown = new SOCountDownLatch();
            ff = new FilesFacadeImpl() {
                @Override
                public long openRW(LPSZ name, long opts) {
                    if (Chars.endsWith(name, "_meta.swp")) {
                        queryStartedCountDown.await();
                        Os.sleep(configuration.getWriterAsyncCommandBusyWaitTimeout() / 1000);
                    }
                    return super.openRW(name, opts);
                }
            };
            testAddColumnBusyWriter(true, new SOCountDownLatch());
        });
    }

    @Test
    public void testRunAlterWhenTableLockedAndAlterTakesTooLongFailsToWait() throws Exception {
        assertMemoryLeak(() -> {
            writerAsyncCommandMaxTimeout = configuration.getWriterAsyncCommandBusyWaitTimeout();
            SOCountDownLatch queryStartedCountDown = new SOCountDownLatch();
            ff = new FilesFacadeImpl() {
                @Override
                public long openRW(LPSZ name, long opts) {
                    if (Chars.endsWith(name, "_meta.swp")) {
                        queryStartedCountDown.await();
                        Os.sleep(configuration.getWriterAsyncCommandBusyWaitTimeout() / 1000);
                    }
                    return super.openRW(name, opts);
                }
            };
            testAddColumnBusyWriter(false, queryStartedCountDown);
        });
    }

    @Test
    public void testRunAlterWhenTableLockedAndAlterTimeoutsToStart() throws Exception {
        assertMemoryLeak(() -> {
            writerAsyncCommandBusyWaitTimeout = 1;
            ff = new FilesFacadeImpl() {
                @Override
                public long openRW(LPSZ name, long opts) {
                    if (Chars.endsWith(name, "_meta.swp")) {
                        Os.sleep(50);
                    }
                    return super.openRW(name, opts);
                }
            };
            testAddColumnBusyWriter(false, new SOCountDownLatch());
        });
    }

    @Test
    public void testRunAlterWhenTableLockedWithInserts() throws Exception {
        writerAsyncCommandBusyWaitTimeout = 10_000_000;
        assertMemoryLeak(() -> testAddColumnBusyWriter(true, new SOCountDownLatch()));
    }

    @Test
    public void testRustBindVariableHex() throws Exception {
        //hex for close message 43 00000009 53 535f31 00
        String script = ">0000003600030000636c69656e745f656e636f64696e67005554463800757365720061646d696e006461746162617365007164620000\n" +
                "<520000000800000003\n" +
                ">700000000a717565737400\n" +
                "<520000000800000000530000001154696d655a6f6e6500474d5400530000001d6170706c69636174696f6e5f6e616d6500517565737444420053000000187365727665725f76657273696f6e0031312e33005300000019696e74656765725f6461746574696d6573006f6e005300000019636c69656e745f656e636f64696e670055544638005a0000000549\n" +
                ">5100000067435245415445205441424c45204946204e4f542045584953545320747261646573202874732054494d455354414d502c206461746520444154452c206e616d6520535452494e472c2076616c756520494e54292074696d657374616d70287473293b00\n" +
                "<43000000074f4b005a0000000549\n" +
                ">510000000a424547494e00\n" +
                "<430000000a424547494e005a0000000554\n" +
                ">5000000031733000696e7365727420696e746f207472616465732076616c756573202824312c24322c24332c2434290000004400000008537330005300000004\n" +
                "<3100000004740000001600040000045a0000045a00000413000000176e000000045a0000000554\n" +
                ">420000004200733000000100010004000000080002649689ed0814000000080002649689ed08170000000c72757374206578616d706c65000000040000000000010001450000000900000000005300000004\n" +
                "<3200000004430000000f494e5345525420302031005a0000000554\n" +
                ">510000000b434f4d4d495400\n" +
                "<430000000b434f4d4d4954005a0000000549\n" +
                ">4300000008537330005300000004\n" +
                "<33000000045a0000000549\n" +
                ">5800000004\n";

        assertHexScript(
                NetworkFacadeImpl.INSTANCE,
                script,
                new DefaultPGWireConfiguration()
        );
    }

    @Test
    public void testRustSelectHex() throws Exception {
        final String script = ">0000004300030000636c69656e745f656e636f64696e6700555446380074696d657a6f6e650055544300757365720061646d696e006461746162617365007164620000\n" +
                "<520000000800000003\n" +
                ">700000000a717565737400\n" +
                "<520000000800000000530000001154696d655a6f6e6500474d5400530000001d6170706c69636174696f6e5f6e616d6500517565737444420053000000187365727665725f76657273696f6e0031312e33005300000019696e74656765725f6461746574696d6573006f6e005300000019636c69656e745f656e636f64696e670055544638005a0000000549\n" +
                ">510000005c435245415445205441424c45204946204e4f542045584953545320747261646573202874732054494d455354414d502c206e616d6520535452494e472c2076616c756520494e54292074696d657374616d70287473293b00\n" +
                "<43000000074f4b005a0000000549\n" +
                ">5000000059733000494e5345525420494e544f207472616465732056414c55455328746f5f74696d657374616d702824312c2027797979792d4d4d2d64645448483a6d6d3a73732e53535355555527292c24322c2433290000004400000008537330005300000004\n" +
                "<3100000004740000001200030000041300000413000000176e000000045a0000000549\n" +
                ">4200000048007330000001000100030000001a323032312d30312d32305431343a30303a30362e3537323839370000000c72757374206578616d706c65000000040000007b00010001450000000900000000005300000004\n" +
                "<3200000004430000000f494e5345525420302031005a0000000549\n" +
                ">4300000008537330005300000004\n" +
                "<33000000045a0000000549\n" +
                ">510000000a424547494e00\n" +
                "<430000000a424547494e005a0000000554\n" +
                ">500000005b733100696e7365727420696e746f207472616465732076616c7565732028746f5f74696d657374616d702824312c2027797979792d4d4d2d64645448483a6d6d3a73732e53535355555527292c24322c202433290000004400000008537331005300000004\n" +
                "<3100000004740000001200030000041300000413000000176e000000045a0000000554\n" +
                ">4200000048007331000001000100030000001a323032312d30312d32305431343a30303a30362e3630323834330000000c72757374206578616d706c65000000040000000000010001450000000900000000005300000004\n" +
                "<3200000004430000000f494e5345525420302031005a0000000554\n" +
                ">4200000048007331000001000100030000001a323032312d30312d32305431343a30303a30362e3630333430360000000c72757374206578616d706c65000000040000000100010001450000000900000000005300000004\n" +
                "<3200000004430000000f494e5345525420302031005a0000000554\n" +
                ">4200000048007331000001000100030000001a323032312d30312d32305431343a30303a30362e3630333830350000000c72757374206578616d706c65000000040000000200010001450000000900000000005300000004\n" +
                "<3200000004430000000f494e5345525420302031005a0000000554\n" +
                ">4200000048007331000001000100030000001a323032312d30312d32305431343a30303a30362e3630343139360000000c72757374206578616d706c65000000040000000300010001450000000900000000005300000004\n" +
                "<3200000004430000000f494e5345525420302031005a0000000554\n" +
                ">4200000048007331000001000100030000001a323032312d30312d32305431343a30303a30362e3630343537370000000c72757374206578616d706c65000000040000000400010001450000000900000000005300000004\n" +
                "<3200000004430000000f494e5345525420302031005a0000000554\n" +
                ">4200000048007331000001000100030000001a323032312d30312d32305431343a30303a30362e3630343938320000000c72757374206578616d706c65000000040000000500010001450000000900000000005300000004\n" +
                "<3200000004430000000f494e5345525420302031005a0000000554\n" +
                ">4200000048007331000001000100030000001a323032312d30312d32305431343a30303a30362e3630353338350000000c72757374206578616d706c65000000040000000600010001450000000900000000005300000004\n" +
                "<3200000004430000000f494e5345525420302031005a0000000554\n" +
                ">4200000048007331000001000100030000001a323032312d30312d32305431343a30303a30362e3630353738310000000c72757374206578616d706c65000000040000000700010001450000000900000000005300000004\n" +
                "<3200000004430000000f494e5345525420302031005a0000000554\n" +
                ">4200000048007331000001000100030000001a323032312d30312d32305431343a30303a30362e3630363237380000000c72757374206578616d706c65000000040000000800010001450000000900000000005300000004\n" +
                "<3200000004430000000f494e5345525420302031005a0000000554\n" +
                ">4200000048007331000001000100030000001a323032312d30312d32305431343a30303a30362e3630363636360000000c72757374206578616d706c65000000040000000900010001450000000900000000005300000004\n" +
                "<3200000004430000000f494e5345525420302031005a0000000554\n" +
                ">510000000b434f4d4d495400\n" +
                "<430000000b434f4d4d4954005a0000000549\n" +
                ">4300000008537331005300000004\n" +
                "<33000000045a0000000549\n" +
                ">5800000004\n";
        assertHexScript(NetworkFacadeImpl.INSTANCE,
                script,
                new DefaultPGWireConfiguration());
    }

    @Test
    public void testSchemasCall() throws Exception {
        assertMemoryLeak(() -> {

            sink.clear();

            try (final PGWireServer ignored = createPGServer(2);
                 final Connection connection = getConnection(false, true)
            ) {
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate("create table test (id long,val int)");
                    statement.executeUpdate("create table test2(id long,val int)");
                }

                final DatabaseMetaData metaData = connection.getMetaData();
                try (ResultSet rs = metaData.getCatalogs()) {
                    assertResultSet(
                            "TABLE_CAT[VARCHAR]\n" +
                                    "qdb\n",
                            sink,
                            rs);
                }

                sink.clear();

                try (ResultSet rs = metaData.getSchemas()) {
                    assertResultSet(
                            "TABLE_SCHEM[VARCHAR],TABLE_CATALOG[VARCHAR]\n" +
                                    "pg_catalog,null\n" +
                                    "public,null\n",
                            sink,
                            rs
                    );
                }

                sink.clear();

                try (ResultSet rs = metaData.getTables(
                        "qdb", null, null, null
                )) {
                    assertResultSet(
                            "TABLE_CAT[VARCHAR],TABLE_SCHEM[VARCHAR],TABLE_NAME[VARCHAR],TABLE_TYPE[VARCHAR],REMARKS[VARCHAR],TYPE_CAT[VARCHAR],TYPE_SCHEM[VARCHAR],TYPE_NAME[VARCHAR],SELF_REFERENCING_COL_NAME[VARCHAR],REF_GENERATION[VARCHAR]\n" +
                                    "null,pg_catalog,pg_class,SYSTEM TABLE,null,,,,,\n" +
                                    "null,public,test,TABLE,null,,,,,\n" +
                                    "null,public,test2,TABLE,null,,,,,\n",
                            sink,
                            rs
                    );
                }

                sink.clear();
                try (ResultSet rs = metaData.getColumns("qdb", null, "test", null)) {
                    assertResultSet(
                            "TABLE_CAT[VARCHAR],TABLE_SCHEM[VARCHAR],TABLE_NAME[VARCHAR],COLUMN_NAME[VARCHAR],DATA_TYPE[SMALLINT],TYPE_NAME[VARCHAR],COLUMN_SIZE[INTEGER],BUFFER_LENGTH[VARCHAR],DECIMAL_DIGITS[INTEGER],NUM_PREC_RADIX[INTEGER],NULLABLE[INTEGER],REMARKS[VARCHAR],COLUMN_DEF[VARCHAR],SQL_DATA_TYPE[INTEGER],SQL_DATETIME_SUB[INTEGER],CHAR_OCTET_LENGTH[VARCHAR],ORDINAL_POSITION[INTEGER],IS_NULLABLE[VARCHAR],SCOPE_CATALOG[VARCHAR],SCOPE_SCHEMA[VARCHAR],SCOPE_TABLE[VARCHAR],SOURCE_DATA_TYPE[SMALLINT],IS_AUTOINCREMENT[VARCHAR],IS_GENERATEDCOLUMN[VARCHAR]\n" +
                                    "null,public,test,id,-5,int8,19,null,0,10,1,null,null,null,null,19,0,YES,null,null,null,0,NO,NO\n" +
                                    "null,public,test,val,4,int4,10,null,0,10,1,null,null,null,null,10,1,YES,null,null,null,0,NO,NO\n",
                            sink,
                            rs
                    );
                }

                // todo:  does not work
                //    trim() function syntax is not supported (https://w3resource.com/PostgreSQL/trim-function.php)
                /*
                sink.clear();
                try (ResultSet rs = metaData.getIndexInfo("qdb", "public", "test", true, false)) {
                    assertResultSet(
                            "",
                            sink,
                            rs
                    );
                }
                */
            }
        });
    }

    @Test
    /* asyncq.py (please pay attention to non-standard username/password)

--
    import asyncio
    import asyncpg

    async def run():
        conn = await asyncpg.connect(user='xyz', password='oh',
                                 database='postgres', host='127.0.0.1')
        s = """
            select * from 'tab'
            LIMIT 100
            """
        values = await conn.fetch(s)
        await conn.close()

    loop = asyncio.get_event_loop()
    loop.run_until_complete(run())
--

-- SQL to create table

create table tab as (
    select
        rnd_byte() b,
        rnd_short() sh,
        rnd_int() i,
        rnd_long() l,
        rnd_float() f,
        rnd_double() d,
        rnd_str() s,
        rnd_symbol('abc', 'cde') sym,
        rnd_boolean() bool,
        rnd_date() dt,
        rnd_long256() lt,
        rnd_char() ch,
        rnd_timestamp(0, 1000, 0) ts,
        rnd_bin() bin
    from long_sequence(10)
);

     */
    public void testSelectAllTypesFromAsyncPG() throws Exception {

        compiler.compile("create table tab as (\n" +
                "    select\n" +
                "        rnd_byte() b,\n" +
                "        rnd_short() sh,\n" +
                "        rnd_int() i,\n" +
                "        rnd_long() l,\n" +
                "        rnd_float() f,\n" +
                "        rnd_double() d,\n" +
                "        rnd_str() s,\n" +
                "        rnd_symbol('abc', 'cde') sym,\n" +
                "        rnd_boolean() bool,\n" +
                "        rnd_date() dt,\n" +
                "        rnd_long256() lt,\n" +
                "        rnd_char() ch,\n" +
                "        rnd_timestamp(0, 1000, 0) ts,\n" +
                "        rnd_bin() bin\n" +
                "    from long_sequence(10)\n" +
                ");\n", sqlExecutionContext
        );

        engine.releaseAllWriters();

        final String script = ">0000000804d2162f\n" +
                "<4e\n" +
                ">0000003c00030000636c69656e745f656e636f64696e6700277574662d382700757365720078797a00646174616261736500706f737467\n" +
                ">726573\n" +
                ">00\n" +
                ">00\n" +
                "<520000000800000003\n" +
                ">70000000\n" +
                ">07\n" +
                ">6f68\n" +
                ">00\n" +
                "<520000000800000000530000001154696d655a6f6e6500474d5400530000001d6170706c69636174696f6e5f6e616d6500517565737444420053000000187365727665725f76657273696f6e0031312e33005300000019696e74656765725f6461746574696d6573006f6e005300000019636c69656e745f656e636f64696e670055544638005a0000000549\n" +
                ">50\n" +
                ">00\n" +
                ">0000595f\n" +
                ">5f\n" +
                ">61\n" +
                ">7379\n" +
                ">6e\n" +
                ">6370675f73746d745f31\n" +
                ">5f\n" +
                ">5f\n" +
                ">000a\n" +
                ">20\n" +
                ">2020\n" +
                ">20\n" +
                ">2020\n" +
                ">20\n" +
                ">20\n" +
                ">2020\n" +
                ">20\n" +
                ">20\n" +
                ">7365\n" +
                ">6c\n" +
                ">6563\n" +
                ">74\n" +
                ">20\n" +
                ">2a20\n" +
                ">66\n" +
                ">726f\n" +
                ">6d\n" +
                ">2027\n" +
                ">7461\n" +
                ">6227\n" +
                ">0a\n" +
                ">2020\n" +
                ">20\n" +
                ">20\n" +
                ">2020\n" +
                ">20\n" +
                ">20\n" +
                ">2020\n" +
                ">20\n" +
                ">204c\n" +
                ">49\n" +
                ">4d49\n" +
                ">54\n" +
                ">20\n" +
                ">3130\n" +
                ">30\n" +
                ">0a20\n" +
                ">20\n" +
                ">20\n" +
                ">2020\n" +
                ">20\n" +
                ">2020\n" +
                ">00\n" +
                ">00\n" +
                ">0044\n" +
                ">00000018535f5f6173796e6370675f73746d745f315f5f004800000004\n" +
                "<310000000474000000060000540000012a000e6200000000000001000000150001ffffffff0000736800000000000002000000150002ffffffff00006900000000000003000000170004ffffffff00006c00000000000004000000140008ffffffff00006600000000000005000002bc0004ffffffff00006400000000000006000002bd0008ffffffff0000730000000000000700000413ffffffffffff000073796d0000000000000800000413ffffffffffff0000626f6f6c00000000000009000000100001ffffffff000064740000000000000a0000045a0008ffffffff00006c740000000000000b00000413ffffffffffff000063680000000000000c000000120002ffffffff000074730000000000000d0000045a0008ffffffff000062696e0000000000000e00000011ffffffffffff0001\n" +
                ">4200\n" +
                ">00\n" +
                ">0022\n" +
                ">00\n" +
                ">5f5f\n" +
                ">61\n" +
                ">7379\n" +
                ">6e\n" +
                ">6370675f\n" +
                ">7374\n" +
                ">6d\n" +
                ">745f\n" +
                ">315f\n" +
                ">5f\n" +
                ">0000\n" +
                ">01\n" +
                ">000100\n" +
                ">00\n" +
                ">0001\n" +
                ">0001\n" +
                ">450000000900\n" +
                ">00\n" +
                ">0000\n" +
                ">00\n" +
                ">5300000004\n" +
                "<320000000444000000d6000e00000002004c0000000260ee000000045c50d341000000089f9b2131d49fcd1d000000043dadd020000000083fd23631d4c984f000000005595258504500000003636465000000010100000008fffca2ff9b5cae6000000042307836653630613031613562336561306462346230663539356631343365356437323266316138323636653739323165336237313664653364323564636332643931000000015800000008fffca2fec4c821d600000020c788dea0793c7715686126af19c49594365349b4597e3b08a11e388d1b9ef4c844000000d6000e00000002003900000002fb09000000040fbffdfe000000086afe61bd7c4ae0d8000000043f675fe3000000083feeffefe8f64b8500000005445251515500000003636465000000010100000008fffca2fee4ad31d000000042307836353566383761336132316435373566363130663639656665303633666537393333366463343334373930656433333132626266636636366261623933326663000000014a00000008fffca2fec4c8227400000020934d1a8e78b5b91153d0fb64bb1ad4f02d40e24bb13ee3f1f11eca9c1d06ac3744000000da000e0000000200700000000217cd000000046fdde48200000008997918f622d62989000000043f3916a1000000083fdd38eacf6e41fa000000094f545345445959435400000003616263000000010000000008fffca3000aa21be800000042307837656261663663613939336638666339386231333039636633326436386262386161376463346563636236383134366662333766316563383237353263376437000000014300000008fffca2fec4c8227a000000208447dcd2857fa5b87b4a9d467c8ddd93e6d0b32b0798cc7648a3bb64d2ad491c44000000d8000e00000002005600000002cc3c0000000424a116ed000000086ea837f54a415439000000043e9beabe000000083f9b7b1f63e262c0000000074a4f4a4950485a00000003616263000000010100000008fffca300ec9bd72800000042307862623536616237376366666530613839346165643131633732323536613830633762356464326238353133623331653762323065313930306361666638313961000000014f00000008fffca2fec4c8229d00000020b7c29f298e295e69c6ebeac3c9739346fec2d368798b431d573404238dd8579144000000d7000e00000002004c000000023a2800000004c43377a500000008fdb12ef0d2c74218000000043e8ad49a000000083fe4a8ba7fe3d5cd000000064a4f58504b5200000003616263000000010100000008fffca301160fd32000000042307838643563346265643834333264653938363261326631316538353130613365393963623866633634363730323865623061303739333462326131356465386530000000014f00000008fffca2fec4c820f8000000202860b0ec0b92587d24bc2e606a1c0b20a2868937112c140c2d208452d96f04ab44000000db000e00000002007d000000027e470000000455572a8f000000089c0a1370d099b723000000043f2c45d5000000083fdd63a4d105648a0000000a4e4f4d56454c4c4b4b4800000003636465000000010000000008fffca2ffe0fb34c800000042307834633037316431636136353830356133303565373337303063626562653565623366386363346663343736636163633937393834323036623434363761323830000000014c00000008fffca2fec4c823290000002079e435e43adc5c65ff276777125452d02926c5aada18ce5fb28b5c549025c22044000000db000e000000020039000000020d7000000004f85e333a000000087d85ee2916b209c7000000043e8c4988000000083fd61b4700e1e4460000000a544a434b464d514e544f00000003636465000000010100000008fffca2ffe5c7e3c000000042307833346130353839393038383036393862376362303535633534373235623935323761313931363464383037636565363133343537306132626565343436373335000000014d00000008fffca2fec4c821840000002057a5dba1761c1c26fb2e42faf56e8f80e354b807b13257ff9aef88cb4ba1cfcf44000000d5000e00000002007500000002947d000000048a4592a60000000886be020b55a15fd1000000043f4f90cc000000083fe28cacbc129a84000000044849554700000003616263000000010000000008fffca2ff170d01f000000042307837333762316461636436626535393731393233383461616264383838656362333461363533323836623031303931326237326631643638363735643836376366000000014300000008fffca2fec4c823d60000002011963708dd98ef54882aa2ade7d462e14ed6b2575be3713d20e237f26443845544000000d8000e0000000200240000000240dd00000004c493cf44000000089aadb86434093111000000043d5244c0000000083fef5a79f2bd966500000007575a4e464b504500000003636465000000010000000008fffca300421f7e0800000042307862663839323565316139336666613637396638376439316330366466383733353766623537626331366335313265623862353264336265616664376536306537000000014700000008fffca2fec4c8227e000000208e28b6a917ec0e01c4eb9f138fbb2a4baf8f89df358fdafe3398808520533b5144000000d9000e00000002006900000002655d000000044d4f2528000000083cc96390430d88ac000000043e5c247c000000083fe2723a9f780843000000085151454d58444b5800000003636465000000010100000008fffca2fef62d1d3000000042307837656635393366303066623438313863363466363836303336343261373136643734356430373932643038666466616638303530376365316434323238383630000000015900000008fffca2fec4c822af00000020463b473ce1723b9defc44ac9cffb9d63ca94006bdd18fe7176bc4524cd13007c430000000e53454c454354203130005a0000000549\n" +
                ">580000\n" +
                ">00\n" +
                ">04\n";
        assertHexScript(
                getFragmentedSendFacade(),
                script,
                getHexPgWireConfig()
        );
    }

    /* asyncqp.py
    import asyncio
    import asyncpg

    async def run():
        conn = await asyncpg.connect(user='xyz', password='oh',
                                     database='postgres', host='127.0.0.1')
        s = """
                select * from 'tab2' where a > $1
                LIMIT 100
            """
        values = await conn.fetch(s, 0.4)
        await conn.close()

    loop = asyncio.get_event_loop()
    loop.run_until_complete(run())
     */
    @Test
    public void testSelectBindVarsAsyncPG() throws Exception {

        compiler.compile("create table tab2 (a double);", sqlExecutionContext);
        executeInsert("insert into 'tab2' values (0.7);");
        executeInsert("insert into 'tab2' values (0.2);");
        engine.releaseAllWriters();
        engine.releaseAllReaders();

        final String script = ">0000000804d2162f\n" +
                "<4e\n" +
                ">0000003c00030000636c69656e745f656e636f64696e6700277574662d382700757365720078797a00646174616261736500706f7374677265730000\n" +
                "<520000000800000003\n" +
                ">70000000076f6800\n" +
                "<520000000800000000530000001154696d655a6f6e6500474d5400530000001d6170706c69636174696f6e5f6e616d6500517565737444420053000000187365727665725f76657273696f6e0031312e33005300000019696e74656765725f6461746574696d6573006f6e005300000019636c69656e745f656e636f64696e670055544638005a0000000549\n" +
                ">50000000675f5f6173796e6370675f73746d745f315f5f000a20202020202020202020202073656c656374202a2066726f6d202774616232272077686572652061203e2024310a2020202020202020202020204c494d4954203130300a20202020202020200000004400000018535f5f6173796e6370675f73746d745f315f5f004800000004\n" +
                "<3100000004740000000a0001000002bd540000001a00016100000000000001000002bd0008ffffffff0000\n" +
                ">420000002e005f5f6173796e6370675f73746d745f315f5f00000100010001000000083fd999999999999a00010001450000000900000000005300000004\n" +
                "<320000000444000000120001000000083fe6666666666666430000000d53454c4543542031005a0000000549\n" +
                ">5800000004\n";
        assertHexScript(
                getFragmentedSendFacade(),
                script,
                getHexPgWireConfig()
        );
    }

    @Test
    public void testSemicolonExtendedMode() throws Exception {
        testSemicolon(false);
    }

    @Test
    public void testSemicolonSimpleMode() throws Exception {
        testSemicolon(true);
    }

    @Test
    public void testSendingBufferWhenFlushMessageReceivedHex() throws Exception {
        String script = ">0000006e00030000757365720078797a0064617461626173650071646200636c69656e745f656e636f64696e67005554463800446174655374796c650049534f0054696d655a6f6e65004575726f70652f4c6f6e646f6e0065787472615f666c6f61745f64696769747300320000\n" +
                "<520000000800000003\n" +
                ">70000000076f6800\n" +
                "<520000000800000000530000001154696d655a6f6e6500474d5400530000001d6170706c69636174696f6e5f6e616d6500517565737444420053000000187365727665725f76657273696f6e0031312e33005300000019696e74656765725f6461746574696d6573006f6e005300000019636c69656e745f656e636f64696e670055544638005a0000000549\n" +
                ">500000002c0073656c65637420782c202024312066726f6d206c6f6e675f73657175656e63652832293b000000\n" +
                ">42000000110000000000010000000133000044000000065000450000000900000000004800000004\n" +
                "<31000000043200000004540000002f00027800000000000001000000140008ffffffff000024310000000000000200000413ffffffffffff000044000000100002000000013100000001334400000010000200000001320000000133430000000d53454c454354203200\n" +
                ">4800000004\n" +
                ">5300000004\n" +
                "<5a0000000549\n" +
                ">5800000004\n";

        assertHexScript(
                NetworkFacadeImpl.INSTANCE,
                script,
                getHexPgWireConfig()
        );
    }

    @Test
    public void testSimple() throws Exception {
        testQuery("rnd_double(4) d, ", "s[VARCHAR],i[INTEGER],d[DOUBLE],t[TIMESTAMP],f[REAL],_short[SMALLINT],l[BIGINT],ts2[TIMESTAMP],bb[SMALLINT],b[BIT],rnd_symbol[VARCHAR],rnd_date[TIMESTAMP],rnd_bin[BINARY],rnd_char[CHAR],rnd_long256[VARCHAR]\n");
    }

    @Test
    public void testSimpleAlterTable() throws Exception {
        // we are going to:
        // 1. create a table
        // 2. alter table
        // 3. check table column added
        assertMemoryLeak(() -> {
            try (
                    final PGWireServer ignored = createPGServer(2);
                    final Connection connection = getConnection(true, true)
            ) {
                PreparedStatement statement = connection.prepareStatement("create table x (a int)");
                statement.execute();

                PreparedStatement alter = connection.prepareStatement("alter table x add column b long");
                alter.executeUpdate();

                PreparedStatement select = connection.prepareStatement("x");
                try (ResultSet resultSet = select.executeQuery()) {
                    Assert.assertEquals(resultSet.findColumn("a"), 1);
                    Assert.assertEquals(resultSet.findColumn("b"), 2);
                }
            }
        });
    }

    @Test
    public void testSimpleHex() throws Exception {
        // this is a HEX encoded bytes of the same script as 'testSimple' sends using postgres jdbc driver
        String script = ">0000006600030000757365720061646d696e0064617461626173650071646200636c69656e745f656e636f64696e67005554463800446174655374796c650049534f0054696d655a6f6e6500474d540065787472615f666c6f61745f64696769747300320000\n" +
                "<520000000800000003\n" +
                ">700000000a717565737400\n" +
                "<520000000800000000530000001154696d655a6f6e6500474d5400530000001d6170706c69636174696f6e5f6e616d6500517565737444420053000000187365727665725f76657273696f6e0031312e33005300000019696e74656765725f6461746574696d6573006f6e005300000019636c69656e745f656e636f64696e670055544638005a0000000549\n" +
                ">5000000022005345542065787472615f666c6f61745f646967697473203d2033000000420000000c0000000000000000450000000900000000015300000004\n" +
                "<310000000432000000044300000008534554005a0000000549\n" +
                ">500000003700534554206170706c69636174696f6e5f6e616d65203d2027506f737467726553514c204a4442432044726976657227000000420000000c0000000000000000450000000900000000015300000004\n" +
                "<310000000432000000044300000008534554005a0000000549\n" +
                ">50000001af0073656c65637420726e645f73747228342c342c342920732c20726e645f696e7428302c203235362c20342920692c20726e645f646f75626c6528342920642c2074696d657374616d705f73657175656e636528302c31303030302920742c20726e645f666c6f617428342920662c20726e645f73686f72742829205f73686f72742c20726e645f6c6f6e6728302c2031303030303030302c203529206c2c20726e645f74696d657374616d7028746f5f74696d657374616d70282732303135272c277979797927292c746f5f74696d657374616d70282732303136272c277979797927292c3229207473322c20726e645f6279746528302c313237292062622c20726e645f626f6f6c65616e282920622c20726e645f73796d626f6c28342c342c342c32292c20726e645f6461746528746f5f64617465282732303135272c20277979797927292c20746f5f64617465282732303136272c20277979797927292c2032292c726e645f62696e2831302c32302c32292c20726e645f6368617228292c20726e645f6c6f6e6732353628292066726f6d206c6f6e675f73657175656e636528353029000000420000000c000000000000000044000000065000450000000900000000005300000004\n" +
                "<310000000432000000045400000161000f730000000000000100000413ffffffffffff00006900000000000002000000170004ffffffff00006400000000000003000002bd0008ffffffff000074000000000000040000045a0008ffffffff00006600000000000005000002bc0004ffffffff00005f73686f727400000000000006000000150002ffffffff00006c00000000000007000000140008ffffffff0000747332000000000000080000045a0008ffffffff0000626200000000000009000000150001ffffffff0000620000000000000a000000100001ffffffff0000726e645f73796d626f6c0000000000000b00000413ffffffffffff0000726e645f646174650000000000000c0000045a0008ffffffff0000726e645f62696e0000000000000d00000011ffffffffffff0001726e645f636861720000000000000e000000120002ffffffff0000726e645f6c6f6e673235360000000000000f00000413ffffffffffff000044000000f1000fffffffff00000002353700000012302e363235343032313534323431323031380000001a313937302d30312d30312030303a30303a30302e30303030303000000005302e343632000000052d313539330000000733343235323332ffffffff000000033132310000000166000000045045484e00000017323031352d30332d31372030343a32353a35322e3736350000000e19c49594365349b4597e3b08a11e00000001440000004230783566323061333565383065313534663435386466643038656562396363333965636563383238363965646563313231626332353933663832623433303332386444000000fd000f000000044f554f4a000000023737ffffffff0000001a313937302d30312d30312030303a30303a30302e30313030303000000005302e363736000000052d3733373400000007373737373739310000001a323031352d30362d31392030383a34373a34352e3630333138320000000235330000000174ffffffff00000017323031352d31312d31302030393a35303a33332e323135000000138b812b934d1a8e78b5b91153d0fb64bb1ad4f00000000156000000423078626564663239656662323863646362316237356463636264663166386238346239623237656261356539636661316532393636303330306365613764623534304400000110000f00000004494343580000000332303500000012302e383833373432313931383830303930370000001a313937302d30312d30312030303a30303a30302e30323030303000000005302e303534000000043630393300000007343535323936300000001a323031352d30372d31372030303a35303a35392e37383737343200000002333300000001660000000456544a5700000017323031352d30372d31352030313a30363a31312e32323600000010e5612f640e2c7fd76fb8c9ae28c7844700000001550000004230783862346534383331343939666332613532363536376634343330623436623766373863353934633439363939353838356161313839366430616433343139643244000000f0000f000000044753484f00000002333100000013302e33343934373236393939373133373336350000001a313937302d30312d30312030303a30303a30302e30333030303000000005302e31393800000005313037393500000007363430363230370000001a323031352d30352d32322031343a35393a34312e3637333432320000000235360000000166ffffffffffffffff0000000a491cf23ced39aca83ba600000001530000004230783765623664383036343964316466653338653461376636363164663663333262326631373162336630366636333837643266643262346136306261326261336244000000f4000f00000004485a45500000000331383000000013302e30363934343438303034363332373331370000001a313937302d30312d30312030303a30303a30302e30343030303000000005302e343330000000053231333437ffffffff0000001a323031352d30322d30372031303a30323a31332e36303039353600000002343100000001660000000448595258ffffffff00000010eac3c9739346fec2d368798b431d57340000000146000000423078333865346265396531393332316235373833326464323739353264393439643836393164643434313261326433393864346663303165326239666431313632334400000101000f000000044857564400000002333800000013302e34383532343034363836383439393731350000001a313937302d30312d30312030303a30303a30302e30353030303000000005302e36383000000005323535373900000007353537353735310000001a323031352d31302d31392031323a33383a34392e33363032393400000002313500000001660000000456544a5700000017323031352d30322d30362032323a35383a35302e333333ffffffff0000000151000000423078383531333434363830323561616562306132663862626562623938396261363039626230663231616339653432373238336565663366313538653038343336324400000118000f0000000450474c5500000002393700000014302e3032393232373639363934323732363634340000001a313937302d30312d30312030303a30303a30302e30363030303000000005302e313732000000062d313839313200000007383334303237320000001a323031352d30352d32342032323a30393a35352e3137353939310000000331313100000001660000000456544a5700000017323031352d31312d30382032313a35373a32322e38313200000014d96f04ab27478f233fae7c9f7704e90cea4eea8b000000014b0000004230783535643336383664356461323765313432353561393162306532386162656233366333343933666362326430323732643630343665356431333764643866306644000000fa000f00000004574946460000000331303400000011302e3839323435343738333932313139370000001a313937302d30312d30312030303a30303a30302e30373030303000000005302e30393300000005323832313800000007343030393035370000001a323031352d30322d31382030373a32363a31302e31343130353500000002383900000001660000000448595258ffffffff000000112926c5aada18ce5fb28b5c549025c220ff00000001520000004230783535623035383664316330326466623339393930343632346334396236643861376438356565323931366232303963373739343036616231663835653333336144000000f5000f00000004434c544a0000000331313500000012302e323039333536393934373634343233360000001a313937302d30312d30312030303a30303a30302e30383030303000000005302e353436000000052d3832303700000007323337383731380000001a323031352d30342d32312031323a32353a34332e3239313931360000000233310000000166000000045045484effffffff0000000ba5dba1761c1c26fb2e42fa00000001460000004230783438336338336438386163363734653338393434393961316131363830353830636665646666323361363764393138666234396233633234653435366164366544000000f5000f0000000448464c5000000002373900000012302e393133303135313130353132353130320000001a313937302d30312d30312030303a30303a30302e303930303030ffffffff00000005313436363700000007323531333234380000001a323031352d30382d33312031333a31363a31322e33313837383200000001330000000166ffffffff00000016323031352d30322d30382031323a32383a33362e3636ffffffff0000000155000000423078373934323364346433323064323634393736376134666564613036306434666236393233633063376439363539363964613162313134306132626532353234314400000111000f00000004474c4e590000000331333800000012302e373136353834373331383139313430350000001a313937302d30312d30312030303a30303a30302e31303030303000000005302e373533000000052d3236363600000007393333373337390000001a323031352d30332d32352030393a32313a35322e373736353736000000033131310000000166000000044859525800000016323031352d30312d32342031353a32333a31332e39320000001062e14ed6b2575be3713d20e237f26443000000015900000042307861616334326363626334393363663434616136613061316434636466343064643661653466643235376534343132613037663139373737656331333638303535440000010e000f0000000456544e500000000332333700000013302e32393234323734383437353232373835330000001a313937302d30312d30312030303a30303a30302e31313030303000000005302e373533000000062d323638363100000007323335343133320000001a323031352d30322d31302031383a32373a31312e3134303637350000000235360000000174ffffffff00000017323031352d30322d32352030303a34353a31352e3336330000000f28b6a917ec0e01c4eb9f138fbb2a4b000000014f0000004230783932366364643939653633616262333536353064316662343632643031346466353930373033393265663661613338393933326534623530386533353432386644000000ef000f0000000457464f5100000003323535ffffffff0000001a313937302d30312d30312030303a30303a30302e31323030303000000005302e31313600000005333135363900000007363638383237370000001a323031352d30352d31392030333a33303a34352e373739393939000000033132360000000174000000045045484e00000016323031352d31322d30392030393a35373a31372e3738ffffffff000000014500000042307834663338383034323730613461363433343962353736306136383764386366383338636262396165393665396563646337343565643966616562353133616433440000010f000f00000004454a43540000000331393500000013302e31333331323231343339363735343136330000001a313937302d30312d30312030303a30303a30302e31333030303000000005302e393434000000052d33303133ffffffff0000001a323031352d31312d30332031343a35343a34372e353234303135000000033131340000000174000000045045484e00000017323031352d30382d32382030373a34313a32392e39353200000013fb9d63ca94006bdd18fe7176bc4524cd13007c00000001520000004230783363666535306239636162616631663239653064636666623735323065626361633438616436623866363936323231396232376230616337666264656532303144000000fd000f000000044a5959460000000332343900000012302e323030303638323435303932393335330000001a313937302d30312d30312030303a30303a30302e31343030303000000005302e363032000000043538363900000007323037393231370000001a323031352d30372d31302031383a31363a33382e38383239393100000002343400000001740000000448595258ffffffff00000014b76c4bfb2d16f389a38364ded6fdc45bc4e9194700000001500000004230783835653730623436333439373939666534396637383364353334336464376263336433666531333032636433333731313337666363646162663138316235616444000000f7000f00000004545a4f44ffffffff00000013302e33363037383837383939363233323136370000001a313937302d30312d30312030303a30303a30302e31353030303000000005302e363031000000062d32333132350000000735303833333130ffffffff00000002313100000001660000000456544a5700000017323031352d30392d31392031383a31343a35372e35393000000011c560b7d15a0ce9db51134d5920c937a10000000001450000004230786366663835663932353838343765303361366632653261373732636432663337353164383232613637646666336432333735313636323233613631383136343244000000ff000f0000000450424d4200000002373600000013302e32333536373431393537363635383333330000001a313937302d30312d30312030303a30303a30302e31363030303000000005302e353731000000053236323834ffffffff0000001a323031352d30352d32312031333a31343a35362e3334393033360000000234350000000174ffffffff00000016323031352d30392d31312030393a33343a33392e35300000000a97cbf62c2345a3766015000000014d000000423078336333613362373934376365383336393932366362636231366539613266313163666162373066326431373564306439616562393839626537396364326238634400000110000f00000004544b52490000000332303100000012302e323632353432343331323431393536320000001a313937302d30312d30312030303a30303a30302e31373030303000000005302e393135000000052d3534383600000007393931373136320000001a323031352d30352d30332030333a35393a30342e32353637313900000002363600000001660000000456544a5700000016323031352d30312d31352030333a32323a30312e333300000010a1f54bea01c963b4fc92601fdf41ec2c000000014f00000042307834653365313561643439653061383539333132393831613733633964666365373930323261373561373339656534383865656661323932303032366462613838440000010b000f000000044e4b47510000000331373400000012302e343033393034323633393538313233320000001a313937302d30312d30312030303a30303a30302e31383030303000000005302e34333800000005323036383700000007373331353332390000001a323031352d30372d32352030343a35323a32372e3732343836390000000232300000000166000000045045484e00000016323031352d30362d31302032323a32383a35372e31300000000b9283fc88f3322770c801b000000001540000004230783537396231346332373235643761376535646662643865323334393837313562386439656533306537626362663833613664316231633830663031326134633944000000fc000f000000044655584300000002353200000012302e373433303130313939343531313531370000001a313937302d30312d30312030303a30303a30302e313930303030ffffffff000000062d313437323900000007313034323036340000001a323031352d30382d32312030323a31303a35382e3934393637340000000232380000000174000000044350535700000017323031352d30382d32392032303a31353a35312e383335ffffffff000000015800000042307834313435376562633561303261326235343263626434393431346530323261303666346161326463343861396134643939323838323234626533333462323530440000010d000f0000000454474e4a0000000331353900000012302e393536323537373132383430313434340000001a313937302d30312d30312030303a30303a30302e32303030303000000005302e3235310000000337393500000007353036393733300000001a323031352d30372d30312030313a33363a35372e3130313734390000000237310000000174000000045045484e00000017323031352d30392d31322030353a34313a35392e3939390000000e333fb267da984747bf4fea5f48ed000000014d000000423078346261323061386530636637633533633966353237343835633461616334613238323666343762616163643538623238373030613637663631313963363362624400000116000f0000000448434e500000000331373300000013302e31383638343236373634303139353931370000001a313937302d30312d30312030303a30303a30302e32313030303000000005302e363838000000062d313438383200000007383431363835380000001a323031352d30362d31362031393a33313a35392e3831323834380000000232350000000166000000044859525800000017323031352d30392d33302031373a32383a32342e313133000000131d5cc15d2d44ea0081c419a1ec74f810fc6e230000000144000000423078336436343535393836356638346338363438386265393531383139663433303432663033363134376337386530623264313237636135646232663431633565304400000110000f00000004455a42520000000332343300000012302e383230333431383134303533383832340000001a313937302d30312d30312030303a30303a30302e32323030303000000005302e323231000000052d3834343700000007343637373136380000001a323031352d30332d32342030333a33323a33392e3833323337380000000237380000000166000000044350535700000016323031352d30322d31362030343a30343a31392e38320000001042677847b38069b914d6fcee032281b800000001510000004230783732313330346666653163393334333836343636323038643530363930356166343063376533626365346232383430363738336133393435616236383263633444000000fd000f000000045a5042480000000331333100000012302e313939393537363538363737383033390000001a313937302d30312d30312030303a30303a30302e32333030303000000005302e343739000000062d3138393531000000063837343535350000001a323031352d31322d32322031393a31333a35352e3430343132330000000235320000000166ffffffff00000017323031352d31302d30332030353a31363a31372e383931ffffffff000000015a000000423078613934346261613830396133663261646464343132316334376362313133396164643466316135363431633931653361623831663466306361313532656336314400000101000f00000004564c54500000000331393600000012302e343130343835353539353330343533330000001a313937302d30312d30312030303a30303a30302e32343030303000000005302e393138000000062d3132323639000000063134323130370000001a323031352d31302d31302031383a32373a34332e3432333737340000000239320000000166000000045045484e00000017323031352d30322d30362031383a34323a32342e363331ffffffff00000001480000004230783532393363653333393434323465366135616536336264663039613834653332626163343438346264656563343065383837656338346430313531303137363644000000d9000f0000000452554d4d00000003313835ffffffff0000001a313937302d30312d30312030303a30303a30302e32353030303000000005302e383338000000062d323736343900000007333633393034390000001a323031352d30352d30362030303a35313a35372e3337353738340000000238390000000174000000045045484effffffffffffffff000000015700000042307833313636656433626266666238353833313266313930353764393533343138383633363063393939323364323534663338663232353437616539363631343233440000010b000fffffffff00000002373100000012302e373430393039323330323032333630370000001a313937302d30312d30312030303a30303a30302e32363030303000000005302e373432000000062d313838333700000007343136313138300000001a323031352d30342d32322031303a31393a31392e3136323831340000000233370000000174000000044859525800000017323031352d30392d32332030333a31343a35362e3636340000000e8e93bd2742f8252a4271a37a58e500000001440000004230783638396131356438393036373730666361656665303236366239663633626436363938633537343234386539303131633663633834643961366434316530623844000000f6000f000000044e475a540000000332313400000013302e31383137303634363833353634333234350000001a313937302d30312d30312030303a30303a30302e32373030303000000005302e3834310000000532313736340000000733323331383732ffffffff0000000237390000000166000000044859525800000017323031352d30352d32302030373a35313a32392e3637350000000eababac216199be2df530786d5a3b00000001480000004230783562386465663465376130313765383834613363326335303434303337303862343966623864356665306666323833636261633634393965373163653562333044000000ff000f0000000445595950000000023133ffffffff0000001a313937302d30312d30312030303a30303a30302e32383030303000000005302e35333400000005313931333600000007343635383130380000001a323031352d30382d32302030353a32363a30342e30363136313400000001350000000166000000044350535700000017323031352d30332d32332032333a34333a33372e36333400000012c8660c4071ea207e4397271f5cd9ee045b9c000000014300000042307836653665643831316532353438363935336633353938376135303031366262663438316539663535633333616334386336613232623062643666376230626632440000010c000f00000004474d504c00000002353000000012302e373930323638323931383237343330390000001a313937302d30312d30312030303a30303a30302e32393030303000000005302e383734000000062d323738303700000007353639333032390000001a323031352d30372d31342032313a30363a30372e3937353734370000000233370000000174000000044350535700000016323031352d30392d30312030343a30303a32392e34390000000c3b4bb7e27fab6e2303ddc7d600000001550000004230783732633630376231393932666632663838303265383339623737613461326433346238623936376334313265376338393562353039623535643163333864323944000000f0000f0000000442435a490000000332303700000013302e31303836333036313537373030303232310000001a313937302d30312d30312030303a30303a30302e33303030303000000005302e313239000000043339393900000006313231323332ffffffff0000000238380000000174000000044350535700000016323031352d30352d31302032313a31303a32302e34310000000b970bf5ef3bbe857c11f734000000014b0000004230783333626534633034363935663734643737366163366466373161323231663531386633633634323438666235393433656135356162346536393136663366366344000000fe000f000000044458555500000003313339ffffffff0000001a313937302d30312d30312030303a30303a30302e33313030303000000005302e323632000000062d3135323839000000063334313036300000001a323031352d30312d30362030373a34383a32342e363234373733000000033131300000000166ffffffff00000017323031352d30372d30382031383a33373a31362e3837320000001271cf5a8f2106b23f0e41938927ca102f60ce000000014e0000004230783163303564383136333336393465303237393565626163666365623063376464376563396237653963363334626337393132383331343061623737353533316344000000dc000f00000004464d44560000000331393700000012302e323532323130323230393230313935340000001a313937302d30312d30312030303a30303a30302e33323030303000000005302e393933000000062d32363032360000000735333936343338ffffffff00000002383300000001740000000443505357ffffffff0000000b8675ada52d49486836f035000000014b0000004230783330386137613439363665363561303136306230303232393633343834383935376661363764366134313965313732316231353230663636636161373439343544000000cf000f000000045351434e00000002363200000013302e31313530303934333437383834393234360000001a313937302d30312d30312030303a30303a30302e33333030303000000005302e35393500000004313031310000000734363331343132ffffffff00000002353600000001660000000456544a57ffffffffffffffff00000001570000004230783636393036646331663161646263323036613862663632376338353937313461366238343164366336633865343463653134373236316638363839643932353044000000f7000f000000045153434d0000000331333000000012302e383637313430353937383535393237370000001a313937302d30312d30312030303a30303a30302e33343030303000000005302e34323800000005323238393900000006343033313933ffffffff0000000232310000000174000000045045484e00000017323031352d31312d33302032313a30343a33322e38363500000011a0baa5d163ca32e50d6852c694c318c97c00000001490000004230783364636333363231663337333463343835626238316332386563326464623031363364656630366662346536393564633262666134376238323331386666396644000000e4000f0000000455555a490000000331393600000012302e393237373432393434373332303435380000001a313937302d30312d30312030303a30303a30302e33353030303000000005302e3632350000000532343335350000000735373631373336ffffffff000000033131360000000166ffffffff00000017323031352d30322d30342030373a31353a32362e393937ffffffff000000014200000042307862306135323234323438623039336130363765656534353239636365323663333734323966393939626666633935343861613364663134626665643432393639440000010c000f000000044445514e00000002343100000012302e393032383338313136303936353131330000001a313937302d30312d30312030303a30303a30302e33363030303000000005302e31323000000005323930363600000007323534353430340000001a323031352d30342d30372032313a35383a31342e373134373931000000033132350000000166000000045045484e00000017323031352d30322d30362032333a32393a34392e3833360000000bec4b9727dfcd7a1407920100000001490000004230783535303136616362323534623538636433636530356361616236353531383331363833373238666632663732356161316261363233333636633264303865366144000000fc000fffffffff0000000331363400000012302e373635323737353338373732393236360000001a313937302d30312d30312030303a30303a30302e33373030303000000005302e333132000000052d3835363300000007373638343530310000001a323031352d30322d30312031323a33383a32382e33323232383200000001300000000174000000044859525800000017323031352d30372d31362032303a31313a35312e333430ffffffff000000014600000042307839376166396462383462383035343565636465653635313433636263393266383965666561346430343536643930663239646439333339353732323831303432440000010b000f00000004514a504c0000000331363000000012302e313734303033353831323233303034330000001a313937302d30312d30312030303a30303a30302e33383030303000000005302e373633000000043539393100000007323039393236390000001a323031352d30322d32352031353a34393a30362e34373236373400000002363500000001740000000456544a5700000016323031352d30342d32332031313a31353a31332e36350000000cde5845d01b58be3392cd5c9d0000000145000000423078613835613566633230373736653832623336633163646266653334656232363336656563346666633062343466393235623039616334663039636232376633364400000106000f00000004424b554e0000000332303800000012302e343435323134383532343936373032380000001a313937302d30312d30312030303a30303a30302e33393030303000000005302e35383200000005313739323800000007363338333732310000001a323031352d31302d32332030373a31323a32302e37333034323400000001370000000166ffffffff00000017323031352d30312d30322031373a30343a35382e3935390000000a5e37e4682a960646b6aa0000000146000000423078653164323032306265326362376265396335623638663965613162643330633738396536643037323964343462363433393036373862353734656430663539324400000109000f0000000452454453000000013400000013302e30333830343939353332373435343731390000001a313937302d30312d30312030303a30303a30302e34303030303000000005302e313033000000043233353800000007313839373439310000001a323031352d30372d32312031363a33343a31342e3537313536350000000237350000000166000000044350535700000017323031352d30372d33302031363a30343a34362e3732360000000ad6883a93ef24a5e2bc8600000001500000004230783839323435386233346538373639393238363437313636343635333035656631646436363830343038343561313061333865613566626136636639626663393244000000d5000f000000044d505652ffffffffffffffff0000001a313937302d30312d30312030303a30303a30302e34313030303000000005302e353932000000043837353400000007353832383034340000001a323031352d31302d30352032313a31313a31302e3630303835310000000331313600000001660000000443505357ffffffffffffffff00000001480000004230783964316536376336626532663234623261346532636336613632386339343339353932346461646162616564376565343539623261363162306663623734633544000000f4000f000000044b4b4e5a0000000331383600000012302e383232333338383339383932323337320000001a313937302d30312d30312030303a30303a30302e34323030303000000005302e373230000000052d363137390000000738373238393037ffffffff00000002383000000001740000000456544a5700000017323031352d30392d31312030333a34393a31322e3234340000000d16b2d883f5957c95fd52bb50c900000001420000004230783535373234363631636663633831316634343832653161326261386566616566366534616566303339343830316334303934316438396632343038316636346444000000e7000f000000044249434c0000000331383200000012302e373231353639353039353631303233330000001a313937302d30312d30312030303a30303a30302e34333030303000000005302e323237000000062d323238393900000007363430313636300000001a323031352d30382d32332031383a33313a32392e3933313631380000000237380000000174ffffffffffffffffffffffff000000015400000042307862626237353165653130663036306431633266626562373330343435303461656135356138653238336263663835376235333964386364383839666139633931440000010f000f0000000453575046ffffffff00000013302e34383737303737323331303132383637340000001a313937302d30312d30312030303a30303a30302e34343030303000000005302e393134000000062d313739323900000007383337373333360000001a323031352d31322d31332032333a30343a32302e3436353435340000000232380000000166000000044859525800000017323031352d31302d33312031333a33373a30312e3332370000000fb2319c69be749aadcccfb8e4d17a4f0000000149000000423078626539316437333434343333383861326136333164373136623537356338313963393232346132356533663665366661366364373830393364356537656131364400000114000f000000044248455600000002383000000012302e383931373637383530303137343930370000001a313937302d30312d30312030303a30303a30302e34353030303000000005302e32333700000005323932383400000007393537373531330000001a323031352d31302d32302030373a33383a32332e3838393234390000000232370000000166000000044859525800000017323031352d31322d31352031333a33323a35362e3739370000001492832453604d04c2f07a07d4a3d15f0dfe63100d00000001560000004230783232356664646430663433323561396438363334653163623331373333386130643363623766363137333766313637646339303262366636643737396337353344000000fa000f000000044450434800000002363200000012302e363638343530323333323735303630340000001a313937302d30312d30312030303a30303a30302e34363030303000000005302e383739000000062d32323630300000000739323636353533ffffffff00000002383900000001740000000456544a5700000017323031352d30352d32352031393a34323a31372e39353500000013351bb90f97f5777ea32dcefeebcd4706536197000000015300000042307838396436613433623233663833363935623233366165356666616235343632326365316634646163383436343930613862383866303436386330636266613333440000010a000f000000044d4b4e4a00000002363100000012302e323638323030393933353537353030370000001a313937302d30312d30312030303a30303a30302e34373030303000000005302e383133000000052d31333232ffffffff0000001a323031352d31312d30342030383a31313a33392e39393631333200000001340000000166000000044350535700000017323031352d30372d32392032323a35313a30332e333439000000128208fbe7943a325d8a660be485f11306f2270000000156000000423078393839306434616561313439663034393862646566316336626131366464386362643031636638333633323838346165386237303833663838383535346230634400000109000f00000004475351490000000331353800000012302e383034373935343839303139343036350000001a313937302d30312d30312030303a30303a30302e34383030303000000005302e33343700000005323331333900000007313235323338350000001a323031352d30342d32322030303a31303a31322e3036373331310000000233320000000174ffffffff00000017323031352d30312d30392030363a30363a33322e3231330000000c38a785461a275b4d0f33f4700000000156000000423078633065366531313062393039653133613831323432356133383136326265306262363565323965643532396434646261383638613730373566336233343335374400000110000f00000004425054550000000332303500000011302e3433303231343731323430393235350000001a313937302d30312d30312030303a30303a30302e34393030303000000005302e39303500000005333132363600000007383237313535370000001a323031352d30312d30372030353a35333a30332e38333830303500000002313400000001740000000456544a5700000017323031352d31302d33302030353a33333a31352e38313900000010240bc51a5a8d855039429e8a8617896b000000015300000042307834653237326539646664653762623132363138313738663766656261353032313338326138633437613238666566613437356437343363663063326334626364430000000e53454c454354203530005a0000000549\n";
        assertHexScript(script);
    }

    @Test
    public void testSimpleModeNoCommit() throws Exception {
        assertMemoryLeak(() -> {
            try (final PGWireServer ignored = createPGServer(2)) {
                for (int i = 0; i < 50; i++) {
                    try (final Connection connection = getConnection(true, true)) {

                        connection.setAutoCommit(false);
                        connection.prepareStatement("create table if not exists xyz(a int)").execute();
                        connection.prepareStatement("insert into xyz values (100)").execute();
                        connection.prepareStatement("insert into xyz values (101)").execute();
                        connection.prepareStatement("insert into xyz values (102)").execute();
                        connection.prepareStatement("insert into xyz values (103)").execute();
                        connection.rollback();

                        sink.clear();
                        try (
                                PreparedStatement ps = connection.prepareStatement("xyz");
                                ResultSet rs = ps.executeQuery()
                        ) {
                            assertResultSet(
                                    "a[INTEGER]\n",
                                    sink,
                                    rs
                            );
                        }
                        // The next iteration of the loop will create a new connection which may be in a different thread than the current
                        // connection
                        // The new connection will execute a "create table if not exists " statement which requires a full table lock
                        // This connection has just execute a read query on the table and hence has a temporary read lock which will be
                        // released shortly after we receive the query response
                        // In order to guarantee that the temporary read lock is released before the next iteration of this loop we execute
                        // a new query, with this connection, which does not lock the table.
                        connection.prepareStatement("select 1").execute();
                    }
                }
            }
        });
    }

    @Test
    public void testSimpleModeTransaction() throws Exception {
        assertTransaction(true);
    }

    @Test
    public void testSimpleSimpleQuery() throws Exception {
        TestUtils.assertMemoryLeak(() -> {
            try (
                    final PGWireServer ignored = createPGServer(2);
                    final Connection connection = getConnection(true, false)
            ) {
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "select " +
                                "rnd_str(4,4,4) s, " +
                                "rnd_int(0, 256, 4) i, " +
                                "rnd_double(4) d, " +
                                "timestamp_sequence(0,10000) t, " +
                                "rnd_float(4) f, " +
                                "rnd_short() _short, " +
                                "rnd_long(0, 10000000, 5) l, " +
                                "rnd_timestamp(to_timestamp('2015','yyyy'),to_timestamp('2016','yyyy'),2) ts2, " +
                                "rnd_byte(0,127) bb, " +
                                "rnd_boolean() b, " +
                                "rnd_symbol(4,4,4,2), " +
                                "rnd_date(to_date('2015', 'yyyy'), to_date('2016', 'yyyy'), 2)," +
                                "rnd_bin(10,20,2) " +
                                "from long_sequence(50)");

                final String expected = "s[VARCHAR],i[INTEGER],d[DOUBLE],t[TIMESTAMP],f[REAL],_short[SMALLINT],l[BIGINT],ts2[TIMESTAMP],bb[SMALLINT],b[BIT],rnd_symbol[VARCHAR],rnd_date[TIMESTAMP],rnd_bin[BINARY]\n" +
                        "null,57,0.6254021542412018,1970-01-01 00:00:00.0,0.462,-1593,3425232,null,121,false,PEHN,2015-03-17 04:25:52.765,00000000 19 c4 95 94 36 53 49 b4 59 7e 3b 08 a1 1e\n" +
                        "XYSB,142,0.5793466326862211,1970-01-01 00:00:00.01,0.969,20088,1517490,2015-01-17 20:41:19.480685,100,true,PEHN,2015-06-20 01:10:58.599,00000000 79 5f 8b 81 2b 93 4d 1a 8e 78 b5 b9 11 53 d0 fb\n" +
                        "00000010 64\n" +
                        "OZZV,219,0.16381374773748514,1970-01-01 00:00:00.02,0.659,-12303,9489508,2015-08-13 17:10:19.752521,6,false,null,2015-05-20 01:48:37.418,00000000 2b 4d 5f f6 46 90 c3 b3 59 8e e5 61 2f 64 0e\n" +
                        "OLYX,30,0.7133910271555843,1970-01-01 00:00:00.03,0.655,6610,6504428,2015-08-08 00:42:24.545639,123,false,null,2015-01-03 13:53:03.165,null\n" +
                        "TIQB,42,0.6806873134626418,1970-01-01 00:00:00.04,0.626,-1605,8814086,2015-07-28 15:08:53.462495,28,true,CPSW,null,00000000 3b a6 dc 3b 7d 2b e3 92 fe 69 38 e1 77 9a\n" +
                        "LTOV,137,0.7632615004324503,1970-01-01 00:00:00.05,0.882,9054,null,2015-04-20 05:09:03.580574,106,false,PEHN,2015-01-09 06:57:17.512,null\n" +
                        "ZIMN,125,null,1970-01-01 00:00:00.06,null,11524,8335261,2015-10-26 02:10:50.688394,111,true,PEHN,2015-08-21 15:46:32.624,null\n" +
                        "OPJO,168,0.10459352312331183,1970-01-01 00:00:00.07,0.535,-5920,7080704,2015-07-11 09:15:38.342717,103,false,VTJW,null,null\n" +
                        "GLUO,145,0.5391626621794673,1970-01-01 00:00:00.08,0.767,14242,2499922,2015-11-02 09:01:31.312804,84,false,PEHN,2015-11-14 17:37:36.43,null\n" +
                        "ZVQE,103,0.6729405590773638,1970-01-01 00:00:00.09,null,13727,7875846,2015-12-12 13:16:26.134562,22,true,PEHN,2015-01-20 04:50:34.98,00000000 14 33 80 c9 eb a3 67 7a 1a 79 e4 35 e4 3a dc 5c\n" +
                        "00000010 65 ff\n" +
                        "LIGY,199,0.2836347139481469,1970-01-01 00:00:00.1,null,30426,3215562,2015-08-21 14:55:07.055722,11,false,VTJW,null,00000000 ff 70 3a c7 8a b3 14 cd 47 0b 0c 39 12\n" +
                        "MQNT,43,0.5859332388599638,1970-01-01 00:00:00.11,0.335,27019,null,null,27,true,PEHN,2015-07-12 12:59:47.665,00000000 26 fb 2e 42 fa f5 6e 8f 80 e3 54 b8 07 b1 32 57\n" +
                        "00000010 ff 9a ef\n" +
                        "WWCC,213,0.7665029914376952,1970-01-01 00:00:00.12,0.580,13640,4121923,2015-08-06 02:27:30.469762,73,false,PEHN,2015-04-30 08:18:10.453,00000000 71 a7 d5 af 11 96 37 08 dd 98 ef 54 88 2a a2 ad\n" +
                        "00000010 e7 d4\n" +
                        "VFGP,120,0.8402964708129546,1970-01-01 00:00:00.13,0.773,7223,7241423,2015-12-18 07:32:18.456025,43,false,VTJW,null,00000000 24 4e 44 a8 0d fe 27 ec 53 13 5d b2 15 e7 b8 35\n" +
                        "00000010 67\n" +
                        "RMDG,134,0.11047315214793696,1970-01-01 00:00:00.14,0.043,21227,7155708,2015-07-03 04:12:45.774281,42,true,CPSW,2015-02-24 12:10:43.199,null\n" +
                        "WFOQ,255,null,1970-01-01 00:00:00.15,0.116,31569,6688277,2015-05-19 03:30:45.779999,126,true,PEHN,2015-12-09 09:57:17.78,null\n" +
                        "MXDK,56,0.9997797234031688,1970-01-01 00:00:00.16,0.523,-32372,6884132,null,58,false,null,2015-01-20 06:18:18.583,null\n" +
                        "XMKJ,139,0.8405815493567417,1970-01-01 00:00:00.17,0.306,25856,null,2015-05-18 03:50:22.731437,2,true,VTJW,2015-06-25 10:45:01.14,00000000 00 7c fb 01 19 ca f2 bf 84 5a 6f 38 35\n" +
                        "VIHD,null,null,1970-01-01 00:00:00.18,0.550,22280,9109842,2015-01-25 13:51:38.270583,94,false,CPSW,2015-10-27 02:52:19.935,00000000 2d 16 f3 89 a3 83 64 de d6 fd c4 5b c4 e9\n" +
                        "WPNX,null,0.9469700813926907,1970-01-01 00:00:00.19,0.415,-17933,674261,2015-03-04 15:43:15.213686,43,true,HYRX,2015-12-18 21:28:25.325,00000000 b3 4c 0e 8f f1 0c c5 60 b7 d1\n" +
                        "YPOV,36,0.6741248448728824,1970-01-01 00:00:00.2,0.031,-5888,1375423,2015-12-10 20:50:35.866614,3,true,null,2015-07-23 20:17:04.236,00000000 d4 ab be 30 fa 8d ac 3d 98 a0 ad 9a 5d\n" +
                        "NUHN,null,0.6940917925148332,1970-01-01 00:00:00.21,0.339,-25226,3524748,2015-05-07 04:07:18.152968,39,true,VTJW,2015-04-04 15:23:34.13,00000000 b8 be f8 a1 46 87 28 92 a3 9b e3 cb c2 64 8a b0\n" +
                        "00000010 35 d8\n" +
                        "BOSE,240,0.06001827721556019,1970-01-01 00:00:00.22,0.379,23904,9069339,2015-03-21 03:42:42.643186,84,true,null,null,null\n" +
                        "INKG,124,0.8615841627702753,1970-01-01 00:00:00.23,0.404,-30383,7233542,2015-07-21 16:42:47.012148,99,false,null,2015-08-27 17:25:35.308,00000000 87 fc 92 83 fc 88 f3 32 27 70 c8 01 b0 dc c9 3a\n" +
                        "00000010 5b 7e\n" +
                        "FUXC,52,0.7430101994511517,1970-01-01 00:00:00.24,null,-14729,1042064,2015-08-21 02:10:58.949674,28,true,CPSW,2015-08-29 20:15:51.835,null\n" +
                        "UNYQ,71,0.442095410281938,1970-01-01 00:00:00.25,0.539,-22611,null,2015-12-23 18:41:42.319859,98,true,PEHN,2015-01-26 00:55:50.202,00000000 28 ed 97 99 d8 77 33 3f b2 67 da 98 47 47 bf\n" +
                        "KBMQ,null,0.28019218825051395,1970-01-01 00:00:00.26,null,12240,null,2015-08-16 01:02:55.766622,21,false,null,2015-05-19 00:47:18.698,00000000 6a de 46 04 d3 81 e7 a2 16 22 35 3b 1c\n" +
                        "JSOL,243,null,1970-01-01 00:00:00.27,0.068,-17468,null,null,20,true,null,2015-06-19 10:38:54.483,00000000 3d e0 2d 04 86 e7 ca 29 98 07 69 ca 5b d6 cf 09\n" +
                        "00000010 69\n" +
                        "HNSS,150,null,1970-01-01 00:00:00.28,0.148,14841,5992443,null,25,false,PEHN,null,00000000 14 d6 fc ee 03 22 81 b8 06 c4 06 af\n" +
                        "PZPB,101,0.061646717786158045,1970-01-01 00:00:00.29,null,12237,9878179,2015-09-03 22:13:18.852465,79,false,VTJW,2015-12-17 15:12:54.958,00000000 12 61 3a 9a ad 98 2e 75 52 ad 62 87 88 45 b9 9d\n" +
                        "OYNN,25,0.3393509514000247,1970-01-01 00:00:00.3,0.628,22412,4736378,2015-10-10 12:19:42.528224,106,true,CPSW,2015-07-01 00:23:49.789,00000000 54 13 3f ff b6 7e cd 04 27 66 94 89 db\n" +
                        "null,117,0.5638404775663161,1970-01-01 00:00:00.31,null,-5604,6353018,null,84,false,null,null,00000000 2b ad 25 07 db 62 44 33 6e 00 8e\n" +
                        "HVRI,233,0.22407665790705777,1970-01-01 00:00:00.32,0.425,10469,1715213,null,86,false,null,2015-02-02 05:48:17.373,null\n" +
                        "OYTO,96,0.7407581616916364,1970-01-01 00:00:00.33,0.528,-12239,3499620,2015-02-07 22:35:03.212268,17,false,PEHN,2015-03-29 12:55:11.682,null\n" +
                        "LFCY,63,0.7217315729790722,1970-01-01 00:00:00.34,null,23344,9523982,null,123,false,CPSW,2015-05-18 04:35:27.228,00000000 05 e5 c0 4e cc d6 e3 7b 34 cd 15 35 bb a4\n" +
                        "GHLX,148,0.3057937704964272,1970-01-01 00:00:00.35,0.636,-31457,2322337,2015-10-22 12:06:05.544701,91,true,HYRX,2015-05-21 09:33:18.158,00000000 57 1d 91 72 30 04 b7 02 cb 03\n" +
                        "YTSZ,123,null,1970-01-01 00:00:00.36,0.519,22534,4446236,2015-07-27 07:23:37.233711,53,false,CPSW,2015-01-13 04:37:10.36,null\n" +
                        "SWLU,251,null,1970-01-01 00:00:00.37,0.179,7734,4082475,2015-10-21 18:24:34.400345,69,false,PEHN,2015-04-01 14:33:42.5,null\n" +
                        "TQJL,245,null,1970-01-01 00:00:00.38,0.865,9516,929340,2015-05-28 04:18:18.640567,69,false,VTJW,2015-06-12 20:12:28.881,00000000 6c 3e 51 d7 eb b1 07 71 32 1f af 40 4e 8c 47\n" +
                        "REIJ,94,null,1970-01-01 00:00:00.39,0.130,-29924,null,2015-03-20 22:14:46.204718,113,true,HYRX,2015-12-19 13:58:41.819,null\n" +
                        "HDHQ,94,0.7234181773407536,1970-01-01 00:00:00.4,0.730,19970,654131,2015-01-10 22:56:08.48045,84,true,null,2015-03-05 17:14:48.275,00000000 4f 56 6b 65 a4 53 38 e9 cd c1 a7 ee 86 75 ad a5\n" +
                        "00000010 2d 49\n" +
                        "UMEU,40,0.008444033230580739,1970-01-01 00:00:00.41,0.805,-11623,4599862,2015-11-20 04:02:44.335947,76,false,PEHN,2015-05-17 17:33:20.922,null\n" +
                        "YJIH,184,null,1970-01-01 00:00:00.42,0.383,17614,3101671,2015-01-28 12:05:46.683001,105,true,null,2015-12-07 19:24:36.838,00000000 ec 69 cd 73 bb 9b c5 95 db 61 91 ce\n" +
                        "CYXG,27,0.2917796053045747,1970-01-01 00:00:00.43,0.953,3944,249165,null,67,true,null,2015-03-02 08:19:44.566,00000000 01 48 15 3e 0c 7f 3f 8f e4 b5 ab 34 21 29\n" +
                        "MRTG,143,0.02632531361499113,1970-01-01 00:00:00.44,0.943,-27320,1667842,2015-01-24 19:56:15.973109,11,false,null,2015-01-24 07:15:02.772,null\n" +
                        "DONP,246,0.654226248740447,1970-01-01 00:00:00.45,0.556,27477,4160018,2015-12-14 03:40:05.911839,20,true,PEHN,2015-10-29 14:35:10.167,00000000 07 92 01 f5 6a a1 31 cd cb c2 a2 b4 8e 99\n" +
                        "IQXS,232,0.23075700218038853,1970-01-01 00:00:00.46,0.049,-18113,4005228,2015-06-11 13:00:07.248188,8,true,CPSW,2015-08-16 11:09:24.311,00000000 fa 1f 92 24 b1 b8 67 65 08 b7 f8 41 00\n" +
                        "null,178,null,1970-01-01 00:00:00.47,0.903,-14626,2934570,2015-04-04 08:51:54.068154,88,true,null,2015-07-01 04:32:23.83,00000000 84 36 25 63 2b 63 61 43 1c 47 7d b6 46 ba bb 98\n" +
                        "00000010 ca 08 be a4\n" +
                        "HUWZ,94,0.110401374979613,1970-01-01 00:00:00.48,0.420,-3736,5687514,2015-01-02 17:18:05.627633,74,false,null,2015-03-29 06:39:11.642,null\n" +
                        "SRED,66,0.11274667140915928,1970-01-01 00:00:00.49,0.060,-10543,3669377,2015-10-22 02:53:02.381351,77,true,PEHN,null,00000000 7c 3f d6 88 3a 93 ef 24 a5 e2 bc\n";

                StringSink sink = new StringSink();

                // dump metadata
                assertResultSet(expected, sink, rs);
            }
        });
    }

    @Test
    public void testSimpleSyntaxErrorReporting() throws Exception {
        testSyntaxErrorReporting(true);
    }

    @Test
    public void testSingleInClause() throws Exception {
        TestUtils.assertMemoryLeak(() -> {
            try (final PGWireServer ignored = createPGServer(1)) {
                try (final Connection connection = getConnection(false, false)) {
                    try (PreparedStatement statement = connection.prepareStatement(createDatesTblStmt)) {
                        statement.execute();
                    }

                    try (PreparedStatement statement = connection.prepareStatement("select ts FROM xts WHERE ts in ?")) {
                        sink.clear();
                        String date = "1970-01-01";
                        statement.setString(1, date);
                        statement.executeQuery();
                        try (ResultSet rs = statement.executeQuery()) {
                            String expected = datesArr.stream()
                                    .filter(arr -> (long) arr[0] < Timestamps.HOUR_MICROS * 24)
                                    .map(arr -> arr[1] + "\n")
                                    .collect(Collectors.joining());

                            assertResultSet("ts[TIMESTAMP]\n" + expected, sink, rs);
                        }
                    }


                    // NOT IN
                    try (PreparedStatement statement = connection.prepareStatement("select ts FROM xts WHERE ts not in ?")) {
                        sink.clear();
                        String date = "1970-01-01";
                        statement.setString(1, date);
                        statement.executeQuery();
                        try (ResultSet rs = statement.executeQuery()) {
                            String expected = datesArr.stream()
                                    .filter(arr -> (long) arr[0] >= Timestamps.HOUR_MICROS * 24)
                                    .map(arr -> arr[1] + "\n")
                                    .collect(Collectors.joining());

                            assertResultSet("ts[TIMESTAMP]\n" + expected, sink, rs);
                        }
                    }

                    // IN NULL
                    try (PreparedStatement statement = connection.prepareStatement("select ts FROM xts WHERE ts in ?")) {
                        sink.clear();
                        statement.setString(1, null);
                        statement.executeQuery();
                        try (ResultSet rs = statement.executeQuery()) {
                            String expected = "";
                            assertResultSet("ts[TIMESTAMP]\n" + expected, sink, rs);
                        }
                    }

                    // NOT IN NULL
                    try (PreparedStatement statement = connection.prepareStatement("select ts FROM xts WHERE ts not in ?")) {
                        sink.clear();
                        statement.setString(1, null);
                        statement.executeQuery();
                        try (ResultSet rs = statement.executeQuery()) {
                            String expected = datesArr.stream()
                                    .map(arr -> arr[1] + "\n")
                                    .collect(Collectors.joining());

                            assertResultSet("ts[TIMESTAMP]\n" + expected, sink, rs);
                        }
                    }

                    // NULL in not null
                    try (PreparedStatement statement = connection.prepareStatement("select ts FROM xts WHERE cast(NULL as TIMESTAMP) in ?")) {
                        sink.clear();
                        String date = "1970-01-01";
                        statement.setString(1, date);
                        statement.executeQuery();
                        try (ResultSet rs = statement.executeQuery()) {
                            String expected = "";
                            assertResultSet("ts[TIMESTAMP]\n" + expected, sink, rs);
                        }
                    }

                    try (PreparedStatement statement = connection.prepareStatement("drop table xts")) {
                        statement.execute();
                    }
                }
            }
        });
    }

    @Test
    public void testSingleInClauseNonDedicatedTimestamp() throws Exception {
        TestUtils.assertMemoryLeak(() -> {
            try (final PGWireServer ignored = createPGServer(1)) {
                try (final Connection connection = getConnection(false, false)) {
                    try (PreparedStatement statement = connection.prepareStatement(
                            "create table xts as (select timestamp_sequence(0, 3600L * 1000 * 1000) ts from long_sequence(" + count + "))")) {
                        statement.execute();
                    }

                    try (PreparedStatement statement = connection.prepareStatement("select ts FROM xts WHERE ts in ?")) {
                        sink.clear();
                        String date = "1970-01-01";
                        statement.setString(1, date);
                        statement.executeQuery();
                        try (ResultSet rs = statement.executeQuery()) {
                            String expected = datesArr.stream()
                                    .filter(arr -> (long) arr[0] < Timestamps.HOUR_MICROS * 24)
                                    .map(arr -> arr[1] + "\n")
                                    .collect(Collectors.joining());

                            assertResultSet("ts[TIMESTAMP]\n" + expected, sink, rs);
                        }
                    }

                    // NOT IN
                    try (PreparedStatement statement = connection.prepareStatement("select ts FROM xts WHERE ts not in ?")) {
                        sink.clear();
                        String date = "1970-01-01";
                        statement.setString(1, date);
                        statement.executeQuery();
                        try (ResultSet rs = statement.executeQuery()) {
                            String expected = datesArr.stream()
                                    .filter(arr -> (long) arr[0] >= Timestamps.HOUR_MICROS * 24)
                                    .map(arr -> arr[1] + "\n")
                                    .collect(Collectors.joining());

                            assertResultSet("ts[TIMESTAMP]\n" + expected, sink, rs);
                        }
                    }

                    // IN NULL
                    try (PreparedStatement statement = connection.prepareStatement("select ts FROM xts WHERE ts in ?")) {
                        sink.clear();
                        statement.setString(1, null);
                        statement.executeQuery();
                        try (ResultSet rs = statement.executeQuery()) {
                            String expected = "";
                            assertResultSet("ts[TIMESTAMP]\n" + expected, sink, rs);
                        }
                    }

                    // NOT IN NULL
                    try (PreparedStatement statement = connection.prepareStatement("select ts FROM xts WHERE ts not in ?")) {
                        sink.clear();
                        statement.setString(1, null);
                        statement.executeQuery();
                        try (ResultSet rs = statement.executeQuery()) {
                            String expected = datesArr.stream()
                                    .map(arr -> arr[1] + "\n")
                                    .collect(Collectors.joining());

                            assertResultSet("ts[TIMESTAMP]\n" + expected, sink, rs);
                        }
                    }

                    // NULL in not null
                    try (PreparedStatement statement = connection.prepareStatement("select ts FROM xts WHERE cast(NULL as TIMESTAMP) in ?")) {
                        sink.clear();
                        String date = "1970-01-01";
                        statement.setString(1, date);
                        statement.executeQuery();
                        try (ResultSet rs = statement.executeQuery()) {
                            String expected = "";
                            assertResultSet("ts[TIMESTAMP]\n" + expected, sink, rs);
                        }
                    }

                    try (PreparedStatement statement = connection.prepareStatement("drop table xts")) {
                        statement.execute();
                    }
                }
            }
        });
    }

    @Test
    public void testSlowClient() throws Exception {
        assertMemoryLeak(() -> {
            DelayingNetworkFacade nf = new DelayingNetworkFacade();
            PGWireConfiguration configuration = new DefaultPGWireConfiguration() {
                @Override
                public NetworkFacade getNetworkFacade() {
                    return nf;
                }

                @Override
                public int getSendBufferSize() {
                    return 1024;
                }
            };

            try (
                    PGWireServer ignored = createPGServer(configuration);
                    Connection connection = getConnection(false, true);
                    Statement statement = connection.createStatement()
            ) {
                String sql = "SELECT * FROM long_sequence(100) x";

                nf.startDelaying();

                boolean hasResultSet = statement.execute(sql);
                // Temporary log showing a value of hasResultSet, as it is currently impossible to stop the server and complete the test.
                LOG.info().$("hasResultSet=").$(hasResultSet).$();
                Assert.assertTrue(hasResultSet);
            }
        });
    }

    @Test
    public void testSlowClient2() throws Exception {
        assertMemoryLeak(() -> {
            DelayingNetworkFacade nf = new DelayingNetworkFacade();
            PGWireConfiguration configuration = new DefaultPGWireConfiguration() {
                @Override
                public NetworkFacade getNetworkFacade() {
                    return nf;
                }
            };

            try (
                    PGWireServer ignored = createPGServer(configuration);
                    Connection connection = getConnection(false, true);
                    Statement statement = connection.createStatement()
            ) {
                statement.executeUpdate("CREATE TABLE sensors (ID LONG, make STRING, city STRING)");
                statement.executeUpdate("INSERT INTO sensors\n" +
                        "    SELECT\n" +
                        "        x ID, \n" +
                        "        rnd_str('Eberle', 'Honeywell', 'Omron', 'United Automation', 'RS Pro') make,\n" +
                        "        rnd_str('New York', 'Miami', 'Boston', 'Chicago', 'San Francisco') city\n" +
                        "    FROM long_sequence(10000) x");
                statement.executeUpdate("CREATE TABLE readings\n" +
                        "AS(\n" +
                        "    SELECT\n" +
                        "        x ID,\n" +
                        "        timestamp_sequence(to_timestamp('2019-10-17T00:00:00', 'yyyy-MM-ddTHH:mm:ss'), rnd_long(1,10,0) * 100000L) ts,\n" +
                        "        rnd_double(0)*8 + 15 temp,\n" +
                        "        rnd_long(0, 10000, 0) sensorId\n" +
                        "    FROM long_sequence(10000) x)\n" +
                        "TIMESTAMP(ts)\n" +
                        "PARTITION BY MONTH");

                String sql = "SELECT *\n" +
                        "FROM readings\n" +
                        "JOIN(\n" +
                        "    SELECT ID sensId, make, city\n" +
                        "    FROM sensors)\n" +
                        "ON readings.sensorId = sensId";

                nf.startDelaying();

                boolean hasResultSet = statement.execute(sql);
                // Temporary log showing a value of hasResultSet, as it is currently impossible to stop the server and complete the test.
                LOG.info().$("hasResultSet=").$(hasResultSet).$();
                Assert.assertTrue(hasResultSet);
            }
        });
    }

    @Test
    public void testSmallSendBufferForRowData() throws Exception {
        assertMemoryLeak(() -> {

            PGWireConfiguration configuration = new DefaultPGWireConfiguration() {
                @Override
                public int getSendBufferSize() {
                    return 300;
                }
            };

            try (
                    PGWireServer ignored = createPGServer(configuration);
                    Connection connection = getConnection(false, true);
                    Statement statement = connection.createStatement()
            ) {
                statement.executeUpdate("create table x as (" +
                        "select" +
                        " rnd_str(5,16,2) i," +
                        " rnd_str(5,16,2) sym," +
                        " rnd_str(5,16,2) amt," +
                        " rnd_str(5,16,2) timestamp," +
                        " rnd_str(5,16,2) b," +
                        " rnd_str('ABC', 'CDE', null, 'XYZ') c," +
                        " rnd_str(5,16,2) d," +
                        " rnd_str(5,16,2) e," +
                        " rnd_str(300,300,2) f," + // <-- really long string
                        " rnd_str(5,16,2) g," +
                        " rnd_str(5,16,2) ik," +
                        " rnd_str(5,16,2) j," +
                        " timestamp_sequence(500000000000L,100000000L) ts," +
                        " rnd_str(5,16,2) l," +
                        " rnd_str(5,16,2) m," +
                        " rnd_str(5,16,2) n," +
                        " rnd_str(5,16,2) t," +
                        " rnd_str(5,16,2) l256" +
                        " from long_sequence(10000)" +
                        ") timestamp (ts) partition by DAY");
                String sql = "SELECT * FROM x";

                try {
                    statement.execute(sql);
                    Assert.fail();
                } catch (SQLException e) {
                    TestUtils.assertContains(e.getMessage(), "not enough space in send buffer for row data");
                }
            }
        });
    }

    @Test
    public void testSmallSendBufferForRowDescription() throws Exception {
        assertMemoryLeak(() -> {

            PGWireConfiguration configuration = new DefaultPGWireConfiguration() {
                @Override
                public int getSendBufferSize() {
                    return 256;
                }
            };

            try (
                    PGWireServer ignored = createPGServer(configuration);
                    Connection connection = getConnection(false, true);
                    Statement statement = connection.createStatement()
            ) {
                statement.executeUpdate("create table x as (" +
                        "select" +
                        " rnd_str(5,16,2) i," +
                        " rnd_str(5,16,2) sym," +
                        " rnd_str(5,16,2) amt," +
                        " rnd_str(5,16,2) timestamp," +
                        " rnd_str(5,16,2) b," +
                        " rnd_str('ABC', 'CDE', null, 'XYZ') c," +
                        " rnd_str(5,16,2) d," +
                        " rnd_str(5,16,2) e," +
                        " rnd_str(5,16,2) f," +
                        " rnd_str(5,16,2) g," +
                        " rnd_str(5,16,2) ik," +
                        " rnd_str(5,16,2) j," +
                        " timestamp_sequence(500000000000L,100000000L) ts," +
                        " rnd_str(5,16,2) l," +
                        " rnd_str(5,16,2) m," +
                        " rnd_str(5,16,2) n," +
                        " rnd_str(5,16,2) t," +
                        " rnd_str(5,16,2) l256" +
                        " from long_sequence(10000)" +
                        ") timestamp (ts) partition by DAY");
                String sql = "SELECT * FROM x";

                try {
                    statement.execute(sql);
                    Assert.fail();
                } catch (SQLException e) {
                    TestUtils.assertContains(e.getMessage(), "not enough space in send buffer for row description");
                }
            }
        });
    }

    @Test
    public void testStaleQueryCacheOnTableDroppedNonSimple() throws Exception {
        testStaleQueryCacheOnTableDropped(false);
    }

    @Test
    public void testStaleQueryCacheOnTableDroppedSimple() throws Exception {
        testStaleQueryCacheOnTableDropped(true);
    }

    @Test
    public void testSymbolBindVariableInFilterBinaryTransfer() throws Exception {
        testSymbolBindVariableInFilter(true);
    }

    @Test
    public void testSymbolBindVariableInFilterStringTransfer() throws Exception {
        testSymbolBindVariableInFilter(false);
    }

    @Test
    public void testSyntaxErrorSimple() throws Exception {
        assertMemoryLeak(() -> {
            try (
                    final PGWireServer ignored = createPGServer(4);
                    final Connection connection = getConnection(false, true)
            ) {
                // column does not exits
                connection.prepareStatement("select x2 from long_sequence(5)").execute();
                Assert.fail();
            } catch (SQLException e) {
                TestUtils.assertContains(e.getMessage(), "Invalid column: x2");
                TestUtils.assertEquals("00000", e.getSQLState());
            }
        });
    }

    /*
    We want to ensure that tableoid is set to zero, otherwise squirrelSql will not display the result set.
     */
    @Test
    public void testThatTableOidIsSetToZero() throws Exception {
        assertMemoryLeak(() -> {
            try (
                    final PGWireServer ignored = createPGServer(2);
                    final Connection connection = getConnection(false, false);
                    final PreparedStatement statement = connection.prepareStatement("select 1,2,3 from long_sequence(1)");
                    final ResultSet rs = statement.executeQuery()
            ) {
                assertTrue(((PGResultSetMetaData) rs.getMetaData()).getBaseColumnName(1).isEmpty()); // getBaseColumnName returns "" if tableOid is zero
            }
        });
    }

    @Test
    public void testTimestamp() throws Exception {
        TestUtils.assertMemoryLeak(() -> {
            try (final PGWireServer ignored = createPGServer(1)) {
                try (final Connection connection = getConnection(false, true)) {

                    connection.setAutoCommit(false);
                    connection.prepareStatement("CREATE TABLE ts (id INT, ts TIMESTAMP) TIMESTAMP(ts) PARTITION BY MONTH").execute();
                    connection.prepareStatement("INSERT INTO ts VALUES(0, '2021-09-27T16:45:03.202345Z')").execute();
                    connection.commit();
                    connection.setAutoCommit(true);

                    // select the timestamp that we just inserted
                    Timestamp ts;
                    try (PreparedStatement statement = connection.prepareStatement("SELECT ts FROM ts")) {
                        try (ResultSet rs = statement.executeQuery()) {
                            assertTrue(rs.next());
                            ts = rs.getTimestamp("ts");
                        }
                    }

                    // NOTE: java.sql.Timestamp takes milliseconds from epoch as constructor parameter,
                    // which is processed and stored internally coupling ts.getTime() and ts.getNanos():
                    //   - ts.getTime(): the last 3 digits account for millisecond precision, e.g. 1632761103202L -> 202 milliseconds.
                    //   - ts.getNanos(): the first 3 digits match the last 3 digits from ts.getTime(), then
                    //         3 more digits follow for micros, and 3 more for nanos,, e.g. 202345000 -> (202)milli(345)micro(000)nano
                    assertEquals(1632761103202L, ts.getTime());
                    assertEquals(202345000, ts.getNanos());
                    assertEquals("2021-09-27 16:45:03.202345", ts.toString());

                    sink.clear();
                    try (PreparedStatement ps = connection.prepareStatement("INSERT INTO ts VALUES (?, ?)")) {
                        int rowId = 1;

                        // Case 1: insert timestamp as we selected it, no modifications
                        // -> microsecond precision is kept
                        ps.setInt(1, rowId++);
                        ps.setTimestamp(2, ts);
                        ps.execute();

                        // Case 2: we create a timestamp from another, but there is a catch, we must set the nanos too
                        // -> microsecond precision is kept
                        Timestamp aTs = new Timestamp(ts.getTime());
                        aTs.setNanos(ts.getNanos());
                        ps.setInt(1, rowId++);
                        ps.setTimestamp(2, aTs);
                        ps.execute();

                        // Case 3: we create a timestamp from another, and clear the micro precision
                        // -> microsecond precision is dropped by us
                        Timestamp bTs = new Timestamp(ts.getTime() * 1000);
                        bTs.setNanos(202000000);
                        ps.setInt(1, rowId++);
                        ps.setTimestamp(2, bTs);
                        ps.execute();

                        // Case 4: if we forget to setNanos, we get a broken timestamp
                        // -> this results in a broken timestamp 1970-...
                        Timestamp kaputTs = new Timestamp(ts.getTime());
                        ps.setInt(1, rowId++);
                        ps.setTimestamp(2, kaputTs);
                        ps.execute();

                        // Case 4: if we setNanos to 0, we also get a broken timestamp! UNLESS we scale up time
                        // to trick the constructor
                        // -> microsecond precision is dropped by us, we keep millisecond precision
                        Timestamp cTs = new Timestamp(ts.getTime() * 1000);
                        cTs.setNanos(0); // <=== THIS requires ---- ^ ^
                        ps.setInt(1, rowId++);
                        ps.setTimestamp(2, cTs);
                        ps.execute();

                        // Case 5: we use space-age mathematics to produce a long number which is
                        // equivalent to a QuestDB timestamp WITH MICROSECOND precision, and then
                        // we can feed it to java.sql.Timestamp without worrying for setNanos.
                        // -> microsecond precision is lost in this case [*]
                        long epochMicroNoMillis = (ts.getTime() / 1000) * 1000000;
                        long actualTimestamp = epochMicroNoMillis + (ts.getNanos() / 1000);
                        actualTimestamp = (actualTimestamp / 1000) * 1000; // [*] drop micros
                        Timestamp dTs = new Timestamp(actualTimestamp);
                        ps.setInt(1, rowId++);
                        ps.setTimestamp(2, dTs);
                        ps.execute();

                        // Case 6: the complementary approach to Case 5, where we take a QuestDB
                        // timestamp WITH microsecond precision and we massage it to extract two
                        // numbers that can be used to create a java.sql.Timestamp.
                        // -> microsecond precision is kept
                        long questdbTs = TimestampFormatUtils.parseTimestamp("2021-09-27T16:45:03.202345Z");
                        long time = questdbTs / 1000;
                        int nanos = (int) (questdbTs - (int) (questdbTs / 1e6) * 1e6) * 1000;
                        assertEquals(1632761103202345L, questdbTs);
                        assertEquals(1632761103202L, time);
                        assertEquals(202345000, nanos);
                        Timestamp eTs = new Timestamp(time);
                        eTs.setNanos(nanos);
                        ps.setInt(1, rowId);
                        ps.setTimestamp(2, eTs);
                        ps.execute();
                    }

                    try (PreparedStatement statement = connection.prepareStatement("SELECT id as Case, ts FROM ts ORDER BY id ASC")) {
                        sink.clear();
                        try (ResultSet rs = statement.executeQuery()) {
                            assertResultSet(
                                    "Case[INTEGER],ts[TIMESTAMP]\n" +
                                            "0,2021-09-27 16:45:03.202345\n" +
                                            "1,2021-09-27 16:45:03.202345\n" +
                                            "2,2021-09-27 16:45:03.202345\n" +
                                            "3,2021-09-27 16:45:03.202202\n" +
                                            "4,1970-01-19 21:32:41.103202\n" +
                                            "5,2021-09-27 16:45:03.202\n" +
                                            "6,2021-09-27 16:45:03.202\n" +
                                            "7,2021-09-27 16:45:03.202345\n",
                                    sink,
                                    rs
                            );
                        }
                    }
                    connection.prepareStatement("drop table ts").execute();
                }
            }
        });
    }

    @Test
    public void testTimestampSentEqualsReceived() throws Exception {
        TestUtils.assertMemoryLeak(() -> {

            final Timestamp expectedTs = new Timestamp(1632761103202L); // '2021-09-27T16:45:03.202000Z'
            assertEquals(1632761103202L, expectedTs.getTime());
            assertEquals(202000000, expectedTs.getNanos());

            try (final PGWireServer ignored = createPGServer(1)) {
                try (final Connection conn = getConnection(false, true)) {
                    conn.setAutoCommit(false);
                    conn.prepareStatement("CREATE TABLE ts (ts TIMESTAMP) TIMESTAMP(ts) PARTITION BY MONTH").execute();
                    conn.commit();
                    conn.setAutoCommit(true);

                    // insert
                    final Timestamp ts = Timestamp.valueOf("2021-09-27 16:45:03.202");
                    assertEquals(expectedTs.getTime(), ts.getTime());
                    assertEquals(expectedTs.getNanos(), ts.getNanos());
                    try (PreparedStatement insert = conn.prepareStatement("INSERT INTO ts VALUES (?)")) {
                        // QuestDB timestamps have MICROSECOND precision and require you to be aware
                        // of it if you use java.sql.Timestamp's constructor
                        insert.setTimestamp(1, new Timestamp(ts.getTime() * 1000));
                        insert.execute();
                    }

                    // select
                    final Timestamp tsBack;
                    try (ResultSet queryResult = conn.prepareStatement("SELECT * FROM ts").executeQuery()) {
                        queryResult.next();
                        tsBack = queryResult.getTimestamp("ts");
                    }
                    assertEquals(expectedTs.getTime(), tsBack.getTime());
                    assertEquals(expectedTs.getNanos(), tsBack.getNanos());
                    assertEquals(expectedTs, tsBack);

                    // cleanup
                    conn.prepareStatement("drop table ts").execute();
                }
            }
        });
    }

    @Test
    public void testUnsupportedParameterType() throws Exception {
        TestUtils.assertMemoryLeak(() -> {
            try (
                    final PGWireServer ignored = createPGServer(2);
                    final Connection connection = getConnection(false, false);
                    final PreparedStatement statement = connection.prepareStatement("select x, ? from long_sequence(5)")
            ) {
                // TIME is passed over protocol as UNSPECIFIED type
                // it will rely on date parser to work out what it is
                // for now date parser does not parse just time, it could i guess if required.
                statement.setTime(1, new Time(100L));

                try (ResultSet rs = statement.executeQuery()) {
                    StringSink sink = new StringSink();
                    // dump metadata
                    assertResultSet(
                            "x[BIGINT],$1[VARCHAR]\n" +
                                    "1,00:00:00.1+00\n" +
                                    "2,00:00:00.1+00\n" +
                                    "3,00:00:00.1+00\n" +
                                    "4,00:00:00.1+00\n" +
                                    "5,00:00:00.1+00\n",
                            sink,
                            rs
                    );
                }
            }
        });
    }

    @Test
    public void testUpdateAsync() throws Exception {
        testUpdateAsync(null, writer -> {},
                "a[BIGINT],b[DOUBLE],ts[TIMESTAMP]\n" +
                "1,2.0,2020-06-01 00:00:02.0\n" +
                "9,2.6,2020-06-01 00:00:06.0\n" +
                "9,3.0,2020-06-01 00:00:12.0\n");
    }

    @Test
    public void testUpdateAsyncWithReaderOutOfDateException() throws Exception {
        SOCountDownLatch queryScheduledCount = new SOCountDownLatch(1);
        testUpdateAsync(queryScheduledCount, new OnTickAction() {
            private boolean first = true;

            @Override
            public void run(TableWriter writer) {
                if (first) {
                    queryScheduledCount.await();
                    // adding a new column before calling writer.tick() will result in ReaderOutOfDateException
                    // thrown from UpdateOperator as this changes table structure
                    // recompile should be successful so the UPDATE completes
                    writer.addColumn("newCol", ColumnType.INT);
                    first = false;
                }
            }
        },
        "a[BIGINT],b[DOUBLE],ts[TIMESTAMP],newCol[INTEGER]\n" +
        "1,2.0,2020-06-01 00:00:02.0,null\n" +
        "9,2.6,2020-06-01 00:00:06.0,null\n" +
        "9,3.0,2020-06-01 00:00:12.0,null\n");
    }

    private void testUpdateAsync(SOCountDownLatch queryScheduledCount, OnTickAction onTick, String expected) throws Exception {
        assertMemoryLeak(() -> {
            try (
                    final PGWireServer ignored = createPGServer(queryScheduledCount);
                    final Connection connection = getConnection(true, false)
            ) {
                final PreparedStatement statement = connection.prepareStatement("create table x (a long, b double, ts timestamp) timestamp(ts)");
                statement.execute();

                final PreparedStatement insert1 = connection.prepareStatement("insert into x values " +
                        "(1, 2.0, '2020-06-01T00:00:02'::timestamp)," +
                        "(2, 2.6, '2020-06-01T00:00:06'::timestamp)," +
                        "(5, 3.0, '2020-06-01T00:00:12'::timestamp)");
                insert1.execute();

                try (TableWriter writer = engine.getWriter(AllowAllCairoSecurityContext.INSTANCE, "x", "test lock")) {
                    SOCountDownLatch finished = new SOCountDownLatch(1);
                    new Thread(() -> {
                        try {
                            final PreparedStatement update1 = connection.prepareStatement("update x set a=9 where b>2.5");
                            int numOfRowsUpdated1 = update1.executeUpdate();
                            assertEquals(2, numOfRowsUpdated1);
                        } catch (Throwable e) {
                            Assert.fail(e.getMessage());
                            e.printStackTrace();
                        } finally {
                            finished.countDown();
                        }
                    }).start();

                    MicrosecondClock microsecondClock = engine.getConfiguration().getMicrosecondClock();
                    long startTimeMicro = microsecondClock.getTicks();
                    // Wait 1 min max for completion
                    while (microsecondClock.getTicks() - startTimeMicro < 60_000_000 && finished.getCount() > 0) {
                        onTick.run(writer);
                        writer.tick(true);
                        finished.await(500_000);
                    }
                }

                try (ResultSet resultSet = connection.prepareStatement("x").executeQuery()) {
                    sink.clear();
                    assertResultSet(expected, sink, resultSet);
                }
            }
        });
    }

    private PGWireServer createPGServer(SOCountDownLatch queryScheduledCount) {
        int workerCount = 2;

        final PGWireConfiguration conf = new DefaultPGWireConfiguration() {
            @Override
            public Rnd getRandom() {
                return new Rnd();
            }

            @Override
            public int[] getWorkerAffinity() {
                return TestUtils.getWorkerAffinity(getWorkerCount());
            }

            @Override
            public int getWorkerCount() {
                return workerCount;
            }
        };

        WorkerPool pool = new WorkerPool(conf, metrics);

        final PGWireServer pgWireServer = PGWireServer.create(
                conf,
                pool,
                LOG,
                engine,
                compiler.getFunctionFactoryCache(),
                snapshotAgent,
                metrics,
                createPGConnectionContextFactory(conf, workerCount, null, queryScheduledCount)
        );

        pool.start(LOG);
        return pgWireServer;
    }

    @Test
    public void testUpdate() throws Exception {
        assertMemoryLeak(() -> {
            try (
                    final PGWireServer ignored = createPGServer(1);
                    final Connection connection = getConnection(true, false)
            ) {
                final PreparedStatement statement = connection.prepareStatement("create table x (a long, b double, ts timestamp) timestamp(ts)");
                statement.execute();

                final PreparedStatement insert1 = connection.prepareStatement("insert into x values " +
                        "(1, 2.0, '2020-06-01T00:00:02'::timestamp)," +
                        "(2, 2.6, '2020-06-01T00:00:06'::timestamp)," +
                        "(5, 3.0, '2020-06-01T00:00:12'::timestamp)");
                insert1.execute();

                final PreparedStatement update1 = connection.prepareStatement("update x set a=9 where b>2.5");
                int numOfRowsUpdated1 = update1.executeUpdate();
                assertEquals(2, numOfRowsUpdated1);

                final PreparedStatement insert2 = connection.prepareStatement("insert into x values " +
                        "(8, 4.0, '2020-06-01T00:00:22'::timestamp)," +
                        "(10, 6.0, '2020-06-01T00:00:32'::timestamp)");
                insert2.execute();

                final PreparedStatement update2 = connection.prepareStatement("update x set a=7 where b>5.0");
                int numOfRowsUpdated2 = update2.executeUpdate();
                assertEquals(1, numOfRowsUpdated2);

                final String expected = "a[BIGINT],b[DOUBLE],ts[TIMESTAMP]\n" +
                        "1,2.0,2020-06-01 00:00:02.0\n" +
                        "9,2.6,2020-06-01 00:00:06.0\n" +
                        "9,3.0,2020-06-01 00:00:12.0\n" +
                        "8,4.0,2020-06-01 00:00:22.0\n" +
                        "7,6.0,2020-06-01 00:00:32.0\n";
                try (ResultSet resultSet = connection.prepareStatement("x").executeQuery()) {
                    sink.clear();
                    assertResultSet(expected, sink, resultSet);
                }
            }
        });
    }

    @Test
    public void testUpdateBatch() throws Exception {
        assertMemoryLeak(() -> {
            try (
                    final PGWireServer ignored = createPGServer(2);
                    final Connection connection = getConnection(true, false)
            ) {
                final PreparedStatement statement = connection.prepareStatement("create table x (a long, b double, ts timestamp) timestamp(ts)");
                statement.execute();

                final PreparedStatement insert1 = connection.prepareStatement("insert into x values " +
                        "(1, 2.0, '2020-06-01T00:00:02'::timestamp)," +
                        "(2, 2.6, '2020-06-01T00:00:06'::timestamp)," +
                        "(5, 3.0, '2020-06-01T00:00:12'::timestamp)");
                insert1.execute();

                final PreparedStatement update1 = connection.prepareStatement("update x set a=9 where b>2.5; update x set a=3 where b>2.7; update x set a=2 where b<2.2");
                int numOfRowsUpdated1 = update1.executeUpdate();
                assertEquals(2, numOfRowsUpdated1);

                final PreparedStatement insert2 = connection.prepareStatement("insert into x values " +
                        "(8, 4.0, '2020-06-01T00:00:22'::timestamp)," +
                        "(10, 6.0, '2020-06-01T00:00:32'::timestamp)");
                insert2.execute();

                final PreparedStatement update2 = connection.prepareStatement("update x set a=7 where b>5.0; update x set a=6 where a=2");
                int numOfRowsUpdated2 = update2.executeUpdate();
                assertEquals(1, numOfRowsUpdated2);

                final String expected = "a[BIGINT],b[DOUBLE],ts[TIMESTAMP]\n" +
                        "6,2.0,2020-06-01 00:00:02.0\n" +
                        "9,2.6,2020-06-01 00:00:06.0\n" +
                        "3,3.0,2020-06-01 00:00:12.0\n" +
                        "8,4.0,2020-06-01 00:00:22.0\n" +
                        "7,6.0,2020-06-01 00:00:32.0\n";
                try (ResultSet resultSet = connection.prepareStatement("x").executeQuery()) {
                    sink.clear();
                    assertResultSet(expected, sink, resultSet);
                }
            }
        });
    }

    @Test
    public void testUpdateNoAutoCommit() throws Exception {
        assertMemoryLeak(() -> {
            try (
                    final PGWireServer ignored = createPGServer(1);
                    final Connection connection = getConnection(false, true)
            ) {
                connection.setAutoCommit(false);

                PreparedStatement tbl = connection.prepareStatement("create table x (a int, b int, ts timestamp) timestamp(ts)");
                tbl.execute();

                PreparedStatement insert = connection.prepareStatement("insert into x values(?, ?, '2022-03-17T00:00:00'::timestamp)");
                for (int i = 0; i < 10; i++) {
                    insert.setInt(1, i);
                    insert.setInt(2, i + 100);
                    insert.execute();
                }

                PreparedStatement updateB = connection.prepareStatement("update x set b=? where a=?");
                for (int i = 0; i < 10; i++) {
                    updateB.setInt(1, i + 10);
                    updateB.setInt(2, i);
                    updateB.execute();
                }

                for (int i = 10; i < 15; i++) {
                    insert.setInt(1, i);
                    insert.setInt(2, i + 100);
                    insert.execute();
                }

                PreparedStatement updateA = connection.prepareStatement("update x set a=? where a=?");
                for (int i = 10; i < 15; i++) {
                    updateA.setInt(1, i + 10);
                    updateA.setInt(2, i);
                    updateA.execute();
                }

                for (int i = 0; i < 5; i++) {
                    updateA.setInt(1, i + 10);
                    updateA.setInt(2, i);
                    updateA.execute();
                }

                for (int i = 0; i < 3; i++) {
                    updateB.setInt(1, i + 1000);
                    updateB.setInt(2, i + 10);
                    updateB.execute();
                }
                connection.commit();

                final String expected = "a[INTEGER],b[INTEGER],ts[TIMESTAMP]\n" +
                        "10,1000,2022-03-17 00:00:00.0\n" +
                        "11,1001,2022-03-17 00:00:00.0\n" +
                        "12,1002,2022-03-17 00:00:00.0\n" +
                        "13,13,2022-03-17 00:00:00.0\n" +
                        "14,14,2022-03-17 00:00:00.0\n" +
                        "5,15,2022-03-17 00:00:00.0\n" +
                        "6,16,2022-03-17 00:00:00.0\n" +
                        "7,17,2022-03-17 00:00:00.0\n" +
                        "8,18,2022-03-17 00:00:00.0\n" +
                        "9,19,2022-03-17 00:00:00.0\n" +
                        "20,110,2022-03-17 00:00:00.0\n" +
                        "21,111,2022-03-17 00:00:00.0\n" +
                        "22,112,2022-03-17 00:00:00.0\n" +
                        "23,113,2022-03-17 00:00:00.0\n" +
                        "24,114,2022-03-17 00:00:00.0\n";
                try (ResultSet resultSet = connection.prepareStatement("x").executeQuery()) {
                    sink.clear();
                    assertResultSet(expected, sink, resultSet);
                }
            }
        });
    }

    @Test
    public void testUtf8QueryText() throws Exception {
        testQuery(
                "rnd_double(4) расход, ",
                "s[VARCHAR],i[INTEGER],расход[DOUBLE],t[TIMESTAMP],f[REAL],_short[SMALLINT],l[BIGINT],ts2[TIMESTAMP],bb[SMALLINT],b[BIT],rnd_symbol[VARCHAR],rnd_date[TIMESTAMP],rnd_bin[BINARY],rnd_char[CHAR],rnd_long256[VARCHAR]\n"
        );
    }

    private void assertHexScript(String script) throws Exception {
        final Rnd rnd = new Rnd();
        assertHexScript(NetworkFacadeImpl.INSTANCE, script, new DefaultPGWireConfiguration() {
            @Override
            public Rnd getRandom() {
                return rnd;
            }
        });
    }

    private void assertHexScript(
            NetworkFacade clientNf,
            String script,
            PGWireConfiguration configuration
    ) throws Exception {
        assertMemoryLeak(() -> {
            try (PGWireServer ignored = createPGServer(configuration)) {
                NetUtils.playScript(clientNf, script, "127.0.0.1", 8812);
            }
        });
    }


    private void assertTransaction(boolean simple) throws Exception {
        assertMemoryLeak(() -> {
            try (
                    final PGWireServer ignored = createPGServer(2);
                    final Connection connection = getConnection(simple, true)
            ) {
                connection.setAutoCommit(false);
                connection.prepareStatement("create table xyz(a int)").execute();
                connection.prepareStatement("insert into xyz values (100)").execute();
                connection.prepareStatement("insert into xyz values (101)").execute();
                connection.prepareStatement("insert into xyz values (102)").execute();
                connection.prepareStatement("insert into xyz values (103)").execute();
                connection.commit();

                sink.clear();
                try (
                        PreparedStatement ps = connection.prepareStatement("xyz");
                        ResultSet rs = ps.executeQuery()
                ) {
                    assertResultSet(
                            "a[INTEGER]\n" +
                                    "100\n" +
                                    "101\n" +
                                    "102\n" +
                                    "103\n",
                            sink,
                            rs
                    );
                }
            }
        });
    }

    private PGWireServer.PGConnectionContextFactory createPGConnectionContextFactory(PGWireConfiguration conf, int workerCount,
                                                                                     SOCountDownLatch queryStartedCount, SOCountDownLatch queryScheduledCount) {
        return new PGWireServer.PGConnectionContextFactory(engine, conf, workerCount) {
            @Override
            protected SqlExecutionContextImpl getSqlExecutionContext(CairoEngine engine, int workerCount) {
                return new SqlExecutionContextImpl(engine, workerCount) {
                    @Override
                    public QueryFutureUpdateListener getQueryFutureUpdateListener() {
                        return new QueryFutureUpdateListener() {
                            @Override
                            public void reportProgress(long commandId, int status) {
                                if (status == OperationFuture.QUERY_STARTED && queryStartedCount != null) {
                                    queryStartedCount.countDown();
                                }
                            }
                            @Override
                            public void reportStart(CharSequence tableName, long commandId) {
                                if (queryScheduledCount != null) {
                                    queryScheduledCount.countDown();
                                }
                            }
                        };
                    }
                };
            }
        };
    }

    private void insertAllGeoHashTypes(boolean binary) throws Exception {
        assertMemoryLeak(() -> {
            compiler.compile("create table xyz (" +
                            "a geohash(1b)," +
                            "b geohash(2b)," +
                            "c geohash(3b)," +
                            "d geohash(1c)," +
                            "e geohash(2c)," +
                            "f geohash(4c)," +
                            "g geohash(8c)" +
                            ")",
                    sqlExecutionContext
            );

            try (
                    final PGWireServer ignored = createPGServer(2);
                    final Connection connection = getConnection(false, binary);
                    final PreparedStatement insert = connection.prepareStatement(
                            "insert into xyz values (" +
                                    "cast(? as geohash(1b))," +
                                    "cast(? as geohash(2b))," +
                                    "cast(? as geohash(3b))," +
                                    "cast(? as geohash(1c))," +
                                    "cast(? as geohash(2c))," +
                                    "cast(? as geohash(4c))," +
                                    "cast(? as geohash(8c)))"
                    )
            ) {
                connection.setAutoCommit(false);
                for (int i = 0; i < 100; i++) {
                    insert.setString(1, "0b");
                    insert.setString(2, "10b");
                    insert.setString(3, "010b");
                    insert.setString(4, "x");
                    insert.setString(5, "xy");
                    insert.setString(6, "xyzw");
                    insert.setString(7, "xyzwzvxq");
                    insert.execute();
                    Assert.assertEquals(1, insert.getUpdateCount());
                }
                connection.commit();

                try (RecordCursorFactory factory = compiler.compile("xyz", sqlExecutionContext).getRecordCursorFactory()) {
                    try (RecordCursor cursor = factory.getCursor(sqlExecutionContext)) {
                        final Record record = cursor.getRecord();
                        int count = 0;
                        while (cursor.hasNext()) {
                            //TODO: bits GeoHash literal
//                            Assert.assertEquals((byte)GeoHashes.fromBitString("0", 0), record.getGeoByte(0));
//                            Assert.assertEquals((byte)GeoHashes.fromBitString("01", 0), record.getGeoByte(1));
//                            Assert.assertEquals((byte)GeoHashes.fromBitString("010", 0), record.getGeoByte(2));
                            Assert.assertEquals(GeoHashes.fromString("x", 0, 1), record.getGeoByte(3));
                            Assert.assertEquals(GeoHashes.fromString("xy", 0, 2), record.getGeoShort(4));
                            Assert.assertEquals(GeoHashes.fromString("xyzw", 0, 4), record.getGeoInt(5));
                            Assert.assertEquals(GeoHashes.fromString("xyzwzvxq", 0, 8), record.getGeoLong(6));
                            count++;
                        }

                        Assert.assertEquals(100, count);
                    }
                }
            }
        });
    }

    private void queryTimestampsInRange(Connection connection) throws SQLException, IOException {
        try (PreparedStatement statement = connection.prepareStatement("select ts FROM xts WHERE ts <= dateadd('d', -1, ?) and ts >= dateadd('d', -2, ?)")) {
            ResultSet rs = null;
            for (long micros = 0; micros < count * Timestamps.HOUR_MICROS; micros += Timestamps.HOUR_MICROS * 7) {
                sink.clear();
                statement.setTimestamp(1, new Timestamp(micros));
                statement.setTimestamp(2, new Timestamp(micros));
                statement.executeQuery();
                rs = statement.executeQuery();

                long finalMicros = micros;
                String expected = datesArr.stream().filter(arr -> (long) arr[0] <= (finalMicros - DAY_MICROS) && (long) arr[0] >= (finalMicros - 2 * DAY_MICROS))
                        .map(arr -> arr[1] + "\n")
                        .collect(Collectors.joining());

                assertResultSet("ts[TIMESTAMP]\n" + expected, sink, rs);
            }
            rs.close();
        }
    }

    //
    // Tests for ResultSet.setFetchSize().
    //

    private void testAddColumnBusyWriter(boolean alterRequestReturnSuccess, SOCountDownLatch queryStartedCountDownLatch) throws SQLException, InterruptedException, BrokenBarrierException, SqlException {
        AtomicLong errors = new AtomicLong();
        int workerCount = 2;

        final PGWireConfiguration conf = new DefaultPGWireConfiguration() {
            @Override
            public Rnd getRandom() {
                return new Rnd();
            }

            @Override
            public int[] getWorkerAffinity() {
                return TestUtils.getWorkerAffinity(getWorkerCount());
            }

            @Override
            public int getWorkerCount() {
                return workerCount;
            }
        };

        WorkerPool pool = new WorkerPool(conf, metrics);
        pool.assign(engine.getEngineMaintenanceJob());
        try (
                final PGWireServer ignored = PGWireServer.create(
                        conf,
                        pool,
                        LOG,
                        engine,
                        compiler.getFunctionFactoryCache(),
                        snapshotAgent,
                        metrics,
                        createPGConnectionContextFactory(conf, workerCount, queryStartedCountDownLatch, null)
                )
        ) {
            pool.start(LOG);
            int iteration = 0;

            do {
                final String tableName = "xyz" + iteration++;
                compiler.compile("create table " + tableName + " (a int)", sqlExecutionContext);

                try (
                        final Connection connection1 = getConnection(false, true);
                        final Connection connection2 = getConnection(false, true);
                        final PreparedStatement insert = connection1.prepareStatement(
                                "insert into " + tableName + " values (?)"
                        )
                ) {
                    connection1.setAutoCommit(false);
                    int totalCount = 10;
                    for (int i = 0; i < totalCount; i++) {
                        insert.setInt(1, i);
                        insert.execute();
                    }
                    CyclicBarrier start = new CyclicBarrier(2);
                    CountDownLatch finished = new CountDownLatch(1);
                    errors.set(0);

                    new Thread(() -> {
                        try {
                            start.await();
                            try (
                                    final PreparedStatement alter = connection2.prepareStatement(
                                            "alter table " + tableName + " add column b long"
                                    )
                            ) {
                                alter.execute();
                            }
                        } catch (Throwable e) {
                            e.printStackTrace();
                            errors.incrementAndGet();
                        } finally {
                            finished.countDown();
                        }
                    }).start();

                    start.await();
                    Os.sleep(100);
                    connection1.commit();
                    finished.await();

                    if (alterRequestReturnSuccess) {
                        Assert.assertEquals(0, errors.get());
                        try (TableReader rdr = engine.getReader(sqlExecutionContext.getCairoSecurityContext(), tableName)) {
                            int bIndex = rdr.getMetadata().getColumnIndex("b");
                            Assert.assertEquals(1, bIndex);
                            Assert.assertEquals(totalCount, rdr.size());
                        }
                    }
                } finally {
                    pool.halt();
                    engine.releaseAllWriters();
                }
                // Failure may not happen if we're lucky, even when they are expected
                // When alterRequestReturnSuccess if false and errors are 0, repeat
            } while (!alterRequestReturnSuccess && errors.get() == 0);
        }
    }

    private void testAllTypesSelect(boolean simple) throws Exception {
        assertMemoryLeak(() -> {
            try (
                    final PGWireServer ignored = createPGServer(1);
                    final Connection connection = getConnection(simple, true)
            ) {
                CallableStatement stmt = connection.prepareCall(
                        "create table x as (select" +
                                " cast(x as int) kk, " +
                                " rnd_int() a," +
                                " rnd_boolean() b," + // str
                                " rnd_str(1,1,2) c," + // str
                                " rnd_double(2) d," +
                                " rnd_float(2) e," +
                                " rnd_short(10,1024) f," +
                                " rnd_date(to_date('2015', 'yyyy'), to_date('2016', 'yyyy'), 2) g," +
                                " rnd_symbol(4,4,4,2) i," + // str
                                " rnd_long() j," +
                                " timestamp_sequence(889001, 8890012) k," +
                                " rnd_byte(2,50) l," +
                                " rnd_bin(10, 20, 2) m," +
                                " rnd_str(5,16,2) n," +
                                " rnd_char() cc," + // str
                                " rnd_long256() l2" + // str
                                " from long_sequence(15))" // str
                );

                stmt.execute();

                try (PreparedStatement statement = connection.prepareStatement("x")) {
                    for (int i = 0; i < 1_000; i++) {
                        sink.clear();
                        try (ResultSet rs = statement.executeQuery()) {
                            // dump metadata
                            assertResultSet(
                                    "kk[INTEGER],a[INTEGER],b[BIT],c[VARCHAR],d[DOUBLE],e[REAL],f[SMALLINT],g[TIMESTAMP],i[VARCHAR],j[BIGINT],k[TIMESTAMP],l[SMALLINT],m[BINARY],n[VARCHAR],cc[CHAR],l2[VARCHAR]\n" +
                                            "1,1569490116,false,Z,null,0.761,428,2015-05-16 20:27:48.158,VTJW,-8671107786057422727,1970-01-01 00:00:00.889001,26,00000000 68 61 26 af 19 c4 95 94 36 53 49,FOWLPD,X,0xbccb30ed7795ebc85f20a35e80e154f458dfd08eeb9cc39ecec82869edec121b\n" +
                                            "2,-461611463,false,J,0.9687423276940171,0.676,279,2015-11-21 14:32:13.134,HYRX,-6794405451419334859,1970-01-01 00:00:09.779013,6,null,ETJRSZSRYR,F,0x9ff97d73fc0c62d069440048957ae05360802a2ca499f211b771e27f939096b9\n" +
                                            "3,-1515787781,false,null,0.8001121139739173,0.188,759,2015-06-17 02:40:55.328,CPSW,-4091897709796604687,1970-01-01 00:00:18.669025,6,00000000 9c 1d 06 ac 37 c8 cd 82 89 2b 4d 5f f6 46 90 c3,DYYCTGQOLYXWCKYL,S,0x26567f4430b46b7f78c594c496995885aa1896d0ad3419d2910aa7b6d58506dc\n" +
                                            "4,1235206821,true,null,0.9540069089049732,0.255,310,null,VTJW,6623443272143014835,1970-01-01 00:00:27.559037,17,00000000 cc 76 48 a3 bb 64 d2 ad 49 1c f2 3c ed 39 ac,VSJOJIPHZEPIHVLT,O,0x825c96def9f2fcc2b942438168662cb7aa21f9d816335363d27e6df7d9d5b758\n" +
                                            "5,454820511,false,L,0.9918093114862231,0.324,727,2015-02-10 08:56:03.707,null,5703149806881083206,1970-01-01 00:00:36.449049,36,00000000 68 79 8b 43 1d 57 34 04 23 8d d8 57,WVDKFLOPJOXPK,R,0xa07934b2a15de8e0550988dbaca497348692bc8c04e4bb71d24b84c08ea7606a\n" +
                                            "6,1728220848,false,O,0.24642266252221556,0.267,174,2015-02-20 01:11:53.748,null,2151565237758036093,1970-01-01 00:00:45.339061,31,null,HZSQLDGLOGIFO,U,0xf0431c7d0a5f126f8531876c963316d961f392242addf45287dd0b29ca2c4c84\n" +
                                            "7,-120660220,false,B,0.07594017197103131,0.064,542,2015-01-16 16:01:53.328,VTJW,5048272224871876586,1970-01-01 00:00:54.229073,23,00000000 f5 0f 2d b3 14 33 80 c9 eb a3 67 7a 1a 79 e4 35\n" +
                                            "00000010 e4 3a dc 5c,ULIGYVFZ,F,0xa15aae5b999db11899193c2e0a9e76da695f8ae33a2cc2aa529d71aba0f6fec5\n" +
                                            "8,-1548274994,true,X,0.9292491654871197,null,523,2015-01-05 19:01:46.416,HYRX,9044897286885345735,1970-01-01 00:01:03.119085,16,00000000 cd 47 0b 0c 39 12 f7 05 10 f4 6d f1 e3 ee 58 35\n" +
                                            "00000010 61,MXSLUQDYOPHNIMYF,F,0x20cfa22cd22bf054483c83d88ac674e3894499a1a1680580cfedff23a67d918f\n" +
                                            "9,1430716856,false,P,0.7707249647497968,null,162,2015-02-05 10:14:02.889,null,7046578844650327247,1970-01-01 00:01:12.009097,47,null,LEGPUHHIUGGLNYR,Z,0x5565337913b499af36be4fe79117ebd53756b77218c738a7737b1dacd6be5971\n" +
                                            "10,-772867311,false,Q,0.7653255982993546,null,681,2015-05-07 02:45:07.603,null,4794469881975683047,1970-01-01 00:01:20.899109,31,00000000 4e d6 b2 57 5b e3 71 3d 20 e2 37 f2 64 43 84 55\n" +
                                            "00000010 a0 dd,VTNPIW,Z,0x6bfac0b6e487d3532d1c6f57bbfd47ec39bd4dd9ad497a2721dc4adc870c62fe\n" +
                                            "11,494704403,true,C,0.4834201611292943,0.794,28,2015-06-16 21:00:55.459,HYRX,6785355388782691241,1970-01-01 00:01:29.789121,39,null,RVNGSTEQOD,R,0xc82c35a389f834dababcd0482f05618f926cdd99e63abb35650d1fb462d014df\n" +
                                            "12,-173290754,true,K,0.7198854503668188,null,114,2015-06-15 20:39:39.538,VTJW,9064962137287142402,1970-01-01 00:01:38.679133,20,00000000 3b 94 5f ec d3 dc f8 43 b2 e3,TIZKYFLUHZQSNPX,M,0x5073897a288aa6cf74c509677990f1c962588b84eddb7b4a64a4822086748dc4\n" +
                                            "13,-2041781509,true,E,0.44638626240707313,0.035,605,null,VTJW,415951511685691973,1970-01-01 00:01:47.569145,28,00000000 00 7c fb 01 19 ca f2 bf 84 5a 6f 38 35,null,V,0xab059a2342cb232f543554ee7efea2c341b1a691af3ce51f91a63337ac2e9683\n" +
                                            "14,813111021,true,null,0.1389067130304884,0.373,259,null,CPSW,4422067104162111415,1970-01-01 00:01:56.459157,19,00000000 2d 16 f3 89 a3 83 64 de d6 fd c4 5b c4 e9,PNXHQUTZODWKOC,P,0x09debd6254b1776d50902704a317faeea7fc3b8563ada5ab985499c7f07368a3\n" +
                                            "15,980916820,false,C,0.8353079103853974,0.011,670,2015-10-06 01:12:57.175,null,7536661420632276058,1970-01-01 00:02:05.349169,37,null,FDBZWNIJEE,H,0xa6d100033dcaf68cb265942d3a1f96a1cff85f9258847e03a6f2e2a772cd2f37\n",
                                    sink,
                                    rs
                            );
                        }
                    }
                }

                // run some random SQLs
                final String header = "kk[INTEGER],a[INTEGER],b[BIT],c[VARCHAR],d[DOUBLE],e[REAL],f[SMALLINT],g[TIMESTAMP],i[VARCHAR],j[BIGINT],k[TIMESTAMP],l[SMALLINT],m[BINARY],n[VARCHAR],cc[CHAR],l2[VARCHAR]\n";

                final String[] results = {
                        "1,1569490116,false,Z,null,0.761,428,2015-05-16 20:27:48.158,VTJW,-8671107786057422727,1970-01-01 00:00:00.889001,26,00000000 68 61 26 af 19 c4 95 94 36 53 49,FOWLPD,X,0xbccb30ed7795ebc85f20a35e80e154f458dfd08eeb9cc39ecec82869edec121b\n",
                        "2,-461611463,false,J,0.9687423276940171,0.676,279,2015-11-21 14:32:13.134,HYRX,-6794405451419334859,1970-01-01 00:00:09.779013,6,null,ETJRSZSRYR,F,0x9ff97d73fc0c62d069440048957ae05360802a2ca499f211b771e27f939096b9\n",
                        "3,-1515787781,false,null,0.8001121139739173,0.188,759,2015-06-17 02:40:55.328,CPSW,-4091897709796604687,1970-01-01 00:00:18.669025,6,00000000 9c 1d 06 ac 37 c8 cd 82 89 2b 4d 5f f6 46 90 c3,DYYCTGQOLYXWCKYL,S,0x26567f4430b46b7f78c594c496995885aa1896d0ad3419d2910aa7b6d58506dc\n",
                        "4,1235206821,true,null,0.9540069089049732,0.255,310,null,VTJW,6623443272143014835,1970-01-01 00:00:27.559037,17,00000000 cc 76 48 a3 bb 64 d2 ad 49 1c f2 3c ed 39 ac,VSJOJIPHZEPIHVLT,O,0x825c96def9f2fcc2b942438168662cb7aa21f9d816335363d27e6df7d9d5b758\n",
                        "5,454820511,false,L,0.9918093114862231,0.324,727,2015-02-10 08:56:03.707,null,5703149806881083206,1970-01-01 00:00:36.449049,36,00000000 68 79 8b 43 1d 57 34 04 23 8d d8 57,WVDKFLOPJOXPK,R,0xa07934b2a15de8e0550988dbaca497348692bc8c04e4bb71d24b84c08ea7606a\n",
                        "6,1728220848,false,O,0.24642266252221556,0.267,174,2015-02-20 01:11:53.748,null,2151565237758036093,1970-01-01 00:00:45.339061,31,null,HZSQLDGLOGIFO,U,0xf0431c7d0a5f126f8531876c963316d961f392242addf45287dd0b29ca2c4c84\n",
                        "7,-120660220,false,B,0.07594017197103131,0.064,542,2015-01-16 16:01:53.328,VTJW,5048272224871876586,1970-01-01 00:00:54.229073,23,00000000 f5 0f 2d b3 14 33 80 c9 eb a3 67 7a 1a 79 e4 35\n" +
                                "00000010 e4 3a dc 5c,ULIGYVFZ,F,0xa15aae5b999db11899193c2e0a9e76da695f8ae33a2cc2aa529d71aba0f6fec5\n",
                        "8,-1548274994,true,X,0.9292491654871197,null,523,2015-01-05 19:01:46.416,HYRX,9044897286885345735,1970-01-01 00:01:03.119085,16,00000000 cd 47 0b 0c 39 12 f7 05 10 f4 6d f1 e3 ee 58 35\n" +
                                "00000010 61,MXSLUQDYOPHNIMYF,F,0x20cfa22cd22bf054483c83d88ac674e3894499a1a1680580cfedff23a67d918f\n",
                        "9,1430716856,false,P,0.7707249647497968,null,162,2015-02-05 10:14:02.889,null,7046578844650327247,1970-01-01 00:01:12.009097,47,null,LEGPUHHIUGGLNYR,Z,0x5565337913b499af36be4fe79117ebd53756b77218c738a7737b1dacd6be5971\n",
                        "10,-772867311,false,Q,0.7653255982993546,null,681,2015-05-07 02:45:07.603,null,4794469881975683047,1970-01-01 00:01:20.899109,31,00000000 4e d6 b2 57 5b e3 71 3d 20 e2 37 f2 64 43 84 55\n" +
                                "00000010 a0 dd,VTNPIW,Z,0x6bfac0b6e487d3532d1c6f57bbfd47ec39bd4dd9ad497a2721dc4adc870c62fe\n",
                        "11,494704403,true,C,0.4834201611292943,0.794,28,2015-06-16 21:00:55.459,HYRX,6785355388782691241,1970-01-01 00:01:29.789121,39,null,RVNGSTEQOD,R,0xc82c35a389f834dababcd0482f05618f926cdd99e63abb35650d1fb462d014df\n",
                        "12,-173290754,true,K,0.7198854503668188,null,114,2015-06-15 20:39:39.538,VTJW,9064962137287142402,1970-01-01 00:01:38.679133,20,00000000 3b 94 5f ec d3 dc f8 43 b2 e3,TIZKYFLUHZQSNPX,M,0x5073897a288aa6cf74c509677990f1c962588b84eddb7b4a64a4822086748dc4\n",
                        "13,-2041781509,true,E,0.44638626240707313,0.035,605,null,VTJW,415951511685691973,1970-01-01 00:01:47.569145,28,00000000 00 7c fb 01 19 ca f2 bf 84 5a 6f 38 35,null,V,0xab059a2342cb232f543554ee7efea2c341b1a691af3ce51f91a63337ac2e9683\n",
                        "14,813111021,true,null,0.1389067130304884,0.373,259,null,CPSW,4422067104162111415,1970-01-01 00:01:56.459157,19,00000000 2d 16 f3 89 a3 83 64 de d6 fd c4 5b c4 e9,PNXHQUTZODWKOC,P,0x09debd6254b1776d50902704a317faeea7fc3b8563ada5ab985499c7f07368a3\n",
                        "15,980916820,false,C,0.8353079103853974,0.011,670,2015-10-06 01:12:57.175,null,7536661420632276058,1970-01-01 00:02:05.349169,37,null,FDBZWNIJEE,H,0xa6d100033dcaf68cb265942d3a1f96a1cff85f9258847e03a6f2e2a772cd2f37\n"
                };

                for (int i = 0; i < 20_000; i++) {
                    sink.clear();
                    int index = (i % 1000) + 1;
                    try (PreparedStatement statement = connection.prepareStatement("x where kk = " + index)) {
                        try (ResultSet rs = statement.executeQuery()) {
                            assertResultSet(header + (index - 1 < results.length ? results[index - 1] : ""), sink, rs);
                        }
                    }
                }
            }
        });
    }

    private void testBinaryInsert(int maxLength, boolean binaryProtocol) throws Exception {
        assertMemoryLeak(() -> {
            compiler.compile("create table xyz (" +
                            "a binary" +
                            ")",
                    sqlExecutionContext
            );
            try (
                    final PGWireServer ignored = createPGServer(1);
                    final Connection connection = getConnection(false, binaryProtocol);
                    final PreparedStatement insert = connection.prepareStatement(
                            "insert into xyz values (?)"
                    )
            ) {
                connection.setAutoCommit(false);
                try (InputStream str = new InputStream() {
                    int value = 0;

                    @Override
                    public int read() {
                        if (maxLength == value) return -1;
                        return value++ % 255;
                    }
                }) {
                    int totalCount = 1;
                    for (int i = 0; i < totalCount; i++) {
                        insert.setBinaryStream(1, str);
                        insert.execute();
                    }
                    connection.commit();

                    try (RecordCursorFactory factory = compiler.compile("xyz", sqlExecutionContext).getRecordCursorFactory()) {
                        try (RecordCursor cursor = factory.getCursor(sqlExecutionContext)) {
                            final Record record = cursor.getRecord();
                            int count = 0;
                            while (cursor.hasNext()) {
                                Assert.assertEquals(maxLength, record.getBinLen(0));
                                BinarySequence bs = record.getBin(0);
                                for (int i = 0; i < maxLength; i++) {
                                    Assert.assertEquals(
                                            i % 255,
                                            bs.byteAt(i) & 0xff // Convert byte to unsigned int
                                    );
                                }
                                count++;
                            }

                            Assert.assertEquals(totalCount, count);
                        }
                    }
                }
            }
        });
    }

    private void testBindVariableInFilter(boolean binary) throws Exception {
        assertMemoryLeak(() -> {
            try (
                    final PGWireServer ignored = createPGServer(1);
                    final Connection connection = getConnection(false, binary)
            ) {
                connection.setAutoCommit(false);
                connection.prepareStatement("create table x (l long, ts timestamp) timestamp(ts)").execute();
                connection.prepareStatement("insert into x values (100, 0)").execute();
                connection.prepareStatement("insert into x values (101, 1)").execute();
                connection.prepareStatement("insert into x values (102, 2)").execute();
                connection.prepareStatement("insert into x values (103, 3)").execute();
                connection.commit();

                sink.clear();
                try (PreparedStatement ps = connection.prepareStatement("select * from x where l != ?")) {
                    ps.setLong(1, 0);
                    try (ResultSet rs = ps.executeQuery()) {
                        assertResultSet(
                                "l[BIGINT],ts[TIMESTAMP]\n" +
                                        "100,1970-01-01 00:00:00.0\n" +
                                        "101,1970-01-01 00:00:00.000001\n" +
                                        "102,1970-01-01 00:00:00.000002\n" +
                                        "103,1970-01-01 00:00:00.000003\n",
                                sink,
                                rs
                        );
                    }
                }
            }
        });
    }

    private void testBindVariableIsNotNull(boolean binary) throws Exception {
        assertMemoryLeak(() -> {
            try (
                    final PGWireServer ignored = createPGServer(1);
                    final Connection connection = getConnection(false, binary)
            ) {
                connection.setAutoCommit(false);
                connection.prepareStatement("create table tab1 (value int, ts timestamp) timestamp(ts)").execute();
                connection.prepareStatement("insert into tab1 (value, ts) values (100, 0)").execute();
                connection.prepareStatement("insert into tab1 (value, ts) values (null, 1)").execute();
                connection.commit();
                connection.setAutoCommit(true);

                sink.clear();
                try (PreparedStatement ps = connection.prepareStatement("tab1 where 3 is not null")) {
                    try (ResultSet rs = ps.executeQuery()) {
                        assertResultSet(
                                "value[INTEGER],ts[TIMESTAMP]\n" +
                                        "100,1970-01-01 00:00:00.0\n" +
                                        "null,1970-01-01 00:00:00.000001\n",
                                sink,
                                rs
                        );
                    }
                }

                sink.clear();
                try (PreparedStatement ps = connection.prepareStatement("tab1 where coalesce(?, 12.37) is not null")) {
                    // 'is not' is an alias for '!=', the matching type for this operator
                    // (with null on the right) is DOUBLE
                    ps.setDouble(1, 3.14);
                    try (ResultSet rs = ps.executeQuery()) {
                        assertResultSet(
                                "value[INTEGER],ts[TIMESTAMP]\n" +
                                        "100,1970-01-01 00:00:00.0\n" +
                                        "null,1970-01-01 00:00:00.000001\n",
                                sink,
                                rs
                        );
                    }
                }

                sink.clear();
                try (PreparedStatement ps = connection.prepareStatement("tab1 where ? is not null")) {
                    // 'is not' is an alias for '!=', the matching type for this operator
                    // (with null on the right) is DOUBLE
                    ps.setDouble(1, 3.14);
                    try (ResultSet rs = ps.executeQuery()) {
                        assertResultSet(
                                "value[INTEGER],ts[TIMESTAMP]\n" +
                                        "100,1970-01-01 00:00:00.0\n" +
                                        "null,1970-01-01 00:00:00.000001\n",
                                sink,
                                rs
                        );
                    }
                }

                sink.clear();
                try (PreparedStatement ps = connection.prepareStatement("tab1 where ? is not null")) {
                    ps.setDouble(1, Double.NaN);
                    try (ResultSet rs = ps.executeQuery()) {
                        assertResultSet(
                                "value[INTEGER],ts[TIMESTAMP]\n",
                                sink,
                                rs
                        );
                    }
                }

                sink.clear();
                try (PreparedStatement ps = connection.prepareStatement("tab1 where ? is not null")) {
                    ps.setInt(1, Numbers.INT_NaN);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (binary) {
                            assertResultSet(
                                    "value[INTEGER],ts[TIMESTAMP]\n",
                                    sink,
                                    rs
                            );
                        } else {
                            assertResultSet(
                                    "value[INTEGER],ts[TIMESTAMP]\n" +
                                            "100,1970-01-01 00:00:00.0\n" +
                                            "null,1970-01-01 00:00:00.000001\n",
                                    sink,
                                    rs
                            );
                        }
                    }
                }

                sink.clear();
                try (PreparedStatement ps = connection.prepareStatement("tab1 where ? is not null")) {
                    ps.setInt(1, 12);
                    try (ResultSet rs = ps.executeQuery()) {
                        assertResultSet(
                                "value[INTEGER],ts[TIMESTAMP]\n" +
                                        "100,1970-01-01 00:00:00.0\n" +
                                        "null,1970-01-01 00:00:00.000001\n",
                                sink,
                                rs
                        );
                    }
                }

                try (PreparedStatement ps = connection.prepareStatement("tab1 where ? is not null")) {
                    ps.setString(1, "");
                    try (ResultSet ignore1 = ps.executeQuery()) {
                        Assert.fail();
                    } catch (PSQLException e) {
                        TestUtils.assertContains(e.getMessage(), "could not parse [value='', as=DOUBLE, index=0]");
                    }
                }

                try (PreparedStatement ps = connection.prepareStatement("tab1 where ? is not null")) {
                    ps.setString(1, "cah-cha-cha");
                    try (ResultSet ignore1 = ps.executeQuery()) {
                        Assert.fail();
                    } catch (PSQLException e) {
                        TestUtils.assertContains(e.getMessage(), "could not parse [value='cah-cha-cha', as=DOUBLE, index=0]");
                    }
                }

                try (PreparedStatement ps = connection.prepareStatement("tab1 where null is not ?")) {
                    ps.setString(1, "NULL");
                    try (ResultSet ignore1 = ps.executeQuery()) {
                        Assert.fail();
                    } catch (PSQLException e) {
                        TestUtils.assertContains(e.getMessage(), "IS NOT must be followed by NULL");
                    }
                }

                try (PreparedStatement ps = connection.prepareStatement("tab1 where null is not ?")) {
                    ps.setDouble(1, Double.NaN);
                    try (ResultSet ignore1 = ps.executeQuery()) {
                        Assert.fail();
                    } catch (PSQLException e) {
                        TestUtils.assertContains(e.getMessage(), "IS NOT must be followed by NULL");
                    }
                }

                try (PreparedStatement ps = connection.prepareStatement("tab1 where null is not ?")) {
                    ps.setNull(1, Types.NULL);
                    try (ResultSet ignored1 = ps.executeQuery()) {
                        Assert.fail();
                    } catch (PSQLException e) {
                        TestUtils.assertContains(e.getMessage(), "IS NOT must be followed by NULL");
                    }
                }

                try (PreparedStatement ps = connection.prepareStatement("tab1 where value is not ?")) {
                    ps.setString(1, "NULL");
                    try (ResultSet ignored1 = ps.executeQuery()) {
                        Assert.fail();
                    } catch (PSQLException e) {
                        TestUtils.assertContains(e.getMessage(), "IS NOT must be followed by NULL");
                    }
                }
            }
        });
    }

    private void testBindVariableIsNull(boolean binary) throws Exception {
        assertMemoryLeak(() -> {
            try (
                    final PGWireServer ignored = createPGServer(1);
                    final Connection connection = getConnection(false, binary)
            ) {
                connection.setAutoCommit(false);
                connection.prepareStatement("create table tab1 (value int, ts timestamp) timestamp(ts)").execute();
                connection.prepareStatement("insert into tab1 (value, ts) values (100, 0)").execute();
                connection.prepareStatement("insert into tab1 (value, ts) values (null, 1)").execute();
                connection.commit();
                connection.setAutoCommit(true);

                sink.clear();
                try (PreparedStatement ps = connection.prepareStatement("tab1 where null is null")) {
                    try (ResultSet rs = ps.executeQuery()) {
                        // all rows, null = null is always true
                        assertResultSet(
                                "value[INTEGER],ts[TIMESTAMP]\n" +
                                        "100,1970-01-01 00:00:00.0\n" +
                                        "null,1970-01-01 00:00:00.000001\n",
                                sink,
                                rs
                        );
                    }
                }

                sink.clear();
                try (PreparedStatement ps = connection.prepareStatement("tab1 where (? | null) is null")) {
                    ps.setLong(1, 1066);
                    try (ResultSet rs = ps.executeQuery()) {
                        assertResultSet(
                                "value[INTEGER],ts[TIMESTAMP]\n" +
                                        "100,1970-01-01 00:00:00.0\n" +
                                        "null,1970-01-01 00:00:00.000001\n",
                                sink,
                                rs
                        );
                    }
                }

                sink.clear();
                try (PreparedStatement ps = connection.prepareStatement("tab1 where ? is null")) {
                    // 'is' is an alias for '=', the matching type for this operator, with null
                    // on the right, is DOUBLE (EqDoubleFunctionFactory)
                    ps.setDouble(1, Double.NaN);
                    try (ResultSet rs = ps.executeQuery()) {
                        assertResultSet(
                                "value[INTEGER],ts[TIMESTAMP]\n" +
                                        "100,1970-01-01 00:00:00.0\n" +
                                        "null,1970-01-01 00:00:00.000001\n",
                                sink,
                                rs
                        );
                    }
                }

                sink.clear();
                try (PreparedStatement ps = connection.prepareStatement("tab1 where ? is null")) {
                    // INTEGER fits in a DOUBLE, however it is interpreted differently depending on
                    // transfer type (binary, string)
                    ps.setInt(1, Numbers.INT_NaN);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (binary) {
                            // in binary protocol DOUBLE.null == INT.null
                            assertResultSet(
                                    "value[INTEGER],ts[TIMESTAMP]\n" +
                                            "100,1970-01-01 00:00:00.0\n" +
                                            "null,1970-01-01 00:00:00.000001\n",
                                    sink,
                                    rs
                            );
                        } else {
                            // in string protocol DOUBLE.null != INT.null
                            assertResultSet(
                                    "value[INTEGER],ts[TIMESTAMP]\n",
                                    sink,
                                    rs
                            );
                        }
                    }
                }

                sink.clear();
                try (PreparedStatement ps = connection.prepareStatement("tab1 where ? is null")) {
                    // 'is' is an alias for '=', the matching type for this operator
                    // (with null on the right) is DOUBLE, and thus INT is a valid
                    // value type
                    ps.setInt(1, 21);
                    try (ResultSet rs = ps.executeQuery()) {
                        assertResultSet(
                                "value[INTEGER],ts[TIMESTAMP]\n",
                                sink,
                                rs
                        );
                    }
                }

                try (PreparedStatement ps = connection.prepareStatement("tab1 where ? is null")) {
                    ps.setString(1, "");
                    try (ResultSet ignore1 = ps.executeQuery()) {
                        Assert.fail();
                    } catch (PSQLException e) {
                        TestUtils.assertContains(e.getMessage(), "could not parse [value='', as=DOUBLE, index=0]");
                    }
                }

                try (PreparedStatement ps = connection.prepareStatement("tab1 where ? is null")) {
                    ps.setString(1, "cha-cha-cha");
                    try (ResultSet ignore1 = ps.executeQuery()) {
                        Assert.fail();
                    } catch (PSQLException e) {
                        TestUtils.assertContains(e.getMessage(), "could not parse [value='cha-cha-cha', as=DOUBLE, index=0]");
                    }
                }

                try (PreparedStatement ps = connection.prepareStatement("tab1 where value is ?")) {
                    ps.setString(1, "NULL");
                    try (ResultSet ignore1 = ps.executeQuery()) {
                        Assert.fail();
                    } catch (PSQLException e) {
                        TestUtils.assertContains(e.getMessage(), "IS must be followed by NULL");

                    }
                }

                try (PreparedStatement ps = connection.prepareStatement("tab1 where null is ?")) {
                    ps.setDouble(1, Double.NaN);
                    try (ResultSet ignore1 = ps.executeQuery()) {
                        Assert.fail();
                    } catch (PSQLException e) {
                        TestUtils.assertContains(e.getMessage(), "IS must be followed by NULL");
                    }
                }

                try (PreparedStatement ps = connection.prepareStatement("tab1 where null is ?")) {
                    ps.setNull(1, Types.NULL);
                    try (ResultSet ignored1 = ps.executeQuery()) {
                        Assert.fail();
                    } catch (PSQLException e) {
                        TestUtils.assertContains(e.getMessage(), "IS must be followed by NULL");
                    }
                }

                try (PreparedStatement ps = connection.prepareStatement("tab1 where value is ?")) {
                    ps.setString(1, "NULL");
                    try (ResultSet ignored1 = ps.executeQuery()) {
                        Assert.fail();
                    } catch (PSQLException e) {
                        TestUtils.assertContains(e.getMessage(), "IS must be followed by NULL");
                    }
                }
            }
        });
    }

    private void testBindVariablesWithIndexedSymbolInFilter(boolean binary, boolean indexed) throws Exception {
        assertMemoryLeak(() -> {
            try (
                    final PGWireServer ignored = createPGServer(1);
                    final Connection connection = getConnection(false, binary)
            ) {
                connection.setAutoCommit(false);
                connection.prepareStatement("create table x (device_id symbol" + (indexed ? " index," : ",") + " column_name symbol, value double, timestamp timestamp) timestamp(timestamp) partition by day").execute();
                connection.prepareStatement("insert into x (device_id, column_name, value, timestamp) values ('d1', 'c1', 101.1, 0)").execute();
                connection.prepareStatement("insert into x (device_id, column_name, value, timestamp) values ('d1', 'c1', 101.2, 1)").execute();
                connection.prepareStatement("insert into x (device_id, column_name, value, timestamp) values ('d1', 'c1', 101.3, 2)").execute();
                connection.prepareStatement("insert into x (device_id, column_name, value, timestamp) values ('d2', 'c1', 201.1, 0)").execute();
                connection.prepareStatement("insert into x (device_id, column_name, value, timestamp) values ('d2', 'c1', 201.2, 1)").execute();
                connection.prepareStatement("insert into x (device_id, column_name, value, timestamp) values ('d2', 'c1', 201.3, 2)").execute();
                connection.prepareStatement("insert into x (device_id, column_name, value, timestamp) values ('d3', 'c1', 301.1, 0)").execute();
                connection.prepareStatement("insert into x (device_id, column_name, value, timestamp) values ('d3', 'c1', 301.2, 1)").execute();
                connection.commit();

                // single key value in filter

                sink.clear();
                try (PreparedStatement ps = connection.prepareStatement("select * from x where device_id = ? and timestamp > ?")) {
                    ps.setString(1, "d1");
                    ps.setTimestamp(2, new Timestamp(1));
                    try (ResultSet rs = ps.executeQuery()) {
                        assertResultSet(
                                "device_id[VARCHAR],column_name[VARCHAR],value[DOUBLE],timestamp[TIMESTAMP]\n" +
                                        "d1,c1,101.3,1970-01-01 00:00:00.000002\n",
                                sink,
                                rs
                        );
                    }
                }

                sink.clear();
                try (PreparedStatement ps = connection.prepareStatement("select * from x where device_id != ? and timestamp > ?")) {
                    ps.setString(1, "d1");
                    ps.setTimestamp(2, new Timestamp(1));
                    try (ResultSet rs = ps.executeQuery()) {
                        assertResultSet(
                                "device_id[VARCHAR],column_name[VARCHAR],value[DOUBLE],timestamp[TIMESTAMP]\n" +
                                        "d2,c1,201.3,1970-01-01 00:00:00.000002\n",
                                sink,
                                rs
                        );
                    }
                }

                // multiple key values in filter

                sink.clear();
                try (PreparedStatement ps = connection.prepareStatement("select * from x where device_id in (?, ?) and timestamp > ?")) {
                    ps.setString(1, "d1");
                    ps.setString(2, "d2");
                    ps.setTimestamp(3, new Timestamp(0));
                    try (ResultSet rs = ps.executeQuery()) {
                        assertResultSet(
                                "device_id[VARCHAR],column_name[VARCHAR],value[DOUBLE],timestamp[TIMESTAMP]\n" +
                                        "d2,c1,201.2,1970-01-01 00:00:00.000001\n" +
                                        "d1,c1,101.2,1970-01-01 00:00:00.000001\n" +
                                        "d2,c1,201.3,1970-01-01 00:00:00.000002\n" +
                                        "d1,c1,101.3,1970-01-01 00:00:00.000002\n",
                                sink,
                                rs
                        );
                    }
                }

                sink.clear();
                try (PreparedStatement ps = connection.prepareStatement("select * from x where device_id not in (?, ?) and timestamp > ?")) {
                    ps.setString(1, "d2");
                    ps.setString(2, "d3");
                    ps.setTimestamp(3, new Timestamp(0));
                    try (ResultSet rs = ps.executeQuery()) {
                        assertResultSet(
                                "device_id[VARCHAR],column_name[VARCHAR],value[DOUBLE],timestamp[TIMESTAMP]\n" +
                                        "d1,c1,101.2,1970-01-01 00:00:00.000001\n" +
                                        "d1,c1,101.3,1970-01-01 00:00:00.000002\n",
                                sink,
                                rs
                        );
                    }
                }
            }
        });
    }

    private void testGeoHashSelect(boolean simple, boolean binary) throws Exception {
        TestUtils.assertMemoryLeak(() -> {
            try (
                    final PGWireServer ignore = createPGServer(2);
                    final Connection connection = getConnection(simple, binary)
            ) {
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "select " +
                                "rnd_geohash(1) hash1b, " +
                                "rnd_geohash(2) hash2b, " +
                                "rnd_geohash(3) hash3b, " +
                                "rnd_geohash(5) hash1c, " +
                                "rnd_geohash(10) hash2c, " +
                                "rnd_geohash(20) hash4c, " +
                                "rnd_geohash(40) hash8c " +
                                "from long_sequence(10)");

                final String expected = "hash1b[VARCHAR],hash2b[VARCHAR],hash3b[VARCHAR],hash1c[VARCHAR],hash2c[VARCHAR],hash4c[VARCHAR],hash8c[VARCHAR]\n" +
                        "0,00,100,z,hp,wh4b,s2z2fyds\n" +
                        "0,10,001,f,q4,uzr0,jj53eufn\n" +
                        "1,01,111,7,q0,s2vq,y5nbb1qj\n" +
                        "1,10,111,r,5t,g5xx,kt2bujns\n" +
                        "1,11,010,w,u7,qjuz,gyye1jqc\n" +
                        "1,01,101,2,cs,vqnq,9yvqyf2r\n" +
                        "1,10,001,0,be,4bw1,v676yupj\n" +
                        "0,11,010,q,vg,g6mm,4tyruscu\n" +
                        "1,01,011,u,wt,jgke,pw94gc64\n" +
                        "0,01,101,8,y0,b2vj,b8182chp\n";
                StringSink sink = new StringSink();
                // dump metadata
                assertResultSet(expected, sink, rs);
            }
        });
    }

    private void testInsert0(boolean simpleQueryMode, boolean binary) throws Exception {
        assertMemoryLeak(() -> {

            String expectedAll = "a[INTEGER],d[TIMESTAMP],t[TIMESTAMP],d1[TIMESTAMP],t1[TIMESTAMP],t2[TIMESTAMP]\n" +
                    "0,2011-04-11 00:00:00.0,2011-04-11 14:40:54.998821,2011-04-11 14:40:54.998,2011-04-11 14:39:50.4,2011-04-11 14:40:54.998821\n" +
                    "1,2011-04-11 00:00:00.0,2011-04-11 14:40:54.999821,2011-04-11 14:40:54.999,2011-04-11 14:39:50.4,2011-04-11 14:40:54.999821\n" +
                    "2,2011-04-11 00:00:00.0,2011-04-11 14:40:55.000821,2011-04-11 14:40:55.0,2011-04-11 14:39:50.4,2011-04-11 14:40:55.000821\n" +
                    "3,2011-04-11 00:00:00.0,2011-04-11 14:40:55.001821,2011-04-11 14:40:55.1,2011-04-11 14:39:50.4,2011-04-11 14:40:55.001821\n" +
                    "4,2011-04-11 00:00:00.0,2011-04-11 14:40:55.002821,2011-04-11 14:40:55.2,2011-04-11 14:39:50.4,2011-04-11 14:40:55.002821\n" +
                    "5,2011-04-11 00:00:00.0,2011-04-11 14:40:55.003821,2011-04-11 14:40:55.3,2011-04-11 14:39:50.4,2011-04-11 14:40:55.003821\n" +
                    "6,2011-04-11 00:00:00.0,2011-04-11 14:40:55.004821,2011-04-11 14:40:55.4,2011-04-11 14:39:50.4,2011-04-11 14:40:55.004821\n" +
                    "7,2011-04-11 00:00:00.0,2011-04-11 14:40:55.005821,2011-04-11 14:40:55.5,2011-04-11 14:39:50.4,2011-04-11 14:40:55.005821\n" +
                    "8,2011-04-11 00:00:00.0,2011-04-11 14:40:55.006821,2011-04-11 14:40:55.6,2011-04-11 14:39:50.4,2011-04-11 14:40:55.006821\n" +
                    "9,2011-04-11 00:00:00.0,2011-04-11 14:40:55.007821,2011-04-11 14:40:55.7,2011-04-11 14:39:50.4,2011-04-11 14:40:55.007821\n" +
                    "10,2011-04-11 00:00:00.0,2011-04-11 14:40:55.008821,2011-04-11 14:40:55.8,2011-04-11 14:39:50.4,2011-04-11 14:40:55.008821\n" +
                    "11,2011-04-11 00:00:00.0,2011-04-11 14:40:55.009821,2011-04-11 14:40:55.9,2011-04-11 14:39:50.4,2011-04-11 14:40:55.009821\n" +
                    "12,2011-04-11 00:00:00.0,2011-04-11 14:40:55.010821,2011-04-11 14:40:55.1,2011-04-11 14:39:50.4,2011-04-11 14:40:55.010821\n" +
                    "13,2011-04-11 00:00:00.0,2011-04-11 14:40:55.011821,2011-04-11 14:40:55.11,2011-04-11 14:39:50.4,2011-04-11 14:40:55.011821\n" +
                    "14,2011-04-11 00:00:00.0,2011-04-11 14:40:55.012821,2011-04-11 14:40:55.12,2011-04-11 14:39:50.4,2011-04-11 14:40:55.012821\n" +
                    "15,2011-04-11 00:00:00.0,2011-04-11 14:40:55.013821,2011-04-11 14:40:55.13,2011-04-11 14:39:50.4,2011-04-11 14:40:55.013821\n" +
                    "16,2011-04-11 00:00:00.0,2011-04-11 14:40:55.014821,2011-04-11 14:40:55.14,2011-04-11 14:39:50.4,2011-04-11 14:40:55.014821\n" +
                    "17,2011-04-11 00:00:00.0,2011-04-11 14:40:55.015821,2011-04-11 14:40:55.15,2011-04-11 14:39:50.4,2011-04-11 14:40:55.015821\n" +
                    "18,2011-04-11 00:00:00.0,2011-04-11 14:40:55.016821,2011-04-11 14:40:55.16,2011-04-11 14:39:50.4,2011-04-11 14:40:55.016821\n" +
                    "19,2011-04-11 00:00:00.0,2011-04-11 14:40:55.017821,2011-04-11 14:40:55.17,2011-04-11 14:39:50.4,2011-04-11 14:40:55.017821\n" +
                    "20,2011-04-11 00:00:00.0,2011-04-11 14:40:55.018821,2011-04-11 14:40:55.18,2011-04-11 14:39:50.4,2011-04-11 14:40:55.018821\n" +
                    "21,2011-04-11 00:00:00.0,2011-04-11 14:40:55.019821,2011-04-11 14:40:55.19,2011-04-11 14:39:50.4,2011-04-11 14:40:55.019821\n" +
                    "22,2011-04-11 00:00:00.0,2011-04-11 14:40:55.020821,2011-04-11 14:40:55.2,2011-04-11 14:39:50.4,2011-04-11 14:40:55.020821\n" +
                    "23,2011-04-11 00:00:00.0,2011-04-11 14:40:55.021821,2011-04-11 14:40:55.21,2011-04-11 14:39:50.4,2011-04-11 14:40:55.021821\n" +
                    "24,2011-04-11 00:00:00.0,2011-04-11 14:40:55.022821,2011-04-11 14:40:55.22,2011-04-11 14:39:50.4,2011-04-11 14:40:55.022821\n" +
                    "25,2011-04-11 00:00:00.0,2011-04-11 14:40:55.023821,2011-04-11 14:40:55.23,2011-04-11 14:39:50.4,2011-04-11 14:40:55.023821\n" +
                    "26,2011-04-11 00:00:00.0,2011-04-11 14:40:55.024821,2011-04-11 14:40:55.24,2011-04-11 14:39:50.4,2011-04-11 14:40:55.024821\n" +
                    "27,2011-04-11 00:00:00.0,2011-04-11 14:40:55.025821,2011-04-11 14:40:55.25,2011-04-11 14:39:50.4,2011-04-11 14:40:55.025821\n" +
                    "28,2011-04-11 00:00:00.0,2011-04-11 14:40:55.026821,2011-04-11 14:40:55.26,2011-04-11 14:39:50.4,2011-04-11 14:40:55.026821\n" +
                    "29,2011-04-11 00:00:00.0,2011-04-11 14:40:55.027821,2011-04-11 14:40:55.27,2011-04-11 14:39:50.4,2011-04-11 14:40:55.027821\n" +
                    "30,2011-04-11 00:00:00.0,2011-04-11 14:40:55.028821,2011-04-11 14:40:55.28,2011-04-11 14:39:50.4,2011-04-11 14:40:55.028821\n" +
                    "31,2011-04-11 00:00:00.0,2011-04-11 14:40:55.029821,2011-04-11 14:40:55.29,2011-04-11 14:39:50.4,2011-04-11 14:40:55.029821\n" +
                    "32,2011-04-11 00:00:00.0,2011-04-11 14:40:55.030821,2011-04-11 14:40:55.3,2011-04-11 14:39:50.4,2011-04-11 14:40:55.030821\n" +
                    "33,2011-04-11 00:00:00.0,2011-04-11 14:40:55.031821,2011-04-11 14:40:55.31,2011-04-11 14:39:50.4,2011-04-11 14:40:55.031821\n" +
                    "34,2011-04-11 00:00:00.0,2011-04-11 14:40:55.032821,2011-04-11 14:40:55.32,2011-04-11 14:39:50.4,2011-04-11 14:40:55.032821\n" +
                    "35,2011-04-11 00:00:00.0,2011-04-11 14:40:55.033821,2011-04-11 14:40:55.33,2011-04-11 14:39:50.4,2011-04-11 14:40:55.033821\n" +
                    "36,2011-04-11 00:00:00.0,2011-04-11 14:40:55.034821,2011-04-11 14:40:55.34,2011-04-11 14:39:50.4,2011-04-11 14:40:55.034821\n" +
                    "37,2011-04-11 00:00:00.0,2011-04-11 14:40:55.035821,2011-04-11 14:40:55.35,2011-04-11 14:39:50.4,2011-04-11 14:40:55.035821\n" +
                    "38,2011-04-11 00:00:00.0,2011-04-11 14:40:55.036821,2011-04-11 14:40:55.36,2011-04-11 14:39:50.4,2011-04-11 14:40:55.036821\n" +
                    "39,2011-04-11 00:00:00.0,2011-04-11 14:40:55.037821,2011-04-11 14:40:55.37,2011-04-11 14:39:50.4,2011-04-11 14:40:55.037821\n" +
                    "40,2011-04-11 00:00:00.0,2011-04-11 14:40:55.038821,2011-04-11 14:40:55.38,2011-04-11 14:39:50.4,2011-04-11 14:40:55.038821\n" +
                    "41,2011-04-11 00:00:00.0,2011-04-11 14:40:55.039821,2011-04-11 14:40:55.39,2011-04-11 14:39:50.4,2011-04-11 14:40:55.039821\n" +
                    "42,2011-04-11 00:00:00.0,2011-04-11 14:40:55.040821,2011-04-11 14:40:55.4,2011-04-11 14:39:50.4,2011-04-11 14:40:55.040821\n" +
                    "43,2011-04-11 00:00:00.0,2011-04-11 14:40:55.041821,2011-04-11 14:40:55.41,2011-04-11 14:39:50.4,2011-04-11 14:40:55.041821\n" +
                    "44,2011-04-11 00:00:00.0,2011-04-11 14:40:55.042821,2011-04-11 14:40:55.42,2011-04-11 14:39:50.4,2011-04-11 14:40:55.042821\n" +
                    "45,2011-04-11 00:00:00.0,2011-04-11 14:40:55.043821,2011-04-11 14:40:55.43,2011-04-11 14:39:50.4,2011-04-11 14:40:55.043821\n" +
                    "46,2011-04-11 00:00:00.0,2011-04-11 14:40:55.044821,2011-04-11 14:40:55.44,2011-04-11 14:39:50.4,2011-04-11 14:40:55.044821\n" +
                    "47,2011-04-11 00:00:00.0,2011-04-11 14:40:55.045821,2011-04-11 14:40:55.45,2011-04-11 14:39:50.4,2011-04-11 14:40:55.045821\n" +
                    "48,2011-04-11 00:00:00.0,2011-04-11 14:40:55.046821,2011-04-11 14:40:55.46,2011-04-11 14:39:50.4,2011-04-11 14:40:55.046821\n" +
                    "49,2011-04-11 00:00:00.0,2011-04-11 14:40:55.047821,2011-04-11 14:40:55.47,2011-04-11 14:39:50.4,2011-04-11 14:40:55.047821\n" +
                    "50,2011-04-11 00:00:00.0,2011-04-11 14:40:55.048821,2011-04-11 14:40:55.48,2011-04-11 14:39:50.4,2011-04-11 14:40:55.048821\n" +
                    "51,2011-04-11 00:00:00.0,2011-04-11 14:40:55.049821,2011-04-11 14:40:55.49,2011-04-11 14:39:50.4,2011-04-11 14:40:55.049821\n" +
                    "52,2011-04-11 00:00:00.0,2011-04-11 14:40:55.050821,2011-04-11 14:40:55.5,2011-04-11 14:39:50.4,2011-04-11 14:40:55.050821\n" +
                    "53,2011-04-11 00:00:00.0,2011-04-11 14:40:55.051821,2011-04-11 14:40:55.51,2011-04-11 14:39:50.4,2011-04-11 14:40:55.051821\n" +
                    "54,2011-04-11 00:00:00.0,2011-04-11 14:40:55.052821,2011-04-11 14:40:55.52,2011-04-11 14:39:50.4,2011-04-11 14:40:55.052821\n" +
                    "55,2011-04-11 00:00:00.0,2011-04-11 14:40:55.053821,2011-04-11 14:40:55.53,2011-04-11 14:39:50.4,2011-04-11 14:40:55.053821\n" +
                    "56,2011-04-11 00:00:00.0,2011-04-11 14:40:55.054821,2011-04-11 14:40:55.54,2011-04-11 14:39:50.4,2011-04-11 14:40:55.054821\n" +
                    "57,2011-04-11 00:00:00.0,2011-04-11 14:40:55.055821,2011-04-11 14:40:55.55,2011-04-11 14:39:50.4,2011-04-11 14:40:55.055821\n" +
                    "58,2011-04-11 00:00:00.0,2011-04-11 14:40:55.056821,2011-04-11 14:40:55.56,2011-04-11 14:39:50.4,2011-04-11 14:40:55.056821\n" +
                    "59,2011-04-11 00:00:00.0,2011-04-11 14:40:55.057821,2011-04-11 14:40:55.57,2011-04-11 14:39:50.4,2011-04-11 14:40:55.057821\n" +
                    "60,2011-04-11 00:00:00.0,2011-04-11 14:40:55.058821,2011-04-11 14:40:55.58,2011-04-11 14:39:50.4,2011-04-11 14:40:55.058821\n" +
                    "61,2011-04-11 00:00:00.0,2011-04-11 14:40:55.059821,2011-04-11 14:40:55.59,2011-04-11 14:39:50.4,2011-04-11 14:40:55.059821\n" +
                    "62,2011-04-11 00:00:00.0,2011-04-11 14:40:55.060821,2011-04-11 14:40:55.6,2011-04-11 14:39:50.4,2011-04-11 14:40:55.060821\n" +
                    "63,2011-04-11 00:00:00.0,2011-04-11 14:40:55.061821,2011-04-11 14:40:55.61,2011-04-11 14:39:50.4,2011-04-11 14:40:55.061821\n" +
                    "64,2011-04-11 00:00:00.0,2011-04-11 14:40:55.062821,2011-04-11 14:40:55.62,2011-04-11 14:39:50.4,2011-04-11 14:40:55.062821\n" +
                    "65,2011-04-11 00:00:00.0,2011-04-11 14:40:55.063821,2011-04-11 14:40:55.63,2011-04-11 14:39:50.4,2011-04-11 14:40:55.063821\n" +
                    "66,2011-04-11 00:00:00.0,2011-04-11 14:40:55.064821,2011-04-11 14:40:55.64,2011-04-11 14:39:50.4,2011-04-11 14:40:55.064821\n" +
                    "67,2011-04-11 00:00:00.0,2011-04-11 14:40:55.065821,2011-04-11 14:40:55.65,2011-04-11 14:39:50.4,2011-04-11 14:40:55.065821\n" +
                    "68,2011-04-11 00:00:00.0,2011-04-11 14:40:55.066821,2011-04-11 14:40:55.66,2011-04-11 14:39:50.4,2011-04-11 14:40:55.066821\n" +
                    "69,2011-04-11 00:00:00.0,2011-04-11 14:40:55.067821,2011-04-11 14:40:55.67,2011-04-11 14:39:50.4,2011-04-11 14:40:55.067821\n" +
                    "70,2011-04-11 00:00:00.0,2011-04-11 14:40:55.068821,2011-04-11 14:40:55.68,2011-04-11 14:39:50.4,2011-04-11 14:40:55.068821\n" +
                    "71,2011-04-11 00:00:00.0,2011-04-11 14:40:55.069821,2011-04-11 14:40:55.69,2011-04-11 14:39:50.4,2011-04-11 14:40:55.069821\n" +
                    "72,2011-04-11 00:00:00.0,2011-04-11 14:40:55.070821,2011-04-11 14:40:55.7,2011-04-11 14:39:50.4,2011-04-11 14:40:55.070821\n" +
                    "73,2011-04-11 00:00:00.0,2011-04-11 14:40:55.071821,2011-04-11 14:40:55.71,2011-04-11 14:39:50.4,2011-04-11 14:40:55.071821\n" +
                    "74,2011-04-11 00:00:00.0,2011-04-11 14:40:55.072821,2011-04-11 14:40:55.72,2011-04-11 14:39:50.4,2011-04-11 14:40:55.072821\n" +
                    "75,2011-04-11 00:00:00.0,2011-04-11 14:40:55.073821,2011-04-11 14:40:55.73,2011-04-11 14:39:50.4,2011-04-11 14:40:55.073821\n" +
                    "76,2011-04-11 00:00:00.0,2011-04-11 14:40:55.074821,2011-04-11 14:40:55.74,2011-04-11 14:39:50.4,2011-04-11 14:40:55.074821\n" +
                    "77,2011-04-11 00:00:00.0,2011-04-11 14:40:55.075821,2011-04-11 14:40:55.75,2011-04-11 14:39:50.4,2011-04-11 14:40:55.075821\n" +
                    "78,2011-04-11 00:00:00.0,2011-04-11 14:40:55.076821,2011-04-11 14:40:55.76,2011-04-11 14:39:50.4,2011-04-11 14:40:55.076821\n" +
                    "79,2011-04-11 00:00:00.0,2011-04-11 14:40:55.077821,2011-04-11 14:40:55.77,2011-04-11 14:39:50.4,2011-04-11 14:40:55.077821\n" +
                    "80,2011-04-11 00:00:00.0,2011-04-11 14:40:55.078821,2011-04-11 14:40:55.78,2011-04-11 14:39:50.4,2011-04-11 14:40:55.078821\n" +
                    "81,2011-04-11 00:00:00.0,2011-04-11 14:40:55.079821,2011-04-11 14:40:55.79,2011-04-11 14:39:50.4,2011-04-11 14:40:55.079821\n" +
                    "82,2011-04-11 00:00:00.0,2011-04-11 14:40:55.080821,2011-04-11 14:40:55.8,2011-04-11 14:39:50.4,2011-04-11 14:40:55.080821\n" +
                    "83,2011-04-11 00:00:00.0,2011-04-11 14:40:55.081821,2011-04-11 14:40:55.81,2011-04-11 14:39:50.4,2011-04-11 14:40:55.081821\n" +
                    "84,2011-04-11 00:00:00.0,2011-04-11 14:40:55.082821,2011-04-11 14:40:55.82,2011-04-11 14:39:50.4,2011-04-11 14:40:55.082821\n" +
                    "85,2011-04-11 00:00:00.0,2011-04-11 14:40:55.083821,2011-04-11 14:40:55.83,2011-04-11 14:39:50.4,2011-04-11 14:40:55.083821\n" +
                    "86,2011-04-11 00:00:00.0,2011-04-11 14:40:55.084821,2011-04-11 14:40:55.84,2011-04-11 14:39:50.4,2011-04-11 14:40:55.084821\n" +
                    "87,2011-04-11 00:00:00.0,2011-04-11 14:40:55.085821,2011-04-11 14:40:55.85,2011-04-11 14:39:50.4,2011-04-11 14:40:55.085821\n" +
                    "88,2011-04-11 00:00:00.0,2011-04-11 14:40:55.086821,2011-04-11 14:40:55.86,2011-04-11 14:39:50.4,2011-04-11 14:40:55.086821\n" +
                    "89,2011-04-11 00:00:00.0,2011-04-11 14:40:55.087821,2011-04-11 14:40:55.87,2011-04-11 14:39:50.4,2011-04-11 14:40:55.087821\n";

            try (
                    final PGWireServer ignored = createPGServer(4);
                    final Connection connection = getConnection(simpleQueryMode, binary)
            ) {
                //
                // test methods of inserting QuestDB's DATA and TIMESTAMP values
                //
                final PreparedStatement statement = connection.prepareStatement("create table x (a int, d date, t timestamp, d1 date, t1 timestamp, t2 timestamp) timestamp(t)");
                statement.execute();

                // exercise parameters on select statement
                PreparedStatement select = connection.prepareStatement("x where a = ?");
                execSelectWithParam(select, 9);


                try (final PreparedStatement insert = connection.prepareStatement("insert into x values (?, ?, ?, ?, ?, ?)")) {
                    long micros = TimestampFormatUtils.parseTimestamp("2011-04-11T14:40:54.998821Z");
                    for (int i = 0; i < 90; i++) {
                        insert.setInt(1, i);
                        // DATE as jdbc's DATE
                        // jdbc's DATE takes millis from epoch and i think it removes time element from it, leaving
                        // just date
                        insert.setDate(2, new Date(micros / 1000));

                        // TIMESTAMP as jdbc's TIMESTAMP, this should keep the micros
                        insert.setTimestamp(3, new Timestamp(micros));

                        // DATE as jdbc's TIMESTAMP, this should keep millis and we need to supply millis
                        insert.setTimestamp(4, new Timestamp(micros / 1000L));

                        // TIMESTAMP as jdbc's DATE, DATE takes millis and throws them away
                        insert.setDate(5, new Date(micros));

                        // TIMESTAMP as PG specific TIMESTAMP type
                        insert.setTimestamp(6, new PGTimestamp(micros));

                        insert.execute();
                        Assert.assertEquals(1, insert.getUpdateCount());
                        micros += 1000;
                    }
                }

                try (ResultSet resultSet = connection.prepareStatement("x").executeQuery()) {
                    sink.clear();
                    assertResultSet(expectedAll, sink, resultSet);
                }

                TestUtils.assertEquals(expectedAll, sink);

                // exercise parameters on select statement
                execSelectWithParam(select, 9);
                TestUtils.assertEquals("9\n", sink);

                execSelectWithParam(select, 11);
                TestUtils.assertEquals("11\n", sink);

            }
        });
    }

    private void testInsertAllTypes(boolean binary) throws Exception {
        assertMemoryLeak(() -> {
            compiler.compile("create table xyz (" +
                            "a byte," +
                            "b char," +
                            "c short," +
                            "d int," +
                            "e long," +
                            "f float," +
                            "g double," +
                            "h string," +
                            "i symbol," +
                            "j boolean," +
                            "k long256" +
                            ")",
                    sqlExecutionContext
            );
            try (
                    final PGWireServer ignored = createPGServer(2);
                    final Connection connection = getConnection(false, binary);
                    final PreparedStatement insert = connection.prepareStatement(
                            "insert into xyz values (?,?,?,?,?,?,?,?,?,?,?)"
                    )
            ) {
                final Rnd rnd = new Rnd();
                connection.setAutoCommit(false);
                for (int i = 0; i < 10_000; i++) {
                    if (rnd.nextInt() % 4 > 0) {
                        insert.setByte(1, rnd.nextByte());
                    } else {
                        insert.setNull(1, Types.SMALLINT);
                    }

                    if (rnd.nextInt() % 4 > 0) {
                        insert.setByte(2, (byte) rnd.nextChar());
                    } else {
                        insert.setNull(2, Types.SMALLINT);
                    }

                    if (rnd.nextInt() % 4 > 0) {
                        insert.setShort(3, rnd.nextShort());
                    } else {
                        insert.setNull(3, Types.SMALLINT);
                    }

                    if (rnd.nextInt() % 4 > 0) {
                        insert.setInt(4, rnd.nextInt());
                    } else {
                        insert.setNull(4, Types.INTEGER);
                    }

                    if (rnd.nextInt() % 4 > 0) {
                        insert.setLong(5, rnd.nextLong());
                    } else {
                        insert.setNull(5, Types.BIGINT);
                    }

                    if (rnd.nextInt() % 4 > 0) {
                        insert.setFloat(6, rnd.nextFloat());
                    } else {
                        insert.setNull(6, Types.REAL);
                    }

                    if (rnd.nextInt() % 4 > 0) {
                        insert.setDouble(7, rnd.nextDouble());
                    } else {
                        insert.setNull(7, Types.FLOAT);
                    }

                    if (rnd.nextInt() % 4 > 0) {
                        insert.setString(8, "hello21");
                    } else {
                        insert.setNull(8, Types.VARCHAR);
                    }

                    if (rnd.nextInt() % 4 > 0) {
                        insert.setString(9, "bus");
                    } else {
                        insert.setNull(9, Types.VARCHAR);
                    }

                    if (rnd.nextInt() % 4 > 0) {
                        insert.setBoolean(10, true);
                    } else {
                        insert.setNull(10, Types.BOOLEAN);
                    }

                    if (rnd.nextInt() % 4 > 0) {
                        insert.setString(11, "05a9796963abad00001e5f6bbdb38");
                    } else {
                        insert.setNull(11, Types.VARCHAR);
                    }
                    insert.execute();
                    Assert.assertEquals(1, insert.getUpdateCount());
                }
                connection.commit();

                rnd.reset();
                try (RecordCursorFactory factory = compiler.compile("xyz", sqlExecutionContext).getRecordCursorFactory()) {
                    try (RecordCursor cursor = factory.getCursor(sqlExecutionContext)) {
                        final Record record = cursor.getRecord();
                        int count = 0;
                        while (cursor.hasNext()) {

                            if (rnd.nextInt() % 4 > 0) {
                                Assert.assertEquals(rnd.nextByte(), record.getByte(0));
                            } else {
                                Assert.assertEquals(0, record.getByte(0));
                            }

                            if (rnd.nextInt() % 4 > 0) {
                                Assert.assertEquals(rnd.nextChar(), record.getChar(1));
                            } else {
                                Assert.assertEquals(0, record.getChar(1));
                            }

                            if (rnd.nextInt() % 4 > 0) {
                                Assert.assertEquals(rnd.nextShort(), record.getShort(2));
                            } else {
                                Assert.assertEquals(0, record.getShort(2));
                            }

                            if (rnd.nextInt() % 4 > 0) {
                                Assert.assertEquals(rnd.nextInt(), record.getInt(3));
                            } else {
                                Assert.assertEquals(Numbers.INT_NaN, record.getInt(3));
                            }

                            if (rnd.nextInt() % 4 > 0) {
                                Assert.assertEquals(rnd.nextLong(), record.getLong(4));
                            } else {
                                Assert.assertEquals(Numbers.LONG_NaN, record.getLong(4));
                            }

                            if (rnd.nextInt() % 4 > 0) {
                                Assert.assertEquals(rnd.nextFloat(), record.getFloat(5), 0.0001f);
                            } else {
                                Assert.assertTrue(record.getFloat(5) != record.getFloat(5));
                            }

                            if (rnd.nextInt() % 4 > 0) {
                                Assert.assertEquals(rnd.nextDouble(), record.getDouble(6), 0.000001);
                            } else {
                                Assert.assertTrue(record.getDouble(6) != record.getDouble(6));
                            }

                            if (rnd.nextInt() % 4 > 0) {
                                TestUtils.assertEquals("hello21", record.getStr(7));
                            } else {
                                Assert.assertNull(record.getStr(7));
                            }

                            if (rnd.nextInt() % 4 > 0) {
                                TestUtils.assertEquals("bus", record.getSym(8));
                            } else {
                                Assert.assertNull(record.getSym(8));
                            }

                            if (rnd.nextInt() % 4 > 0) {
                                Assert.assertTrue(record.getBool(9));
                            } else {
                                Assert.assertFalse(record.getBool(9));
                            }

                            sink.clear();
                            record.getLong256(10, sink);
                            if (rnd.nextInt() % 4 > 0) {
                                TestUtils.assertEquals("0x5a9796963abad00001e5f6bbdb38", sink);
                            } else {

                                Assert.assertEquals(0, sink.length());
                            }
                            count++;
                        }

                        Assert.assertEquals(10_000, count);
                    }
                }
            }
        });
    }

    private void testInsertBinaryBindVariable(boolean binaryProtocol) throws Exception {
        assertMemoryLeak(() -> {
            compiler.compile("create table xyz (" +
                            "a binary" +
                            ")",
                    sqlExecutionContext
            );
            try (
                    final PGWireServer ignored = createPGServer(2);
                    final Connection connection = getConnection(false, binaryProtocol);
                    final PreparedStatement insert = connection.prepareStatement(
                            "insert into xyz values (?)"
                    )
            ) {
                connection.setAutoCommit(false);
                int totalCount = 10;
                for (int i = 0; i < totalCount; i++) {
                    insert.setBytes(1, new byte[]{1, 2, 3, 4});
                    insert.execute();
                }
                connection.commit();

                try (RecordCursorFactory factory = compiler.compile("xyz", sqlExecutionContext).getRecordCursorFactory()) {
                    try (RecordCursor cursor = factory.getCursor(sqlExecutionContext)) {
                        final Record record = cursor.getRecord();
                        int count = 0;
                        while (cursor.hasNext()) {
                            Assert.assertEquals(4, record.getBinLen(0));
                            count++;
                        }

                        Assert.assertEquals(totalCount, count);
                    }
                }
            }
        });
    }

    private void testInsertTableDoesNotExist(boolean simple, String expectedError) throws Exception {
        // we are going to:
        // 1. create a table
        // 2. insert a record
        // 3. drop table
        // 4. attempt to insert a record (should fail)
        assertMemoryLeak(() -> {
            try (
                    final PGWireServer ignored = createPGServer(2);
                    final Connection connection = getConnection(simple, true)
            ) {
                PreparedStatement statement = connection.prepareStatement("create table x (a int)");
                statement.execute();

                // exercise parameters on select statement
                PreparedStatement select = connection.prepareStatement("x where a = ?");
                execSelectWithParam(select, 9);

                PreparedStatement insert = connection.prepareStatement("insert into x (a) values (?)");
                insert.setInt(1, 1);
                insert.execute();

                PreparedStatement drop = connection.prepareStatement("drop table x");
                drop.execute();

                try {
                    insert.setInt(1, 10);
                    insert.execute();
                    Assert.fail();
                } catch (SQLException e) {
                    TestUtils.assertContains(e.getMessage(), expectedError);
                }
            }
        });
    }

    private void testQuery(String s, String s2) throws Exception {
        TestUtils.assertMemoryLeak(() -> {
            try (
                    final PGWireServer ignore = createPGServer(2);
                    final Connection connection = getConnection(false, true)
            ) {
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "select " +
                                "rnd_str(4,4,4) s, " +
                                "rnd_int(0, 256, 4) i, " +
                                s +
                                "timestamp_sequence(0,10000) t, " +
                                "rnd_float(4) f, " +
                                "rnd_short() _short, " +
                                "rnd_long(0, 10000000, 5) l, " +
                                "rnd_timestamp(to_timestamp('2015','yyyy'),to_timestamp('2016','yyyy'),2) ts2, " +
                                "rnd_byte(0,127) bb, " +
                                "rnd_boolean() b, " +
                                "rnd_symbol(4,4,4,2), " +
                                "rnd_date(to_date('2015', 'yyyy'), to_date('2016', 'yyyy'), 2)," +
                                "rnd_bin(10,20,2), " +
                                "rnd_char(), " +
                                "rnd_long256() " +
                                "from long_sequence(50)");

                final String expected = s2 +
                        "null,57,0.6254021542412018,1970-01-01 00:00:00.0,0.462,-1593,3425232,null,121,false,PEHN,2015-03-17 04:25:52.765,00000000 19 c4 95 94 36 53 49 b4 59 7e 3b 08 a1 1e,D,0x5f20a35e80e154f458dfd08eeb9cc39ecec82869edec121bc2593f82b430328d\n" +
                        "OUOJ,77,null,1970-01-01 00:00:00.01,0.676,-7374,7777791,2015-06-19 08:47:45.603182,53,true,null,2015-11-10 09:50:33.215,00000000 8b 81 2b 93 4d 1a 8e 78 b5 b9 11 53 d0 fb 64 bb\n" +
                        "00000010 1a d4 f0,V,0xbedf29efb28cdcb1b75dccbdf1f8b84b9b27eba5e9cfa1e29660300cea7db540\n" +
                        "ICCX,205,0.8837421918800907,1970-01-01 00:00:00.02,0.054,6093,4552960,2015-07-17 00:50:59.787742,33,false,VTJW,2015-07-15 01:06:11.226,00000000 e5 61 2f 64 0e 2c 7f d7 6f b8 c9 ae 28 c7 84 47,U,0x8b4e4831499fc2a526567f4430b46b7f78c594c496995885aa1896d0ad3419d2\n" +
                        "GSHO,31,0.34947269997137365,1970-01-01 00:00:00.03,0.198,10795,6406207,2015-05-22 14:59:41.673422,56,false,null,null,00000000 49 1c f2 3c ed 39 ac a8 3b a6,S,0x7eb6d80649d1dfe38e4a7f661df6c32b2f171b3f06f6387d2fd2b4a60ba2ba3b\n" +
                        "HZEP,180,0.06944480046327317,1970-01-01 00:00:00.04,0.430,21347,null,2015-02-07 10:02:13.600956,41,false,HYRX,null,00000000 ea c3 c9 73 93 46 fe c2 d3 68 79 8b 43 1d 57 34,F,0x38e4be9e19321b57832dd27952d949d8691dd4412a2d398d4fc01e2b9fd11623\n" +
                        "HWVD,38,0.48524046868499715,1970-01-01 00:00:00.05,0.680,25579,5575751,2015-10-19 12:38:49.360294,15,false,VTJW,2015-02-06 22:58:50.333,null,Q,0x85134468025aaeb0a2f8bbebb989ba609bb0f21ac9e427283eef3f158e084362\n" +
                        "PGLU,97,0.029227696942726644,1970-01-01 00:00:00.06,0.172,-18912,8340272,2015-05-24 22:09:55.175991,111,false,VTJW,2015-11-08 21:57:22.812,00000000 d9 6f 04 ab 27 47 8f 23 3f ae 7c 9f 77 04 e9 0c\n" +
                        "00000010 ea 4e ea 8b,K,0x55d3686d5da27e14255a91b0e28abeb36c3493fcb2d0272d6046e5d137dd8f0f\n" +
                        "WIFF,104,0.892454783921197,1970-01-01 00:00:00.07,0.093,28218,4009057,2015-02-18 07:26:10.141055,89,false,HYRX,null,00000000 29 26 c5 aa da 18 ce 5f b2 8b 5c 54 90 25 c2 20\n" +
                        "00000010 ff,R,0x55b0586d1c02dfb399904624c49b6d8a7d85ee2916b209c779406ab1f85e333a\n" +
                        "CLTJ,115,0.2093569947644236,1970-01-01 00:00:00.08,0.546,-8207,2378718,2015-04-21 12:25:43.291916,31,false,PEHN,null,00000000 a5 db a1 76 1c 1c 26 fb 2e 42 fa,F,0x483c83d88ac674e3894499a1a1680580cfedff23a67d918fb49b3c24e456ad6e\n" +
                        "HFLP,79,0.9130151105125102,1970-01-01 00:00:00.09,null,14667,2513248,2015-08-31 13:16:12.318782,3,false,null,2015-02-08 12:28:36.66,null,U,0x79423d4d320d2649767a4feda060d4fb6923c0c7d965969da1b1140a2be25241\n" +
                        "GLNY,138,0.7165847318191405,1970-01-01 00:00:00.1,0.753,-2666,9337379,2015-03-25 09:21:52.776576,111,false,HYRX,2015-01-24 15:23:13.92,00000000 62 e1 4e d6 b2 57 5b e3 71 3d 20 e2 37 f2 64 43,Y,0xaac42ccbc493cf44aa6a0a1d4cdf40dd6ae4fd257e4412a07f19777ec1368055\n" +
                        "VTNP,237,0.29242748475227853,1970-01-01 00:00:00.11,0.753,-26861,2354132,2015-02-10 18:27:11.140675,56,true,null,2015-02-25 00:45:15.363,00000000 28 b6 a9 17 ec 0e 01 c4 eb 9f 13 8f bb 2a 4b,O,0x926cdd99e63abb35650d1fb462d014df59070392ef6aa389932e4b508e35428f\n" +
                        "WFOQ,255,null,1970-01-01 00:00:00.12,0.116,31569,6688277,2015-05-19 03:30:45.779999,126,true,PEHN,2015-12-09 09:57:17.78,null,E,0x4f38804270a4a64349b5760a687d8cf838cbb9ae96e9ecdc745ed9faeb513ad3\n" +
                        "EJCT,195,0.13312214396754163,1970-01-01 00:00:00.13,0.944,-3013,null,2015-11-03 14:54:47.524015,114,true,PEHN,2015-08-28 07:41:29.952,00000000 fb 9d 63 ca 94 00 6b dd 18 fe 71 76 bc 45 24 cd\n" +
                        "00000010 13 00 7c,R,0x3cfe50b9cabaf1f29e0dcffb7520ebcac48ad6b8f6962219b27b0ac7fbdee201\n" +
                        "JYYF,249,0.2000682450929353,1970-01-01 00:00:00.14,0.602,5869,2079217,2015-07-10 18:16:38.882991,44,true,HYRX,null,00000000 b7 6c 4b fb 2d 16 f3 89 a3 83 64 de d6 fd c4 5b\n" +
                        "00000010 c4 e9 19 47,P,0x85e70b46349799fe49f783d5343dd7bc3d3fe1302cd3371137fccdabf181b5ad\n" +
                        "TZOD,null,0.36078878996232167,1970-01-01 00:00:00.15,0.601,-23125,5083310,null,11,false,VTJW,2015-09-19 18:14:57.59,00000000 c5 60 b7 d1 5a 0c e9 db 51 13 4d 59 20 c9 37 a1\n" +
                        "00000010 00,E,0xcff85f9258847e03a6f2e2a772cd2f3751d822a67dff3d2375166223a6181642\n" +
                        "PBMB,76,0.23567419576658333,1970-01-01 00:00:00.16,0.571,26284,null,2015-05-21 13:14:56.349036,45,true,null,2015-09-11 09:34:39.5,00000000 97 cb f6 2c 23 45 a3 76 60 15,M,0x3c3a3b7947ce8369926cbcb16e9a2f11cfab70f2d175d0d9aeb989be79cd2b8c\n" +
                        "TKRI,201,0.2625424312419562,1970-01-01 00:00:00.17,0.915,-5486,9917162,2015-05-03 03:59:04.256719,66,false,VTJW,2015-01-15 03:22:01.33,00000000 a1 f5 4b ea 01 c9 63 b4 fc 92 60 1f df 41 ec 2c,O,0x4e3e15ad49e0a859312981a73c9dfce79022a75a739ee488eefa2920026dba88\n" +
                        "NKGQ,174,0.4039042639581232,1970-01-01 00:00:00.18,0.438,20687,7315329,2015-07-25 04:52:27.724869,20,false,PEHN,2015-06-10 22:28:57.1,00000000 92 83 fc 88 f3 32 27 70 c8 01 b0,T,0x579b14c2725d7a7e5dfbd8e23498715b8d9ee30e7bcbf83a6d1b1c80f012a4c9\n" +
                        "FUXC,52,0.7430101994511517,1970-01-01 00:00:00.19,null,-14729,1042064,2015-08-21 02:10:58.949674,28,true,CPSW,2015-08-29 20:15:51.835,null,X,0x41457ebc5a02a2b542cbd49414e022a06f4aa2dc48a9a4d99288224be334b250\n" +
                        "TGNJ,159,0.9562577128401444,1970-01-01 00:00:00.2,0.251,795,5069730,2015-07-01 01:36:57.101749,71,true,PEHN,2015-09-12 05:41:59.999,00000000 33 3f b2 67 da 98 47 47 bf 4f ea 5f 48 ed,M,0x4ba20a8e0cf7c53c9f527485c4aac4a2826f47baacd58b28700a67f6119c63bb\n" +
                        "HCNP,173,0.18684267640195917,1970-01-01 00:00:00.21,0.688,-14882,8416858,2015-06-16 19:31:59.812848,25,false,HYRX,2015-09-30 17:28:24.113,00000000 1d 5c c1 5d 2d 44 ea 00 81 c4 19 a1 ec 74 f8 10\n" +
                        "00000010 fc 6e 23,D,0x3d64559865f84c86488be951819f43042f036147c78e0b2d127ca5db2f41c5e0\n" +
                        "EZBR,243,0.8203418140538824,1970-01-01 00:00:00.22,0.221,-8447,4677168,2015-03-24 03:32:39.832378,78,false,CPSW,2015-02-16 04:04:19.82,00000000 42 67 78 47 b3 80 69 b9 14 d6 fc ee 03 22 81 b8,Q,0x721304ffe1c934386466208d506905af40c7e3bce4b28406783a3945ab682cc4\n" +
                        "ZPBH,131,0.1999576586778039,1970-01-01 00:00:00.23,0.479,-18951,874555,2015-12-22 19:13:55.404123,52,false,null,2015-10-03 05:16:17.891,null,Z,0xa944baa809a3f2addd4121c47cb1139add4f1a5641c91e3ab81f4f0ca152ec61\n" +
                        "VLTP,196,0.4104855595304533,1970-01-01 00:00:00.24,0.918,-12269,142107,2015-10-10 18:27:43.423774,92,false,PEHN,2015-02-06 18:42:24.631,null,H,0x5293ce3394424e6a5ae63bdf09a84e32bac4484bdeec40e887ec84d015101766\n" +
                        "RUMM,185,null,1970-01-01 00:00:00.25,0.838,-27649,3639049,2015-05-06 00:51:57.375784,89,true,PEHN,null,null,W,0x3166ed3bbffb858312f19057d95341886360c99923d254f38f22547ae9661423\n" +
                        "null,71,0.7409092302023607,1970-01-01 00:00:00.26,0.742,-18837,4161180,2015-04-22 10:19:19.162814,37,true,HYRX,2015-09-23 03:14:56.664,00000000 8e 93 bd 27 42 f8 25 2a 42 71 a3 7a 58 e5,D,0x689a15d8906770fcaefe0266b9f63bd6698c574248e9011c6cc84d9a6d41e0b8\n" +
                        "NGZT,214,0.18170646835643245,1970-01-01 00:00:00.27,0.841,21764,3231872,null,79,false,HYRX,2015-05-20 07:51:29.675,00000000 ab ab ac 21 61 99 be 2d f5 30 78 6d 5a 3b,H,0x5b8def4e7a017e884a3c2c504403708b49fb8d5fe0ff283cbac6499e71ce5b30\n" +
                        "EYYP,13,null,1970-01-01 00:00:00.28,0.534,19136,4658108,2015-08-20 05:26:04.061614,5,false,CPSW,2015-03-23 23:43:37.634,00000000 c8 66 0c 40 71 ea 20 7e 43 97 27 1f 5c d9 ee 04\n" +
                        "00000010 5b 9c,C,0x6e6ed811e25486953f35987a50016bbf481e9f55c33ac48c6a22b0bd6f7b0bf2\n" +
                        "GMPL,50,0.7902682918274309,1970-01-01 00:00:00.29,0.874,-27807,5693029,2015-07-14 21:06:07.975747,37,true,CPSW,2015-09-01 04:00:29.49,00000000 3b 4b b7 e2 7f ab 6e 23 03 dd c7 d6,U,0x72c607b1992ff2f8802e839b77a4a2d34b8b967c412e7c895b509b55d1c38d29\n" +
                        "BCZI,207,0.10863061577000221,1970-01-01 00:00:00.3,0.129,3999,121232,null,88,true,CPSW,2015-05-10 21:10:20.41,00000000 97 0b f5 ef 3b be 85 7c 11 f7 34,K,0x33be4c04695f74d776ac6df71a221f518f3c64248fb5943ea55ab4e6916f3f6c\n" +
                        "DXUU,139,null,1970-01-01 00:00:00.31,0.262,-15289,341060,2015-01-06 07:48:24.624773,110,false,null,2015-07-08 18:37:16.872,00000000 71 cf 5a 8f 21 06 b2 3f 0e 41 93 89 27 ca 10 2f\n" +
                        "00000010 60 ce,N,0x1c05d81633694e02795ebacfceb0c7dd7ec9b7e9c634bc791283140ab775531c\n" +
                        "FMDV,197,0.2522102209201954,1970-01-01 00:00:00.32,0.993,-26026,5396438,null,83,true,CPSW,null,00000000 86 75 ad a5 2d 49 48 68 36 f0 35,K,0x308a7a4966e65a0160b00229634848957fa67d6a419e1721b1520f66caa74945\n" +
                        "SQCN,62,0.11500943478849246,1970-01-01 00:00:00.33,0.595,1011,4631412,null,56,false,VTJW,null,null,W,0x66906dc1f1adbc206a8bf627c859714a6b841d6c6c8e44ce147261f8689d9250\n" +
                        "QSCM,130,0.8671405978559277,1970-01-01 00:00:00.34,0.428,22899,403193,null,21,true,PEHN,2015-11-30 21:04:32.865,00000000 a0 ba a5 d1 63 ca 32 e5 0d 68 52 c6 94 c3 18 c9\n" +
                        "00000010 7c,I,0x3dcc3621f3734c485bb81c28ec2ddb0163def06fb4e695dc2bfa47b82318ff9f\n" +
                        "UUZI,196,0.9277429447320458,1970-01-01 00:00:00.35,0.625,24355,5761736,null,116,false,null,2015-02-04 07:15:26.997,null,B,0xb0a5224248b093a067eee4529cce26c37429f999bffc9548aa3df14bfed42969\n" +
                        "DEQN,41,0.9028381160965113,1970-01-01 00:00:00.36,0.120,29066,2545404,2015-04-07 21:58:14.714791,125,false,PEHN,2015-02-06 23:29:49.836,00000000 ec 4b 97 27 df cd 7a 14 07 92 01,I,0x55016acb254b58cd3ce05caab6551831683728ff2f725aa1ba623366c2d08e6a\n" +
                        "null,164,0.7652775387729266,1970-01-01 00:00:00.37,0.312,-8563,7684501,2015-02-01 12:38:28.322282,0,true,HYRX,2015-07-16 20:11:51.34,null,F,0x97af9db84b80545ecdee65143cbc92f89efea4d0456d90f29dd9339572281042\n" +
                        "QJPL,160,0.1740035812230043,1970-01-01 00:00:00.38,0.763,5991,2099269,2015-02-25 15:49:06.472674,65,true,VTJW,2015-04-23 11:15:13.65,00000000 de 58 45 d0 1b 58 be 33 92 cd 5c 9d,E,0xa85a5fc20776e82b36c1cdbfe34eb2636eec4ffc0b44f925b09ac4f09cb27f36\n" +
                        "BKUN,208,0.4452148524967028,1970-01-01 00:00:00.39,0.582,17928,6383721,2015-10-23 07:12:20.730424,7,false,null,2015-01-02 17:04:58.959,00000000 5e 37 e4 68 2a 96 06 46 b6 aa,F,0xe1d2020be2cb7be9c5b68f9ea1bd30c789e6d0729d44b64390678b574ed0f592\n" +
                        "REDS,4,0.03804995327454719,1970-01-01 00:00:00.4,0.103,2358,1897491,2015-07-21 16:34:14.571565,75,false,CPSW,2015-07-30 16:04:46.726,00000000 d6 88 3a 93 ef 24 a5 e2 bc 86,P,0x892458b34e8769928647166465305ef1dd668040845a10a38ea5fba6cf9bfc92\n" +
                        "MPVR,null,null,1970-01-01 00:00:00.41,0.592,8754,5828044,2015-10-05 21:11:10.600851,116,false,CPSW,null,null,H,0x9d1e67c6be2f24b2a4e2cc6a628c94395924dadabaed7ee459b2a61b0fcb74c5\n" +
                        "KKNZ,186,0.8223388398922372,1970-01-01 00:00:00.42,0.720,-6179,8728907,null,80,true,VTJW,2015-09-11 03:49:12.244,00000000 16 b2 d8 83 f5 95 7c 95 fd 52 bb 50 c9,B,0x55724661cfcc811f4482e1a2ba8efaef6e4aef0394801c40941d89f24081f64d\n" +
                        "BICL,182,0.7215695095610233,1970-01-01 00:00:00.43,0.227,-22899,6401660,2015-08-23 18:31:29.931618,78,true,null,null,null,T,0xbbb751ee10f060d1c2fbeb73044504aea55a8e283bcf857b539d8cd889fa9c91\n" +
                        "SWPF,null,0.48770772310128674,1970-01-01 00:00:00.44,0.914,-17929,8377336,2015-12-13 23:04:20.465454,28,false,HYRX,2015-10-31 13:37:01.327,00000000 b2 31 9c 69 be 74 9a ad cc cf b8 e4 d1 7a 4f,I,0xbe91d734443388a2a631d716b575c819c9224a25e3f6e6fa6cd78093d5e7ea16\n" +
                        "BHEV,80,0.8917678500174907,1970-01-01 00:00:00.45,0.237,29284,9577513,2015-10-20 07:38:23.889249,27,false,HYRX,2015-12-15 13:32:56.797,00000000 92 83 24 53 60 4d 04 c2 f0 7a 07 d4 a3 d1 5f 0d\n" +
                        "00000010 fe 63 10 0d,V,0x225fddd0f4325a9d8634e1cb317338a0d3cb7f61737f167dc902b6f6d779c753\n" +
                        "DPCH,62,0.6684502332750604,1970-01-01 00:00:00.46,0.879,-22600,9266553,null,89,true,VTJW,2015-05-25 19:42:17.955,00000000 35 1b b9 0f 97 f5 77 7e a3 2d ce fe eb cd 47 06\n" +
                        "00000010 53 61 97,S,0x89d6a43b23f83695b236ae5ffab54622ce1f4dac846490a8b88f0468c0cbfa33\n" +
                        "MKNJ,61,0.2682009935575007,1970-01-01 00:00:00.47,0.813,-1322,null,2015-11-04 08:11:39.996132,4,false,CPSW,2015-07-29 22:51:03.349,00000000 82 08 fb e7 94 3a 32 5d 8a 66 0b e4 85 f1 13 06\n" +
                        "00000010 f2 27,V,0x9890d4aea149f0498bdef1c6ba16dd8cbd01cf83632884ae8b7083f888554b0c\n" +
                        "GSQI,158,0.8047954890194065,1970-01-01 00:00:00.48,0.347,23139,1252385,2015-04-22 00:10:12.067311,32,true,null,2015-01-09 06:06:32.213,00000000 38 a7 85 46 1a 27 5b 4d 0f 33 f4 70,V,0xc0e6e110b909e13a812425a38162be0bb65e29ed529d4dba868a7075f3b34357\n" +
                        "BPTU,205,0.430214712409255,1970-01-01 00:00:00.49,0.905,31266,8271557,2015-01-07 05:53:03.838005,14,true,VTJW,2015-10-30 05:33:15.819,00000000 24 0b c5 1a 5a 8d 85 50 39 42 9e 8a 86 17 89 6b,S,0x4e272e9dfde7bb12618178f7feba5021382a8c47a28fefa475d743cf0c2c4bcd\n";

                StringSink sink = new StringSink();
                // dump metadata
                assertResultSet(expected, sink, rs);
            }
        });
    }

    private void testSemicolon(boolean simpleQueryMode) throws Exception {
        assertMemoryLeak(() -> {
            try (
                    final PGWireServer ignored = createPGServer(2);
                    final Connection connection = getConnection(simpleQueryMode, true);
                    final PreparedStatement statement = connection.prepareStatement(";;")
            ) {
                statement.execute();
            }
        });
    }

    private void testStaleQueryCacheOnTableDropped(boolean simple) throws Exception {
        assertMemoryLeak(() -> {
            try (
                    final PGWireServer ignored = createPGServer(2);
                    final Connection connection = getConnection(simple, true)
            ) {
                try (CallableStatement st1 = connection.prepareCall("create table y as (" +
                        "select timestamp_sequence(0, 1000000000) timestamp," +
                        " rnd_symbol('a','b',null) symbol1 " +
                        " from long_sequence(10)" +
                        ") timestamp (timestamp)")) {
                    st1.execute();
                }

                try (PreparedStatement select = connection.prepareStatement("select timestamp, symbol1 from y")) {
                    ResultSet rs0 = select.executeQuery();
                    rs0.close();

                    connection.prepareStatement("drop table y").execute();
                    connection.prepareStatement("create table y as ( " +
                            " select " +
                            " timestamp_sequence('1970-01-01T02:30:00.000000Z', 1000000000L) timestamp " +
                            " ,rnd_str('a','b','c', 'd', 'e', 'f',null) symbol2" +
                            " ,rnd_str('a','b',null) symbol1" +
                            " from long_sequence(10)" +
                            ")").execute();

                    ResultSet rs1 = select.executeQuery();
                    sink.clear();
                    assertResultSet("timestamp[TIMESTAMP],symbol1[VARCHAR]\n" +
                            "1970-01-01 02:30:00.0,null\n" +
                            "1970-01-01 02:46:40.0,b\n" +
                            "1970-01-01 03:03:20.0,a\n" +
                            "1970-01-01 03:20:00.0,b\n" +
                            "1970-01-01 03:36:40.0,b\n" +
                            "1970-01-01 03:53:20.0,a\n" +
                            "1970-01-01 04:10:00.0,null\n" +
                            "1970-01-01 04:26:40.0,b\n" +
                            "1970-01-01 04:43:20.0,b\n" +
                            "1970-01-01 05:00:00.0,a\n", sink, rs1);

                    rs1.close();
                }
            }
        });
    }

    private void testSymbolBindVariableInFilter(boolean binary) throws Exception {
        assertMemoryLeak(() -> {
            // create and initialize table outside of PG wire
            // to ensure we do not collaterally initialize execution context on function parser
            compiler.compile("CREATE TABLE x (\n" +
                    "    ticker symbol index,\n" +
                    "    sample_time timestamp,\n" +
                    "    value int\n" +
                    ") timestamp (sample_time)", sqlExecutionContext);
            executeInsert("INSERT INTO x VALUES ('ABC',0,0)");

            try (
                    final PGWireServer ignored = createPGServer(1);
                    final Connection connection = getConnection(false, binary)
            ) {
                sink.clear();
                try (PreparedStatement ps = connection.prepareStatement("select * from x where ticker=?")) {
                    ps.setString(1, "ABC");
                    try (ResultSet rs = ps.executeQuery()) {
                        assertResultSet(
                                "ticker[VARCHAR],sample_time[TIMESTAMP],value[INTEGER]\n" +
                                        "ABC,1970-01-01 00:00:00.0,0\n",
                                sink,
                                rs
                        );
                    }
                }
            }
        });
    }

    private void testSyntaxErrorReporting(boolean simple) throws Exception {
        assertMemoryLeak(() -> {
            try (
                    final PGWireServer ignored = createPGServer(2);
                    final Connection connection = getConnection(simple, true)
            ) {
                connection.prepareCall("drop table xyz;").execute();
                Assert.fail();
            } catch (SQLException e) {
                TestUtils.assertContains(e.getMessage(), "table 'xyz' does not exist");
                TestUtils.assertEquals("00000", e.getSQLState());
            }
        });
    }

    private void testNullTypeSerialization0(boolean simple, boolean binary) throws Exception {
        try (final Connection connection = getConnection(simple, binary)) {
            sink.clear();
            try (
                    PreparedStatement ps = connection.prepareStatement("SELECT * FROM (\n" +
                            "  SELECT \n" +
                            "    n.nspname\n" +
                            "    ,c.relname\n" +
                            "    ,a.attname\n" +
                            "    ,a.atttypid\n" +
                            "    ,a.attnotnull OR (t.typtype = 'd' AND t.typnotnull) AS attnotnull\n" +
                            "    ,a.atttypmod\n" +
                            "    ,a.attlen\n" +
                            "    ,t.typtypmod\n" +
                            "    ,row_number() OVER (PARTITION BY a.attrelid ORDER BY a.attnum) AS attnum\n" +
                            "    , nullif(a.attidentity, '') as attidentity\n" +
                            "    ,null as attgenerated\n" +
                            "    ,pg_catalog.pg_get_expr(def.adbin, def.adrelid) AS adsrc\n" +
                            "    ,dsc.description\n" +
                            "    ,t.typbasetype\n" +
                            "    ,t.typtype  \n" +
                            "  FROM pg_catalog.pg_namespace n\n" +
                            "  JOIN pg_catalog.pg_class c ON (c.relnamespace = n.oid)\n" +
                            "  JOIN pg_catalog.pg_attribute a ON (a.attrelid=c.oid)\n" +
                            "  JOIN pg_catalog.pg_type t ON (a.atttypid = t.oid)\n" +
                            "  LEFT JOIN pg_catalog.pg_attrdef def ON (a.attrelid=def.adrelid AND a.attnum = def.adnum)\n" +
                            "  LEFT JOIN pg_catalog.pg_description dsc ON (c.oid=dsc.objoid AND a.attnum = dsc.objsubid)\n" +
                            "  LEFT JOIN pg_catalog.pg_class dc ON (dc.oid=dsc.classoid AND dc.relname='pg_class')\n" +
                            "  LEFT JOIN pg_catalog.pg_namespace dn ON (dc.relnamespace=dn.oid AND dn.nspname='pg_catalog')\n" +
                            "  WHERE \n" +
                            "    c.relkind in ('r','p','v','f','m')\n" +
                            "    and a.attnum > 0 \n" +
                            "    AND NOT a.attisdropped\n" +
                            "    AND c.relname LIKE E'test'\n" +
                            "  ) c WHERE true\n" +
                            "  ORDER BY nspname,c.relname,attnum;\n");
                    ResultSet rs = ps.executeQuery()
            ) {
                assertResultSet(
                        "nspname[VARCHAR],relname[VARCHAR],attname[VARCHAR],atttypid[INTEGER],attnotnull[BIT],atttypmod[INTEGER],attlen[SMALLINT],typtypmod[INTEGER],attnum[BIGINT],attidentity[VARCHAR],attgenerated[VARCHAR],adsrc[VARCHAR],description[VARCHAR],typbasetype[INTEGER],typtype[CHAR]\n" +
                                "public,test,x,20,false,0,8,0,0,null,null,null,null,0,b\n",
                        sink,
                        rs
                );
            }
        }
    }

    private static class DelayingNetworkFacade extends NetworkFacadeImpl {
        private final AtomicBoolean delaying = new AtomicBoolean(false);
        private final AtomicInteger delayedAttemptsCounter = new AtomicInteger(0);

        @Override
        public int send(long fd, long buffer, int bufferLen) {
            if (!delaying.get()) {
                return super.send(fd, buffer, bufferLen);
            }

            if (delayedAttemptsCounter.decrementAndGet() < 0) {
                delaying.set(false);
            }
            return 0;
        }

        void startDelaying() {
            delayedAttemptsCounter.set(1000);
            delaying.set(true);
        }
    }

    @FunctionalInterface
    interface OnTickAction {
        void run(TableWriter writer);
    }
}
