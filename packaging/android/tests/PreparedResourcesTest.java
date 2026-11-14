package dev.horizon.android;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Host regression: the reported PNG-only install must recover every definition. */
public final class PreparedResourcesTest {
    private static void check(boolean value, String message) { if(!value) throw new AssertionError(message); }
    private static void clean(Path root) throws IOException {
        try(var paths = Files.walk(root)) { for(Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path); }
    }
    private static void invalid(Path zip) throws IOException {
        try(PreparedResources ignored = new PreparedResources(zip.toFile())) { throw new AssertionError("Invalid archive accepted"); }
        catch(IOException expected) { /* Rejected before publishing files. */ }
    }
    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("horizon-resource-test-");
        try {
            String[] files = {"horizon-resources.json", "assets/minecraft/atlases/blocks.json", "assets/minecraft/blockstates/stone.json", "assets/minecraft/models/block/stone.json", "assets/minecraft/font/default.json", "assets/minecraft/font/include/default.json", "assets/minecraft/font/include/space.json", "assets/minecraft/font/include/unifont.json", "assets/minecraft/font/unifont.zip", "assets/minecraft/textures/font/ascii.png", "assets/minecraft/textures/block/stone.png", "assets/minecraft/models/block/extra.json"};
            Path zip = temp.resolve("fixture.zip");
            try(ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
                for(String file : files) { out.putNextEntry(new ZipEntry("resources/"+file)); out.write(file.getBytes(java.nio.charset.StandardCharsets.UTF_8)); out.closeEntry(); }
            }
            Path root = Files.createDirectory(temp.resolve("resources"));
            try(PreparedResources source = new PreparedResources(zip.toFile())) {
                for(String file : files) if(file.endsWith(".png") || file.equals("horizon-resources.json")) {
                    Path at = root.resolve(file); Files.createDirectories(at.getParent()); Files.writeString(at,file);
                }
                check(!PreparedResources.complete(root.toFile()),"PNG-only tree must not be ready");
                // Native import deletes its ZIP before returning to the Java launcher.
                Files.delete(zip);
                check(source.restoreMissing(root.toFile()) == files.length-3,"Restore every missing entry, including non-sentinel models");
                check(PreparedResources.complete(root.toFile()),"Repaired tree must be ready");
                check(source.restoreMissing(root.toFile()) == 0,"Second verification must preserve complete files");
                for(String file : files) check(Files.readString(root.resolve(file)).equals(file),"File differs: "+file);
            }
            Path bad = temp.resolve("bad.zip");
            try(ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(bad))) {
                out.putNextEntry(new ZipEntry("resources/horizon-resources.json")); out.write(1); out.closeEntry();
            }
            invalid(bad);
            Path escape = temp.resolve("escape.zip");
            try(ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(escape))) {
                out.putNextEntry(new ZipEntry("resources/../outside")); out.write(1); out.closeEntry();
            }
            invalid(escape);
            if(args.length > 0) {
                Path input = temp.resolve("original.zip"); Files.createLink(input,Path.of(args[0]));
                Path published = Files.createDirectory(temp.resolve("original-resources"));
                try(PreparedResources source = new PreparedResources(input.toFile()); ZipFile original = new ZipFile(input.toFile())) {
                    for(String file : files) if(file.endsWith(".png") || file.equals("horizon-resources.json")) {
                        Path at = published.resolve(file); Files.createDirectories(at.getParent());
                        try(InputStream in = original.getInputStream(original.getEntry("resources/"+file))) { Files.copy(in,at); }
                    }
                    Files.delete(input);
                    int count = source.restoreMissing(published.toFile());
                    check(count > 28000,"Full original ZIP must restore the whole published tree");
                    check(PreparedResources.complete(published.toFile()),"Original font/model files must be restored");
                    System.out.println("Original ZIP: restored and CRC-verified "+count+" missing files after unlink.");
                }
            }
            System.out.println("Resource import regression tests passed.");
        } finally { clean(temp); }
    }
}
