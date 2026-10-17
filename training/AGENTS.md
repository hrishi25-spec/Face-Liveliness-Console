# Liveness model training pipeline

This directory is its own project: a synthetic-data training pipeline for the MiniFASNet liveness model, written in Python/TensorFlow. It is not part of the Spring service, the shared engine, or the FastAPI backend, and nothing here is compiled or packaged by them.

- Generate **synthetic data only**. Never add real faces, biometric samples, or production identifiers to this tree, its outputs, or its logs; the pipeline's samples are procedurally generated and the repository's rules on biometrics apply in full.
- Run this pipeline in a **dedicated virtual environment**. Its dependencies are not pinned (there is no `requirements.txt` here yet), so installing TensorFlow/OpenCV into another project's environment — `pad_liveness_backend/` pins `opencv-python-headless==4.10.0.84` and `numpy==1.26.4` — can silently violate that project's lockfile. Adding a pinned requirements file is tracked as an open gap.
- Keep generated artifacts out of git: `training/data/`, `training/models/`, `*.tflite` and `*.keras` are gitignored, and the cleanup step (`scripts/cleanup_dataset.py --data-dir ./data --confirm`) must run before you consider a training run finished.
- Treat the model hand-off as **manual and unverified**: no build or deploy path in this repository consumes `models/minifasnet_liveness.tflite`, and the service's bundled model is separate. Do not describe a model as shipped, evaluated, or integrated without naming the artifact, its hash, and where it was measured.
- There is no CI job for this directory because there are no tests. If you add tests, pin the test dependencies and wire a job into `.github/workflows/ci.yml` beside the other projects' jobs.
- Update this directory's `README.md` when the setup, training, or cleanup steps change.
