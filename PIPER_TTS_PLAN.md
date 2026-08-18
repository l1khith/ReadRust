# ReadRust — PiperTTS Integration Technical Specification

**Document Version:** 1.0  
**Date:** 2026-08-16  
**Repository:** `https://github.com/l1khith/ReadRust`  
**Scope:** Android PDF-to-Audio Pipeline via PiperTTS (ONNX)  

---

## 1. Current Architecture Audit

### 1.1 Existing Rust Layer (`rust/pdfium_bridge/`)
| Component | Technology | Notes |
|-----------|-----------|-------|
| FFI Bridge | `jni` crate (raw `#[no_mangle]`) | Direct JNI, no UniFFI code generation despite dependency |
| PDF Engine | `pdfium` (dynamic `libpdfium.so`) | Memory-mapped FD loading, page rendering, text extraction |
| Text Processing | Custom sentence extractor | Already produces JSON with bounding-box normalized coordinates |
| Build | `cargo-ndk` | Outputs `cdylib` to `app/src/main/jniLibs/{abi}/` |
| ABIs | `arm64-v8a`, `x86_64` | Matches your `build.gradle.kts` filters |

**Key JNI Exports (Existing):**
- `Java_com_l1khith_readrust_PdfiumBridge_nativeExtractText`
- `Java_com_l1khith_readrust_PdfiumBridge_nativeExtractSentencesJson`
- `Java_com_l1khith_readrust_PdfiumBridge_nativeRenderPage`

### 1.2 Existing Android Layer (Kotlin)
| File | Responsibility |
|------|---------------|
| `PdfiumBridge.kt` | JNI class loading `libpdfium_bridge.so` |
| `PdfHelper.kt` | High-level PDF operations, FD extraction |
| `ReaderService.kt` | Foreground service for read-aloud |
| `ReaderScreen.kt` | Compose reader UI with sentence highlighting |
| `BookEntity.kt` / `BookDao.kt` | Room persistence |
| `LibraryScreen.kt` | Book grid UI |

### 1.3 Critical Gaps for TTS
1. **No audio generation path** — Rust only extracts text/images, no PCM/WAV output.
2. **No model management** — Piper `.onnx` + `.json` voice models need asset bundling / download management.
3. **No progress streaming** — Current JNI is request/response; TTS requires streaming callbacks.
4. **No cancellation primitive** — Long-running synthesis needs cooperative cancellation.
5. **No audio encoder** — Need WAV writer in Rust.

---

## 2. Target Architecture

### 2.1 High-Level Pipeline
```
[PDF File] → (1) Extract & Chunk (Rust) → (2) Neural Synth (Piper-ONNX) 
                                                  ↓
[Kotlin UI] ← Progress / Cancel ← (3) Audio Stitch (hound) → [WAV File]
       ↑
[ForegroundService] ← [Notification Bar]
```

### 2.2 Layered Module Design

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         ANDROID APPLICATION (Kotlin)                        │
│  ┌─────────────┐  ┌──────────────┐  ┌──────────────┐  ┌─────────────────┐  │
│  │  TtsScreen  │  │TtsViewModel  │  │AudioExporter │  │ TtsRepository   │  │
│  │  (Compose)  │  │(StateFlow)   │  │  (Service)   │  │  (Room + JNI)   │  │
│  └──────┬──────┘  └──────┬───────┘  └──────┬───────┘  └────────┬────────┘  │
│         └──────────────────┴───────────────────┴───────────────────┘           │
│                              JNI Boundary                                      │
└────────────────────────────────────┬──────────────────────────────────────────┘
                                     ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                         RUST NATIVE ENGINE (cdylib)                         │
│                                                                             │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │ 1. JNI Adapter Layer (jni_adapter)                                    │  │
│  │    - `tts_bridge.rs`: New JNI exports with JObject callbacks          │  │
│  │    - `callback.rs`: JNIEnv progress & error callbacks                 │  │
│  └──────────────────────────────────┬────────────────────────────────────┘  │
│                                     ▼                                       │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │ 2. Domain / Pipeline Orchestrator (pipeline)                          │  │
│  │    - `audio_exporter.rs`: Owns cancel token, batching, thread pool    │  │
│  │    - `chunk_scheduler.rs`: Splits book into synthesis batches         │  │
│  └──────┬────────────────────┬────────────────────┬───────────────────────┘  │
│         ▼                    ▼                    ▼                           │
│  ┌──────────────┐   ┌──────────────┐   ┌──────────────────────────────┐    │
│  │ pdf_extractor│   │text_processor│   │     audio_engine             │    │
│  │ (existing)   │   │  (refined)   │   │  ┌────────────────────────┐  │    │
│  │              │   │ chunker.rs   │   │  │ piper_synth.rs         │  │    │
│  │ pdfium FFI   │   │ cleaner.rs   │   │  │   - piper-rs wrapper   │  │    │
│  │ text_extract │   │ normalizer.rs│   │  ├────────────────────────┤  │    │
│  │              │   │              │   │  │ wav_encoder.rs         │  │    │
│  └──────────────┘   └──────────────┘   │  │   - hound WAV writer   │  │    │
│                                         │  └────────────────────────┘  │    │
│                                         └──────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Implementation Roadmap

| Phase | Task | Effort | Deliverable |
|-------|------|--------|-------------|
| **P0** | Refactor Rust: move existing PDF code into `pdfium/` module | 2h | Clean `lib.rs` |
| **P0** | Add `audio_engine/` with `hound` WAV writing | 3h | PCM → WAV pipeline |
| **P1** | Integrate `piper-rs` + `ort` in Rust | 4h | Working synthesis |
| **P1** | Add ONNX Runtime `.so` to `jniLibs`; update build links | 2h | Compiling Android build |
| **P2** | Design `jni_adapter/tts_bridge.rs` with progress callbacks | 4h | JNI methods callable from Kotlin |
| **P2** | Implement Kotlin `TtsNativeBridge` + `AudioExporterService` | 4h | Foreground export working |
| **P3** | Add text chunker, cleaner, normalizer | 3h | Proper sentence chunking |
| **P4** | Compose UI: `TtsConfigScreen`, export bottom sheet | 4h | User-facing controls |
| **P5** | Cancellation, error recovery, memory profiling | 3h | Production-ready |

---
