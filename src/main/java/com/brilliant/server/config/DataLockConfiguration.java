package com.brilliant.server.config;

import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import org.springframework.context.annotation.*;

@Configuration
public class DataLockConfiguration {
    public record LockHandle(FileChannel channel,FileLock lock) implements AutoCloseable {
        public void close() throws IOException { lock.release();channel.close(); }
    }
    @Bean(destroyMethod="close") LockHandle dataLock(Settings.Values settings) throws IOException {
        FileChannel channel=FileChannel.open(settings.directory().resolve("server.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
        try {
            FileLock lock=channel.tryLock();
            if(lock==null) throw new IllegalStateException("DATA_DIR ya está en uso por otro servidor");
            return new LockHandle(channel,lock);
        } catch(RuntimeException|IOException e) { channel.close();throw e; }
    }
}
