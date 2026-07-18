plugins {
    kotlin("jvm") version "2.4.0"
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
    // PlaceholderAPI
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
    // Floodgate/Cumulus (Bedrock forms)
    maven("https://repo.opencollab.dev/main/")
}

dependencies {
    // --- Server API + provided plugins: compile-only ---
    compileOnly("com.destroystokyo.paper:paper-api:1.16.5-R0.1-SNAPSHOT")
    compileOnly("com.github.retrooper:packetevents-spigot:2.5.0")

    // --- Soft-depend currency APIs: compile against, provided at runtime by the actual plugins ---
    compileOnly("org.black_ixx:playerpoints:3.2.6")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7") { exclude(group = "org.bukkit") }
    compileOnly("me.clip:placeholderapi:2.11.6")
    testImplementation("me.clip:placeholderapi:2.11.6")
    // Floodgate API (brings Cumulus transitively) for Bedrock forms; provided at runtime by the Floodgate plugin.
    compileOnly("org.geysermc.floodgate:api:2.2.4-SNAPSHOT")

    // --- Shaded + relocated (not on Maven Central, so the libraries: loader can't fetch them) ---
    implementation("com.tcoded:FoliaLib:0.5.1")                 // Folia-safe scheduler
    implementation("net.wesjd:anvilgui:1.10.11-SNAPSHOT")         // anvil text input

    // --- Downloaded at runtime via plugin.yml `libraries:` (Maven Central). compileOnly = compile, not bundled. ---
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib:2.4.0")
    compileOnly("com.github.cryptomorin:XSeries:13.7.0")
    compileOnly("com.google.code.gson:gson:2.14.0")
    compileOnly("com.zaxxer:HikariCP:7.0.2")
    compileOnly("org.xerial:sqlite-jdbc:3.53.2.0")
    compileOnly("com.mysql:mysql-connector-j:9.7.0")

    // --- Unit tests: JUnit 5 + a real SQLite-backed pool for the migration runner ---
    // stdlib is provided at runtime via libraries: for the plugin, but tests run in a plain JVM.
    testImplementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("com.zaxxer:HikariCP:7.0.2")
    testImplementation("org.xerial:sqlite-jdbc:3.53.2.0")
    // Gson is provided at runtime via libraries: for the plugin; tests exercise the card adapters directly.
    testImplementation("com.google.code.gson:gson:2.14.0")
    // paper-api on the test classpath so the typed config holders (which reference Bukkit types) load.
    testImplementation("com.destroystokyo.paper:paper-api:1.16.5-R0.1-SNAPSHOT")
}

kotlin {
    jvmToolchain(21)
}

tasks {
    build {
        dependsOn(shadowJar)
    }

    jar {
        // Deterministic output: no build timestamps, stable entry order → identical checksum per source.
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }

    shadowJar {
        // Only the two non-Central libs are bundled; relocate to avoid conflicts.
        val libBase = "net.kingmc.plugin.kingmcdonate.lib"
        relocate("com.tcoded.folialib", "$libBase.folialib")
        relocate("net.wesjd.anvilgui", "$libBase.anvilgui")
        // Deterministic output: no build timestamps, stable entry order → identical checksum per source.
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
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
