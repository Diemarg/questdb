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

package io.questdb.griffin.engine.groupby.vect;

import io.questdb.cairo.ArrayColumnTypes;
import io.questdb.cairo.ColumnType;
import io.questdb.cairo.sql.Record;
import io.questdb.griffin.engine.functions.IntFunction;
import io.questdb.std.Rosti;
import io.questdb.std.Unsafe;
import io.questdb.std.Vect;

import java.util.concurrent.atomic.LongAccumulator;
import java.util.function.LongBinaryOperator;

import static io.questdb.griffin.SqlCodeGenerator.GKK_HOUR_INT;

public class MaxIntVectorAggregateFunction extends IntFunction implements VectorAggregateFunction {

    public static final LongBinaryOperator MAX = Math::max;
    private final LongAccumulator max = new LongAccumulator(
            MAX, Integer.MIN_VALUE
    );
    private final int columnIndex;
    private final DistinctFunc distinctFunc;
    private final KeyValueFunc keyValueFunc;
    private int valueOffset;

    public MaxIntVectorAggregateFunction(int keyKind, int columnIndex, int workerCount) {
        super();
        this.columnIndex = columnIndex;
        if (keyKind == GKK_HOUR_INT) {
            this.distinctFunc = Rosti::keyedHourDistinct;
            this.keyValueFunc = Rosti::keyedHourMaxInt;
        } else {
            this.distinctFunc = Rosti::keyedIntDistinct;
            this.keyValueFunc = Rosti::keyedIntMaxInt;
        }
    }

    @Override
    public void aggregate(long address, long addressSize, int columnSizeHint, int workerId) {
        if (address != 0) {
            max.accumulate(Vect.maxInt(address, addressSize / Integer.BYTES));
        }
    }

    @Override
    public boolean aggregate(long pRosti, long keyAddress, long valueAddress, long valueAddressSize, int columnSizeShr, int workerId) {
        if (valueAddress == 0) {
            return distinctFunc.run(pRosti, keyAddress, valueAddressSize / Integer.BYTES);
        } else {
            return keyValueFunc.run(pRosti, keyAddress, valueAddress, valueAddressSize / Integer.BYTES, valueOffset);
        }
    }

    @Override
    public int getColumnIndex() {
        return columnIndex;
    }

    @Override
    public int getValueOffset() {
        return valueOffset;
    }

    @Override
    public void initRosti(long pRosti) {
        Unsafe.getUnsafe().putInt(Rosti.getInitialValueSlot(pRosti, valueOffset), Integer.MIN_VALUE);
    }

    @Override
    public void merge(long pRostiA, long pRostiB) {
        Rosti.keyedIntMaxIntMerge(pRostiA, pRostiB, valueOffset);
    }

    @Override
    public void pushValueTypes(ArrayColumnTypes types) {
        this.valueOffset = types.getColumnCount();
        types.add(ColumnType.INT);
    }

    @Override
    public void wrapUp(long pRosti) {
        Rosti.keyedIntMaxIntWrapUp(pRosti, valueOffset, max.intValue());
    }

    @Override
    public void clear() {
        max.reset();
    }

    @Override
    public int getInt(Record rec) {
        return max.intValue();
    }

    @Override
    public boolean isReadThreadSafe() {
        return false;
    }
}
