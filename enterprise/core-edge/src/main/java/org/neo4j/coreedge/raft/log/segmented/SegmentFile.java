/*
 * Copyright (c) 2002-2016 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.coreedge.raft.log.segmented;

import java.io.File;
import java.io.IOException;

import org.neo4j.coreedge.helper.StatUtil.StatContext;
import org.neo4j.coreedge.raft.log.EntryRecord;
import org.neo4j.coreedge.raft.log.LogPosition;
import org.neo4j.coreedge.raft.log.RaftLogEntry;
import org.neo4j.coreedge.raft.replication.ReplicatedContent;
import org.neo4j.coreedge.raft.state.ChannelMarshal;
import org.neo4j.cursor.IOCursor;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.io.fs.StoreChannel;
import org.neo4j.kernel.impl.transaction.log.PhysicalFlushableChannel;
import org.neo4j.kernel.impl.transaction.log.ReadAheadChannel;
import org.neo4j.logging.Log;
import org.neo4j.logging.LogProvider;
import org.neo4j.storageengine.api.ReadPastEndException;

import static java.lang.String.format;
import static org.neo4j.coreedge.raft.log.EntryRecord.read;

/**
 * Keeps track of a segment of the RAFT log, i.e. a consecutive set of entries.
 * Concurrent reading is thread-safe.
 */
class SegmentFile implements AutoCloseable
{
    private static final SegmentHeader.Marshal headerMarshal = new SegmentHeader.Marshal();

    private final Log log;
    private final FileSystemAbstraction fileSystem;
    private final File file;
    private final ReaderPool readerPool;
    private final ChannelMarshal<ReplicatedContent> contentMarshal;

    private final PositionCache positionCache;
    private final ReferenceCounter refCount;

    private final SegmentHeader header;
    private final long version;

    private PhysicalFlushableChannel bufferedWriter;
    private final StatContext scanStats;

    SegmentFile( FileSystemAbstraction fileSystem, File file, ReaderPool readerPool, long version,
            ChannelMarshal<ReplicatedContent> contentMarshal, LogProvider logProvider, SegmentHeader header )
    {
        this( fileSystem, file, readerPool, version, contentMarshal, logProvider, header, null );
    }

    SegmentFile( FileSystemAbstraction fileSystem, File file, ReaderPool readerPool, long version,
            ChannelMarshal<ReplicatedContent> contentMarshal, LogProvider logProvider, SegmentHeader header, StatContext scanStats )
    {
        this.fileSystem = fileSystem;
        this.file = file;
        this.readerPool = readerPool;
        this.contentMarshal = contentMarshal;
        this.header = header;
        this.version = version;
        this.scanStats = scanStats;

        this.positionCache = new PositionCache();
        this.refCount = new ReferenceCounter();

        this.log = logProvider.getLog( getClass() );
    }

    static SegmentFile create( FileSystemAbstraction fileSystem, File file, ReaderPool readerPool, long version,
            ChannelMarshal<ReplicatedContent> contentMarshal, LogProvider logProvider, SegmentHeader header ) throws IOException
    {
        return create( fileSystem, file, readerPool, version, contentMarshal, logProvider, header, null );
    }

    static SegmentFile create( FileSystemAbstraction fileSystem, File file, ReaderPool readerPool, long version,
            ChannelMarshal<ReplicatedContent> contentMarshal, LogProvider logProvider, SegmentHeader header, StatContext scanStats )
            throws IOException
    {
        if ( fileSystem.fileExists( file ) )
        {
            throw new IllegalStateException( "File was not expected to exist" );
        }

        SegmentFile segment = new SegmentFile( fileSystem, file, readerPool, version, contentMarshal, logProvider, header, scanStats );
        headerMarshal.marshal( header, segment.getOrCreateWriter() );
        segment.flush();

        return segment;
    }

    /**
     * Channels must be closed when no longer used, so that they are released back to the pool of readers.
     */
    IOCursor<EntryRecord> getReader( long logIndex ) throws IOException, DisposedException
    {
        assert logIndex > header.prevIndex();

        if ( !refCount.increase() )
        {
            throw new DisposedException();
        }

        /* This is the relative index within the file, starting from zero. */
        long offsetIndex = logIndex - (header.prevIndex() + 1);

        LogPosition position = positionCache.lookup( offsetIndex );
        Reader reader = readerPool.acquire( version, position.byteOffset );

        try
        {
            ReadAheadChannel<StoreChannel> bufferedReader = new ReadAheadChannel<>( reader.channel() );
            long currentIndex = position.logIndex;
            if ( scanStats != null )
            {
                scanStats.collect( offsetIndex - currentIndex );
            }

            try
            {
                /* The cache lookup might have given us an earlier position, scan forward to the exact position. */
                while ( currentIndex < offsetIndex )
                {
                    read( bufferedReader, contentMarshal );
                    currentIndex++;
                }
            }
            catch ( ReadPastEndException e )
            {
                bufferedReader.close();
                return IOCursor.getEmpty();
            }

            return new EntryRecordCursor( bufferedReader, contentMarshal, currentIndex )
            {
                boolean closed = false; /* This is just a defensive measure, for catching user errors from messing up the refCount. */

                @Override
                public void close()
                {
                    if ( closed )
                    {
                        throw new IllegalStateException( "Already closed" );
                    }

                    /* The reader owns the channel and it is returned to the pool with it open. */
                    closed = true;
                    positionCache.put( this.position() );
                    readerPool.release( reader );
                    refCount.decrease();
                }
            };
        }
        catch ( IOException e )
        {
            reader.close();
            refCount.decrease();
            throw e;
        }
    }

    private synchronized PhysicalFlushableChannel getOrCreateWriter() throws IOException
    {
        if ( bufferedWriter == null )
        {
            if ( !refCount.increase() )
            {
                throw new IOException( "Writer has been closed" );
            }

            StoreChannel channel = fileSystem.open( file, "rw" );
            channel.position( channel.size() );
            bufferedWriter = new PhysicalFlushableChannel( channel );
        }
        return bufferedWriter;
    }

    synchronized long position() throws IOException
    {
        return getOrCreateWriter().position();
    }

    /**
     * Idempotently closes the writer.
     */
    synchronized void closeWriter()
    {
        if ( bufferedWriter != null )
        {
            try
            {
                flush();
                bufferedWriter.close();
            }
            catch ( IOException e )
            {
                log.error( "Failed to close writer for: " + file, e );
            }

            bufferedWriter = null;
            refCount.decrease();
        }
    }

    public synchronized void write( long logIndex, RaftLogEntry entry ) throws IOException
    {
        EntryRecord.write( getOrCreateWriter(), contentMarshal, logIndex, entry.term(), entry.content() );
    }

    synchronized void flush() throws IOException
    {
        bufferedWriter.prepareForFlush().flush();
    }

    public boolean delete()
    {
        return fileSystem.deleteFile( file );
    }

    public SegmentHeader header()
    {
        return header;
    }

    public long size()
    {
        return fileSystem.getFileSize( file );
    }

    String getFilename()
    {
        return file.getName();
    }

    /**
     * Called by the pruner when it wants to prune this segment. If there are no open
     * readers or writers then the segment will be closed.
     *
     * @return True if the segment can be pruned at this time, false otherwise.
     */
    boolean tryClose()
    {
        if ( refCount.tryDispose() )
        {
            close();
            return true;
        }
        return false;
    }

    @Override
    public void close()
    {
        closeWriter();

        if ( !refCount.tryDispose() )
        {
            throw new IllegalStateException( format( "Segment still referenced. Value: %d", refCount.get() ) );
        }
    }

    @Override
    public String toString()
    {
        return "SegmentFile{" +
               "file=" + file.getName() +
               ", header=" + header +
               '}';
    }
}
