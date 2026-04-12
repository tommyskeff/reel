package dev.tommyjs.reel.storage;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

class ReelFileWriter implements ReelWriter {

    private final RandomAccessFile file;
    private final ReentrantLock lock = new ReentrantLock();
    private final ByteArrayOutputStream blockBuffer = new ByteArrayOutputStream();

    private final LinkedHashMap<String, Integer> entityTypeRegistry = new LinkedHashMap<>();
    private final LinkedHashMap<Long, EntityState> entities = new LinkedHashMap<>();
    private final LinkedHashMap<String, TrackDef> trackDirectory = new LinkedHashMap<>();
    private final Map<Long, Map<Integer, TrackEntries>> trackIndex = new HashMap<>();

    private int nextEntityTypeId = 0;
    private int nextTrackId = 0;
    private int blockCount = 0;
    private int currentOffsetInBlock = 0;
    private boolean finished = false;
    private boolean closed = false;

    ReelFileWriter(RandomAccessFile file) throws IOException {
        this.file = file;
        writeHeader();
    }

    private void writeHeader() throws IOException {
        file.write(ReelFormat.headerMagic());
        file.writeShort(ReelFormat.FORMAT_VERSION);
        file.writeShort(0);
    }

    @Override
    public void registerEntityType(@NotNull String typeId) {
        lock.lock();
        try {
            ensureNotFinished();
            entityTypeRegistry.putIfAbsent(typeId, nextEntityTypeId++);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void registerTrack(@NotNull String trackId, int codecVersion) {
        lock.lock();
        try {
            ensureNotFinished();
            trackDirectory.putIfAbsent(trackId, new TrackDef(nextTrackId++, codecVersion));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void writeEntityCreate(long entityId, @NotNull String entityType, byte @NotNull [] metadata) {
        lock.lock();
        try {
            ensureNotFinished();
            int typeId = resolveEntityType(entityType);
            if (entities.containsKey(entityId)) {
                throw new IllegalStateException("Entity " + entityId + " has already been created");
            }
            entities.put(entityId, new EntityState(typeId, metadata.clone()));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void writeEntitySpawn(long entityId, long frame) {
        if (frame < 0) {
            throw new IllegalArgumentException("frame must be non-negative, got " + frame);
        }
        lock.lock();
        try {
            ensureNotFinished();
            EntityState state = entities.get(entityId);
            if (state == null) {
                throw new IllegalStateException("Entity " + entityId + " has not been created");
            }
            if (!state.spawns.isEmpty()) {
                SpawnState last = state.spawns.get(state.spawns.size() - 1);
                if (last.despawnFrame == -1) {
                    throw new IllegalStateException("Entity " + entityId + " must be despawned before re-spawning");
                }
            }
            state.spawns.add(new SpawnState(frame, -1));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void writeEntityDespawn(long entityId, long frame) {
        if (frame < 0) {
            throw new IllegalArgumentException("frame must be non-negative, got " + frame);
        }
        lock.lock();
        try {
            ensureNotFinished();
            EntityState state = entities.get(entityId);
            if (state == null) {
                throw new IllegalStateException("Entity " + entityId + " has not been created");
            }
            if (state.spawns.isEmpty()) {
                throw new IllegalStateException("Entity " + entityId + " has not been spawned");
            }
            SpawnState last = state.spawns.get(state.spawns.size() - 1);
            if (last.despawnFrame != -1) {
                throw new IllegalStateException("Entity " + entityId + " already despawned in current cycle");
            }
            last.despawnFrame = frame;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void writeTrackSpan(long entityId, @NotNull String track, long referenceFrame, long targetFrame, int cost, byte @NotNull [] data) {
        if (referenceFrame < 0) {
            throw new IllegalArgumentException("referenceFrame must be non-negative, got " + referenceFrame);
        }
        if (targetFrame < 0) {
            throw new IllegalArgumentException("targetFrame must be non-negative, got " + targetFrame);
        }
        if (cost < 0) {
            throw new IllegalArgumentException("cost must be non-negative, got " + cost);
        }
        lock.lock();
        try {
            ensureNotFinished();
            ensureEntitySpawned(entityId);
            int trackId = resolveTrack(track);
            long refId = appendToBlock(data);
            getTrackEntries(entityId, trackId).spans.add(new SpanEntry(refId, referenceFrame, targetFrame, cost));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void writeTrackSnapshot(long entityId, @NotNull String track, long frame, int cost, byte @NotNull [] data) {
        if (frame < 0) {
            throw new IllegalArgumentException("frame must be non-negative, got " + frame);
        }
        if (cost < 0) {
            throw new IllegalArgumentException("cost must be non-negative, got " + cost);
        }
        lock.lock();
        try {
            ensureNotFinished();
            ensureEntitySpawned(entityId);
            int trackId = resolveTrack(track);
            long refId = appendToBlock(data);
            getTrackEntries(entityId, trackId).snapshots.add(new SnapshotEntry(refId, frame, cost));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void finish(long totalFrames) {
        if (totalFrames < 0) {
            throw new IllegalArgumentException("totalFrames must be non-negative, got " + totalFrames);
        }
        lock.lock();
        try {
            if (finished) return;

            flushBlock();

            long opDataOffset = ReelFormat.HEADER_SIZE;
            long entityDirOffset = file.getFilePointer();
            writeEntityDirectory();

            long trackDirOffset = file.getFilePointer();
            writeTrackDirectory();

            long trackIndexOffset = file.getFilePointer();
            writeTrackIndexSection();

            writeFooter(totalFrames, opDataOffset, entityDirOffset, trackDirOffset, trackIndexOffset);
            finished = true;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() throws IOException {
        lock.lock();
        try {
            if (closed) return;
            closed = true;
            file.close();
        } finally {
            lock.unlock();
        }
    }

    private void ensureNotFinished() {
        if (finished) {
            throw new IllegalStateException("Writer has already been finished");
        }
    }

    private void ensureEntitySpawned(long entityId) {
        EntityState state = entities.get(entityId);
        if (state == null) {
            throw new IllegalStateException("Entity " + entityId + " has not been created");
        }

        if (state.spawns.isEmpty()) {
            throw new IllegalStateException("Entity " + entityId + " has not been spawned");
        }

        SpawnState last = state.spawns.get(state.spawns.size() - 1);
        if (last.despawnFrame != -1) {
            throw new IllegalStateException("Entity " + entityId + " is currently despawned");
        }
    }

    private int resolveEntityType(String entityType) {
        Integer id = entityTypeRegistry.get(entityType);
        if (id == null) {
            throw new IllegalStateException("Entity type \"" + entityType + "\" has not been registered");
        }

        return id;
    }

    private int resolveTrack(String trackId) {
        TrackDef def = trackDirectory.get(trackId);
        if (def == null) {
            throw new IllegalStateException("Track \"" + trackId + "\" has not been registered");
        }

        return def.id;
    }

    private long appendToBlock(byte[] data) {
        long refId = ReelFormat.packRefId(blockCount, currentOffsetInBlock, data.length);
        blockBuffer.write(data, 0, data.length);
        currentOffsetInBlock += data.length;

        if (blockBuffer.size() >= ReelFormat.BLOCK_FLUSH_THRESHOLD) {
            try {
                flushBlock();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        return refId;
    }

    private void flushBlock() throws IOException {
        if (blockBuffer.size() == 0) return;

        byte[] uncompressed = blockBuffer.toByteArray();
        byte[] compressed = ReelIOUtil.compressLz4(uncompressed);

        file.writeInt(uncompressed.length);
        file.writeInt(compressed.length);
        file.write(compressed);

        blockBuffer.reset();
        blockCount++;
        currentOffsetInBlock = 0;
    }

    private void writeEntityDirectory() throws IOException {
        ByteBuf buf = Unpooled.buffer();
        try {
            ReelIOUtil.writeVarInt(buf, entityTypeRegistry.size());
            for (var regEntry : entityTypeRegistry.entrySet()) {
                ReelIOUtil.writeVarInt(buf, regEntry.getValue());
                byte[] strBytes = regEntry.getKey().getBytes(StandardCharsets.UTF_8);
                ReelIOUtil.writeVarInt(buf, strBytes.length);
                buf.writeBytes(strBytes);
            }

            ReelIOUtil.writeVarInt(buf, entities.size());
            for (var mapEntry : entities.entrySet()) {
                long entityId = mapEntry.getKey();
                EntityState state = mapEntry.getValue();
                ReelIOUtil.writeVarInt(buf, entityId);
                ReelIOUtil.writeVarInt(buf, state.entityTypeId);
                ReelIOUtil.writeVarInt(buf, state.metadata.length);
                buf.writeBytes(state.metadata);
                ReelIOUtil.writeVarInt(buf, state.spawns.size());
                for (SpawnState spawn : state.spawns) {
                    ReelIOUtil.writeVarInt(buf, spawn.spawnFrame);
                    ReelIOUtil.writeSVarInt(buf, spawn.despawnFrame);
                }
            }

            byte[] uncompressed = new byte[buf.readableBytes()];
            buf.readBytes(uncompressed);

            if (uncompressed.length > ReelFormat.ZSTD_COMPRESSION_THRESHOLD) {
                byte[] compressed = ReelIOUtil.compressZstd(uncompressed);
                file.writeByte(ReelFormat.COMPRESSION_ZSTD);
                file.writeInt(uncompressed.length);
                file.writeInt(compressed.length);
                file.write(compressed);
            } else {
                file.writeByte(ReelFormat.COMPRESSION_NONE);
                file.write(uncompressed);
            }
        } finally {
            buf.release();
        }
    }

    private void writeTrackDirectory() throws IOException {
        ByteBuf buf = Unpooled.buffer();
        try {
            ReelIOUtil.writeVarInt(buf, trackDirectory.size());
            for (var entry : trackDirectory.entrySet()) {
                ReelIOUtil.writeVarInt(buf, entry.getValue().id);
                byte[] strBytes = entry.getKey().getBytes(StandardCharsets.UTF_8);
                ReelIOUtil.writeVarInt(buf, strBytes.length);
                buf.writeBytes(strBytes);
                ReelIOUtil.writeVarInt(buf, entry.getValue().codecVersion);
            }
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            file.write(data);
        } finally {
            buf.release();
        }
    }

    private void writeTrackIndexSection() throws IOException {
        long offsetTableSize = (long) entities.size() * 8;

        List<byte[]> encodedBlocks = new ArrayList<>();
        List<Long> offsets = new ArrayList<>();
        long currentOffset = offsetTableSize;

        for (long entityId : entities.keySet()) {
            offsets.add(currentOffset);
            Map<Integer, TrackEntries> tracks = trackIndex.getOrDefault(entityId, Collections.emptyMap());
            byte[] block = encodeEntityTrackIndexBlock(tracks);
            encodedBlocks.add(block);
            currentOffset += block.length;
        }

        for (long offset : offsets) {
            file.writeLong(offset);
        }

        for (byte[] block : encodedBlocks) {
            file.write(block);
        }
    }

    private byte[] encodeEntityTrackIndexBlock(Map<Integer, TrackEntries> tracks) throws IOException {
        ByteBuf buf = Unpooled.buffer();
        try {
            ReelIOUtil.writeVarInt(buf, tracks.size());
            for (var entry : tracks.entrySet()) {
                ReelIOUtil.writeVarInt(buf, entry.getKey());
                TrackEntries te = entry.getValue();

                ReelIOUtil.writeVarInt(buf, te.spans.size());
                for (SpanEntry span : te.spans) {
                    ReelIOUtil.writeVarInt(buf, span.referenceFrame);
                    ReelIOUtil.writeVarInt(buf, span.targetFrame);
                    ReelIOUtil.writeVarInt(buf, span.cost);
                    buf.writeLong(span.refId);
                }

                ReelIOUtil.writeVarInt(buf, te.snapshots.size());
                for (SnapshotEntry snap : te.snapshots) {
                    ReelIOUtil.writeVarInt(buf, snap.frame);
                    ReelIOUtil.writeVarInt(buf, snap.cost);
                    buf.writeLong(snap.refId);
                }
            }

            byte[] uncompressed = new byte[buf.readableBytes()];
            buf.readBytes(uncompressed);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(baos);
            if (uncompressed.length > ReelFormat.LZ4_TRACK_INDEX_THRESHOLD) {
                byte[] compressed = ReelIOUtil.compressLz4(uncompressed);
                out.writeByte(ReelFormat.COMPRESSION_LZ4);
                out.writeInt(uncompressed.length);
                out.writeInt(compressed.length);
                out.write(compressed);
            } else {
                out.writeByte(ReelFormat.COMPRESSION_NONE);
                out.write(uncompressed);
            }
            return baos.toByteArray();
        } finally {
            buf.release();
        }
    }

    private void writeFooter(long totalFrames, long opDataOffset, long entityDirOffset,
                              long trackDirOffset, long trackIndexOffset) throws IOException {
        file.writeLong(totalFrames);
        file.writeLong(opDataOffset);
        file.writeInt(blockCount);
        file.writeLong(entityDirOffset);
        file.writeLong(trackDirOffset);
        file.writeLong(trackIndexOffset);
        file.write(ReelFormat.footerMagic());
    }

    private TrackEntries getTrackEntries(long entityId, int trackId) {
        return trackIndex
                .computeIfAbsent(entityId, k -> new LinkedHashMap<>())
                .computeIfAbsent(trackId, k -> new TrackEntries());
    }

    private static class EntityState {

        final int entityTypeId;
        final byte[] metadata;
        final List<SpawnState> spawns = new ArrayList<>();

        EntityState(int entityTypeId, byte[] metadata) {
            this.entityTypeId = entityTypeId;
            this.metadata = metadata;
        }

    }

    private static class SpawnState {

        final long spawnFrame;
        long despawnFrame;

        SpawnState(long spawnFrame, long despawnFrame) {
            this.spawnFrame = spawnFrame;
            this.despawnFrame = despawnFrame;
        }

    }

    private static class TrackEntries {

        private final List<SpanEntry> spans = new ArrayList<>();
        private final List<SnapshotEntry> snapshots = new ArrayList<>();

    }

    private static class TrackDef {

        final int id;
        final int codecVersion;

        TrackDef(int id, int codecVersion) {
            this.id = id;
            this.codecVersion = codecVersion;
        }

    }

    private record SpanEntry(long refId, long referenceFrame, long targetFrame, int cost) {
    }

    private record SnapshotEntry(long refId, long frame, int cost) {
    }

}
