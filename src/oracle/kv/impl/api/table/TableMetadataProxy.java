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

package oracle.kv.impl.api.table;

import oracle.kv.impl.util.SerializationUtil;

import com.sleepycat.persist.model.Persistent;
import com.sleepycat.persist.model.PersistentProxy;

/**
 *
 */
@Persistent(proxyFor=TableMetadata.class)
public class TableMetadataProxy implements PersistentProxy<TableMetadata> {

    private byte[] bytes;

    @Override
    public void initializeProxy(TableMetadata tm) {
        bytes = SerializationUtil.getBytes(tm);
    }

    @Override
    public TableMetadata convertProxy() {
        return SerializationUtil.getObject(bytes, TableMetadata.class);
    }
}
