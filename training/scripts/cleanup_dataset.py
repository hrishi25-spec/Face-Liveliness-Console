#!/usr/bin/env python3
"""
Dataset Cleanup Script

Safely deletes the synthetic training dataset after model training.
Ensures no face data remains in the repository.

Usage:
    python cleanup_dataset.py --data-dir ./data --confirm
"""

import os
import argparse
import shutil
import json
from pathlib import Path
from datetime import datetime


def get_directory_size(path: Path) -> int:
    """Get total size of directory in bytes."""
    total = 0
    for dirpath, dirnames, filenames in os.walk(path):
        for f in filenames:
            fp = os.path.join(dirpath, f)
            total += os.path.getsize(fp)
    return total


def format_size(size_bytes: int) -> str:
    """Format bytes to human readable string."""
    for unit in ['B', 'KB', 'MB', 'GB']:
        if size_bytes < 1024.0:
            return f"{size_bytes:.2f} {unit}"
        size_bytes /= 1024.0
    return f"{size_bytes:.2f} TB"


def count_files(path: Path) -> int:
    """Count total files in directory."""
    count = 0
    for dirpath, dirnames, filenames in os.walk(path):
        count += len(filenames)
    return count


def cleanup_dataset(data_dir: str, force: bool = False):
    """Delete the synthetic training dataset."""
    
    data_path = Path(data_dir)
    
    print("=" * 60)
    print("Dataset Cleanup Script")
    print("=" * 60)
    
    # Check if directory exists
    if not data_path.exists():
        print(f"\n✓ Dataset directory not found: {data_path}")
        print("  Nothing to clean up.")
        return True
    
    # Get statistics before deletion
    num_files = count_files(data_path)
    dir_size = get_directory_size(data_path)
    
    print(f"\nDataset to delete:")
    print(f"  Directory: {data_path.absolute()}")
    print(f"  Files: {num_files}")
    print(f"  Size: {format_size(dir_size)}")
    
    # List contents
    print(f"\nContents:")
    for item in data_path.iterdir():
        if item.is_dir():
            sub_files = count_files(item)
            sub_size = get_directory_size(item)
            print(f"  {item.name}/: {sub_files} files, {format_size(sub_size)}")
        else:
            print(f"  {item.name}: {format_size(item.stat().st_size)}")
    
    # Confirm deletion
    if not force:
        print("\n⚠️  WARNING: This will permanently delete all training data!")
        response = input("\nType 'DELETE' to confirm deletion: ")
        if response.strip() != 'DELETE':
            print("\n❌ Deletion cancelled.")
            return False
    
    # Perform deletion
    print("\nDeleting dataset...")
    
    try:
        # Remove directory tree
        shutil.rmtree(data_path)
        
        # Also remove any __pycache__ directories
        for cache_dir in data_path.parent.rglob('__pycache__'):
            if cache_dir.exists():
                shutil.rmtree(cache_dir)
        
        # Log the cleanup
        log_entry = {
            'timestamp': datetime.now().isoformat(),
            'directory': str(data_path.absolute()),
            'files_deleted': num_files,
            'size_deleted': format_size(dir_size)
        }
        
        log_file = data_path.parent / 'cleanup_log.json'
        logs = []
        if log_file.exists():
            with open(log_file, 'r') as f:
                logs = json.load(f)
        
        logs.append(log_entry)
        
        with open(log_file, 'w') as f:
            json.dump(logs, f, indent=2)
        
        print(f"\n✅ Dataset deleted successfully!")
        print(f"  Deleted {num_files} files ({format_size(dir_size)})")
        print(f"  Cleanup logged to: {log_file}")
        
        return True
        
    except Exception as e:
        print(f"\n❌ Error deleting dataset: {e}")
        return False


def main():
    parser = argparse.ArgumentParser(description='Clean up synthetic training dataset')
    parser.add_argument('--data-dir', type=str, default='./data',
                       help='Dataset directory to delete')
    parser.add_argument('--confirm', action='store_true',
                       help='Skip confirmation prompt')
    args = parser.parse_args()
    
    success = cleanup_dataset(data_dir=args.data_dir, force=args.confirm)
    exit(0 if success else 1)


if __name__ == '__main__':
    main()
