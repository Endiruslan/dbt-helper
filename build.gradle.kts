import java.security.MessageDigest

plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.intellijPlatform)
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    jvmToolchain(providers.gradleProperty("javaVersion").get().toInt())
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation(libs.jackson.databind)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.snakeyaml)
    // Provided by the IntelliJ Platform at runtime — must NOT be bundled, or its
    // kotlinx.coroutines conflicts with the platform's patched copy (on 2026.2 that
    // surfaces as "NoClassDefFoundError: ...CoroutineExceptionHandlerImplKt").
    compileOnly(libs.coroutines.core)

    intellijPlatform {
        val type = providers.gradleProperty("platformType").get()
        val version = providers.gradleProperty("platformVersion").get()
        create(type, version)
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "com.dbthelper"
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = providers.gradleProperty("sinceBuild")
            untilBuild = providers.gradleProperty("untilBuild").map { it.ifEmpty { null } }
        }
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}

tasks {
    wrapper {
        gradleVersion = "8.14.3"
    }
}

// Pin the vendored dagre bundle to the published @dagrejs/dagre@1.1.8 release, so the opaque
// minified file cannot drift or be swapped unnoticed. Runs as part of `check`.
// (dagre.min.js is marked `binary` in .gitattributes so its bytes stay identical to npm.)
val verifyDagreBundle by tasks.registering {
    val bundle = layout.projectDirectory.file("src/main/resources/js/dagre.min.js")
    val expected = "c35b8d6f410ce7bdd302fc00cad27331184e52b2e738609e55ae3bbb4fb4849f"
    inputs.file(bundle)
    doLast {
        val actual = MessageDigest.getInstance("SHA-256")
            .digest(bundle.asFile.readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        require(actual == expected) {
            "dagre.min.js does not match @dagrejs/dagre@1.1.8:\n  expected $expected\n  actual   $actual"
        }
    }
}
tasks.named("check") { dependsOn(verifyDagreBundle) }
