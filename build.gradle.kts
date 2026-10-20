import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.4.21"
    id("net.fabricmc.fabric-loom") version "1.19.1"
    id("maven-publish")
}

// Tagged pipelines publish a jar named after the tag; everything else uses the
// version in gradle.properties.
version = System.getenv("CI_COMMIT_TAG")?.removePrefix("v")
    ?: project.property("mod_version") as String
group = project.property("maven_group") as String

base {
    archivesName.set(project.property("archives_base_name") as String)
}

val targetJavaVersion = 25
java {
    toolchain.languageVersion = JavaLanguageVersion.of(targetJavaVersion)
    // Loom will automatically attach sourcesJar to a RemapSourcesJar task and to the "build" task
    // if it is present.
    // If you remove this line, sources will not be generated.
    withSourcesJar()
}

loom {
    splitEnvironmentSourceSets()

    runs {
        named("client") {
            // The dev client runs an offline account (name-based UUID), whose
            // profile is looked up by name. To see a real player's capes and
            // cosmetics on yourself, run the dev client under that name:
            //   ./gradlew runClient -PdevUsername=SomeName
            // Offline accounts cannot sign in, so linking and the wardrobe
            // need a real Microsoft account.

            val customName = System.getenv("DEV_MC_USERNAME")
                ?: (project.findProperty("devUsername") as String?)

            customName?.let {
                programArgs("--username", it)
            }
        }
    }

    mods {
        register("leicraftclient") {
            sourceSet("main")
            sourceSet("client")
        }
    }
}


repositories {
    mavenCentral()
    // Add repositories to retrieve artifacts from in here.
    // You should only use this when depending on other mods because
    // Loom adds the essential maven repositories to download Minecraft and libraries from automatically.
    // See https://docs.gradle.org/current/userguide/declaring_repositories.html
    // for more information about repositories.
}

dependencies {
    // To change the versions see the gradle.properties file
    //
    // Minecraft 26.3 ships unobfuscated, so there is no `mappings` dependency and
    // nothing needs remapping -- mod dependencies use plain `implementation`.
    // See https://github.com/FabricMC/fabric-example-mod/tree/26.3
    minecraft("com.mojang:minecraft:${project.property("minecraft_version")}")
    implementation("net.fabricmc:fabric-loader:${project.property("loader_version")}")
    implementation("net.fabricmc:fabric-language-kotlin:${project.property("kotlin_loader_version")}")

    implementation("net.fabricmc.fabric-api:fabric-api:${project.property("fabric_version")}")

    // The CPM parser is pure logic with no Minecraft types, so it can be unit
    // tested on a plain JVM. It lives in the client source set, which the test
    // source set does not see by default.
    testImplementation(kotlin("test"))
    testImplementation(sourceSets["client"].output)
    testImplementation(sourceSets["client"].compileClasspath)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
}

tasks.processResources {
    inputs.property("version", project.version)
    inputs.property("minecraft_version", project.property("minecraft_version"))
    inputs.property("loader_version", project.property("loader_version"))
    filteringCharset = "UTF-8"

    filesMatching("fabric.mod.json") {
        expand(
            "version" to project.version,
            "minecraft_version" to project.property("minecraft_version")!!,
            "loader_version" to project.property("loader_version")!!,
            "kotlin_loader_version" to project.property("kotlin_loader_version")!!
        )
    }
}

tasks.withType<JavaCompile>().configureEach {
    // ensure that the encoding is set to UTF-8, no matter what the system default is
    // this fixes some edge cases with special characters not displaying correctly
    // see http://yodaconditions.net/blog/fix-for-java-file-encoding-problems-with-gradle.html
    // If Javadoc is generated, this must be specified in that task too.
    options.encoding = "UTF-8"
    options.release.set(targetJavaVersion)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.fromTarget(targetJavaVersion.toString()))
}

tasks.jar {
    from("LICENSE.txt") {
        rename { "${it}_${project.base.archivesName.get()}" }
    }
}

// configure the maven publication
publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            artifactId = project.property("archives_base_name") as String
            from(components["java"])
        }
    }

    // See https://docs.gradle.org/current/userguide/publishing_maven.html for information on how to set up publishing.
    repositories {
        // Add repositories to publish to here.
        // Notice: This block does NOT have the same function as the block in the top level.
        // The repositories here will be used for publishing your artifact, not for
        // retrieving dependencies.
    }
}
