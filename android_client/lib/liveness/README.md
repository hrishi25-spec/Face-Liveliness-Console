# Dart liveness layer

- `pigeon/liveness.dart` (repo root `android_client/pigeon/`) — Pigeon API;
  regenerate both checked-in bindings with `./tool/generate_pigeon.sh`.
- `liveness_view_model.dart` — `ChangeNotifier` mirroring the native
  orchestrator state machine; exposes `captureEnabled` (PASSED + valid gate).
- `liveness_view.dart` — widget rendering preview, oval overlay, challenge
  prompts, hints, generic failure messages; all text resolved from i18n keys.

Wire-up sketch (resident capture screen):

```dart
final vm = LivenessViewModel(hostApi: PigeonLivenessHost());
// Register the generated callback receiver for this screen.
vm.bindFlutterApi();

LivenessView(
  viewModel: vm,
  onRetry: () => vm.startSession(LivenessRole.resident),
  onCancel: () => vm.cancelSession(),
);

// capture button:
onPressed: vm.captureEnabled && await vm.ensureGateValidForCapture()
    ? () => startFaceCapture()   // existing SBI capture flow (orchestration spec §5.1)
    : null,
```

Operator / supervisor login dialogs reuse the same widget with
`LivenessRole.operator` / `LivenessRole.supervisor` (analyse.md §6.2/§6.3: supervisor
policy is stricter by default — higher threshold, two challenges).
