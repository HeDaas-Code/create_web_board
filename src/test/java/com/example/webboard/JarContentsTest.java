package com.example.webboard;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JarContentsTest — assertion that the built mod jar includes every class the server
 * needs at runtime. Issue #5 (NoClassDefFoundError on first run) was caused by Javalin
 * being on `implementation` configuration, which is dev-time only; the fix was to wrap
 * the dep in `jarJar(implementation(...))` so the dep's class files actually land in
 * the shipped jar. This test prevents that regression.
 *
 * <p>Build flow: the test reads {@code build/libs/create_web_board-1.21.1-*.jar}.
 * Run {@code gradle test} after {@code gradle build} (the standard CI order). The test
 * is skipped if the jar is absent (e.g. running only {@code compileTestJava}).
 */
class JarContentsTest {

    private static final String EXPECTED_CLASS = "io/javalin/websocket/WsContext.class";
    private static final String JAR_GLOB = "build/libs/create_web_board-1.21.1-*.jar";

    @Test
    void shippedJar_containsJavalinWsContext() throws IOException {
        Path jar = findBuiltJar();
        if (jar == null) {
            // gradle test was run without gradle build first — skip the assertion.
            System.err.println("[skip] " + JAR_GLOB + " not found; run `gradle build` first.");
            return;
        }
        try (JarFile jf = new JarFile(jar.toFile())) {
            // Walk both the top-level entries and any jar-in-jar under META-INF/jarjar/.
            assertTrue(containsRecursively(jf, EXPECTED_CLASS),
                    "shipped jar does not contain " + EXPECTED_CLASS
                            + " — this regresses issue #5 (NoClassDefFoundError at runtime). "
                            + "Wrap io.javalin:javalin in jarJar(implementation(...)) in build.gradle.");
        }
    }

    @Test
    void shippedJar_containsJettyServer() throws IOException {
        Path jar = findBuiltJar();
        if (jar == null) return;
        try (JarFile jf = new JarFile(jar.toFile())) {
            assertTrue(containsRecursively(jf, "org/eclipse/jetty/server/Server.class"),
                    "shipped jar missing jetty-server/Server.class — "
                            + "moddev's jarJar is root-only; explicit each jetty-* transitive. See build.gradle.");
        }
    }

    @Test
    void shippedJar_isLoadable() throws Exception {
        // Stronger check: the shipped jar, as a classpath entry, can resolve the Javalin
        // type via reflection. Catches "class is in the jar but the classloader wiring is
        // broken" failures that the simple contains check would miss.
        Path jar = findBuiltJar();
        if (jar == null) return;
        try (URLClassLoader cl = new URLClassLoader(
                new URL[]{jar.toUri().toURL()},
                JarContentsTest.class.getClassLoader())) {
            Class<?> wsCtx = cl.loadClass("io.javalin.websocket.WsContext");
            assertNotNull(wsCtx);
            Class<?> server = cl.loadClass("org.eclipse.jetty.server.Server");
            assertNotNull(server);
        }
    }

    @Test
    void shippedJar_containsKotlinStdlib() throws IOException {
        // Regression guard for issue #6: Javalin's module-info.class declares
        // `requires kotlin.stdlib` (transitive). Without kotlin-stdlib on the
        // module path at runtime, JVM startup dies with
        //   java.lang.module.FindException: Module kotlin.stdlib not found,
        //                                  required by io.javalin
        // kotlin.Unit is the canonical "stdlib is present" probe — it's in the
        // top-level kotlin package, present in every stdlib version since 1.0.
        Path jar = findBuiltJar();
        if (jar == null) return;
        try (JarFile jf = new JarFile(jar.toFile())) {
            assertTrue(containsRecursively(jf, "kotlin/Unit.class"),
                    "shipped jar missing kotlin/Unit.class — "
                            + "this regresses issue #6 (FindException: kotlin.stdlib). "
                            + "Add jarJar(implementation(\"org.jetbrains.kotlin:kotlin-stdlib:\" + kotlin_version)) "
                            + "to build.gradle.");
        }
    }

    // ---- helpers ----

    private static Path findBuiltJar() throws IOException {
        Path libs = Path.of("build", "libs");
        if (!Files.isDirectory(libs)) return null;
        try (var stream = Files.list(libs)) {
            return stream
                    .filter(p -> p.getFileName().toString().matches("create_web_board-1\\.21\\.1-.*\\.jar"))
                    .findFirst()
                    .orElse(null);
        }
    }

    private static boolean containsRecursively(JarFile jf, String classPath) throws IOException {
        Enumeration<JarEntry> entries = jf.entries();
        while (entries.hasMoreElements()) {
            JarEntry e = entries.nextElement();
            if (e.getName().equals(classPath)) return true;
            if (e.getName().endsWith(".jar") && e.getName().startsWith("META-INF/jarjar/")) {
                // The nested jar — its bytes are inline in the outer jar, but JarFile won't
                // auto-navigate. Open the nested jar as a separate JarFile to scan it.
                try (var nested = jf.getInputStream(e);
                     var nestedJf = new JarFile(copyToTemp(nested).toFile())) {
                    if (containsRecursively(nestedJf, classPath)) return true;
                }
            }
        }
        return false;
    }

    private static Path copyToTemp(java.io.InputStream in) throws IOException {
        Path tmp = Files.createTempFile("nested-jar-", ".jar");
        Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        tmp.toFile().deleteOnExit();
        return tmp;
    }
}
