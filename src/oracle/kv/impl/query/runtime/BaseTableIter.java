/*-
 * Copyright (C) 2011, 2017 Oracle and/or its affiliates. All rights reserved.
 *
 * This file was distributed by Oracle as part of a version of Oracle NoSQL
 * Database made available at:
 *
 * http://www.oracle.com/technetwork/database/database-technologies/nosqldb/downloads/index.html
 *
 * Please see the LICENSE file included in the top-level directory of the
 * appropriate version of Oracle NoSQL Database for a copy of the license and
 * additional information.
 */

package oracle.kv.impl.query.runtime;

import static oracle.kv.impl.query.QueryException.Location;
import static oracle.kv.impl.util.SerialVersion.QUERY_VERSION_2;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import oracle.kv.impl.api.table.BooleanValueImpl;
import oracle.kv.impl.api.table.FieldValueImpl;
import oracle.kv.impl.api.table.RecordValueImpl;
import oracle.kv.impl.api.table.IndexKeyImpl;
import oracle.kv.impl.api.table.PrimaryKeyImpl;
import oracle.kv.impl.api.table.RecordDefImpl;
import oracle.kv.impl.api.table.TableImpl;
import oracle.kv.impl.api.table.IndexImpl;

import oracle.kv.impl.query.compiler.Expr;
import oracle.kv.impl.query.compiler.FuncCompOp;
import oracle.kv.impl.query.compiler.FunctionLib.FuncCode;
import oracle.kv.impl.query.compiler.QueryFormatter;
import oracle.kv.impl.util.SerializationUtil;
import oracle.kv.table.FieldRange;
import oracle.kv.Direction;

/**
 * BaseTableIter performs table scans via the primary or a secondary index of
 * the table.
 *
 * The query plan generated by the compiler includes instances of BaseTableIter,
 * but the actual work is done by either a ClientTableIter instance, if the
 * query is executed at the client, or a ServerTableIter instance, if the query
 * is executed at the server. These "worker" instances are created during
 * BaseTableIter.open(). After the creation of the worker iter, the open/next/
 * reset/close methods of BaseTableIter are propagated to that worker.
 * ClientTableIter uses the public TableAPI while ServerTableIter uses server-
 * side classes, which are not available in the client jar.
 *
 * TableIterFactory is an interface whose single createTableIterator() method
 * does the job of creating the appropriate worker instance. Specifically, when
 * the RCB is created, the appropriate implementing class of TableIterFactory
 * (ClientTableIter.ClientIterFactory or ServerTableIter.ServerIterFactory) is
 * also created and stored in the RCB. Then, during BaseTableIter.open() the
 * TableIterFactory instance stored in the RCB is used to create the appropriate
 * worker instance.
 */
public class BaseTableIter extends PlanIter {

    public static class BaseTableIterState extends PlanIterState {

        protected final RuntimeControlBlock theRCB;

        protected final Direction theDirection;

        protected final PrimaryKeyImpl thePrimaryKey;

        protected final IndexKeyImpl theSecondaryKey;

        protected final FieldRange theRange;

        protected boolean theAlwaysFalse;

        protected BaseTableIterState(
            RuntimeControlBlock rcb,
            BaseTableIter iter) {

            theRCB = rcb;
            theDirection = iter.theDirection;

            TableImpl table = iter.getTable();
            IndexImpl index = (iter.theIndexName != null ?
                               (IndexImpl)table.getIndex(iter.theIndexName) :
                               null);

            if (index != null) {
                theSecondaryKey =
                    index.createIndexKeyFromFlattenedRecord(iter.theSecKey);
                thePrimaryKey = null;
            } else {
                assert(iter.thePrimKey != null);
                thePrimaryKey = table.createPrimaryKey(iter.thePrimKey);
                theSecondaryKey = null;
            }

            if (iter.thePushedExternals == null ||
                iter.thePushedExternals.length == 0) {
                theRange = iter.theRange;
                return;
            }

            int size = iter.thePushedExternals.length;

            if (iter.theRange != null) {
                FieldRange range = iter.theRange.clone();

                PlanIter lowIter = iter.thePushedExternals[size-2];
                PlanIter highIter = iter.thePushedExternals[size-1];

                size -= 2;

                if (lowIter != null) {
                    lowIter.open(rcb);
                    lowIter.next(rcb);
                    FieldValueImpl val = rcb.getRegVal(lowIter.getResultReg());
                    lowIter.close(rcb);

                    FieldValueImpl newVal = castValueToIndexKey(
                        table, index, size, val, FuncCode.OP_GE);

                    if (newVal != val) {

                        if (newVal == BooleanValueImpl.falseValue) {
                            theAlwaysFalse = true;
                            theRange = null;
                            return;
                        }

                        if (newVal == BooleanValueImpl.trueValue) {
                            val = null;
                        } else {
                            val = newVal;
                        }
                    }

                    range.setStart(val, range.getStartInclusive(), false);

                    if (!range.check()) {
                        theAlwaysFalse = true;
                        theRange = null;
                        return;
                    }
                }

                if (highIter != null) {
                    highIter.open(rcb);
                    highIter.next(rcb);
                    FieldValueImpl val = rcb.getRegVal(highIter.getResultReg());
                    highIter.close(rcb);

                    FieldValueImpl newVal = castValueToIndexKey(
                        table, index, size, val, FuncCode.OP_LE);

                    if (newVal != val) {

                        if (newVal == BooleanValueImpl.falseValue) {
                            theAlwaysFalse = true;
                            theRange = null;
                            return;
                        }

                        if (newVal == BooleanValueImpl.trueValue) {
                            val = null;
                        } else {
                            val = newVal;
                        }
                    }

                    range.setEnd(val, range.getEndInclusive(), false);

                    if (!range.check()) {
                        theAlwaysFalse = true;
                        theRange = null;
                        return;
                    }
                }

                if (range.getStart() != null || range.getEnd() != null) {
                    theRange = range;
                } else {
                    theRange = null;
                }

            } else {
                theRange = null;
            }

            for (int i = 0; i < size; ++i) {

                PlanIter extIter = iter.thePushedExternals[i];

                if (extIter == null) {
                    continue;
                }

                extIter.open(rcb);
                extIter.next(rcb);
                FieldValueImpl val = rcb.getRegVal(extIter.getResultReg());
                extIter.close(rcb);

                FieldValueImpl newVal = 
                    castValueToIndexKey(table, index, i, val, FuncCode.OP_EQ);

                if (newVal != val) {

                    if (newVal == BooleanValueImpl.falseValue) {
                        theAlwaysFalse = true;
                        return;
                    }

                    assert(newVal != BooleanValueImpl.trueValue);
                    val = newVal;
                }

                if (index != null) {
                    theSecondaryKey.put(i, val);
                } else {
                    assert(thePrimaryKey != null);
                    thePrimaryKey.put(i, val);
                }
            }
        }

        public boolean isAlwaysFalse() {
            return theAlwaysFalse;
        }
    }

    protected final String theTableName;

    protected final String theIndexName;

    /*
     * The definition of the data returned by this iterator.
     */
    protected final RecordDefImpl theTypeDef;

    /*
     * The direction of the table scan
     */
    protected final Direction theDirection;

    /*
     * The primary key to use in accessing the base table. This will be null if
     * a secondary index must be used to access the table. It will be empty
     * if the primary index must be used to access the table, but there were
     * no equality predicates on primary-key columns to be pushed into the
     * primary index scan.
     *
     * Note: thePrimKey is stored here as a RecordValue, instead of PrimaryKey,
     * for 2 reasons:
     * - When we serialize the BaseTableIter, we don't want to serialize a
     *   PrimaryKey, because that contains a TableImpl as well.
     * - thePrimKey may include "placeholder" values that correspod to external
     *   variables. As a result, a new primary key instance must be created and
     *   stored in the plan state during the open() method. This new instance
     *   will actually be an instance of PrimaryKey.
     */
    protected final RecordValueImpl thePrimKey;

    /*
     * The secondary key to use in accessing the base table. This will be null
     * if the primary index must be used to access the table. It will be empty
     * if a secondary index must be used to access the table, but there were
     * no equality predicates on secondary index columns to be pushed into the
     * index scan.
     *
     * Note: theSecKey is stored here as a RecordValue, instead of IndexKey,
     * for the same reasons described above for thePrimKey.
     */
    protected final RecordValueImpl theSecKey;

    /*
     * An optional key range
     */
    protected final FieldRange theRange;

    /*
     * An optional filtering iterator that works on primary and index keys.
     */
    protected PlanIter theFilterIter;

    /*
     * Whether the index used to access the table is a covering index or not.
     */
    protected final boolean theUsesCoveringIndex;

    /*
     * True if the table will be accessed via a multikey index in a way that
     * may generate duplicate table rows. Such duplicates should be eliminated.
     *
     * added in QUERY_VERSION_2
     */
    protected final boolean theEliminateIndexDups;

    /*
     * See OptRulePushIndexPreds.IndexAnalyzer.thePushedExternals
     */
    protected final PlanIter[] thePushedExternals;

    protected final int[] theTupleRegs;

    /*
     * The "worker" iter associated with this BaseTableIter.
     */
    protected PlanIter theTableIter;

    /**
     * Constructor used by compiler during code generation.
     *
     * primKey and secKey will be both null if no predicates were pushed to
     * either the primary or any secondary index. In this case, an empty
     * primary key is created by this constructor, so that the table will be
     * accessed via a full scan of the primary index. If a range exists,
     * either primKey or secKey will be non-null to indicate whether the
     * range is over the primary or a secondary index. But the given key will
     * be empty, if no equality predicates were pushed to the index.
     */
    public BaseTableIter(
        Expr e,
        int resultReg,
        int[] tupleRegs,
        TableImpl table,
        Direction dir,
        PrimaryKeyImpl primKey,
        IndexKeyImpl secKey,
        FieldRange range,
        boolean usesCoveringIndex,
        boolean eliminateIndexDups,
        PlanIter[] pushedExternals) {

        super(e, resultReg);

        assert(primKey == null || secKey == null);
        assert(range == null || primKey != null || secKey != null);

        theTableName = table.getFullName();

        theTypeDef = (RecordDefImpl) e.getType().getDef();

        theDirection = dir;

        if (primKey == null && secKey == null) {
            thePrimKey = table.createPrimaryKey();
            theSecKey = null;
            theIndexName = null;

        } else if (primKey != null) {
            thePrimKey = primKey.getDefinition().createRecord();
            thePrimKey.copyFrom(primKey);
            theSecKey = null;
            theIndexName = null;

        } else {
            thePrimKey = null;
            theSecKey = secKey.clone();
            theIndexName = secKey.getIndex().getName();
        }

        theRange = range;
        theUsesCoveringIndex = usesCoveringIndex;
        theEliminateIndexDups = eliminateIndexDups;
        thePushedExternals = pushedExternals;

        theTupleRegs = tupleRegs;
    }

    /**
     * Constructor called during creation of ClientTableIter or ServerTableIter.
     */
    protected BaseTableIter(
        Location loc,
        int statePos,
        int resultReg,
        int[] tupleRegs,
        String tableName,
        String indexName,
        RecordDefImpl typeDef,
        Direction dir,
        RecordValueImpl primKey,
        RecordValueImpl secKey,
        FieldRange range,
        PlanIter filterIter,
        boolean usesCoveringIndex,
        boolean eliminateIndexDups,
        PlanIter[] pushedExternals) {

        super(statePos, resultReg, loc);

        theTableName = tableName;
        theIndexName = indexName;

        theTypeDef = typeDef;

        theDirection = dir;

        thePrimKey = primKey;
        theSecKey = secKey;
        theRange = range;
        theFilterIter = filterIter;
        theUsesCoveringIndex = usesCoveringIndex;
        theEliminateIndexDups = eliminateIndexDups;
        thePushedExternals = pushedExternals;

        theTupleRegs = tupleRegs;
    }

    /**
     * FastExternalizable constructor.
     */
    BaseTableIter(DataInput in, short serialVersion) throws IOException {

        super(in, serialVersion);

        theTableName = SerializationUtil.readString(in);
        theIndexName = SerializationUtil.readString(in);
        theTypeDef = (RecordDefImpl)deserializeFieldDef(in, serialVersion);
        short ordinal = in.readShort();
        theDirection = Direction.valueOf(ordinal);

        if (theIndexName == null) {
            thePrimKey = deserializeKey(in, serialVersion);
            theSecKey = null;
        } else {
            thePrimKey = null;
            theSecKey = deserializeKey(in, serialVersion);
        }

        theRange = deserializeFieldRange(in, serialVersion);
        theUsesCoveringIndex = in.readBoolean();

        if (serialVersion < QUERY_VERSION_2) {
            theEliminateIndexDups = false;
        } else {
            theEliminateIndexDups = in.readBoolean();
        }

        thePushedExternals = deserializeIters(in, serialVersion);
        theFilterIter = deserializeIter(in, serialVersion);
        theTupleRegs = deserializeIntArray(in);
    }

    /**
     * FastExternalizable writer.  Must call superclass method first to
     * write common elements.
     */
    @Override
    public void writeFastExternal(DataOutput out, short serialVersion)
            throws IOException {

        super.writeFastExternal(out, serialVersion);

        SerializationUtil.writeString(out, theTableName);
        SerializationUtil.writeString(out, theIndexName);
        serializeFieldDef(theTypeDef, out, serialVersion);
        out.writeShort(theDirection.ordinal());

        if (theIndexName == null) {
            serializeKey(thePrimKey, out, serialVersion);
        } else {
            serializeKey(theSecKey, out, serialVersion);
        }

        serializeFieldRange(theRange, out, serialVersion);
        out.writeBoolean(theUsesCoveringIndex);

        if (serialVersion >= QUERY_VERSION_2) {
            out.writeBoolean(theEliminateIndexDups);
        }

        serializeIters(thePushedExternals, out, serialVersion);
        serializeIter(theFilterIter, out, serialVersion);
        serializeIntArray(theTupleRegs, out);
    }

    @Override
    public PlanIterKind getKind() {
        return PlanIterKind.BASE_TABLE;
    }

    @Override
    public int[] getTupleRegs() {
        return theTupleRegs;
    }

    protected TableImpl getTable() {
        throw new ClassCastException(
            "getTable method should only be called on a ClientTableIter " +
            "or a ServerTableIter");
    }

    public void setFilterIter(PlanIter iter) {
        theFilterIter = iter;
    }

    @Override
    public void open(RuntimeControlBlock rcb) {

        /*
         * If the actual iterator has not yet been created, do so now.
         */
        if (theTableIter == null) {
            TableIterFactory tableIterFactory = rcb.getTableIterFactory();
            theTableIter = tableIterFactory.createTableIter(
                rcb,
                getLocation(),
                theStatePos,
                theResultReg,
                theTupleRegs,
                theTableName,
                theIndexName,
                theTypeDef,
                theDirection,
                thePrimKey,
                theSecKey,
                theRange,
                theFilterIter,
                theUsesCoveringIndex,
                theEliminateIndexDups,
                thePushedExternals);
        }
        theTableIter.open(rcb);
    }

    @Override
    public boolean next(RuntimeControlBlock rcb) {
        boolean retVal = false;

        if (theTableIter != null) {
            retVal = theTableIter.next(rcb);
        }

        return retVal;
    }

    @Override
    public void reset(RuntimeControlBlock rcb) {
        if (theTableIter != null) {
            theTableIter.reset(rcb);
        }
    }

    @Override
    public void close(RuntimeControlBlock rcb) {
        if (theTableIter != null) {
            theTableIter.close(rcb);
        }
    }

    @Override
    protected void display(StringBuilder sb, QueryFormatter formatter) {

        formatter.indent(sb);
        sb.append(getKind());

        displayRegs(sb);

        displayContent(sb, formatter);
    }

    @Override
    protected void displayContent(StringBuilder sb, QueryFormatter formatter) {

        if ((thePrimKey != null && thePrimKey.size() > 0) ||
            theSecKey != null ||
            theRange != null ||
            theFilterIter != null) {

            sb.append("\n");
            formatter.indent(sb);
            sb.append("[\n");

            formatter.incIndent();
            formatter.indent(sb);
            sb.append(theTableName);

            if (thePrimKey != null) {
                if (theUsesCoveringIndex) {
                    sb.append(" via covering primary index");
                } else {
                    sb.append(" via primary index");
                }
                sb.append("\n");
                formatter.indent(sb);
                sb.append("KEY: ");
                sb.append(thePrimKey);
            }

            if (theSecKey != null) {
                if (theUsesCoveringIndex) {
                    sb.append(" via covering index ");
                } else {
                    sb.append(" via index ");
                }
                sb.append(theIndexName);
                if (theEliminateIndexDups) {
                    sb.append(" with duplicate elimination");
                }
                sb.append("\n");
                formatter.indent(sb);
                sb.append("SEC KEY: ");
                sb.append(theSecKey);
            }

            if (theRange != null) {
                sb.append("\n");
                formatter.indent(sb);
                sb.append("RANGE: ");
                sb.append(theRange);
            }

            if (theFilterIter != null) {
                sb.append("\n\n");
                formatter.indent(sb);
                sb.append("Filtering Predicate:\n");
                theFilterIter.display(sb, formatter);
            }

            if (thePushedExternals != null) {
                sb.append("\n\n");
                formatter.indent(sb);
                sb.append("EXTERNAL KEY EXPRS: ");
                sb.append(thePushedExternals.length);

                for (PlanIter iter : thePushedExternals) {

                    sb.append("\n");
                    if (iter != null) {
                        iter.display(sb, formatter);
                    } else {
                        formatter.indent(sb);
                        sb.append("null");
                    }
                }
            }

            formatter.decIndent();
            sb.append("\n");
            formatter.indent(sb);
            sb.append("]");

        } else {
            sb.append("\n");
            formatter.indent(sb);
            sb.append("[");
            sb.append(theTableName);
            if (theUsesCoveringIndex) {
                sb.append(" via covering primary index");
            } else {
                sb.append(" via primary index");
            }
            sb.append("]");
        }
    }

    static FieldValueImpl castValueToIndexKey(
        TableImpl table,
        IndexImpl index,
        int keyPos,
        FieldValueImpl val,
        FuncCode opcode) {

        if (index != null) {
            return FuncCompOp.castConstInCompOp(index.getFieldDef(keyPos),
                                                val,
                                                opcode,
                                                false/*strict*/);
        }

        return FuncCompOp.castConstInCompOp(table.getPrimKeyColumnDef(keyPos),
                                            val, 
                                            opcode,
                                            false/*strict*/);
    }
}