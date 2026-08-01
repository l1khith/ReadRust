#!/bin/bash
set -e

# Install targets
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android 2>/dev/null || true
cargo install uniffi-bindgen cargo-ndk 2>/dev/null || true

# Generate Kotlin bindings
cd rust/pdfium_bridge
# Run via cargo because the binary isn't installed standalone
cargo run --bin uniffi-bindgen generate src/pdfium_bridge.udl --language kotlin --out-dir ../../app/src/main/java/
cd ../..

# Build Rust for all ABIs
cd rust/pdfium_bridge
cargo ndk -t arm64-v8a -t x86_64 \
    -o ../../app/src/main/jniLibs \
    build --release
cd ../..

echo "Build complete. libpdfium_bridge.so generated for all ABIs."
