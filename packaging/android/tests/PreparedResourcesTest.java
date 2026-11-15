package dev.horizon.android;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Host regression: a PNG-only publication is rejected without writing to it. */
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
                try { source.verifyPublished(root.toFile()); throw new AssertionError("Incomplete publication accepted"); }
                catch(IOException expected) { check(expected.getMessage().contains("Incomplete"),"Expected missing-file diagnosis"); }
                check(!Files.exists(root.resolve("assets/minecraft/font/default.json")),"Verification must not repair a tree the renderer may be reading");
                for(String file : files) {
                    Path at = root.resolve(file); Files.createDirectories(at.getParent()); Files.writeString(at,file);
                }
                check(!PreparedResources.complete(root.toFile()),"Uncommitted files must not be ready");
                Files.writeString(root.resolve(".horizon-import-verified"),"horizon-resource-import-v1\n");
                check(source.verifyPublished(root.toFile()) == files.length,"Complete publication must verify after source unlink");
                check(PreparedResources.complete(root.toFile()),"Complete tree must be ready");
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
                Path mirror=Files.createDirectory(temp.resolve("original-tree"));
                Path prepared=Path.of(args[1]);
                try(var tree=Files.walk(prepared)) {
                    for(Path from : tree.toList()) {
                        Path to=mirror.resolve(prepared.relativize(from));
                        if(Files.isDirectory(from)) Files.createDirectories(to);
                        else Files.createLink(to,from);
                    }
                }
                Files.writeString(mirror.resolve(".horizon-import-verified"),"horizon-resource-import-v1\n");
                try(PreparedResources source = new PreparedResources(Path.of(args[0]).toFile())) {
                    source.close();
                    int count = source.verifyPublished(mirror.toFile());
                    check(count > 28000,"Whole original prepared tree must verify");
                    System.out.println("Original ZIP: preflighted and verified "+count+" published files without writes.");
                }
            }
            System.out.println("Resource import regression tests passed.");
        } finally { clean(temp); }
    }
}
