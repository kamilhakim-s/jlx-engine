// Root build script. Shared configuration applied to every subproject so each
// module stays minimal. We pin a Java 21 toolchain everywhere — the whole project
// targets one LTS so behaviour (GC, JIT, intrinsics) is reproducible.

subprojects {
    apply(plugin = "java")

    repositories {
        mavenCentral()
    }

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        // -parameters keeps parameter names at runtime; helps tooling/debugging.
        options.compilerArgs.add("-parameters")
    }
}
