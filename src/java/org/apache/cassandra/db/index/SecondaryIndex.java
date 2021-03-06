/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.db.index;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

import com.google.common.base.Objects;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.config.CFMetaData;
import org.apache.cassandra.config.ColumnDefinition;
import org.apache.cassandra.config.IndexType;
import org.apache.cassandra.cql3.Operator;
import org.apache.cassandra.db.*;
import org.apache.cassandra.db.rows.*;
import org.apache.cassandra.db.compaction.CompactionManager;
import org.apache.cassandra.db.index.composites.CompositesIndex;
import org.apache.cassandra.db.index.keys.KeysIndex;
import org.apache.cassandra.db.lifecycle.SSTableSet;
import org.apache.cassandra.db.lifecycle.View;
import org.apache.cassandra.db.marshal.AbstractType;
import org.apache.cassandra.db.marshal.BytesType;
import org.apache.cassandra.db.marshal.LocalByPartionerType;
import org.apache.cassandra.exceptions.ConfigurationException;
import org.apache.cassandra.io.sstable.format.SSTableReader;
import org.apache.cassandra.exceptions.InvalidRequestException;
import org.apache.cassandra.io.sstable.ReducingKeyIterator;
import org.apache.cassandra.service.StorageService;
import org.apache.cassandra.utils.FBUtilities;

import org.apache.cassandra.utils.concurrent.Refs;

/**
 * Abstract base class for different types of secondary indexes.
 *
 * Do not extend this directly, please pick from PerColumnSecondaryIndex or PerRowSecondaryIndex
 */
public abstract class SecondaryIndex
{
    protected static final Logger logger = LoggerFactory.getLogger(SecondaryIndex.class);

    public static final String CUSTOM_INDEX_OPTION_NAME = "class_name";

    /**
     * The name of the option used to specify that the index is on the collection keys.
     */
    public static final String INDEX_KEYS_OPTION_NAME = "index_keys";

    /**
     * The name of the option used to specify that the index is on the collection values.
     */
    public static final String INDEX_VALUES_OPTION_NAME = "index_values";

    /**
     * The name of the option used to specify that the index is on the collection (map) entries.
     */
    public static final String INDEX_ENTRIES_OPTION_NAME = "index_keys_and_values";

    public static final AbstractType<?> keyComparator = StorageService.getPartitioner().preservesOrder()
                                                      ? BytesType.instance
                                                      : new LocalByPartionerType(StorageService.getPartitioner());

    /**
     * Base CF that has many indexes
     */
    protected ColumnFamilyStore baseCfs;


    /**
     * The column definitions which this index is responsible for
     */
    protected final Set<ColumnDefinition> columnDefs = Collections.newSetFromMap(new ConcurrentHashMap<ColumnDefinition,Boolean>());

    /**
     * Perform any initialization work
     */
    public abstract void init();

    /**
     * Reload an existing index following a change to its configuration,
     * or that of the indexed column(s). Differs from init() in that we expect
     * expect new resources (such as CFS for a KEYS index) to be created by
     * init() but not here
     */
    public abstract void reload();

    /**
     * Validates the index_options passed in the ColumnDef
     * @throws ConfigurationException
     */
    public abstract void validateOptions() throws ConfigurationException;

    /**
     * @return The name of the index
     */
    abstract public String getIndexName();

    /**
     * All internal 2ndary indexes will return "_internal_" for this. Custom
     * 2ndary indexes will return their class name. This only matter for
     * SecondaryIndexManager.groupByIndexType.
     */
    String indexTypeForGrouping()
    {
        // Our internal indexes overwrite this
        return getClass().getCanonicalName();
    }

    /**
     * Return the unique name for this index and column
     * to be stored in the SystemKeyspace that tracks if each column is built
     *
     * @param columnName the name of the column
     * @return the unique name
     */
    abstract public String getNameForSystemKeyspace(ByteBuffer columnName);

    /**
     * Checks if the index for specified column is fully built
     *
     * @param columnName the column
     * @return true if the index is fully built
     */
    public boolean isIndexBuilt(ByteBuffer columnName)
    {
        return SystemKeyspace.isIndexBuilt(baseCfs.keyspace.getName(), getNameForSystemKeyspace(columnName));
    }

    public void setIndexBuilt()
    {
        for (ColumnDefinition columnDef : columnDefs)
            SystemKeyspace.setIndexBuilt(baseCfs.keyspace.getName(), getNameForSystemKeyspace(columnDef.name.bytes));
    }

    public void setIndexRemoved()
    {
        for (ColumnDefinition columnDef : columnDefs)
            SystemKeyspace.setIndexRemoved(baseCfs.keyspace.getName(), getNameForSystemKeyspace(columnDef.name.bytes));
    }

    /**
     * Called at query time
     * Creates a implementation specific searcher instance for this index type
     * @param columns the list of columns which belong to this index type
     * @return the secondary index search impl
     */
    protected abstract SecondaryIndexSearcher createSecondaryIndexSearcher(Set<ColumnDefinition> columns);

    /**
     * Forces this indexes' in memory data to disk
     */
    public abstract void forceBlockingFlush();

    /**
     * Allow access to the underlying column family store if there is one
     * @return the underlying column family store or null
     */
    public abstract ColumnFamilyStore getIndexCfs();


    /**
     * Delete all files and references to this index
     * @param columnName the indexed column to remove
     */
    public abstract void removeIndex(ByteBuffer columnName);

    /**
     * Remove the index and unregisters this index's mbean if one exists
     */
    public abstract void invalidate();

    /**
     * Truncate all the data from the current index
     *
     * @param truncatedAt The truncation timestamp, all data before that timestamp should be rejected.
     */
    public abstract void truncateBlocking(long truncatedAt);

    /**
     * Builds the index using the data in the underlying CFS
     * Blocks till it's complete
     */
    protected void buildIndexBlocking()
    {
        logger.info(String.format("Submitting index build of %s for data in %s",
                getIndexName(), StringUtils.join(baseCfs.getSSTables(SSTableSet.CANONICAL), ", ")));

        try (Refs<SSTableReader> sstables = baseCfs.selectAndReference(View.select(SSTableSet.CANONICAL)).refs)
        {
            SecondaryIndexBuilder builder = new SecondaryIndexBuilder(baseCfs,
                                                                      Collections.singleton(getIndexName()),
                                                                      new ReducingKeyIterator(sstables));
            Future<?> future = CompactionManager.instance.submitIndexBuild(builder);
            FBUtilities.waitOnFuture(future);
            forceBlockingFlush();
            setIndexBuilt();
        }
        logger.info("Index build of {} complete", getIndexName());
    }


    /**
     * Builds the index using the data in the underlying CF, non blocking
     *
     *
     * @return A future object which the caller can block on (optional)
     */
    public Future<?> buildIndexAsync()
    {
        // if we're just linking in the index to indexedColumns on an already-built index post-restart, we're done
        boolean allAreBuilt = true;
        for (ColumnDefinition cdef : columnDefs)
        {
            if (!SystemKeyspace.isIndexBuilt(baseCfs.keyspace.getName(), getNameForSystemKeyspace(cdef.name.bytes)))
            {
                allAreBuilt = false;
                break;
            }
        }

        if (allAreBuilt)
            return null;

        // build it asynchronously; addIndex gets called by CFS open and schema update, neither of which
        // we want to block for a long period.  (actual build is serialized on CompactionManager.)
        Runnable runnable = new Runnable()
        {
            public void run()
            {
                baseCfs.forceBlockingFlush();
                buildIndexBlocking();
            }
        };
        FutureTask<?> f = new FutureTask<Object>(runnable, null);

        new Thread(f, "Creating index: " + getIndexName()).start();
        return f;
    }

    public ColumnFamilyStore getBaseCfs()
    {
        return baseCfs;
    }

    private void setBaseCfs(ColumnFamilyStore baseCfs)
    {
        this.baseCfs = baseCfs;
    }

    public Set<ColumnDefinition> getColumnDefs()
    {
        return columnDefs;
    }

    void addColumnDef(ColumnDefinition columnDef)
    {
       columnDefs.add(columnDef);
    }

    void removeColumnDef(ByteBuffer name)
    {
        Iterator<ColumnDefinition> it = columnDefs.iterator();
        while (it.hasNext())
        {
            if (it.next().name.bytes.equals(name))
                it.remove();
        }
    }

    /** Returns true if the index supports lookups for the given operator, false otherwise. */
    public boolean supportsOperator(Operator operator)
    {
        return operator == Operator.EQ;
    }

    /**
     * Returns the decoratedKey for a column value. Assumes an index CFS is present.
     * @param value column value
     * @return decorated key
     */
    public DecoratedKey getIndexKeyFor(ByteBuffer value)
    {
        return getIndexCfs().partitioner.decorateKey(value);
    }

    /**
     * Returns true if the provided column is indexed by this secondary index.
     *
     * The default implementation checks whether the name is one the columnDef name,
     * but this should be overriden but subclass if needed.
     */
    public abstract boolean indexes(ColumnDefinition column);

    /**
     * This is the primary way to create a secondary index instance for a CF column.
     * It will validate the index_options before initializing.
     *
     * @param baseCfs the source of data for the Index
     * @param cdef the meta information about this column (index_type, index_options, name, etc...)
     *
     * @return The secondary index instance for this column
     * @throws ConfigurationException
     */
    public static SecondaryIndex createInstance(ColumnFamilyStore baseCfs, ColumnDefinition cdef) throws ConfigurationException
    {
        SecondaryIndex index;

        switch (cdef.getIndexType())
        {
        case KEYS:
            index = new KeysIndex();
            break;
        case COMPOSITES:
            index = CompositesIndex.create(cdef);
            break;
        case CUSTOM:
            assert cdef.getIndexOptions() != null;
            String class_name = cdef.getIndexOptions().get(CUSTOM_INDEX_OPTION_NAME);
            assert class_name != null;
            try
            {
                index = (SecondaryIndex) Class.forName(class_name).newInstance();
            }
            catch (Exception e)
            {
                throw new RuntimeException(e);
            }
            break;
            default:
                throw new RuntimeException("Unknown index type: " + cdef.getIndexName());
        }

        index.addColumnDef(cdef);
        index.validateOptions();
        index.setBaseCfs(baseCfs);

        return index;
    }

    public abstract void validate(DecoratedKey partitionKey) throws InvalidRequestException;
    public abstract void validate(Clustering clustering) throws InvalidRequestException;
    public abstract void validate(ByteBuffer cellValue, CellPath path) throws InvalidRequestException;

    public abstract long estimateResultRows();

    protected String baseKeyspace()
    {
        return baseCfs.metadata.ksName;
    }

    protected String baseTable()
    {
        return baseCfs.metadata.cfName;
    }

    /**
     * Create the index metadata for the index on a given column of a given table.
     */
    public static CFMetaData newIndexMetadata(CFMetaData baseMetadata, ColumnDefinition def)
    {
        if (def.getIndexType() == IndexType.CUSTOM)
            return null;

        CFMetaData.Builder builder = CFMetaData.Builder.create(baseMetadata.ksName, baseMetadata.indexColumnFamilyName(def))
                                                       .withId(baseMetadata.cfId)
                                                       .addPartitionKey(def.name, def.type);

        if (def.getIndexType() == IndexType.COMPOSITES)
        {
            CompositesIndex.addIndexClusteringColumns(builder, baseMetadata, def);
        }
        else
        {
            assert def.getIndexType() == IndexType.KEYS;
            KeysIndex.addIndexClusteringColumns(builder, baseMetadata, def);
        }

        return builder.build().reloadIndexMetadataProperties(baseMetadata);
    }

    @Override
    public String toString()
    {
        return Objects.toStringHelper(this).add("columnDefs", columnDefs).toString();
    }
}
