package com.knode.user;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Deletes only the freshly created cache; unresolved I/O is an explicit post-fork gate. */
final class ExternalNacosCacheCleanup {
    @FunctionalInterface interface Delete {void remove(Path path) throws IOException;}
    private ExternalNacosCacheCleanup() {}
    static Map<String,Object> attempt(Path target,Path cache) throws IOException {
        return attempt(target,cache,Files::deleteIfExists);
    }
    static Map<String,Object> attempt(Path target,Path cache,Delete delete) throws IOException {
        Path parent=target.toAbsolutePath().normalize(),owned=cache.toAbsolutePath().normalize();
        if (!owned.getParent().equals(parent) || !owned.getFileName().toString().matches("external-nacos-cache-[0-9]+")
                || Files.isSymbolicLink(owned))
            throw new IllegalArgumentException("Invalid owned Nacos cache path");
        Path realParent=parent.toRealPath();
        if (Files.exists(owned,LinkOption.NOFOLLOW_LINKS)
                && (!Files.isDirectory(owned,LinkOption.NOFOLLOW_LINKS) || !owned.toRealPath().getParent().equals(realParent)))
            throw new IllegalArgumentException("Invalid resolved Nacos cache path");
        var result=new LinkedHashMap<String,Object>();
        result.put("ownedPath",owned.toString());
        if (Files.notExists(owned,LinkOption.NOFOLLOW_LINKS)) {
            result.put("state","removed-in-fork");result.put("postForkCleanupRequired",false);return result;
        }
        try {
            List<Path> paths;
            try (var walk=Files.walk(owned)) {paths=walk.sorted(Comparator.reverseOrder()).toList();}
            for (Path path:paths) delete.remove(path);
            result.put("state","removed-in-fork");result.put("postForkCleanupRequired",false);
        } catch (UncheckedIOException wrapped) {
            result.put("state","pending-post-fork");result.put("postForkCleanupRequired",true);
            result.put("attemptFailure",ExternalFailureAudit.describe("cache-prune","cleanup",wrapped.getCause()));
        } catch (IOException failure) {
            result.put("state","pending-post-fork");result.put("postForkCleanupRequired",true);
            result.put("attemptFailure",ExternalFailureAudit.describe("cache-prune","cleanup",failure));
        }
        return result;
    }
}
