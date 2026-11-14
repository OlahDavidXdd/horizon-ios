package dev.horizon.android;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Keep the source archive open across native import, then verify the published tree. */
final class PreparedResources implements AutoCloseable {
    private static final long LIMIT = 4L << 30;
    private static final String MARKER = "horizon-resources.json";
    private static final String[] REQUIRED = {
        MARKER,
        "assets/minecraft/atlases/blocks.json",
        "assets/minecraft/blockstates/stone.json",
        "assets/minecraft/models/block/stone.json",
        "assets/minecraft/font/default.json",
        "assets/minecraft/font/include/default.json",
        "assets/minecraft/font/include/space.json",
        "assets/minecraft/font/include/unifont.json",
        "assets/minecraft/font/unifont.zip",
        "assets/minecraft/textures/font/ascii.png",
        "assets/minecraft/textures/block/stone.png"
    };
    private final ZipFile archive;
    private final Map<String,ZipEntry> files = new LinkedHashMap<>();

    PreparedResources(File source) throws IOException {
        archive = new ZipFile(source);
        try {
            Map<String,ZipEntry> names = new LinkedHashMap<>();
            String prefix = null;
            long total = 0;
            Enumeration<? extends ZipEntry> entries = archive.entries();
            while(entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName().replace('\\','/');
                if(entry.isDirectory() || name.endsWith("/")) continue;
                if(name.startsWith("__MACOSX/") || name.endsWith("/.DS_Store")) continue;
                if(name.startsWith("/") || name.contains(":") || name.indexOf('\0') >= 0) throw new IOException("Unsafe resource ZIP path");
                for(String part : name.split("/",-1)) if(part.isEmpty() || part.equals(".") || part.equals("..")) throw new IOException("Unsafe resource ZIP path");
                if(names.put(name,entry) != null || names.size() > 50000) throw new IOException("Duplicate or excessive ZIP entries");
                if(entry.getSize() < 0 || entry.getSize() > LIMIT || (total += entry.getSize()) > LIMIT) throw new IOException("Resource ZIP exceeds size limit");
                if(name.equals(MARKER) || name.endsWith("/" + MARKER)) {
                    if(prefix != null) throw new IOException("Multiple resource roots");
                    prefix = name.substring(0,name.length()-MARKER.length());
                }
            }
            if(prefix == null) throw new IOException("Incomplete resource ZIP: missing " + MARKER);
            for(Map.Entry<String,ZipEntry> entry : names.entrySet()) {
                if(!entry.getKey().startsWith(prefix)) throw new IOException("ZIP entry outside resource root");
                files.put(entry.getKey().substring(prefix.length()),entry.getValue());
            }
            for(String path : REQUIRED) {
                ZipEntry entry = files.get(path);
                if(entry == null || entry.getSize() == 0) throw new IOException("Incomplete resource ZIP: missing " + path);
            }
        } catch(IOException | RuntimeException failure) { archive.close(); throw failure; }
    }

    static boolean complete(File root) {
        for(String path : REQUIRED) {
            File file = new File(root,path);
            if(!file.isFile() || file.length() == 0) return false;
        }
        return true;
    }

    /** Native import has finished and the launcher keeps Play disabled until this returns. */
    int restoreMissing(File root) throws IOException {
        if(!root.isDirectory() || Files.isSymbolicLink(root.toPath())) throw new IOException("Missing resource destination");
        String base = root.getCanonicalPath() + File.separator;
        int repaired = 0;
        for(Map.Entry<String,ZipEntry> item : files.entrySet()) {
            if(Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
            File file = new File(root,item.getKey());
            if(!file.getCanonicalPath().startsWith(base)) throw new IOException("Resource destination escapes root");
            ZipEntry entry = item.getValue();
            if(file.isFile() && file.length() == entry.getSize()) continue;
            File parent = file.getParentFile();
            if(!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create resource folder");
            File temp = File.createTempFile(".resource-", ".tmp",parent);
            try {
                CRC32 crc = new CRC32(); long count = 0;
                try(InputStream in = archive.getInputStream(entry); OutputStream out = new FileOutputStream(temp)) {
                    byte[] buffer = new byte[65536]; int n;
                    while((n = in.read(buffer)) != -1) {
                        count += n;
                        if(count > entry.getSize()) throw new IOException("Resource entry exceeds declared size");
                        out.write(buffer,0,n); crc.update(buffer,0,n);
                    }
                }
                if(count != entry.getSize() || crc.getValue() != entry.getCrc()) throw new IOException("Resource ZIP checksum mismatch");
                try { Files.move(temp.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE); }
                catch(AtomicMoveNotSupportedException ignored) { Files.move(temp.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING); }
                repaired++;
            } finally { temp.delete(); }
        }
        if(!complete(root)) throw new IOException("Incomplete resource folder after import");
        return repaired;
    }

    @Override public void close() throws IOException { archive.close(); }
}
