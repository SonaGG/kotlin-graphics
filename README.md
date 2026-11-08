# kotlin-graphics

Kotlin Multiplatform bindings for Vulkan and Metal.

| Module    | Contents                                                      |
|-----------|---------------------------------------------------------------|
| `runtime` | Native memory, a memory stack and native library loading      |
| `vulkan`  | Vulkan 1.4, generated from `registry/vk.xml`                  |
| `metal`   | Metal, QuartzCore and Core Graphics, generated for Apple      |
| `vma`     | A Vulkan memory allocator                                     |

Targets: JVM (FFM), Windows x64, Linux x64 and arm64, macOS x64 and arm64.

```kotlin
memoryStack {
    Vulkan.load()
    val instance = Vulkan.createInstance {
        applicationInfo { apiVersion = VkApiVersion.V1_3 }
    }
    instance.enumeratePhysicalDevices().forEach { println(it.getProperties().deviceName) }
    instance.destroy()
}
```

Build and test with `./gradlew build`. The license is Apache 2.0 (see `LICENSE`).
