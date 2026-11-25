package net.portswigger.mcp.capture;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Runs in a separate JVM so POSIX process-level lock release is observable. */
public final class CaptureStoreLockProbe {
    private CaptureStoreLockProbe() {}

    public static void main(String[] args) throws Exception {
        try (FileChannel channel = FileChannel.open(Path.of(args[0]), StandardOpenOption.WRITE);
             FileLock lock = channel.tryLock()) {
            if (lock != null) {
                throw new AssertionError("The active capture store lost its native ownership lock");
            }
        }
    }
}
