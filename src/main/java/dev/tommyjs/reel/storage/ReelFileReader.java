package dev.tommyjs.reel.storage;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.tommyjs.reel.storage.model.*;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

class ReelFileReader implements ReelReader {

    private static final int MAX_CACHED_BLOCKS = 32;

    private final RandomAccessFile file;
    private final ReentrantLock fileLock = new ReentrantLock();

    private final long totalFrames;
    private final long opDataOffset;
    private final int opBlockCount;
    private final long entityDirOffset;
    private final long trackDirOffset;
    private final long trackIndexOffset;

    private volatile EntityDirectoryImpl cachedEntityDirectory;
    private volatile Map<Long, Integer> entityIdToIndex;
    private volatile long[] blockFileOffsets;
    private volatile List<TrackEntry> cachedTrackDirectory;
    private volatile Map<Integer, String> cachedTrackIdMap;

    private final Cache<Integer, byte[]> blockCache = Caffeine.newBuilder()
            .maximumSize(MAX_CACHED_BLOCKS)
            .build();

    ReelFileReader(RandomAccessFile file) throws IOException {
        this.file = file;

        try {
            if (file.length() < ReelFormat.FOOTER_SIZE) {
                throw new IOException("File too small to contain footer");
            }

            validateHeaderMagic();

            file.seek(file.length() - ReelFormat.FOOTER_SIZE);
            this.totalFrames = file.readLong();
            this.opDataOffset = file.readLong();
            this.opBlockCount = file.readInt();
            this.entityDirOffset = file.readLong();
            this.trackDirOffset = file.readLong();
            this.trackIndexOffset = file.readLong();

            byte[] footerMagic = new byte[ReelFormat.FOOTER_MAGIC_LENGTH];
            file.readFully(footerMagic);
            if (!Arrays.equals(footerMagic, ReelFormat.footerMagic())) {
                throw new IOException("Invalid footer magic — file may be incomplete");
            }
        } catch (IOException e) {
            file.close();
            throw e;
        }
    }

    private void validateHeaderMagic() throws IOException {
        file.seek(0);
        byte[] magic = new byte[ReelFormat.HEADER_MAGIC_LENGTH];
        file.readFully(magic);
        if (!Arrays.equals(magic, ReelFormat.headerMagic())) {
            throw new IOException("Invalid header magic");
        }
    }

    @Override
    public long getTotalFrames() {
        return totalFrames;
    }

    @Override
    public @NotNull EntityDirectory readEntityDirectory() {
        try {
            return getEntityDirectoryData();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public @NotNull TrackDirectory readTrackDirectory() {
        try {
            List<TrackEntry> tracks = getTrackEntries();
            return () -> tracks;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public @NotNull EntityTrackIndex readTrackIndex(long entityId) {
        try {
            EntityDirectoryImpl dirData = getEntityDirectoryData();
            int entityIndex = getEntityIndex(entityId);
            Map<Integer, String> trackIdMap = getTrackIdMap();

            fileLock.lock();
            try {
                file.seek(trackIndexOffset + (long) entityIndex * 8);
                long blockOffset = file.readLong();

                file.seek(trackIndexOffset + blockOffset);
                byte[] indexData = readTrackIndexBlock(dirData.entries, entityIndex, blockOffset);

                ByteBuf buf = Unpooled.wrappedBuffer(indexData);
                try {
                    return parseTrackIndex(buf, trackIdMap);
                } finally {
                    buf.release();
                }
            } finally {
                fileLock.unlock();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public byte @NotNull [] readTrackData(long refId) {
        try {
            int blockIndex = ReelFormat.unpackBlockIndex(refId);
            int offset = ReelFormat.unpackOffset(refId);
            int size = ReelFormat.unpackSize(refId);

            if (size == 0) {
                return new byte[0];
            }

            if (blockIndex < 0 || blockIndex >= opBlockCount) {
                throw new IOException("Block index " + blockIndex + " out of range [0, " + opBlockCount + ")");
            }

            byte[] decompressed = getDecompressedBlock(blockIndex);

            if (offset < 0 || offset + size > decompressed.length) {
                throw new IOException("RefId references data beyond block boundary: offset=" + offset
                        + " size=" + size + " blockLength=" + decompressed.length);
            }

            byte[] result = new byte[size];
            System.arraycopy(decompressed, offset, result, 0, size);
            return result;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() throws IOException {
        fileLock.lock();
        try {
            file.close();
        } finally {
            fileLock.unlock();
        }
        blockCache.invalidateAll();
    }

    private int getEntityIndex(long entityId) throws IOException {
        Map<Long, Integer> index = entityIdToIndex;
        if (index != null) {
            Integer result = index.get(entityId);
            if (result == null) {
                throw new IllegalArgumentException("Entity " + entityId + " not found");
            }
            return result;
        }

        EntityDirectoryImpl data = getEntityDirectoryData();
        Map<Long, Integer> map = new HashMap<>(data.entries.size());
        for (int i = 0; i < data.entries.size(); i++) {
            map.put(data.entries.get(i).entityId, i);
        }
        entityIdToIndex = Collections.unmodifiableMap(map);

        Integer result = map.get(entityId);
        if (result == null) {
            throw new IllegalArgumentException("Entity " + entityId + " not found");
        }
        return result;
    }

    private byte[] readTrackIndexBlock(List<EntityEntryImpl> entities, int entityIndex,
                                        long blockOffset) throws IOException {
        int compression = file.readUnsignedByte();
        if (compression == ReelFormat.COMPRESSION_LZ4) {
            int uncompressedSize = file.readInt();
            int compressedSize = file.readInt();
            byte[] compressed = new byte[compressedSize];
            file.readFully(compressed);
            return ReelIOUtil.decompressLz4(compressed, uncompressedSize);
        }

        long dataStart = trackIndexOffset + blockOffset + 1;
        long dataEnd;
        if (entityIndex + 1 < entities.size()) {
            file.seek(trackIndexOffset + (long) (entityIndex + 1) * 8);
            dataEnd = trackIndexOffset + file.readLong();
        } else {
            dataEnd = file.length() - ReelFormat.FOOTER_SIZE;
        }

        file.seek(dataStart);
        byte[] indexData = new byte[(int) (dataEnd - dataStart)];
        file.readFully(indexData);
        return indexData;
    }

    private EntityTrackIndex parseTrackIndex(ByteBuf buf, Map<Integer, String> trackIdMap) {
        int trackCount = (int) ReelIOUtil.readVarInt(buf);
        List<EntityTrackEntry> tracks = new ArrayList<>(trackCount);

        for (int t = 0; t < trackCount; t++) {
            int numericTrackId = (int) ReelIOUtil.readVarInt(buf);
            String trackId = trackIdMap.get(numericTrackId);

            int spanCount = (int) ReelIOUtil.readVarInt(buf);
            List<TrackSpan> spans = new ArrayList<>(spanCount);
            for (int s = 0; s < spanCount; s++) {
                long referenceFrame = ReelIOUtil.readVarInt(buf);
                long targetFrame = ReelIOUtil.readVarInt(buf);
                int cost = (int) ReelIOUtil.readVarInt(buf);
                long refId = buf.readLong();
                spans.add(new TrackSpan(refId, referenceFrame, targetFrame, cost));
            }

            int snapshotCount = (int) ReelIOUtil.readVarInt(buf);
            List<TrackSnapshot> snapshots = new ArrayList<>(snapshotCount);
            for (int s = 0; s < snapshotCount; s++) {
                long frame = ReelIOUtil.readVarInt(buf);
                int cost = (int) ReelIOUtil.readVarInt(buf);
                long refId = buf.readLong();
                snapshots.add(new TrackSnapshot(refId, frame, cost));
            }

            tracks.add(new EntityTrackEntryImpl(trackId,
                    Collections.unmodifiableList(spans),
                    Collections.unmodifiableList(snapshots)));
        }

        List<EntityTrackEntry> result = Collections.unmodifiableList(tracks);
        return () -> result;
    }

    private EntityDirectoryImpl getEntityDirectoryData() throws IOException {
        EntityDirectoryImpl result = cachedEntityDirectory;
        if (result != null) return result;

        fileLock.lock();
        try {
            result = cachedEntityDirectory;
            if (result != null) return result;

            file.seek(entityDirOffset);
            int compression = file.readUnsignedByte();
            byte[] directoryData;
            if (compression == ReelFormat.COMPRESSION_ZSTD) {
                int uncompressedSize = file.readInt();
                int compressedSize = file.readInt();
                byte[] compressed = new byte[compressedSize];
                file.readFully(compressed);
                directoryData = ReelIOUtil.decompressZstd(compressed, uncompressedSize);
            } else {
                int available = (int) (trackDirOffset - entityDirOffset - 1);
                directoryData = new byte[available];
                file.readFully(directoryData);
            }

            ByteBuf buf = Unpooled.wrappedBuffer(directoryData);
            try {
                int entityTypeCount = (int) ReelIOUtil.readVarInt(buf);
                Map<Integer, String> entityTypeMap = new HashMap<>(entityTypeCount);
                List<String> entityTypes = new ArrayList<>(entityTypeCount);
                for (int i = 0; i < entityTypeCount; i++) {
                    int numericId = (int) ReelIOUtil.readVarInt(buf);
                    int strLen = (int) ReelIOUtil.readVarInt(buf);
                    byte[] strBytes = new byte[strLen];
                    buf.readBytes(strBytes);
                    String stringId = new String(strBytes, StandardCharsets.UTF_8);
                    entityTypeMap.put(numericId, stringId);
                    entityTypes.add(stringId);
                }

                int entityCount = (int) ReelIOUtil.readVarInt(buf);
                List<EntityEntryImpl> entries = new ArrayList<>(entityCount);
                for (int i = 0; i < entityCount; i++) {
                    long entityId = ReelIOUtil.readVarInt(buf);
                    int numericTypeId = (int) ReelIOUtil.readVarInt(buf);
                    String entityType = entityTypeMap.get(numericTypeId);
                    int metadataLen = (int) ReelIOUtil.readVarInt(buf);
                    byte[] metadata = new byte[metadataLen];
                    buf.readBytes(metadata);
                    int spawnCount = (int) ReelIOUtil.readVarInt(buf);
                    List<EntitySpawnEntry> spawns = new ArrayList<>(spawnCount);
                    for (int s = 0; s < spawnCount; s++) {
                        long spawnFrame = ReelIOUtil.readVarInt(buf);
                        long despawnFrame = ReelIOUtil.readSVarInt(buf);
                        spawns.add(new EntitySpawnEntryImpl(spawnFrame, despawnFrame));
                    }
                    entries.add(new EntityEntryImpl(entityId, entityType, metadata, Collections.unmodifiableList(spawns)));
                }

                //noinspection unchecked
                List<EntityEntry> entityEntries = (List<EntityEntry>) (List<?>) Collections.unmodifiableList(entries);

                result = new EntityDirectoryImpl(
                        Collections.unmodifiableList(entityTypes),
                        entityEntries,
                        Collections.unmodifiableList(entries));
                cachedEntityDirectory = result;
                return result;
            } finally {
                buf.release();
            }
        } finally {
            fileLock.unlock();
        }
    }

    private Map<Integer, String> getTrackIdMap() throws IOException {
        Map<Integer, String> result = cachedTrackIdMap;
        if (result != null) return result;

        getTrackEntries();
        return cachedTrackIdMap;
    }

    private List<TrackEntry> getTrackEntries() throws IOException {
        List<TrackEntry> result = cachedTrackDirectory;
        if (result != null) return result;

        fileLock.lock();
        try {
            result = cachedTrackDirectory;
            if (result != null) return result;

            file.seek(trackDirOffset);
            byte[] raw = new byte[(int) (trackIndexOffset - trackDirOffset)];
            file.readFully(raw);

            ByteBuf buf = Unpooled.wrappedBuffer(raw);
            try {
                int trackCount = (int) ReelIOUtil.readVarInt(buf);
                List<TrackEntry> tracks = new ArrayList<>(trackCount);
                Map<Integer, String> trackIdMap = new HashMap<>(trackCount);
                for (int i = 0; i < trackCount; i++) {
                    int numericId = (int) ReelIOUtil.readVarInt(buf);
                    int strLen = (int) ReelIOUtil.readVarInt(buf);
                    byte[] strBytes = new byte[strLen];
                    buf.readBytes(strBytes);
                    String stringId = new String(strBytes, StandardCharsets.UTF_8);
                    int codecVersion = (int) ReelIOUtil.readVarInt(buf);
                    tracks.add(new TrackEntryImpl(stringId, codecVersion));
                    trackIdMap.put(numericId, stringId);
                }
                result = Collections.unmodifiableList(tracks);
                cachedTrackDirectory = result;
                cachedTrackIdMap = Collections.unmodifiableMap(trackIdMap);
                return result;
            } finally {
                buf.release();
            }
        } finally {
            fileLock.unlock();
        }
    }

    private byte[] getDecompressedBlock(int blockIndex) throws IOException {
        try {
            return blockCache.get(blockIndex, this::loadBlock);
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException ioe) {
                throw ioe;
            }
            throw e;
        }
    }

    private byte[] loadBlock(int blockIndex) {
        try {
            ensureBlockOffsets();

            byte[] compressed;
            int uncompressedSize;
            fileLock.lock();
            try {
                file.seek(blockFileOffsets[blockIndex]);
                uncompressedSize = file.readInt();
                int compressedSize = file.readInt();
                compressed = new byte[compressedSize];
                file.readFully(compressed);
            } finally {
                fileLock.unlock();
            }

            return ReelIOUtil.decompressLz4(compressed, uncompressedSize);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void ensureBlockOffsets() throws IOException {
        if (blockFileOffsets != null) return;

        fileLock.lock();
        try {
            if (blockFileOffsets != null) return;

            long[] offsets = new long[opBlockCount];
            long pos = opDataOffset;
            for (int i = 0; i < opBlockCount; i++) {
                offsets[i] = pos;
                file.seek(pos + 4);
                int compressedSize = file.readInt();
                pos += 4 + 4 + compressedSize;
            }
            blockFileOffsets = offsets;
        } finally {
            fileLock.unlock();
        }
    }

    private record TrackEntryImpl(String trackId, int codecVersion) implements TrackEntry {

        @Override
        public @NotNull String getTrackId() {
            return trackId;
        }

        @Override
        public int getCodecVersion() {
            return codecVersion;
        }

    }

    private record EntityEntryImpl(long entityId, String entityType, byte[] metadata,
                                   List<EntitySpawnEntry> spawnEntries) implements EntityEntry {

        EntityEntryImpl(long entityId, String entityType, byte[] metadata,
                        List<EntitySpawnEntry> spawnEntries) {
            this.entityId = entityId;
            this.entityType = entityType;
            this.metadata = metadata.clone();
            this.spawnEntries = spawnEntries;
        }

        @Override
        public long getEntityId() {
            return entityId;
        }

        @Override
        public @NotNull String getEntityType() {
            return entityType;
        }

        @Override
        public byte @NotNull [] getMetadata() {
            return metadata.clone();
        }

        @Override
        public @NotNull List<EntitySpawnEntry> getSpawns() {
            return spawnEntries;
        }

    }

    private record EntitySpawnEntryImpl(long spawnFrame,
                                        long despawnFrame) implements EntitySpawnEntry {

        @Override
        public long getSpawnFrame() {
            return spawnFrame;
        }

        @Override
        public long getDespawnFrame() {
            return despawnFrame;
        }

    }

    private record EntityTrackEntryImpl(String trackId, List<TrackSpan> spans,
                                        List<TrackSnapshot> snapshots) implements EntityTrackEntry {

        @Override
        public @NotNull String getTrackId() {
            return trackId;
        }

        @Override
        public @NotNull List<TrackSpan> getSpans() {
            return spans;
        }

        @Override
        public @NotNull List<TrackSnapshot> getSnapshots() {
            return snapshots;
        }

    }

    private static class EntityDirectoryImpl implements EntityDirectory {

        final List<String> entityTypes;
        final List<EntityEntry> entities;
        final List<EntityEntryImpl> entries;

        EntityDirectoryImpl(List<String> entityTypes, List<EntityEntry> entities,
                            List<EntityEntryImpl> entries) {
            this.entityTypes = entityTypes;
            this.entities = entities;
            this.entries = entries;
        }

        @Override
        public @NotNull List<String> getEntityTypes() {
            return entityTypes;
        }

        @Override
        public @NotNull List<EntityEntry> getEntities() {
            return entities;
        }

    }

}
