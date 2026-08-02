#!/bin/bash
set -e

# Install targets & cargo-ndk
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android 2>/dev/null || true
cargo install cargo-ndk 2>/dev/null || true

# Build Rust for all ABIs
cd rust/pdfium_bridge
cargo ndk -t arm64-v8a -t x86_64 \
    -o ../../app/src/main/jniLibs \
    build --release
cd ../..

echo "Build complete. libpdfium_bridge.so generated for all ABIs."