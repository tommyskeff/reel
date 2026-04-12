# Reel Recording File Format (.reel)

## Design principles

- Entities use consecutive numeric IDs (0, 1, 2, ...) assigned by the
  recording engine based on creation order.
- Entity types and track types use caller-assigned numeric IDs. These must
  be stable across versions (append-only, never reuse an ID).
- Timestamps are called "indices" (not "ticks") to be game-agnostic.
- No frame rate is stored — callers decide whether indices represent
  fixed-rate frames or real timestamps.
- Entities may spawn and despawn multiple times.
- Any track type may be used on any entity — track bindings are not
  enforced at the format level.
- The entity directory and track directory are small enough to load
  eagerly. Track indexes and operation data are loaded lazily on demand.

## Primitive encoding

| Type     | Encoding                                         |
|----------|--------------------------------------------------|
| u8       | Unsigned 8-bit integer                           |
| u16      | Unsigned 16-bit big-endian                       |
| u32      | Unsigned 32-bit big-endian                       |
| u64      | Unsigned 64-bit big-endian                       |
| varint   | Unsigned LEB128 variable-length integer           |
| svarint  | Signed ZigZag + LEB128 variable-length integer    |
| f32      | IEEE 754 32-bit float big-endian                 |
| f64      | IEEE 754 64-bit float big-endian                 |
| bytes    | varint length prefix + raw bytes                 |
| string   | varint length prefix + UTF-8 bytes               |

---

## File structure overview

```
┌─────────────────────────┐
│  File Header            │  Fixed 8 bytes, magic + version + flags
├─────────────────────────┤
│  Operation Data Section │  Compressed blocks of encoded operations
├─────────────────────────┤
│  Entity Directory       │  Lifecycle entries for all entities
├─────────────────────────┤
│  Track Directory        │  Track type definitions (ID + codec version)
├─────────────────────────┤
│  Track Index Section    │  Per-(entity, track) layer entry metadata
├─────────────────────────┤
│  File Footer            │  Section offsets + finalization marker
└─────────────────────────┘
```

### Write order

During recording, the file is written top-to-bottom in this exact order:

1. **Header** — written at recording start (8 bytes)
2. **Operation data** — streamed continuously during recording as
   operations are produced, appended to the file in real time
3. **Entity directory** — written at finalization from in-memory state
4. **Track directory** — written at finalization from in-memory state
5. **Track index** — written at finalization from in-memory state
6. **Footer** — written last, contains byte offsets to all sections

The entity directory, track directory, and track index are held in memory
during recording. This is feasible because their size is proportional to
the number of *layer entries produced*, not entity_count × time. Quiet
entities (dropped items, static decorations) produce very few layer entries
and consume minimal memory.

### Read order

Readers start from the end: read the footer (last 52 bytes) to get
section offsets, then jump to whatever section they need. Typical
read sequence:

1. Footer (seek to EOF-52)
2. Entity directory (loaded eagerly, small)
3. Track directory (loaded eagerly, small)
4. Track index entries (loaded lazily per entity, on demand)
5. Operation data blocks (loaded lazily, only for operations on the
   chosen seek path)

---

## File header (8 bytes, fixed)

```
magic            : u8[4]   = 0x52 0x45 0x45 0x4C  ("REEL")
format_version   : u16     = 1
flags            : u16     (reserved, must be 0)
```

---

## Operation data section

Bulk storage for encoded operations. This is the largest section by far,
typically 90%+ of the file size.

### Block structure

Operations are grouped into compressed blocks. Each block contains a batch
of operations and is independently decompressible (no cross-block
dependencies). This allows random access — to read one operation, you
decompress only the block containing it.

```
OperationDataSection:
  blocks             : OperationBlock[block_count]
                       (block_count is stored in the file footer)

OperationBlock:
  uncompressed_size  : u32       Original size before compression
  compressed_size    : u32       Size of compressed payload
  compressed_data    : u8[compressed_size]
```

Within a decompressed block, operations are packed with no internal
framing. The track index provides exact (block_index, offset_in_block,
size) tuples to locate each operation. A single block may contain
operations from multiple entities and tracks — they are ordered
chronologically as produced during recording.

### Compression: LZ4 frame format

Operation data uses **LZ4** (frame format, not raw block) for these reasons:

- **Decompression speed is the priority.** During playback, especially
  live 1x-speed playback, operations must be decoded with minimal latency.
  LZ4 decompresses at 3-5 GB/s on modern hardware — effectively free.
- **Acceptable compression ratio.** LZ4 typically achieves 2-3x
  compression on operation data. The data has moderate redundancy
  (repeated entity IDs, similar position values) but is not as
  compressible as text.
- **Frame format includes content checksum.** The LZ4 frame format
  includes an optional content checksum (XXH32). We enable this, giving
  us per-block integrity verification for free without a separate
  checksum mechanism.
- **Block independence.** Each OperationBlock is a complete LZ4 frame.
  Decompressing block N does not require data from block N-1.

Target block size before compression: 64 KB. The recording engine
accumulates operations in a buffer and flushes a new block when the
buffer reaches this threshold, or when a flush is forced (finalization,
memory pressure).

---

## Entity directory

Loaded eagerly at playback start. Contains lifecycle information for
every entity in the recording. Entities are stored in order of their
numeric ID (0, 1, 2, ...) — the ID is implied by position, not stored.

```
EntityDirectory:
  compression        : u8       0 = none, 1 = ZSTD
  uncompressed_size  : u32      (only present if compression != 0)
  compressed_size    : u32      (only present if compression != 0)
  data               : u8[...]  Compressed or raw directory data

DirectoryData (after decompression):
  entity_type_count  : varint
  entity_types       : EntityTypeDef[entity_type_count]
  entity_count       : varint
  entities           : EntityEntry[entity_count]

EntityTypeDef:
  type_id            : varint    Library-assigned numeric entity type ID
  string_id          : string    Caller-provided string identifier

EntityEntry:
  entity_type_id     : varint    References EntityTypeDef.type_id
  spawn_count        : varint    Number of spawn/despawn cycles
  spawns             : SpawnEntry[spawn_count]

SpawnEntry:
  spawn_index        : varint    Index at which this entity spawned
  despawn_index      : svarint   Index at which despawned, or -1 if still
                                 alive at recording end (or until next spawn)
  creation_data      : bytes     Caller-encoded creation payload (UUID,
                                 display name, initial state, etc.)
                                 Opaque to the library.
```

### Compression: ZSTD

The entity directory uses **ZSTD** (with dictionary if available) because:

- **Read once, decompress once.** This section is loaded exactly once at
  playback start, so decompression speed is less critical than ratio.
- **High redundancy.** Entity entries are structurally similar (same
  fields, similar value ranges). ZSTD's larger window exploits this
  repetition far better than LZ4. A directory with 50,000 entities of
  5 types compresses extremely well — the type_id and spawn patterns
  repeat constantly.
- **Meaningful size reduction.** For large recordings with many entities,
  the directory can be several hundred KB uncompressed. ZSTD typically
  achieves 5-10x compression here, reducing it to tens of KB.
- **Built-in integrity.** ZSTD frames include a content checksum by
  default when using the frame format.

For small recordings (< 1000 entities), the directory is small enough
that compression is optional. The compression byte allows the writer to
skip compression when the overhead isn't worth it.

---

## Track directory

Loaded eagerly at playback start. Contains all track types used in the
recording with their codec versions. This allows readers to verify codec
compatibility before attempting to decode any operation data.

```
TrackDirectory:
  track_count        : varint
  tracks             : TrackDef[track_count]

TrackDef:
  track_type_id      : varint    Library-assigned numeric track type ID
  string_id          : string    Caller-provided string identifier
  codec_version      : varint    Caller-managed codec version number
```

No compression. This section is tiny (typically under 1 KB) and must be
readable without any decompression dependencies for format introspection
tools.

---

## Track index section

Per-(entity, track) metadata used by the seek planner to build the seek
graph. Loaded lazily — only read when the playback engine needs to seek
within a specific entity's track.

The section begins with an offset table (one `u64` per entity, in entity
ID order matching the entity directory). Each offset is relative to the
start of this section and points to the entity's track index block.

```
TrackIndexSection:
  offset_table       : u64[entity_count]   One offset per entity, pointing
                                           to that entity's block (relative
                                           to section start)
  blocks             : EntityTrackIndexBlock[entity_count]

EntityTrackIndexBlock:
  compression        : u8       0 = none, 1 = LZ4
  uncompressed_size  : u32      (only present if compression != 0)
  compressed_size    : u32      (only present if compression != 0)
  data               : u8[...]  Compressed or raw index data

TrackIndexData (after decompression):
  track_count        : varint    Number of tracks with data
  tracks             : TrackIndex[track_count]

TrackIndex:
  track_type_id      : varint    References TrackDef.track_type_id
  span_count         : varint
  spans              : TrackSpanEntry[span_count]
  snapshot_count     : varint
  snapshots          : TrackSnapshotEntry[snapshot_count]

TrackSpanEntry:
  reference_frame    : varint    Frame at which this span starts (absolute)
  target_frame       : varint    Frame at which this span ends
  cost               : varint    Caller-assigned application cost weight
  ref_id             : u64       Packed reference (block_index, offset, size)

TrackSnapshotEntry:
  frame              : varint    Frame of this snapshot (absolute)
  cost               : varint    Caller-assigned application cost weight
  ref_id             : u64       Packed reference (block_index, offset, size)
```

### Compression: LZ4

Track index blocks use **LZ4** because:

- **Read latency matters.** Track indexes are loaded on-demand during
  seeks. A spectator skipping forward triggers a track index load for
  every visible entity. This must be fast.
- **Good fit for the data.** Index entries are small, regular structures.
  LZ4 handles this well with minimal CPU cost.
- **Per-entity blocks.** Each entity has its own compressed block, so
  loading one entity's index doesn't require decompressing other
  entities' data.

For entities with very few track entries (< 10 entries), compression is
skipped (compression byte = 0) since the overhead exceeds the savings.

---

## File footer (52 bytes, fixed)

Written last during finalization.

```
total_frames         : u64    Total index count in the recording
op_data_offset       : u64    Byte offset of Operation Data Section
op_block_count       : u32    Number of OperationBlocks in the
                              Operation Data Section
entity_dir_offset    : u64    Byte offset of Entity Directory
track_dir_offset     : u64    Byte offset of Track Directory
track_index_offset   : u64    Byte offset of Track Index Section
footer_magic         : u8[8]  = 0x52 0x45 0x45 0x4C 0x45 0x4E 0x44 0x00
                                ("REELEND\0")
```

The footer magic serves as the finalization marker. If these 8 bytes are
present at EOF-8, the file was properly finalized. If absent, the file is
incomplete (recording crashed or is still in progress).

No whole-file checksum. Integrity is verified per-section by the
compression format:
- Operation data blocks: LZ4 frame content checksum (XXH32)
- Entity directory: ZSTD frame content checksum
- Track index blocks: LZ4 frame content checksum
- Track directory and footer: uncompressed, integrity implied by structural
  validity (parseable = valid)

---

## Recovery from incomplete files

If the footer magic is absent:

1. Header is at byte 0 (fixed offset, always valid)
2. Operation data starts at byte 8 (fixed offset, immediately after
   header) — operation blocks follow
3. Entity directory, track directory, and track index are missing — must
   be reconstructed

Recovery scans the operation data blocks sequentially. Each LZ4 frame is
self-delimiting (the frame format encodes its own size), so blocks can be
found without an index. The recovery tool decompresses each block, parses
the operations within (using codec information from the track directory),
and rebuilds the entity directory and track index in memory. Then appends
them to the file along with the footer.

This is slow (full file scan + decompression) but only needed after a
crash, not during normal operation.

---

## Entity ID assignment

The recording engine assigns entity IDs as consecutive integers starting
from 0, in entity creation order. Since varint encoding favors smaller
values, entities created earlier get smaller IDs and are cheaper to
reference throughout the file.

Callers can influence this by creating high-frequency entities first
(e.g. register all player entities before registering dropped items).
The library does not reorder IDs after recording — that would require a
full file rewrite. A post-processing tool could do this as an optimization
pass if desired.

---

## Size estimates

A 5-minute Minecraft recording at 20 indices/second, 100 players, 500
chunk sections:

| Section          | Uncompressed | Compressed | Ratio | Notes                        |
|------------------|-------------|------------|-------|-------------------------------|
| Header           | 8 B         | 8 B        | 1:1   | Fixed, not compressed         |
| Operation data   | ~60 MB      | ~25 MB     | 2.4:1 | LZ4, dominated by positions   |
| Entity directory | ~24 KB      | ~4 KB      | 6:1   | ZSTD, highly repetitive       |
| Track directory  | ~200 B      | ~200 B     | 1:1   | Tiny, not compressed          |
| Track index      | ~800 KB     | ~300 KB    | 2.7:1 | LZ4                           |
| Footer           | 52 B        | 52 B       | 1:1   | Fixed, not compressed         |
| **Total**        | **~61 MB**  | **~25 MB** |       |                               |
