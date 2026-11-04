package com.knode.user;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Pure filesystem/input checks; called before allocating ports or starting processes. */
final class ExternalBrowserGuard {
    record Inputs(Path root, Path node, Path edge, Path git, Path blogService, Path adminService, Path driver, String revision) {}

    static Inputs check(Map<String,String> env, Path expectedRoot, boolean events, boolean nacos) throws IOException {
        if (events || nacos) throw new IllegalArgumentException("Browser mode must not be combined with events or Nacos mode");
        Path root=directory(required(env,"KNODE_IT_BROWSER_FRONTEND_ROOT"));
        if (!root.equals(expectedRoot.toRealPath())) throw new IllegalArgumentException("Frontend root must be the current checkout");
        Path node=executable(required(env,"KNODE_IT_BROWSER_NODE"),"node.exe");
        Path edge=executable(required(env,"KNODE_IT_BROWSER_EDGE"),"msedge.exe");
        Path git=executable(required(env,"KNODE_IT_BROWSER_GIT"),"git.exe");
        String revision=required(env,"KNODE_IT_BROWSER_FRONTEND_REVISION");
        if (!revision.matches("[0-9a-f]{40}")) throw new IllegalArgumentException("Frontend revision must be a full lowercase commit ID");
        Path blog=regular(root.resolve("frond/sg-blog-vue/node_modules/webpack-dev-server/bin/webpack-dev-server.js"));
        Path admin=regular(root.resolve("frond/sg-vue-admin/node_modules/@vue/cli-service/bin/vue-cli-service.js"));
        Path driver=regular(root.resolve("scripts/microservices/user/browser-journey.cjs"));
        if (!blog.startsWith(root) || !admin.startsWith(root) || !driver.startsWith(root))
            throw new IllegalArgumentException("Browser inputs must resolve inside the checkout");
        return new Inputs(root,node,edge,git,blog,admin,driver,revision);
    }
    private static String required(Map<String,String> env,String name) {
        String value=env.get(name);
        if (value==null || value.isBlank()) throw new IllegalArgumentException("Missing explicit browser input "+name);
        return value;
    }
    private static Path directory(String text) throws IOException {
        Path p=Path.of(text);
        if (!p.isAbsolute() || !Files.isDirectory(p)) throw new IllegalArgumentException("Browser root must be an existing absolute directory");
        return p.toRealPath();
    }
    private static Path executable(String text,String name) throws IOException {
        Path p=Path.of(text);
        if (!p.isAbsolute() || p.getFileName()==null || !p.getFileName().toString().equalsIgnoreCase(name))
            throw new IllegalArgumentException("Expected absolute browser executable path: "+name);
        return regular(p);
    }
    private static Path regular(Path p) throws IOException {
        if (!Files.isRegularFile(p)) throw new IllegalArgumentException("Missing browser input file");
        return p.toRealPath();
    }
}
