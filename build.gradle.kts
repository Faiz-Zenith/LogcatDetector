plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.22"
    id("org.jetbrains.intellij.platform") version "2.1.0"
}

group = "id.ac.umj"
version = "1.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
        marketplace()
        intellijDependencies()
    }
}

dependencies {
    intellijPlatform {
        // Menggunakan IntelliJ IDEA Community 2024.1 sebagai basis SDK kompilasi yang stabil
        intellijIdeaCommunity("2024.1")

        instrumentationTools()

        // Memuat modul pendukung Java bawaan IDE
        bundledPlugin("com.intellij.java")

        // PERBAIKAN UTAMA: Mengunduh ekstensi Android (ddmlib) yang cocok dengan versi 2024.1 dari Marketplace
        plugin("org.jetbrains.android:241.14494.240")
    }

    // Pustaka Gson diletakkan di dependency proyek utama agar dikemas ke dalam plugin jadinya
    implementation("com.google.code.gson:gson:2.10.1")
}

tasks {
    patchPluginXml {
        // Mendukung Android Studio/IntelliJ versi 2023.1 ke atas
        sinceBuild.set("231")
        // Dikosongkan agar plugin Anda ke depannya bisa terus diinstal di Android Studio versi terbaru
        untilBuild.set("")
    }

    buildSearchableOptions {
        enabled = false
    }

    // Memastikan target kompilasi menggunakan spesifikasi Java 17 standar Android Studio modern
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }

    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "17"
    }

    runIde {
        maxHeapSize = "2g"
    }
}
