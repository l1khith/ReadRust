fn main() {
    let target = std::env::var("TARGET").unwrap_or_default();
    let abi = match target.as_str() {
        "aarch64-linux-android" => "arm64-v8a",
        "armv7-linux-androideabi" => "armeabi-v7a",
        "x86_64-linux-android" => "x86_64",
        "i686-linux-android" => "x86",
        _ => "arm64-v8a",
    };
    let manifest = std::path::PathBuf::from(std::env::var("CARGO_MANIFEST_DIR").unwrap());
    let jni_libs = manifest.join("../../app/src/main/jniLibs").join(abi);
    println!("cargo:rustc-link-search=native={}", jni_libs.display());
    println!("cargo:rustc-link-lib=dylib=pdfium");
    println!("cargo:rustc-link-lib=dylib=jnigraphics");
}