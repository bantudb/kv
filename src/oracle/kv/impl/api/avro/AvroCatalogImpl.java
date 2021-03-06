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

package oracle.kv.impl.api.avro;

import java.util.Collections;
import java.util.Map;

import org.apache.avro.Schema;
import org.apache.avro.specific.SpecificRecord;

import oracle.kv.Consistency;
import oracle.kv.avro.AvroCatalog;
import oracle.kv.avro.GenericAvroBinding;
import oracle.kv.avro.JsonAvroBinding;
import oracle.kv.avro.RawAvroBinding;
import oracle.kv.avro.SchemaNotAllowedException;
import oracle.kv.avro.SpecificAvroBinding;
import oracle.kv.avro.UndefinedSchemaException;
import oracle.kv.impl.api.KVStoreImpl;
import oracle.kv.impl.test.TestHook;

/**
 * Implementation of AvroCatalog.  Most details are in the binding classes.
 */
@SuppressWarnings("deprecation")
public class AvroCatalogImpl implements AvroCatalog {

    /* All read-only access to schemas goes through the schema cache. */
    private final SchemaCache schemaCache;

    /**
     * The singleton raw binding serves a double purpose.  It is returned by
     * {@link #getRawBinding} for direct use by the client app.  And it is used
     * internally by all other bindings to package/unpackage the schema ID and
     * the Avro binary data.
     */
    private final RawAvroBinding rawBinding;

    /** Copy of the KVStore config default consistency setting. */
    private final Consistency defaultConsistency;

    /**
     * Creates a catalog.  Populates the schema cache by querying the store.
     */
    public AvroCatalogImpl(KVStoreImpl store) {
        schemaCache = new SchemaCache(new SchemaAccessor(store));
        rawBinding = new RawBinding(schemaCache);
        defaultConsistency = store.getDefaultConsistency();
    }

    /** For testing. */
    public int getSchemaCacheByIdSize() {
        return schemaCache.getByIdSize();
    }

    /** For testing. */
    public int getSchemaCacheByNameSize() {
        return schemaCache.getByNameSize();
    }

    /** For testing. */
    public int getSchemaCacheByValueSize() {
        return schemaCache.getByValueSize();
    }

    /** For testing. */
    public int getSchemaCacheByCSchemaSize() {
        return schemaCache.getByCSchemaSize();
    }

    /** For testing. */
    public void setCacheMissHook(TestHook<Void> hook) {
        schemaCache.setCacheMissHook(hook);
    }

    @Override
    public <T extends SpecificRecord> SpecificAvroBinding<T>
        getSpecificBinding(Class<T> cls) {
        return new SpecificBinding<T>(this, cls);
    }

    @Override
    public SpecificAvroBinding<SpecificRecord> getSpecificMultiBinding() {
        return new SpecificBinding<SpecificRecord>(this, null);
    }

    @Override
    public GenericAvroBinding getGenericBinding(Schema schema) {
        return new GenericBinding
            (this, Collections.singletonMap(schema.getFullName(), schema));
    }

    @Override
    public GenericAvroBinding
        getGenericMultiBinding(Map<String, Schema> schemas) {
        return new GenericBinding(this, schemas);
    }

    @Override
    public JsonAvroBinding getJsonBinding(Schema schema) {
        return new JsonBinding
            (this, Collections.singletonMap(schema.getFullName(), schema));
    }

    @Override
    public JsonAvroBinding getJsonMultiBinding(Map<String, Schema> schemas) {
        return new JsonBinding(this, schemas);
    }

    @Override
    public RawAvroBinding getRawBinding() {
        return rawBinding;
    }

    @Override
    public Map<String, Schema> getCurrentSchemas() {
        return schemaCache.getCurrentSchemas();
    }

    @Override
    public void refreshSchemaCache(Consistency consistency) {
        schemaCache.updateStoredSchemas
            ((consistency != null) ? consistency : defaultConsistency);
    }

    /**
     * Returns an internal interface used by the C Avro API.
     */
    public CBindingBridge getCBindingBridge() {
        return schemaCache.getCBindingBridge();
    }

    /**
     * Utility method for use by AvroBinding constructors.
     *
     * @throws UndefinedSchemaException if one of the given schemas has not
     * been defined.
     */
    void checkDefinedSchemas(Map<String, Schema> schemas)
        throws UndefinedSchemaException {

        for (final Schema schema : schemas.values()) {
            if (schemaCache.getSchemaInfoByValue(schema) == null) {
                throw newUndefinedSchemaException(schema);
            }
        }
    }

    /**
     * Returns a new UndefinedSchemaException for the given schema.
     */
    static UndefinedSchemaException newUndefinedSchemaException(Schema sch) {
        final String name = sch.getFullName();
        return new UndefinedSchemaException
            ("Schema name: " + name + " has not been defined using the" +
             " administration interface as a top-level schema; note that" +
             " new and modified schemas must be added or changed using the" +
             " administration interface, before being used by clients.", name);
    }

    /**
     * Utility method for use by AvroBinding.toObject implementations.
     *
     * @return the schema in allowedSchemas having the same name as the given
     * schema.  Used by toObject as the 'reader' schema.
     *
     * @throws SchemaNotAllowedException if the writer schema name does not
     * appear in allowedSchemas.
     */
    static Schema checkToObjectSchema(Schema writerSchema,
                                      Map<String, Schema> allowedSchemas)
        throws SchemaNotAllowedException {

        final String name = writerSchema.getFullName();
        final Schema schema = allowedSchemas.get(name);
        if (schema == null) {
            throw new SchemaNotAllowedException
                ("The writer schema associated with the value param is not" +
                 " one of the schemas allowed by the binding: " + name, name);
        }
        return schema;
    }

    /**
     * Utility method for use by AvroBinding.toValue implementations.
     *
     * @throws SchemaNotAllowedException if the writer schema name does not
     * appear in allowedSchemas, or is not the same instance as the schema as
     * the value in the map.
     */
    static void checkToValueSchema(Schema writerSchema,
                                   Map<String, Schema> allowedSchemas)
        throws SchemaNotAllowedException {

        final String name = writerSchema.getFullName();
        final Schema schema = allowedSchemas.get(name);
        if (schema == null || schema != writerSchema) {
            throw new SchemaNotAllowedException
                ("The writer schema associated with the object param is not" +
                 " one of the schemas allowed by the binding: " + name, name);
        }
    }
}
