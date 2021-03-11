/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.plugin.hive.orc;

import com.google.common.collect.ImmutableList;
import io.trino.memory.context.AggregatedMemoryContext;
import io.trino.orc.OrcCorruptionException;
import io.trino.orc.OrcDataSource;
import io.trino.orc.OrcDataSourceId;
import io.trino.orc.OrcRecordReader;
import io.trino.orc.metadata.ColumnMetadata;
import io.trino.orc.metadata.OrcType;
import io.trino.plugin.hive.FileFormatDataSourceStats;
import io.trino.plugin.hive.HiveColumnHandle;
import io.trino.plugin.hive.HiveUpdateProcessor;
import io.trino.plugin.hive.orc.OrcDeletedRows.MaskDeletedRowsFunction;
import io.trino.spi.Page;
import io.trino.spi.TrinoException;
import io.trino.spi.block.Block;
import io.trino.spi.block.LazyBlock;
import io.trino.spi.block.LazyBlockLoader;
import io.trino.spi.block.LongArrayBlock;
import io.trino.spi.block.RunLengthEncodedBlock;
import io.trino.spi.connector.ConnectorPageSource;
import io.trino.spi.type.Type;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static io.trino.plugin.hive.HiveErrorCode.HIVE_BAD_DATA;
import static io.trino.plugin.hive.HiveErrorCode.HIVE_CURSOR_ERROR;
import static io.trino.plugin.hive.HiveUpdatablePageSource.BUCKET_CHANNEL;
import static io.trino.plugin.hive.HiveUpdatablePageSource.ORIGINAL_TRANSACTION_CHANNEL;
import static io.trino.plugin.hive.HiveUpdatablePageSource.ROW_ID_CHANNEL;
import static io.trino.plugin.hive.orc.OrcFileWriter.computeBucketValue;
import static io.trino.spi.block.RowBlock.fromFieldBlocks;
import static io.trino.spi.predicate.Utils.nativeValueToBlock;
import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.IntegerType.INTEGER;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

public class OrcPageSource
        implements ConnectorPageSource
{
    private static final Block ORIGINAL_FILE_TRANSACTION_ID_BLOCK = nativeValueToBlock(BIGINT, 0L);

    private final OrcRecordReader recordReader;
    private final List<ColumnAdaptation> columnAdaptations;
    private final OrcDataSource orcDataSource;
    private final Optional<OrcDeletedRows> deletedRows;

    private boolean closed;

    private final AggregatedMemoryContext systemMemoryContext;

    private final FileFormatDataSourceStats stats;

    // Row ID relative to all the original files of the same bucket ID before this file in lexicographic order
    private Optional<Long> originalFileRowId = Optional.empty();

    public OrcPageSource(
            OrcRecordReader recordReader,
            List<ColumnAdaptation> columnAdaptations,
            OrcDataSource orcDataSource,
            Optional<OrcDeletedRows> deletedRows,
            Optional<Long> originalFileRowId,
            AggregatedMemoryContext systemMemoryContext,
            FileFormatDataSourceStats stats)
    {
        this.recordReader = requireNonNull(recordReader, "recordReader is null");
        this.columnAdaptations = ImmutableList.copyOf(requireNonNull(columnAdaptations, "columnAdaptations is null"));
        this.orcDataSource = requireNonNull(orcDataSource, "orcDataSource is null");
        this.deletedRows = requireNonNull(deletedRows, "deletedRows is null");
        this.stats = requireNonNull(stats, "stats is null");
        this.systemMemoryContext = requireNonNull(systemMemoryContext, "systemMemoryContext is null");
        this.originalFileRowId = requireNonNull(originalFileRowId, "originalFileRowId is null");
    }

    @Override
    public long getCompletedBytes()
    {
        return orcDataSource.getReadBytes();
    }

    @Override
    public long getReadTimeNanos()
    {
        return orcDataSource.getReadTimeNanos();
    }

    @Override
    public boolean isFinished()
    {
        return closed;
    }

    public ColumnMetadata<OrcType> getColumnTypes()
    {
        return recordReader.getColumnTypes();
    }

    @Override
    public Page getNextPage()
    {
        Page page;
        try {
            page = recordReader.nextPage();
        }
        catch (IOException | RuntimeException e) {
            closeWithSuppression(e);
            throw handleException(orcDataSource.getId(), e);
        }

        if (page == null) {
            close();
            return null;
        }

        OptionalLong startRowId = originalFileRowId.isPresent() ?
                OptionalLong.of(originalFileRowId.get() + recordReader.getFilePosition()) : OptionalLong.empty();

        MaskDeletedRowsFunction maskDeletedRowsFunction = deletedRows
                .map(deletedRows -> deletedRows.getMaskDeletedRowsFunction(page, startRowId))
                .orElseGet(() -> MaskDeletedRowsFunction.noMaskForPage(page));
        Block[] blocks = new Block[columnAdaptations.size()];
        for (int i = 0; i < columnAdaptations.size(); i++) {
            blocks[i] = columnAdaptations.get(i).block(page, maskDeletedRowsFunction, recordReader.getFilePosition());
        }
        return new Page(maskDeletedRowsFunction.getPositionCount(), blocks);
    }

    static TrinoException handleException(OrcDataSourceId dataSourceId, Exception exception)
    {
        if (exception instanceof TrinoException) {
            return (TrinoException) exception;
        }
        if (exception instanceof OrcCorruptionException) {
            return new TrinoException(HIVE_BAD_DATA, exception);
        }
        return new TrinoException(HIVE_CURSOR_ERROR, format("Failed to read ORC file: %s", dataSourceId), exception);
    }

    @Override
    public void close()
    {
        // some hive input formats are broken and bad things can happen if you close them multiple times
        if (closed) {
            return;
        }
        closed = true;

        try {
            stats.addMaxCombinedBytesPerRow(recordReader.getMaxCombinedBytesPerRow());
            recordReader.close();
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public String toString()
    {
        return toStringHelper(this)
                .add("orcDataSource", orcDataSource.getId())
                .add("columns", columnAdaptations)
                .toString();
    }

    @Override
    public long getSystemMemoryUsage()
    {
        return systemMemoryContext.getBytes();
    }

    private void closeWithSuppression(Throwable throwable)
    {
        requireNonNull(throwable, "throwable is null");
        try {
            close();
        }
        catch (RuntimeException e) {
            // Self-suppression not permitted
            if (throwable != e) {
                throwable.addSuppressed(e);
            }
        }
    }

    public interface ColumnAdaptation
    {
        Block block(Page sourcePage, MaskDeletedRowsFunction maskDeletedRowsFunction, long filePosition);

        static ColumnAdaptation nullColumn(Type type)
        {
            return new NullColumn(type);
        }

        static ColumnAdaptation sourceColumn(int index)
        {
            return new SourceColumn(index);
        }

        static ColumnAdaptation rowIdColumn()
        {
            return new RowIdAdaptation();
        }

        static ColumnAdaptation originalFileRowIdColumn(long startingRowId, int bucketId)
        {
            return new OriginalFileRowIdAdaptation(startingRowId, bucketId);
        }

        static ColumnAdaptation updatedRowColumns(HiveUpdateProcessor updateProcessor, List<HiveColumnHandle> dependencyColumns)
        {
            return new UpdatedRowAdaptation(updateProcessor, dependencyColumns);
        }
    }

    private static class NullColumn
            implements ColumnAdaptation
    {
        private final Type type;
        private final Block nullBlock;

        public NullColumn(Type type)
        {
            this.type = requireNonNull(type, "type is null");
            this.nullBlock = type.createBlockBuilder(null, 1, 0)
                    .appendNull()
                    .build();
        }

        @Override
        public Block block(Page sourcePage, MaskDeletedRowsFunction maskDeletedRowsFunction, long filePosition)
        {
            return new RunLengthEncodedBlock(nullBlock, maskDeletedRowsFunction.getPositionCount());
        }

        @Override
        public String toString()
        {
            return toStringHelper(this)
                    .add("type", type)
                    .toString();
        }
    }

    private static class SourceColumn
            implements ColumnAdaptation
    {
        private final int index;

        public SourceColumn(int index)
        {
            checkArgument(index >= 0, "index is negative");
            this.index = index;
        }

        @Override
        public Block block(Page sourcePage, MaskDeletedRowsFunction maskDeletedRowsFunction, long filePosition)
        {
            Block block = sourcePage.getBlock(index);
            return new LazyBlock(maskDeletedRowsFunction.getPositionCount(), new MaskingBlockLoader(maskDeletedRowsFunction, block));
        }

        @Override
        public String toString()
        {
            return toStringHelper(this)
                    .add("index", index)
                    .toString();
        }

        private static final class MaskingBlockLoader
                implements LazyBlockLoader
        {
            private MaskDeletedRowsFunction maskDeletedRowsFunction;
            private Block sourceBlock;

            public MaskingBlockLoader(MaskDeletedRowsFunction maskDeletedRowsFunction, Block sourceBlock)
            {
                this.maskDeletedRowsFunction = requireNonNull(maskDeletedRowsFunction, "maskDeletedRowsFunction is null");
                this.sourceBlock = requireNonNull(sourceBlock, "sourceBlock is null");
            }

            @Override
            public Block load()
            {
                checkState(maskDeletedRowsFunction != null, "Already loaded");

                Block resultBlock = maskDeletedRowsFunction.apply(sourceBlock.getLoadedBlock());

                maskDeletedRowsFunction = null;
                sourceBlock = null;

                return resultBlock;
            }
        }
    }

    private static class RowIdAdaptation
            implements ColumnAdaptation
    {
        @Override
        public Block block(Page sourcePage, MaskDeletedRowsFunction maskDeletedRowsFunction, long filePosition)
        {
            Block rowBlock = maskDeletedRowsFunction.apply(fromFieldBlocks(
                    sourcePage.getPositionCount(),
                    Optional.empty(),
                    new Block[] {
                            sourcePage.getBlock(ORIGINAL_TRANSACTION_CHANNEL),
                            sourcePage.getBlock(ROW_ID_CHANNEL),
                            sourcePage.getBlock(BUCKET_CHANNEL),
                    }));
            return rowBlock;
        }
    }

    /**
     * This ColumnAdaptation creates a RowBlock column containing the three
     * ACID columms - - originalTransaction, rowId, bucket - - and
     * all the columns not changed by the UPDATE statement.
     */
    private static final class UpdatedRowAdaptation
            implements ColumnAdaptation
    {
        private final HiveUpdateProcessor updateProcessor;
        private final List<Integer> nonUpdatedSourceChannels;

        public UpdatedRowAdaptation(HiveUpdateProcessor updateProcessor, List<HiveColumnHandle> dependencyColumns)
        {
            this.updateProcessor = updateProcessor;
            this.nonUpdatedSourceChannels = updateProcessor.makeNonUpdatedSourceChannels(dependencyColumns);
        }

        @Override
        public Block block(Page sourcePage, MaskDeletedRowsFunction maskDeletedRowsFunction, long filePosition)
        {
            return updateProcessor.createUpdateRowBlock(sourcePage, nonUpdatedSourceChannels, maskDeletedRowsFunction);
        }
    }

    private static class OriginalFileRowIdAdaptation
            implements ColumnAdaptation
    {
        private final long startingRowId;
        private final Block bucketBlock;

        public OriginalFileRowIdAdaptation(long startingRowId, int bucketId)
        {
            this.startingRowId = startingRowId;
            this.bucketBlock = nativeValueToBlock(INTEGER, Long.valueOf(computeBucketValue(bucketId, 0)));
        }

        @Override
        public Block block(Page sourcePage, MaskDeletedRowsFunction maskDeletedRowsFunction, long filePosition)
        {
            int positionCount = sourcePage.getPositionCount();
            Block rowBlock = maskDeletedRowsFunction.apply(fromFieldBlocks(
                    positionCount,
                    Optional.empty(),
                    new Block[] {
                            new RunLengthEncodedBlock(ORIGINAL_FILE_TRANSACTION_ID_BLOCK, positionCount),
                            createRowIdBlock(filePosition, positionCount),
                            new RunLengthEncodedBlock(bucketBlock, positionCount)
                    }));
            return rowBlock;
        }

        private Block createRowIdBlock(long filePosition, int positionCount)
        {
            long[] translatedRowIds = new long[positionCount];
            for (int index = 0; index < positionCount; index++) {
                translatedRowIds[index] = filePosition + startingRowId + index;
            }
            return new LongArrayBlock(positionCount, Optional.empty(), translatedRowIds);
        }
    }
}
