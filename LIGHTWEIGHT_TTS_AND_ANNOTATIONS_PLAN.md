# ReadRust — Lightweight Hybrid TTS & Annotation Pipeline Specification

**Version:** 2.0  
**Scope:** Lightweight Android TTS + Rust WAV Stitcher (`hound`), Highlights, Color Picker, Bookmarks, Pure Rust Annotated PDF Export (`lopdf`), Audio Sidecar Metadata  

---

## 1. Executive Summary

| Metric | Heavy Neural Approach (Piper + ONNX) | Lightweight Hybrid Approach (Android TTS + Rust Stitcher) |
|--------|────────────────────────────────────|──────────────────────────────────────────────────────────|
| **Native Library Footprint** | ~15MB per ABI (`libonnxruntime.so`) | **~250KB total** (`hound` crate) |
| **Voice Model Footprint** | 50–100MB per voice download | **0MB** (uses Android System Neural Voices) |
| **Export Mechanism** | Rust ONNX inference thread pool | Android `TextToSpeech.synthesizeToFile()` + Rust WAV Stitcher |
| **PDF Annotation Engine** | None | Pure Rust `lopdf` crate (`/Annots` & `/Outlines`) |
| **APK Footprint Impact** | +80–120MB | **+250KB (99.8% reduction)** |

---

## 2. System Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    ANDROID APPLICATION                      │
│                                                             │
│  ┌─────────────┐    ┌──────────────┐    ┌──────────────┐  │
│  │  Reader UI  │    │ Export Audio  │    │ Audio Player │  │
│  │ (Compose)   │    │ (Foreground)  │    │ (with marks) │  │
│  └──────┬──────┘    └──────┬───────┘    └──────┬───────┘  │
│         │                   │                     │          │
│         └───────────────────┼─────────────────────┘          │
│                             ▼                               │
│  ┌─────────────────────────────────────────────────────┐    │
│  │         TTS Abstraction Layer (Kotlin)               │    │
│  │  • System TtsEngine (synthesizeToFile to temp WAVs) │    │
│  │  • AudioExporterService (Foreground notification)   │    │
│  └──────────────────────────┬──────────────────────────┘    │
│                             │ JNI Bridge                     │
│                             ▼                               │
│  ┌─────────────────────────────────────────────────────┐    │
│  │         Rust Native Layer (pdfium_bridge.so)        │    │
│  │  • PDF Text Extraction & Page Rendering (PDFium)     │    │
│  │  • audio_engine/stitcher.rs: WAV concatenation     │    │
│  │  • annotations/pdf_writer.rs: lopdf PDF injection   │    │
│  │  • annotations/audio_meta.rs: JSON timestamp map    │    │
│  └─────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────┘
```

---

## 3. Implementation Phases

| Phase | Description | Deliverables |
|-------|-------------|--------------|
| **Phase 1** | Room Database Migration | `HighlightEntity`, `BookmarkEntity`, `HighlightDao`, `BookmarkDao` |
| **Phase 2** | Rust `audio_engine/stitcher.rs` | `hound` WAV file stitching & JNI `nativeStitchWavFiles` |
| **Phase 3** | Kotlin `TtsEngine` & `AudioExporterService` | Foreground Service synthesis pipeline with notification bar |
| **Phase 4** | Pure Rust `annotations/pdf_writer.rs` | `lopdf` PDF `/Annots` highlights & `/Outlines` bookmarks |
| **Phase 5** | Reader UI Annotations & Export Sheets | Sentence long-press, `ColorPickerSheet`, Compose Canvas overlay, `ExportOptionsSheet` |

---
