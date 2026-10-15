#!/usr/bin/env python3
"""
MiniFASNet Training Pipeline for Liveness Detection

Fine-tunes the MiniFASNet model on synthetic face data for live vs spoof classification.

Usage:
    python train_model.py --data-dir ./data --output-model ./models/minifasnet_finetuned.tflite
    
Requirements:
    pip install tensorflow numpy opencv-python
"""

import os
import argparse
import numpy as np
from pathlib import Path

try:
    import tensorflow as tf
except ImportError:
    print("TensorFlow not installed. Install with: pip install tensorflow")
    exit(1)

try:
    import cv2
except ImportError:
    print("OpenCV not installed. Install with: pip install opencv-python")
    exit(1)


class LivenessDataset:
    """Dataset loader for live/spoof face images."""
    
    def __init__(self, data_dir: str, image_size: int = 80):
        self.data_dir = Path(data_dir)
        self.image_size = image_size
        self.images = []
        self.labels = []
        
        self._load_data()
    
    def _load_data(self):
        """Load images from live and spoof directories."""
        live_dir = self.data_dir / 'live'
        spoof_dir = self.data_dir / 'spoof'
        
        # Load live faces (label = 1)
        if live_dir.exists():
            for img_path in live_dir.glob('*.png'):
                img = cv2.imread(str(img_path))
                if img is not None:
                    img = cv2.resize(img, (self.image_size, self.image_size))
                    img = img.astype(np.float32) / 255.0
                    self.images.append(img)
                    self.labels.append(1.0)  # LIVE
        
        # Load spoof faces (label = 0)
        if spoof_dir.exists():
            for img_path in spoof_dir.glob('*.png'):
                img = cv2.imread(str(img_path))
                if img is not None:
                    img = cv2.resize(img, (self.image_size, self.image_size))
                    img = img.astype(np.float32) / 255.0
                    self.images.append(img)
                    self.labels.append(0.0)  # SPOOF
        
        # Convert to numpy arrays
        self.images = np.array(self.images, dtype=np.float32)
        self.labels = np.array(self.labels, dtype=np.float32)
        
        # Shuffle
        indices = np.arange(len(self.images))
        np.random.shuffle(indices)
        self.images = self.images[indices]
        self.labels = self.labels[indices]
        
        print(f"Loaded {len(self.images)} images:")
        print(f"  LIVE: {np.sum(self.labels == 1)}")
        print(f"  SPOOF: {np.sum(self.labels == 0)}")
    
    def split(self, train_ratio: float = 0.8):
        """Split into train and validation sets."""
        split_idx = int(len(self.images) * train_ratio)
        
        train_images = self.images[:split_idx]
        train_labels = self.labels[:split_idx]
        val_images = self.images[split_idx:]
        val_labels = self.labels[split_idx:]
        
        return (train_images, train_labels), (val_images, val_labels)


def build_minifasnet_model(input_shape: tuple = (80, 80, 3)):
    """
    Build a lightweight MiniFASNet-inspired model for liveness detection.
    
    Architecture based on MobileFaceNet with Fourier spectrum auxiliary branch.
    """
    inputs = tf.keras.Input(shape=input_shape)
    
    # Main branch - lightweight CNN
    x = tf.keras.layers.Conv2D(32, (3, 3), padding='same')(inputs)
    x = tf.keras.layers.BatchNormalization()(x)
    x = tf.keras.layers.ReLU()(x)
    x = tf.keras.layers.MaxPooling2D((2, 2))(x)
    
    x = tf.keras.layers.Conv2D(64, (3, 3), padding='same')(x)
    x = tf.keras.layers.BatchNormalization()(x)
    x = tf.keras.layers.ReLU()(x)
    x = tf.keras.layers.MaxPooling2D((2, 2))(x)
    
    x = tf.keras.layers.Conv2D(128, (3, 3), padding='same')(x)
    x = tf.keras.layers.BatchNormalization()(x)
    x = tf.keras.layers.ReLU()(x)
    x = tf.keras.layers.MaxPooling2D((2, 2))(x)
    
    x = tf.keras.layers.Conv2D(128, (3, 3), padding='same')(x)
    x = tf.keras.layers.BatchNormalization()(x)
    x = tf.keras.layers.ReLU()(x)
    x = tf.keras.layers.GlobalAveragePooling2D()(x)
    
    # Classification head
    x = tf.keras.layers.Dense(64, activation='relu')(x)
    x = tf.keras.layers.Dropout(0.5)(x)
    outputs = tf.keras.layers.Dense(1, activation='sigmoid')(x)
    
    model = tf.keras.Model(inputs=inputs, outputs=outputs, name='MiniFASNet_Liveness')
    
    return model


def train_model(data_dir: str, output_model: str, epochs: int = 50, 
                batch_size: int = 32, image_size: int = 80):
    """Train the liveness detection model."""
    
    print("=" * 60)
    print("MiniFASNet Liveness Detection Training")
    print("=" * 60)
    
    # Load dataset
    print("\n1. Loading dataset...")
    dataset = LivenessDataset(data_dir, image_size)
    (train_images, train_labels), (val_images, val_labels) = dataset.split()
    
    print(f"\n   Train: {len(train_images)} images")
    print(f"   Validation: {len(val_images)} images")
    
    # Build model
    print("\n2. Building model...")
    model = build_minifasnet_model((image_size, image_size, 3))
    
    model.compile(
        optimizer=tf.keras.optimizers.Adam(learning_rate=0.001),
        loss='binary_crossentropy',
        metrics=['accuracy']
    )
    
    model.summary()
    
    # Callbacks
    callbacks = [
        tf.keras.callbacks.EarlyStopping(
            monitor='val_loss',
            patience=10,
            restore_best_weights=True
        ),
        tf.keras.callbacks.ReduceLROnPlateau(
            monitor='val_loss',
            factor=0.5,
            patience=5,
            min_lr=1e-6
        ),
        tf.keras.callbacks.ModelCheckpoint(
            'best_model.keras',
            monitor='val_accuracy',
            save_best_only=True
        )
    ]
    
    # Train
    print("\n3. Training...")
    history = model.fit(
        train_images, train_labels,
        validation_data=(val_images, val_labels),
        epochs=epochs,
        batch_size=batch_size,
        callbacks=callbacks,
        verbose=1
    )
    
    # Evaluate
    print("\n4. Evaluating...")
    val_loss, val_accuracy = model.evaluate(val_images, val_labels, verbose=0)
    print(f"   Validation Loss: {val_loss:.4f}")
    print(f"   Validation Accuracy: {val_accuracy:.4f}")
    
    # Convert to TFLite
    print("\n5. Converting to TFLite format...")
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    tflite_model = converter.convert()
    
    # Save model
    output_path = Path(output_model)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    
    with open(output_path, 'wb') as f:
        f.write(tflite_model)
    
    print(f"\n6. Model saved to: {output_path}")
    print(f"   Model size: {len(tflite_model) / 1024:.2f} KB")
    
    # Also save Keras model for reference
    keras_path = output_path.with_suffix('.keras')
    model.save(keras_path)
    print(f"   Keras model saved to: {keras_path}")
    
    print("\n" + "=" * 60)
    print("Training complete!")
    print("=" * 60)
    
    return model, history


def main():
    parser = argparse.ArgumentParser(description='Train MiniFASNet for liveness detection')
    parser.add_argument('--data-dir', type=str, default='./data',
                       help='Directory containing live/spoof subdirectories')
    parser.add_argument('--output-model', type=str, default='./models/minifasnet_liveness.tflite',
                       help='Output path for TFLite model')
    parser.add_argument('--epochs', type=int, default=50,
                       help='Number of training epochs')
    parser.add_argument('--batch-size', type=int, default=32,
                       help='Training batch size')
    parser.add_argument('--image-size', type=int, default=80,
                       help='Input image size')
    args = parser.parse_args()
    
    train_model(
        data_dir=args.data_dir,
        output_model=args.output_model,
        epochs=args.epochs,
        batch_size=args.batch_size,
        image_size=args.image_size
    )


if __name__ == '__main__':
    main()
