# Copyright (c) 2026 Starbright Lab.
#
# This source code is licensed under the MIT license found in the
# LICENSE file in the root directory of this source tree.

# Consumer rules: applied to whatever app bundles this module, so the JNI contract survives R8 in
# ANY host — not just this repo's shell. These used to live only in app/proguard-rules.pro, which
# does not travel when the module is copied into Immortal; a minified host would then load the .so
# fine and get a null jmethodID for every native->Java callback (black screen, no audio).
#
# The native side resolves these by NAME via GetMethodID (see cpp/android_raop_callbacks.c and
# cpp/log_sink.h), so renaming or stripping any of them breaks the bridge silently.

# Callback interfaces invoked from native code, and their implementors (AirPlayService).
-keep class io.github.jqssun.airplay.bridge.RaopCallbackHandler { *; }
-keep class * implements io.github.jqssun.airplay.bridge.RaopCallbackHandler { *; }
-keep class io.github.jqssun.airplay.bridge.LogListener { *; }
-keep class * implements io.github.jqssun.airplay.bridge.LogListener { *; }

# The JNI surface itself: method names must match the Java_io_github_jqssun_airplay_bridge_
# NativeBridge_* symbols exported by native_bridge.cpp.
-keep class io.github.jqssun.airplay.bridge.NativeBridge { *; }
