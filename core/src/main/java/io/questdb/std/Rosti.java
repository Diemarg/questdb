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

package io.questdb.std;

import io.questdb.cairo.ColumnTypes;

public final class Rosti {

    public static final int FAKE_ALLOC_SIZE = 1024;

    public static long alloc(ColumnTypes types, long capacity) {
        final int columnCount = types.getColumnCount();
        final long mem = Unsafe.malloc(4L * columnCount, MemoryTag.NATIVE_DEFAULT);
        try {
            long p = mem;
            for (int i = 0; i < columnCount; i++) {
                Unsafe.getUnsafe().putInt(p, types.getColumnType(i));
                p += Integer.BYTES;
            }
            // this is not an exact size of memory allocated for Rosti, but this is useful to
            // track that we free these maps
            long pRosti = alloc(mem, columnCount, Numbers.ceilPow2(capacity) - 1);
            if (pRosti != 0) {
                Unsafe.recordMemAlloc(FAKE_ALLOC_SIZE, MemoryTag.NATIVE_DEFAULT);
            }
            return pRosti;
        } finally {
            Unsafe.free(mem, 4L * columnCount, MemoryTag.NATIVE_DEFAULT);
        }
    }

    public static native void clear(long pRosti);

    public static void free(long pRosti) {
        free0(pRosti);
        Unsafe.recordMemAlloc(-FAKE_ALLOC_SIZE, MemoryTag.NATIVE_DEFAULT);
    }

    public static long getCtrl(long pRosti) {
        return Unsafe.getUnsafe().getLong(pRosti);
    }

    public static long getInitialValueSlot(long pRosti, int columnIndex) {
        return getInitialValuesSlot(pRosti) + Unsafe.getUnsafe().getInt(getValueOffsets(pRosti) + columnIndex * 4L);
    }

    public static long getInitialValuesSlot(long pRosti) {
        return Unsafe.getUnsafe().getLong(pRosti + 8 * Long.BYTES);
    }

    public static long getSize(long pRosti) {
        return Unsafe.getUnsafe().getLong(pRosti + 2 * Long.BYTES);
    }

    public static long getSlotShift(long pRosti) {
        return Unsafe.getUnsafe().getLong(pRosti + 5 * Long.BYTES);
    }

    public static long getSlots(long pRosti) {
        return Unsafe.getUnsafe().getLong(pRosti + Long.BYTES);
    }

    public static long getValueOffsets(long pRosti) {
        return Unsafe.getUnsafe().getLong(pRosti + 7 * Long.BYTES);
    }

    public static native boolean keyedHourCount(long pRosti, long pKeys, long count, int valueOffset);

    public static native boolean keyedHourDistinct(long pRosti, long pKeys, long count);

    public static native boolean keyedHourKSumDouble(long pRosti, long pKeys, long pDouble, long count, int valueOffset);

    public static native boolean keyedHourMaxDouble(long pRosti, long pKeys, long pDouble, long count, int valueOffset);

    public static native boolean keyedHourMaxInt(long pRosti, long pKeys, long pDouble, long count, int valueOffset);

    public static native boolean keyedHourMaxLong(long pRosti, long pKeys, long pDouble, long count, int valueOffset);

    public static native boolean keyedHourMinDouble(long pRosti, long pKeys, long pDouble, long count, int valueOffset);

    public static native boolean keyedHourMinInt(long pRosti, long pKeys, long pDouble, long count, int valueOffset);

    public static native boolean keyedHourMinLong(long pRosti, long pKeys, long pDouble, long count, int valueOffset);

    public static native boolean keyedHourNSumDouble(long pRosti, long pKeys, long pDouble, long count, int valueOffset);

    public static native boolean keyedHourSumDouble(long pRosti, long pKeys, long pDouble, long count, int valueOffset);

    public static native boolean keyedHourSumInt(long pRosti, long pKeys, long pDouble, long count, int valueOffset);

    public static native boolean keyedHourSumLong(long pRosti, long pKeys, long pLong, long count, int valueOffset);

    // sum long256
    public static native boolean keyedHourSumLong256(long pRosti, long pKeys, long pLong256, long count, int valueOffset);

    public static native boolean keyedHourSumLongLong(long pRosti, long pKeys, long pLong, long count, int valueOffset);

    public static native boolean keyedIntAvgDoubleWrapUp(long pRosti, int valueOffset, double valueAtNull, long valueAtNullCount);

    public static native boolean keyedIntAvgLongLongWrapUp(long pRosti, int valueOffset, double valueAtNull, long valueAtNullCount);

    // avg long
    public static native boolean keyedIntAvgLongWrapUp(long pRosti, int valueOffset, double valueAtNull, long valueAtNullCount);

    public static native boolean keyedIntCount(long pRosti, long pKeys, long count, int valueOffset);

    public static native boolean keyedIntCountMerge(long pRostiA, long pRostiB, int valueOffset);

    public static native boolean keyedIntDistinct(long pRosti, long pKeys, long count);

    // ksum double
    public static native boolean keyedIntKSumDouble(long pRosti, long pKeys, long pDouble, long count, int valueOffset);

    public static native boolean keyedIntKSumDoubleMerge(long pRostiA, long pRostiB, int valueOffset);

    public static native boolean keyedIntKSumDoubleWrapUp(long pRosti, int valueOffset, double valueAtNull, long valueAtNullCount);

    // max double
    public static native boolean keyedIntMaxDouble(long pRosti, long pKeys, long pDouble, long count, int valueOffset);

    public static native boolean keyedIntMaxDoubleMerge(long pRostiA, long pRostiB, int valueOffset);

    public static native boolean keyedIntMaxDoubleWrapUp(long pRosti, int valueOffset, double valueAtNull);

    // max int
    public static native boolean keyedIntMaxInt(long pRosti, long pKeys, long pDouble, long count, int valueOffset);

    public static native boolean keyedIntMaxIntMerge(long pRostiA, long pRostiB, int valueOffset);

    public static native boolean keyedIntMaxIntWrapUp(long pRosti, int valueOffset, int valueAtNull);

    // max long
    public static native boolean keyedIntMaxLong(long pRosti, long pKeys, long pDouble, long count, int valueOffset);

    public static native boolean keyedIntMaxLongMerge(long pRostiA, long pRostiB, int valueOffset);

    public static native boolean keyedIntMaxLongWrapUp(long pRosti, int valueOffset, long valueAtNull);

    // min double
    public static native boolean keyedIntMinDouble(long pRosti, long pKeys, long pDouble, long count, int valueOffset);

    public static native boolean keyedIntMinDoubleMerge(long pRostiA, long pRostiB, int valueOffset);

    public static native boolean keyedIntMinDoubleWrapUp(long pRosti, int valueOffset, double valueAtNull);

    // min int
    public static native boolean keyedIntMinInt(long pRosti, long pKeys, long pDouble, long count, int valueOffset);

    public static native boolean keyedIntMinIntMerge(long pRostiA, long pRostiB, int valueOffset);

    public static native boolean keyedIntMinIntWrapUp(long pRosti, int valueOffset, int valueAtNull);

    // min long
    public static native boolean keyedIntMinLong(long pRosti, long pKeys, long pDouble, long count, int valueOffset);

    public static native boolean keyedIntMinLongMerge(long pRostiA, long pRostiB, int valueOffset);

    public static native boolean keyedIntMinLongWrapUp(long pRosti, int valueOffset, long valueAtNull);

    // nsum double
    public static native boolean keyedIntNSumDouble(long pRosti, long pKeys, long pDouble, long count, int valueOffset);

    public static native boolean keyedIntNSumDoubleMerge(long pRostiA, long pRostiB, int valueOffset);

    public static native boolean keyedIntNSumDoubleWrapUp(long pRosti, int valueOffset, double valueAtNull, long valueAtNullCount, double valueAtNullC);

    // sum double
    public static native boolean keyedIntSumDouble(long pRosti, long pKeys, long pDouble, long count, int valueOffset);

    public static native boolean keyedIntSumDoubleMerge(long pRostiA, long pRostiB, int valueOffset);

    public static native boolean keyedIntSumDoubleWrapUp(long pRosti, int valueOffset, double valueAtNull, long valueAtNullCount);

    // sum int
    public static native boolean keyedIntSumInt(long pRosti, long pKeys, long pDouble, long count, int valueOffset);

    public static native boolean keyedIntSumIntMerge(long pRostiA, long pRostiB, int valueOffset);

    // sum long
    public static native boolean keyedIntSumLong(long pRosti, long pKeys, long pLong, long count, int valueOffset);

    public static native boolean keyedIntSumLong256(long pRosti, long pKeys, long pLong, long count, int valueOffset);

    public static native boolean keyedIntSumLong256Merge(long pRostiA, long pRostiB, int valueOffset);

    public static native boolean keyedIntSumLong256WrapUp(long pRosti, int valueOffset, long v0, long v1, long v2, long v3, long valueAtNullCount);

    public static native boolean keyedIntSumLongLong(long pRosti, long pKeys, long pLong, long count, int valueOffset);

    public static native boolean keyedIntSumLongLongMerge(long pRostiA, long pRostiB, int valueOffset);

    public static native boolean keyedIntSumLongLongWrapUp(long pRosti, int valueOffset, long valueAtNull, long valueAtNullCount);

    public static native boolean keyedIntSumLongMerge(long pRostiA, long pRostiB, int valueOffset);

    public static native boolean keyedIntSumLongWrapUp(long pRosti, int valueOffset, long valueAtNull, long valueAtNullCount);

    public static void printRosti(long pRosti) {
        final long slots = getSlots(pRosti);
        final long shift = getSlotShift(pRosti);
        long ctrl = getCtrl(pRosti);
        final long start = ctrl;
        long count = getSize(pRosti);
        while (count > 0) {
            byte b = Unsafe.getUnsafe().getByte(ctrl);
            if ((b & 0x80) == 0) {
                long p = slots + ((ctrl - start) << shift);
                System.out.println(Unsafe.getUnsafe().getInt(p) + " -> " + Unsafe.getUnsafe().getDouble(p + 12));
                count--;
            }
            ctrl++;
        }
    }

    private static native long alloc(long pKeyTypes, int keyTypeCount, long capacity);

    private static native void free0(long pRosti);
}
