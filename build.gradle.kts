plugins {
    id("java")
}

group = "com.lunarclient"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    mavenLocal()

    maven {
        name = "codemc-releases"
        url = uri("https://repo.codemc.io/repository/maven-releases/")
    }

    maven {
        name = "codemc-snapshots"
        url = uri("https://repo.codemc.io/repository/maven-snapshots/")
    }

    maven {
        name = "lunarclient"
        url = uri("https://repo.lunarclient.dev")
    }
}

dependencies {
    compileOnly("org.spigotmc:spigot:1.8.8-R0.1-SNAPSHOT")
    compileOnly("com.lunarclient:apollo-api:1.2.2-SNAPSHOT")
    compileOnly("com.lunarclient:apollo-common:1.2.2-SNAPSHOT")
    compileOnly("com.github.retrooper:packetevents-spigot:2.11.1")
}