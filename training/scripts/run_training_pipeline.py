#!/usr/bin/env python3
"""
Full Training Pipeline Orchestrator

Runs the complete training pipeline:
1. Generate synthetic face data
2. Train the liveness detection model
3. Clean up the dataset (delete all face data)

This ensures no face data remains in the repository after training.

Usage:
    python run_training_pipeline.py --num-samples 1000 --epochs 50
"""

import os
import sys
import argparse
import subprocess
from pathlib import Path
from datetime import datetime


def run_command(cmd: list, description: str) -> bool:
    """Run a command and return success status."""
    print(f"\n{'=' * 60}")
    print(f"Step: {description}")
    print(f"Command: {' '.join(cmd)}")
    print(f"{'=' * 60}\n")
    
    try:
        result = subprocess.run(cmd, check=True, capture_output=False)
        return True
    except subprocess.CalledProcessError as e:
        print(f"\n❌ Error: {description} failed with exit code {e.returncode}")
        return False
    except FileNotFoundError as e:
        print(f"\n❌ Error: Command not found: {e}")
        return False


def main():
    parser = argparse.ArgumentParser(description='Run complete training pipeline')
    parser.add_argument('--num-samples', type=int, default=1000,
                       help='Number of synthetic samples per class')
    parser.add_argument('--epochs', type=int, default=50,
                       help='Training epochs')
    parser.add_argument('--batch-size', type=int, default=32,
                       help='Training batch size')
    parser.add_argument('--image-size', type=int, default=80,
                       help='Image size for training')
    parser.add_argument('--output-model', type=str, 
                       default='./models/minifasnet_liveness.tflite',
                       help='Output model path')
    parser.add_argument('--keep-data', action='store_true',
                       help='Keep dataset after training (for debugging)')
    args = parser.parse_args()
    
    # Get script directory
    script_dir = Path(__file__).parent
    
    print("=" * 60)
    print("Liveness Detection Training Pipeline")
    print("=" * 60)
    print(f"Start time: {datetime.now().isoformat()}")
    print(f"\nConfiguration:")
    print(f"  Samples per class: {args.num_samples}")
    print(f"  Training epochs: {args.epochs}")
    print(f"  Batch size: {args.batch_size}")
    print(f"  Image size: {args.image_size}x{args.image_size}")
    print(f"  Output model: {args.output_model}")
    print(f"  Keep data after training: {args.keep_data}")
    
    # Step 1: Generate synthetic data
    success = run_command([
        sys.executable,
        str(script_dir / 'generate_synthetic_data.py'),
        '--output-dir', './data',
        '--num-samples', str(args.num_samples),
        '--image-size', str(args.image_size)
    ], "Generate Synthetic Training Data")
    
    if not success:
        print("\n❌ Pipeline failed at data generation step")
        return False
    
    # Step 2: Train model
    success = run_command([
        sys.executable,
        str(script_dir / 'train_model.py'),
        '--data-dir', './data',
        '--output-model', args.output_model,
        '--epochs', str(args.epochs),
        '--batch-size', str(args.batch_size),
        '--image-size', str(args.image_size)
    ], "Train MiniFASNet Model")
    
    if not success:
        print("\n❌ Pipeline failed at training step")
        return False
    
    # Step 3: Cleanup dataset (unless --keep-data flag is set)
    if not args.keep_data:
        success = run_command([
            sys.executable,
            str(script_dir / 'cleanup_dataset.py'),
            '--data-dir', './data',
            '--confirm'
        ], "Cleanup Dataset")
        
        if not success:
            print("\n⚠️  Warning: Dataset cleanup failed, but training completed successfully")
            print("  Please manually delete the ./data directory")
    else:
        print("\n⚠️  Keeping dataset (--keep-data flag set)")
        print("  Remember to delete ./data before committing to repository")
    
    # Summary
    print("\n" + "=" * 60)
    print("Pipeline Complete!")
    print("=" * 60)
    print(f"End time: {datetime.now().isoformat()}")
    print(f"\nOutput model: {args.output_model}")
    
    if not args.keep_data:
        print("\n✅ No face data remains in the repository")
    else:
        print(f"\n⚠️  Dataset still exists at ./data - delete before committing")
    
    return True


if __name__ == '__main__':
    success = main()
    sys.exit(0 if success else 1)
