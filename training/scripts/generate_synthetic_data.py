#!/usr/bin/env python3
"""
Synthetic Face Data Generator for Liveness Detection Training

Generates two classes:
- LIVE: Synthetic face-like patterns with natural variations
- SPOOF: Modified faces simulating presentation attacks (printed photos, screen replay)

No real face data is used. All images are procedurally generated.

Usage:
    python generate_synthetic_data.py --output-dir ./data --num-samples 1000 --image-size 80
"""

import os
import argparse
import numpy as np
from pathlib import Path

try:
    import cv2
except ImportError:
    print("OpenCV not installed. Install with: pip install opencv-python")
    exit(1)


class SyntheticFaceGenerator:
    """Generates synthetic face-like images for training."""
    
    def __init__(self, image_size: int = 80, seed: int = 42):
        self.image_size = image_size
        self.rng = np.random.RandomState(seed)
    
    def generate_live_face(self) -> np.ndarray:
        """Generate a synthetic 'live' face image."""
        img = np.zeros((self.image_size, self.image_size, 3), dtype=np.uint8)
        
        # Background with slight gradient
        for y in range(self.image_size):
            intensity = int(180 + 40 * (y / self.image_size))
            img[y, :] = [intensity - 20, intensity, intensity - 10]
        
        # Face oval (skin tone with variation)
        center_x = self.image_size // 2 + self.rng.randint(-5, 5)
        center_y = int(self.image_size * 0.45) + self.rng.randint(-3, 3)
        axes = (int(self.image_size * 0.35), int(self.image_size * 0.4))
        
        skin_r = 180 + self.rng.randint(-20, 20)
        skin_g = 150 + self.rng.randint(-15, 15)
        skin_b = 130 + self.rng.randint(-15, 15)
        
        cv2.ellipse(img, (center_x, center_y), axes, 0, 0, 360, 
                    (skin_b, skin_g, skin_r), -1)
        
        # Eyes
        eye_y = center_y - int(axes[1] * 0.15)
        eye_offset = int(axes[0] * 0.45)
        
        for eye_x in [center_x - eye_offset, center_x + eye_offset]:
            # Eye white
            cv2.ellipse(img, (eye_x, eye_y), (8, 5), 0, 0, 360, (240, 240, 245), -1)
            # Iris
            cv2.circle(img, (eye_x, eye_y), 4, (60, 40, 30), -1)
            # Pupil
            cv2.circle(img, (eye_x, eye_y), 2, (10, 10, 10), -1)
        
        # Nose
        nose_y = center_y + int(axes[1] * 0.1)
        cv2.line(img, (center_x, nose_y), (center_x - 3, nose_y + 8), 
                (skin_b - 20, skin_g - 20, skin_r - 20), 2)
        
        # Mouth
        mouth_y = center_y + int(axes[1] * 0.35)
        mouth_width = int(axes[0] * 0.4)
        
        # Add natural variation (slight smile/frown)
        mouth_curve = self.rng.randint(-3, 4)
        pts = np.array([
            [center_x - mouth_width, mouth_y],
            [center_x - mouth_width // 2, mouth_y + mouth_curve],
            [center_x, mouth_y + mouth_curve + 2],
            [center_x + mouth_width // 2, mouth_y + mouth_curve],
            [center_x + mouth_width, mouth_y]
        ], np.int32)
        cv2.polylines(img, [pts], False, (80, 60, 100), 2)
        
        # Add subtle noise for naturalness
        noise = self.rng.normal(0, 3, img.shape).astype(np.int16)
        img = np.clip(img.astype(np.int16) + noise, 0, 255).astype(np.uint8)
        
        return img
    
    def generate_spoof_photo(self, live_face: np.ndarray = None) -> np.ndarray:
        """Generate a 'printed photo' spoof face."""
        if live_face is None:
            live_face = self.generate_live_face()
        
        img = live_face.copy()
        
        # Add white border (paper frame)
        border = self.rng.randint(3, 8)
        img = cv2.copyMakeBorder(img, border, border, border, border,
                                cv2.BORDER_CONSTANT, value=(255, 255, 255))
        
        # Resize back to original size
        img = cv2.resize(img, (self.image_size, self.image_size))
        
        # Reduce contrast (flat lighting of printed photo)
        alpha = 0.7 + self.rng.random() * 0.2
        beta = 20 + self.rng.randint(0, 30)
        img = cv2.convertScaleAbs(img, alpha=alpha, beta=beta)
        
        # Add subtle moiré pattern (print artifact)
        if self.rng.random() > 0.5:
            freq = self.rng.randint(20, 40)
            x = np.arange(self.image_size)
            y = np.arange(self.image_size)
            xx, yy = np.meshgrid(x, y)
            pattern = np.sin(xx * freq / self.image_size * np.pi) * 5
            img[:, :, 0] = np.clip(img[:, :, 0].astype(np.int16) + pattern.astype(np.int16), 0, 255).astype(np.uint8)
            img[:, :, 1] = np.clip(img[:, :, 1].astype(np.int16) + pattern.astype(np.int16), 0, 255).astype(np.uint8)
            img[:, :, 2] = np.clip(img[:, :, 2].astype(np.int16) + pattern.astype(np.int16), 0, 255).astype(np.uint8)
        
        return img
    
    def generate_spoof_screen(self, live_face: np.ndarray = None) -> np.ndarray:
        """Generate a 'screen replay' spoof face."""
        if live_face is None:
            live_face = self.generate_live_face()
        
        img = live_face.copy()
        
        # Add blue tint (screen glow)
        img[:, :, 0] = np.clip(img[:, :, 0].astype(np.int16) + 15, 0, 255).astype(np.uint8)
        
        # Add scan lines
        for y in range(0, self.image_size, 2):
            img[y, :] = np.clip(img[y, :].astype(np.int16) - 10, 0, 255).astype(np.uint8)
        
        # Add slight brightness variation (screen refresh)
        brightness_shift = self.rng.randint(-20, 20)
        img = np.clip(img.astype(np.int16) + brightness_shift, 0, 255).astype(np.uint8)
        
        # Add reflection glare
        glare_x = self.rng.randint(self.image_size // 4, 3 * self.image_size // 4)
        glare_y = self.rng.randint(self.image_size // 4, 3 * self.image_size // 4)
        cv2.circle(img, (glare_x, glare_y), self.rng.randint(5, 15), 
                  (200, 200, 220), -1)
        img = cv2.GaussianBlur(img, (15, 15), 5)
        
        return img


def main():
    parser = argparse.ArgumentParser(description='Generate synthetic face data for liveness training')
    parser.add_argument('--output-dir', type=str, default='./data',
                       help='Output directory for generated images')
    parser.add_argument('--num-samples', type=int, default=1000,
                       help='Number of samples per class')
    parser.add_argument('--image-size', type=int, default=80,
                       help='Image size (NxN)')
    parser.add_argument('--seed', type=int, default=42,
                       help='Random seed for reproducibility')
    args = parser.parse_args()
    
    # Create output directories
    output_dir = Path(args.output_dir)
    live_dir = output_dir / 'live'
    spoof_dir = output_dir / 'spoof'
    
    live_dir.mkdir(parents=True, exist_ok=True)
    spoof_dir.mkdir(parents=True, exist_ok=True)
    
    print(f"Generating {args.num_samples} samples per class...")
    print(f"Image size: {args.image_size}x{args.image_size}")
    print(f"Output directory: {output_dir}")
    
    generator = SyntheticFaceGenerator(image_size=args.image_size, seed=args.seed)
    
    # Generate live faces
    print("\nGenerating LIVE faces...")
    for i in range(args.num_samples):
        img = generator.generate_live_face()
        cv2.imwrite(str(live_dir / f'live_{i:05d}.png'), img)
        if (i + 1) % 100 == 0:
            print(f"  Live: {i + 1}/{args.num_samples}")
    
    # Generate spoof faces (mix of photo and screen attacks)
    print("\nGenerating SPOOF faces...")
    for i in range(args.num_samples):
        live_face = generator.generate_live_face()
        
        if i % 2 == 0:
            img = generator.generate_spoof_photo(live_face)
            prefix = 'photo'
        else:
            img = generator.generate_spoof_screen(live_face)
            prefix = 'screen'
        
        cv2.imwrite(str(spoof_dir / f'spoof_{prefix}_{i:05d}.png'), img)
        if (i + 1) % 100 == 0:
            print(f"  Spoof: {i + 1}/{args.num_samples}")
    
    print(f"\nDataset generated successfully!")
    print(f"  LIVE: {args.num_samples} images in {live_dir}")
    print(f"  SPOOF: {args.num_samples} images in {spoof_dir}")
    print(f"  Total: {args.num_samples * 2} images")


if __name__ == '__main__':
    main()
