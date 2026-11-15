package dev.horizon.android;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.apache.commons.compress.archivers.sevenz.*;

public final class ResourceVolumesTest {
    private static void check(boolean value,String message) { if(!value) throw new AssertionError(message); }
    private static void rejected(File[] parts) throws IOException {
        try { ResourceVolumes.extract(parts,OutputStream.nullOutputStream()); throw new AssertionError("Invalid archive accepted"); }
        catch(IOException expected) { /* Invalid selection must not reach native import. */ }
    }
    public static void main(String[] args) throws Exception {
        check(ResourceVolumes.partNumber("Horizon-download.7z.001")==0,"First part order");
        check(ResourceVolumes.partNumber("Horizon-download.7z.005")==4,"Last part order");
        try { ResourceVolumes.partNumber("Horizon-download.7z.006"); throw new AssertionError("Wrong part accepted"); }
        catch(IOException expected) { }
        rejected(new File[4]); rejected(new File[5]);
        Path temp=Files.createTempDirectory("horizon-volumes-test-");
        try {
            Path tiny=temp.resolve("wrong.7z");
            try(SevenZOutputFile out=new SevenZOutputFile(tiny.toFile())) {
                out.setContentCompression(SevenZMethod.COPY);
                SevenZArchiveEntry entry=new SevenZArchiveEntry(); entry.setName("Horizon-26.3.zip");
                out.putArchiveEntry(entry); out.write(new byte[]{1,2,3}); out.closeArchiveEntry();
            }
            byte[] bytes=Files.readAllBytes(tiny); File[] parts=new File[5];
            for(int i=0;i<5;i++) {
                Path part=temp.resolve("part-"+i); Files.write(part,Arrays.copyOfRange(bytes,bytes.length*i/5,bytes.length*(i+1)/5)); parts[i]=part.toFile();
            }
            rejected(parts); // A valid 7z wrapper containing the wrong inner ZIP is refused.
            Files.write(parts[4].toPath(),new byte[0]); rejected(parts);
            if(args.length>0) {
                File[] actual=Arrays.stream(args).map(File::new).toArray(File[]::new);
                long[] count={0};
                OutputStream sink=new OutputStream() {
                    public void write(int value) { count[0]++; }
                    public void write(byte[] bytes,int offset,int length) { count[0]+=length; }
                };
                ResourceVolumes.extract(actual,sink);
                check(count[0]==413308554L,"Original ZIP size differs");
                System.out.println("Real Drive volumes: reconstructed and SHA-256 verified "+count[0]+" bytes.");
            }
            System.out.println("Volume selection and extraction tests passed.");
        } finally {
            try(var paths=Files.walk(temp)) { for(Path path:paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path); }
        }
    }
}
