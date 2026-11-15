package dev.horizon.android;

import java.io.*;
import java.nio.channels.SeekableByteChannel;
import java.security.*;
import java.util.regex.*;
import org.apache.commons.compress.archivers.sevenz.*;
import org.apache.commons.compress.utils.MultiReadOnlySeekableByteChannel;

/** The owner's five Drive volumes contain exactly one known prepared ZIP. */
final class ResourceVolumes {
    static final int PARTS = 5;
    static final long PART_LIMIT = 100L*1024*1024;
    private static final long ZIP_SIZE = 413308554L;
    private static final String ZIP_SHA256 = "20ed97425e2282a2cef33643d12562761258d125bfbcbc0c907ce351520ad9a4";
    private static final Pattern NAME = Pattern.compile("^(.+\\.7z\\.)(00[1-5])$",Pattern.CASE_INSENSITIVE);

    static int partNumber(String name) throws IOException {
        Matcher match = NAME.matcher(name);
        if(!match.matches()) throw new IOException("Select all five archive parts .001 through .005");
        return Integer.parseInt(match.group(2))-1;
    }

    static void extract(File[] parts, OutputStream output) throws IOException {
        if(parts.length != PARTS) throw new IOException("Select all five archive parts");
        for(File file : parts) if(file == null || !file.isFile() || file.length() == 0 || file.length() > PART_LIMIT) throw new IOException("Missing or oversized archive parts");
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch(NoSuchAlgorithmException impossible) { throw new IOException(impossible); }
        try(SeekableByteChannel channel = MultiReadOnlySeekableByteChannel.forFiles(parts);
            SevenZFile archive = SevenZFile.builder().setSeekableByteChannel(channel).setMaxMemoryLimitKiB(65536).get()) {
            SevenZArchiveEntry entry = archive.getNextEntry();
            if(entry == null || entry.isDirectory() || !"Horizon-26.3.zip".equals(entry.getName()) || entry.getSize() != ZIP_SIZE) throw new IOException("Archive parts do not contain the original game ZIP");
            byte[] buffer = new byte[65536]; long count = 0; int n;
            while((n = archive.read(buffer)) != -1) {
                if(Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
                count += n;
                if(count > ZIP_SIZE) throw new IOException("Archive parts exceed expected size");
                output.write(buffer,0,n); digest.update(buffer,0,n);
            }
            StringBuilder hash = new StringBuilder();
            for(byte value : digest.digest()) hash.append(String.format(java.util.Locale.ROOT,"%02x",value & 255));
            if(count != ZIP_SIZE || !ZIP_SHA256.equals(hash.toString()) || archive.getNextEntry() != null) throw new IOException("Archive parts checksum mismatch");
        }
    }
}
