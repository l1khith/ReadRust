// =============================================================================
// ReadRust — jni_adapter/audio_bridge.rs
// JNI export for WAV file stitching adhering strictly to pro_rust_patterns_guide.md
// =============================================================================

use crate::audio_engine::stitcher::stitch_wav_files;
use jni::objects::{JObjectArray, JString};
use jni::sys::{jboolean, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv as JNIEnvStruct;
use std::path::Path;

#[no_mangle]
pub unsafe extern "system" fn Java_com_l1khith_readrust_tts_NativeAudioBridge_nativeStitchWavFiles(
    mut env: JNIEnvStruct,
    _class: jni::sys::jobject,
    input_paths: JObjectArray,
    output_path: JString,
) -> jboolean {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let out_str: String = match env.get_string(&output_path) {
            Ok(s) => s.into(),
            Err(_) => return JNI_FALSE,
        };

        // SAFETY:
        // 1. `input_paths` is guaranteed non-null by JNI framework during call.
        // 2. Length check bounds iteration to valid array element indices.
        let array_len = match unsafe { env.get_array_length(&input_paths) } {
            Ok(len) => len as usize,
            Err(_) => return JNI_FALSE,
        };

        let mut paths: Vec<String> = Vec::with_capacity(array_len);

        for i in 0..array_len {
            // SAFETY: `i` is strictly within range `0..array_len`.
            let obj = match unsafe { env.get_object_array_element(&input_paths, i as jni::sys::jsize) } {
                Ok(o) => o,
                Err(_) => return JNI_FALSE,
            };

            let path_jstr: JString = obj.into();
            let path_str: String = match env.get_string(&path_jstr) {
                Ok(s) => s.into(),
                Err(_) => return JNI_FALSE,
            };
            paths.push(path_str);
        }

        let path_refs: Vec<&Path> = paths.iter().map(|p| Path::new(p)).collect();
        let out_path = Path::new(&out_str);

        match stitch_wav_files(&path_refs, out_path, 22050) {
            Ok(_) => JNI_TRUE,
            Err(e) => {
                log::error!("nativeStitchWavFiles failed: {}", e);
                JNI_FALSE
            }
        }
    }));

    result.unwrap_or(JNI_FALSE)
}
