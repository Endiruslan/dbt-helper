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
    implementation(libs.coroutines.core)

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
            untilBuild = providers.gradleProperty("untilBuild")
        }
    }
}

tasks {
    wrapper {
        gradleVersion = "8.12"
    }
}
