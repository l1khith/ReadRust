# ReadRust — Annotation & Export Pipeline Specification

**Extension to:** PiperTTS Integration Plan  
**Scope:** Highlighting, Color Picker, Bookmarks, Annotated PDF Export, Audio Chapter Metadata  

---

## 1. Feature Overview

| Feature | Current State | Target State |
|---------|--------------|--------------|
| **Text Highlighting** | ❌ Not implemented | ✅ Long-press selection → Color picker → Persisted overlay |
| **Color Picker** | ❌ Not implemented | ✅ Material3 `BottomSheet` with preset + custom colors |
| **Bookmarks** | ⚠️ Partial / UI-only | ✅ Persisted per page with labels; exported to PDF & Audio |
| **Annotated PDF Export** | ❌ Not implemented | ✅ New PDF with `/Annots` (highlights) + `/Outlines` (bookmarks) |
| **Audio Chapter Marks** | ❌ Not implemented | ✅ Bookmark timestamps embedded in WAV metadata + sidecar JSON |

---

## 2. Data Architecture (Room/SQLite)

### 2.1 New Entities

```kotlin
// HighlightEntity.kt
@Entity(
    tableName = "highlights",
    foreignKeys = [ForeignKey(
        entity = BookEntity::class,
        parentColumns = ["id"],
        childColumns = ["bookId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class HighlightEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val pageIndex: Int,
    val charStart: Int,          // Index into page text
    val charEnd: Int,
    val colorArgb: Int,          // Android ColorInt (0xFF2196F3)
    val note: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

// BookmarkEntity.kt
@Entity(
    tableName = "bookmarks",
    foreignKeys = [ForeignKey(
        entity = BookEntity::class,
        parentColumns = ["id"],
        childColumns = ["bookId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val pageIndex: Int,
    val label: String,           // e.g., "Chapter 1", "Important quote"
    val createdAt: Long = System.currentTimeMillis()
)
```

---

## 3. Rust Annotation Engine

### 3.1 Module Structure

```
rust/pdfium_bridge/src/
├── lib.rs                    # Add annotation JNI exports
├── pdfium/                   # Existing PDF read/render
├── annotations/              # NEW
│   ├── mod.rs
│   ├── types.rs              # Highlight, Bookmark structs
│   ├── pdf_writer.rs         # lopdf-based annotated PDF export
│   └── audio_meta.rs         # Bookmark → timestamp mapping for audio
└── jni_adapter/
    ├── annotation_bridge.rs  # JNI bindings
    └── tts_bridge.rs
```

### 3.2 Annotated PDF Export (`annotations/pdf_writer.rs`)

Uses **`lopdf`** (pure Rust) to clone the source PDF and inject `/Annots` (highlights) and `/Outlines` (bookmarks).

---

## 4. Implementation Roadmap (Annotations)

| Phase | Task | Deliverable |
|-------|------|-------------|
| **P0** | Room schema migration: `HighlightEntity`, `BookmarkEntity`, DAOs | DAOs & Entities |
| **P0** | ReaderScreen: Long-press sentence detection | Touch handler |
| **P0** | Color picker bottom sheet + highlight persistence | `ColorPickerSheet.kt` |
| **P1** | Canvas overlay for highlight rendering on bitmap | `ReaderScreen.kt` |
| **P1** | Bookmark button + label dialog in reader toolbar | Toolbar controls |
| **P2** | Add `lopdf` to Rust; build `annotations/pdf_writer.rs` | Rust PDF exporter |
| **P2** | JNI bridge for `nativeExportAnnotatedPdf` | JNI bindings |
| **P3** | Audio sidecar generation & timestamp mapping | `audio_meta.rs` |
| **P3** | Export UI: Bottom sheet with mode selection | `ExportOptionsSheet.kt` |
| **P4** | Integration testing & verification | Verified Android build |

---
