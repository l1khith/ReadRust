// =============================================================================
// ReadRust — audio_engine/stitcher.rs
// Idiomatic, high-performance WAV file stitcher adhering to pro_rust_patterns_guide.md
// =============================================================================

use hound::{WavReader, WavSpec, WavWriter};
use std::path::Path;
use thiserror::Error;

#[derive(Error, Debug)]
pub enum StitchError {
    #[error("No input WAV files provided for stitching")]
    NoInputs,

    #[error("I/O error during audio stitching: {0}")]
    Io(#[from] std::io::Error),

    #[error("WAV decoding/encoding error: {0}")]
    Wav(#[from] hound::Error),

    #[error("Invalid audio format in file {path:?}: expected 16-bit PCM")]
    InvalidFormat { path: String },
}

pub type StitchResult<T> = Result<T, StitchError>;

/// Stitches multiple WAV audio chunk files into a single gapless WAV file.
/// Inputs: Slice of borrowed Path references.
/// Output: Consolidated 22.05kHz (or target sample rate) mono 16-bit PCM WAV file.
pub fn stitch_wav_files(
    input_paths: &[&Path],
    output_path: &Path,
    target_sample_rate: u32,
) -> StitchResult<()> {
    if input_paths.is_empty() {
        return Err(StitchError::NoInputs);
    }

    let spec = WavSpec {
        channels: 1,
        sample_rate: target_sample_rate,
        bits_per_sample: 16,
        sample_format: hound::SampleFormat::Int,
    };

    let mut writer = WavWriter::create(output_path, spec)?;
    let mut total_samples_written: u64 = 0;

    for path in input_paths {
        if !path.exists() {
            log::warn!("Stitcher skipping missing chunk file: {:?}", path);
            continue;
        }

        let mut reader = WavReader::open(path)?;
        let src_spec = reader.spec();

        // If sample rates and channels match target (16-bit mono), stream directly with zero allocation
        if src_spec.sample_rate == target_sample_rate && src_spec.channels == 1 && src_spec.bits_per_sample == 16 {
            for sample in reader.samples::<i16>() {
                let sample_val = sample?;
                writer.write_sample(sample_val)?;
                total_samples_written += 1;
            }
        } else {
            // Resample / downmix down to target rate mono
            let samples: Vec<i16> = reader.samples::<i16>()
                .collect::<Result<Vec<_>, _>>()?;

            if src_spec.channels > 1 {
                // Downmix stereo to mono
                let mono_samples: Vec<i16> = samples.chunks_exact(src_spec.channels as usize)
                    .map(|chunk| {
                        let sum: i32 = chunk.iter().map(|&s| s as i32).sum();
                        (sum / chunk.len() as i32) as i16
                    })
                    .collect();

                let resampled = resample_linear(&mono_samples, src_spec.sample_rate, target_sample_rate);
                for s in resampled {
                    writer.write_sample(s)?;
                    total_samples_written += 1;
                }
            } else {
                let resampled = resample_linear(&samples, src_spec.sample_rate, target_sample_rate);
                for s in resampled {
                    writer.write_sample(s)?;
                    total_samples_written += 1;
                }
            }
        }
    }

    writer.finalize()?;
    log::info!(
        "Successfully stitched {} chunks into {:?} ({} samples, {} Hz)",
        input_paths.len(),
        output_path,
        total_samples_written,
        target_sample_rate
    );

    Ok(())
}

/// Linear interpolation fallback for sample rate matching without external C dependencies
fn resample_linear(input: &[i16], from_rate: u32, to_rate: u32) -> Vec<i16> {
    if from_rate == to_rate || input.is_empty() {
        return input.to_vec();
    }

    let ratio = to_rate as f64 / from_rate as f64;
    let output_len = ((input.len() as f64) * ratio) as usize;
    let mut output = Vec::with_capacity(output_len);

    for i in 0..output_len {
        let src_idx = (i as f64) / ratio;
        let src_floor = src_idx.floor() as usize;
        let src_ceil = (src_floor + 1).min(input.len() - 1);
        let frac = src_idx - (src_floor as f64);

        if src_floor < input.len() {
            let v0 = input[src_floor] as f64;
            let v1 = input[src_ceil] as f64;
            let interpolated = v0 * (1.0 - frac) + v1 * frac;
            output.push(interpolated.clamp(-32768.0, 32767.0) as i16);
        }
    }

    output
}
