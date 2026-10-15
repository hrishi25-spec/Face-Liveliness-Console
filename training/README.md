# Liveness Detection Model Training

This directory contains the training pipeline for fine-tuning the MiniFASNet liveness detection model using **synthetic face data**.

## ⚠️ Important: No Real Face Data

This training pipeline uses **100% synthetic data** generated procedurally. No real human faces are used at any point. This ensures:

- ✅ No privacy concerns (GDPR, CCPA compliant)
- ✅ No consent requirements
- ✅ No biometric data in the repository
- ✅ Fully auditable training process

## Quick Start

### Run Full Pipeline (Recommended)

```bash
cd training

# Install dependencies
pip install tensorflow opencv-python numpy

# Run complete pipeline: generate → train → cleanup
python scripts/run_training_pipeline.py --num-samples 1000 --epochs 50
```

This will:
1. Generate 1000 synthetic "live" faces and 1000 synthetic "spoof" faces
2. Train the MiniFASNet model for 50 epochs
3. Save the trained model to `./models/minifasnet_liveness.tflite`
4. **Delete all training data** automatically

### Manual Steps (For Debugging)

```bash
# Step 1: Generate synthetic data
python scripts/generate_synthetic_data.py --output-dir ./data --num-samples 1000

# Step 2: Train model
python scripts/train_model.py --data-dir ./data --output-model ./models/minifasnet_liveness.tflite

# Step 3: Cleanup (CRITICAL - delete face data)
python scripts/cleanup_dataset.py --data-dir ./data --confirm
```

## Dataset Generation

The synthetic data generator creates two classes:

### LIVE Faces (Label: 1)
- Synthetic face-like patterns with natural variations
- Skin tone variations
- Eye/nose/mouth placement with slight randomness
- Subtle noise for naturalness

### SPOOF Faces (Label: 0)
Two types of presentation attacks:

1. **Printed Photo** (50% of spoof samples)
   - White border (paper frame)
   - Reduced contrast (flat lighting)
   - Moiré patterns (print artifacts)

2. **Screen Replay** (50% of spoof samples)
   - Blue tint (screen glow)
   - Scan lines
   - Reflection glare
   - Brightness variations

## Model Architecture

The trained model uses a lightweight CNN architecture inspired by MiniFASNet:

- **Input**: 80×80 RGB image
- **Parameters**: ~200K (suitable for mobile)
- **Output**: Binary classification (live=1, spoof=0)
- **Format**: TFLite (INT8 quantized)

## Configuration Options

| Parameter | Default | Description |
|-----------|---------|-------------|
| `--num-samples` | 1000 | Number of images per class |
| `--epochs` | 50 | Training epochs |
| `--batch-size` | 32 | Training batch size |
| `--image-size` | 80 | Input image dimensions |
| `--keep-data` | false | Keep dataset after training |

## Output

After training, you'll have:

```
training/
├── models/
│   ├── minifasnet_liveness.tflite    # Quantized TFLite model
│   └── minifasnet_liveness.keras     # Full Keras model (reference)
└── data/                             # DELETED after training
```

## Git Safety

The `.gitignore` is configured to prevent:

- `training/data/` - Synthetic face images
- `training/models/` - Trained model files
- `*.tflite` - TFLite model files
- `*.keras` - Keras model files

**Never commit face data to the repository!**

## Troubleshooting

### "No module named tensorflow"
```bash
pip install tensorflow  # CPU-only
# or
pip install tensorflow-gpu  # With GPU support
```

### "No module named cv2"
```bash
pip install opencv-python
```

### Dataset not deleted
```bash
# Manual cleanup
python scripts/cleanup_dataset.py --data-dir ./data --confirm
# or
rm -rf ./data
```

## License

This training pipeline is part of the MOSIP Face Liveness & PAD Service.
See the main project LICENSE for details.
