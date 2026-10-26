plugins {
    id("com.android.application") version "8.2.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
}

// Proiectul sta in OneDrive, care blocheaza fisierele din build/ in timpul sincronizarii.
// Build-ul se face in %TEMP%, in afara OneDrive.
allprojects {
    layout.buildDirectory.set(file("${System.getProperty("java.io.tmpdir")}/itsmartclient-build/${project.name}"))
}
