# Shipped inside the AAR as proguard.txt; applied to the host app's R8 build.

# JNI resolves Java_com_akular_rayneo_slam_SlamShim_* by name.
-keep class com.akular.rayneo.slam.SlamShim { native <methods>; }

# The RayNeo client loads its runtime reflectively; keep it intact.
-keep class com.rayneo.xr.** { *; }
-dontwarn com.rayneo.xr.**
