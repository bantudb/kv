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

package oracle.kv.impl.query.compiler;

import oracle.kv.impl.query.QueryException;
import oracle.kv.impl.query.QueryStateException;
import oracle.kv.impl.query.runtime.ArithUnaryOpIter;
import oracle.kv.impl.query.runtime.PlanIter;
import oracle.kv.impl.query.types.ExprType;
import oracle.kv.impl.query.types.TypeManager;

/**
 * Arithmetic unary operations + - function implementation.
 *
 * Note: only - is implemented, + is skipped.
 */
public class FuncArithUnaryOp extends Function {

    FuncArithUnaryOp(FunctionLib.FuncCode code, String name) {
        super(code,
            name,
            TypeManager.ANY_JATOMIC_QSTN() /* params */,
            TypeManager.ANY_JATOMIC_QSTN() /* RetType */);
    }

    @Override
    ExprType getRetType(ExprFuncCall caller) {

        ExprType.TypeCode typeCode;
        ExprType.Quantifier quantifier = ExprType.Quantifier.ONE;

        assert caller.getNumArgs() == 1;

        ExprType argType = caller.getArg(0).getType();

        switch (argType.getQuantifier()) {
        case ONE:
        case PLUS:
            break;
        case QSTN:
        case STAR:
            quantifier = ExprType.Quantifier.QSTN;
            break;
        default:
            throw new QueryStateException(
                "Unknown Quantifier: " + argType.getQuantifier());
        }

        switch (argType.getCode()) {
        case INT:
            typeCode = ExprType.TypeCode.INT;
            break;
        case LONG:
            typeCode = ExprType.TypeCode.LONG;
            break;
        case FLOAT:
            typeCode = ExprType.TypeCode.FLOAT;
            break;
        case DOUBLE:
            typeCode = ExprType.TypeCode.DOUBLE;
            break;
        case ANY_JSON_ATOMIC:
        case JSON:
        case ANY_ATOMIC:
        case ANY:
            typeCode = ExprType.TypeCode.ANY_JSON_ATOMIC;
            break;
        default:
            throw new QueryException(
                "Operand in unary arithmetic operation has illegal " +
                "type.\nOperand type :\n" + argType.getDef().getDDLString(),
                caller.getLocation());
        }

        return TypeManager.getBuiltinType(typeCode, quantifier);
    }

    @Override
    boolean mayReturnNULL(ExprFuncCall caller) {
        return  caller.getArg(0).mayReturnNULL();
    }

    @Override
    PlanIter codegen(
        CodeGenerator codegen,
        Expr funcCall,
        PlanIter[] argIters) {

        int resultReg = codegen.allocateResultReg(funcCall);

        assert argIters != null && argIters.length == 1;
        assert (theCode == FunctionLib.FuncCode.OP_ARITH_UNARY );

        return new ArithUnaryOpIter(funcCall, resultReg, theCode, argIters[0]);
    }
}
