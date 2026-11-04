package com.knode.user;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ExternalBrowserGuardTest {
    @TempDir Path root;
    private Map<String,String> inputs() throws Exception {
        Path node=Files.createFile(root.resolve("node.exe"));
        Path edge=Files.createFile(root.resolve("msedge.exe"));
        Path git=Files.createFile(root.resolve("git.exe"));
        for (String name:new String[]{"frond/sg-blog-vue/node_modules/webpack-dev-server/bin/webpack-dev-server.js",
                "frond/sg-vue-admin/node_modules/@vue/cli-service/bin/vue-cli-service.js",
                "scripts/microservices/user/browser-journey.cjs"}) {
            Path p=root.resolve(name);Files.createDirectories(p.getParent());Files.createFile(p);
        }
        return new HashMap<>(Map.of("KNODE_IT_BROWSER_FRONTEND_ROOT",root.toString(),
                "KNODE_IT_BROWSER_NODE",node.toString(),"KNODE_IT_BROWSER_EDGE",edge.toString(),"KNODE_IT_BROWSER_GIT",git.toString(),
                "KNODE_IT_BROWSER_FRONTEND_REVISION","a".repeat(40)));
    }
    @Test void acceptsExplicitIsolatedInputs() throws Exception {
        var inputs=inputs();assertEquals(root.toRealPath(),ExternalBrowserGuard.check(inputs,root,false,false).root());
    }
    @Test void rejectsUnsafeModesAndInputs() throws Exception {
        var inputs=inputs();
        assertThrows(IllegalArgumentException.class,()->ExternalBrowserGuard.check(inputs,root,true,false));
        assertThrows(IllegalArgumentException.class,()->ExternalBrowserGuard.check(inputs,root,false,true));
        inputs.put("KNODE_IT_BROWSER_FRONTEND_REVISION","HEAD");
        assertThrows(IllegalArgumentException.class,()->ExternalBrowserGuard.check(inputs,root,false,false));
        inputs.put("KNODE_IT_BROWSER_FRONTEND_REVISION","a".repeat(40));
        inputs.put("KNODE_IT_BROWSER_NODE","node.exe");
        assertThrows(IllegalArgumentException.class,()->ExternalBrowserGuard.check(inputs,root,false,false));
        inputs.remove("KNODE_IT_BROWSER_EDGE");
        assertThrows(IllegalArgumentException.class,()->ExternalBrowserGuard.check(inputs,root,false,false));
    }
}
