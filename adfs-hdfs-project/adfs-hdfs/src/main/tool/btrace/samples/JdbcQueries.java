/*
 * Copyright 2008-2010 Sun Microsystems, Inc.  All Rights Reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Sun designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Sun in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Sun Microsystems, Inc., 4150 Network Circle, Santa Clara,
 * CA 95054 USA or visit www.sun.com if you need additional information or
 * have any questions.
 */

package com.sun.btrace.samples;

import static com.sun.btrace.BTraceUtils.*;
import static com.sun.btrace.BTraceUtils.Collections.*;
import static com.sun.btrace.BTraceUtils.Aggregations.*;
import static com.sun.btrace.BTraceUtils.Threads.*;

import java.sql.Statement;
import java.util.Map;

import com.sun.btrace.AnyType;
import com.sun.btrace.BTraceUtils;
import com.sun.btrace.aggregation.Aggregation;
import com.sun.btrace.aggregation.AggregationFunction;
import com.sun.btrace.aggregation.AggregationKey;
import com.sun.btrace.annotations.BTrace;
import com.sun.btrace.annotations.Duration;
import com.sun.btrace.annotations.Kind;
import com.sun.btrace.annotations.Location;
import com.sun.btrace.annotations.OnEvent;
import com.sun.btrace.annotations.OnMethod;
import com.sun.btrace.annotations.TLS;
import com.sun.btrace.annotations.Return;
import com.sun.btrace.annotations.Self;

/**
 * BTrace script to print timings for all executed JDBC statements on an event. Demonstrates
 * different types of aggregation function.
 * <p>
 *
 * @author Christian Glencross 
 */
@BTrace
public class JdbcQueries {

    private static Map<Statement, String> preparedStatementDescriptions = newWeakMap();

    private static Aggregation histogram = newAggregation(AggregationFunction.QUANTIZE);

    private static Aggregation average = newAggregation(AggregationFunction.AVERAGE);

    private static Aggregation max = newAggregation(AggregationFunction.MAXIMUM);

    private static Aggregation min = newAggregation(AggregationFunction.MINIMUM);

    private static Aggregation sum = newAggregation(AggregationFunction.SUM);

    private static Aggregation count = newAggregation(AggregationFunction.COUNT);

    private static Aggregation globalCount = newAggregation(AggregationFunction.COUNT);

    @TLS
    private static String preparingStatement;

    @TLS
    private static String executingStatement;

    /**
     * If "--stack" is passed on command line, print the Java stack trace of the JDBC statement.
     * 
     * Otherwise we print the SQL.
     */
    private static boolean useStackTrace = Sys.$(2) != null && Strings.strcmp("--stack", Sys.$(2)) == 0;

    // The first couple of probes capture whenever prepared statement and callable statements are
    // instantiated, in order to let us track what SQL they contain.

    /**
     * Capture SQL used to create prepared statements.
     * 
     * @param args
     *            the list of method parameters. args[1] is the SQL.
     */
    @OnMethod(clazz = "+java.sql.Connection", method = "/prepare.*/")
    public static void onPrepare(AnyType[] args) {
        preparingStatement = useStackTrace ? jstackStr() : str(args[0]);
    }

    /**
     * Cache SQL associated with a prepared statement.
     * 
     * @param arg
     *            the return value from the prepareXxx() method.
     */
    @OnMethod(clazz = "+java.sql.Connection", method = "/prepare.*/", location = @Location(Kind.RETURN))
    public static void onPrepareReturn(@Return Statement preparedStatement) {
        if (preparingStatement != null) {
            print("P"); // Debug Prepared
            put(preparedStatementDescriptions, preparedStatement, preparingStatement);
            preparingStatement = null;
        }
    }

    // The next couple of probes intercept the execution of a statement. If it execute with no-args,
    // then it must be a prepared statement or callable statement. Get the SQL from the probes up above.
    // Otherwise the SQL is in the first argument.

    @OnMethod(clazz = "+java.sql.Statement", method = "/execute.*/")
    public static void onExecute(@Self Statement currentStatement, AnyType[] args) {
        if (args.length == 0) {
            // No SQL argument; lookup the SQL from the prepared statement
            executingStatement = get(preparedStatementDescriptions, currentStatement);
        } else {
            // Direct SQL in the first argument
            executingStatement = useStackTrace ? jstackStr() : str(args[0]);
        }
    }

    @OnMethod(clazz = "+java.sql.Statement", method = "/execute.*/", location = @Location(Kind.RETURN))
    public static void onExecuteReturn(@Duration long durationL) {

        if (executingStatement == null) {
            return;
        }

        print("X"); // Debug Executed

        AggregationKey key = newAggregationKey(executingStatement);
        int duration = (int) durationL / 1000;

        addToAggregation(histogram, key, duration);
        addToAggregation(average, key, duration);
        addToAggregation(max, key, duration);
        addToAggregation(min, key, duration);
        addToAggregation(sum, key, duration);
        addToAggregation(count, key, duration);
        addToAggregation(globalCount, duration);

        executingStatement = null;
    }

    @OnEvent
    public static void onEvent() {

        // Top 10 queries only
        truncateAggregation(histogram, 10);

        println("---------------------------------------------");
        printAggregation("Count", count);
        printAggregation("Min", min);
        printAggregation("Max", max);
        printAggregation("Average", average);
        printAggregation("Sum", sum);
        printAggregation("Histogram", histogram);
        printAggregation("Global Count", globalCount);
        println("---------------------------------------------");
    }

}
