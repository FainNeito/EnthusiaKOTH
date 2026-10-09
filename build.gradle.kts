plugins {
    kotlin("jvm") version "2.1.0"
    id("com.gradleup.shadow") version "8.3.6"
}

group = "net.badgersmc.ek"
version = "0.3.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
    maven("https://jitpack.io")
    maven("https://maven.enginehub.org/repo/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")

    implementation("com.github.BadgersMC.Nexus:nexus-core:v2.1.1")
    implementation("com.github.BadgersMC.Nexus:nexus-i18n:v2.1.1")
    implementation("com.github.BadgersMC.Nexus:nexus-paper-loader:v2.1.1")

    // LumaGuilds is provided by Paper join-classpath. A checked-in compile shim
    // mirrors only its public ServicesManager API and is excluded from shadowJar.
    compileOnly("com.github.MilkBowl:VaultAPI:1.7.1") {
        exclude(group = "org.bukkit", module = "bukkit")
    }
    compileOnly("me.clip:placeholderapi:2.11.6")
    compileOnly("com.sk89q.worldguard:worldguard-bukkit:7.0.14")

    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("org.xerial:sqlite-jdbc:3.45.1.0")
    implementation("org.slf4j:slf4j-nop:2.0.13")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("io.mockk:mockk:1.13.13")
    testImplementation("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    testImplementation("com.github.MilkBowl:VaultAPI:1.7.1") {
        exclude(group = "org.bukkit", module = "bukkit")
    }
}

kotlin {
    jvmToolchain(21)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

tasks.jar {
    archiveBaseName.set("EnthusiaKOTH")
}

tasks.shadowJar {
    archiveBaseName.set("EnthusiaKOTH")
    mergeServiceFiles()
    exclude("net/lumalyte/lg/api/**")
    exclude("net/enthusia/loreitems/api/**")
    exclude("org/enthusia/tags/TagService.class")
    exclude("io/github/badgersmc/advancements/pilot/**")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.test {
    useJUnitPlatform()
    environment("ENTHUSIA_GUILD_API_CONTRACT", "0")
    environment("ENTHUSIA_LORE_API_CONTRACT", "0")
}

tasks.register<Test>("actualLoreApiTest") {
    group = "verification"
    description = "Verify read-only readiness against the real LoreItems provider API"
    useJUnitPlatform()
    val providerJar = providers.environmentVariable("ENTHUSIA_LORE_API_JAR").orElse("")
    inputs.property("loreApiJarPath", providerJar)
    inputs.files(providerJar.map { path -> if (path.isBlank()) files() else files(path) })
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = files(providerJar) + sourceSets.test.get().runtimeClasspath
    environment("ENTHUSIA_LORE_API_CONTRACT", "1")
    environment("ENTHUSIA_LORE_API_JAR", providerJar.get())
    filter {
        includeTestsMatching("net.badgersmc.ek.infrastructure.bukkit.ActualLoreApiContractTest")
        includeTestsMatching("net.badgersmc.ek.infrastructure.bukkit.LoreDefinitionReadinessTest")
    }
    doFirst {
        require(providerJar.get().isNotBlank() && file(providerJar.get()).isFile) {
            "ENTHUSIA_LORE_API_JAR must name a real LoreItems JAR"
        }
    }
}

// Run the consumer against the supplied real provider, ahead of the compile shim.
// A separate task keeps this verification distinct from ordinary unit tests.
tasks.register<Test>("actualGuildApiTest") {
    group = "verification"
    description = "Verify alliance and bank adapters against a real LumaGuilds JAR"
    useJUnitPlatform()
    val providerJar = providers.environmentVariable("ENTHUSIA_GUILD_API_JAR").orElse("")
    inputs.property("guildApiJarPath", providerJar)
    inputs.files(providerJar.map { path -> if (path.isBlank()) files() else files(path) })
        .withPropertyName("guildApiJarContents")
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = files(providerJar) + sourceSets.test.get().runtimeClasspath
    javaLauncher = javaToolchains.launcherFor {
        languageVersion = JavaLanguageVersion.of(25)
    }
    environment("ENTHUSIA_GUILD_API_CONTRACT", "1")
    environment("ENTHUSIA_GUILD_API_JAR", providerJar.get())
    filter {
        includeTestsMatching("net.badgersmc.ek.application.ActualGuildApiContractTest")
        includeTestsMatching("net.badgersmc.ek.application.AllianceAdapterCompatibilityTest")
        includeTestsMatching("net.badgersmc.ek.infrastructure.lumaguilds.LumaGuildsBank*")
        includeTestsMatching("net.badgersmc.ek.application.ProtectedRewardsIntegrationTest")
    }
    doFirst {
        require(providerJar.get().isNotBlank() && file(providerJar.get()).isFile) {
            "ENTHUSIA_GUILD_API_JAR must name a real LumaGuilds JAR"
        }
    }
}
