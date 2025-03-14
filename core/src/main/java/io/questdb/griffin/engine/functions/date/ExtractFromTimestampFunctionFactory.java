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

package io.questdb.griffin.engine.functions.date;

import io.questdb.cairo.CairoConfiguration;
import io.questdb.cairo.sql.Function;
import io.questdb.cairo.sql.Record;
import io.questdb.griffin.FunctionFactory;
import io.questdb.griffin.SqlException;
import io.questdb.griffin.SqlExecutionContext;
import io.questdb.griffin.SqlKeywords;
import io.questdb.griffin.engine.functions.IntFunction;
import io.questdb.griffin.engine.functions.LongFunction;
import io.questdb.griffin.engine.functions.UnaryFunction;
import io.questdb.std.IntList;
import io.questdb.std.Numbers;
import io.questdb.std.ObjList;
import io.questdb.std.datetime.microtime.Timestamps;

public class ExtractFromTimestampFunctionFactory implements FunctionFactory {

    @Override
    public String getSignature() {
        return "extract(sN)";
    }

    @Override
    public Function newInstance(
            int position,
            ObjList<Function> args,
            IntList argPositions,
            CairoConfiguration configuration,
            SqlExecutionContext sqlExecutionContext
    ) throws SqlException {
        final CharSequence part = args.getQuick(0).getStr(null);
        final Function arg = args.getQuick(1);

        if (SqlKeywords.isCenturyKeyword(part)) {
            return new CenturyFunction(arg);
        }

        if (SqlKeywords.isDayKeyword(part)) {
            return new DayOfMonthFunctionFactory.DayOfMonthFunction(arg);
        }

        if (SqlKeywords.isDecadeKeyword(part)) {
            return new DecadeFunction(arg);
        }

        if (SqlKeywords.isDowKeyword(part)) {
            return new DowFunction(arg);
        }

        if (SqlKeywords.isDoyKeyword(part)) {
            return new DoyFunction(arg);
        }

        if (SqlKeywords.isEpochKeyword(part)) {
            return new EpochFunction(arg);
        }

        if (SqlKeywords.isHourKeyword(part)) {
            return new HourOfDayFunctionFactory.HourOfDayFunction(arg);
        }

        if (SqlKeywords.isIsoDowKeyword(part)) {
            return new IsoDowFunction(arg);
        }

        if (SqlKeywords.isIsoYearKeyword(part)) {
            return new IsoYearFunction(arg);
        }

        if (SqlKeywords.isMicrosecondsKeyword(part)) {
            return new MicrosecondsFunction(arg);
        }

        if (SqlKeywords.isMillenniumKeyword(part)) {
            return new MillenniumFunction(arg);
        }

        if (SqlKeywords.isMillisecondsKeyword(part)) {
            return new MillisecondsFunction(arg);
        }

        if (SqlKeywords.isMinuteKeyword(part)) {
            return new MinuteOfHourFunctionFactory.MinuteFunction(arg);
        }

        if (SqlKeywords.isMonthKeyword(part)) {
            return new MonthOfYearFunctionFactory.MonthOfYearFunction(arg);
        }

        if (SqlKeywords.isQuarterKeyword(part)) {
            return new QuarterFunction(arg);
        }

        if (SqlKeywords.isSecondKeyword(part)) {
            return new SecondOfMinuteFunctionFactory.SecondOfMinuteFunc(arg);
        }

        if (SqlKeywords.isWeekKeyword(part)) {
            return new WeekFunction(arg);
        }

        if (SqlKeywords.isYearKeyword(part)) {
            return new YearFunctionFactory.YearFunction(arg);
        }

        throw SqlException.position(argPositions.getQuick(0)).put("unsupported part '").put(part).put('\'');
    }

    static final class CenturyFunction extends IntFunction implements UnaryFunction {

        private final Function arg;

        public CenturyFunction(Function arg) {
            this.arg = arg;
        }

        @Override
        public Function getArg() {
            return arg;
        }

        @Override
        public int getInt(Record rec) {
            final long value = arg.getTimestamp(rec);
            if (value != Numbers.LONG_NaN) {
                return Timestamps.getCentury(value);
            }
            return Numbers.INT_NaN;
        }
    }

    static final class DecadeFunction extends IntFunction implements UnaryFunction {

        private final Function arg;

        public DecadeFunction(Function arg) {
            this.arg = arg;
        }

        @Override
        public Function getArg() {
            return arg;
        }

        @Override
        public int getInt(Record rec) {
            final long value = arg.getTimestamp(rec);
            if (value != Numbers.LONG_NaN) {
                return Timestamps.getDecade(value);
            }
            return Numbers.INT_NaN;
        }
    }

    static final class DowFunction extends IntFunction implements UnaryFunction {

        private final Function arg;

        public DowFunction(Function arg) {
            this.arg = arg;
        }

        @Override
        public Function getArg() {
            return arg;
        }

        @Override
        public int getInt(Record rec) {
            final long value = arg.getTimestamp(rec);
            if (value != Numbers.LONG_NaN) {
                return Timestamps.getDow(value);
            }
            return Numbers.INT_NaN;
        }
    }

    static final class DoyFunction extends IntFunction implements UnaryFunction {

        private final Function arg;

        public DoyFunction(Function arg) {
            this.arg = arg;
        }

        @Override
        public Function getArg() {
            return arg;
        }

        @Override
        public int getInt(Record rec) {
            final long value = arg.getTimestamp(rec);
            if (value != Numbers.LONG_NaN) {
                return Timestamps.getDoy(value);
            }
            return Numbers.INT_NaN;
        }
    }

    static final class WeekFunction extends IntFunction implements UnaryFunction {

        private final Function arg;

        public WeekFunction(Function arg) {
            this.arg = arg;
        }

        @Override
        public Function getArg() {
            return arg;
        }

        @Override
        public int getInt(Record rec) {
            final long value = arg.getTimestamp(rec);
            if (value != Numbers.LONG_NaN) {
                return Timestamps.getWeek(value);
            }
            return Numbers.INT_NaN;
        }
    }

    static final class IsoYearFunction extends IntFunction implements UnaryFunction {

        private final Function arg;

        public IsoYearFunction(Function arg) {
            this.arg = arg;
        }

        @Override
        public Function getArg() {
            return arg;
        }

        @Override
        public int getInt(Record rec) {
            final long value = arg.getTimestamp(rec);
            if (value != Numbers.LONG_NaN) {
                return Timestamps.getIsoYear(value);
            }
            return Numbers.INT_NaN;
        }
    }

    static final class EpochFunction extends LongFunction implements UnaryFunction {

        private final Function arg;

        public EpochFunction(Function arg) {
            this.arg = arg;
        }

        @Override
        public Function getArg() {
            return arg;
        }

        @Override
        public long getLong(Record rec) {
            final long value = arg.getTimestamp(rec);
            if (value != Numbers.LONG_NaN) {
                return value / Timestamps.SECOND_MICROS;
            }
            return Numbers.LONG_NaN;
        }
    }

    static final class MicrosecondsFunction extends LongFunction implements UnaryFunction {

        private final Function arg;

        public MicrosecondsFunction(Function arg) {
            this.arg = arg;
        }

        @Override
        public Function getArg() {
            return arg;
        }

        @Override
        public long getLong(Record rec) {
            final long value = arg.getTimestamp(rec);
            if (value != Numbers.LONG_NaN) {
                return Timestamps.getMicrosOfMinute(value);
            }
            return Numbers.LONG_NaN;
        }
    }

    static final class MillisecondsFunction extends LongFunction implements UnaryFunction {

        private final Function arg;

        public MillisecondsFunction(Function arg) {
            this.arg = arg;
        }

        @Override
        public Function getArg() {
            return arg;
        }

        @Override
        public long getLong(Record rec) {
            final long value = arg.getTimestamp(rec);
            if (value != Numbers.LONG_NaN) {
                return Timestamps.getMillisOfMinute(value);
            }
            return Numbers.LONG_NaN;
        }
    }

    static final class IsoDowFunction extends IntFunction implements UnaryFunction {

        private final Function arg;

        public IsoDowFunction(Function arg) {
            this.arg = arg;
        }

        @Override
        public Function getArg() {
            return arg;
        }

        @Override
        public int getInt(Record rec) {
            final long value = arg.getTimestamp(rec);
            if (value != Numbers.LONG_NaN) {
                return Timestamps.getDayOfWeek(value);
            }
            return Numbers.INT_NaN;
        }
    }

    static final class MillenniumFunction extends IntFunction implements UnaryFunction {

        private final Function arg;

        public MillenniumFunction(Function arg) {
            this.arg = arg;
        }

        @Override
        public Function getArg() {
            return arg;
        }

        @Override
        public int getInt(Record rec) {
            final long value = arg.getTimestamp(rec);
            if (value != Numbers.LONG_NaN) {
                return Timestamps.getMillennium(value);
            }
            return Numbers.INT_NaN;
        }
    }

    static final class QuarterFunction extends IntFunction implements UnaryFunction {

        private final Function arg;

        public QuarterFunction(Function arg) {
            this.arg = arg;
        }

        @Override
        public Function getArg() {
            return arg;
        }

        @Override
        public int getInt(Record rec) {
            final long value = arg.getTimestamp(rec);
            if (value != Numbers.LONG_NaN) {
                return Timestamps.getQuarter(value);
            }
            return Numbers.INT_NaN;
        }
    }
}
