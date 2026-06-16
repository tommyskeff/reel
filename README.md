# Reel

A Java library for recording and replaying time-series entity state to a
compact, seekable binary format (`.reel`).

Reel is game-agnostic — it models the world as **entities** carrying
**tracks** of state over time. Recordings are chunked, layered, and
compressed, so a long recording opens instantly and seeks in any direction
without replaying from the start. Track state semantics and serialization are
supplied by the caller via `TrackModel` and `Codec`.

## Install

Maven, Java 17+:

```xml
<dependency>
    <groupId>dev.tommyjs</groupId>
    <artifactId>reel</artifactId>
    <version>0.1.0</version>
</dependency>
```

```
mvn package
```

## Recording

Register your tracks, then write frames:

```java
TrackHandle<Position, PositionDelta> POSITION =
    TrackHandle.of("position", Position.class, PositionDelta.class);
EntityTypeHandle PLAYER = EntityTypeHandle.of("player");

TrackRegistry registry = TrackRegistry.builder()
    .register(POSITION, new PositionModel(), new PositionStateCodec(), new PositionDeltaCodec())
    .build();

try (ReelRecorder recorder = ReelRecorder.builder()
        .setTrackRegistry(registry)
        .setWriter(w -> w.setFile(Path.of("match.reel")))
        .build()) {

    EntityRecorder player = recorder.createEntity(PLAYER);
    player.recordState(POSITION, new Position(0, 0));

    for (int frame = 0; frame < 1200; frame++) {
        player.recordDelta(POSITION, new PositionDelta(1, 0));
        recorder.advanceFrame();
    }
}
```

## Replaying

`ReplayCursor` drives replay. Seeks are random-access in any direction; the
cursor emits per-entity, per-track updates between the previous frame and the
new one:

```java
try (ReplayCursor cursor = ReplayCursor.fromFile(Path.of("match.reel"), registry)) {
    cursor.addListener(update -> {
        for (var entity : update.getEntities()) {
            for (var step : entity.getUpdatesFor(POSITION)) {
                if (step instanceof TrackUpdate.Snapshot<Position, PositionDelta> s) {
                    System.out.println(entity.getEntityId() + " = " + s.state());
                } else if (step instanceof TrackUpdate.Delta<Position, PositionDelta> d) {
                    System.out.println(entity.getEntityId() + " += " + d.delta());
                }
            }
        }
    });

    cursor.seek(600);   // random access forward
    cursor.seek(50);    // backward jump
}
```

For a higher-level, reactive API, `ReplayScene` lets you bind entity actors to
tracks declaratively (`onCreate` / `onState` / `onDelta` / `onEvent`) instead
of handling raw updates.

## Examples

Runnable end-to-end examples live under
[src/main/java/dev/tommyjs/reel/example](src/main/java/dev/tommyjs/reel/example):

- [ExampleMain.java](src/main/java/dev/tommyjs/reel/example/ExampleMain.java) —
  minimal record-and-replay at the `ReplayCursor` level.
- [SceneExample.java](src/main/java/dev/tommyjs/reel/example/SceneExample.java) —
  a `ReplayScene` rendered as a live terminal animation that plays, scrubs, and
  rewinds.
