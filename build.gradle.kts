plugins {
    id("fabric-loom") version "1.16-SNAPSHOT"
    id("java")
}

base {
    archivesName = project.property("archives_base_name") as String
}

version = project.property("mod_version") as String
group = project.property("maven_group") as String

repositories {
    mavenCentral()
}

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    mappings("net.fabricmc:yarn:${property("yarn_mappings")}:v2")
    modImplementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_version")}")

    // baritone: 编译期经 loom remap 成 yarn 名; 运行时由下方 jar 任务内置进 mod (免外部依赖)
    modImplementation(files("libs/1.21.11-baritone.jar"))

    // pinyin4j: 物品名拼音模糊搜索 (直接打进 mod jar, 无外部依赖)
    implementation(files("libs/pinyin4j-2.5.1.jar"))
}

// baritone 以 jar-in-jar 方式内置: 保持 baritone-meteor 独立 mod 身份 (mixin 环境与单独安装一致),
// 嵌套在 autocraft 内, 外部无需再装 baritone
tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from("libs/1.21.11-baritone.jar") {
        into("META-INF/jars")
    }
    // pinyin4j 类文件并入最终 mod jar (fabric loader 直接从根目录加载)
    from(zipTree("libs/pinyin4j-2.5.1.jar")) {
        exclude("META-INF/**")
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.withType(JavaCompile::class).configureEach {
    options.encoding = "UTF-8"
    options.release = 21
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}
