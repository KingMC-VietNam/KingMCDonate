plugins {
    kotlin("jvm") version "2.4.0-Beta2"
    id("com.gradleup.shadow") version "9.4.1"
    id("xyz.jpenilla.run-paper") version "3.0.2"
}

repositories {
    mavenCentral()
    // Paper API (baseline 1.16.5) + bungeecord-chat transitive
    maven("https://repo.papermc.io/repository/maven-public/")
    // PacketEvents + AnvilGUI
    maven("https://repo.codemc.io/repository/maven-public/")
    // FoliaLib
    maven("https://repo.tcoded.com/releases")
    // PlayerPoints (Rosewood)
    maven("https://repo.rosewooddev.io/repository/public/")
    // XSeries fallback + VaultAPI
    maven("https://jitpack.io")
}

dependencies {
    // --- Server API + provided plugins: compile-only ---
    compileOnly("com.destroystokyo.paper:paper-api:1.16.5-R0.1-SNAPSHOT")
    compileOnly("com.github.retrooper:packetevents-spigot:2.5.0")

    // --- Soft-depend currency APIs: compile against, provided at runtime by the actual plugins ---
    compileOnly("org.black_ixx:playerpoints:3.2.6")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7") { exclude(group = "org.bukkit") }

    // --- Shaded + relocated (not on Maven Central, so the libraries: loader can't fetch them) ---
    implementation("com.tcoded:FoliaLib:0.5.1")                 // Folia-safe scheduler
    implementation("net.wesjd:anvilgui:1.9.6-SNAPSHOT")         // anvil text input

    // --- Downloaded at runtime via plugin.yml `libraries:` (Maven Central). compileOnly = compile, not bundled. ---
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib:2.4.0-Beta2")
    compileOnly("com.github.cryptomorin:XSeries:11.3.0")
    compileOnly("com.google.code.gson:gson:2.10.1")
    compileOnly("com.google.zxing:core:3.5.1")
    compileOnly("com.zaxxer:HikariCP:5.1.0")
    compileOnly("org.xerial:sqlite-jdbc:3.45.1.0")
    compileOnly("com.mysql:mysql-connector-j:8.0.33")

    // --- Unit tests: JUnit 5 + a real SQLite-backed pool for the migration runner ---
    // stdlib is provided at runtime via libraries: for the plugin, but tests run in a plain JVM.
    testImplementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.0-Beta2")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("com.zaxxer:HikariCP:5.1.0")
    testImplementation("org.xerial:sqlite-jdbc:3.45.1.0")
}

kotlin {
    jvmToolchain(21)
}

tasks {
    build {
        dependsOn(shadowJar)
    }

    shadowJar {
        // Only the two non-Central libs are bundled; relocate to avoid conflicts.
        val libBase = "net.kingmc.plugin.kingmcdonate.lib"
        relocate("com.tcoded.folialib", "$libBase.folialib")
        relocate("net.wesjd.anvilgui", "$libBase.anvilgui")
    }

    runServer {
        minecraftVersion("1.20.4")
        jvmArgs("-Xms2G", "-Xmx2G")
    }

    test {
        useJUnitPlatform()
    }

    processResources {
        val props = mapOf("version" to version)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }
}
