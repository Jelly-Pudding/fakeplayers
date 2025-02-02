plugins {
    `java-library`
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.14"
}

group = "com.jellypudding"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    paperweight.paperDevBundle("1.21.4-R0.1-SNAPSHOT")
    implementation("com.google.code.gson:gson:2.12.1")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

// Use Mojang mappings since we're targeting Paper only
paperweight.reobfArtifactConfiguration = io.papermc.paperweight.userdev.ReobfArtifactConfiguration.MOJANG_PRODUCTION