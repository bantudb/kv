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

package oracle.kv.impl.api.ops;

import java.io.DataInput;
import java.io.IOException;

import oracle.kv.Depth;
import oracle.kv.KeyRange;

/**
 * A multi-get-keys operation.
 */
public class MultiGetKeys extends MultiKeyOperation {

    /**
     * Construct a multi-get operation.
     */
    public MultiGetKeys(byte[] parentKey, KeyRange subRange, Depth depth) {
        super(OpCode.MULTI_GET_KEYS, parentKey, subRange, depth);
    }

    /**
     * FastExternalizable constructor.  Must call superclass constructor first
     * to read common elements.
     */
    MultiGetKeys(DataInput in, short serialVersion)
        throws IOException {

        super(OpCode.MULTI_GET_KEYS, in, serialVersion);
    }
}
