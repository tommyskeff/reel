package dev.tommyjs.reel.storage;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;

public final class ReelFileStorage {

    private ReelFileStorage() {
    }

    public static ReelWriter openWriter(Path path) throws IOException {
        RandomAccessFile raf = new RandomAccessFile(path.toFile(), "rw");
        raf.setLength(0);
        return new ReelFileWriter(raf);
    }

    public static ReelWriter openWriter(File file) throws IOException {
        RandomAccessFile raf = new RandomAccessFile(file, "rw");
        raf.setLength(0);
        return new ReelFileWriter(raf);
    }

    public static ReelReader openReader(Path path) throws IOException {
        return new ReelFileReader(new RandomAccessFile(path.toFile(), "r"));
    }

    public static ReelReader openReader(File file) throws IOException {
        return new ReelFileReader(new RandomAccessFile(file, "r"));
    }

}
