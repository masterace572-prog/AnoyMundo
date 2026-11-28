# CCore Virtualization Engine

[![JitPack](https://jitpack.io/v/cold4u/CCore.svg)](https://jitpack.io/#cold4u/CCore)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

CCore is an advanced, high-performance Android user-space application virtualization and containerization framework upgraded from the BlackBox baseline. It enables executing Android applications inside an isolated virtual container with support for modern Android platforms (Android 5.0 through Android 14+).

---

## Key Features

- **Multi-Process Sandbox Pool**: Manages an isolated pool of 50 pre-allocated virtual processes (`:p0` to `:p49`) with 5-second PID recycling quarantine.
- **Trie-Based VFS Redirection**: Fast prefix-matching path redirection mapping private app directories, external OBB/storage, and virtual `/proc/self/cmdline`.
- **Google Mobile Services (GMS) Layer**: Built-in Google Sign-In and Play Games account authentication support with OAuth2 intent identifier rewriting.
- **In-Memory Linker & Hidden API Detours**: Native ARM64/x86 symbol resolving via `/proc/self/maps` and runtime hidden API policy uncapping.
- **Self-Healing Hook Watchdog**: Active verification watchdog that detects unwound or stripped Binder proxies and re-injects detours automatically.
- **Multi-Android Framework Compatibility**: Extended service proxy layer covering Android 10 (Device Identifiers), Android 11 (Activity Task Manager), Android 12 (Activity Client), and Android 14 (Play Integrity rule suppression).

---

## Download & Integration

### Option 1: JitPack Dependency

Add the JitPack repository to your root `settings.gradle` or root `build.gradle`:

```groovy
// settings.gradle (dependencyResolutionManagement) or root build.gradle
allprojects {
    repositories {
        google()
        mavenCentral()
        maven { url 'https://jitpack.io' }
    }
}
```

Add the CCore dependency to your `app/build.gradle`:

```groovy
dependencies {
    implementation 'com.github.cold4u:CCore:v1.0.0'
}
```

---

### Option 2: Local Module Integration

Clone the repository and link `:Bcore` into your project:

```groovy
// settings.gradle
include ':Bcore'
project(':Bcore').projectDir = new File(rootDir, 'CCore/Bcore')
```

```groovy
// app/build.gradle
dependencies {
    implementation project(':Bcore')
}
```

---

## Quick Start

### 1. Initialize CCore in Application

```java
package com.example.app;

import android.app.Application;
import android.content.Context;
import com.ccore.CCoreCore;
import com.ccore.app.configuration.ClientConfiguration;

public class App extends Application {
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        try {
            CCoreCore.get().doAttachBaseContext(base, new ClientConfiguration() {
                @Override
                public String getHostPackageName() {
                    return getPackageName();
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        CCoreCore.get().doCreate();
    }
}
```

### 2. Install & Launch Virtual Applications

```java
// Install from host package into virtual user 0
CCoreCore.get().installPackageAsUser("com.example.target", 0);

// Or install from an APK file
CCoreCore.get().installPackageAsUser(new File("/sdcard/target.apk"), 0);

// Launch virtual app
CCoreCore.get().launchApk("com.example.target", 0);

// Enable GMS support for user 0
CCoreCore.get().installGms(0);
```

---

## License

This project is licensed under the Apache 2.0 License.
